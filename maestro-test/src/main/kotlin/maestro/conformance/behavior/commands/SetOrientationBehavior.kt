package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import maestro.conformance.logcat.Watermark
import maestro.device.DeviceOrientation

class SetOrientationBehavior : CommandBehavior {
    override val name = "setOrientation"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expected = mapOf("event" to "ORIENTATION", "value_1" to "LANDSCAPE", "value_2" to "PORTRAIT")

        // Step 1: rotate to LANDSCAPE_LEFT and assert ORIENTATION{value=LANDSCAPE}
        val w = ctx.markWatermark()
        ctx.driver.setOrientation(DeviceOrientation.LANDSCAPE_LEFT)

        val landscapeEvents = Poll.forEvents(ctx, w, "ORIENTATION", 5000)
        val landscapeEvent = landscapeEvents.firstOrNull { it.payload["value"] == "LANDSCAPE" }
        if (landscapeEvent == null) {
            return CommandOutcome(
                Verdict.fail("no ORIENTATION{value=LANDSCAPE} past watermark after setOrientation(LANDSCAPE_LEFT)"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("orientationEvents" to landscapeEvents.map { it.payload }),
                mapOf("orientation1" to "LANDSCAPE_LEFT", "orientation2" to "PORTRAIT"),
            )
        }

        // Step 2: round-trip back to PORTRAIT and assert ORIENTATION{value=PORTRAIT}
        val w2 = ctx.markWatermark()
        ctx.driver.setOrientation(DeviceOrientation.PORTRAIT)

        val portraitEvents = Poll.forEvents(ctx, w2, "ORIENTATION", 5000)
        val portraitEvent = portraitEvents.firstOrNull { it.payload["value"] == "PORTRAIT" }
        if (portraitEvent == null) {
            return CommandOutcome(
                Verdict.fail("no ORIENTATION{value=PORTRAIT} past watermark after setOrientation(PORTRAIT)"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("landscapeOk" to true, "orientationEvents" to portraitEvents.map { it.payload }),
                mapOf("orientation1" to "LANDSCAPE_LEFT", "orientation2" to "PORTRAIT"),
            )
        }

        return CommandOutcome(
            Verdict.pass(),
            OracleKind.APP_EVENT,
            expected,
            mapOf("value_1" to landscapeEvent.payload["value"], "value_2" to portraitEvent.payload["value"]),
            mapOf("orientation1" to "LANDSCAPE_LEFT", "orientation2" to "PORTRAIT"),
        )
    }
}
