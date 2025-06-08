package maestro.device.util

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object EnvUtils {
    val OS_NAME: String = System.getProperty("os.name")
    val OS_ARCH: String = System.getProperty("os.arch")
    val OS_VERSION: String = System.getProperty("os.version")

    /**
     * @return true, if we're executing from Windows Linux shell (WSL)
     */
    fun isWSL(): Boolean {
        try {
            val p1 = ProcessBuilder("printenv", "WSL_DISTRO_NAME").start()
            if (!p1.waitFor(20, TimeUnit.SECONDS)) throw TimeoutException()
            if (p1.exitValue() == 0 && String(p1.inputStream.readBytes()).trim().isNotEmpty()) {
                return true
            }

            val p2 = ProcessBuilder("printenv", "IS_WSL").start()
            if (!p2.waitFor(20, TimeUnit.SECONDS)) throw TimeoutException()
            if (p2.exitValue() == 0 && String(p2.inputStream.readBytes()).trim().isNotEmpty()) {
                return true
            }
        } catch (ignore: Exception) {
            // ignore
        }

        return false
    }

    fun isWindows(): Boolean {
        return OS_NAME.lowercase().startsWith("windows")
    }

    /**
     * Returns major version of Java, e.g. 8, 11, 17, 21.
     */
    fun getJavaVersion(): Int {
        // Adapted from https://stackoverflow.com/a/2591122/7009800
        val version = System.getProperty("java.version")
        return if (version.startsWith("1.")) {
            version.substring(2, 3).toInt()
        } else {
            val dot = version.indexOf(".")
            if (dot != -1) version.substring(0, dot).toInt() else 0
        }
    }
}

internal fun runProcess(program: String, vararg arguments: String): List<String> {
    val process = ProcessBuilder(program, *arguments).start()
    return try {
        process.inputStream.reader().use { it.readLines().map(String::trim) }
    } catch (ignore: Exception) {
        emptyList()
    }
}
