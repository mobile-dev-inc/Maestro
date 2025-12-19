package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.harawata.appdirs.AppDirsFactory
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

object XCRunnerCLIUtils {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private const val MAX_COUNT_XCTEST_LOGS = 5

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }

    internal val logDirectory by lazy {
        val parentName = AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR)
        val logsDirectory = File(parentName, "xctest_runner_logs")
        File(parentName).apply {
            if (!exists()) mkdir()

            if (!logsDirectory.exists()) logsDirectory.mkdir()

            val existing = logsDirectory.listFiles() ?: emptyArray()
            val toDelete = existing.sortedByDescending { it.name }
            val count = toDelete.size
            if (count > MAX_COUNT_XCTEST_LOGS) toDelete.forEach { it.deleteRecursively() }
        }
        logsDirectory
    }

    fun clearLogs() {
        logDirectory.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun listApps(deviceId: String): Set<String> {
        val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", "xcrun simctl listapps $deviceId | plutil -convert json - -o -"))

        val json = String(process.inputStream.readBytes())

        if (json.isEmpty()) return emptySet()

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }

    fun setProxy(host: String, port: Int) {
        val service = proxyNetworkService()
        runNetworksetup("-setwebproxy", service, host, port.toString())
        runNetworksetup("-setwebproxystate", service, "on")
        runNetworksetup("-setsecurewebproxy", service, host, port.toString())
        runNetworksetup("-setsecurewebproxystate", service, "on")
    }

    fun resetProxy() {
        val service = proxyNetworkService()
        runNetworksetup("-setwebproxystate", service, "off")
        runNetworksetup("-setsecurewebproxystate", service, "off")
    }

    data class ProxySettings(
        val webEnabled: Boolean,
        val webHost: String,
        val webPort: Int,
        val secureEnabled: Boolean,
        val secureHost: String,
        val securePort: Int,
    )

    fun currentProxySettings(service: String = proxyNetworkService()): ProxySettings {
        val web = readProxyConfig(listOf("networksetup", "-getwebproxy", service))
        val secure = readProxyConfig(listOf("networksetup", "-getsecurewebproxy", service))
        return ProxySettings(
            webEnabled = web.enabled,
            webHost = web.host,
            webPort = web.port,
            secureEnabled = secure.enabled,
            secureHost = secure.host,
            securePort = secure.port,
        )
    }

    fun restoreProxySettings(settings: ProxySettings, service: String = proxyNetworkService()) {
        // Best-effort restore. Some macOS versions may reject an empty host; always restore enabled state at least.
        runCatching {
            runNetworksetup("-setwebproxy", service, settings.webHost, settings.webPort.toString())
        }
        runNetworksetup("-setwebproxystate", service, if (settings.webEnabled) "on" else "off")

        runCatching {
            runNetworksetup("-setsecurewebproxy", service, settings.secureHost, settings.securePort.toString())
        }
        runNetworksetup("-setsecurewebproxystate", service, if (settings.secureEnabled) "on" else "off")
    }

    private data class ParsedProxyConfig(
        val enabled: Boolean,
        val host: String,
        val port: Int,
    )

    private fun readProxyConfig(command: List<String>): ParsedProxyConfig {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        fun findValue(prefix: String): String? {
            val line = lines.firstOrNull { it.trimStart().startsWith(prefix) } ?: return null
            return line.substringAfter(prefix).trim()
        }

        val enabled = when (findValue("Enabled:")) {
            "Yes" -> true
            "No" -> false
            else -> false
        }
        val host = findValue("Server:") ?: ""
        val port = findValue("Port:")?.toIntOrNull() ?: 0

        return ParsedProxyConfig(enabled = enabled, host = host, port = port)
    }

    private fun runNetworksetup(vararg args: String) {
        ProcessBuilder(listOf("networksetup") + args)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    private fun proxyNetworkService(): String {
        return System.getenv("MAESTRO_NETWORK_SERVICE")?.takeIf { it.isNotBlank() } ?: "Wi-Fi"
    }

    fun uninstall(bundleId: String, deviceId: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "uninstall",
                deviceId,
                bundleId
            )
        )
    }

    private fun runningApps(deviceId: String): Map<String, Int?> {
        val process = ProcessBuilder(
            "xcrun",
            "simctl",
            "spawn",
            deviceId,
            "launchctl",
            "list"
        ).start()

        val processOutput = process.inputStream.bufferedReader().readLines()

        if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) {
            return emptyMap()
        }
        return processOutput
            .asSequence()
            .drop(1)
            .toList()
            .map { line -> line.split("\\s+".toRegex()) }
            .filter { parts -> parts.count() <= 3 }
            .associate { parts -> parts[2] to parts[0].toIntOrNull() }
            .mapKeys { (key, _) ->
                // Fixes issue with iOS 14.0 where process names are sometimes prefixed with "UIKitApplication:"
                // and ending with [stuff]
                key
                    .substringBefore("[")
                    .replace("UIKitApplication:", "")
            }
    }

    fun pidForApp(bundleId: String, deviceId: String): Int? {
        return runningApps(deviceId)[bundleId]
    }

    fun runXcTestWithoutBuild(deviceId: String, xcTestRunFilePath: String, port: Int, snapshotKeyHonorModalViews: Boolean?): Process {
        val date = dateFormatter.format(LocalDateTime.now())
        val outputFile = File(logDirectory, "xctest_runner_$date.log")
        val logOutputDir = Files.createTempDirectory("maestro_xctestrunner_xcodebuild_output")
        val params = mutableMapOf("TEST_RUNNER_PORT" to port.toString())
        if (snapshotKeyHonorModalViews != null) {
            params["TEST_RUNNER_snapshotKeyHonorModalViews"] = snapshotKeyHonorModalViews.toString()
        }
        return CommandLineUtils.runCommand(
            listOf(
                "xcodebuild",
                "test-without-building",
                "-xctestrun",
                xcTestRunFilePath,
                "-destination",
                "id=$deviceId",
                "-derivedDataPath",
                logOutputDir.absolutePathString()
            ),
            waitForCompletion = false,
            outputFile = outputFile,
            params = params,
        )
    }
}
