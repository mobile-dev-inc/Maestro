package maestro.android.chromedevtools

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import maestro.UiElement.Companion.toUiElementOrNull
import org.junit.jupiter.api.Test

class AndroidWebViewHierarchyTest {

    @Test
    fun testMergeHierarchies1() {
        testMergeHierarchies(
            "[1-2,text=foo]",
            "",
            "[1-2,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies2() {
        testMergeHierarchies(
            "[1-2,text=foo]",
            "[1-2,text=bar]",
            "[1-2,text=foo][1-2,text=bar]"
        )
    }

    @Test
    fun testMergeHierarchies3() {
        testMergeHierarchies(
            "[1-3,text=foo]",
            "[2-4,text=foo]",
            "[1-3,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies4() {
        testMergeHierarchies(
            "[1-2,text=foobar]",
            "[1-2,text=foo]",
            "[1-2,text=foobar]"
        )
    }

    @Test
    fun testMergeHierarchies5() {
        testMergeHierarchies(
            "[1-3,text=foobar]",
            "[2-4,text=foo]",
            "[1-3,text=foobar]"
        )
    }

    @Test
    fun testMergeHierarchies6() {
        testMergeHierarchies(
            "[1-2,text=foobar]",
            "[2-4,text=foo]",
            "[1-2,text=foobar][2-4,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies7() {
        testMergeHierarchies(
            "[1-2,text=foo]",
            "[1-2,id=foo]",
            "[1-2,text=foo,id=foo]"
        )
    }

    @Test
    fun testMergeHierarchies8() {
        testMergeHierarchies(
            "[1-2,text=foo][2-3,text=bar]",
            "[2-3,text=foo]",
            "[1-2,text=foo][2-3,text=bar][2-3,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies9() {
        testMergeHierarchies(
            "[1-2,text=foo,id=bar]",
            "[1-2,text=foo]",
            "[1-2,text=foo,id=bar]"
        )
    }

    @Test
    fun testMergeHierarchies10() {
        testMergeHierarchies(
            "[1-3,text=foobar]",
            "[2-4,text=foo,id=bar]",
            "[1-3,text=foobar,id=bar]"
        )
    }

    @Test
    fun testMergeHierarchies11() {
        testMergeHierarchies(
            "[1-2,text=foo][2-3,text=bar]",
            "[1-2,id=foo][2-3,id=bar]",
            "[1-2,text=foo,id=foo][2-3,text=bar,id=bar]"
        )
    }

    @Test
    fun testMergeHierarchies12() {
        testMergeHierarchies(
            "[1-2,text=foo]",
            "[2-3,text=foo]",
            "[1-2,text=foo][2-3,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies13() {
        testMergeHierarchies(
            "[1-2,text=foo]",
            "[2-3,text=]",
            "[1-2,text=foo]"
        )
    }

    @Test
    fun testMergeHierarchies14() {
        testMergeHierarchies(
            "[1-2,text=foo,id=][3-4,text=,id=bar]",
            "[1-2,text=foo,id=bar][3-4,text=foo,id=bar]",
            "[1-2,text=foo,id=bar][3-4,text=foo,id=bar]"
        )
    }

    @Test
    fun testMergeHierarchies15() {
        testMergeHierarchies(
            "[1-2,accessibilityText=foo]",
            "[1-2,text=foo,id=bar]",
            "[1-2,accessibilityText=foo,id=bar]"
        )
    }

    @Test
    fun testMergeHierarchies16() {
        testMergeHierarchies(
            "[1-2,hintText=foo]",
            "[1-2,text=foo,id=bar]",
            "[1-2,hintText=foo,id=bar]"
        )
    }

    private fun testMergeHierarchies(
        base: String,
        webview: String,
        expected: String,
    ) {
        val baseHierarchy = stringToHierarchy(base)
        val webviewHierarchy = stringToHierarchy(webview)

        val mergedHierarchy = AndroidWebViewHierarchy.mergeHierarchies(baseHierarchy, webviewHierarchy.children)
        assertThat(hierarchyToString(mergedHierarchy)).isEqualTo(expected)
    }

    @Test
    fun stringToHierarchyTest() {
        val hierarchy1 = stringToHierarchy("[0-1]")
        assertThat(hierarchy1.children).hasSize(1)
        assertThat(hierarchy1.children[0]).isEqualTo(TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[0,0][1,100]"
            )
        ))
        assertThat(hierarchyToString(hierarchy1)).isEqualTo("[0-1]")

        val hierarchy2 = stringToHierarchy("[1-2,text=foo]")
        assertThat(hierarchy2.children).containsExactly(TreeNode(
            attributes = mutableMapOf(
                "text" to "foo",
                "bounds" to "[1,0][2,100]"
            )
        )).inOrder()
        assertThat(hierarchyToString(hierarchy2)).isEqualTo("[1-2,text=foo]")

        val hierarchy3 = stringToHierarchy("[1-2,text=foo,id=bar]")
        assertThat(hierarchy3.children).containsExactly(TreeNode(
            attributes = mutableMapOf(
                "text" to "foo",
                "resource-id" to "bar",
                "bounds" to "[1,0][2,100]"
            )
        )).inOrder()
        assertThat(hierarchyToString(hierarchy3)).isEqualTo("[1-2,text=foo,id=bar]")

        val hierarchy4 = stringToHierarchy("[1-2,text=foo,id=bar][2-3,text=baz,id=boo]")
        assertThat(hierarchy4.children).containsExactly(TreeNode(
            attributes = mutableMapOf(
                "text" to "foo",
                "resource-id" to "bar",
                "bounds" to "[1,0][2,100]"
            )
        ), TreeNode(
            attributes = mutableMapOf(
                "text" to "baz",
                "resource-id" to "boo",
                "bounds" to "[2,0][3,100]"
            )
        )).inOrder()
        assertThat(hierarchyToString(hierarchy4)).isEqualTo("[1-2,text=foo,id=bar][2-3,text=baz,id=boo]")

        val hierarchy5 = stringToHierarchy("")
        assertThat(hierarchy5.children).isEmpty()
        assertThat(hierarchyToString(hierarchy5)).isEqualTo("")
    }

    private fun hierarchyToString(hierarchy: TreeNode): String {
        return hierarchy.aggregate().mapNotNull {
            it.toUiElementOrNull()
        }.map {
            assertThat(it.bounds.y).isEqualTo(0)
            assertThat(it.bounds.height).isEqualTo(100)
            val range = it.bounds.x.toString() + "-" + (it.bounds.x + it.bounds.width).toString()
            val attributes = it.treeNode.attributes.entries.filter { it.key != "bounds" }.map { (key, value) ->
                val finalKey = when (key) {
                    "resource-id" -> "id"
                    else -> key
                }
                "$finalKey=$value"
            }
            return@map "[${(listOf(range) + attributes).joinToString(",")}]"
        }.joinToString("")
    }

    private fun stringToHierarchy(hierarchy: String): TreeNode {
        val nodes = hierarchy.split("]")
            .map { it.trim('[', ']') }
            .filter { it.isNotEmpty() }
            .map {
                val parts = it.split(',')
                val (start, end) = parts.first().split('-').map(String::toInt)
                val bounds = "[$start,0][$end,100]"
                val attributes = parts.drop(1).map {
                    val (key, value) = it.split("=")
                    val finalKey = when (key) {
                        "id" -> "resource-id"
                        else -> key
                    }
                    finalKey to value
                }.toMap() + mapOf("bounds" to bounds)
                TreeNode(attributes.toMutableMap())
            }
        return TreeNode(children = nodes)
    }
}