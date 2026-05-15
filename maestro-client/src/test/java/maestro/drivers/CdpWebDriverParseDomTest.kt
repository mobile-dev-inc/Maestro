package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CdpWebDriverParseDomTest {

    private val driver = CdpWebDriver(
        isStudio = false,
        screenSize = null,
    )

    private fun node(
        text: String = "",
        bounds: String = "0,0,100,100",
        resourceId: String? = null,
        selected: Boolean? = null,
        synthetic: Boolean? = null,
        ignoreBoundsFiltering: Boolean? = null,
        accessibilityText: String? = null,
        children: List<Map<String, Any>> = emptyList(),
    ): Map<String, Any> {
        val attrs = mutableMapOf<String, Any>(
            "text" to text,
            "bounds" to bounds,
        )
        if (resourceId != null) attrs["resource-id"] = resourceId
        if (selected != null) attrs["selected"] = selected
        if (synthetic != null) attrs["synthetic"] = synthetic
        if (ignoreBoundsFiltering != null) attrs["ignoreBoundsFiltering"] = ignoreBoundsFiltering
        if (accessibilityText != null) attrs["accessibilityText"] = accessibilityText
        return mapOf(
            "attributes" to attrs,
            "children" to children,
        )
    }

    @Test
    fun `parses text and bounds`() {
        val result = driver.parseDomAsTreeNodes(node(text = "Hello", bounds = "10,20,30,40"))

        assertThat(result.attributes["text"]).isEqualTo("Hello")
        assertThat(result.attributes["bounds"]).isEqualTo("10,20,30,40")
        assertThat(result.children).isEmpty()
    }

    @Test
    fun `handles empty text`() {
        val result = driver.parseDomAsTreeNodes(node(text = ""))

        assertThat(result.attributes["text"]).isEqualTo("")
    }

    @Test
    fun `does not include optional attributes when absent`() {
        val result = driver.parseDomAsTreeNodes(node())

        assertThat(result.attributes).containsExactly("text", "", "bounds", "0,0,100,100")
    }

    @Test
    fun `parses resource-id`() {
        val result = driver.parseDomAsTreeNodes(node(resourceId = "my-button"))

        assertThat(result.attributes["resource-id"]).isEqualTo("my-button")
    }

    @Test
    fun `parses selected true`() {
        val result = driver.parseDomAsTreeNodes(node(selected = true))

        assertThat(result.attributes["selected"]).isEqualTo("true")
    }

    @Test
    fun `parses selected false`() {
        val result = driver.parseDomAsTreeNodes(node(selected = false))

        assertThat(result.attributes["selected"]).isEqualTo("false")
    }

    @Test
    fun `parses synthetic`() {
        val result = driver.parseDomAsTreeNodes(node(synthetic = true))

        assertThat(result.attributes["synthetic"]).isEqualTo("true")
    }

    @Test
    fun `parses ignoreBoundsFiltering`() {
        val result = driver.parseDomAsTreeNodes(node(ignoreBoundsFiltering = true))

        assertThat(result.attributes["ignoreBoundsFiltering"]).isEqualTo("true")
    }

    @Test
    fun `parses accessibilityText`() {
        val result = driver.parseDomAsTreeNodes(node(accessibilityText = "Add to favourites"))

        assertThat(result.attributes["accessibilityText"]).isEqualTo("Add to favourites")
    }

    @Test
    fun `accessibilityText with special characters`() {
        val result = driver.parseDomAsTreeNodes(node(accessibilityText = "★ Close (dialog) — 日本語"))

        assertThat(result.attributes["accessibilityText"]).isEqualTo("★ Close (dialog) — 日本語")
    }

    @Test
    fun `accessibilityText does not affect resource-id`() {
        val result = driver.parseDomAsTreeNodes(node(accessibilityText = "label"))

        assertThat(result.attributes).doesNotContainKey("resource-id")
    }

    @Test
    fun `accessibilityText coexists with resource-id`() {
        val result = driver.parseDomAsTreeNodes(
            node(accessibilityText = "Submit", resourceId = "btn-submit")
        )

        assertThat(result.attributes["accessibilityText"]).isEqualTo("Submit")
        assertThat(result.attributes["resource-id"]).isEqualTo("btn-submit")
    }

    @Test
    fun `parses all optional attributes together`() {
        val result = driver.parseDomAsTreeNodes(
            node(
                text = "Click me",
                resourceId = "btn-1",
                selected = true,
                synthetic = true,
                ignoreBoundsFiltering = true,
                accessibilityText = "Submit form",
            )
        )

        assertThat(result.attributes).containsExactly(
            "text", "Click me",
            "bounds", "0,0,100,100",
            "resource-id", "btn-1",
            "selected", "true",
            "synthetic", "true",
            "ignoreBoundsFiltering", "true",
            "accessibilityText", "Submit form",
        )
    }

    @Test
    fun `parses children recursively`() {
        val dom = node(
            text = "parent",
            children = listOf(
                node(text = "child1", accessibilityText = "label1"),
                node(text = "child2"),
            ),
        )

        val result = driver.parseDomAsTreeNodes(dom)

        assertThat(result.children).hasSize(2)
        assertThat(result.children[0].attributes["text"]).isEqualTo("child1")
        assertThat(result.children[0].attributes["accessibilityText"]).isEqualTo("label1")
        assertThat(result.children[1].attributes["text"]).isEqualTo("child2")
        assertThat(result.children[1].attributes).doesNotContainKey("accessibilityText")
    }

    @Test
    fun `parses deeply nested children`() {
        val dom = node(
            text = "root",
            children = listOf(
                node(
                    text = "level1",
                    children = listOf(
                        node(text = "level2", accessibilityText = "deep label"),
                    ),
                ),
            ),
        )

        val result = driver.parseDomAsTreeNodes(dom)

        val grandchild = result.children[0].children[0]
        assertThat(grandchild.attributes["text"]).isEqualTo("level2")
        assertThat(grandchild.attributes["accessibilityText"]).isEqualTo("deep label")
    }

    @Test
    fun `handles empty children list`() {
        val result = driver.parseDomAsTreeNodes(node(children = emptyList()))

        assertThat(result.children).isEmpty()
    }

    @Test
    fun `skips null optional attribute values`() {
        val attrs = mutableMapOf<String, Any?>(
            "text" to "hi",
            "bounds" to "0,0,1,1",
            "resource-id" to null,
            "selected" to null,
            "synthetic" to null,
            "ignoreBoundsFiltering" to null,
            "accessibilityText" to null,
        )
        @Suppress("UNCHECKED_CAST")
        val dom = mapOf(
            "attributes" to (attrs as Map<String, Any>),
            "children" to emptyList<Map<String, Any>>(),
        )

        val result = driver.parseDomAsTreeNodes(dom)

        assertThat(result.attributes).containsExactly("text", "hi", "bounds", "0,0,1,1")
    }
}
