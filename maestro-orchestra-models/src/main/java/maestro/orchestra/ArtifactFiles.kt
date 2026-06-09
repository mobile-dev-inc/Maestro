package maestro.orchestra

/**
 * Canonical relative filenames for the artifacts a flow run produces. Single
 * source of truth so the writer and the manifest-builder (and the CLI's flat
 * copier) never drift on a filename. These are relative to the artifact root.
 */
object ArtifactFiles {
    const val COMMANDS_JSON = "commands.json"
    const val MAESTRO_LOG = "maestro.log"
    const val MANIFEST_JSON = "manifest.json"
    const val FAILURE_SCREENSHOT_PREFIX = "screenshot-❌-"
    const val SCREENSHOT_EXTENSION = ".png"
}
