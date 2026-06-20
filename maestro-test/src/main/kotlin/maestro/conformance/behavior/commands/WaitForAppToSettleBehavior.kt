package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class WaitForAppToSettleBehavior : CommandBehavior {
    override val name = "waitForAppToSettle"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val vh = ctx.driver.waitForAppToSettle(null, ctx.appId, 5000)

        val settled = vh != null
        val expected = mapOf("settled" to true)
        val actual = mapOf("settled" to settled)

        return if (settled) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            CommandOutcome(
                Verdict.fail("waitForAppToSettle returned null"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }
}
