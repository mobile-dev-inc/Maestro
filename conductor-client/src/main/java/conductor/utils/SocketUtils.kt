package conductor.utils

import java.io.IOException
import java.net.Socket

object SocketUtils {

    fun isPortInUse(host: String, port: Int): Boolean {
        return try {
            Socket(host, port).use { true }
        } catch (ignored: IOException) {
            false
        }
    }

}