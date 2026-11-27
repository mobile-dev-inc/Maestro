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

    @Test
    internal fun `check visible percentage on screen - clipped by a scroll container`() {
        val element = UiElement(
            TreeNode(attributes = mutableMapOf(CLIP_BOUNDS_ATTRIBUTE to "[0,0][720,100]")),
            bounds = Bounds(
                x = 50,
                y = 50,
                width = 200,
                height = 100
            )
        )

        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(0.5)
    }

    @Test
    internal fun `check visible percentage on screen - entirely clipped by a scroll container`() {
        val element = UiElement(
            TreeNode(attributes = mutableMapOf(CLIP_BOUNDS_ATTRIBUTE to "[0,0][0,0]")),
            bounds = Bounds(
                x = 50,
                y = 50,
                width = 200,
                height = 100
            )
        )

        assertThat(element.visibleBounds).isNull()
        assertThat(element.getVisiblePercentage(screenWidth, screenHeight)).isEqualTo(0)
    }

    @Test
    internal fun `check visible percentage on screen - larger than the screen stays fully visible when clipped`() {
        val element = UiElement(
            TreeNode(attributes = mutableMapOf(CLIP_BOUNDS_ATTRIBUTE to "[0,100][720,1000]")),
            bounds = Bounds(
                x = 0,
                y = -500,
                width = 720,
                height = 3000
            )
        )

        val percent = element.getVisiblePercentage(screenWidth, screenHeight)
        assertThat(percent).isEqualTo(1)
    }

    @Test
    internal fun `visible bounds default to the bounds when nothing clipped the element`() {
        val bounds = Bounds(x = 50, y = 50, width = 200, height = 100)
        val element = UiElement(TreeNode(), bounds)

        assertThat(element.visibleBounds).isEqualTo(bounds)
    }
}
