package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class ContentDescriptorBehavior : CommandBehavior {
    override val name = "contentDescriptor"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val tree = ctx.driver.contentDescriptor()

        val expectedIds = listOf("tree_root", "tree_label_a", "tree_button_b")
        val foundIds = expectedIds.filter { id -> TreeBounds.find(tree, id) != null }

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
