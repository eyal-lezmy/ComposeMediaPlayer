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
 * the latest [androidx.compose.ui.graphics.ImageBitmap] scaled to the surface, overlay on top.
 * Fullscreen-as-separate-window and the subtitle layer are intentionally out of scope for this surface.
 */
@Composable
fun VlcVideoPlayerSurface(
    playerState: VlcVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (playerState.hasMedia) {
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
}
