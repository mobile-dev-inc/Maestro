package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import util.CommandLineUtils.runCommand
import java.io.File
import java.io.InputStream
import java.lang.ProcessBuilder.Redirect.PIPE
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory

object LocalSimulatorUtils {

    data class SimctlError(override val message: String, override val cause: Throwable? = null) : Throwable(message, cause)

    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private val homedir = System.getProperty("user.home")
    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val date = dateFormatter.format(LocalDateTime.now())

    private val logger = LoggerFactory.getLogger(LocalSimulatorUtils::class.java)

    private val allPermissions = listOf(
        "calendar",
        "camera",
        "contacts",
        "faceid",
        "homekit",
        "medialibrary",
        "microphone",
        "motion",
        "photos",
        "reminders",
        "siri",
        "speech",
        "userTracking",
    )

    private val simctlPermissions = listOf(
        "location"
    )

    fun list(): SimctlList {
        val command = listOf("xcrun", "simctl", "list", "-j")

        val process = ProcessBuilder(command).start()
        val json = String(process.inputStream.readBytes())

        return jacksonObjectMapper().readValue(json)
    }

    fun awaitLaunch(deviceId: String) {
        MaestroTimer.withTimeout(60000) {
            if (list()
                    .devices
                    .values
                    .flatten()
                    .find { it.udid.equals(deviceId, ignoreCase = true) }
                    ?.state == "Booted"
            ) true else null
        } ?: throw SimctlError("Device $deviceId did not boot in time")
    }

    fun awaitShutdown(deviceId: String, timeoutMs: Long = 60000) {
        MaestroTimer.withTimeout(timeoutMs) {
            if (list()
                    .devices
                    .values
                    .flatten()
                    .find { it.udid.equals(deviceId, ignoreCase = true) }
                    ?.state == "Shutdown"
            ) true else null
        } ?: throw SimctlError("Device $deviceId did not shutdown in time")
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
            ),
            waitForCompletion = true
        )
        awaitLaunch(deviceId)
    }

    fun shutdownSimulator(deviceId: String) {
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
        shutdownSimulator(deviceId)
        bootSimulator(deviceId)
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
        logger.info("[Start] Terminating app $bundleId")
        runCatching {
            runCommand(
                listOf(
                    "xcrun",
                    "simctl",
                    "terminate",
                    deviceId,
                    bundleId
                )
            )
        }.onFailure {
            if (it.message?.contains("found nothing to terminate") == false) {
                logger.info("The bundle $bundleId is already terminated")
                throw it
            }
        }
        logger.info("[Done] Terminating app $bundleId")
    }

    private fun isAppRunning(deviceId: String, bundleId: String): Boolean {
        val process = ProcessBuilder(
            listOf(
                "xcrun",
                "simctl",
                "spawn",
                deviceId,
                "launchctl",
                "list",
            )
        ).start()

        return String(process.inputStream.readBytes()).trimEnd().contains(bundleId)
    }

    private fun ensureStopped(deviceId: String, bundleId: String) {
        MaestroTimer.withTimeout(10000) {
            while (true) {
                if (isAppRunning(deviceId, bundleId)) {
                    Thread.sleep(1000)
                } else {
                    return@withTimeout
                }
            }
        } ?: throw SimctlError("App $bundleId did not stop in time")
    }

    private fun ensureRunning(deviceId: String, bundleId: String) {
        MaestroTimer.withTimeout(10000) {
            while (true) {
                if (isAppRunning(deviceId, bundleId)) {
                    return@withTimeout
                } else {
                    Thread.sleep(1000)
                }
            }
        } ?: throw SimctlError("App $bundleId did not start in time")
    }

    private fun copyDirectoryRecursively(source: Path, target: Path) {
        Files.walk(source).forEach { path ->
            val targetPath = target.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(targetPath)
            } else {
                Files.copy(path, targetPath)
            }
        }
    }

    private fun deleteFolderRecursively(folder: File): Boolean {
        if (folder.isDirectory) {
            folder.listFiles()?.forEach { child ->
                deleteFolderRecursively(child)
            }
        }
        return folder.delete()
    }

    private fun reinstallApp(deviceId: String, bundleId: String) {
        val appBinaryPath = getAppBinaryDirectory(deviceId, bundleId)
        if (appBinaryPath.isEmpty()) {
            throw SimctlError("Could not find app binary for bundle $bundleId at $appBinaryPath")
        }

        val pathToBinary = Path(appBinaryPath)
        if (Files.isDirectory(pathToBinary)) {
            val tmpDir = createTempDirectory()
            val tmpBundlePath = tmpDir.resolve("$bundleId-${System.currentTimeMillis()}.app")

            logger.info("Copying app binary from $pathToBinary to $tmpBundlePath")
            Files.copy(pathToBinary, tmpBundlePath)
            copyDirectoryRecursively(pathToBinary, tmpBundlePath)

            logger.info("Reinstalling and launching $bundleId")
            uninstall(deviceId, bundleId)
            install(deviceId, tmpBundlePath)
            deleteFolderRecursively(tmpBundlePath.toFile())
            logger.info("App $bundleId reinstalled and launched")
        } else {
            throw SimctlError("Could not find app binary for bundle $bundleId at $pathToBinary")
        }
    }

    fun clearAppState(deviceId: String, bundleId: String) {
        logger.info("Clearing app $bundleId state")
        // Stop the app before clearing the file system
        // This prevents the app from saving its state after it has been cleared
        terminate(deviceId, bundleId)
        ensureStopped(deviceId, bundleId)

        // reinstall the app as that is the most stable way to clear state
        reinstallApp(deviceId, bundleId)
    }

    private fun getAppBinaryDirectory(deviceId: String, bundleId: String): String {
        val process = ProcessBuilder(
            listOf(
                "xcrun",
                "simctl",
                "get_app_container",
                deviceId,
                bundleId,
            )
        ).start()

        return String(process.inputStream.readBytes()).trimEnd()
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
    ) {
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

    fun launchUITestRunner(
        deviceId: String,
        port: Int,
    ) {
        val outputFile = File(XCRunnerCLIUtils.logDirectory, "xctest_runner_$date.log")
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "launch",
                "--console",
                "--terminate-running-process",
                deviceId,
                "dev.mobile.maestro-driver-iosUITests.xctrunner"
            ),
            params = mapOf("SIMCTL_CHILD_PORT" to port.toString()),
            outputFile = outputFile,
            waitForCompletion = false,
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

    fun addMedia(deviceId: String, path: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "addmedia",
                deviceId,
                path
            )
        )
    }

    fun clearKeychain(deviceId: String) {
        runCommand(
            listOf("xcrun", "simctl", "keychain", deviceId, "reset")
        )
    }

    fun setAppleSimutilsPermissions(deviceId: String, bundleId: String, permissions: Map<String, String>) {
        val permissionsMap = permissions.toMutableMap()
        if (permissionsMap.containsKey("all")) {
            val value = permissionsMap.remove("all")
            allPermissions.forEach {
                when (value) {
                    "allow" -> permissionsMap.putIfAbsent(it, allowValueForPermission(it))
                    "deny" -> permissionsMap.putIfAbsent(it, denyValueForPermission(it))
                    "unset" -> permissionsMap.putIfAbsent(it, "unset")
                    else -> throw IllegalArgumentException("Permission 'all' can be set to 'allow', 'deny' or 'unset', not '$value'")
                }
            }
        }

        val permissionsArgument = permissionsMap
            .filter { allPermissions.contains(it.key) }
            .map { "${it.key}=${translatePermissionValue(it.value)}" }
            .joinToString(",")

        if (permissionsArgument.isNotEmpty()) {
            try {
                logger.info("[Start] Setting permissions via pinned applesimutils")
                runCommand(
                    listOf(
                        "$homedir/.maestro/deps/applesimutils",
                        "--byId",
                        deviceId,
                        "--bundle",
                        bundleId,
                        "--setPermissions",
                        permissionsArgument
                    )
                )
                logger.info("[Done] Setting permissions pinned applesimutils")
            } catch (e: Exception) {
                logger.error("Exception while setting permissions through pinned applesimutils ${e.message}", e)
                logger.info("[Start] Setting permissions via applesimutils as fallback")
                runCommand(
                    listOf(
                        "applesimutils",
                        "--byId",
                        deviceId,
                        "--bundle",
                        bundleId,
                        "--setPermissions",
                        permissionsArgument
                    )
                )
                logger.info("[Done] Setting permissions via applesimutils as fallback")
            }
        }
    }

    fun setSimctlPermissions(deviceId: String, bundleId: String, permissions: Map<String, String>) {
        val permissionsMap = permissions.toMutableMap()

        permissionsMap.remove("all")?.let { value ->
            val transformedPermissions = simctlPermissions.associateWith { permission ->
                val newValue = when (value) {
                    "allow" -> allowValueForPermission(permission)
                    "deny" -> denyValueForPermission(permission)
                    "unset" -> "unset"
                    else -> throw IllegalArgumentException("Permission 'all' can be set to 'allow', 'deny', or 'unset', not '$value'")
                }
                newValue
            }

            permissionsMap.putAll(transformedPermissions)
        }


        permissionsMap
            .forEach {
                if (simctlPermissions.contains(it.key)) {
                    when (it.key) {
                        // TODO: more simctl supported permissions can be migrated here
                        "location" -> {
                            setLocationPermission(deviceId, bundleId, it.value)
                        }
                    }
                }
            }
    }

    private fun setLocationPermission(deviceId: String, bundleId: String, value: String) {
        when (value) {
            "always" -> {
                runCommand(
                    listOf(
                        "xcrun",
                        "simctl",
                        "privacy",
                        deviceId,
                        "grant",
                        "location-always",
                        bundleId
                    )
                )
            }

            "inuse" -> {
                runCommand(
                    listOf(
                        "xcrun",
                        "simctl",
                        "privacy",
                        deviceId,
                        "grant",
                        "location",
                        bundleId
                    )
                )
            }

            "never" -> {
                runCommand(
                    listOf(
                        "xcrun",
                        "simctl",
                        "privacy",
                        deviceId,
                        "revoke",
                        "location-always",
                        bundleId
                    )
                )
            }

            "unset" -> {
                runCommand(
                    listOf(
                        "xcrun",
                        "simctl",
                        "privacy",
                        deviceId,
                        "reset",
                        "location-always",
                        bundleId
                    )
                )
            }

            else -> throw IllegalArgumentException("wrong argument value '$value' was provided for 'location' permission")
        }
    }

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

    fun install(deviceId: String, path: Path) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "install",
                deviceId,
                path.toAbsolutePath().toString(),
            )
        )
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
        val file: File,
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

    fun setDeviceLanguage(deviceId: String, language: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "spawn",
                deviceId,
                "defaults",
                "write",
                ".GlobalPreferences.plist",
                "AppleLanguages",
                "($language)"
            )
        )
    }

    fun setDeviceLocale(deviceId: String, locale: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "spawn",
                deviceId,
                "defaults",
                "write",
                ".GlobalPreferences.plist",
                "AppleLocale",
                "-string",
                locale
            )
        )
    }

    fun stopScreenRecording(screenRecording: ScreenRecording): File {
        screenRecording.process.outputStream.close()
        screenRecording.process.waitFor()
        return screenRecording.file
    }
}
