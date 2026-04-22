package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class SelectorValidatorTest {

    @Test
    fun `passes when every text selector matches on-screen text`() {
        val snapshot = snapshotOf("Sign in", "Create account")
        val yaml = "- tapOn:\n    text: \"Sign in\""
        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `flags truncated text (Maestro uses full-string regex matches)`() {
        val snapshot = snapshotOf("RNR 352 - Expo Launch with Cedric van Putten")
        val yaml = "- tapOn:\n    text: \"RNR 352\""

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().selector).isEqualTo("RNR 352")
        assertThat(result.findings.single().suggestions).contains("RNR 352 - Expo Launch with Cedric van Putten")
    }

    @Test
    fun `flags selectors not present in the snapshot`() {
        val snapshot = snapshotOf("Sign in", "Create account", "Help")
        val yaml = "- tapOn:\n    text: \"Favorite\""

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().selector).isEqualTo("Favorite")
    }

    @Test
    fun `matches case-insensitively like the runner`() {
        val snapshot = snapshotOf("Favorite")
        val yaml = "- tapOn:\n    text: \"favorite\""
        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `suggests close matches via bidirectional substring overlap`() {
        val snapshot = snapshotOf("Add to favorites page", "Remove from favorites", "Unrelated")
        val yaml = "- tapOn:\n    text: \"favorite\""

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().suggestions)
            .containsExactly("Add to favorites page", "Remove from favorites").inOrder()
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
    fun `deduplicates repeated selectors`() {
        val snapshot = snapshotOf("Sign in")
        val yaml = """
            - tapOn:
                text: "Ghost"
            - tapOn:
                text: "Ghost"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings).hasSize(1)
    }

    @Test
    fun `skips selectors containing env-var interpolation`() {
        val snapshot = snapshotOf("Welcome, Pedro")
        val yaml = "- assertVisible:\n    text: \"Welcome, ${'$'}{USER}\""
        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `falls back to literal equality when selector is invalid regex`() {
        val snapshot = snapshotOf("Price: \$9.99")
        val yaml = "- assertVisible:\n    text: \"Price: \${'$'}9.99\""
        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `peek list surfaces when selector has no textual overlap`() {
        val snapshot = snapshotOf("One", "Two", "Three")
        val yaml = "- tapOn:\n    text: \"xyz-nowhere\""

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().suggestions).containsExactly("One", "Two", "Three")
    }

    @Test
    fun `flow without text selectors is Ok`() {
        val snapshot = snapshotOf("whatever")
        val yaml = "- launchApp\n- scroll"
        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `invalid yaml falls through`() {
        assertThat(SelectorValidator.validate(": : ::", snapshotOf("x"))).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `matches hintText and accessibilityText`() {
        val root = TreeNode(children = listOf(
            TreeNode(attributes = mutableMapOf("hintText" to "Search here")),
            TreeNode(attributes = mutableMapOf("accessibilityText" to "Menu button")),
        ))
        val yaml = """
            - tapOn:
                text: "Search here"
            - tapOn:
                text: "Menu button"
        """.trimIndent()
        assertThat(SelectorValidator.validate(yaml, HierarchySnapshotStore.Snapshot(root)))
            .isEqualTo(SelectorValidator.Result.Ok)
    }

    private fun snapshotOf(vararg texts: String) = HierarchySnapshotStore.Snapshot(
        TreeNode(children = texts.map { TreeNode(attributes = mutableMapOf("text" to it)) }),
    )
}
