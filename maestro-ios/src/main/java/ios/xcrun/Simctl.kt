package ios.xcrun

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit

object Simctl {
    private val NULL_FILE = File(
        if (System.getProperty("os.name")
                .startsWith("Windows")
        ) "NUL" else "/dev/null"
    )

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

    fun runXcTestWithoutBuild(deviceId: String, xcTestRunFilePath: String) {
        CommandLineUtils.runCommand(
            "xcodebuild test-without-building -xctestrun $xcTestRunFilePath -destination id=$deviceId",
            waitForCompletion = false,
            outputFile = NULL_FILE
        )
    }

    fun uninstall(bundleId: String) {
        CommandLineUtils.runCommand("xcrun simctl uninstall booted $bundleId")
    }
}