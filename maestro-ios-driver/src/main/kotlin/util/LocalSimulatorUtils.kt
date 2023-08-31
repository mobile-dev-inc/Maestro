package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import util.CommandLineUtils.runCommand
import java.io.File
import java.io.InputStream
import java.lang.ProcessBuilder.Redirect.PIPE
import kotlin.io.path.createTempDirectory

object LocalSimulatorUtils {

    data class SimctlError(override val message: String): Throwable(message)
    private val homedir = System.getProperty("user.home")

    fun list(): SimctlList {
        val command = listOf("xcrun", "simctl", "list", "-j")

        val process = ProcessBuilder(command).start()
        val json = String(process.inputStream.readBytes())

        return jacksonObjectMapper().readValue(json)
    }

    fun awaitLaunch(deviceId: String, timeout: Long= 30000) {
        MaestroTimer.withTimeout(timeout) {
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

    private fun xcodePath(): String {
        val process = ProcessBuilder(listOf("xcode-select", "-p"))
            .start()

        return process.inputStream.bufferedReader().readLine()
    }

    fun bootSimulator(deviceId: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "boot",
                deviceId
            )
        )
    }
    fun launchSimulator(deviceId: String) {
        val simulatorPath = "${xcodePath()}/Applications/Simulator.app"
        var exceptionToThrow: Exception? = null

        // Up to 10 iterations => max wait time of 1 second
        repeat(10) {
            try {
                runCommand(
                    listOf(
                        "open",
                        "-a",
                        simulatorPath,
                        "--args",
                        "-CurrentDeviceUDID",
                        deviceId
                    )
                )
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
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "shutdown",
                deviceId
            ),
            waitForCompletion = true
        )
        awaitShutdown(deviceId)

        runCommand(
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
        runCommand(
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


    fun launch(
        deviceId: String,
        bundleId: String,
        launchArguments: List<String> = emptyList(),
        sessionId: String?,
    ) {
        sessionId?.let {
            runCommand(
                listOf(
                    "xcrun",
                    "simctl",
                    "spawn",
                    deviceId,
                    "launchctl",
                    "setenv",
                    "MAESTRO_SESSION_ID",
                    sessionId,
                )
            )
        }

        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "launch",
                deviceId,
                bundleId,
            ) + launchArguments,
        )
    }

    fun setLocation(deviceId: String, latitude: Double, longitude: Double) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "location",
                deviceId,
                "set",
                "$latitude,$longitude",
            )
        )
    }

    fun openURL(deviceId: String, url: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "openurl",
                deviceId,
                url,
            )
        )
    }

    fun uninstall(deviceId: String, bundleId: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "uninstall",
                deviceId,
                bundleId
            )
        )
    }

    fun clearKeychain(deviceId: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "spawn",
                deviceId,
                "launchctl",
                "stop",
                "com.apple.securityd",
            )
        )

        runCommand(
            listOf(
                "rm", "-rf",
                "$homedir/Library/Developer/CoreSimulator/Devices/$deviceId/data/Library/Keychains"
            )
        )

        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "spawn",
                deviceId,
                "launchctl",
                "start",
                "com.apple.securityd",
            )
        )
    }

    fun setPermissions(deviceId: String, bundleId: String, permissions: Map<String, String>) {
        val mutable = permissions.toMutableMap()
        if (mutable.containsKey("all")) {
            val value = mutable.remove("all")
            allPermissions.forEach {
                when (value) {
                    "allow" -> mutable.putIfAbsent(it, allowValueForPermission(it))
                    "deny" -> mutable.putIfAbsent(it, denyValueForPermission(it))
                    "unset" -> mutable.putIfAbsent(it, "unset")
                    else -> throw IllegalArgumentException("Permission 'all' can be set to 'allow', 'deny' or 'unset', not '$value'")
                }
            }
        }

        val argument = mutable
            .filter { allPermissions.contains(it.key) }
            .map { "${it.key}=${translatePermissionValue(it.value)}" }
            .joinToString(",")

        try {
            runCommand(
                listOf(
                    "$homedir/.maestro/deps/applesimutils",
                    "--byId",
                    deviceId,
                    "--bundle",
                    bundleId,
                    "--setPermissions",
                    argument
                )
            )
        } catch(e: Exception) {
            runCommand(
                listOf(
                    "applesimutils",
                    "--byId",
                    deviceId,
                    "--bundle",
                    bundleId,
                    "--setPermissions",
                    argument
                )
            )
        }
    }

    private val allPermissions = listOf(
        "calendar",
        "camera",
        "contacts",
        "faceid",
        "homekit",
        "location",
        "medialibrary",
        "microphone",
        "motion",
        "photos",
        "reminders",
        "siri",
        "speech",
        "userTracking",
    )

    private fun translatePermissionValue(value: String): String {
        return when (value) {
            "allow" -> "YES"
            "deny" -> "NO"
            else -> value
        }
    }

    private fun allowValueForPermission(permission: String): String {
        return when (permission) {
            "location" -> "always"
            else -> "YES"
        }
    }

    private fun denyValueForPermission(permission: String): String {
        return when (permission) {
            "location" -> "never"
            else -> "NO"
        }
    }

    fun install(deviceId: String, stream: InputStream) {
        val temp = createTempDirectory()
        val extractDir = temp.toFile()

        ArchiverFactory
            .createArchiver(ArchiveFormat.ZIP)
            .extract(stream, extractDir)

        val app = extractDir.walk()
            .filter { it.name.endsWith(".app") }
            .first()

        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "install",
                deviceId,
                app.absolutePath,
            )
        )
    }

    data class ScreenRecording(
        val process: Process,
        val file: File
    )

    fun startScreenRecording(deviceId: String): ScreenRecording {
        val tempDir = createTempDirectory()
        val inputStream = LocalSimulatorUtils::class.java.getResourceAsStream("/screenrecord.sh")
        if (inputStream != null) {
            val recording = File(tempDir.toFile(), "screenrecording.mov")

            val processBuilder = ProcessBuilder(
                listOf(
                    "bash",
                    "-c",
                    inputStream.bufferedReader().readText()
                )
            )
            val environment = processBuilder.environment()
            environment["DEVICE_ID"] = deviceId
            environment["RECORDING_PATH"] = recording.path

            val recordingProcess = processBuilder
                .redirectInput(PIPE)
                .start()

            return ScreenRecording(
                recordingProcess,
                recording
            )
        } else {
            throw IllegalStateException("screenrecord.sh file not found")
        }
    }

    fun stopScreenRecording(screenRecording: ScreenRecording): File {
        screenRecording.process.outputStream.close()
        screenRecording.process.waitFor()
        return screenRecording.file
    }
}
