package maestro.drivers

import com.google.common.truth.Truth.assertThat
import maestro.drivers.MaestroWebJsHarness.resolveResourceId
import org.junit.jupiter.api.Test

/**
 * Flutter web puts a developer's Semantics(identifier:) on the DOM attribute
 * flt-semantics-identifier, while the id attribute is an internal handle of
 * the form flt-semantic-node-N that is reassigned between frames. resource-id
 * must therefore prefer the stable identifier, and must leave every existing
 * (non-Flutter) resolution path untouched.
 */
class FlutterWebSemanticsIdentifierTest {

    @Test
    fun `resource-id is taken from flt-semantics-identifier when present`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("flt-semantics-identifier" to "login_button"),
        )
        assertThat(resourceId).isEqualTo("login_button")
    }

    @Test
    fun `flt-semantics-identifier wins over the unstable node id and aria-label`() {
        val resourceId = resolveResourceId(
            id = "flt-semantic-node-7",
            ariaLabel = "Log in",
            attributes = mapOf("flt-semantics-identifier" to "login_button"),
        )
        assertThat(resourceId).isEqualTo("login_button")
    }

    @Test
    fun `node id is still used when there is no flt-semantics-identifier`() {
        val resourceId = resolveResourceId(id = "native-dom-id")
        assertThat(resourceId).isEqualTo("native-dom-id")
    }

    @Test
    fun `data-testid is still used when present and no flt identifier exists`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("data-testid" to "submit"),
        )
        assertThat(resourceId).isEqualTo("submit")
    }

    @Test
    fun `an empty flt-semantics-identifier falls through to the next candidate`() {
        val resourceId = resolveResourceId(
            id = "native-dom-id",
            attributes = mapOf("flt-semantics-identifier" to ""),
        )
        assertThat(resourceId).isEqualTo("native-dom-id")
    }

    @Test
    fun `an empty data-testid still yields an empty resource-id (unchanged)`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("data-testid" to ""),
        )
        assertThat(resourceId).isEqualTo("")
    }

    @Test
    fun `a node with no identifying attributes gets no resource-id`() {
        val resourceId = resolveResourceId()
        assertThat(resourceId).isNull()
    }
}
