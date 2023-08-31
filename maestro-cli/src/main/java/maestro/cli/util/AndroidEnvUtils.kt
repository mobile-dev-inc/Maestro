package maestro.cli.util

import maestro.cli.CliError
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object AndroidEnvUtils {

    /**
     * @return Path to java compatible android cmdline-tools
     */
    fun requireCommandLineTools(tool: String): File {
        val androidHome = EnvUtils.androidHome()
            ?: throw CliError("Could not detect Android home environment variable is not set. Ensure that either ANDROID_HOME or ANDROID_SDK_ROOT is set.")

        val javaVersion = EnvUtils.getJavaVersion()
        val recommendedToolsVersion = getRecommendedToolsVersion()

        val tools = File(androidHome, "cmdline-tools")
        if (!tools.exists()) {
            throw CliError(
                "Missing required component cmdline-tools. To install:\n" +
                        "1) Open Android Studio SDK manager \n" +
                        "2) Check \"Show package details\" to show all versions\n" +
                        "3) Install Android SDK Command-Line Tools. Recommended version: $recommendedToolsVersion\n" +
                        "* https://developer.android.com/studio/intro/update#sdk-manager"
            )
        }

        return findCompatibleCommandLineTool(tool)
            ?: throw CliError(
                "Unable to find compatible cmdline-tools ($tools/<version>) for java version $javaVersion.\n\n" +
                        "Try to install a different cmdline-tools version:\n" +
                        "1) Open Android Studio SDK manager \n" +
                        "2) Check \"Show package details\" to show all versions\n" +
                        "3) Install Android SDK Command-Line Tools. Recommended version: $recommendedToolsVersion\n" +
                        "* https://developer.android.com/studio/intro/update#sdk-manager"
            )
    }

    private fun getRecommendedToolsVersion(): String? {
        return EnvUtils.getJavaVersion()?.let {
            if (it.startsWith("1.8") || it.startsWith("8.")) {
                "8.0"
            }
            else if (it.startsWith("11.")) {
                "10.0"
            }
            else if (it.startsWith("17")) {
                "11.0"
            }
            else "latest"
        }
    }

    private fun findCompatibleCommandLineTool(tool: String): File? {
        val path = File(EnvUtils.androidHome(), "cmdline-tools")
        return path.listFiles()
            .filter { it.isDirectory && File(it, "/bin/$tool").exists() }
            .filter { isCommandLineToolCompatible(File(it, "bin/$tool")) }
            .sortedWith(compareBy<File> { it.name != "latest" }
                .thenByDescending { it.name.toDoubleOrNull() })
            .map { File(it, "bin/$tool") }
            .firstOrNull()
    }

    /**
     * @return true if tool is compatible with running java version
     */
    private fun isCommandLineToolCompatible(toolPath: File): Boolean {
        return runCatching {
            val process = ProcessBuilder(listOf(toolPath.absolutePath, "-h")).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw TimeoutException()
            // don't rely on exit code, it's wrong
            val output = process.errorStream
                .source()
                .buffer()
                .readUtf8()
            return !output.contains("UnsupportedClassVersionError", ignoreCase = true)
        }.getOrNull() ?: false
    }

    /**
     * @return parses a string from 'avdmanager list device' and returns the pixel devices
     */
    fun parsePixelDevices(input: String): List<AvdDevice> {
        val pattern = "id: (\\d+) or \"(pixel.*?)\"\\n.*?Name: (.*?)\\n".toRegex()
        return pattern.findAll(input)
            .map { matchResult -> AvdDevice(matchResult.groupValues[1], matchResult.groupValues[2], matchResult.groupValues[3]) }
            .toList()
    }

    fun requireEmulatorBinary(): File {
        val androidHome = EnvUtils.androidHome()
            ?: throw CliError("Could not detect Android home environment variable is not set. Ensure that either ANDROID_HOME or ANDROID_SDK_ROOT is set.")
        val firstChoice = File(androidHome, "emulator/emulator")
        val secondChoice = File(androidHome, "tools/emulator")
        return firstChoice.takeIf { it.exists() } ?: secondChoice.takeIf { it.exists() }
        ?: throw CliError("Could not find emulator binary at either of the following paths:\n$firstChoice\n$secondChoice")
    }
}

data class AvdDevice(val numericId: String, val nameId: String, val name: String)
