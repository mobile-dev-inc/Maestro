package maestro

import java.io.IOException

/**
 * Structured detail about what was in flight when a device transport died. Optional on the
 * exception below: the Android connection layer populates it with rich diagnostics; the iOS
 * path leaves it null and relies on [DeviceUnreachableException.operation] alone.
 */
data class DeviceDiagnostics(
    /** Which call was in flight when it died. */
    val operation: String,
    /** Original exception class + message. */
    val rootCause: String,
    val serial: String,
    /** Abrupt crash vs slow stall. */
    val msSinceLastByte: Long,
    val connectionAgeMs: Long,
)

/**
 * The single abstraction for "the device connection is in trouble" — every transport/connection
 * failure surfaced by the device layer (AndroidDeviceConnection or the iOS device) is a subtype:
 * [DeviceUnreachableException], `DeviceServerDiedException`, `DeviceAuthException`.
 *
 * The whole point of the connection abstraction is that a consumer reacts to a transport problem by
 * catching THIS one type — never a raw [IOException]. Operation failures (the device answered with a
 * failure: a rejected install, a non-zero shell exit, a gRPC error status) are RuntimeExceptions and
 * deliberately never land here, so a `catch (DeviceConnectionException)` can't swallow them.
 *
 * Still an [IOException] for backward compatibility, but consumers should prefer the typed base.
 */
abstract class DeviceConnectionException(message: String, cause: Throwable?) : IOException(message, cause)

/**
 * Thrown when a driver call fails because the underlying device transport has stopped responding
 * — the iOS XCTest runner's HTTP socket dies, or the Android device's adbd becomes unreachable.
 * Distinct from [MaestroException] — this is an infrastructure failure, not a test failure, and
 * consumers should treat it accordingly (no test-error reporting, no flow-level retry).
 *
 * Once a driver records a transport failure it should fail-fast on subsequent calls instead of
 * issuing fresh requests against the same dead transport.
 *
 * [operation] names the call that was in flight. [diagnostics], when present (Android), carries
 * richer structured detail and drives a more descriptive message.
 */
class DeviceUnreachableException(
    val operation: String,
    cause: Throwable,
    val diagnostics: DeviceDiagnostics? = null,
) : DeviceConnectionException(
    diagnostics?.let {
        "Device ${it.serial} is unreachable during '${it.operation}' " +
            "(${it.msSinceLastByte}ms since last byte, connection age ${it.connectionAgeMs}ms): " +
            it.rootCause
    } ?: "Device became unreachable during $operation",
    cause,
)
