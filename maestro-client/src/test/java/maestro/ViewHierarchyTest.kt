package maestro

import com.google.common.truth.Truth.assertThat
import maestro.UiElement.Companion.toUiElement
import org.junit.jupiter.api.Test

internal class ViewHierarchyTest {

    private val screenWidth = 400
    private val screenHeight = 800

    // header 0..100, list 100..700, footer 700..800
    private fun screenWithList(vararg rows: TreeNode): TreeNode {
        return node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(id = "list", bounds = "[0,100][400,700]", clips = true, children = rows.toList()),
                node(id = "header", bounds = "[0,0][400,100]"),
                node(id = "footer-button", bounds = "[0,700][400,800]"),
            ),
        )
    }

    @Test
    internal fun `a row scrolled under the header is visible only where it is inside the list`() {
        val root = screenWithList(node(id = "row-3", bounds = "[0,60][400,140]"))
            .filterOutOfBounds(screenWidth, screenHeight)!!

        val row = root.byId("row-3").toUiElement()

        assertThat(row.bounds).isEqualTo(Bounds(x = 0, y = 60, width = 400, height = 80))
        assertThat(row.visibleBounds).isEqualTo(Bounds(x = 0, y = 100, width = 400, height = 40))
        assertThat(row.visibleBounds!!.center()).isEqualTo(Point(x = 200, y = 120))
    }

    @Test
    internal fun `a row entirely above the list is not visible although it is on screen`() {
        val root = screenWithList(node(id = "row-2", bounds = "[0,10][400,90]"))
            .filterOutOfBounds(screenWidth, screenHeight)!!

        assertThat(root.findById("row-2")).isNull()
    }

    @Test
    internal fun `without a clipping container the hierarchy is filtered against the screen only`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(id = "list", bounds = "[0,100][400,700]", children = listOf(node(id = "row-2", bounds = "[0,10][400,90]"))),
            ),
        ).filterOutOfBounds(screenWidth, screenHeight)!!

        assertThat(root.findById("row-2")).isNotNull()
        assertThat(root.byId("row-2").attributes).doesNotContainKey(CLIP_BOUNDS_ATTRIBUTE)
    }

    @Test
    internal fun `a row below the list is not visible although the footer leaves it on screen`() {
        val root = screenWithList(node(id = "row-12", bounds = "[0,720][400,780]"))
            .filterOutOfBounds(screenWidth, screenHeight)!!

        assertThat(root.findById("row-12")).isNull()
        assertThat(root.byId("footer-button").attributes).doesNotContainKey(CLIP_BOUNDS_ATTRIBUTE)
    }

    @Test
    internal fun `a card cut by the bottom of the list is visible down to that edge`() {
        val root = screenWithList(node(id = "card", bounds = "[0,600][400,760]"))
            .filterOutOfBounds(screenWidth, screenHeight)!!

        val card = root.byId("card").toUiElement()

        assertThat(card.visibleBounds).isEqualTo(Bounds(x = 0, y = 600, width = 400, height = 100))
    }

    @Test
    internal fun `nested clipping containers narrow the visible rect together`() {
        val root = node(
            bounds = "[0,0][400,800]",
            clips = true,
            children = listOf(
                node(
                    id = "carousel",
                    bounds = "[50,100][350,300]",
                    clips = true,
                    children = listOf(node(id = "card", bounds = "[300,250][400,350]")),
                ),
            ),
        )

        val card = root.filterOutOfBounds(width = 1000, height = 1000)!!.byId("card").toUiElement()

        assertThat(card.visibleBounds).isEqualTo(Bounds(x = 300, y = 250, width = 50, height = 50))
    }

    @Test
    internal fun `a clipping container with no area does not clip`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(
                    bounds = "[0,0][0,0]",
                    clips = true,
                    children = listOf(node(id = "row", bounds = "[0,100][400,200]")),
                ),
            ),
        )

        val row = root.filterOutOfBounds(width = 400, height = 800)!!.byId("row")

        assertThat(row.attributes).doesNotContainKey(CLIP_BOUNDS_ATTRIBUTE)
    }

    @Test
    internal fun `a node kept only for a visible descendant has no visible rect of its own`() {
        val root = node(
            bounds = "[0,0][400,800]",
            clips = true,
            children = listOf(
                node(
                    id = "overflowing-parent",
                    bounds = "[0,900][400,1000]",
                    children = listOf(node(id = "child", bounds = "[0,100][400,200]")),
                ),
            ),
        )

        val parent = root.filterOutOfBounds(width = 400, height = 1000)!!.byId("overflowing-parent").toUiElement()

        assertThat(parent.visibleBounds).isNull()
    }

    @Test
    internal fun `a container larger than the list is kept when it covers the list`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(
                    id = "list",
                    bounds = "[0,300][400,350]",
                    clips = true,
                    children = listOf(node(id = "huge-section", bounds = "[-1000,-1000][1400,1800]")),
                ),
            ),
        )

        val section = root.filterOutOfBounds(width = 400, height = 800)!!.byId("huge-section").toUiElement()

        assertThat(section.visibleBounds).isEqualTo(Bounds(x = 0, y = 300, width = 400, height = 50))
        assertThat(section.getVisiblePercentage(400, 800)).isEqualTo(1.0)
    }

    @Test
    internal fun `an element covering its clip is fully visible when it is smaller than the screen`() {
        val root = node(
            bounds = "[0,0][402,874]",
            children = listOf(
                node(
                    id = "list",
                    bounds = "[0,100][402,700]",
                    clips = true,
                    children = listOf(node(id = "section", bounds = "[0,50][402,750]")),
                ),
            ),
        )

        val section = root.filterOutOfBounds(width = 402, height = 874)!!.byId("section").toUiElement()

        assertThat(section.getVisiblePercentage(402, 874)).isEqualTo(1.0)
    }

    @Test
    internal fun `visible percentage is measured against the clip, not the screen`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(
                    id = "list",
                    bounds = "[0,100][400,500]",
                    clips = true,
                    children = listOf(node(id = "row", bounds = "[0,400][400,600]")),
                ),
            ),
        )

        val row = root.filterOutOfBounds(width = 400, height = 800)!!.byId("row").toUiElement()

        assertThat(row.getVisiblePercentage(400, 800)).isEqualTo(0.5)
    }

    @Test
    internal fun `nodes that ignore bounds filtering are kept untouched`() {
        val toast = node(id = "toast", bounds = "[0,900][400,1000]").apply {
            attributes["ignoreBoundsFiltering"] = "true"
        }
        val root = node(bounds = "[0,0][400,800]", clips = true, children = listOf(toast))

        val filtered = root.filterOutOfBounds(width = 400, height = 800)!!

        assertThat(filtered.byId("toast")).isSameInstanceAs(toast)
    }

    @Test
    internal fun `filtering again recomputes the clip instead of keeping a stale one`() {
        val row = node(id = "row", bounds = "[0,100][400,200]").apply {
            attributes[CLIP_BOUNDS_ATTRIBUTE] = "[0,150][400,200]"
        }
        val root = node(bounds = "[0,0][400,800]", clips = true, children = listOf(row))

        val once = root.filterOutOfBounds(width = 400, height = 800)!!

        assertThat(once.byId("row").attributes).doesNotContainKey(CLIP_BOUNDS_ATTRIBUTE)
        assertThat(once.filterOutOfBounds(width = 400, height = 800)).isEqualTo(once)
    }

    @Test
    internal fun `a node is the same node after a scroll moves its clip`() {
        val before = node(id = "row", bounds = "[0,50][400,150]").apply {
            attributes[CLIP_BOUNDS_ATTRIBUTE] = "[0,100][400,700]"
        }
        val after = node(id = "row", bounds = "[0,300][400,400]")
        val hierarchy = ViewHierarchy(node(bounds = "[0,0][400,800]", children = listOf(after)))

        assertThat(hierarchy.refreshElement(before)).isSameInstanceAs(after)
    }

    @Test
    internal fun `a row whose bounds centre is under the footer is visible at its visible centre`() {
        val root = screenWithList(node(id = "row", bounds = "[0,600][400,800]"))
        val hierarchy = ViewHierarchy(root.filterOutOfBounds(screenWidth, screenHeight)!!)

        assertThat(hierarchy.isVisible(hierarchy.root.byId("row"))).isTrue()
    }

    @Test
    internal fun `a point in the clipped part of a row hits the footer drawn over it`() {
        val root = screenWithList(node(id = "row", bounds = "[0,600][400,800]"))
        val hierarchy = ViewHierarchy(root.filterOutOfBounds(screenWidth, screenHeight)!!)

        assertThat(hierarchy.getElementAt(hierarchy.root, x = 200, y = 750)?.attributes?.get("resource-id"))
            .isEqualTo("footer-button")
    }

    @Test
    internal fun `a point in the clipped part of a row hits the footer drawn before the list`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(id = "footer-button", bounds = "[0,700][400,800]"),
                node(
                    bounds = "[0,100][400,700]",
                    clips = true,
                    children = listOf(node(id = "row", bounds = "[0,600][400,800]")),
                ),
            ),
        )
        val hierarchy = ViewHierarchy(root.filterOutOfBounds(screenWidth, screenHeight)!!)

        assertThat(hierarchy.getElementAt(hierarchy.root, x = 200, y = 750)?.attributes?.get("resource-id"))
            .isEqualTo("footer-button")
    }

    @Test
    internal fun `a point in the clipped part of a row hits nothing when no footer covers it`() {
        val root = node(
            bounds = "[0,0][400,800]",
            children = listOf(
                node(
                    bounds = "[0,100][400,700]",
                    clips = true,
                    children = listOf(node(id = "row", bounds = "[0,600][400,800]")),
                ),
            ),
        )
        val hierarchy = ViewHierarchy(root.filterOutOfBounds(screenWidth, screenHeight)!!)

        assertThat(hierarchy.getElementAt(hierarchy.root, x = 200, y = 750)).isNull()
    }

    private fun node(
        id: String? = null,
        bounds: String,
        clips: Boolean = false,
        children: List<TreeNode> = emptyList(),
    ): TreeNode {
        val attributes = mutableMapOf("bounds" to bounds)
        id?.let { attributes["resource-id"] = it }
        if (clips) {
            attributes[CLIPS_CHILDREN_ATTRIBUTE] = "true"
        }
        return TreeNode(attributes = attributes, children = children)
    }

    private fun TreeNode.findById(id: String): TreeNode? {
        return aggregate().firstOrNull { it.attributes["resource-id"] == id }
    }

    private fun TreeNode.byId(id: String): TreeNode {
        return findById(id) ?: throw AssertionError("No node with resource-id $id")
    }
}
