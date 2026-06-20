package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*
import maestro.conformance.logcat.Watermark

class ClearAppStateBehavior : CommandBehavior {
    override val name = "clearAppState"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expected = mapOf("seeded" to false)

        // Seed: tap the state_seed_button to set seeded=true in SharedPreferences.
        val bounds = Resolve.bounds(ctx, "state_seed_button")
            ?: return CommandOutcome(
                Verdict.fail("state_seed_button not found in hierarchy"),
                OracleKind.APP_EVENT, expected, emptyMap(), emptyMap(),
            )
        ctx.driver.tap(Point(bounds.centerX, bounds.centerY))
        Thread.sleep(500)

        // Stop → clear state → relaunch.
        ctx.driver.stopApp(ctx.appId)
        Thread.sleep(300)
        ctx.driver.clearAppState(ctx.appId)
        Thread.sleep(300)
        ctx.driver.launchApp(ctx.appId, mapOf("route" to "AppLifecycleScreen"))
        Thread.sleep(1200)

        // Read STATE event from the relaunched process.
        val wm = ctx.reader.latestWatermark()
            ?: return CommandOutcome(
                Verdict.fail("no events after relaunch"),
                OracleKind.APP_EVENT, expected, emptyMap(), emptyMap(),
            )

        val events = ctx.reader.eventsAfter(Watermark(wm.epoch, 0), "STATE")

        val stateEvent = events.firstOrNull { ev ->
            ev.payload["seeded"] == false
        }

        return if (stateEvent != null) {
            CommandOutcome(
                Verdict.pass(),
                OracleKind.APP_EVENT,
                expected,
                mapOf("seeded" to stateEvent.payload["seeded"]),
                emptyMap(),
            )
        } else {
            CommandOutcome(
                Verdict.fail("no STATE{seeded=false} event after clearAppState+relaunch"),
                OracleKind.APP_EVENT,
                expected,
                mapOf("events" to events.map { it.payload }),
                emptyMap(),
            )
        }
    }
}
