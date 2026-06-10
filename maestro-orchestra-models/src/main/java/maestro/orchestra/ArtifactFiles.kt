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

    /**
     * The hand-written JSON Schema describing [ArtifactManifest], bundled next to
     * [MANIFEST_JSON] in each run dir so an agent reading the manifest can resolve
     * its own `$schema` offline.
     */
    const val MANIFEST_SCHEMA_JSON = "manifest.schema.json"

    /** Classpath location of the bundled schema, copied to [MANIFEST_SCHEMA_JSON] at write time. */
    const val MANIFEST_SCHEMA_RESOURCE = "/maestro/orchestra/manifest.schema.json"
}
