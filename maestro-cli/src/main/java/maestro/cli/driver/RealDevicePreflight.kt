package maestro.cli.driver

import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import util.GoIosHelper
import util.IProxyHelper
import java.util.concurrent.TimeUnit

/**
 * Environment checks that run before any real-iOS-device session is set up, so common
 * misconfigurations fail with an actionable message instead of a stack trace mid-run.
 */
object RealDevicePreflight {

    fun run() {
        checkDevicectl()
        checkGoIos()
        checkIProxy()
        PrintUtils.message(
            "Keep the iPhone unlocked while Maestro sets up the driver — xcodebuild stalls on a locked device."
        )
    }

    private fun checkDevicectl() {
        val available = try {
            val process = ProcessBuilder("xcrun", "devicectl", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(PREFLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
        if (!available) {
            throw CliError(
                "Running Maestro on a physical iOS device requires Xcode 15 or newer " +
                        "(`xcrun devicectl` was not found or failed). Install or upgrade Xcode and " +
                        "select it with `sudo xcode-select -s /Applications/Xcode.app`."
            )
        }
    }

    private fun checkGoIos() {
        if (!GoIosHelper.isAvailable()) {
            throw CliError(
                "Running Maestro on a physical iOS device requires go-ios for device services such as logs and location. " +
                        GoIosHelper.INSTALL_HINT
            )
        }
    }

    private fun checkIProxy() {
        if (!IProxyHelper.isAvailable()) {
            throw CliError(
                "Running Maestro on a physical iOS device requires iproxy for secure, " +
                        "loopback-only USB port forwarding. " + IProxyHelper.INSTALL_HINT
            )
        }
    }

    private const val PREFLIGHT_TIMEOUT_SECONDS = 10L
}
