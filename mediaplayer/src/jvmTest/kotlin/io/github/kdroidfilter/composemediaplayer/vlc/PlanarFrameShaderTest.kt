package io.github.kdroidfilter.composemediaplayer.vlc

import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The YUV → RGB conversion, checked on actual pixels.
 *
 * libVLC hands the movie player planar I420 so it will draw subtitles into the frame
 * (docs/adr/0041-subtitles-blend-into-an-i420-surface.md), and the recombination happens in an SkSL
 * shader. Nothing else in the pipeline would notice a swapped chroma plane, a full-range matrix on
 * limited-range content, or BT.601 coefficients on an HD frame: the picture simply looks wrong.
 */
class PlanarFrameShaderTest {

    @Test
    fun convertsLimitedRangeYuvToRgb() {
        // Y'CbCr values from the BT.709 limited-range definition of each colour.
        check(1080, y = 235, u = 128, v = 128, expected = Triple(255, 255, 255), what = "white")
        check(1080, y = 16, u = 128, v = 128, expected = Triple(0, 0, 0), what = "black")
        check(1080, y = 126, u = 128, v = 128, expected = Triple(128, 128, 128), what = "mid grey")
        check(1080, y = 63, u = 102, v = 240, expected = Triple(255, 0, 0), what = "BT.709 red")
        check(1080, y = 173, u = 42, v = 26, expected = Triple(0, 255, 0), what = "BT.709 green")
        // Below 720 lines the shader must switch to BT.601, where red sits at different chroma.
        check(576, y = 81, u = 90, v = 240, expected = Triple(255, 0, 0), what = "BT.601 red")
    }

    @Test
    fun readsTheChromaPlanesInOrder() {
        // Cb and Cr swapped would still give a plausible-looking picture, so assert the asymmetry:
        // this pair is red only if V feeds Cr.
        check(1080, y = 63, u = 102, v = 240, expected = Triple(255, 0, 0), what = "u/v in order")
        val swapped = renderColour(1080, y = 63, u = 240, v = 102)
        assertTrue(
            swapped.first < 128,
            "swapping the chroma planes must not produce red — got $swapped",
        )
    }

    private fun check(
        height: Int,
        y: Int,
        u: Int,
        v: Int,
        expected: Triple<Int, Int, Int>,
        what: String,
    ) {
        val (r, g, b) = renderColour(height, y, u, v)
        val off = maxOf(
            abs(r - expected.first), abs(g - expected.second), abs(b - expected.third),
        )
        assertTrue(off <= TOLERANCE, "$what: expected $expected, got ($r, $g, $b)")
    }

    /** Renders one flat I420 frame and reads the middle pixel back. */
    private fun renderColour(height: Int, y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
        val width = height * 16 / 9
        val frame = PlanarFrame(
            y = flatPlane(width, height, y),
            u = flatPlane(width / 2, height / 2, u),
            v = flatPlane(width / 2, height / 2, v),
            width = width,
            height = height,
        )
        val surface = Surface.makeRasterN32Premul(SURFACE, SURFACE)
        val shader = PlanarFrameShader.shaderFor(
            frame,
            scaleX = width.toFloat() / SURFACE,
            scaleY = height.toFloat() / SURFACE,
            offsetX = 0f,
            offsetY = 0f,
        )
        val paint = Paint().also { it.shader = shader }
        surface.canvas.drawRect(Rect.makeWH(SURFACE.toFloat(), SURFACE.toFloat()), paint)
        val out = Bitmap().apply {
            allocPixels(ImageInfo(SURFACE, SURFACE, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        }
        surface.readPixels(out, 0, 0)
        val colour = out.getColor(SURFACE / 2, SURFACE / 2)
        paint.close(); shader.close(); surface.close()
        return Triple((colour shr 16) and 0xFF, (colour shr 8) and 0xFF, colour and 0xFF)
    }

    private fun flatPlane(width: Int, height: Int, value: Int) = Bitmap().apply {
        allocPixels(ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE))
        erase(0xFF000000.toInt() or (value shl 16) or (value shl 8) or value)
    }.asComposeImageBitmap()

    private companion object {
        const val SURFACE = 16

        /** Eight-bit rounding through a half-precision shader, plus the matrices' own rounding. */
        const val TOLERANCE = 4
    }
}
