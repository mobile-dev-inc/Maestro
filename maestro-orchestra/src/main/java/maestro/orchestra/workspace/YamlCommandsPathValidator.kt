package maestro.orchestra.workspace

import maestro.orchestra.error.ValidationError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object YamlCommandsPathValidator {

    fun validatePathsExistInWorkspace(input: Set<Path>, flowFile: Path, pathStrings: List<String>) {
        pathStrings.forEach {
            val exists = validateInsideWorkspace(input, it)
            if (!exists) {
                throw ValidationError("The File \"${Paths.get(it).fileName}\" referenced in flow file: $flowFile not found in workspace")
            }
        }
    }

    private fun validateInsideWorkspace(workspace: Set<Path>, pathString: String): Boolean {
        val mediaPath = workspace.firstNotNullOfOrNull { it.resolve(it.fileSystem.getPath(pathString)) }
        return workspace.any { Files.walk(it).anyMatch { path -> path.fileName == mediaPath?.fileName } }
    }
}
