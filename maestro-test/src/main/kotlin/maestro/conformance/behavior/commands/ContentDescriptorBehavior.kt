package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class ContentDescriptorBehavior : CommandBehavior {
    override val name = "contentDescriptor"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expectedIds = listOf("tree_root", "tree_label_a", "tree_button_b")

        // Retry up to ~3 s (200 ms polls) so the tree is stable before asserting.
        // TreeScreen may not have finished rendering immediately after launch,
        // and recording load can make the initial settle even slower.
        val deadlineMs = System.currentTimeMillis() + 3_000L
        var foundIds: List<String> = emptyList()
        do {
            val tree = ctx.driver.contentDescriptor()
            foundIds = expectedIds.filter { id -> TreeBounds.find(tree, id) != null }
            if (foundIds.size == expectedIds.size) break
            if (System.currentTimeMillis() < deadlineMs) Thread.sleep(200)
        } while (System.currentTimeMillis() < deadlineMs)

        val expected = mapOf("containsIds" to expectedIds)
        val actual = mapOf("foundIds" to foundIds)

        return if (foundIds.size == expectedIds.size) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            val missing = expectedIds - foundIds.toSet()
            CommandOutcome(
                Verdict.fail("missing ids in tree: $missing"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }
}
