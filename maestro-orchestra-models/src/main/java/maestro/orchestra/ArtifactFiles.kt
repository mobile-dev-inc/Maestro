package maestro.orchestra

/**
 * Canonical relative paths for the artifacts a flow run produces, all relative
 * to the run root (the dir that holds [MANIFEST_JSON]). Single source of truth
 * so the writer, the manifest-builder, and the CLI's copier never drift.
 *
 * The run root itself IS the zippable bundle — everything core makes sits
 * directly under it, with no intermediate `artifacts/` folder.
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
 *   screenshot-❌-*.png        ← failure screenshot
 *   screenshots/              ← per-step screenshots (flag-gated)
 *   screen-recording.mp4      ← full-run recording (flag-gated)
 * ```
 */
object ArtifactFiles {
    /** Self-describing manifest, at the run root so its relative paths resolve against the root. */
    const val MANIFEST_JSON = "manifest.json"

    const val COMMANDS_JSON = "commands.json"

    /** Holds maestro.log plus (worker/cloud) device logs and crash/ANR reports. */
    const val LOGS_DIR = "logs"
    const val MAESTRO_LOG = "$LOGS_DIR/maestro.log"

    /** takeScreenshot command output; folder name matches the command. */
    const val TAKE_SCREENSHOT_DIR = "takeScreenshot"

    /** startRecording command output; folder name matches the command. */
    const val START_RECORDING_DIR = "startRecording"

    /** Failure screenshot filename prefix; the file lives at the run root. */
    const val FAILURE_SCREENSHOT_PREFIX = "screenshot-❌-"
    const val SCREENSHOT_EXTENSION = ".png"

    /** Per-step screenshots, a run-root subfolder. */
    const val STEP_SCREENSHOTS_DIR = "screenshots"

    /** Full-run recording, a single file at the run root. */
    const val SCREEN_RECORDING = "screen-recording.mp4"

    /**
     * Stable identity written as each manifest's `$schema`: the hand-written
     * schema served straight from this repo's `main` branch via GitHub raw.
     * The filename carries the schema's major version ([ArtifactManifest.schemaVersion]),
     * so a future structural change ships as a new `manifest.vN.schema.json` beside
     * this one and the v1 URL keeps resolving for every manifest already in the wild.
     * Within a version, a fixed branch keeps the URL constant while its content tracks
     * additive changes — safe because the model tolerates unknown fields and a test
     * blocks undocumented artifact kinds. The manifest therefore stays self-describing
     * even after it is moved away from its run folder, with no extra hosting
     * infrastructure. Keep this in sync with [MANIFEST_SCHEMA_RESOURCE].
     */
    const val MANIFEST_SCHEMA_URL = "https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.v1.schema.json"

    /**
     * Classpath location of the hand-written schema, and the same file [MANIFEST_SCHEMA_URL]
     * serves from `main`. Kept in the repo as the source of truth and for the
     * schema-coverage test; no longer copied into each run dir.
     */
    const val MANIFEST_SCHEMA_RESOURCE = "/maestro/orchestra/manifest.v1.schema.json"
}
