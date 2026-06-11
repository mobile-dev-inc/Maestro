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
     * Stable identity written as each manifest's `$schema`: the hand-written
     * schema served straight from this repo's `main` branch via GitHub raw.
     * Using a fixed branch keeps the URL constant while its content tracks the
     * latest schema — safe here because the model tolerates unknown fields and a
     * test blocks undocumented artifact kinds. The manifest therefore stays
     * self-describing even after it is moved away from its run folder, with no
     * extra hosting infrastructure. Keep this in sync with [MANIFEST_SCHEMA_RESOURCE].
     */
    const val MANIFEST_SCHEMA_URL = "https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.schema.json"

    /**
     * Classpath location of the hand-written schema, and the same file [MANIFEST_SCHEMA_URL]
     * serves from `main`. Kept in the repo as the source of truth and for the
     * schema-coverage test; no longer copied into each run dir.
     */
    const val MANIFEST_SCHEMA_RESOURCE = "/maestro/orchestra/manifest.schema.json"
}
