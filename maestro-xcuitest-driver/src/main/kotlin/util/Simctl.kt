package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object Simctl {

    fun listApps(): Set<String> {
        val process = ProcessBuilder("bash", "-c", "xcrun simctl listapps booted | plutil -convert json - -o -").start()

        val json = String(process.inputStream.readBytes())

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }
}