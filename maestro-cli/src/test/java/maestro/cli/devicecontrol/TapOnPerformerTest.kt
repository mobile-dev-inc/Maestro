package maestro.cli.devicecontrol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TapOnPerformerTest {

    @Test
    fun `toSelector uses fuzzy regex by default`() {
        val selector = TapOnPerformer.toSelector(
            TapOnPerformer.Request(text = "Login")
        )

        assertThat(selector.textRegex).isEqualTo(".*Login.*")
        assertThat(selector.idRegex).isNull()
    }

    @Test
    fun `toSelector uses exact values when fuzzy matching disabled`() {
        val selector = TapOnPerformer.toSelector(
            TapOnPerformer.Request(
                text = "Login",
                id = "button_login",
                useFuzzyMatching = false,
            )
        )

        assertThat(selector.textRegex).isEqualTo("Login")
        assertThat(selector.idRegex).isEqualTo("button_login")
    }

    @Test
    fun `escapeRegex escapes regex characters`() {
        val escaped = TapOnPerformer.escapeRegex("Login (Primary)+")
        assertThat(escaped).isEqualTo("Login \\(Primary\\)\\+")
    }
}
