package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import maestro.conformance.logcat.Watermark

class LaunchAppBehavior : CommandBehavior {
    override val name = "launchApp"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // The runner already launched the app on AppLifecycleScreen.
        // Force a fresh cold start that re-reads intent extras.
        ctx.driver.stopApp(ctx.appId)
        Thread.sleep(600)
        ctx.driver.launchApp(ctx.appId, mapOf("route" to "AppLifecycleScreen", "k" to "v"))
        // Generous wait: LAUNCHED is emitted natively in onCreate, but slow-cold-start toolkits
        // (Flutter/RN) can land onCreate past a tighter window and flake.
        Thread.sleep(2200)

        val wm = ctx.reader.latestWatermark()
            ?: return CommandOutcome(
                Verdict.fail("no events after launch"),
                OracleKind.APP_EVENT,
                mapOf("state" to "LAUNCHED", "args" to mapOf("k" to "v")),
                emptyMap(),
                emptyMap(),
            )

        val events = ctx.reader.eventsAfter(Watermark(wm.epoch, 0), "LIFECYCLE")

        val expected = mapOf("state" to "LAUNCHED", "args" to mapOf("k" to "v"))

        val launched = events.firstOrNull { ev ->
            ev.payload["state"] == "LAUNCHED" &&
                (ev.payload["args"] as? Map<*, *>)?.get("k") == "v"
        }

        return if (launched != null) {
            CommandOutcome(
                Verdict.pass(),
                OracleKind.APP_EVENT,
                expected,
                mapOf("state" to launched.payload["state"], "args" to launched.payload["args"]),
                emptyMap(),
            )
        } else {
            CommandOutcome(
                Verdict.fail("no LIFECYCLE{state=LAUNCHED, args.k=v} event found after launch"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("events" to events.map { it.payload }),
                emptyMap(),
            )
        }
    }
}
