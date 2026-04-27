package maestro

/**
 * Thrown when a driver call fails because the underlying device transport (e.g. the iOS XCTest
 * runner's HTTP socket) has stopped responding. Distinct from [MaestroException] — this is an
 * infrastructure failure, not a test failure, and consumers should treat it accordingly
 * (no test-error reporting, no flow-level retry).
 *
 * Once a driver records a transport failure it should fail-fast on subsequent calls instead of
 * issuing fresh requests against the same dead transport.
 */
class DeviceUnreachableException(
    val callName: String,
    cause: Throwable,
) : RuntimeException("Device became unreachable during $callName", cause)
