package maestro.device.util

import maestro.device.Platform
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
        isX86_64 -> CPU_ARCHITECTURE.X86_64
        else -> CPU_ARCHITECTURE.UNKNOWN
      }
    }
  }

  object LinuxArchitectureDetection : ArchitectureDetectionStrategy {
    override fun detectArchitecture(): CPU_ARCHITECTURE {
      return when (runProcess("uname", "-m").first()) {
        "x86_64" -> CPU_ARCHITECTURE.X86_64
        "arm64" -> CPU_ARCHITECTURE.ARM64
        else -> CPU_ARCHITECTURE.UNKNOWN
      }
    }

  }

  object WindowsArchitectureDetection: ArchitectureDetectionStrategy {
    override fun detectArchitecture(): CPU_ARCHITECTURE {
      return CPU_ARCHITECTURE.X86_64
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


enum class CPU_ARCHITECTURE(val value: String) {
    X86_64("x86_64"),
    ARM64("arm64-v8a"),
    UNKNOWN("unknown");

    companion object {
        fun fromString(p: String?): Platform? {
          return Platform.entries.firstOrNull { it.description.equals(p, ignoreCase = true) }
        }
    }
}
