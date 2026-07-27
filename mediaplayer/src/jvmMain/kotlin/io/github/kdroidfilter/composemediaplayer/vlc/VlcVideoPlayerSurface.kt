package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/**
 * Renders [VlcVideoPlayerState]'s frames (bundled libVLC). Same shape as `MacVideoPlayerSurface`: draw
 * the latest [androidx.compose.ui.graphics.ImageBitmap] scaled to the surface, overlay on top, and
 * hand both to a separate fullscreen window while [VlcVideoPlayerState.isFullscreen] is set.
 * The subtitle layer is intentionally out of scope for this surface.
 *
 * @param isInFullscreenWindow Whether this surface is the one already inside the fullscreen window —
 *                             it draws the frames then, and must not open a second window.
 */
@Composable
fun VlcVideoPlayerSurface(
    playerState: VlcVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // While fullscreen, only the fullscreen window draws frames — the windowed surface stays
        // black behind it rather than decoding the same picture into two canvases.
        if (playerState.hasMedia && (!playerState.isFullscreen || isInFullscreenWindow)) {
            val currentFrame by remember(playerState) { playerState.currentFrameState }
            currentFrame?.let { frame ->
                Canvas(
                    modifier = contentScale.toCanvasModifier(
                        playerState.aspectRatio, playerState.metadata.width, playerState.metadata.height
                    ),
                ) {
                    drawScaledImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        contentScale = contentScale,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) { overlay() }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale = contentScale)
    }
}
