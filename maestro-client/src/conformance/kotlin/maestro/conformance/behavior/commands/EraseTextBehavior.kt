package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class EraseTextBehavior : CommandBehavior {
    override val name = "eraseText"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val b = TreeBounds.find(node, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Focus the field and let the IME rise
        ctx.driver.tap(Point(b.centerX, b.centerY))
        Thread.sleep(700)

        // Seed the field with "ABCDE"
        ctx.driver.inputText("ABCDE")
        Thread.sleep(400)

        // Now mark baseline and erase 2 characters
        val w = ctx.markWatermark()
        ctx.driver.eraseText(2)

        val events = Poll.forEvents(ctx, w, "TEXT_CHANGED", 5000)
        val expected = mapOf("event" to "TEXT_CHANGED", "text" to "ABC")
        val match = events.firstOrNull { it.payload["text"] == "ABC" }
        return if (match != null) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected,
                mapOf("text" to match.payload["text"]),
                mapOf("seeded" to "ABCDE", "erased" to 2, "expected_text" to "ABC")
            )
        } else {
            CommandOutcome(
                Verdict.fail("no TEXT_CHANGED with text=ABC past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("seeded" to "ABCDE", "erased" to 2)
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
