package maestro.cli.driver

import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import util.GoIosHelper
import util.IProxyHelper
import util.IProxyNotFoundException
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

    internal fun checkDevicectl(
        processStarter: () -> Process = {
            ProcessBuilder("xcrun", "devicectl", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        },
    ) {
        val available = try {
            val process = processStarter()
            val finished = try {
                process.waitFor(PREFLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                terminate(process)
                Thread.currentThread().interrupt()
                throw e
            }
            if (!finished) {
                terminate(process)
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
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

    private fun terminate(process: Process) {
        var interrupted = false
        try {
            if (!process.isAlive) return
            runCatching { process.destroy() }
            val stopped = try {
                process.waitFor(PREFLIGHT_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!stopped && process.isAlive) {
                runCatching { process.destroyForcibly() }
                try {
                    process.waitFor(PREFLIGHT_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
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

    internal fun checkIProxy(checkAvailability: () -> Unit = IProxyHelper::checkAvailability) {
        try {
            checkAvailability()
        } catch (e: IProxyNotFoundException) {
            throw CliError(
                "Running Maestro on a physical iOS device requires iproxy for secure, " +
                        "loopback-only USB port forwarding. ${e.message}"
            )
        }
    }

    private const val PREFLIGHT_TIMEOUT_SECONDS = 10L
    private const val PREFLIGHT_TERMINATION_TIMEOUT_SECONDS = 3L
}
