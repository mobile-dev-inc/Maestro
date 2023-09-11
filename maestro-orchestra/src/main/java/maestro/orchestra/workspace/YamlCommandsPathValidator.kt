package maestro.orchestra.workspace

import maestro.orchestra.error.ValidationError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object YamlCommandsPathValidator {

    fun validatePathsExistInWorkspace(input: Path, flowFile: Path, pathStrings: List<String>) {
        pathStrings.forEach {
            val exists = validateInsideWorkspace(input, it)
            if (!exists) {
                throw ValidationError("The File \"${Paths.get(it).fileName}\" referenced in flow file: $flowFile not found in workspace")
            }
        }
    }

    private fun validateInsideWorkspace(workspace: Path, pathString: String): Boolean {
        val mediaPath = workspace.resolve(workspace.fileSystem.getPath(pathString))
        val exists = Files.walk(workspace).anyMatch { path -> path.fileName == mediaPath.fileName }
        if (!exists) {
            return false
        }
        return true
    }
}