package ios.devicectl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.ViewHierarchy
import okio.Sink
import org.slf4j.LoggerFactory
import util.CommandLineUtils
import util.GoIosHelper
import util.IOSLaunchArguments.toIOSLaunchArguments
import util.LocalIOSDevice
import util.LocalIOSDeviceController
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.installer.LocalXCTestInstaller
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-management backend for physical iOS devices, built on `xcrun devicectl` with
 * go-ios filling the gaps devicectl cannot cover headlessly (location, terminate-by-bundle-id).
 *
 * UI automation never goes through this class — the LocalIOSDevice facade routes it to the
 * on-device XCTest HTTP server. Operations a real device cannot support fail loudly instead
 * of silently no-oping, so flows never diverge from Simulator behaviour without an error.
 */
class DeviceControlIOSDevice(
    override val deviceId: String,
    private val goIosHelperProvider: () -> GoIosHelper = { GoIosHelper() },
) : IOSDevice {

    private val localIOSDevice by lazy { LocalIOSDevice() }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceControlIOSDevice::class.java)
        private val jsonMapper = jacksonObjectMapper()

        internal fun buildLaunchCommand(
            deviceId: String,
            bundleId: String,
            launchArguments: Map<String, Any>,
            jsonOutputPath: String,
        ): List<String> {
            val command = mutableListOf(
                "xcrun", "devicectl", "device", "process", "launch",
                "--terminate-existing",
                "--json-output", jsonOutputPath,
                "--device", deviceId,
            )
            val appArguments = launchArguments.toIOSLaunchArguments()
            if (appArguments.isEmpty()) {
                command.add(bundleId)
            } else {
                // App arguments come out of toIOSLaunchArguments() with a leading dash
                // (e.g. "-foo bar"); the "--" terminator stops devicectl's own option parser
                // from trying to consume them.
                command.add("--")
                command.add(bundleId)
                command.addAll(appArguments)
            }
            return command
        }

        internal fun buildTerminateCommand(deviceId: String, pid: Long): List<String> = listOf(
            "xcrun", "devicectl", "device", "process", "terminate",
            "--device", deviceId,
            "--pid", pid.toString(),
        )

        internal fun parseLaunchedProcessId(json: String): Long? = runCatching {
            jsonMapper.readTree(json)
                .path("result").path("process").path("processIdentifier")
                .asLong(0L)
                .takeIf { it > 0L }
        }.getOrNull()
    }

    /**
     * Set by the session wiring once the XCTest runner client exists; used for operations
     * (openLink) that are served by the on-device runner rather than devicectl.
     */
    var xcTestDriverClient: XCTestDriverClient? = null

    private val launchedPids = ConcurrentHashMap<String, Long>()
    private var goIosHelper: GoIosHelper? = null

    private fun goIos(): GoIosHelper =
        goIosHelper ?: goIosHelperProvider().also { goIosHelper = it }

    override fun open() {
        // No-op for real devices — device is already running
    }

    override fun deviceInfo(): DeviceInfo {
        // Device info is fetched via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        // View hierarchy is fetched via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun tap(x: Int, y: Int) {
        // Taps are handled via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        // Long presses are handled via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) {
        // Scrolls are handled via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun input(text: String) {
        // Text input is handled via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun install(stream: InputStream) {
        logger.info("Installing app on device $deviceId")
        LocalIOSDeviceController.install(deviceId, stream)
    }

    override fun uninstall(id: String) {
        localIOSDevice.uninstall(deviceId, id)
    }

    override fun clearAppState(id: String) {
        // The data container of another app is inaccessible on a non-jailbroken device, so
        // there is no true clearState. Failing loudly beats silently diverging from Simulator.
        throw UnsupportedOperationException(
            "clearState is not supported on real iOS devices: app data containers cannot be " +
                    "accessed without reinstalling the app. Uninstall and reinstall '$id' to reset " +
                    "its state, or remove clearState from this flow when running on a physical device."
        )
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        logger.warn("clearKeychain has no effect on real iOS devices (requires MDM/supervision); continuing without clearing")
        return Ok(Unit)
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        logger.info("Launching app $id on device $deviceId")
        val jsonOutput = File.createTempFile("devicectl_launch", ".json")
        try {
            CommandLineUtils.runCommand(buildLaunchCommand(deviceId, id, launchArguments, jsonOutput.path))
            val pid = parseLaunchedProcessId(jsonOutput.readText())
            if (pid != null) {
                launchedPids[id] = pid
            } else {
                logger.warn("Could not resolve pid of launched app $id from devicectl output")
            }
        } finally {
            jsonOutput.delete()
        }
    }

    override fun stop(id: String) {
        logger.info("Stopping app $id on device $deviceId")
        try {
            val pid = launchedPids.remove(id)
            if (pid != null) {
                CommandLineUtils.runCommand(buildTerminateCommand(deviceId, pid))
            } else {
                // devicectl can only terminate by pid; go-ios can kill by bundle id.
                goIos().kill(id, deviceId)
            }
        } catch (e: Exception) {
            // Termination failure usually means the app is not running; the user-visible
            // stop path goes through the XCTest server, so this is best-effort.
            logger.warn("Failed to stop app $id: ${e.message}")
        }
    }

    override fun isKeyboardVisible(): Boolean {
        // Keyboard visibility is checked via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        logger.info("Opening link $link on device $deviceId")
        val client = xcTestDriverClient
            ?: return Err(IllegalStateException("Cannot open link: XCTest runner client is not initialised for device $deviceId"))
        return try {
            client.openLink(link)
            Ok(Unit)
        } catch (e: Exception) {
            Err(e)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        // Screenshots are taken via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun startScreenRecording(out: Sink): IOSScreenRecording {
        // Not supported on real devices via devicectl — fail loudly rather than silently no-op.
        throw UnsupportedOperationException(
            "Screen recording is not yet supported on real iOS devices. Remove the recording " +
                    "option when running on a physical device."
        )
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        logger.info("Setting location to $latitude,$longitude on device $deviceId")
        return try {
            goIos().setLocation(latitude, longitude, deviceId)
            Ok(Unit)
        } catch (e: Exception) {
            Err(e)
        }
    }

    override fun setOrientation(orientation: String) {
        // Orientation is set via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        return false
    }

    override fun isScreenStatic(): Boolean {
        // Screen static check is done via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        // Permission alerts are handled at interaction time by the XCTest runner
        // (the facade also forwards the permission map there); nothing to pre-grant here.
    }

    override fun pressKey(name: String) {
        // Key presses are handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun pressButton(name: String) {
        // Button presses are handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        // Text erasure is handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun addMedia(path: String) {
        throw UnsupportedOperationException(
            "addMedia is not supported on real iOS devices: media cannot be injected into the " +
                    "Photos library of a physical device. Remove addMedia from this flow when " +
                    "running on a physical device."
        )
    }

    override fun close() {
        logger.info("[Start] Uninstall the runner app")
        uninstall(id = LocalXCTestInstaller.UI_TEST_RUNNER_APP_BUNDLE_ID)
        logger.info("[Done] Uninstall the runner app")
        goIosHelper?.close()
        goIosHelper = null
    }
}
