package maestro.conformance.behavior.commands

import maestro.KeyCode
import maestro.conformance.behavior.*

class KillAppBehavior : CommandBehavior {
    override val name = "killApp"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val pidBefore = Probe.pidOf(ctx.serial, ctx.appId)

        val expected = mapOf("pid" to "empty")

        if (pidBefore.isEmpty()) {
            return CommandOutcome(
                Verdict.fail("app was not running before killApp (pidBefore is empty)"),
                OracleKind.DEVICE_PROBE,
                expected,
                mapOf("pidBefore" to pidBefore, "pidAfter" to ""),
                emptyMap(),
            )
        }

        // am kill only works on cached/background processes; press Home first.
        ctx.driver.pressKey(KeyCode.HOME)
        Thread.sleep(500)

        ctx.driver.killApp(ctx.appId)
        Thread.sleep(500)

        val pidAfter = Probe.pidOf(ctx.serial, ctx.appId)

        return if (pidAfter.isEmpty()) {
            CommandOutcome(
                Verdict.pass(),
                OracleKind.DEVICE_PROBE,
                expected,
                mapOf("pidBefore" to pidBefore, "pidAfter" to pidAfter),
                emptyMap(),
            )
        } else {
            CommandOutcome(
                Verdict.fail("process still running after killApp: pid=$pidAfter"),
                OracleKind.DEVICE_PROBE,
                expected,
                mapOf("pidBefore" to pidBefore, "pidAfter" to pidAfter),
                emptyMap(),
            )
        }
    }
}
