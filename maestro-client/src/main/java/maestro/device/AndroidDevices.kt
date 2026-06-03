package maestro.device

import dadb.Dadb

/**
 * A flat, transport-agnostic description of a reachable Android device.
 *
 * [id] equals the underlying `dadb.toString()` ("host:port") and round-trips through
 * [host]/[port] via `Dadb.create(host, port)`. This type deliberately carries NO [dadb.Dadb]
 * (or any transport handle) so consumers cannot name the transport through it.
 */
data class AndroidDeviceDescriptor(
    val id: String,    // == dadb.toString(), i.e. "host:port"
    val host: String,
    val port: Int,
)

/**
 * Enumeration + reachability utility for Android devices over the single TCP path.
 *
 * Yields ONLY [AndroidDeviceDescriptor]s — never a [dadb.Dadb]. This is the device-layer chokepoint
 * that keeps every [dadb.Dadb] reference inside maestro-client.
 */
object AndroidDevices {

    /**
     * Lists reachable Android devices on [host]. Each probe connection opened to read the device's
     * endpoint is closed immediately (reading `toString()` does not require the socket to stay open),
     * so enumeration does not leak sockets.
     */
    fun list(host: String = "localhost"): List<AndroidDeviceDescriptor> =
        Dadb.list(host).mapNotNull { dadb ->
            dadb.use { parseDescriptor(it.toString()) }
        }

    /**
     * Best-effort check that `host:port` looks like an Android endpoint we can build a connection for.
     * `Dadb.create` is lazy (it does not open a socket), so this validates construction, not live
     * reachability — a truly dead endpoint surfaces later as a [maestro.DeviceUnreachableException]
     * when the driver first opens. This preserves the prior `isAndroid` probe semantics while keeping
     * the [dadb.Dadb] reference inside the device layer so callers never name the transport.
     */
    fun isReachable(port: Int, host: String = "localhost"): Boolean =
        try {
            Dadb.create(host, port).use { true }
        } catch (_: Exception) {
            false
        }

    /**
     * Parses a `dadb.toString()` value ("host:port") into a flat descriptor. Returns null for a
     * value without a colon (no parseable endpoint).
     */
    internal fun parseDescriptor(id: String): AndroidDeviceDescriptor? {
        if (!id.contains(':')) return null
        val port = id.substringAfterLast(':').toIntOrNull() ?: return null
        val host = id.substringBeforeLast(':')
        if (host.isEmpty()) return null
        return AndroidDeviceDescriptor(id = id, host = host, port = port)
    }
}
