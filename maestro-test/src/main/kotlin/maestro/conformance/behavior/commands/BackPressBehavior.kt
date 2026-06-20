package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class BackPressBehavior : CommandBehavior {
    override val name = "backPress"
    override val coverage = Coverage.MIXED

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expected = mapOf("event" to "BACK")

        val w = ctx.markWatermark()
        ctx.driver.backPress()

        val events = Poll.forEvents(ctx, w, "BACK", 4000)
        val match = events.firstOrNull()

        return if (match != null) {
            CommandOutcome(
                Verdict.pass(),
                OracleKind.APP_EVENT,
                expected,
                mapOf("event" to "BACK"),
                emptyMap(),
            )
        } else {
            CommandOutcome(
                Verdict.fail("no BACK event past watermark after backPress()"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("events" to events.map { it.payload }),
                emptyMap(),
            )
        }
    }
}
