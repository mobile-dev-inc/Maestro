package maestro.conformance.behavior.commands

import maestro.SwipeDirection
import maestro.conformance.behavior.*

class SwipeDirectionBehavior : CommandBehavior {
    override val name = "swipeDirection"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val w = ctx.markWatermark()
        ctx.driver.swipe(SwipeDirection.LEFT, 300)

        val events = Poll.forEvents(ctx, w, "SWIPE")
        val ev = events.firstOrNull { it.payload["dir"] == "LEFT" }
        val expected = mapOf(
            "event" to "SWIPE",
            "dir" to "LEFT",
            "dx_sign" to "negative",
        )
        return if (ev != null) {
            val dx = (ev.payload["dx"] as? Number)?.toInt() ?: 0
            if (dx < 0) {
                CommandOutcome(
                    Verdict.pass(), OracleKind.APP_EVENT, expected,
                    mapOf("dir" to ev.payload["dir"], "dx" to dx),
                    emptyMap(),
                )
            } else {
                CommandOutcome(
                    Verdict.fail("dx=$dx is not negative (expected dx<0 for LEFT swipe)"),
                    OracleKind.APP_EVENT, expected,
                    mapOf("dir" to ev.payload["dir"], "dx" to dx),
                    emptyMap(),
                )
            }
        } else {
            CommandOutcome(
                Verdict.fail("no SWIPE with dir=LEFT past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                emptyMap(),
            )
        }
    }
}
