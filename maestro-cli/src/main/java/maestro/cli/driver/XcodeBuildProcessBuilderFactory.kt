package maestro.cli.driver

import java.io.File

class XcodeBuildProcessBuilderFactory {

    fun createProcess(commands: List<String>, workingDirectory: File): ProcessBuilder {
        return ProcessBuilder(commands).directory(workingDirectory)
    }
}