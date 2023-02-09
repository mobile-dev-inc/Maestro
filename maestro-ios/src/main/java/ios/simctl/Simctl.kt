package ios.simctl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import util.CommandLineUtils
import java.io.File

object Simctl {

    data class SimctlError(override val message: String): Throwable(message)

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

    fun terminate(deviceId: String, bundleId: String) {
        // Ignore error return: terminate will fail if the app is not running
        ProcessBuilder(
            listOf(
                "xcrun",
                "simctl",
                "terminate",
                deviceId,
                bundleId
            )
        )
            .start()
            .waitFor()
    }

    fun clearAppState(deviceId: String, bundleId: String) {
        // Stop the app before clearing the file system
        // This prevents the app from saving its state after it has been cleared
        terminate(deviceId, bundleId)

        // Wait for the app to be stopped
        Thread.sleep(1500)

        // deletes app data, including container folder
        val appDataDirectory = getApplicationDataDirectory(deviceId, bundleId)
        ProcessBuilder(listOf("rm", "-rf", appDataDirectory)).start().waitFor()

        // forces app container folder to be re-created
        val paths = listOf(
            "Documents",
            "Library",
            "Library/Caches",
            "Library/Preferences",
            "SystemData",
            "tmp"
        )

        val command = listOf("mkdir", appDataDirectory) + paths.map { "$appDataDirectory/$it" }
        ProcessBuilder(command).start().waitFor()
    }

    private fun getApplicationDataDirectory(deviceId: String, bundleId: String): String {
        val process = ProcessBuilder(
            listOf(
                "xcrun",
                "simctl",
                "get_app_container",
                deviceId,
                bundleId,
                "data"
            )
        ).start()

        return String(process.inputStream.readBytes()).trimEnd()
    }
}
