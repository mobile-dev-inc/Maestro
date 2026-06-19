package maestro.conformance.runner

import maestro.conformance.behavior.*
import maestro.conformance.device.*
import maestro.conformance.fixture.FixtureCatalog
import maestro.conformance.logcat.LogcatEventReader
import maestro.conformance.logcat.Watermark
import maestro.conformance.report.CommandRecord
import maestro.conformance.report.Reporter

class ConformanceRunner(
    private val provider: DeviceProvider,
    private val reporter: Reporter,
    private val behaviors: List<CommandBehavior>,
) {
    fun run(apis: List<Int>, frameworks: List<String>, commands: List<String>?) {
        val selected = behaviors.filter { commands == null || it.name in commands }
        val banners = mutableListOf<String>()
        for (api in apis) {
            val handle = provider.acquire(DeviceSpec(api))
            banners += "device: ${handle.serial} (api $api)" +
                if (handle.userSupplied) " [user-supplied]" else ""
            val reader = LogcatEventReader().apply { startTailing(handle.serial) }
            try {
                for (fw in frameworks) {
                    val fixture = FixtureCatalog.byName(fw)
                    installFixture(handle.serial, fixture.apkResource)
                    val cell = "api$api-$fw"
                    val records = ArrayList<CommandRecord>()
                    for (b in selected) {
                        records += runCommand(handle, reader, fixture.appId, b)
                    }
                    reporter.writeCell(cell, records)
                    uninstall(handle.serial, fixture.appId)
                }
            } finally {
                reader.close()
                provider.release(handle)
            }
        }
        reporter.writeSummary(banners.joinToString("; "))
    }

    private fun runCommand(
        handle: DeviceHandle, reader: LogcatEventReader, appId: String,
        behavior: CommandBehavior,
    ): CommandRecord {
        val totalStart = System.currentTimeMillis()
        // arrange: relaunch on the command's screen (deep link, not a tap).
        val screen = ScreenFor.of(behavior.name)
        handle.driver.launchApp(appId, mapOf("route" to screen))
        Thread.sleep(800) // let the screen settle + SELFTEST/LAUNCHED flush

        val ctx = BehaviorContext(
            driver = handle.driver, reader = reader, serial = handle.serial,
            apiLevel = handle.apiLevel, appId = appId,
            markWatermark = { markWatermark(handle.serial, reader) },
        )
        val actStart = System.currentTimeMillis()
        val outcome = runCatching { behavior.run(ctx) }.getOrElse {
            CommandOutcome(Verdict.fail("behavior threw: ${it.message}"),
                OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
        }
        val actMs = System.currentTimeMillis() - actStart
        val totalMs = System.currentTimeMillis() - totalStart
        return CommandRecord(
            command = behavior.name, coverage = behavior.coverage.name.lowercase().replace('_', '-'),
            args = outcome.args, oracleKind = outcome.oracleKind,
            expected = outcome.expected, actual = outcome.actual,
            verdict = outcome.verdict.pass, failureReason = outcome.verdict.reason,
            actMs = actMs, totalMs = totalMs,
        )
    }

    private fun markWatermark(serial: String, reader: LogcatEventReader): Watermark {
        // On the first call, `before` may be null; that is safe because the fixture's
        // SELFTEST/LAUNCHED already flushed a watermark before the first command's MARK.
        val before = reader.latestWatermark()
        Cmd.run("adb", "-s", serial, "shell", "am", "broadcast",
            "-a", "dev.mobile.maestro.fixture.MARK")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val now = reader.latestWatermark()
            if (now != null && now != before) return now
            Thread.sleep(50)
        }
        error("MARK not observed within 3s (before=$before, serial=$serial) — fixture not emitting")
    }

    private fun installFixture(serial: String, apkResource: String) {
        val apk = kotlin.io.path.createTempFile("fixture", ".apk").toFile()
        apk.deleteOnExit()
        ConformanceRunner::class.java.getResourceAsStream(apkResource)!!.use { it.copyTo(apk.outputStream()) }
        require(Cmd.run("adb", "-s", serial, "install", "-r", apk.absolutePath).ok) { "install failed" }
    }

    private fun uninstall(serial: String, appId: String) {
        Cmd.run("adb", "-s", serial, "uninstall", appId)
    }
}

object ScreenFor {
    fun of(command: String): String = when (command) {
        "tap", "longPress" -> "TapScreen"
        else -> "TapScreen" // extended in later tasks
    }
}
