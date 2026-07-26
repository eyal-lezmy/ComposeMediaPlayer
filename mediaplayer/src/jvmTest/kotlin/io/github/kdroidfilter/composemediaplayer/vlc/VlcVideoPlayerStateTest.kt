package io.github.kdroidfilter.composemediaplayer.vlc

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end test of [VlcVideoPlayerState] against a remote mkv: `openUri` → libVLC event wiring flips
 * `isPlaying`/`hasMedia`, frames reach the render callback, length populates `durationText`. Exercises
 * the state class without Compose.
 *
 * Opt-in via `VLC_TEST_URL` (a direct media URL); unset → no-op.
 */
class VlcVideoPlayerStateTest {

    @Test
    fun backendPlaysRemoteMkv() {
        val url = System.getenv("VLC_TEST_URL")?.takeIf { it.isNotBlank() } ?: run {
            println("VLC_TEST_URL unset — skipping backend test")
            return
        }

        val state = VlcVideoPlayerState()
        state.openUri(url, InitialPlayerState.PLAY)

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (state.error != null) break
            if (state.isPlaying && state.framesReceived.get() > 3 && state.durationText != "00:00") break
            Thread.sleep(200)
        }

        // Snapshot everything BEFORE dispose() — dispose flips isPlaying/hasMedia to false.
        val received = state.framesReceived.get()
        val playing = state.isPlaying
        val duration = state.durationText
        val err = state.error
        println(
            "backend result: hasMedia=${state.hasMedia} isPlaying=$playing " +
                "framesReceived=$received framesPublished=${state.framesPublished.get()} " +
                "duration=$duration error=$err"
        )
        state.dispose()

        // NB: framesPublished / currentFrameState can't be asserted here — org.jetbrains.skia.Bitmap
        // needs Skiko's native runtime, which only initialises inside a running Compose app, not a bare
        // unit-test JVM. Decode+delivery (framesReceived) is the meaningful headless signal; the Skia
        // copy + on-screen render is verified by a Compose UI test.
        assertTrue(err == null, "backend reported error: $err")
        assertTrue(playing, "backend never reached isPlaying")
        assertTrue(received > 3, "libVLC delivered no video frames to the render callback (got $received)")
        assertTrue(duration != "00:00", "duration never populated")
    }
}
