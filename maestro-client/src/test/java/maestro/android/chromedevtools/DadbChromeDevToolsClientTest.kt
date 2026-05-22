package maestro.android.chromedevtools

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class DadbChromeDevToolsClientTest {

    // OkHttp builds `InetSocketAddress(InetAddress, port)` from the Dns result;
    // `AdbSocket.connect()` then reads `endpoint.hostString` to build
    // `dadb.open("localabstract:<host>")`, so the webview socket name MUST
    // survive the round-trip through DummyDns.
    @Test
    fun `DummyDns preserves the original hostname through OkHttp's InetSocketAddress`() {
        val hostname = "webview_devtools_remote_25535"

        val resolved = DummyDns().lookup(hostname).single()
        val socketAddr = InetSocketAddress(resolved, 9222)

        assertThat(socketAddr.hostString).isEqualTo(hostname)
    }
}
