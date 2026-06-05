package maestro.device

import dadb.Dadb

/**
 * Enumeration + resolution for Android devices.
 *
 * Device identity is the verbatim `dadb.toString()` — the adb serial (e.g. `emulator-5554`) when an
 * adb server is backing enumeration, or `host:port` only in the bare port-scan case. Identity is an
 * opaque round-trip token: [resolveDadb] reconnects an id through the SAME `Dadb.list` mechanism that
 * produced it, so a serial that enumeration emits is always resolvable. No id parsing.
 *
 * A raw [Dadb] never escapes this module: [resolveDadb] is `internal` and its result is wrapped in a
 * [maestro.drivers.DadbConnection] by the driver layer before any consumer sees it.
 */
object AndroidDevices {

    /** Lists reachable Android device ids (verbatim `dadb.toString()`) on [host]. */
    fun list(host: String = "localhost"): List<String> =
        Dadb.list(host).map { dadb -> dadb.use { it.toString() } }

    /**
     * Best-effort check that an explicit `host:port` looks like an Android endpoint we can build a
     * connection for. `Dadb.create` is lazy (no socket opened), so this validates construction, not
     * live reachability — a dead endpoint surfaces later as a [maestro.DeviceUnreachableException]
     * when the driver first opens.
     */
    fun isReachable(port: Int, host: String = "localhost"): Boolean =
        try {
            Dadb.create(host, port).use { true }
        } catch (_: Exception) {
            false
        }

    /**
     * Resolves a device [deviceId] to an OPEN [Dadb] by re-running [list] and matching the verbatim
     * id, so reconnection uses the same transport enumeration used (the adb server, for serials).
     * When [deviceId] is null, picks the first connected device. The matched connection is returned
     * open; every other enumerated connection is closed. Throws [IllegalStateException] when nothing
     * matches.
     *
     * [list] is injectable purely so the id round-trip can be tested without an emulator.
     */
    internal fun resolveDadb(
        deviceId: String?,
        host: String = "localhost",
        list: (String) -> List<Dadb> = { Dadb.list(it) },
    ): Dadb {
        val dadbs = list(host)
        val match = if (deviceId != null) dadbs.firstOrNull { it.toString() == deviceId }
        else dadbs.firstOrNull()
        dadbs.forEach { if (it !== match) runCatching { it.close() } }
        return match ?: error(
            if (deviceId != null) "Unable to find device with id $deviceId"
            else "No Android devices found on host $host"
        )
    }
}
