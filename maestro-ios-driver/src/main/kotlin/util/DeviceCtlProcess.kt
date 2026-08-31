package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.utils.TempFileHandler
import java.io.File
import java.util.concurrent.TimeUnit

class DeviceCtlProcess {

    fun listDevices(): List<DeviceCtlResponse.Device> =
        TempFileHandler().use { tempFiles ->
            val output = tempFiles.createTempFile("devicectl_response", ".json")
            val process = ProcessBuilder(
                listOf("xcrun", "devicectl", "--json-output", output.path, "list", "devices"),
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            if (!process.waitFor(LIST_DEVICES_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Timed out while listing iOS devices with devicectl")
            }

            check(process.exitValue() == 0) {
                "devicectl list devices failed with exit code ${process.exitValue()}"
            }

            parseDeviceList(output)
        }

    internal fun parseDeviceList(output: File): List<DeviceCtlResponse.Device> {
        if (output.length() == 0L) {
            return emptyList()
        }

        return jacksonObjectMapper()
            .readValue<DeviceCtlResponse>(output)
            .result
            .devices
    }

    private companion object {
        const val LIST_DEVICES_TIMEOUT_SECONDS = 15L
    }
}
