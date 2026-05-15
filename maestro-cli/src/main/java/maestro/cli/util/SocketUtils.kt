package maestro.cli.util

import java.net.InetSocketAddress
import java.net.ServerSocket

fun isPortAvailable(port: Int): Boolean {
    return try {
        ServerSocket(port).use { true }
    } catch (e: Exception) {
        false
    }
}

/**
 * Picks a free TCP port, optionally probing on a specific [host]. The host-bound probe matters when
 * the caller will bind to a specific interface (e.g. `127.0.0.1`): a default `ServerSocket(0)` probe
 * uses the IPv6 wildcard on macOS, and a port reported free there can still be taken on IPv4.
 *
 * Prefers the 9999..11000 range so users see stable URLs across restarts when possible, and falls
 * back to an OS-picked ephemeral port if every port in the range is taken.
 */
fun getFreePort(host: String? = null): Int {
    (9999..11000).forEach { probe(host, it)?.let { port -> return port } }
    return probe(host, 0) ?: error("Could not find a free port")
}

private fun probe(host: String?, port: Int): Int? = try {
    if (host == null) ServerSocket(port).use { it.localPort }
    else ServerSocket().use { socket ->
        socket.bind(InetSocketAddress(host, port))
        socket.localPort
    }
} catch (_: Exception) {
    null
}
