package maestro.orchestra

/**
 * What an artifact is, semantically. Superset across local + cloud; each
 * environment emits the subset it produces. Wire form is the enum name
 * (SCREAMING_SNAKE_CASE) — no Jackson annotations, so this stays a plain
 * model usable from any consumer's mapper.
 */
enum class ArtifactKind {
    SCREENSHOT,         // failure screenshot (core) or a per-step collection (worker)
    COMMAND_METADATA,   // commands.json — view hierarchy inline on the failing command
    MAESTRO_LOG,        // maestro.log
    SCREEN_RECORDING,
    DEVICE_LOG,         // metadata["source"] = simulator | xctest | emulator
    CRASH_REPORT,
    ANR_REPORT,
    USER_FILE,          // customer takeScreenshot / pushed files — a collection
    AI_ANALYSIS,        // reserved; not emitted yet
}

/** Concrete on-disk format of an artifact's bytes. ZIP is a download *view*, not a kind. */
enum class ArtifactFormat { PNG, MP4, RRWEB, JSON, TXT, HTML }

/**
 * One artifact, or one homogeneous collection of artifacts.
 *
 * @param relativePath path under the artifact root (the artifacts dir locally,
 *   `run/{runId}/` in cloud). A directory when [count] is set.
 * @param count null for a single file; set for a collection living under [relativePath].
 * @param format member format when the entry is homogeneous; null for a mixed collection.
 */
data class ArtifactEntry(
    val kind: ArtifactKind,
    val format: ArtifactFormat?,
    val relativePath: String,
    val count: Int? = null,
    val sizeBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * The set of artifacts produced for a single flow run. Owned and emitted by
 * Orchestra; consumed verbatim by the CLI and the cloud worker.
 *
 * @param schemaVersion bumped only on a STRUCTURAL change to this file's shape,
 *   not for additive fields (those rely on the reader tolerating unknown props).
 *   Guards the persisted local `manifest.json` against future readers.
 */
data class ArtifactManifest(
    val schemaVersion: Int = 1,
    val entries: List<ArtifactEntry> = emptyList(),
)
