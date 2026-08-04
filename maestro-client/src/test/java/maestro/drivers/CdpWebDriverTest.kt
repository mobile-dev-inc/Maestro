package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CdpWebDriverTest {

    private fun makeDriver() = CdpWebDriver(isStudio = false, isHeadless = false, screenSize = null)

    @Test
    fun `parseDomAsTreeNodes handles bounds as String`() {
        val dom = mapOf(
            "attributes" to mapOf("text" to "Button", "bounds" to "[10,20][110,60]"),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[10,20][110,60]")
    }

    @Test
    fun `parseDomAsTreeNodes handles bounds as LinkedHashMap`() {
        val dom = mapOf(
            "attributes" to mapOf(
                "text" to "Button",
                "bounds" to mapOf("left" to 10, "top" to 20, "right" to 110, "bottom" to 60),
            ),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[10,20][110,60]")
    }

    @Test
    fun `parseDomAsTreeNodes uses fallback for unknown bounds type`() {
        val dom = mapOf(
            "attributes" to mapOf("text" to "Button", "bounds" to 42L),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[0,0][0,0]")
    }
}
