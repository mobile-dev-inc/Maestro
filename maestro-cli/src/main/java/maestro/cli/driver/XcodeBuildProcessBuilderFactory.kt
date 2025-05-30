package maestro.cli.driver

import java.io.File

class XcodeBuildProcessBuilderFactory {

    fun createProcess(commands: List<String>, workingDirectory: File, outputFile: File): Process {
        return ProcessBuilder(commands).directory(workingDirectory).redirectOutput(outputFile)
            .redirectError(outputFile)
            .start()
    }
}