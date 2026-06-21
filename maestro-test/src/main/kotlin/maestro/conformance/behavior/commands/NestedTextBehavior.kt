package maestro.conformance.behavior.commands

import maestro.TreeNode
import maestro.conformance.behavior.*

/**
 * Reproduces mobile-dev-inc/Maestro#821 — nested <Text> inside <Text> in React Native.
 *
 * RN renders `<Text>Outer <Text onPress=...>Inner</Text></Text>` as a SINGLE Android TextView backed
 * by a Spannable. The inner <Text> is a span (a character range), not a separate Android view — so
 * it has no own AccessibilityNodeInfo, no resource-id/content-desc, and no bounds. The hierarchy has
 * exactly ONE node whose text is the concatenated "Outer Inner"; "Inner" never appears as a node on
 * its own, so it cannot be individually resolved or tapped.
 *
 * Oracle: (1) the GREEN standalone <Text> resolves by testID/label; (2) NO node's text is exactly
 * "Inner" (the inner segment exists only merged inside the outer node); (3) a node carries the
 * merged "Outer"+"Inner" text (so a missing screen can't pass vacuously). PASS = reproduction held.
 * React-Native-only; scoped via [frameworks].
 */
class NestedTextBehavior : CommandBehavior {
    override val name = "nestedText"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE
    override val frameworks = setOf("react-native")

    private val standaloneId = "standalone_text"
    private val innerSegment = "Inner"
    private val outerSegment = "Outer"

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val deadlineMs = System.currentTimeMillis() + 3_000L
        var standaloneFound = false
        var innerAsOwnNode = false
        var mergedTextPresent = false
        do {
            val tree = ctx.driver.contentDescriptor()
            standaloneFound = TreeBounds.find(tree, standaloneId) != null
            innerAsOwnNode = hasNodeWithExactText(tree, innerSegment)
            mergedTextPresent = hasNodeWithTextContainingAll(tree, listOf(outerSegment, innerSegment))
            if (standaloneFound && mergedTextPresent) break
            if (System.currentTimeMillis() < deadlineMs) Thread.sleep(200)
        } while (System.currentTimeMillis() < deadlineMs)

        val expected = mapOf(
            "standaloneResolves" to true, "innerSegmentIsOwnNode" to false, "mergedTextPresent" to true,
        )
        val actual = mapOf(
            "standaloneResolves" to standaloneFound, "innerSegmentIsOwnNode" to innerAsOwnNode,
            "mergedTextPresent" to mergedTextPresent,
        )

        val reproHeld = standaloneFound && !innerAsOwnNode && mergedTextPresent
        return if (reproHeld) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            val why = when {
                !standaloneFound -> "standalone '$standaloneId' did not resolve — screen not rendered?"
                !mergedTextPresent -> "no node carries merged '$outerSegment … $innerSegment' — screen not rendered?"
                innerAsOwnNode -> "a node with text exactly '$innerSegment' exists — inner nested Text " +
                    "became individually addressable; #821 did not reproduce"
                else -> "unexpected state"
            }
            CommandOutcome(Verdict.fail(why), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        }
    }

    private fun hasNodeWithExactText(node: TreeNode, target: String): Boolean {
        if (node.attributes["text"]?.trim() == target) return true
        return node.children.any { hasNodeWithExactText(it, target) }
    }

    private fun hasNodeWithTextContainingAll(node: TreeNode, parts: List<String>): Boolean {
        val text = node.attributes["text"] ?: ""
        if (parts.all { text.contains(it) }) return true
        return node.children.any { hasNodeWithTextContainingAll(it, parts) }
    }
}
