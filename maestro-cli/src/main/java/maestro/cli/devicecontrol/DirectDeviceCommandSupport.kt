package maestro.cli.devicecontrol

import maestro.cli.CliError
import picocli.CommandLine
import java.io.File

object DirectDeviceCommandSupport {

    fun resolveRequiredValue(
        optionValue: String?,
        argumentValue: String?,
        valueName: String,
        optionName: String,
        commandLine: CommandLine,
    ): String {
        return optionValue ?: argumentValue
            ?: throw CommandLine.ParameterException(
                commandLine,
                "$valueName is required. Pass it as an argument or with $optionName"
            )
    }

    fun ensureParentDirectoryExists(output: File) {
        val parentDir = output.absoluteFile.parentFile
        if (parentDir != null) {
            if (parentDir.exists() && !parentDir.isDirectory) {
                throw CliError("Output parent path is not a directory: ${parentDir.absolutePath}")
            }

            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw CliError("Unable to create output directory: ${parentDir.absolutePath}")
            }
        }
    }
}
