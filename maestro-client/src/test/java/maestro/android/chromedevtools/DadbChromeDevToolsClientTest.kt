package maestro.android.chromedevtools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dadb.UninstallResult
import maestro.DeviceConnectionException
import maestro.DeviceUnreachableException
import maestro.TreeNode
import maestro.android.AndroidDeviceConnection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.Pipe
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    // ── MA-4090: one bad webview devtools socket must not abort the whole capture ──

    @Test
    fun `one unresponsive webview does not abort hierarchy capture`() {
        // The DEAD socket is listed first so its websocket timeout hits before the healthy
        // webview is ever attempted: aborting on it would lose the healthy hierarchy.
        val deadWebSocket = NeverRespondingStream()
        val streams = mapOf(
            "localabstract:webview_devtools_remote_111" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse(webViewListing("/devtools/page/1"))) },
                { deadWebSocket },
            )),
            "localabstract:webview_devtools_remote_222" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse(webViewListing("/devtools/page/2"))) },
                { WebSocketServerStream(HEALTHY_NODE_RESPONSE) },
            )),
        )
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111", "webview_devtools_remote_222") },
            onOpen = { destination -> streams.getValue(destination).removeFirst()() },
        )

        val nodes = try {
            clientOver(dadb).use { client ->
                assertTimeoutPreemptively<List<TreeNode>>(Duration.ofSeconds(30)) {
                    client.getWebViewTreeNodes()
                }
            }
        } finally {
            deadWebSocket.release()
        }

        assertThat(nodes).containsExactly(TreeNode(attributes = mutableMapOf("text" to "Hello WebView")))
    }

    @Test
    fun `a garbage devtools listing on one socket does not abort webview enumeration`() {
        val streams = mapOf(
            "localabstract:webview_devtools_remote_111" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse("this is not json")) },
            )),
            "localabstract:webview_devtools_remote_222" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse(webViewListing("/devtools/page/2"))) },
            )),
        )
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111", "webview_devtools_remote_222") },
            onOpen = { destination -> streams.getValue(destination).removeFirst()() },
        )

        val infos = clientOver(dadb).use { it.getWebViewInfos() }

        assertThat(infos.map { it.socketName }).containsExactly("webview_devtools_remote_222")
    }

    @Test
    fun `the websocket is torn down when the response times out`() {
        val stream = NeverRespondingStream()
        val dadb = FakeDadb(onOpen = { stream })

        try {
            clientOver(dadb).use { client ->
                assertThrows<TimeoutException> {
                    client.makeSingleWebsocketRequest(
                        "http://webview_devtools_remote_111/devtools/page/1".toHttpUrl(),
                        "{}",
                    )
                }
                // 2s grace: well before the 10s socket read timeout that would eventually
                // release the connection anyway, so only an explicit cancel can pass this.
                val closed = stream.closed.await(2, TimeUnit.SECONDS)
                assertWithMessage("websocket connection was not torn down after the response timed out")
                    .that(closed).isTrue()
            }
        } finally {
            stream.release()
        }
    }

    @Test
    fun `a non-200 devtools listing response is closed`() {
        val stream = CannedHttpStream(httpResponse("busy", code = 500))
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111") },
            onOpen = { stream },
        )

        clientOver(dadb).use { client ->
            assertThat(client.getWebViewInfos()).isEmpty()
            val closed = stream.closed.await(2, TimeUnit.SECONDS)
            assertWithMessage("the non-200 listing response was not closed").that(closed).isTrue()
        }
    }

    // ── pinning: degradation must never swallow a dead/unauthorized device ──

    @Test
    fun `a device connection failure while listing webviews still propagates`() {
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111") },
            onOpen = { throw DeviceUnreachableException("open: $it", RuntimeException("adb transport gone")) },
        )

        clientOver(dadb).use { client ->
            assertThrows<DeviceConnectionException> { client.getWebViewInfos() }
        }
    }

    @Test
    fun `a device connection failure on the websocket still propagates`() {
        val streams = ArrayDeque<() -> AdbStream>(listOf(
            { CannedHttpStream(httpResponse(webViewListing("/devtools/page/1"))) },
            { throw DeviceUnreachableException("open: websocket", RuntimeException("adb transport gone")) },
        ))
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111") },
            onOpen = { streams.removeFirst()() },
        )

        clientOver(dadb).use { client ->
            assertThrows<DeviceConnectionException> { client.getWebViewTreeNodes() }
        }
    }

    // ── fixtures ──

    private fun clientOver(dadb: Dadb): DadbChromeDevToolsClient =
        DadbChromeDevToolsClient(AndroidDeviceConnection.forTest(dadb = dadb))

    private fun socketListing(vararg names: String) = AdbShellResponse(
        names.joinToString("") { "0000000000000000: 00000002 00000000 00010000 0001 01 54321 @$it\n" },
        "",
        0,
    )

    private fun httpResponse(body: String, code: Int = 200): String {
        val status = if (code == 200) "200 OK" else "$code Server Error"
        return "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.encodeToByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body
    }

    private fun webViewListing(pagePath: String): String =
        """[{"description":"{\"attached\":true,\"empty\":false,\"height\":600,\"screenX\":0,\"screenY\":0,\"visible\":true,\"width\":400}","webSocketDebuggerUrl":"ws://127.0.0.1$pagePath"}]"""

    private class FakeDadb(
        var onShell: (String) -> AdbShellResponse = { error("shell not stubbed") },
        var onOpen: (String) -> AdbStream = { error("open not stubbed") },
    ) : Dadb {
        override fun open(destination: String): AdbStream = onOpen(destination)
        override fun supportsFeature(feature: String): Boolean = true
        override fun shell(command: String): AdbShellResponse = onShell(command)
        override fun install(file: File, vararg options: String): InstallResult = error("install not stubbed")
        override fun uninstall(packageName: String): UninstallResult = error("uninstall not stubbed")
        override fun pull(dst: File, remotePath: String): SyncResult = error("pull not stubbed")
        override fun pull(sink: Sink, remotePath: String): SyncResult = error("pull not stubbed")
        override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult =
            error("push not stubbed")
        override fun close() {}
        override fun toString() = "fake-serial"
    }

    /** An HTTP exchange whose entire response is canned up front; records `close()`. */
    private class CannedHttpStream(response: String) : AdbStream {
        val closed = CountDownLatch(1)
        override val source = Buffer().writeUtf8(response)
        override val sink = Buffer()
        override fun close() {
            closed.countDown()
        }
    }

    /**
     * Accepts the connection but never produces a byte, mirroring a crashed or suspended
     * renderer behind a stale `webview_devtools_remote_*` socket. The park is interruptible
     * (like dadb's `Condition.await`) and `close()` is recorded but does not release it.
     */
    private class NeverRespondingStream : AdbStream {
        private val latch = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                latch.await()
                return -1L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override val sink = Buffer()

        override fun close() {
            closed.countDown()
        }

        fun release() = latch.countDown()
    }

    /**
     * A minimal websocket server over a fake adb stream: answers the HTTP upgrade handshake,
     * then pushes [responsePayload] as a single unmasked text frame and hangs up. The client's
     * own frames are absorbed unparsed; only the handshake headers are read (for the key).
     */
    private class WebSocketServerStream(responsePayload: String) : AdbStream {
        private val request = Pipe(1024L * 1024)
        private val response = Pipe(1024L * 1024)

        override val source = response.source.buffer()
        override val sink = request.sink.buffer()

        override fun close() = Unit

        init {
            Thread {
                val reader = request.source.buffer()
                var key = ""
                while (true) {
                    val line = reader.readUtf8LineStrict()
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                        key = line.substringAfter(':').trim()
                    }
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                val payload = responsePayload.encodeToByteArray()
                check(payload.size < 126) { "fixture only encodes single-byte frame lengths" }
                response.sink.buffer().use { out ->
                    out.writeUtf8("HTTP/1.1 101 Switching Protocols\r\n")
                    out.writeUtf8("Upgrade: websocket\r\n")
                    out.writeUtf8("Connection: Upgrade\r\n")
                    out.writeUtf8("Sec-WebSocket-Accept: $accept\r\n")
                    out.writeUtf8("\r\n")
                    out.writeByte(0x81) // FIN + text frame
                    out.writeByte(payload.size)
                    out.write(payload)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val HEALTHY_NODE_RESPONSE =
            """{"id":1,"result":{"result":{"type":"object","value":{"attributes":{"text":"Hello WebView"},"children":[]}}}}"""
    }
}
