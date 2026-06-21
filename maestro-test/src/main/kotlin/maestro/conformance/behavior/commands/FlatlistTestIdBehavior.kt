package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

/**
 * Regression guard for mobile-dev-inc/Maestro#2051 — "Maestro can't identify the testIDs of the
 * title/subtitle inside a Pressable FlatList row; only the row's testID is visible."
 *
 * Empirically the bug does NOT reproduce on React Native 0.86 (New Architecture): the nested
 * `row_0_title` / `row_0_subtitle` testIDs inside a `Pressable` row DO resolve, alongside the row
 * container (`row_0`) and the non-accessible control (`plain_row` / `plain_title`). The original
 * report (Sep 2024, older RN/Paper) was consistent with Android collapsing an `accessible` container
 * and dropping its descendant nodes; New-Arch RN exposes them.
 *
 * This asserts the CORRECT behavior — all row testIDs (container + nested) resolve — so it flips red
 * if a future RN/driver regresses #2051. React-Native-only; scoped via [frameworks].
 */
class FlatlistTestIdBehavior : CommandBehavior {
    override val name = "flatlistTestIds"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE
    override val frameworks = setOf("react-native")

    private val allIds = listOf("row_0", "row_0_title", "row_0_subtitle", "plain_row", "plain_title")

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // Poll until everything is present (or timeout); FlatList rows render a beat after launch.
        val deadlineMs = System.currentTimeMillis() + 4_000L
        var found: List<String> = emptyList()
        do {
            val tree = ctx.driver.contentDescriptor()
            found = allIds.filter { TreeBounds.find(tree, it) != null }
            if (found.size == allIds.size) break
            if (System.currentTimeMillis() < deadlineMs) Thread.sleep(200)
        } while (System.currentTimeMillis() < deadlineMs)

        val missing = allIds - found.toSet()
        val expected = mapOf("allResolve" to allIds)
        val actual = mapOf("found" to found, "missing" to missing)
        return if (missing.isEmpty()) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            CommandOutcome(
                Verdict.fail("FlatList-row testIDs not resolvable: $missing — #2051 regressed"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }
}
