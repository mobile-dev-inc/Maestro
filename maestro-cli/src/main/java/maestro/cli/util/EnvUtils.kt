package maestro.cli.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.cli.api.CliVersion
import maestro.cli.update.Updates
import maestro.cli.view.red
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object EnvUtils {
    private const val PROD_API_URL = "https://api.copilot.mobile.dev"

    val OS_NAME: String = System.getProperty("os.name")
    val OS_ARCH: String = System.getProperty("os.arch")
    val OS_VERSION: String = System.getProperty("os.version")

    val CLI_VERSION: CliVersion? = getCLIVersion()

    fun getVersion(): CliVersion? {
        return getCLIVersion().apply {
            if (this == null) {
                System.err.println("\nWarning: Failed to parse current version".red())
            }
        }
    }

    val BASE_API_URL: String
        get() = System.getenv("MAESTRO_API_URL") ?: PROD_API_URL

    /**
     * Where Maestro config and state files were located before v1.37.0.
     */
    fun legacyMaestroHome(): Path {
        return Paths.get(System.getProperty("user.home"), ".maestro")
    }

    fun xdgStateHome(): Path {
        if (System.getenv("XDG_STATE_HOME") != null) {
            return Paths.get(System.getenv("XDG_STATE_HOME"), "maestro")
        }

        return Paths.get(System.getProperty("user.home"), ".maestro")
    }

    fun maestroCloudApiKey(): String? {
        return System.getenv("MAESTRO_CLOUD_API_KEY")
    }

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

    fun getFlutterVersionAndChannel(): Pair<String?, String?> {
        val stdout = try {
             runProcess(
                "flutter",
                "--no-version-check", "--version", "--machine",
            ).joinToString(separator = "")
        } catch (e: IOException) {
            // Flutter is probably not installed
            return Pair(first = null, second = null)
        }

        val mapper = jacksonObjectMapper()
        val version = runCatching {
            val obj: Map<String, String> = mapper.readValue(stdout)
            obj["flutterVersion"].toString()
        }
        val channel = runCatching {
            val obj: Map<String, String> = mapper.readValue(stdout)
            obj["channel"].toString()
        }

        return Pair(first = version.getOrNull(), second = channel.getOrNull())
    }

    fun getMacOSArchitecture(): CPU_ARCHITECTURE {
        return determineArchitectureDetectionStrategy().detectArchitecture()
    }

    private fun determineArchitectureDetectionStrategy(): ArchitectureDetectionStrategy {
        return if (isWindows()) {
            ArchitectureDetectionStrategy.WindowsArchitectureDetection
        } else if (runProcess("uname").contains("Linux")) {
            ArchitectureDetectionStrategy.LinuxArchitectureDetection
        } else {
            ArchitectureDetectionStrategy.MacOsArchitectureDetection
        }
    }

    private fun getCLIVersion(): CliVersion? {
        val props = try {
            Updates::class.java.classLoader.getResourceAsStream("version.properties").use {
                Properties().apply { load(it) }
            }
        } catch (e: Exception) {
            return null
        }

        val versionString = props["version"] as? String ?: return null

        return CliVersion.parse(versionString)
    }
}

sealed interface ArchitectureDetectionStrategy {

    fun detectArchitecture(): CPU_ARCHITECTURE

    object MacOsArchitectureDetection : ArchitectureDetectionStrategy {
        override fun detectArchitecture(): CPU_ARCHITECTURE {
            fun runSysctl(property: String) = runProcess("sysctl", property).any { it.endsWith(": 1") }

            // Prefer sysctl over 'uname -m' due to Rosetta making it unreliable
            val isArm64 = runSysctl("hw.optional.arm64")
            val isX86_64 = runSysctl("hw.optional.x86_64")
            return when {
                isArm64 -> CPU_ARCHITECTURE.ARM64
                isX86_64 -> CPU_ARCHITECTURE.x86_64
                else -> CPU_ARCHITECTURE.UNKNOWN
            }
        }
    }

    object LinuxArchitectureDetection : ArchitectureDetectionStrategy {
        override fun detectArchitecture(): CPU_ARCHITECTURE {
            return when (runProcess("uname", "-m").first()) {
                "x86_64" -> CPU_ARCHITECTURE.x86_64
                "arm64" -> CPU_ARCHITECTURE.ARM64
                else -> CPU_ARCHITECTURE.UNKNOWN
            }
        }

    }

    object WindowsArchitectureDetection: ArchitectureDetectionStrategy {
        override fun detectArchitecture(): CPU_ARCHITECTURE {
            return CPU_ARCHITECTURE.x86_64
        }
    }
}

enum class CPU_ARCHITECTURE {
    x86_64,
    ARM64,
    UNKNOWN
}

internal fun runProcess(program: String, vararg arguments: String): List<String> {
    val process = ProcessBuilder(program, *arguments).start()
    return try {
        process.inputStream.reader().use { it.readLines().map(String::trim) }
    } catch (ignore: Exception) {
        emptyList()
    }
}
