package maestro.orchestra

/** Superset across local + cloud; wire form is the enum name (SCREAMING_SNAKE_CASE). */
enum class ArtifactKind {
    SCREENSHOT,             // per-step screenshots (all steps when captureFullArtifacts, else failed step only)
    TAKE_SCREENSHOT,        // takeScreenshot command output
    SCREEN_RECORDING,       // full-run recording, flag-gated
    START_SCREEN_RECORDING, // startRecording command output
    SCREEN_HIERARCHY,
    COMMAND_METADATA,       // commands.json
    MAESTRO_LOG,
    DEVICE_LOG,             // metadata["source"] = simulator | xctest | emulator
    CRASH_REPORT,
    ANR_REPORT,
    USER_FILE,              // other files the flow produced — a collection
    AI_ANALYSIS,            // reserved; not emitted yet
}

/** Concrete on-disk format of an artifact's bytes. ZIP is a download *view*, not a kind. */
enum class ArtifactFormat { PNG, MP4, RRWEB, JSON, TXT, HTML }

/**
 * One artifact, or one homogeneous collection of artifacts.
 *
 * @param relativePath path under the run root (the dir holding manifest.json);
 *   a directory when [count] is set.
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
 * The set of artifacts produced for a single flow run. Deliberately free of
 * Jackson annotations — a plain model usable from any consumer's mapper.
 *
 * @param schemaVersion bumped only on a STRUCTURAL change to this file's shape,
 *   not for additive fields (those rely on the reader tolerating unknown props).
 */
data class ArtifactManifest(
    val schemaVersion: Int = 1,
    val entries: List<ArtifactEntry> = emptyList(),
) {
    companion object {
        /**
         * Stable identity written as each manifest's `$schema`. The filename carries
         * the schema's major version ([schemaVersion]): a structural change ships as a
         * new `manifest.vN.schema.json`, so this URL keeps resolving for every manifest
         * already in the wild, while additive changes land in-place on `main` (safe —
         * readers tolerate unknown fields). Keep in sync with [SCHEMA_RESOURCE].
         */
        const val SCHEMA_URL = "https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.v1.schema.json"

        /** Classpath copy of the schema [SCHEMA_URL] serves; checked by the schema-coverage test. */
        const val SCHEMA_RESOURCE = "/maestro/orchestra/manifest.v1.schema.json"
    }
}
