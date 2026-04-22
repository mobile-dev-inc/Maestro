package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class SelectorValidatorTest {

    @Test
    fun `passes when every text selector matches some on-screen text`() {
        val snapshot = snapshotOfAll("Sign in", "Create account")
        val yaml = """
            - launchApp
            - tapOn:
                text: "Sign in"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `flags truncated text that only partially matches (matches runner's full-string semantics)`() {
        // Matches Simon's RNR 352 failure: Maestro's text matcher uses
        // regex.matches (full-string), so "RNR 352" does NOT hit an element
        // whose text is "RNR 352 - Expo Launch with Cedric van Putten".
        val snapshot = snapshotOfAll("RNR 352 - Expo Launch with Cedric van Putten")
        val yaml = """
            - tapOn:
                text: "RNR 352"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().selector).isEqualTo("RNR 352")
        assertThat(result.findings.single().suggestions)
            .contains("RNR 352 - Expo Launch with Cedric van Putten")
    }

    @Test
    fun `flags selectors that do not appear anywhere in the snapshot`() {
        val snapshot = snapshotOfAll("Sign in", "Create account", "Help")
        val yaml = """
            - tapOn:
                text: "Favorite"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().selector).isEqualTo("Favorite")
    }

    @Test
    fun `suggests close matches via case-insensitive substring overlap`() {
        val snapshot = snapshotOfAll(
            "Add to favorites",
            "Remove from favorites",
            "Unrelated string",
        )
        // Capital F in selector — case-sensitive regex against lowercase
        // "favorites" misses, so we surface substring matches.
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
        val snapshot = snapshotOfAll("Email", "Password")
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
        val snapshot = snapshotOfAll("Sign in")
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
    fun `falls back to literal equality when selector is invalid regex`() {
        val snapshot = snapshotOfAll("Price: \$9.99")
        val yaml = """
            - assertVisible:
                text: "Price: \${'$'}9.99"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `snapshot with no textual overlap surfaces a fallback peek list`() {
        val snapshot = snapshotOfAll("One", "Two", "Three")
        val yaml = """
            - tapOn:
                text: "xyz-nowhere"
        """.trimIndent()

        val result = SelectorValidator.validate(yaml, snapshot) as SelectorValidator.Result.Miss
        assertThat(result.findings.single().suggestions).containsExactly("One", "Two", "Three")
    }

    @Test
    fun `no text selectors in the flow is Ok`() {
        val snapshot = snapshotOfAll("whatever")
        val yaml = """
            - launchApp
            - scroll
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `invalid yaml falls through cleanly instead of throwing`() {
        val snapshot = snapshotOfAll("whatever")
        val yaml = ": : ::"

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `matches case-insensitively (same as runner)`() {
        val snapshot = snapshotOfAll("Favorite")
        val yaml = """
            - tapOn:
                text: "favorite"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `skips selectors containing env-var interpolation`() {
        val snapshot = snapshotOfAll("Welcome, Pedro")
        val yaml = """
            - assertVisible:
                text: "Welcome, ${'$'}{USER}"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    @Test
    fun `matches against hintText and accessibilityText the same as text`() {
        val root = TreeNode(
            children = listOf(
                TreeNode(attributes = mutableMapOf("hintText" to "Search here")),
                TreeNode(attributes = mutableMapOf("accessibilityText" to "Menu button")),
            ),
        )
        val snapshot = HierarchySnapshotStore.Snapshot(root)
        val yaml = """
            - tapOn:
                text: "Search here"
            - tapOn:
                text: "Menu button"
        """.trimIndent()

        assertThat(SelectorValidator.validate(yaml, snapshot)).isEqualTo(SelectorValidator.Result.Ok)
    }

    private fun snapshotOfAll(vararg texts: String): HierarchySnapshotStore.Snapshot {
        val root = TreeNode(
            children = texts.map { TreeNode(attributes = mutableMapOf("text" to it)) },
        )
        return HierarchySnapshotStore.Snapshot(root)
    }
}
