package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.common.openFullscreenWindow

/**
 * Opens a fullscreen window for the libVLC-backed surface, mirroring the Linux/macOS/Windows
 * backends. The frames are drawn from the very same [VlcVideoPlayerState], so no second native
 * player is created — only the Compose window rendering them changes.
 *
 * @param playerState The player state to use in the fullscreen window
 * @param overlay Optional composable content displayed on top of the video surface — passed through
 *                so the app's controls follow the video into the fullscreen window.
 */
@Composable
fun openFullscreenWindow(
    playerState: VlcVideoPlayerState,
    overlay: @Composable () -> Unit = {},
    contentScale: ContentScale,
) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            VlcVideoPlayerSurface(
                playerState = state as VlcVideoPlayerState,
                modifier = modifier,
                contentScale = contentScale,
                overlay = overlay,
                isInFullscreenWindow = isInFullscreenWindow,
            )
        }
    )
}
