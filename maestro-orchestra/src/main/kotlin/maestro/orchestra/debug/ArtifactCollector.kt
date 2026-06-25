package maestro.orchestra.debug

import maestro.orchestra.ArtifactEntry
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import java.io.File
import java.nio.file.Path

/**
 * Single owner of the run-root bundle. Allocates every path core writes and
 * records every artifact as it is produced; the manifest is its records and the
 * per-command list is the same records grouped by owning command. Nothing
 * reaches the bundle unrecorded, and there is no end-of-flow disk scan.
 *
 * Layout knowledge — which kinds are folder collections — lives here, the one
 * place the bundle shape is encoded, resolving paths against [BundleLayout].
 *
 * Not thread-safe: assumes Orchestra's single-threaded, synchronous per-flow
 * dispatch (the same invariant the listener relies on).
 */
internal class ArtifactCollector(private val runRoot: Path) {

    /** A kind the manifest reports as one folder entry with a member count. */
    private data class Collection(val dir: String, val format: ArtifactFormat)

    private val collectionKinds: Map<ArtifactKind, Collection> = mapOf(
        ArtifactKind.TAKE_SCREENSHOT to Collection(BundleLayout.TAKE_SCREENSHOT_DIR, ArtifactFormat.PNG),
        ArtifactKind.START_SCREEN_RECORDING to Collection(BundleLayout.START_RECORDING_DIR, ArtifactFormat.MP4),
        ArtifactKind.SCREENSHOT to Collection(BundleLayout.STEP_SCREENSHOTS_DIR, ArtifactFormat.PNG),
        ArtifactKind.SCREEN_HIERARCHY to Collection(BundleLayout.SCREEN_HIERARCHY_DIR, ArtifactFormat.JSON),
    )

    private data class Record(
        val kind: ArtifactKind,
        val format: ArtifactFormat?,
        val relativePath: String,
        val metadata: Map<String, String>,
        /** Executing command's sequence number; null for flow-level artifacts. */
        val sequenceNumber: Int? = null,
    )

    private val records = mutableListOf<Record>()

    /**
     * Reserve [relativePath] for a file core is about to write — creating parent
     * dirs and recording it — and return the file to write into. A record whose
     * file never lands (capture failed or was deduped) is dropped at read time,
     * preserving best-effort capture without extra bookkeeping at the call site.
     */
    fun allocate(
        kind: ArtifactKind,
        format: ArtifactFormat?,
        relativePath: String,
        metadata: Map<String, String> = emptyMap(),
        sequenceNumber: Int? = null,
    ): File {
        val file = runRoot.resolve(relativePath).toFile()
        file.parentFile?.mkdirs()
        records += Record(kind, format, relativePath, metadata, sequenceNumber)
        return file
    }

    /** Allocate [fileName] inside the folder this collector owns for [kind]; callers pass only the leaf name. */
    fun allocateInCollection(kind: ArtifactKind, fileName: String, sequenceNumber: Int? = null): File {
        val collection = collectionKinds.getValue(kind)
        return allocate(kind, collection.format, "${collection.dir}/$fileName", sequenceNumber = sequenceNumber)
    }

    /** Record a file written outside the generator's own path (device logs, crash/ANR) that already lives under the run root. */
    fun adopt(
        kind: ArtifactKind,
        relativePath: String,
        format: ArtifactFormat?,
        metadata: Map<String, String> = emptyMap(),
    ) {
        records += Record(kind, format, relativePath, metadata)
    }

    /**
     * Files from the execution with [sequenceNumber], on disk, deduped by path —
     * so a retry/repeat attempt gets only its own. What commands.json reads.
     */
    fun artifactsForStep(sequenceNumber: Int): List<CommandArtifact> =
        records.filter { it.sequenceNumber == sequenceNumber && it.fileExists() }
            .distinctBy { it.relativePath }
            .map { CommandArtifact(it.kind, it.relativePath) }

    /** Collection kinds folded to one folder entry with a member count; everything else 1:1. */
    fun manifest(): ArtifactManifest {
        val entries = buildList {
            // Dedup by path: a name-stable file overwritten across iterations (e.g.
            // takeScreenshot) is one artifact. Per-step kinds key path on sequence, so
            // each iteration is already a distinct path.
            records.filter { it.fileExists() }
                .distinctBy { it.relativePath }
                .groupBy { it.kind }
                .forEach { (kind, kindRecords) ->
                    val collection = collectionKinds[kind]
                    if (collection != null) {
                        add(
                            ArtifactEntry(
                                kind = kind,
                                format = collection.format,
                                relativePath = collection.dir,
                                count = kindRecords.size,
                            )
                        )
                    } else {
                        kindRecords.forEach { record ->
                            add(
                                ArtifactEntry(
                                    kind = record.kind,
                                    format = record.format,
                                    relativePath = record.relativePath,
                                    sizeBytes = record.file().length(),
                                    metadata = record.metadata,
                                )
                            )
                        }
                    }
                }
        }
        return ArtifactManifest(entries = entries)
    }

    private fun Record.file(): File = runRoot.resolve(relativePath).toFile()

    private fun Record.fileExists(): Boolean = file().exists()
}
