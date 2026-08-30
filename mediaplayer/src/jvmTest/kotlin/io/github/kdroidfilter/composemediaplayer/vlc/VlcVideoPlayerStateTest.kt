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

    /**
     * The volume the host app asked for has to survive the media it was asked on (Okamp.tv task
     * 184). `libvlc_audio_set_volume` has nowhere to write until the audio output of the media
     * being started exists, so a level set before `openUri` — or carried over from the media a zap
     * replaced — is dropped and the new one plays at libVLC's own 100 %, while [volume] keeps
     * reporting the request. [nativeVolumePercent] is the difference, and the only thing worth
     * asserting here.
     *
     * Opt-in the same way as the test above: it needs a real media and real natives.
     */
    @Test
    fun keepsTheRequestedVolumeAcrossAMediaChange() {
        val url = System.getenv("VLC_TEST_URL")?.takeIf { it.isNotBlank() } ?: run {
            println("VLC_TEST_URL unset — skipping volume test")
            return
        }

        val state = VlcVideoPlayerState()
        // Set *before* anything is open, which is what the app does: the stored preference is read
        // from the database while the player is still empty.
        state.volume = 0.2f
        state.openUri(url, InitialPlayerState.PLAY)
        val onFirstOpen = state.awaitNativeVolume()

        // The zap: a second openUri on the same player, with nothing touching `volume` in between.
        state.openUri(url, InitialPlayerState.PLAY)
        val onSecondOpen = state.awaitNativeVolume()
        state.dispose()

        assertTrue(onFirstOpen in 18..22, "first open played at $onFirstOpen% instead of 20%")
        assertTrue(onSecondOpen in 18..22, "the media after a zap played at $onSecondOpen% instead of 20%")
    }

    /** libVLC applies the level asynchronously, once its output exists — so poll rather than read. */
    private fun VlcVideoPlayerState.awaitNativeVolume(): Int {
        val deadline = System.currentTimeMillis() + 30_000
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            last = nativeVolumePercent
            if (isPlaying && last in 18..22) return last
            if (error != null) break
            Thread.sleep(100)
        }
        return last
    }
}
