package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class IsKeyboardVisibleBehavior : CommandBehavior {
    override val name = "isKeyboardVisible"
    override val coverage = Coverage.MIXED

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val b = Resolve.bounds(ctx, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Mark before focusing so the fixture's IME SHOWN event is captured, then POLL until the IME
        // actually rises (timing varies by image/cold-start; a fixed-sleep check flaked on 35/36).
        val w = ctx.markWatermark()
        ctx.driver.tap(Point(b.centerX, b.centerY))
        val visible = Poll.untilKeyboardVisible(ctx)

        // Cross-check: confirm IME SHOWN event appeared
        val imeEvents = ctx.reader.eventsAfter(w, "IME")
        val imeShown = imeEvents.any { it.payload["state"] == "SHOWN" }

        val expected = mapOf("visible" to true)
        val actual = mapOf("visible" to visible, "ime_shown_event" to imeShown)

        return if (visible) {
            CommandOutcome(
                Verdict.pass(), OracleKind.RETURN_VALUE, expected,
                actual, mapOf("oracle" to "isKeyboardVisible")
            )
        } else {
            CommandOutcome(
                Verdict.fail("isKeyboardVisible() returned false after tapping text_field"),
                OracleKind.RETURN_VALUE, expected,
                actual, mapOf("oracle" to "isKeyboardVisible")
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.RETURN_VALUE, emptyMap(), emptyMap(), emptyMap()
    )
}
