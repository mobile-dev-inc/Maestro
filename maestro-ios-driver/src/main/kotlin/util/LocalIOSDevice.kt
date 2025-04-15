package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

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
}