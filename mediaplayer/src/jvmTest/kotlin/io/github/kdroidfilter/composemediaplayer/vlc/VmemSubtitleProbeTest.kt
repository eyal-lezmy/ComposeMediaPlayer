package io.github.kdroidfilter.composemediaplayer.vlc

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does a subtitle actually reach the callback surface? Measured on a **frame**, never on player state.
 *
 * The fixture is a black video carrying one `S_TEXT/UTF8` track, so any lit pixel in the delivered
 * buffer is drawn content and nothing else. Rebuild it with:
 *
 * ```
 * printf '1\n00:00:02,000 --> 00:00:18,000\nHELLO SUBTITLE\n\n' > sub.srt
 * ffmpeg -f lavfi -i color=c=black:s=1280x720:r=25:d=20 -f lavfi -i anullsrc=r=48000:cl=stereo \
 *        -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac black.mp4
 * ffmpeg -i black.mp4 -i sub.srt -c copy -c:s srt black-with-subtitle.mkv
 * ```
 *
 * What it guards: libVLC blends subpictures into the *source* chroma whenever the requested buffer is
 * no larger than the source, and cannot blend into a hardware picture — so the caption is silently
 * dropped. [VlcSurfaceFormat.Planar]'s margin is what moves the blend after the conversion. Lose the
 * margin, ask for a packed buffer, or let a platform stop honouring the requested chroma, and this
 * test goes back to zero. See docs/adr/0041-subtitles-blend-into-an-i420-surface.md.
 */
class VmemSubtitleProbeTest {

    @Test
    fun aSubtitleReachesThePlanarSurface() {
        val fixture = fixtureFile()
        val result = probe(fixture, planar = true)
        println("planar probe: $result")
        assertTrue(result.frames > 0, "no frame was delivered at all")
        assertTrue(
            result.litPixels > 0,
            "the caption never reached the surface (lit pixels: ${result.litPixels}) — libVLC is " +
                "dropping the subpicture again. Cause and measurements: " +
                "docs/adr/0041-subtitles-blend-into-an-i420-surface.md. If this broke on a libVLC " +
                "version bump, the checklist is docs/tasks/pending/153-libvlc-4-migration.md.",
        )
    }

    /**
     * Free-form run against any media, for the next investigation: `OKAMP_SPU_PROBE_FILE` picks the
     * media, `OKAMP_VLC_ARGS` / `OKAMP_VLC_MEDIA_OPTS` change libVLC's configuration,
     * `OKAMP_SPU_PROBE_PAD` and `OKAMP_SPU_PROBE_CHROMA` the buffer, `OKAMP_SPU_PROBE_SEEK_MS` the
     * moment sampled, `OKAMP_SPU_PROBE_OUT` writes the frame as PNG. Unset, it no-ops.
     */
    @Test
    fun exploratoryProbe() {
        val path = System.getenv("OKAMP_SPU_PROBE_FILE")?.takeIf { it.isNotBlank() } ?: run {
            println("OKAMP_SPU_PROBE_FILE unset — skipping the exploratory probe")
            return
        }
        println(
            "exploratory probe: " + probe(
                file = File(path),
                planar = System.getenv("OKAMP_SPU_PROBE_CHROMA").equals("I420", ignoreCase = true),
                pad = System.getenv("OKAMP_SPU_PROBE_PAD")?.toIntOrNull() ?: 0,
                instanceArgs = System.getenv("OKAMP_VLC_ARGS").orEmpty().split(' ').filter { it.isNotBlank() },
                mediaOptions = System.getenv("OKAMP_VLC_MEDIA_OPTS").orEmpty().split(' ').filter { it.isNotBlank() },
                measureMs = System.getenv("OKAMP_SPU_PROBE_MEASURE_MS")?.toLongOrNull() ?: 12_000L,
                seekMs = System.getenv("OKAMP_SPU_PROBE_SEEK_MS")?.toLongOrNull(),
                png = System.getenv("OKAMP_SPU_PROBE_OUT")?.takeIf { it.isNotBlank() }?.let(::File),
                logAll = System.getenv("OKAMP_SPU_PROBE_LOG_ALL")?.isNotBlank() == true,
            ),
        )
    }

    private data class ProbeResult(
        val frames: Int,
        val litPixels: Int,
        val fps: Double,
        val lost: Int,
        val spuTrack: Int,
        val size: String,
    ) {
        override fun toString() =
            "$size frames=$frames fps=${"%.1f".format(fps)} lost=$lost spu=$spuTrack lit=$litPixels"
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun probe(
        file: File,
        planar: Boolean,
        pad: Int = if (planar) 2 else 0,
        instanceArgs: List<String> = emptyList(),
        mediaOptions: List<String> = emptyList(),
        measureMs: Long = 4_000L,
        seekMs: Long? = null,
        png: File? = null,
        logAll: Boolean = false,
    ): ProbeResult {
        // Natives first (libvlccore preload, VLC_PLUGIN_PATH), then an instance this test controls.
        VlcNativeInit.factory()
        val factory = MediaPlayerFactory(*(listOf("--no-plugins-cache", "--verbose=2") + instanceArgs).toTypedArray())
        val interesting = listOf("spu", "blend", "text", "freetype", "vout", "vmem", "subs", "codec")
        factory.application().newLog()?.apply {
            setLevel(LogLevel.DEBUG)
            addLogListener { level, module, _, _, _, _, _, message ->
                val text = message?.trim().orEmpty()
                val mod = module ?: "?"
                if (logAll || interesting.any { mod.contains(it, true) || text.contains(it, true) }) {
                    println("vlc[${level?.name}] $mod: $text")
                }
            }
        }
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

        val frames = AtomicInteger(0)
        val playing = AtomicBoolean(false)
        var width = 0
        var height = 0
        var best = 0
        var bestPixels: IntArray? = null
        var lastPixels: IntArray? = null

        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                width = (sourceWidth + pad + 1) and 1.inv()
                height = (sourceHeight + pad + 1) and 1.inv()
                return if (planar) {
                    BufferFormat(
                        "I420", width, height,
                        intArrayOf(width, width / 2, width / 2),
                        intArrayOf(height, height / 2, height / 2),
                    )
                } else {
                    RV32BufferFormat(width, height)
                }
            }

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCallback = RenderCallback { _, buffers, format ->
            frames.incrementAndGet()
            val buffer = buffers[0].duplicate().apply { rewind() }
            val w = format.width
            val h = format.height
            val pitch = format.pitches[0]
            val pixels = IntArray(w * h)
            val row = ByteArray(pitch)
            var lit = 0
            for (y in 0 until h) {
                buffer.position(y * pitch)
                buffer.get(row, 0, pitch)
                for (x in 0 until w) {
                    // Planar: plane 0 is luma, so a lit pixel is a bright one. Packed: BGRA.
                    val value = if (planar) {
                        val luma = row[x].toInt() and 0xFF
                        pixels[y * w + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                        luma
                    } else {
                        val b = row[x * 4].toInt() and 0xFF
                        val g = row[x * 4 + 1].toInt() and 0xFF
                        val r = row[x * 4 + 2].toInt() and 0xFF
                        pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        maxOf(r, g, b)
                    }
                    if (value > LIT) lit++
                }
            }
            synchronized(this) {
                lastPixels = pixels
                if (lit > best) {
                    best = lit
                    bestPixels = pixels
                }
            }
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) { playing.set(true) }
        })

        player.audio().setVolume(0)
        check(player.media().play(file.absolutePath, *mediaOptions.toTypedArray())) {
            "vlcj play() returned false for $file"
        }
        val ready = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < ready && !playing.get()) Thread.sleep(100)
        Thread.sleep(500)

        val tracks = runCatching { player.subpictures().trackDescriptions() }.getOrNull().orEmpty()
        tracks.firstOrNull { it.id() >= 0 }?.let { player.subpictures().setTrack(it.id()) }

        if (seekMs != null) {
            player.controls().setTime(seekMs)
            Thread.sleep(800)
        }
        // Rate over a fixed wall-clock window: a pipeline that cannot keep up still advances the
        // clock (the audio drives it) and simply delivers fewer pictures.
        Thread.sleep(1_000)
        fun lostCount() = runCatching { player.media().info()?.statistics()?.picturesLost() ?: -1 }.getOrDefault(-1)
        val wall0 = System.currentTimeMillis()
        val frames0 = frames.get()
        val lost0 = lostCount()
        Thread.sleep(measureMs)
        val wallMs = System.currentTimeMillis() - wall0
        val delivered = frames.get() - frames0
        val result = ProbeResult(
            frames = frames.get(),
            litPixels = best,
            fps = delivered * 1000.0 / wallMs,
            lost = (lostCount() - lost0).coerceAtLeast(0),
            spuTrack = runCatching { player.subpictures().track() }.getOrDefault(-99),
            size = "${width}x$height",
        )

        val pixels = if (seekMs != null) lastPixels else bestPixels
        if (png != null && pixels != null && width > 0 && height > 0) {
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                .also { it.setRGB(0, 0, width, height, pixels, 0, width) }
                .let { ImageIO.write(it, "png", png) }
            println("frame written to $png")
        }

        player.controls().stop()
        player.release()
        factory.release()
        return result
    }

    /** libVLC opens a path, not a stream, so the packaged fixture is unpacked next to the build. */
    private fun fixtureFile(): File {
        val out = File.createTempFile("black-with-subtitle", ".mkv").apply { deleteOnExit() }
        val stream = checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) { "$FIXTURE missing" }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private companion object {
        const val FIXTURE = "black-with-subtitle.mkv"

        /** Above this, a channel is lit rather than black — the fixture's background is 0. */
        const val LIT = 40
    }
}
