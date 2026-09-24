package maestro

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class ViewHierarchyTest {

    private val screenHeight = 1080
    private val screenWidth = 1920

    private fun node(bounds: String, resourceId: String) = TreeNode(
        attributes = mutableMapOf("bounds" to bounds, "resource-id" to resourceId)
    )

    @Test
    internal fun `filters out an element collapsed to zero width`() {
        val root = TreeNode(
            attributes = mutableMapOf("bounds" to "[0,0][1920,1080]"),
            children = listOf(node("[-452,0][-452,72]", "collapsed-drawer-item"))
        )

        val filtered = root.filterOutOfBounds(screenWidth, screenHeight)

        assertThat(filtered?.aggregate()?.map { it.attributes["resource-id"] })
            .doesNotContain("collapsed-drawer-item")
    }

    @Test
    internal fun `filters out an element collapsed to zero height`() {
        val root = TreeNode(
            attributes = mutableMapOf("bounds" to "[0,0][1920,1080]"),
            children = listOf(node("[100,100][300,100]", "collapsed-row"))
        )

        val filtered = root.filterOutOfBounds(screenWidth, screenHeight)

        assertThat(filtered?.aggregate()?.map { it.attributes["resource-id"] })
            .doesNotContain("collapsed-row")
    }

    @Test
    internal fun `keeps an element that is on screen`() {
        val root = TreeNode(
            attributes = mutableMapOf("bounds" to "[0,0][1920,1080]"),
            children = listOf(node("[100,100][300,172]", "visible-item"))
        )

        val filtered = root.filterOutOfBounds(screenWidth, screenHeight)

        assertThat(filtered?.aggregate()?.map { it.attributes["resource-id"] })
            .contains("visible-item")
    }
}
