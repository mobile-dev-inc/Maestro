package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import maestro.conformance.logcat.Watermark

class OpenLinkBehavior : CommandBehavior {
    override val name = "openLink"
    override val coverage = Coverage.MIXED

    private val deepLink = "maestrofixture://deeplink/ok"

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expected = mapOf("event" to "DEEPLINK", "data" to deepLink)

        // Stop the app first so openLink triggers a fresh onCreate (not onNewIntent),
        // ensuring the fixture emits DEEPLINK from the cold-start path.
        ctx.driver.stopApp(ctx.appId)
        Thread.sleep(400)

        ctx.driver.openLink(deepLink, ctx.appId, false, false)
        Thread.sleep(1200)

        // Read the new process's epoch via the latest watermark observed after the launch.
        val wm = ctx.reader.latestWatermark()
            ?: return CommandOutcome(
                Verdict.fail("no events after openLink — fixture may not have launched"),
                OracleKind.APP_EVENT,
                expected,
                emptyMap(),
                mapOf("link" to deepLink),
            )

        val events = ctx.reader.eventsAfter(Watermark(wm.epoch, 0), "DEEPLINK")
        val match = events.firstOrNull { (it.payload["data"] as? String)?.contains(deepLink) == true }

        return if (match != null) {
            CommandOutcome(
                Verdict.pass(),
                OracleKind.APP_EVENT,
                expected,
                mapOf("data" to match.payload["data"]),
                mapOf("link" to deepLink),
            )
        } else {
            CommandOutcome(
                Verdict.fail("no DEEPLINK event with data containing \"$deepLink\" after openLink"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("events" to events.map { it.payload }),
                mapOf("link" to deepLink),
            )
        }
    }
}
