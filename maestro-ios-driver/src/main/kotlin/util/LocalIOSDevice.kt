package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

object LocalIOSDevice {

    fun listDeviceViaXcDevice(): List<XCDevice> {
        val process = ProcessBuilder(listOf("xcrun","xcdevice","list"))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                waitFor()
            }

        val bytes = process.inputStream.readBytes()

        val response = String(bytes)
        val xcDeviceList = jacksonObjectMapper().readValue<List<XCDevice>>(response)

        return xcDeviceList.filter { !it.simulator && it.platform == XCDevice.IPHONE_PLATFORM }
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

            val deviceCtlResponse = jacksonObjectMapper().readValue<DeviceCtlResponse>(response)
            return deviceCtlResponse.result.devices.find {
                it.hardwareProperties.udid == deviceId
            } ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")
        } finally {
            tempOutput.delete()
        }
    }
}