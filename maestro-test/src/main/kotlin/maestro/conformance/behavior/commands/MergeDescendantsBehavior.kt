package maestro.conformance.behavior.commands

import maestro.TreeNode
import maestro.conformance.behavior.*

/**
 * Reproduces mobile-dev-inc/Maestro#2704 — "Android Compose `mergeDescendants`".
 *
 * A Compose node with `Modifier.semantics(mergeDescendants = true)` should expose its children's
 * combined text on the merged parent node — TalkBack announces "Line 1, Line 2" and Layout
 * Inspector shows the merged content. The bug: in Maestro's hierarchy the merged parent's
 * text / accessibilityText / hintText come through EMPTY while the children keep their individual
 * texts, so no single node carries the merged content and `assertVisible: text: "Line 1, Line 2"`
 * fails.
 *
 * Oracle (out-of-band of the driver action): walk `contentDescriptor()` and require that ONE node
 * carries BOTH child texts in its accessibility attributes. This is robust to whatever separator
 * Compose uses to join the merged text. Today this is expected to FAIL on compose — that red cell
 * IS the reproduction. When the driver learns to surface merged Compose semantics, it turns green
 * with no test change (regression guard).
 *
 * Compose-only: `mergeDescendants` has no native counterpart, so the behavior is scoped to the
 * compose framework via [frameworks]; the native matrix cell is left blank rather than failing.
 */
class MergeDescendantsBehavior : CommandBehavior {
    override val name = "mergeDescendants"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE
    override val frameworks = setOf("compose")

    private val parts = listOf("Line 1", "Line 2")

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // Poll briefly so the screen is fully composed before we read the tree.
        val deadlineMs = System.currentTimeMillis() + 3_000L
        var mergedNodeText: String? = null
        do {
            val tree = ctx.driver.contentDescriptor()
            mergedNodeText = findNodeCarryingAllParts(tree)
            if (mergedNodeText != null) break
            if (System.currentTimeMillis() < deadlineMs) Thread.sleep(200)
        } while (System.currentTimeMillis() < deadlineMs)

        val expected = mapOf("singleNodeContainsAll" to parts)
        val actual = mapOf("mergedNode" to (mergedNodeText ?: "<none — children expose texts separately>"))

        return if (mergedNodeText != null) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            CommandOutcome(
                Verdict.fail("no single node exposes merged a11y text $parts — reproduces #2704 " +
                    "(Compose mergeDescendants not surfaced in the hierarchy)"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }

    /** Returns a node's combined a11y text iff that single node carries every part; else null. */
    private fun findNodeCarryingAllParts(node: TreeNode): String? {
        val a = node.attributes
        val blob = listOf("text", "accessibilityText", "content-desc", "hintText")
            .mapNotNull { a[it] }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        if (parts.all { blob.contains(it) }) return blob
        for (child in node.children) findNodeCarryingAllParts(child)?.let { return it }
        return null
    }
}
