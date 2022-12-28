package ios.xcrun

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import net.harawata.appdirs.AppDirsFactory
import okio.buffer
import okio.source
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Simctl {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private const val MAX_COUNT_XCTEST_LOGS = 5

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val logDirectory by lazy {
        File(AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR), "xctest_runner_logs").apply {
            if (!exists()) {
                mkdir()
            } else {
                val existing = listFiles() ?: emptyArray()
                val toDelete = existing.sortedByDescending { it.name }
                val count = toDelete.size
                if (count > MAX_COUNT_XCTEST_LOGS) toDelete.forEach { it.deleteRecursively() }
            }
        }
    }

    data class SimctlError(override val message: String): Throwable(message)

    fun listApps(): Set<String> {
        val process = ProcessBuilder("bash", "-c", "xcrun simctl listapps booted | plutil -convert json - -o -").start()

        val json = String(process.inputStream.readBytes())

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }

    fun list(): SimctlList {
        val command = listOf("xcrun", "simctl", "list", "-j")

        val process = ProcessBuilder(command).start()
        val json = String(process.inputStream.readBytes())

        return jacksonObjectMapper().readValue(json)
    }

    fun awaitLaunch(deviceId: String) {
        MaestroTimer.withTimeout(30000) {
            if (list()
                    .devices
                    .values
                    .flatten()
                    .find { it.udid == deviceId }
                    ?.state == "Booted"
            ) true else null
        } ?: throw SimctlError("Device $deviceId did not boot in time")
    }

    fun awaitShutdown(deviceId: String) {
        MaestroTimer.withTimeout(30000) {
            if (list()
                    .devices
                    .values
                    .flatten()
                    .find { it.udid == deviceId }
                    ?.state != "Booted"
            ) true else null
        } ?: throw SimctlError("Device $deviceId did not boot in time")
    }

    fun launchSimulator(deviceId: String) {
        CommandLineUtils.runCommand("xcrun simctl boot $deviceId")

        var exceptionToThrow: Exception? = null

        // Up to 10 iterations => max wait time of 1 second
        repeat(10) {
            try {
                CommandLineUtils.runCommand("open -a /Applications/Xcode.app/Contents/Developer/Applications/Simulator.app --args -CurrentDeviceUDID $deviceId")
                return
            } catch (e: Exception) {
                exceptionToThrow = e
                Thread.sleep(100)
            }
        }

        exceptionToThrow?.let { throw it }
    }

    fun reboot(
        deviceId: String,
    ) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "shutdown",
                deviceId
            ),
            waitForCompletion = true
        )
        awaitShutdown(deviceId)

        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "boot",
                deviceId
            ),
            waitForCompletion = true
        )
        awaitLaunch(deviceId)
    }

    fun addTrustedCertificate(
        deviceId: String,
        certificate: File,
    ) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "keychain",
                deviceId,
                "add-root-cert",
                certificate.absolutePath,
            ),
            waitForCompletion = true
        )

        reboot(deviceId)
    }

    fun ensureAppAlive(bundleId: String) {
        MaestroTimer.retryUntilTrue(timeoutMs = 4000, delayMs = 300) {
            val process = ProcessBuilder(
                "bash",
                "-c",
                "xcrun simctl spawn booted launchctl list | grep $bundleId | awk '/$bundleId/ {print \$3}'"
            ).start()

            val processOutput = process.inputStream.source().buffer().readUtf8().trim()
            process.waitFor(3000, TimeUnit.MILLISECONDS)

            processOutput.contains(bundleId)
        }
    }

    fun runXcTestWithoutBuild(deviceId: String, xcTestRunFilePath: String): Process {
        val date = dateFormatter.format(LocalDateTime.now())
        return CommandLineUtils.runCommand(
            "xcodebuild test-without-building -xctestrun $xcTestRunFilePath -destination id=$deviceId",
            waitForCompletion = false,
            outputFile = File(logDirectory, "xctest_runner_$date.log")
        )
    }

    fun uninstall(bundleId: String) {
        CommandLineUtils.runCommand("xcrun simctl uninstall booted $bundleId")
    }
}