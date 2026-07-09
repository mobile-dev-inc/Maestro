package maestro.orchestra.debug

/**
 * The producer's private layout of the run-root bundle: the relative paths core
 * writes, all resolved against the run root (the dir holding [MANIFEST_JSON]).
 * Module-internal on purpose — only the bundle's writers (the collector, the
 * generator, Orchestra's command output) need these. Consumers read each
 * artifact's location from the manifest's `relativePath` at runtime, so the
 * layout is no inter-module contract and stays here, not in -models.
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
 *   screenshots/              ← step screenshots (all steps + final.png when flag on; failed step only when off)
 *   screen-hierarchy/         ← per-step view hierarchy JSON
 *   screen-recording.mp4      ← full-run recording (flag-gated)
 *   ai-analysis/              ← screenshots an AI command analyzed (with defects)
 * ```
 */
internal object BundleLayout {
    const val MANIFEST_JSON = "manifest.json"

    const val COMMANDS_JSON = "commands.json"

    const val LOGS_DIR = "logs"
    const val MAESTRO_LOG = "$LOGS_DIR/maestro.log"

    const val TAKE_SCREENSHOT_DIR = "takeScreenshot"

    const val START_RECORDING_DIR = "startRecording"

    const val SCREENSHOT_EXTENSION = ".png"

    const val STEP_SCREENSHOTS_DIR = "screenshots"

    /** Flow-level (no owning step) shot of the screen the run ended on, after any onFlowComplete teardown. */
    const val FINAL_SCREENSHOT = "$STEP_SCREENSHOTS_DIR/final$SCREENSHOT_EXTENSION"

    const val SCREEN_HIERARCHY_DIR = "screen-hierarchy"

    const val SCREEN_RECORDING = "screen-recording.mp4"

    const val AI_ANALYSIS_DIR = "ai-analysis"
}
