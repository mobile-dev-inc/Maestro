package maestro.cli.util

import java.net.ServerSocket

fun getFreePort(): Int {
    (9999..11000).forEach { port ->
        try {
            ServerSocket(port).use { return it.localPort }
        } catch (ignore: Exception) {}
    }
    ServerSocket(0).use { return it.localPort }
}