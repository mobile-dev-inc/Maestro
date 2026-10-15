package maestro.web.cdp

import CdpClient
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress

internal class CdpClientTest {

    @Test
    internal fun `requests fail after close and closing twice is safe`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/json") { exchange ->
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = CdpClient(host = server.address.address.hostAddress, port = server.address.port)
            assertThat(runBlocking { client.listTargets() }).isEmpty()

            client.close()
            client.close()

            // The server is still up, so a failure here comes from the closed client.
            assertThrows<CancellationException> { runBlocking { client.listTargets() } }
        } finally {
            server.stop(0)
        }
    }
}
