package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class StopAppBehavior : CommandBehavior {
    override val name = "stopApp"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val pidBefore = Probe.pidOf(ctx.serial, ctx.appId)

        val expected = mapOf("pid" to "empty")

        if (pidBefore.isEmpty()) {
            return CommandOutcome(
                Verdict.fail("app was not running before stopApp (pidBefore is empty)"),
                OracleKind.DEVICE_PROBE,
                expected,
                mapOf("pidBefore" to pidBefore, "pidAfter" to ""),
                emptyMap(),
            )
        }

        ctx.driver.stopApp(ctx.appId)
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
                Verdict.fail("process still running after stopApp: pid=$pidAfter"),
                OracleKind.DEVICE_PROBE,
                expected,
                mapOf("pidBefore" to pidBefore, "pidAfter" to pidAfter),
                emptyMap(),
            )
        }
    }
}
