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
 * Versioning lives only in the `$schema` URL's `vN` path ([SCHEMA_URL]); there
 * is no separate `schemaVersion` field.
 */
data class ArtifactManifest(
    val entries: List<ArtifactEntry> = emptyList(),
) {
    companion object {
        /**
         * `$schema` written into every manifest: [SCHEMA_RESOURCE] published to a
         * public GCS object by publish-schemas.yaml. Breaking change → new `vN` path;
         * additive changes overwrite v1 in place (readers tolerate unknown fields).
         */
        const val SCHEMA_URL = "https://storage.googleapis.com/maestro-schemas/artifact-manifest/v1.schema.json"

        /** Classpath copy of the schema CI publishes to [SCHEMA_URL]; checked by the schema-coverage test. */
        const val SCHEMA_RESOURCE = "/maestro/orchestra/artifact-manifest/v1.schema.json"
    }
}
