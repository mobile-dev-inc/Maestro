package maestro.cli.driver

import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import util.GoIosHelper

/**
 * Environment checks that run before any real-iOS-device session is set up, so common
 * misconfigurations fail with an actionable message instead of a stack trace mid-run.
 */
object RealDevicePreflight {

    fun run() {
        checkDevicectl()
        checkGoIos()
        PrintUtils.message(
            "Keep the iPhone unlocked while Maestro sets up the driver — xcodebuild stalls on a locked device."
        )
    }

    private fun checkDevicectl() {
        val available = try {
            ProcessBuilder("xcrun", "devicectl", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
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
                "Running Maestro on a physical iOS device requires go-ios for USB port forwarding. " +
                        GoIosHelper.INSTALL_HINT
            )
        }
    }
}
