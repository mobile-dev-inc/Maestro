package maestro.cli.util

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

fun isPortAvailable(port: Int): Boolean {
    return try {
        ServerSocket(port).use { true }
    } catch (e: Exception) {
        false
    }
}

/**
 * Returns true if something is listening on the given local port.
 * Uses a short connect timeout to avoid hanging on unresponsive ports.
 */
fun isPortListening(port: Int, timeoutMs: Int = 500): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("localhost", port), timeoutMs)
            true
        }
    } catch (e: Exception) {
        false
    }
}

fun getFreePort(): Int {
    (9999..11000).forEach { port ->
        try {
            ServerSocket(port).use { return it.localPort }
        } catch (ignore: Exception) {}
    }
    ServerSocket(0).use { return it.localPort }
}