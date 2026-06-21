package maestro.conformance.runner

import maestro.conformance.behavior.*
import maestro.conformance.device.*
import maestro.conformance.fixture.FixtureCatalog
import maestro.conformance.logcat.LogcatEventReader
import maestro.conformance.logcat.Watermark
import maestro.conformance.report.CommandRecord
import maestro.conformance.report.Reporter
import java.io.File

class ConformanceRunner(
    private val provider: DeviceProvider,
    private val reporter: Reporter,
    private val behaviors: List<CommandBehavior>,
    private val recordPolicy: String = "on-failure",
) {
    init {
        require(recordPolicy in setOf("all", "on-failure", "never")) {
            "recordPolicy must be one of: all, on-failure, never (got '$recordPolicy')"
        }
    }

    fun run(apis: List<Int>, frameworks: List<String>, commands: List<String>?) {
        val selected = behaviors.filter { commands == null || it.name in commands }
        val banners = mutableListOf<String>()
        val failedApis = mutableListOf<Int>()
        for (api in apis) {
            val handle = try {
                provider.acquire(DeviceSpec(api))
            } catch (e: Exception) {
                System.err.println("⚠ API $api: provisioning failed — skipping (${e.message})")
                failedApis += api
                val st = e.stackTraceToString()
                for (fw in frameworks) {
                    reporter.writeApiError("api$api-$fw", "provisioning", e.message ?: e.toString(), st)
                }
                continue
            }
            banners += "device: ${handle.serial} (api $api)" +
                if (handle.userSupplied) " [user-supplied]" else ""
            val reader = LogcatEventReader().apply { startTailing(handle.serial) }
            val completedFws = mutableSetOf<String>()
            var apiFailed = false
            try {
                for (fw in frameworks) {
                    if (apiFailed) {
                        // A previous framework failed mid-run; write api-error for remaining ones
                        reporter.writeApiError("api$api-$fw", "run", "skipped after prior framework failure", "")
                        continue
                    }
                    try {
                        val fixture = FixtureCatalog.byName(fw)
                        installFixture(handle.serial, fixture.apkResource)
                        warmUp(handle, fixture.appId)
                        val cell = "api$api-$fw"
                        val androidVersion = Cmd.run("adb", "-s", handle.serial, "shell", "getprop",
                            "ro.build.version.release").stdout.trim()
                        reporter.writeDeviceInfo(cell, linkedMapOf(
                            "api" to api,
                            "framework" to fw,
                            "serial" to handle.serial,
                            "image" to handle.image,
                            "deviceProfile" to handle.deviceProfile,
                            "abi" to handle.abi,
                            "androidVersion" to androidVersion,
                            "userSupplied" to handle.userSupplied,
                        ))
                        val records = ArrayList<CommandRecord>()
                        for (b in selected) {
                            // Skip behaviors scoped to other frameworks (e.g. compose-only
                            // mergeDescendants on the native fixture) — leaves the cell blank.
                            if (b.frameworks != null && fw !in b.frameworks!!) continue
                            records += runCommand(handle, reader, fixture.appId, b, cell)
                        }
                        reporter.writeCell(cell, records)
                        completedFws += fw
                        uninstall(handle.serial, fixture.appId)
                    } catch (e: Exception) {
                        System.err.println("⚠ API $api / $fw: run failed — ${e.message}")
                        val st = e.stackTraceToString()
                        reporter.writeApiError("api$api-$fw", "run", e.message ?: e.toString(), st)
                        apiFailed = true
                    }
                }
            } finally {
                if (apiFailed) failedApis += api
                reader.close()
                provider.release(handle)
            }
        }
        val banner = banners.joinToString("; ").let { b ->
            if (failedApis.isNotEmpty()) "$b | provisioning-failed APIs: ${failedApis.joinToString()}" else b
        }
        reporter.writeProvisioningErrors(failedApis)
        reporter.writeSummary(banner)
    }

    private fun runCommand(
        handle: DeviceHandle, reader: LogcatEventReader, appId: String,
        behavior: CommandBehavior, cell: String,
    ): CommandRecord {
        val totalStart = System.currentTimeMillis()
        // arrange: relaunch on the command's screen (deep link, not a tap).
        // Stop the app first so that the activity is always recreated via onCreate (not onNewIntent),
        // ensuring the route extra is read fresh every time.
        val screen = ScreenFor.of(behavior.name)
        handle.driver.stopApp(appId)
        Thread.sleep(200) // let the process die
        handle.driver.launchApp(appId, mapOf("route" to screen))
        Thread.sleep(1000) // let the screen settle + SELFTEST/LAUNCHED flush

        val ctx = BehaviorContext(
            driver = handle.driver, reader = reader, serial = handle.serial,
            apiLevel = handle.apiLevel, appId = appId,
            markWatermark = { markWatermark(handle.serial, reader) },
        )

        val recorder = ScreenRecorder(handle.serial)
        val actStart = System.currentTimeMillis()
        val (outcome, recording) = if (recordPolicy != "never") {
            recorder.record(behavior.name) {
                runCatching { behavior.run(ctx) }.getOrElse {
                    CommandOutcome(Verdict.fail("behavior threw: ${it.message}"),
                        OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
                }
            }
        } else {
            val result = runCatching { behavior.run(ctx) }.getOrElse {
                CommandOutcome(Verdict.fail("behavior threw: ${it.message}"),
                    OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
            }
            result to Recording(null, false, "disabled")
        }
        val actMs = System.currentTimeMillis() - actStart
        val totalMs = System.currentTimeMillis() - totalStart

        val keepVideo = recordPolicy == "all" || (recordPolicy == "on-failure" && !outcome.verdict.pass)
        val captureEvidence = recordPolicy != "never" && (recordPolicy == "all" || !outcome.verdict.pass)
        val artifacts = mutableListOf<String>()

        // Always clean stale per-command media first: re-running into an existing report dir
        // (or a run that drops a previously-recorded clip) must NOT leave orphan files from a
        // prior run. The HTML report is driven by `artifacts` in command.json, but orphan media
        // on disk is confusing and wastes space — keep the cell dir reflecting only this run.
        val dir = reporter.commandDir(cell, behavior.name)
        listOf("recording.mp4", "after.png", "logcat-slice.txt").forEach { File(dir, it).delete() }

        if (keepVideo && recording.available && recording.file != null) {
            recording.file.copyTo(File(dir, "recording.mp4"), overwrite = true)
            artifacts += "recording.mp4"
        }

        if (captureEvidence) {
            // after.png
            runCatching {
                val buf = okio.Buffer()
                handle.driver.takeScreenshot(buf, false)
                File(dir, "after.png").writeBytes(buf.readByteArray())
                artifacts += "after.png"
            }
            // logcat slice (unfiltered tail — catches crashes/ANRs the oracle can't show)
            runCatching {
                val log = Cmd.run("adb", "-s", handle.serial, "logcat", "-d", "-v", "threadtime", "-t", "500").stdout
                File(dir, "logcat-slice.txt").writeText(log)
                artifacts += "logcat-slice.txt"
            }
        }

        return CommandRecord(
            command = behavior.name, coverage = behavior.coverage.name.lowercase().replace('_', '-'),
            args = outcome.args, oracleKind = outcome.oracleKind,
            expected = outcome.expected, actual = outcome.actual,
            verdict = outcome.verdict.pass, failureReason = outcome.verdict.reason,
            actMs = actMs, totalMs = totalMs,
            artifacts = artifacts,
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

    /**
     * One throwaway launch right after install. The first cold start of a freshly-installed APK pays
     * a one-time dexopt/JIT cost that is heavy for JS/Hermes runtimes (React Native) — enough to blow
     * past a single command's settle budget and make the first command flake (element not yet
     * rendered). Paying it once here keeps the per-command launches in the loop fast and consistent
     * across frameworks. Best-effort: failures here never fail the run.
     */
    private fun warmUp(handle: DeviceHandle, appId: String) {
        runCatching {
            handle.driver.launchApp(appId, mapOf("route" to "TapScreen"))
            Thread.sleep(3000)
            handle.driver.stopApp(appId)
            Thread.sleep(500)
        }
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
        "swipeStartEnd", "swipeDirection", "swipeElement" -> "SwipeScreen"
        "scrollVertical" -> "ScrollScreen"
        "inputText", "eraseText" -> "InputScreen"
        "pressKey", "isKeyboardVisible", "hideKeyboard" -> "KeyboardScreen"
        "contentDescriptor" -> "TreeScreen"
        "mergeDescendants" -> "MergeScreen"
        "waitUntilScreenIsStatic", "waitForAppToSettle" -> "AnimationScreen"
        "launchApp", "stopApp", "killApp", "clearAppState",
        "openLink", "backPress" -> "AppLifecycleScreen"
        "setOrientation" -> "OrientationScreen"
        else -> "TapScreen" // extended in later tasks
    }
}
