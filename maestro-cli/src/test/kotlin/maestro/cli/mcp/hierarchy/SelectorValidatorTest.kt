package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SelectorValidatorTest {

    @Test
    fun `passes when every text selector matches some on-screen text`() {
        val snapshot = snapshotOf("Sign in", "Create account")
        val yaml = """
            - launchApp
            - tapOn:
                text: "Sign in"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `treats text selector as regex with partial matching`() {
        val snapshot = snapshotOf("RNR 352 - Expo Launch with Cedric van Putten")
        val yaml = """
            - tapOn:
                text: "RNR 352"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `flags selectors that do not appear anywhere in the snapshot`() {
        val snapshot = snapshotOf("Sign in", "Create account", "Help")
        val yaml = """
            - tapOn:
                text: "Favorite"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot)
        assertThat(result).isInstanceOf(SelectorValidator.Result.Miss::class.java)
        val findings = (result as SelectorValidator.Result.Miss).findings
        assertThat(findings).hasSize(1)
        assertThat(findings.single().selector).isEqualTo("Favorite")
    }

    @Test
    fun `suggests close matches via case-insensitive substring overlap`() {
        val snapshot = snapshotOf(
            "Add to favorites",
            "Remove from favorites",
            "Unrelated string",
        )
        // Capital F in selector — case-sensitive regex against lowercase
        // "favorites" in the snapshot misses, so we surface the case-insensitive
        // substring matches as suggestions.
        val yaml = """
            - tapOn:
                text: "Favorite"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().suggestions)
            .containsExactly("Add to favorites", "Remove from favorites").inOrder()
    }

    @Test
    fun `recurses through nested selectors like below`() {
        val snapshot = snapshotOf("Email", "Password")
        val yaml = """
            - tapOn:
                text: "Ghost"
                below:
                  text: "Email"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.map { it.selector }).containsExactly("Ghost")
    }

    @Test
    fun `falls back to literal substring when selector is invalid regex`() {
        val snapshot = snapshotOf("Price: \$9.99")
        val yaml = """
            - assertVisible:
                text: "Price: \${'$'}9.99"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `empty snapshot with no on-screen overlap surfaces a peek list`() {
        val snapshot = snapshotOf("One", "Two", "Three")
        val yaml = """
            - tapOn:
                text: "xyz-nowhere"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().suggestions).containsExactly("One", "Two", "Three")
    }

    @Test
    fun `no text selectors in the flow is Ok`() {
        val snapshot = snapshotOf("whatever")
        val yaml = """
            - launchApp
            - scroll
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `invalid yaml falls through cleanly instead of throwing`() {
        val snapshot = snapshotOf("whatever")
        val yaml = ": : ::"

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    private fun snapshotOf(vararg texts: String) = HierarchySnapshotStore.Snapshot(
        deviceId = "device-1",
        texts = texts.toSet(),
    )
}
