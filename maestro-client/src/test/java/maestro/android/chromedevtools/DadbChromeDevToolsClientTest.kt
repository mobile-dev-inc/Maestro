package maestro.android.chromedevtools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.AdbStreamOpenException
import dadb.Dadb
import maestro.DeviceConnectionException
import maestro.DeviceUnreachableException
import maestro.TreeNode
import maestro.android.AndroidDeviceConnection
import maestro.android.FakeDadb
import maestro.android.InterruptProofLatch
import maestro.android.NeverRespondingStream
import maestro.android.awaitParked
import maestro.android.socketListing
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.Pipe
import okio.buffer
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // ── MA-4090 follow-up: an interrupt is a cancellation, not a bad webview ──

    @Test
    fun `an interrupt while awaiting a websocket response aborts the capture and re-asserts the flag`() {
        // Ordering matters: the interrupt lands on the FIRST webview's websocket wait. Degrading
        // it like an ordinary bad webview would clear the interrupt flag and go on to capture the
        // second webview, so cancellation (a watchdog, the capture-level timeout) never lands.
        val deadWebSocket = NeverRespondingStream()
        val deadWebSocketOpened = CountDownLatch(1)
        val secondWebViewOpened = AtomicBoolean(false)
        val streams = mapOf(
            "localabstract:webview_devtools_remote_111" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse(webViewListing("/devtools/page/1"))) },
                { deadWebSocketOpened.countDown(); deadWebSocket },
            )),
            "localabstract:webview_devtools_remote_222" to ArrayDeque<() -> AdbStream>(listOf(
                { CannedHttpStream(httpResponse(webViewListing("/devtools/page/2"))) },
                { secondWebViewOpened.set(true); WebSocketServerStream(HEALTHY_NODE_RESPONSE) },
            )),
        )
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111", "webview_devtools_remote_222") },
            onOpen = { destination -> streams.getValue(destination).removeFirst()() },
        )

        val completedNormally = AtomicBoolean(false)
        val thrown = AtomicReference<Throwable>()
        val flagReasserted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        try {
            // Generous step timeout: the capture must still be parked on the websocket future
            // when the interrupt lands, not already degraded by the bound under test elsewhere.
            clientOver(dadb, stepTimeoutMillis = 5_000).use { client ->
                val capture = Thread {
                    try {
                        client.getWebViewTreeNodes()
                        completedNormally.set(true)
                    } catch (t: Throwable) {
                        thrown.set(t)
                        flagReasserted.set(Thread.currentThread().isInterrupted)
                    } finally {
                        done.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                // Interrupt only once the capture thread is parked on the dead webview's
                // websocket future (its only park after the websocket opens; the HTTP listings
                // completed synchronously before it).
                assertWithMessage("the dead webview's websocket was never opened")
                    .that(deadWebSocketOpened.await(10, TimeUnit.SECONDS)).isTrue()
                awaitParked(capture)
                capture.interrupt()

                assertWithMessage("the capture did not finish within 10s of the interrupt")
                    .that(done.await(10, TimeUnit.SECONDS)).isTrue()
            }
        } finally {
            deadWebSocket.release()
        }

        assertWithMessage("the capture completed normally instead of aborting on the interrupt")
            .that(completedNormally.get()).isFalse()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        assertWithMessage("the interrupt flag was not re-asserted").that(flagReasserted.get()).isTrue()
        assertWithMessage("the capture moved on to the next webview after the interrupt")
            .that(secondWebViewOpened.get()).isFalse()
    }

    @Test
    fun `a garbage devtools listing aborts the capture loudly instead of silently degrading`() {
        // A 200 response with garbage JSON is a real devtools integration break, not the transport
        // wedge MA-4090 degrades: it must fail the capture loudly rather than silently drop the
        // socket's webviews, which would surface only as untappable "element not found" flake.
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111") },
            onOpen = { CannedHttpStream(httpResponse("this is not json")) },
        )

        clientOver(dadb).use { client ->
            assertThrows<IllegalStateException> { client.getWebViewInfos() }
        }
    }

    @Test
    fun `a webview described with a malformed debugger url aborts the capture loudly`() {
        // The augmentation step of the same policy: a bad CDP payload from a reachable webview (here
        // an unparseable webSocketDebuggerUrl) is a real break, not the transport wedge MA-4090
        // degrades, so it must propagate rather than silently drop the webview and leave its
        // elements untappable behind only a warn log.
        val badUrlListing =
            """[{"description":"{\"attached\":true,\"empty\":false,\"height\":600,\"screenX\":0,\"screenY\":0,\"visible\":true,\"width\":400}","webSocketDebuggerUrl":"not a valid url"}]"""
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111") },
            onOpen = { CannedHttpStream(httpResponse(badUrlListing)) },
        )

        clientOver(dadb).use { client ->
            assertThrows<IllegalArgumentException> { client.getWebViewTreeNodes() }
        }
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

    // ── MA-4111 follow-up: socket discovery rides the same wedgeable dadb transport ──

    @Test
    fun `webview discovery degrades to an empty capture when the shell call hangs forever`() {
        // The same wedged-transport park as MA-4111, one step earlier in the chain: the
        // `cat /proc/net/unix` discovery call blocks in a raw socket read that ignores
        // Thread.interrupt(), so without its own bound the capture never starts timing out.
        val park = InterruptProofLatch()
        val dadb = FakeDadb(
            onShell = {
                park.awaitUninterruptibly()
                error("shell must not complete")
            },
        )

        val nodes = try {
            clientOver(dadb).use { client ->
                assertTimeoutPreemptively<List<TreeNode>>(Duration.ofSeconds(30)) {
                    client.getWebViewTreeNodes()
                }
            }
        } finally {
            park.release()
        }

        assertThat(nodes).isEmpty()
    }

    @Test
    fun `a failed discovery shell command aborts the capture loudly`() {
        // A non-zero `cat /proc/net/unix` exit is a real device/permission failure, not the transport
        // wedge the discovery bound degrades: it propagates so the capture fails visibly instead of
        // silently proceeding with a native-only hierarchy.
        val dadb = FakeDadb(
            onShell = { AdbShellResponse("", "cat: /proc/net/unix: Permission denied", 1) },
        )

        clientOver(dadb).use { client ->
            assertThrows<IllegalStateException> { client.getWebViewInfos() }
        }
    }

    // ── MA-4090/F5: an adb OPEN rejection is a per-stream failure, not a device death ──

    @Test
    fun `a webview socket that rejects the adb OPEN is skipped and the connection stays alive`() {
        // A stale webview_devtools_remote_* socket whose renderer is gone answers the OPEN with a
        // CLSE, which dadb raises as AdbStreamOpenException. The transport that carried that reply
        // is alive by construction, so the capture must skip just that webview and the connection
        // must not be marked dead.
        val healthyStreams = ArrayDeque<() -> AdbStream>(listOf(
            { CannedHttpStream(httpResponse(webViewListing("/devtools/page/2"))) },
            { WebSocketServerStream(HEALTHY_NODE_RESPONSE) },
        ))
        val dadb = FakeDadb(
            onShell = { socketListing("webview_devtools_remote_111", "webview_devtools_remote_222") },
            onOpen = { destination ->
                if (destination == "localabstract:webview_devtools_remote_111") {
                    throw AdbStreamOpenException(destination, "adbd refused to open stream: $destination")
                }
                healthyStreams.removeFirst()()
            },
        )
        val connection = AndroidDeviceConnection.forTest(dadb = dadb)

        val nodes = DadbChromeDevToolsClient(connection).use { client ->
            assertTimeoutPreemptively<List<TreeNode>>(Duration.ofSeconds(30)) {
                client.getWebViewTreeNodes()
            }
        }

        assertThat(nodes).containsExactly(TreeNode(attributes = mutableMapOf("text" to "Hello WebView")))
        assertWithMessage("an OPEN rejection of one webview socket marked the whole connection dead")
            .that(connection.isShutdown()).isFalse()
    }

    // ── pinning: degradation must never swallow a dead/unauthorized device ──

    @Test
    fun `a device connection failure on the discovery shell call still propagates`() {
        val dadb = FakeDadb(
            onShell = { throw DeviceUnreachableException("shell: cat /proc/net/unix", RuntimeException("adb transport gone")) },
        )

        clientOver(dadb).use { client ->
            assertThrows<DeviceConnectionException> { client.getWebViewInfos() }
        }
    }

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

    // The internal constructor shrinks the per-step bound so degrade paths do not wait out the
    // production 5s deadlines; tests parking a caller ON that bound pass a generous one instead.
    private fun clientOver(dadb: Dadb, stepTimeoutMillis: Long = 500): DadbChromeDevToolsClient =
        DadbChromeDevToolsClient(
            AndroidDeviceConnection.forTest(dadb = dadb),
            stepTimeoutMillis = stepTimeoutMillis,
            httpReadTimeoutMillis = null,
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

    // FakeDadb, NeverRespondingStream, InterruptProofLatch, awaitParked, socketListing: see AdbTestFixtures.kt.

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
