package maestro.device

import java.io.File

/**
 * A device-side artifact captured by a [maestro.Driver], already written to disk.
 * Client-local on purpose: the driver layer must NOT reference the manifest model
 * (maestro-orchestra-models depends on maestro-client, so the reverse is circular).
 * maestro-orchestra maps [type] to its ArtifactKind when building the manifest.
 */
data class CapturedDeviceArtifact(
    val type: Type,
    val file: File,
    val source: String? = null,          // "simulator" | "xctest" | "emulator"
    val friendlyMessage: String? = null, // crash/ANR human summary
) {
    enum class Type { DEVICE_LOG, CRASH_REPORT, ANR_REPORT }
}
