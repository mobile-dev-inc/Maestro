package util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

class DeviceCtlProcess {

    /**
     * Executes `xcrun devicectl list devices` (lists Apple devices visible to Xcode)
     * and writes JSON output to a temp file.
     *
     * @return temp JSON file on success, or null if the command fails.
     */
    fun devicectlDevicesOutput(): File? {
        val tempOutput = File.createTempFile("devicectl_response", ".json")

        val process = ProcessBuilder(
            listOf("xcrun", "devicectl", "--json-output", tempOutput.path, "list", "devices")
        )
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val error = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()

        if (exit != 0 || error.isNotBlank()) {
            println("[WARNING] unable to retrieve list of Apple device (devicectl failed)")
            println("   > Make sure that `xcrun devicectl --help` command runs without an issue")
            println("Proceeding without Apple devices...\n")
            tempOutput.delete()
            return null
        }

        return tempOutput
    }
}

class LocalIOSDevice(private val deviceCtlProcess: DeviceCtlProcess = DeviceCtlProcess()) {

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
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput()
            ?: throw java.lang.IllegalArgumentException("Unable retrieve device list")
        try {
            val bytes = tempOutput.readBytes()
            val response = String(bytes)

            val jacksonObjectMapper = jacksonObjectMapper()
            jacksonObjectMapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
            val deviceCtlResponse = jacksonObjectMapper.readValue<DeviceCtlResponse>(response)
            return deviceCtlResponse.result.devices.find {
                it.hardwareProperties?.udid == deviceId
            } ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")
        } finally {
            tempOutput.delete()
        }
    }

    fun listDeviceViaDeviceCtl(): List<DeviceCtlResponse.Device> {
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput() ?: return emptyList()

        try {
            val bytes = tempOutput.readBytes()
            val response = String(bytes)

            val deviceCtlResponse = jacksonObjectMapper().readValue<DeviceCtlResponse>(response)
            return deviceCtlResponse.result.devices
        } finally {
            tempOutput.delete()
        }
    }
}