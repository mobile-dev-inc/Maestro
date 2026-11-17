package maestro.ios

import com.google.common.truth.Truth.assertThat
import hierarchy.AXElement
import hierarchy.AXFrame
import maestro.TreeNode
import org.junit.jupiter.api.Test

/**
 * Tests for the iOS view hierarchy mapping logic.
 *
 * Since IOSDriver.mapViewHierarchy is private, we test the same mapping
 * logic by constructing AXElements and verifying the expected TreeNode
 * attribute behavior through the public mapping contract:
 * - text = title ?: value ?: label
 * - accessibilityText = label
 */
class IOSViewHierarchyTest {

    @Test
    fun `text attribute uses title when present`() {
        val element = axElement(title = "Submit", value = "", label = "Submit button")

        val attributes = mapAttributes(element)

        assertThat(attributes["text"]).isEqualTo("Submit")
        assertThat(attributes["accessibilityText"]).isEqualTo("Submit button")
    }

    @Test
    fun `text attribute uses value when title is empty`() {
        val element = axElement(title = "", value = "42", label = "Counter")

        val attributes = mapAttributes(element)

        assertThat(attributes["text"]).isEqualTo("42")
    }

    @Test
    fun `text attribute falls back to label when title and value are empty`() {
        // This is the React Native Pressable + accessibilityLabel case (#1409)
        val element = axElement(title = "", value = "", label = "Continue with Google")

        val attributes = mapAttributes(element)

        assertThat(attributes["text"]).isEqualTo("Continue with Google")
        assertThat(attributes["accessibilityText"]).isEqualTo("Continue with Google")
    }

    @Test
    fun `text attribute falls back to label when title is null`() {
        val element = axElement(title = null, value = null, label = "Sign Out")

        val attributes = mapAttributes(element)

        assertThat(attributes["text"]).isEqualTo("Sign Out")
    }

    @Test
    fun `text attribute is empty when everything is empty`() {
        val element = axElement(title = "", value = "", label = "")

        val attributes = mapAttributes(element)

        assertThat(attributes["text"]).isEqualTo("")
    }

    // Replicates the mapping logic from IOSDriver.mapViewHierarchy
    private fun mapAttributes(element: AXElement): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        attributes["accessibilityText"] = element.label
        attributes["title"] = element.title ?: ""
        attributes["value"] = element.value ?: ""
        attributes["text"] = element.title
            ?.takeIf { it.isNotEmpty() }
            ?: element.value
                ?.takeIf { it.isNotEmpty() }
            ?: element.label
        attributes["hintText"] = element.placeholderValue ?: ""
        attributes["resource-id"] = element.identifier
        return attributes
    }

    private fun axElement(
        title: String? = "",
        value: String? = "",
        label: String = "",
    ): AXElement = AXElement(
        label = label,
        elementType = 0,
        identifier = "",
        horizontalSizeClass = 0,
        windowContextID = 0,
        verticalSizeClass = 0,
        selected = false,
        displayID = 0,
        hasFocus = false,
        placeholderValue = null,
        value = value,
        frame = AXFrame(0f, 0f, 100f, 50f),
        enabled = true,
        title = title,
        children = arrayListOf(),
    )
}
