package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

object LocalDeviceUtils {

    fun listDevices(): IOSDeviceList {
        val command = listOf("xcrun", "xcdevice", "list")
        val process = ProcessBuilder(command).start()
        val json = String(process.inputStream.readBytes())
        return jacksonObjectMapper().readValue(json)
    }
}