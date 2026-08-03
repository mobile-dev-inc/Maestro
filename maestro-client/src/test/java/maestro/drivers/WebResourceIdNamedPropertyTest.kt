package maestro.drivers

import com.google.common.truth.Truth.assertThat
import maestro.drivers.MaestroWebJsHarness.resolveResourceId
import org.junit.jupiter.api.Test

/**
 * Pins the maestro-web.js side of #2944: HTMLFormElement named-property access
 * makes form.id / form.name / form.title return a child control (e.g.
 * `<form><input name="id">`) instead of the attribute string. resource-id must
 * stay a string and keep the element's real attribute value.
 */
class WebResourceIdNamedPropertyTest {

    @Test
    fun `a form's real id survives a child input named id`() {
        val resourceId = resolveResourceId(
            tagName = "form",
            id = "loginForm",
            clobberedProps = setOf("id"),
        )
        assertThat(resourceId).isEqualTo("loginForm")
    }

    @Test
    fun `clobbered id with no id attribute falls through to the next candidate`() {
        val resourceId = resolveResourceId(
            tagName = "form",
            attributes = mapOf("data-testid" to "login-form"),
            clobberedProps = setOf("id"),
        )
        assertThat(resourceId).isEqualTo("login-form")
    }

    @Test
    fun `clobbered id with no identifying attributes yields no resource-id`() {
        val resourceId = resolveResourceId(
            tagName = "form",
            clobberedProps = setOf("id"),
        )
        assertThat(resourceId).isNull()
    }

    @Test
    fun `name and title stay strings when clobbered by child controls`() {
        val resourceId = resolveResourceId(
            tagName = "form",
            name = "checkout",
            title = "Checkout form",
            clobberedProps = setOf("name", "title"),
        )
        assertThat(resourceId).isEqualTo("checkout")
    }
}
