package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class TapBehavior : CommandBehavior {
    override val name = "tap"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // arrange: relaunch fixture on TapScreen via deep link (done by the runner before run()).
        // resolve target center from the on-device tree.
        val node = ctx.driver.contentDescriptor()
        val bounds = TreeBounds.find(node, "tap_target")
            ?: return fail(ctx, "tap_target not found in hierarchy")
        val point = Point(bounds.centerX, bounds.centerY)

        val w = ctx.markWatermark()          // baseline
        ctx.driver.tap(point)                // act

        val taps = pollFor(ctx, w, "TAP")
        val tap = taps.firstOrNull { it.payload["target"] == "tap_target" }
        val expected = mapOf("event" to "TAP", "target" to "tap_target")
        return if (tap != null) {
            CommandOutcome(Verdict.pass(), OracleKind.APP_EVENT, expected,
                tap.payload, mapOf("point" to listOf(point.x, point.y)))
        } else {
            CommandOutcome(Verdict.fail("no TAP on tap_target past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("taps" to taps.map { it.payload }), mapOf("point" to listOf(point.x, point.y)))
        }
    }

    private fun pollFor(ctx: BehaviorContext, w: maestro.conformance.logcat.Watermark, type: String,
                        timeoutMs: Long = 3000): List<maestro.conformance.logcat.FixtureEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ev = ctx.reader.eventsAfter(w, type)
            if (ev.isNotEmpty()) return ev
            Thread.sleep(100)
        }
        return ctx.reader.eventsAfter(w, type)
    }

    private fun fail(ctx: BehaviorContext, reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
}
