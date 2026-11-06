package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class SkiaFrameRendererTest {

    @Test
    fun `a frame is the same whether or not earlier frames were rendered first`() {
        val reused = SkiaFrameRenderer()
        reused.render(WIDTH, HEIGHT, screen(1080, 2400, Color.RED), "✅ Launch app")

        val frame = reused.render(WIDTH, HEIGHT, screen(1080, 2400, Color.BLUE), "✅ Launch app\n🔲 Tap on \"Next\"")
        val fresh = SkiaFrameRenderer().render(WIDTH, HEIGHT, screen(1080, 2400, Color.BLUE), "✅ Launch app\n🔲 Tap on \"Next\"")

        assertThat(differingPixels(frame, fresh)).isEqualTo(0)
    }

    @Test
    fun `a screen of a different size is laid out for its own size`() {
        val reused = SkiaFrameRenderer()
        reused.render(WIDTH, HEIGHT, screen(1080, 2400, Color.RED), "✅ Launch app")

        val frame = reused.render(WIDTH, HEIGHT, screen(1170, 2532, Color.BLUE), "✅ Launch app")
        val fresh = SkiaFrameRenderer().render(WIDTH, HEIGHT, screen(1170, 2532, Color.BLUE), "✅ Launch app")

        assertThat(differingPixels(frame, fresh)).isEqualTo(0)
    }

    private fun screen(width: Int, height: Int, color: Color) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().use { g ->
                g.color = color
                g.fillRect(0, 0, width, height)
            }
        }

    /** A count rather than the frames themselves, so a failure reports a number, not two 2M-pixel arrays. */
    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        val pixelsA = a.getRGB(0, 0, a.width, a.height, null, 0, a.width)
        val pixelsB = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
        return pixelsA.indices.count { pixelsA[it] != pixelsB[it] }
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
    }
}
