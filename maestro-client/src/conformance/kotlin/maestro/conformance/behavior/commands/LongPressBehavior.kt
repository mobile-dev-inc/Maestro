package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class LongPressBehavior : CommandBehavior {
    override val name = "longPress"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val bounds = TreeBounds.find(node, "longpress_target")
            ?: return fail("longpress_target not found in hierarchy")
        val point = Point(bounds.centerX, bounds.centerY)

        val w = ctx.markWatermark()
        ctx.driver.longPress(point)

        val events = Poll.forEvents(ctx, w, "LONG_PRESS", 6000)
        val ev = events.firstOrNull { it.payload["target"] == "longpress_target" }
        val expected = mapOf(
            "event" to "LONG_PRESS",
            "target" to "longpress_target",
            "downMs_min" to 2000,
        )
        return if (ev != null) {
            val downMs = (ev.payload["downMs"] as? Number)?.toInt() ?: 0
            if (downMs >= 2000) {
                CommandOutcome(
                    Verdict.pass(), OracleKind.APP_EVENT, expected,
                    mapOf("target" to ev.payload["target"], "downMs" to downMs),
                    mapOf("point" to listOf(point.x, point.y)),
                )
            } else {
                CommandOutcome(
                    Verdict.fail("downMs=$downMs is less than 2000"),
                    OracleKind.APP_EVENT, expected,
                    mapOf("target" to ev.payload["target"], "downMs" to downMs),
                    mapOf("point" to listOf(point.x, point.y)),
                )
            }
        } else {
            CommandOutcome(
                Verdict.fail("no LONG_PRESS on longpress_target past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("point" to listOf(point.x, point.y)),
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
