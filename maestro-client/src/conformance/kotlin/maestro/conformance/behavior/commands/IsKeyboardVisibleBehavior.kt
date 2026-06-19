package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class IsKeyboardVisibleBehavior : CommandBehavior {
    override val name = "isKeyboardVisible"
    override val coverage = Coverage.MIXED

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val b = TreeBounds.find(node, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Focus the field and let the IME rise
        ctx.driver.tap(Point(b.centerX, b.centerY))
        Thread.sleep(700)

        val w = ctx.markWatermark()
        val visible = ctx.driver.isKeyboardVisible()

        // Optional cross-check: confirm IME SHOWN event appeared
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
