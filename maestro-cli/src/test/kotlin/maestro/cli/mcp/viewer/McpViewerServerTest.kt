package maestro.cli.mcp.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import uk.org.webcompere.systemstubs.stream.SystemErr
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@ExtendWith(SystemStubsExtension::class)
class McpViewerServerTest {

    @SystemStub
    private val systemErr = SystemErr()

    @Test
    fun `without a requested port it reports the port the OS assigned`() {
        McpViewerServer.start().use { viewer ->
            assertThat(viewer.port).isNotEqualTo(0)
            assertThat(systemErr.text).contains("mcp_viewer_ready http://127.0.0.1:${viewer.port}")
            // The announced port is bound, not merely picked.
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", viewer.port), CONNECT_TIMEOUT_MS) }
        }
    }

    @Test
    fun `a requested port is used as is`() {
        val requested = ServerSocket(0).use { it.localPort }

        McpViewerServer.start(port = requested).use { viewer ->
            assertThat(viewer.port).isEqualTo(requested)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
