package maestro.orchestra

/**
 * Canonical relative paths for the artifacts a flow run produces, all relative
 * to the run root (the dir that holds [MANIFEST_JSON]). Single source of truth
 * so the writer, the manifest-builder, and the CLI's copier never drift.
 *
 * Layout under the run root:
 * ```
 * <run-root>/                 ← the "artifacts" zip = everything core makes
 *   manifest.json
 *   commands.json
 *   logs/
 *     maestro.log
 *     device logs, crash/ANR  ← worker/cloud only
 *   takeScreenshot/           ← takeScreenshot command output
 *   startRecording/           ← startRecording command output
 *   screenshots/              ← step screenshots (all steps when flag on; failed step only when off)
 *   screen-hierarchy/         ← per-step view hierarchy JSON
 *   screen-recording.mp4      ← full-run recording (flag-gated)
 * ```
 */
object ArtifactFiles {
    /** At the run root, so its relative paths resolve against the root. */
    const val MANIFEST_JSON = "manifest.json"

    const val COMMANDS_JSON = "commands.json"

    const val LOGS_DIR = "logs"
    const val MAESTRO_LOG = "$LOGS_DIR/maestro.log"

    const val TAKE_SCREENSHOT_DIR = "takeScreenshot"

    const val START_RECORDING_DIR = "startRecording"

    const val SCREENSHOT_EXTENSION = ".png"

    const val STEP_SCREENSHOTS_DIR = "screenshots"

    const val SCREEN_HIERARCHY_DIR = "screen-hierarchy"

    const val SCREEN_RECORDING = "screen-recording.mp4"

    /**
     * Stable identity written as each manifest's `$schema`. The filename carries
     * the schema's major version ([ArtifactManifest.schemaVersion]): a structural
     * change ships as a new `manifest.vN.schema.json`, so this URL keeps resolving
     * for every manifest already in the wild, while additive changes land in-place
     * on `main` (safe — readers tolerate unknown fields). Keep in sync with
     * [MANIFEST_SCHEMA_RESOURCE].
     */
    const val MANIFEST_SCHEMA_URL = "https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.v1.schema.json"

    /** Classpath copy of the schema [MANIFEST_SCHEMA_URL] serves; checked by the schema-coverage test. */
    const val MANIFEST_SCHEMA_RESOURCE = "/maestro/orchestra/manifest.v1.schema.json"
}
