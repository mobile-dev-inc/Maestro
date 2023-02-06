package maestro

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class UiElementTest {

    private val screenHeight = 1280
    private val screenWidth = 720

    @Test
    internal fun `check visible percentage on screen - full`() {
        val element = UiElement(
            TreeNode(),
            bounds = Bounds(
                x = 50,
                y = 50,
                width = 200,
                height = 100
            )
        )
        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(1)
    }

    @Test
    internal fun `check visible percentage on screen - left bottom 15 percent`() {
        val element = UiElement(
            TreeNode(),
            bounds = Bounds(
                x = -50,
                y = 1260,
                width = 200,
                height = 100
            )
        )
        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(0.15)
    }

    @Test
    internal fun `check visible percentage on screen - right bottom 10 percent`() {
        val element = UiElement(
            TreeNode(),
            bounds = Bounds(
                x = 680,
                y = 1200,
                width = 200,
                height = 100
            )
        )
        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(0.16)
    }

    @Test
    internal fun `check visible percentage on screen - out of bounds`() {
        val element = UiElement(
            TreeNode(),
            bounds = Bounds(
                x = -200,
                y = 1300,
                width = 200,
                height = 100
            )
        )

        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(0)
    }
}