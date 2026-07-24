package maestro.orchestra.debug

import maestro.MaestroException

/**
 * Validates the flow-supplied output path for `takeScreenshot` / `startRecording`. Escaping
 * only matters when [bundled]: without a bundle the flow writes wherever it asks, as locally.
 */
internal object CommandArtifactPath {

    fun validate(path: String, commandName: String, bundled: Boolean) {
        if (path.isBlank()) {
            throw invalid(path, commandName, "the path is empty")
        }

        val segments = path.split('/', '\\')
        if (segments.last().isBlank()) {
            throw invalid(path, commandName, "it names a directory, so there is no file name to write to")
        }
        if (!bundled) return

        if (isAbsolute(path)) {
            throw invalid(path, commandName, "an absolute path would fall outside this run's output bundle")
        }
        if (segments.any { it == ".." }) {
            throw invalid(path, commandName, "\"..\" would take it outside this run's output bundle")
        }
    }

    private fun isAbsolute(path: String) =
        path.startsWith('/') || path.startsWith('\\') || WINDOWS_DRIVE.containsMatchIn(path)

    private fun invalid(path: String, commandName: String, reason: String) = MaestroException.InvalidCommand(
        "Invalid path \"$path\" for $commandName: $reason. " +
            "If the path comes from a variable, check that the variable is set."
    )

    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[/\\]""")
}
