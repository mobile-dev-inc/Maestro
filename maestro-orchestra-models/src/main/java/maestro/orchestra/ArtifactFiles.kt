package maestro.orchestra

/**
 * Canonical relative paths for the artifacts a flow run produces, all relative
 * to the run root (the dir that holds [MANIFEST_JSON]). Single source of truth
 * so the writer, the manifest-builder, and the CLI's copier never drift.
 *
 * Layout under the run root:
 * ```
 * <run-root>/
 *   manifest.json
 *   artifacts/                ← everything core makes, zipped as one unit
 *     commands.json
 *     logs/maestro.log
 *     takeScreenshot/         ← takeScreenshot command output
 *     startRecording/         ← startRecording command output
 *     screenshot-❌-*.png      ← failure screenshot
 *   screenshots/              ← per-step screenshots (flag-gated), its own zip
 *   screen-recording.mp4      ← full-run recording (flag-gated)
 * ```
 */
object ArtifactFiles {
    /** Self-describing manifest, at the run root so its relative paths resolve against the root. */
    const val MANIFEST_JSON = "manifest.json"

    /** The folder holding everything core writes; zipped/served as the "artifacts" bundle. */
    const val ARTIFACTS_DIR = "artifacts"

    const val COMMANDS_JSON = "$ARTIFACTS_DIR/commands.json"

    /** Holds maestro.log plus (worker/cloud) device logs and crash/ANR reports. */
    const val LOGS_DIR = "$ARTIFACTS_DIR/logs"
    const val MAESTRO_LOG = "$LOGS_DIR/maestro.log"

    /** takeScreenshot command output; folder name matches the command. */
    const val TAKE_SCREENSHOT_DIR = "$ARTIFACTS_DIR/takeScreenshot"

    /** startRecording command output; folder name matches the command. */
    const val START_RECORDING_DIR = "$ARTIFACTS_DIR/startRecording"

    /** Failure screenshot filename prefix; the file lives at the root of [ARTIFACTS_DIR]. */
    const val FAILURE_SCREENSHOT_PREFIX = "screenshot-❌-"
    const val SCREENSHOT_EXTENSION = ".png"

    /** Per-step screenshots, a run-root sibling of [ARTIFACTS_DIR] so it zips on its own. */
    const val STEP_SCREENSHOTS_DIR = "screenshots"

    /** Full-run recording, a single file at the run root. */
    const val SCREEN_RECORDING = "screen-recording.mp4"

    /**
     * The hand-written JSON Schema describing [ArtifactManifest], bundled next to
     * [MANIFEST_JSON] in each run dir so an agent reading the manifest can resolve
     * its own `$schema` offline.
     */
    const val MANIFEST_SCHEMA_JSON = "manifest.schema.json"

    /** Classpath location of the bundled schema, copied to [MANIFEST_SCHEMA_JSON] at write time. */
    const val MANIFEST_SCHEMA_RESOURCE = "/maestro/orchestra/manifest.schema.json"
}
