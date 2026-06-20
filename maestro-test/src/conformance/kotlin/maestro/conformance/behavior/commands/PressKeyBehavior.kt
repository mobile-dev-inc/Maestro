package maestro.conformance.behavior.commands

import maestro.KeyCode
import maestro.Point
import maestro.conformance.behavior.*

class PressKeyBehavior : CommandBehavior {
    override val name = "pressKey"
    override val coverage = Coverage.MIXED

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val b = Resolve.bounds(ctx, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Focus the field and let the IME rise
        ctx.driver.tap(Point(b.centerX, b.centerY))
        Thread.sleep(700)

        val w = ctx.markWatermark()
        ctx.driver.pressKey(KeyCode.ENTER)

        val events = Poll.forEvents(ctx, w, "KEY", 5000)
        val expected = mapOf("event" to "KEY", "code" to "ENTER")
        val match = events.firstOrNull { it.payload["code"] == "ENTER" }
        return if (match != null) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected,
                mapOf("code" to match.payload["code"]),
                mapOf("key" to "ENTER")
            )
        } else {
            CommandOutcome(
                Verdict.fail("no KEY with code=ENTER past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("key" to "ENTER")
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
