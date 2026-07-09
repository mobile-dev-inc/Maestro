package util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.TempFileHandler
import java.io.File
import java.util.concurrent.TimeUnit

class DeviceCtlProcess internal constructor(
    private val processStarter: (command: List<String>, errorOutput: File) -> Process,
    private val waitTimeoutMs: Long,
) {

    constructor() : this(
        processStarter = { command, errorOutput ->
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errorOutput)
                .start()
        },
        waitTimeoutMs = DEVICECTL_TIMEOUT_MS,
    )

    /**
     * Compatibility API for callers that consume the JSON output file directly. The caller owns
     * the returned file and must delete it. New code should use [devicectlDevicesJson] so the
     * complete temporary-file lifecycle stays inside [TempFileHandler].
     */
    fun devicectlDevicesOutput(): File {
        val tempFileHandler = TempFileHandler()
        try {
            val output = runDeviceList(tempFileHandler)
            tempFileHandler.tempFiles.remove(output)
            return output
        } finally {
            tempFileHandler.close()
        }
    }

    fun devicectlDevicesJson(): String {
        TempFileHandler().use { tempFileHandler ->
            return runDeviceList(tempFileHandler).readText()
        }
    }

    private fun runDeviceList(tempFileHandler: TempFileHandler): File {
        val jsonOutput = tempFileHandler.createTempFile("devicectl_response", ".json")
        val errorOutput = tempFileHandler.createTempFile("devicectl_error", ".log")
        val command = listOf(
            "xcrun",
            "devicectl",
            "--json-output",
            jsonOutput.path,
            "list",
            "devices",
        )
        val process = try {
            processStarter(command, errorOutput)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to start `xcrun devicectl list devices`. $DEVICECTL_GUIDANCE",
                e,
            )
        }

        val finished = try {
            process.waitFor(waitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw IllegalStateException(
                "Interrupted while waiting for `xcrun devicectl list devices`; the child process was stopped. " +
                        DEVICECTL_GUIDANCE,
                e,
            )
        }

        if (!finished) {
            terminate(process)
            throw IllegalStateException(
                "Timed out after ${waitTimeoutMs}ms while running `xcrun devicectl list devices`; " +
                        "the child process was stopped. $DEVICECTL_GUIDANCE"
            )
        }

        if (process.exitValue() != 0) {
            throw IllegalStateException(
                buildString {
                    append("`xcrun devicectl list devices` failed with exit code ${process.exitValue()}.")
                    errorOutput.readText().trim().takeIf { it.isNotEmpty() }?.let {
                        append(" Error: ${it.takeLast(MAX_ERROR_OUTPUT_LENGTH)}")
                    }
                    append(" $DEVICECTL_GUIDANCE")
                }
            )
        }

        if (jsonOutput.readText().isBlank()) {
            throw IllegalStateException(
                "`xcrun devicectl list devices` returned no JSON output. $DEVICECTL_GUIDANCE"
            )
        }
        return jsonOutput
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return

        var interrupted = false
        process.destroy()
        val stopped = try {
            process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!stopped && process.isAlive) {
            process.destroyForcibly()
            try {
                process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private const val DEVICECTL_TIMEOUT_MS = 15_000L
        private const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 3L
        private const val MAX_ERROR_OUTPUT_LENGTH = 1_000
        private const val DEVICECTL_GUIDANCE =
            "Ensure Xcode 15 or newer is selected and the iOS device is connected over USB, paired, and unlocked."
    }
}

class LocalIOSDevice(private val deviceCtlProcess: DeviceCtlProcess = DeviceCtlProcess()) {

    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
    }

    fun uninstall(deviceId: String, bundleIdentifier: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "uninstall",
                "app",
                "--device",
                deviceId,
                bundleIdentifier
            )
        )
    }

    fun listDeviceViaDeviceCtl(deviceId: String): DeviceCtlResponse.Device {
        return listDeviceViaDeviceCtl().find {
            it.hardwareProperties?.udid == deviceId && it.connectionProperties.isReachable
        } ?: throw IllegalArgumentException(
            "iOS device with identifier $deviceId is not connected over USB or available"
        )
    }

    fun listDeviceViaDeviceCtl(): List<DeviceCtlResponse.Device> {
        val response = deviceCtlProcess.devicectlDevicesJson()
        return objectMapper.readValue<DeviceCtlResponse>(response).result.devices
    }
}
