package maestro.orchestra.util

import com.google.common.truth.Truth.assertThat
import maestro.Bounds
import maestro.TreeNode
import maestro.UiElement
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import org.junit.jupiter.api.Test

/**
 * Unit tests for element coordinate calculation utility functions.
 * Tests the business logic for calculating element-relative points.
 */
internal class ElementCoordinateUtilTest {

    @Test
    fun `test coordinate calculation with percentage coordinates`() {
        // Given: Real TapOnElementCommand with relativePoint
        val command = TapOnElementCommand(
            selector = ElementSelector(textRegex = "Test"),
            relativePoint = "50%, 90%"
        )

        // When: We test the real coordinate calculation
        val testElement = createTestUiElement()
        val calculatedPoint = calculateElementRelativePoint(testElement, command.relativePoint!!)

        // Then: Verify the real calculation works correctly
        assertThat(calculatedPoint.x).isEqualTo(150) // 100 + (100 * 50 / 100) = 150
        assertThat(calculatedPoint.y).isEqualTo(190) // 100 + (100 * 90 / 100) = 190
    }

    @Test
    fun `test coordinate calculation with absolute coordinates`() {
        // Given: Real TapOnElementCommand with absolute coordinates
        val command = TapOnElementCommand(
            selector = ElementSelector(textRegex = "Test"),
            relativePoint = "25, 75"
        )

        // When: We test the real coordinate calculation
        val testElement = createTestUiElement()
        val calculatedPoint = calculateElementRelativePoint(testElement, command.relativePoint!!)

        // Then: Verify the real calculation works correctly
        assertThat(calculatedPoint.x).isEqualTo(125) // 100 + 25 = 125
        assertThat(calculatedPoint.y).isEqualTo(175) // 100 + 75 = 175
    }

    @Test
    fun `test coordinate calculation edge cases`() {
        val testElement = createTestUiElement()
        
        // Test 0%, 0% (top-left)
        val topLeft = calculateElementRelativePoint(testElement, "0%, 0%")
        assertThat(topLeft.x).isEqualTo(100) // 100 + (100 * 0 / 100) = 100
        assertThat(topLeft.y).isEqualTo(100) // 100 + (100 * 0 / 100) = 100

        // Test 100%, 100% (bottom-right)
        val bottomRight = calculateElementRelativePoint(testElement, "100%, 100%")
        assertThat(bottomRight.x).isEqualTo(200) // 100 + (100 * 100 / 100) = 200
        assertThat(bottomRight.y).isEqualTo(200) // 100 + (100 * 100 / 100) = 200

        // Test 25%, 75% (quarter from left, three-quarters from top)
        val quarterPoint = calculateElementRelativePoint(testElement, "25%, 75%")
        assertThat(quarterPoint.x).isEqualTo(125) // 100 + (100 * 25 / 100) = 125
        assertThat(quarterPoint.y).isEqualTo(175) // 100 + (100 * 75 / 100) = 175
    }

    // Helper function to create a test UiElement (same structure as real Maestro)
    private fun createTestUiElement(): UiElement {
        val treeNode = TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[100,100][200,200]",
                "text" to "Test Button",
                "resource-id" to "test-button",
                "class" to "android.widget.Button"
            )
        )
        return UiElement(
            treeNode = treeNode,
            bounds = Bounds(x = 100, y = 100, width = 100, height = 100)
        )
    }
}
