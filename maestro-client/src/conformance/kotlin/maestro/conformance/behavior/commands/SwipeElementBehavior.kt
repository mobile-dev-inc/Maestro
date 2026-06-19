package maestro.conformance.behavior.commands

import maestro.Point
import maestro.SwipeDirection
import maestro.conformance.behavior.*

class SwipeElementBehavior : CommandBehavior {
    override val name = "swipeElement"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val bounds = TreeBounds.find(node, "swipe_surface")
            ?: return fail("swipe_surface not found in hierarchy")
        val center = Point(bounds.centerX, bounds.centerY)

        val w = ctx.markWatermark()
        ctx.driver.swipe(center, SwipeDirection.UP, 300)

        val events = Poll.forEvents(ctx, w, "SWIPE")
        val ev = events.firstOrNull { it.payload["dir"] == "UP" && it.payload["target"] == "swipe_surface" }
        val expected = mapOf(
            "event" to "SWIPE",
            "dir" to "UP",
            "target" to "swipe_surface",
        )
        return if (ev != null) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected,
                mapOf("dir" to ev.payload["dir"], "target" to ev.payload["target"]),
                mapOf("center" to listOf(center.x, center.y)),
            )
        } else {
            CommandOutcome(
                Verdict.fail("no SWIPE with dir=UP and target=swipe_surface past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("center" to listOf(center.x, center.y)),
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
