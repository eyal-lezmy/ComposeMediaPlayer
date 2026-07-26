package io.github.kdroidfilter.composemediaplayer.vlc

import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plays a remote mkv end-to-end through the **bundled** libVLC and vlcj's callback surface: native load
 * (libvlccore preload + plugin path), any HTTP redirect/byte-range handling, Matroska demux, decode, and
 * RV32 frame delivery. Headless — no Compose, no GUI.
 *
 * Opt-in: set `VLC_TEST_URL` to a direct media URL. Unset → the test no-ops, so CI without a media
 * endpoint stays green.
 */
class VlcCallbackPlaybackTest {

    @Test
    fun playsRemoteMkv() {
        val url = System.getenv("VLC_TEST_URL")?.takeIf { it.isNotBlank() } ?: run {
            println("VLC_TEST_URL unset — skipping callback playback test")
            return
        }

        val factory = VlcNativeInit.factory()
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

        val frames = AtomicInteger(0)
        val playing = AtomicBoolean(false)
        val errored = AtomicBoolean(false)
        val length = AtomicLong(0)

        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                RV32BufferFormat(sourceWidth, sourceHeight)

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCallback = RenderCallback { _, _, _ -> frames.incrementAndGet() }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true))

        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) { playing.set(true) }
            override fun error(mp: MediaPlayer) { errored.set(true) }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) { length.set(newLength) }
        })

        player.audio().setVolume(0)
        check(player.media().play(url)) { "vlcj play() returned false for $url" }

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (errored.get()) break
            if (frames.get() > 5 && length.get() > 0) break
            Thread.sleep(200)
        }

        val f = frames.get(); val len = length.get()
        println("playback result: playing=${playing.get()} frames=$f length=${len}ms errored=${errored.get()}")

        player.controls().stop()
        player.release()

        assertTrue(!errored.get(), "libVLC reported an error opening the media")
        assertTrue(playing.get(), "player never reached PLAYING")
        assertTrue(len > 0, "no media length reported")
        assertTrue(f > 5, "no video frames decoded (got $f)")
    }
}
