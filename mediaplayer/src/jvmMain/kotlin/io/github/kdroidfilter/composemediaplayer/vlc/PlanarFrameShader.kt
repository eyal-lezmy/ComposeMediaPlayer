package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a [PlanarFrame] by recombining its three planes on the GPU.
 *
 * libVLC hands us I420 (see [VlcSurfaceFormat.Planar]) and Skia has no YUV image type here — skiko
 * exposes no YUV binding at all — so the planes are uploaded as three single-channel textures and
 * converted in an SkSL shader. That also moves the conversion off the CPU, where libVLC used to do
 * it: an I420 frame is 1.5 bytes per pixel against BGRA's 4, i.e. 12.4 MB per 4K frame instead of 33.
 *
 * The coefficients are baked into two shader variants rather than passed as uniforms: libVLC 3's
 * `libvlc_video_track_t` carries no colour space, so the choice is made by resolution — the usual
 * convention, and the only signal available. Both are limited range (16-235), which is what a
 * decoded broadcast or film stream carries.
 */
internal object PlanarFrameShader {

    private const val PREAMBLE = """
        uniform shader yTex;
        uniform shader uTex;
        uniform shader vTex;
        uniform float2 texScale;
        uniform float2 texOffset;

        half4 main(float2 fragCoord) {
            float2 p = fragCoord * texScale + texOffset;
            half y = yTex.eval(p).r;
            half u = uTex.eval(p * 0.5).r;
            half v = vTex.eval(p * 0.5).r;
            half yy = (y - 0.0627451) * 1.164384;
            half cb = u - 0.5;
            half cr = v - 0.5;
    """

    /** ITU-R BT.709, limited range — HD and above. */
    private val bt709 = RuntimeEffect.makeForShader(
        PREAMBLE + """
            half r = yy + 1.792741 * cr;
            half g = yy - 0.213249 * cb - 0.532909 * cr;
            half b = yy + 2.112402 * cb;
            return half4(clamp(half3(r, g, b), 0.0, 1.0), 1.0);
        }
        """,
    )

    /** ITU-R BT.601, limited range — standard definition. */
    private val bt601 = RuntimeEffect.makeForShader(
        PREAMBLE + """
            half r = yy + 1.596027 * cr;
            half g = yy - 0.391762 * cb - 0.812968 * cr;
            half b = yy + 2.017232 * cb;
            return half4(clamp(half3(r, g, b), 0.0, 1.0), 1.0);
        }
        """,
    )

    /**
     * Paints [frame] over [dstWidth] x [dstHeight], sampling the source rectangle
     * ([srcLeft], [srcTop]) + ([srcWidth], [srcHeight]) — which is how `ContentScale.Crop` asks for
     * less than the whole frame.
     */
    fun DrawScope.drawPlanarFrame(
        frame: PlanarFrame,
        dstWidth: Float,
        dstHeight: Float,
        srcLeft: Float = 0f,
        srcTop: Float = 0f,
        srcWidth: Float = frame.width.toFloat(),
        srcHeight: Float = frame.height.toFloat(),
    ) {
        if (dstWidth <= 0f || dstHeight <= 0f) return
        val shader = shaderFor(frame, srcWidth / dstWidth, srcHeight / dstHeight, srcLeft, srcTop)
        val paint = Paint().also { it.shader = shader }
        drawContext.canvas.nativeCanvas.drawRect(Rect.makeWH(dstWidth, dstHeight), paint)
        paint.close()
        shader.close()
    }

    /**
     * The composed shader for [frame], sampling `dst * scale + offset` in frame pixels. Separate from
     * the draw so a test can render it into a raster surface and read the converted colours back —
     * a swapped chroma plane or the wrong matrix is invisible in every other kind of check.
     */
    internal fun shaderFor(
        frame: PlanarFrame,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
    ): org.jetbrains.skia.Shader {
        val effect = if (frame.height >= HD_LINES) bt709 else bt601
        val uniforms = ByteBuffer.allocate(4 * FLOATS).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(scaleX); putFloat(scaleY); putFloat(offsetX); putFloat(offsetY)
        }
        return effect.makeShader(
            uniforms = Data.makeFromBytes(uniforms.array()),
            children = arrayOf(
                ImageShader(frame.y, TileMode.Clamp, TileMode.Clamp),
                ImageShader(frame.u, TileMode.Clamp, TileMode.Clamp),
                ImageShader(frame.v, TileMode.Clamp, TileMode.Clamp),
            ),
            localMatrix = null,
        )
    }

    /** `texScale` and `texOffset`, two `float2`s. */
    private const val FLOATS = 4

    /** At and above this many lines, a decoded stream is BT.709 by convention. */
    private const val HD_LINES = 720
}
