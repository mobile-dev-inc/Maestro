package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class InputTextBehavior : CommandBehavior {
    override val name = "inputText"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        val b = TreeBounds.find(node, "text_field")
            ?: return fail("text_field not found in hierarchy")

        // Focus the field and let the IME rise
        ctx.driver.tap(Point(b.centerX, b.centerY))
        Thread.sleep(700)

        val text = if (ctx.driver.isUnicodeInputSupported()) "Maestro 42!" else "Maestro 42"

        val w = ctx.markWatermark()
        ctx.driver.inputText(text)

        val events = Poll.forEvents(ctx, w, "TEXT_CHANGED", 5000)
        val match = events.firstOrNull { it.payload["text"] == text }
        val expected = mapOf("event" to "TEXT_CHANGED", "text" to text)
        return if (match != null) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected,
                mapOf("text" to match.payload["text"]),
                mapOf("sent" to text, "observed" to match.payload["text"])
            )
        } else {
            CommandOutcome(
                Verdict.fail("no TEXT_CHANGED with text=$text past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("sent" to text)
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
