package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
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
 * A [VideoPlayerState] backed by the **bundled** libVLC, so containers/codecs a platform backend can't
 * demux (mkv/HEVC/AC3, etc.) still play. Video is rendered through vlcj's callback surface: libVLC hands
 * RV32 (BGRA) frames to [RenderCallback.display], which are copied into a Skia bitmap and published as
 * an [ImageBitmap] for [VlcVideoPlayerSurface] to draw. Playback/UI state is driven off libVLC events,
 * not polling.
 */
@Stable
class VlcVideoPlayerState : VideoPlayerState {

    private val player: EmbeddedMediaPlayer =
        VlcNativeInit.factory().mediaPlayers().newEmbeddedMediaPlayer()

    // vlcj events / render callbacks fire on libVLC threads; marshal Compose-state writes to Main.
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Frame state (surface reads currentFrameState) ---
    private val _currentFrame = mutableStateOf<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = _currentFrame
    private val frameLock = Any()
    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null
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

    // --- Subtitles: stubbed (no track picker exposed yet) ---
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override fun selectSubtitleTrack(track: SubtitleTrack?) { currentSubtitleTrack = track; subtitlesEnabled = track != null }
    override fun disableSubtitles() { subtitlesEnabled = false; currentSubtitleTrack = null }

    init {
        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                onDimensions(sourceWidth, sourceHeight)
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCallback = RenderCallback { _, buffers, format -> onFrame(buffers[0], format) }
        player.videoSurface().set(
            VlcNativeInit.factory().videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true)
        )
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) = onUi { hasMedia = true; isPlaying = true; isLoading = false; error = null }
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
                // libVLC again rather than scale by it. Until a plausible length exists the bar
                // simply holds — an honest "unknown" beats racing to the end.
                if (lengthMs <= newTime) updateLength(player.status().length())
                if (lengthMs > newTime) {
                    sliderPos = (newTime.toFloat() / lengthMs * SLIDER_SCALE).coerceIn(0f, SLIDER_SCALE)
                }
            }
            override fun finished(mp: MediaPlayer) = onUi {
                if (loop) ioScope.launch { player.submit { player.controls().setPosition(0f); player.controls().play() } }
                else { isPlaying = false; sliderPos = SLIDER_SCALE }
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
        framesReceived.incrementAndGet()
        val w = format.width; val h = format.height
        if (w <= 0 || h <= 0) return
        try {
        synchronized(frameLock) {
            if (bitmapA == null || frameW != w || frameH != h) {
                bitmapA?.close(); bitmapB?.close()
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

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        isLoading = true
        error = null
        // Nothing is known about the new media yet; carrying the previous one's length over would
        // scale the bar against the wrong movie until the first lengthChanged arrives.
        lengthMs = 0L
        metadata.duration = null
        sliderPos = 0f
        _positionText.value = formatTime(0.0)
        _durationText.value = formatTime(0.0)
        ioScope.launch {
            player.audio().setVolume((_volume.value * 100).toInt())
            val ok = player.media().play(uri)
            if (!ok) onUi { isLoading = false; error = VideoPlayerError.SourceError("Failed to open: $uri") }
            else if (initializeplayerState == InitialPlayerState.PAUSE) player.submit { player.controls().setPause(true) }
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
        uiScope.launch { isPlaying = false; hasMedia = false }
        ioScope.launch {
            try { player.controls().stop() } catch (_: Throwable) {}
            try { player.release() } catch (_: Throwable) {}
        }
        synchronized(frameLock) {
            bitmapA?.close(); bitmapB?.close(); bitmapA = null; bitmapB = null
        }
    }
}
