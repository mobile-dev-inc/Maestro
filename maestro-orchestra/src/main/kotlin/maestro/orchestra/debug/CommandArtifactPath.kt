package maestro.orchestra.debug

import maestro.MaestroException
import java.nio.file.Path

/**
 * Validates the flow-supplied output path for `takeScreenshot` / `startRecording`, which is written
 * inside that command's own folder in the run's debug output. Confinement only matters when
 * [bundled]: without a bundle the flow writes wherever it asks, as locally.
 */
internal object CommandArtifactPath {

    fun validate(path: String, commandName: String, bundled: Boolean) {
        if (path.isBlank()) {
            throw invalid(path, commandName, "the path is empty")
        }

        val fileName = path.split('/', '\\').last()
        if (fileName.isBlank()) {
            throw invalid(path, commandName, "it names a directory, so there is no file name to write to")
        }
        if (!bundled) return

        if (isAbsolute(path)) {
            throw invalid(path, commandName, "the path must be relative to this run's $commandName output folder")
        }
        if (leavesOwnFolder(path)) {
            throw invalid(path, commandName, "\"..\" would take it outside this run's $commandName output folder")
        }
    }

    private fun leavesOwnFolder(path: String) =
        Path.of(path.replace('\\', '/')).normalize().startsWith("..")

    private fun isAbsolute(path: String) =
        path.startsWith('/') || path.startsWith('\\') || WINDOWS_DRIVE.matchesAt(path, 0)

    private fun invalid(path: String, commandName: String, reason: String) = MaestroException.InvalidCommand(
        "Invalid path \"$path\" for $commandName: $reason. " +
            "If the path comes from a variable, check that the variable is set."
    )

    private val WINDOWS_DRIVE = Regex("""[A-Za-z]:[/\\]""")
}
