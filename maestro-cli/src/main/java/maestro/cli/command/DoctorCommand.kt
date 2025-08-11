package maestro.cli.command

import picocli.CommandLine
import java.io.PrintWriter
import java.util.concurrent.Callable
import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils

@CommandLine.Command(
    name = "doctor",
    description = ["Show information about the local environment for debugging and issue reporting."]
)
class DoctorCommand : Callable<Int> {
    @CommandLine.Option(names = ["-v", "--verbose"], description = ["Show verbose output."])
    var verbose: Boolean = false

    override fun call(): Int {
        val out = PrintWriter(System.out, true)
        printDoctorSummary(out, verbose)
        return 0
    }

    companion object {
        fun printDoctorSummary(out: PrintWriter, verbose: Boolean) {
            val cliVersion = EnvUtils.CLI_VERSION?.toString() ?: "Unknown"
            val osName = EnvUtils.OS_NAME
            val osVersion = EnvUtils.OS_VERSION
            val osArch = EnvUtils.OS_ARCH
            val javaVersion = System.getProperty("java.version") ?: "Unknown"
            val javaVendor = System.getProperty("java.vendor") ?: "Unknown"
            
            val (installMethod, installSource) = detectInstallMethod()

            out.println("Doctor summary (to see all details, run maestro doctor -v):")
            out.println("• Maestro CLI v$cliVersion [$installSource]")
            out.println("• $osName $osVersion ($osArch)")
            out.println("• $javaVendor (Java $javaVersion)")
            
            if (osName.contains("Mac", ignoreCase = true)) {
                val xcodeVersion = IOSEnvUtils.xcodeVersion ?: "Not installed"
                out.println("• Xcode $xcodeVersion")
            }

            if (verbose) {
                out.println("\nVerbose details:")
                out.println("- Java home: ${System.getProperty("java.home")}")
                out.println("- Java vendor: $javaVendor")
                out.println("- User locale: ${System.getProperty("user.language")}-${System.getProperty("user.country")}")
                out.println("- Maestro CLI path: ${getMaestroCliPath()}")
            }
        }

        private data class InstallInfo(
            val method: String,
            val displayText: String
        )

        private fun detectInstallMethod(): Pair<String, String> {
            val path = getMaestroCliPath().replace('\\', '/').lowercase()
            return when {
                path.contains("/brew/") || path.contains("/homebrew/") -> 
                    InstallInfo("homebrew", "installed via Homebrew")
                    
                path.contains("/.maestro/bin") -> 
                    InstallInfo("script", "installed via install script")
                    
                path.contains("maestro/bin") -> 
                    InstallInfo("windows", "installed via zip archive")
                    
                else -> 
                    InstallInfo("custom", "custom installation")
            }.let { it.method to it.displayText }
        }

        private fun getMaestroCliPath(): String {
            return try {
                val codeSource = DoctorCommand::class.java.protectionDomain.codeSource
                codeSource?.location?.path ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
        }
    }
}
