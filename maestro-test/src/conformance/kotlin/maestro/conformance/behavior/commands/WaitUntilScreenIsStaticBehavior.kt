package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class WaitUntilScreenIsStaticBehavior : CommandBehavior {
    override val name = "waitUntilScreenIsStatic"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // Capture a watermark before calling so we can check diagnostics after
        val w = ctx.reader.latestWatermark()

        val static = ctx.driver.waitUntilScreenIsStatic(5000)

        // Diagnostics only: check if ANIM{state:SETTLED} event appeared
        val settledCount = if (w != null) {
            ctx.reader.eventsAfter(w, "ANIM").count { it.payload["state"] == "SETTLED" }
        } else 0

        val expected = mapOf("static" to true)
        val actual = mapOf(
            "static" to static,
            "settledEventCount" to settledCount,
        )

        return if (static) {
            CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, actual, emptyMap())
        } else {
            CommandOutcome(
                Verdict.fail("waitUntilScreenIsStatic returned false"),
                OracleKind.RETURN_VALUE, expected, actual, emptyMap()
            )
        }
    }
}
