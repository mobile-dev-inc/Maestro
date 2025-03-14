package maestro.device.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.MaestroTimer
import org.slf4j.LoggerFactory
import maestro.device.util.CommandLineUtils.runCommand
import java.io.File
import java.lang.ProcessBuilder.Redirect.PIPE

object LocalSimulatorUtils {

    data class SimctlError(override val message: String) : Throwable(message)

    private val logger = LoggerFactory.getLogger(LocalSimulatorUtils::class.java)

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

    fun setDeviceLanguage(deviceId: String, language: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "language",
                deviceId,
                language
            ),
            waitForCompletion = true
        )
    }

    fun setDeviceLocale(deviceId: String, locale: String) {
        runCommand(
            listOf(
                "xcrun",
                "simctl",
                "locale",
                deviceId,
                locale
            ),
            waitForCompletion = true
        )
    }
}
