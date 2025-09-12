package maestro.test

import maestro.Filters
import maestro.TreeNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals

class DeepestMatchingElementTest {

    @Test
    fun `deepestMatchingElement should return only the deepest matching elements`() {
        // Given: A hierarchy with nested elements that match the filter
        val root = TreeNode(
            attributes = mutableMapOf("text" to "some_text"),
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf("text" to "some_text", "id" to "some_id"),
                    children = listOf(
                        TreeNode(
                            attributes = mutableMapOf("text" to "some_text", "id" to "")
                        )
                    )
                )
            )
        )

        val textFilter = Filters.textMatches("some_text".toRegex())
        val deepestFilter = Filters.deepestMatchingElement(textFilter)

        // When: Apply the deepestMatchingElement filter
        val result = deepestFilter(listOf(root))

        // Then: Should return only the deepest matching elements
        // Expected: Only the innermost element with text="some_text" and id=""
        assertEquals(1, result.size)
        assertEquals("", result[0].attributes["id"])
        assertEquals("some_text", result[0].attributes["text"])
    }

    @Test
    fun `deepestMatchingElement should return parent if no children match`() {
        // Given: A hierarchy where only the parent matches
        val root = TreeNode(
            attributes = mutableMapOf("text" to "some_text"),
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf("text" to "different_text")
                )
            )
        )

        val textFilter = Filters.textMatches("some_text".toRegex())
        val deepestFilter = Filters.deepestMatchingElement(textFilter)

        // When: Apply the deepestMatchingElement filter
        val result = deepestFilter(listOf(root))

        // Then: Should return the parent element
        assertEquals(1, result.size)
        assertEquals("some_text", result[0].attributes["text"])
    }

    @Test
    fun `deepestMatchingElement should return empty list if no elements match`() {
        // Given: A hierarchy where no elements match
        val root = TreeNode(
            attributes = mutableMapOf("text" to "different_text"),
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf("text" to "another_text")
                )
            )
        )

        val textFilter = Filters.textMatches("some_text".toRegex())
        val deepestFilter = Filters.deepestMatchingElement(textFilter)

        // When: Apply the deepestMatchingElement filter
        val result = deepestFilter(listOf(root))

        // Then: Should return empty list
        assertEquals(0, result.size)
    }

    @Test
    fun `deepestMatchingElement should handle multiple root elements correctly`() {
        // Given: Multiple root elements with different nesting levels
        val root1 = TreeNode(
            attributes = mutableMapOf("text" to "some_text")
        )
        val root2 = TreeNode(
            attributes = mutableMapOf("text" to "some_text"),
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf("text" to "some_text", "id" to "some_id"),
                    children = listOf(
                        TreeNode(
                            attributes = mutableMapOf("text" to "some_text", "id" to "")
                        )
                    )
                )
            )
        )

        val textFilter = Filters.textMatches("some_text".toRegex())
        val deepestFilter = Filters.deepestMatchingElement(textFilter)

        // When: Apply the deepestMatchingElement filter
        val result = deepestFilter(listOf(root1, root2))

        // Then: Should return only the deepest matching elements
        // Expected: root1 (no children) and the innermost element from root2
        assertEquals(2, result.size)
        assertEquals("some_text", result[0].attributes["text"])
        assertEquals("", result[1].attributes["id"])
        assertEquals("some_text", result[1].attributes["text"])
    }

    @Test
    fun `deepestMatchingElement should match integration test scenario exactly`() {
        val element0 = TreeNode(
            attributes = mutableMapOf("text" to "some_text", "bounds" to "0,0,200,200")
        )
        val element3 = TreeNode(
            attributes = mutableMapOf("text" to "some_text", "resource-id" to "", "bounds" to "50,50,150,150")
        )
        val element2 = TreeNode(
            attributes = mutableMapOf("text" to "some_text", "resource-id" to "some_id", "bounds" to "0,0,200,200"),
            children = listOf(element3)
        )
        val element1 = TreeNode(
            attributes = mutableMapOf("text" to "some_text", "bounds" to "0,0,200,200"),
            children = listOf(element2)
        )

        val textFilter = Filters.textMatches("some_text".toRegex())
        val deepestFilter = Filters.deepestMatchingElement(textFilter)

        // When: Apply the deepestMatchingElement filter
        val result = deepestFilter(listOf(element0, element1))

        // Then: Should return only the deepest matching elements
        // Expected: element0 (no children) and element3 (deepest child of element1)
        assertEquals(2, result.size)
        assertEquals("some_text", result[0].attributes["text"])
        assertEquals("", result[1].attributes["resource-id"])
        assertEquals("some_text", result[1].attributes["text"])
    }
}
