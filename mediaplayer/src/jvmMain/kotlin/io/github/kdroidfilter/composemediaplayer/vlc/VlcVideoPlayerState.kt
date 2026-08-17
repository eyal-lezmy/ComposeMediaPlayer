package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * Scale of [VideoPlayerState.sliderPos] and [VideoPlayerState.seekTo]: permille of the media, as
 * every other backend in this library reports it (the interface KDoc's `0f..1f` is stale).
 */
private const val SLIDER_SCALE = 1000f

/**
 * How often libVLC's own counters are sampled while diagnostics are on. Two seconds keeps the trace
 * readable across a minute-long incident while still bracketing a stop to within one sample.
 */
private const val STATS_SAMPLE_MILLIS = 2_000L

/**
 * How often a *healthy* stream writes a counter line. The sampler still looks every
 * [STATS_SAMPLE_MILLIS], so the instant input stops is still caught within two seconds; this only
 * governs the heartbeat, which is what would otherwise fill the file during normal playback.
 */
private const val STATS_REPORT_MILLIS = 30_000L

/**
 * Per-media options for [VlcVideoPlayerState.openUri]. Empty today, and deliberately documented as
 * such: two plausible options were measured against an Xtream Codes catch-up archive whose input
 * stopped after 16-21 MB on every seeked replay, and **neither did anything**.
 *
 * - `:http-reconnect` — no reconnection ever happened: four dead sessions, zero resumed reads.
 * - `:prefetch-buffer-size=65536` — the 16 MiB default sat exactly where the input stopped, which
 *   looked conclusive; at 64 MiB the next replays still died at 13.0 MB and 20.3 MB. The option was
 *   accepted (no "option does not exist"), so the experiment did run.
 *
 * The real cause was on the application's side: a second connection (an availability probe) against
 * an account the panel limits to one, which the CDN answered by closing the stream — a packet
 * capture shows the `FIN` coming from the server while that probe was open. Nothing in libVLC's
 * options addresses that, which is why this list is empty rather than hopeful.
 */
private fun mediaOptionsFor(uri: String): Array<String> = emptyArray()

/** libVLC's "no track" id: what `setTrack` takes to turn a track off, and what `track()` reads back then. */
private const val TRACK_DISABLED = -1

/**
 * How much bigger than the source the planar buffer is asked for.
 *
 * libVLC blends subpictures *before* the display conversion whenever the display format is no larger
 * than the source (`video_output.c:1008`, `do_early_spu`), and that early blend cannot write into a
 * hardware picture. Two pixels are enough to fail that test and move the blend after the conversion,
 * where the caption lands in the frame we are handed. See ADR 0041.
 *
 * **This is a libVLC 3 heuristic, not an API contract.** libVLC 4 replaced that expression with one
 * that has no size term, so the margin buys nothing there and captions disappear silently. Before
 * bumping libVLC, read `docs/tasks/pending/153-libvlc-4-migration.md` — and trust
 * `VmemSubtitleProbeTest`, which fails on a frame rather than on a hunch.
 */
private const val SPU_BLEND_MARGIN_PX = 2

/**
 * Language libVLC advertises for a track. Its descriptions read `"Track 1 - [English]"`, or plainly
 * `"English"` when the demuxer only knows a name — the bracketed part, when there is one, is the
 * language and the rest is libVLC's own numbering, which the UI supplies itself.
 */
internal fun trackLanguageOf(description: String?): String {
    val text = description?.trim().orEmpty()
    val open = text.lastIndexOf('[')
    val close = text.lastIndexOf(']')
    return if (open in 0 until close) text.substring(open + 1, close).trim() else text
}

private fun TrackDescription.asAudioTrack() = AudioTrack(
    label = description()?.trim().orEmpty(),
    language = trackLanguageOf(description()),
    id = id().toString(),
)

/**
 * [SubtitleTrack.src] is a URL for an external subtitle file; an embedded subpicture track has none,
 * so it carries libVLC's elementary-stream id instead — the handle `selectSubtitleTrack` hands back
 * to `subpictures().setTrack`. Nothing in this backend fetches `src`: libVLC's own video output
 * blends the subpictures into the frame.
 */
private fun TrackDescription.asSubtitleTrack() = SubtitleTrack(
    label = description()?.trim().orEmpty(),
    language = trackLanguageOf(description()),
    src = id().toString(),
)

/**
 * A [VideoPlayerState] backed by the **bundled** libVLC, so containers/codecs a platform backend can't
 * demux (mkv/HEVC/AC3, etc.) still play. Video is rendered through vlcj's callback surface: libVLC hands
 * RV32 (BGRA) frames to [RenderCallback.display], which are copied into a Skia bitmap and published as
 * an [ImageBitmap] for [VlcVideoPlayerSurface] to draw. Playback/UI state is driven off libVLC events,
 * not polling.
 */
/**
 * What the callback surface asks libVLC to hand it.
 *
 * [Packed] is BGRA at the source's own size — the long-standing shape, and the one where **libVLC
 * never draws subtitles**: a buffer the size of the source makes libVLC blend subpictures into the
 * *source* chroma, which under VideoToolbox is an opaque `CVPX` surface it has no blending routine
 * for, so the caption is dropped (docs/adr/0041-subtitles-blend-into-an-i420-surface.md).
 *
 * [Planar] asks for I420 slightly **larger** than the source. The extra pixels move the blend after
 * the display conversion, into a chroma `blend` can write to, so captions reach the frame; asking for
 * I420 rather than BGRA keeps libVLC on its plane-to-plane conversion route, which is what makes 4K
 * 10-bit hold full frame rate (24.0 fps / 0 lost, against 16.4 fps / 149 lost for a padded BGRA
 * buffer). Costs a YUV→RGB step, done on the GPU by [VlcVideoPlayerSurface].
 */
enum class VlcSurfaceFormat { Packed, Planar }

/** One decoded frame as three 8-bit planes, published by [VlcSurfaceFormat.Planar]. */
@Stable
class PlanarFrame(
    val y: ImageBitmap,
    val u: ImageBitmap,
    val v: ImageBitmap,
    val width: Int,
    val height: Int,
)

@Stable
class VlcVideoPlayerState(
    private val surfaceFormat: VlcSurfaceFormat = VlcSurfaceFormat.Packed,
) : VideoPlayerState {

    private val player: EmbeddedMediaPlayer =
        VlcNativeInit.factory().mediaPlayers().newEmbeddedMediaPlayer()

    // vlcj events / render callbacks fire on libVLC threads; marshal Compose-state writes to Main.
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Frame state (surface reads currentFrameState) ---
    private val _currentFrame = mutableStateOf<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = _currentFrame
    private val _currentPlanarFrame = mutableStateOf<PlanarFrame?>(null)
    internal val currentPlanarFrameState: State<PlanarFrame?> = _currentPlanarFrame
    private val frameLock = Any()
    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null
    private var planesA: PlaneSet? = null
    private var planesB: PlaneSet? = null
    private var useA = true
    private var frameW = 0
    private var frameH = 0

    // --- UI state ---
    override var hasMedia: Boolean by mutableStateOf(false)
    override var isPlaying: Boolean by mutableStateOf(false)
    override var isLoading: Boolean by mutableStateOf(false)
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var sliderPos: Float by mutableStateOf(0f)
    override var userDragging: Boolean by mutableStateOf(false)
    override var loop: Boolean by mutableStateOf(false)
    override var isFullscreen: Boolean by mutableStateOf(false)
    override val metadata: VideoMetadata = VideoMetadata()

    private val _positionText = mutableStateOf("00:00")
    override val positionText: String get() = _positionText.value
    private val _durationText = mutableStateOf("00:00")
    override val durationText: String get() = _durationText.value
    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    /**
     * Last plausible media length in ms, or 0 while unknown. libVLC only knows the length once the
     * media is parsed — before that `status().length()` is 0, and for some containers the first
     * value it publishes is one the playhead later overruns. Scaling [sliderPos] by such a length
     * pins the bar at 100% seconds into a movie, so the length is kept here (fed by
     * `lengthChanged`, refreshed whenever the position passes it) instead of being re-read blind on
     * every tick.
     */
    @Volatile
    private var lengthMs: Long = 0L

    override val currentTime: Double get() = player.status().time() / 1000.0

    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f

    private val _volume = mutableStateOf(1f)
    override var volume: Float
        get() = _volume.value
        set(value) {
            val v = value.coerceIn(0f, 1f)
            _volume.value = v
            ioScope.launch { player.audio().setVolume((v * 100).toInt()) }
        }

    private val _speed = mutableStateOf(1f)
    override var playbackSpeed: Float
        get() = _speed.value
        set(value) {
            val v = value.coerceIn(0.5f, 2f)
            _speed.value = v
            ioScope.launch { player.submit { player.controls().setRate(v) } }
        }

    // --- Tracks ---
    //
    // Both lists are read back from libVLC rather than remembered from what we asked for: the
    // demuxer is the only thing that knows which elementary streams a stream really carries, and on
    // a live HLS feed they appear after playback has started (see [refreshTracks]).
    //
    // Subtitles are libVLC's *subpictures*, blended into the video buffer by the video output
    // itself — nothing in this backend has to draw them, which is why `subtitleTextStyle` /
    // `subtitleBackgroundColor` below stay decorative here.

    private val _audioTracks = mutableStateOf<List<AudioTrack>>(emptyList())
    override val availableAudioTracks: List<AudioTrack> get() = _audioTracks.value
    override var currentAudioTrack: AudioTrack? by mutableStateOf(null)

    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    // Observable list: the track panel is recomposed by it appearing, seconds after the stream opened.
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableStateListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    override fun selectAudioTrack(track: AudioTrack?) {
        val id = track?.id?.toIntOrNull() ?: return
        currentAudioTrack = track
        ioScope.launch { player.audio().setTrack(id) }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }
        currentSubtitleTrack = track
        subtitlesEnabled = true
        val id = track.src.toIntOrNull() ?: return
        ioScope.launch { player.subpictures().setTrack(id) }
    }

    override fun disableSubtitles() {
        subtitlesEnabled = false
        currentSubtitleTrack = null
        ioScope.launch { player.subpictures().setTrack(TRACK_DISABLED) }
    }

    /**
     * Re-reads both track lists from libVLC. Called on every event that can change them — the
     * lists are empty until the media is playing, and HLS renditions keep arriving after that.
     *
     * Runs off [ioScope]: these are native calls, and vlcj forbids re-entering the player from the
     * thread its own events are dispatched on.
     */
    private fun refreshTracks() {
        ioScope.launch {
            val audio = runCatching { player.audio().trackDescriptions() }.getOrNull().orEmpty()
            val audioId = runCatching { player.audio().track() }.getOrDefault(TRACK_DISABLED)
            val subtitles = runCatching { player.subpictures().trackDescriptions() }.getOrNull().orEmpty()
            val subtitleId = runCatching { player.subpictures().track() }.getOrDefault(TRACK_DISABLED)

            // libVLC prepends its own "Disable" entry (id -1) to both lists. It is not a rendition;
            // turning subtitles off is `disableSubtitles()`, and the UI renders its own row for it.
            val audioTracks = audio.filter { it.id() >= 0 }.map { it.asAudioTrack() }
            val subtitleTracks = subtitles.filter { it.id() >= 0 }.map { it.asSubtitleTrack() }

            onUi {
                _audioTracks.value = audioTracks
                currentAudioTrack = audioTracks.firstOrNull { it.id == audioId.toString() }
                availableSubtitleTracks.clear()
                availableSubtitleTracks.addAll(subtitleTracks)
                currentSubtitleTrack = subtitleTracks.firstOrNull { it.src == subtitleId.toString() }
                subtitlesEnabled = currentSubtitleTrack != null
            }
        }
    }

    private fun clearTracks() {
        _audioTracks.value = emptyList()
        currentAudioTrack = null
        availableSubtitleTracks.clear()
        currentSubtitleTrack = null
        subtitlesEnabled = false
    }

    init {
        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                // The *source* size, deliberately, even when the buffer asked for is bigger: the
                // margin exists to move libVLC's subpicture blending, not to change the picture's
                // shape, and the layout must keep the film's own aspect ratio.
                onDimensions(sourceWidth, sourceHeight)
                if (surfaceFormat == VlcSurfaceFormat.Packed) {
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                // Even dimensions: I420 carries one chroma sample per 2x2 luma block, so an odd
                // plane size would leave libVLC writing half a sample per row.
                val w = (sourceWidth + SPU_BLEND_MARGIN_PX + 1) and 1.inv()
                val h = (sourceHeight + SPU_BLEND_MARGIN_PX + 1) and 1.inv()
                return BufferFormat(
                    "I420", w, h,
                    intArrayOf(w, w / 2, w / 2),
                    intArrayOf(h, h / 2, h / 2),
                )
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCallback = RenderCallback { _, buffers, format ->
            framesReceived.incrementAndGet()
            if (surfaceFormat == VlcSurfaceFormat.Packed) onFrame(buffers[0], format)
            else onPlanarFrame(buffers, format)
        }
        player.videoSurface().set(
            VlcNativeInit.factory().videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true)
        )
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) = onUi {
                hasMedia = true; isPlaying = true; isLoading = false; error = null
                refreshTracks()
            }
            // The elementary streams of an HLS feed are announced one by one, after `playing` — the
            // second audio rendition of a bilingual channel typically lands a beat later.
            override fun elementaryStreamAdded(mp: MediaPlayer, type: TrackType, id: Int) = refreshTracks()
            override fun elementaryStreamDeleted(mp: MediaPlayer, type: TrackType, id: Int) = refreshTracks()
            override fun elementaryStreamSelected(mp: MediaPlayer, type: TrackType, id: Int) = refreshTracks()
            override fun paused(mp: MediaPlayer) = onUi { isPlaying = false }
            override fun stopped(mp: MediaPlayer) = onUi { isPlaying = false }
            override fun buffering(mp: MediaPlayer, newCache: Float) = onUi { if (isPlaying) isLoading = newCache < 100f }
            override fun error(mp: MediaPlayer) = onUi {
                isLoading = false
                error = VideoPlayerError.SourceError("libVLC could not play this media")
            }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) = onUi { updateLength(newLength) }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) = onUi {
                if (userDragging) return@onUi
                _positionText.value = formatTime(newTime / 1000.0)
                // A length the playhead has already passed is a pre-parse/under-reported one: ask
                // libVLC again rather than scale by it.
                if (lengthMs <= newTime) updateLength(player.status().length())
                sliderPos = if (lengthMs > newTime) {
                    (newTime.toFloat() / lengthMs * SLIDER_SCALE).coerceIn(0f, SLIDER_SCALE)
                } else {
                    // No usable length: libVLC publishes none for an MPEG-TS served over HTTP byte
                    // ranges (an Xtream Codes catch-up window, measured 2026-08-02 — `length()` stays
                    // 0 for the whole replay while `isSeekable` is true and seeking works). Scaling
                    // by that 0 is a division the branch above simply skipped, which left the bar
                    // pinned wherever it was last written and made the playhead unreadable.
                    //
                    // `position()` is the demuxer's own 0..1 progress, maintained from the byte
                    // offset and independent of any duration — measured accurate to 0.1% of the
                    // permille asked for on exactly that media, so it is the right fallback rather
                    // than a guess. The bar becomes usable; `durationText` stays "00:00", honestly
                    // reporting that nobody knows the length.
                    (player.status().position() * SLIDER_SCALE).coerceIn(0f, SLIDER_SCALE)
                }
            }
            override fun finished(mp: MediaPlayer) = onUi {
                if (loop) {
                    ioScope.launch { player.submit { player.controls().setPosition(0f); player.controls().play() } }
                } else {
                    isPlaying = false
                    // Pinning the bar to the end is only right when a length is known. Without one
                    // this event is not trustworthy as "the media ran out": on an HTTP byte-range
                    // MPEG-TS it also fires when a seek drops the connection mid-stream, and pinning
                    // then threw the playhead to the far right seconds after the user had put it in
                    // the middle (measured 2026-08-02).
                    //
                    // Nothing is lost by not pinning: sliderPos is driven by `position()` in that
                    // case, and a media that genuinely reached its end left it at ~1.0 already,
                    // while one whose connection died left it where playback actually stopped. The
                    // bar keeps telling those two apart instead of reporting both as "finished".
                    if (lengthMs > 0) sliderPos = SLIDER_SCALE
                }
            }
        })
    }

    private inline fun onUi(crossinline block: () -> Unit) { uiScope.launch { block() } }

    /** Publishes a new media length (ms, `<= 0` meaning "not known yet"). Main thread only. */
    private fun updateLength(newLength: Long) {
        val len = newLength.coerceAtLeast(0L)
        if (len == lengthMs) return
        lengthMs = len
        metadata.duration = len.takeIf { it > 0 }
        _durationText.value = formatTime(len / 1000.0)
    }

    private fun onDimensions(w: Int, h: Int) = onUi {
        if (w > 0 && h > 0) {
            _aspectRatio.value = w.toFloat() / h.toFloat()
            metadata.width = w
            metadata.height = h
        }
    }

    // Frame-pipeline counters. Public observability by design: callback rendering can fail silently
    // (frames delivered but never drawn), so "how many frames libVLC delivered" vs "how many reached
    // Skia" is exactly what a diagnosis needs. `framesReceived` increments on the libVLC render thread;
    // `framesPublished` once the Skia copy succeeds.
    val framesReceived = java.util.concurrent.atomic.AtomicInteger(0)
    val framesPublished = java.util.concurrent.atomic.AtomicInteger(0)

    /** Copy the RV32 (BGRA) frame into a double-buffered Skia bitmap and publish it. */
    private fun onFrame(buffer: ByteBuffer, format: BufferFormat) {
        val w = format.width; val h = format.height
        if (w <= 0 || h <= 0) return
        try {
        synchronized(frameLock) {
            if (bitmapA == null || frameW != w || frameH != h) {
                // Drop the old pair instead of `close()`-ing it: the ImageBitmap published from it is
                // still referenced by composition and by any recorded GraphicsLayer, and Compose turns
                // it into an SkImage on the AWT thread at draw time. Closing here zeroes the native
                // SkBitmap pointer under that draw -> SIGSEGV at 0x0 inside
                // SkImages::RasterFromBitmap (composeApp/hs_err_pid78542.log). Reached on every
                // resolution change, i.e. every channel zap and every adaptive HLS variant switch.
                // Skia's Bitmap is a Managed with a cleaner, so the frees still happen once the last
                // published frame is unreachable.
                bitmapA = null; bitmapB = null
                val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                bitmapA = Bitmap().apply { allocPixels(info) }
                bitmapB = Bitmap().apply { allocPixels(info) }
                frameW = w; frameH = h; useA = true
            }
            val target = (if (useA) bitmapA else bitmapB) ?: return
            useA = !useA
            val pixmap = target.peekPixels() ?: return
            val addr = pixmap.addr
            if (addr == 0L) return
            val srcPitch = format.pitches[0]
            val dstPitch = pixmap.rowBytes.toInt()
            val dst = com.sun.jna.Pointer(addr).getByteBuffer(0, dstPitch.toLong() * h)
            buffer.rewind()
            if (srcPitch == dstPitch) {
                dst.put(buffer)
            } else {
                val row = ByteArray(minOf(srcPitch, dstPitch))
                for (y in 0 until h) {
                    buffer.position(y * srcPitch)
                    buffer.get(row, 0, row.size)
                    dst.position(y * dstPitch)
                    dst.put(row)
                }
            }
            val published = target.asComposeImageBitmap()
            framesPublished.incrementAndGet()
            uiScope.launch { _currentFrame.value = published }
        }
        } catch (e: Throwable) {
            vlcLogger.e { "onFrame copy failed (${format.width}x${format.height}, pitch=${format.pitches.getOrNull(0)}): $e" }
        }
    }

    /**
     * Copy an I420 frame — three 8-bit planes, chroma at half resolution — into Skia bitmaps and
     * publish them for [VlcVideoPlayerSurface] to recombine on the GPU.
     *
     * Double-buffered and released-by-dropping for the same reasons as the packed path above: the
     * published planes are still referenced by composition while the next frame is being written.
     */
    private fun onPlanarFrame(buffers: Array<ByteBuffer>, format: BufferFormat) {
        val w = format.width; val h = format.height
        if (w <= 0 || h <= 0 || buffers.size < 3) return
        try {
            synchronized(frameLock) {
                if (planesA == null || frameW != w || frameH != h) {
                    planesA = PlaneSet(w, h)
                    planesB = PlaneSet(w, h)
                    frameW = w; frameH = h; useA = true
                }
                val target = (if (useA) planesA else planesB) ?: return
                useA = !useA
                target.copyFrom(buffers, format)
                val published = PlanarFrame(
                    y = target.y.asComposeImageBitmap(),
                    u = target.u.asComposeImageBitmap(),
                    v = target.v.asComposeImageBitmap(),
                    width = w,
                    height = h,
                )
                framesPublished.incrementAndGet()
                uiScope.launch { _currentPlanarFrame.value = published }
            }
        } catch (e: Throwable) {
            vlcLogger.e { "onPlanarFrame copy failed (${format.width}x${format.height}): $e" }
        }
    }

    /** The three Skia bitmaps one I420 frame is copied into. */
    private class PlaneSet(width: Int, height: Int) {
        val y = grayBitmap(width, height)
        val u = grayBitmap(width / 2, height / 2)
        val v = grayBitmap(width / 2, height / 2)

        fun copyFrom(buffers: Array<ByteBuffer>, format: BufferFormat) {
            copyPlane(buffers[0], format.pitches[0], y)
            copyPlane(buffers[1], format.pitches[1], u)
            copyPlane(buffers[2], format.pitches[2], v)
        }

        private fun copyPlane(src: ByteBuffer, srcPitch: Int, into: Bitmap) {
            val pixmap = into.peekPixels() ?: return
            val addr = pixmap.addr
            if (addr == 0L) return
            val dstPitch = pixmap.rowBytes.toInt()
            val rows = into.height
            val dst = com.sun.jna.Pointer(addr).getByteBuffer(0, dstPitch.toLong() * rows)
            src.rewind()
            if (srcPitch == dstPitch) {
                // libVLC was given our pitch, so the whole plane is one contiguous copy.
                dst.put(src)
                return
            }
            val row = ByteArray(minOf(srcPitch, dstPitch))
            for (line in 0 until rows) {
                src.position(line * srcPitch)
                src.get(row, 0, row.size)
                dst.position(line * dstPitch)
                dst.put(row)
            }
        }

        private companion object {
            fun grayBitmap(width: Int, height: Int) = Bitmap().apply {
                allocPixels(ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE))
            }
        }
    }

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        isLoading = true
        error = null
        // Nothing is known about the new media yet; carrying the previous one's length over would
        // scale the bar against the wrong movie until the first lengthChanged arrives.
        lengthMs = 0L
        metadata.duration = null
        // Same reason for the track lists: the previous media's renditions are not this one's, and
        // offering them would let the user pick an id the new demuxer has never heard of.
        clearTracks()
        sliderPos = 0f
        _positionText.value = formatTime(0.0)
        _durationText.value = formatTime(0.0)
        startStatsSampling()
        ioScope.launch {
            player.audio().setVolume((_volume.value * 100).toInt())
            val ok = player.media().play(uri, *mediaOptionsFor(uri))
            if (!ok) onUi { isLoading = false; error = VideoPlayerError.SourceError("Failed to open: $uri") }
            else if (initializeplayerState == InitialPlayerState.PAUSE) player.submit { player.controls().setPause(true) }
        }
    }

    /**
     * Samples libVLC's own input/demux/vout counters into [VlcNativeInit.nativeLogListener], while a
     * media is open and only when a listener is installed.
     *
     * These numbers answer the one question the player API cannot: when playback stops, had the
     * bytes stopped arriving *first*? A stream the server closed shows `read` flat while pictures
     * keep being displayed from the buffer; a pipeline stuck behind its own video output shows both
     * flat with `lost` climbing. libVLC reports a clean end-of-stream identically in both cases —
     * `playing=false`, no error, no log line (measured 2026-08-16).
     */
    private var statsJob: Job? = null

    private fun startStatsSampling() {
        statsJob?.cancel()
        val report = VlcNativeInit.nativeLogListener ?: return
        statsJob = ioScope.launch {
            var lastInput = 0
            var lastDemux = 0
            var lastShown = 0
            var lastLost = 0
            var millisSinceReport = 0L
            var wasStalled = false
            while (isActive) {
                delay(STATS_SAMPLE_MILLIS)
                millisSinceReport += STATS_SAMPLE_MILLIS
                val stats = runCatching { player.media().info()?.statistics() }.getOrNull() ?: continue
                val input = stats.inputBytesRead()
                val demux = stats.demuxBytesRead()
                val shown = stats.picturesDisplayed()
                val lost = stats.picturesLost()
                val readDelta = input - lastInput
                val stalled = readDelta == 0
                // Sampled at [STATS_SAMPLE_MILLIS] but only *written* when it says something: the
                // heartbeat, the moment input stops, the moment it comes back, or dropped pictures.
                // A stream that simply works costs one line every [STATS_REPORT_MILLIS].
                val notable = stalled != wasStalled || lost > lastLost
                if (notable || millisSinceReport >= STATS_REPORT_MILLIS) {
                    val prefix = when {
                        stalled && !wasStalled -> "INPUT STOPPED "
                        !stalled && wasStalled -> "input resumed "
                        else -> ""
                    }
                    report(
                        "STATS",
                        "input",
                        prefix +
                            "read=+${readDelta}B bitrate=${(stats.inputBitrate() * 8000).toInt()}kbps " +
                            "demux=+${demux - lastDemux}B corrupt=${stats.demuxCorrupted()} " +
                            "disc=${stats.demuxDiscontinuity()} shown=+${shown - lastShown} " +
                            "lost=+${lost - lastLost}",
                    )
                    millisSinceReport = 0L
                }
                wasStalled = stalled
                lastInput = input
                lastDemux = demux
                lastShown = shown
                lastLost = lost
            }
        }
    }

    override fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) =
        openUri(file.file.path, initializeplayerState)

    override fun play() { ioScope.launch { player.submit { player.controls().setPause(false) } } }
    override fun pause() { ioScope.launch { player.submit { player.controls().setPause(true) } } }
    override fun stop() { ioScope.launch { player.submit { player.controls().stop() } }; hasMedia = false; isPlaying = false }

    override fun seekTo(value: Float) {
        val pos = (value / SLIDER_SCALE).coerceIn(0f, 1f)
        sliderPos = value
        ioScope.launch { player.submit { player.controls().setPosition(pos) } }
    }

    override fun toggleFullscreen() { isFullscreen = !isFullscreen }
    override fun clearError() { error = null }

    override fun dispose() {
        // Before the player is released: the sampler reads through it.
        statsJob?.cancel()
        uiScope.launch {
            isPlaying = false; hasMedia = false
            _currentFrame.value = null; _currentPlanarFrame.value = null
        }
        ioScope.launch {
            try { player.controls().stop() } catch (_: Throwable) {}
            try { player.release() } catch (_: Throwable) {}
        }
        // Same reason as the format-change branch of [onFrame]: the last published frame can still be
        // drawn after dispose (the surface leaves composition on the next frame, not synchronously),
        // so the buffers are released to the GC rather than closed under a live draw.
        synchronized(frameLock) {
            bitmapA = null; bitmapB = null; frameW = 0; frameH = 0
        }
    }
}
