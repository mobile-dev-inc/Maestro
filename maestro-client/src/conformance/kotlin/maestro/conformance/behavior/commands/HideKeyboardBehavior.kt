package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class HideKeyboardBehavior : CommandBehavior {
    override val name = "hideKeyboard"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val b = TreeBounds.find(node, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Focus the field and let the IME rise
        ctx.driver.tap(Point(b.centerX, b.centerY))
        Thread.sleep(700)

        // Confirm the IME is up before we hide it
        val imeUp = ctx.driver.isKeyboardVisible()
        if (!imeUp) {
            return CommandOutcome(
                Verdict.fail("IME did not appear after tapping text_field (pre-condition failed)"),
                OracleKind.APP_EVENT, mapOf("state" to "HIDDEN"), mapOf("pre_ime_visible" to false),
                emptyMap()
            )
        }

        val w = ctx.markWatermark()
        ctx.driver.hideKeyboard()

        val events = Poll.forEvents(ctx, w, "IME", 5000)
        val expected = mapOf("event" to "IME", "state" to "HIDDEN")
        val hiddenEvent = events.firstOrNull { it.payload["state"] == "HIDDEN" }

        // Cross-check: driver probe
        Thread.sleep(300)
        val stillVisible = ctx.driver.isKeyboardVisible()

        return if (hiddenEvent != null && !stillVisible) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected,
                mapOf("state" to hiddenEvent.payload["state"], "is_keyboard_visible" to stillVisible),
                emptyMap()
            )
        } else if (hiddenEvent == null) {
            CommandOutcome(
                Verdict.fail("no IME{state=HIDDEN} event past watermark after hideKeyboard()"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }, "is_keyboard_visible" to stillVisible),
                emptyMap()
            )
        } else {
            // event present but driver still says visible
            CommandOutcome(
                Verdict.fail("IME HIDDEN event seen but isKeyboardVisible() still true"),
                OracleKind.APP_EVENT, expected,
                mapOf("state" to hiddenEvent.payload["state"], "is_keyboard_visible" to stillVisible),
                emptyMap()
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
