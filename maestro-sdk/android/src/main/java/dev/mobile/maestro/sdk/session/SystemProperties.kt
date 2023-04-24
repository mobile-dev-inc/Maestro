package dev.mobile.maestro.sdk.session

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

object SystemProperties {

    private const val GETPROP_EXECUTABLE_PATH = "/system/bin/getprop"

    fun read(propName: String): String? {
        var process: Process? = null
        var bufferedReader: BufferedReader? = null
        return try {
            process = ProcessBuilder().command(GETPROP_EXECUTABLE_PATH, propName).redirectErrorStream(true).start()
            bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            bufferedReader.readLine()
        } catch (e: Exception) {
            null
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close()
                } catch (_: IOException) {
                }
            }
            process?.destroy()
        }
    }
}
