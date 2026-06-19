package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class SwipeStartEndBehavior : CommandBehavior {
    override val name = "swipeStartEnd"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val bounds = Resolve.bounds(ctx, "swipe_surface")
            ?: return fail("swipe_surface not found in hierarchy")

        // Upward swipe: start near bottom of surface, end near top.
        val startX = bounds.centerX
        val startY = bounds.b - 100
        val endX = bounds.centerX
        val endY = bounds.t + 100
        val start = Point(startX, startY)
        val end = Point(endX, endY)

        val w = ctx.markWatermark()
        ctx.driver.swipe(start, end, 300)

        val events = Poll.forEvents(ctx, w, "SWIPE")
        val ev = events.firstOrNull { it.payload["dir"] == "UP" }
        val expected = mapOf(
            "event" to "SWIPE",
            "dir" to "UP",
            "dy_sign" to "negative",
            "durationMs_range" to "120..900",
        )
        return if (ev != null) {
            val dy = (ev.payload["dy"] as? Number)?.toInt() ?: 0
            val durationMs = (ev.payload["durationMs"] as? Number)?.toInt() ?: 0
            val dyOk = dy < 0
            val durOk = durationMs in 120..900
            if (dyOk && durOk) {
                CommandOutcome(
                    Verdict.pass(), OracleKind.APP_EVENT, expected,
                    mapOf("dir" to ev.payload["dir"], "dy" to dy, "durationMs" to durationMs),
                    mapOf("start" to listOf(startX, startY), "end" to listOf(endX, endY)),
                )
            } else {
                CommandOutcome(
                    Verdict.fail("dy=$dy (want <0)=${dyOk}, durationMs=$durationMs (want 120..900)=${durOk}"),
                    OracleKind.APP_EVENT, expected,
                    mapOf("dir" to ev.payload["dir"], "dy" to dy, "durationMs" to durationMs),
                    mapOf("start" to listOf(startX, startY), "end" to listOf(endX, endY)),
                )
            }
        } else {
            CommandOutcome(
                Verdict.fail("no SWIPE with dir=UP past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("start" to listOf(startX, startY), "end" to listOf(endX, endY)),
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
