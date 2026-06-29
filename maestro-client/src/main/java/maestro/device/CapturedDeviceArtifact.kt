package maestro.device

import java.io.File

/**
 * A device artifact captured by a [maestro.Driver], already written to disk.
 * Client-local so the driver layer needn't depend on the manifest model;
 * maestro-orchestra maps [type] to its ArtifactKind.
 */
data class CapturedDeviceArtifact(
    val type: Type,
    val file: File,
    val source: String? = null,          // "simulator" | "xctest" | "emulator"
    val friendlyMessage: String? = null, // crash/ANR human summary
) {
    enum class Type { DEVICE_LOG, CRASH_REPORT, ANR_REPORT }
}
