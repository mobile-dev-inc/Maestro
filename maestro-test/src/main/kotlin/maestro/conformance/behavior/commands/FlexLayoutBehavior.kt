package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

/**
 * Regression guard for mobile-dev-inc/Maestro#2246 (Android) — "a `<View style={{flex:1}}>` and all
 * its children disappear from Maestro's hierarchy; impossible to find by testID or accessibilityLabel."
 *
 * Empirically the bug does NOT reproduce on React Native 0.86 (New Architecture): a bare `flex:1`
 * wrapper (`flex_bug_root`) and its child (`flex_bug_child`) resolve fine, as does the fixed-size
 * control (`flex_ok_root`/`flex_ok_child`). The original report (Jan 2025, older RN) was most likely
 * a view-flattening / `isVisibleToUser`-pruning interaction (ViewHierarchy.dumpNodeRec prunes a
 * `!isVisibleToUser` child AND its whole subtree) that newer RN no longer triggers.
 *
 * This asserts the CORRECT behavior — every testID resolves — so it turns red if a future RN/driver
 * regresses #2246. React-Native-only; scoped via [frameworks].
 */
class FlexLayoutBehavior : CommandBehavior {
    override val name = "flex"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE
    override val frameworks = setOf("react-native")

    private val allIds = listOf("flex_ok_root", "flex_ok_child", "flex_bug_root", "flex_bug_child")

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val found = allIds.filter { Resolve.bounds(ctx, it) != null }
        val missing = allIds - found.toSet()
        val expected = mapOf("allResolve" to allIds)
        val actual = mapOf("found" to found, "missing" to missing)
        return if (missing.isEmpty()) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            CommandOutcome(
                Verdict.fail("flex:1 elements not resolvable: $missing — #2246 regressed"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }
}
