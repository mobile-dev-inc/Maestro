package util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

class DeviceCtlProcess {

    fun devicectlDevicesOutput(): File {
        val tempOutput = File.createTempFile("devicectl_response", ".json")
        ProcessBuilder(listOf("xcrun", "devicectl", "--json-output", tempOutput.path, "list", "devices"))
            .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                waitFor()
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
        val tempOutput = File.createTempFile("devicectl_response", ".json")
        try {
            ProcessBuilder(listOf("xcrun" , "devicectl", "--json-output", tempOutput.path, "list", "devices"))
                .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                    waitFor()
                }
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
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput()
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