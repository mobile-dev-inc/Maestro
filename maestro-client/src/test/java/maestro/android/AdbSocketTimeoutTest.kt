package maestro.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbStream
import maestro.android.chromedevtools.DadbChromeDevToolsClient
import maestro.android.chromedevtools.WebViewInfo
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A webview devtools socket that accepts the connection but never responds must not hang the flow.
 * dadb parks stream readers two ways, neither bounded by `soTimeout` or `AdbStream.close()` alone:
 * an interruptible `Condition.await`, or a raw `java.net.Socket` read that `Thread.interrupt()` does
 * not release on JDK 17. So [AdbSocketFactory]'s fake socket must bound the caller's wait itself for
 * both park modes.
 */
class AdbSocketTimeoutTest {

    // NeverRespondingStream, InterruptProofLatch, FakeDadb, awaitParked, socketListing: see AdbTestFixtures.kt.

    // The bounded worker pool under test. One per test instance (JUnit makes a fresh instance per
    // method), so a test that parks workers can never bleed into the next; shut down after each.
    private val pool: ExecutorService = newAdbIoExecutor()

    @AfterEach
    fun shutDownPool() = pool.shutdownNow().let { }

    private class InterruptProofNeverRespondingStream : AdbStream {
        private val park = InterruptProofLatch()

        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                park.awaitUninterruptibly()
                return -1L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override val sink = Buffer()

        override fun close() = Unit

        fun release() = park.release()
    }

    /** Parks every write through interrupts until [park] is released. */
    private fun parkedWriteStream(park: InterruptProofLatch) = object : AdbStream {
        override val source = Buffer()
        override val sink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) = park.awaitUninterruptibly()
            override fun flush() = Unit
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun close() = Unit
    }

    /** Fails every read with [failure]. */
    private fun failingReadStream(failure: Throwable) = object : AdbStream {
        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override val sink = Buffer()
        override fun close() = Unit
    }

    /** A read that signals [entered] then parks on [park] through interrupts, holding a pool worker. */
    private fun parkedReadStream(entered: CountDownLatch, park: InterruptProofLatch) = object : AdbStream {
        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                park.awaitUninterruptibly()
                return -1L
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override val sink = Buffer()
        override fun close() = Unit
    }

    /** Fills [target] with [ADB_IO_POOL_MAX] reads parked on [park]; returns the reader threads. */
    private fun saturate(target: ExecutorService, entered: CountDownLatch, park: InterruptProofLatch): List<Thread> =
        (1..ADB_IO_POOL_MAX).map { n ->
            val socket = AdbSocketFactory.bounded(target) { _, _ -> parkedReadStream(entered, park) }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_$n", 9222))
            val input = socket.getInputStream()
            Thread { runCatching { input.read() } }.apply {
                isDaemon = true
                start()
            }
        }

    /**
     * Blocks a daemon thread in [input]`.read()` and waits until it parks; records how the read
     * ends so tests can assert on the release path (close, interrupt).
     */
    private class ParkedReader(input: InputStream) {
        val thrown = AtomicReference<Throwable>()
        val interruptFlagAfterCatch = AtomicBoolean(false)
        private val done = CountDownLatch(1)
        val thread = Thread {
            try {
                input.read()
            } catch (t: Throwable) {
                thrown.set(t)
                interruptFlagAfterCatch.set(Thread.currentThread().isInterrupted)
            } finally {
                done.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        init {
            awaitParked(thread)
        }

        fun releasedWithin5s(): Boolean = done.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `getWebViewInfos degrades to an empty list when a devtools socket accepts but never responds`() {
        val stream = NeverRespondingStream()
        val openedDestinations = mutableListOf<String>()
        val dadb = FakeDadb(
            onShell = { command ->
                assertThat(command).isEqualTo("cat /proc/net/unix")
                socketListing("webview_devtools_remote_12345")
            },
            onOpen = { destination ->
                openedDestinations += destination
                stream
            },
        )
        val connection = AndroidDeviceConnection.forTest(dadb = dadb)

        val infos = try {
            assertTimeoutPreemptively<List<WebViewInfo>>(Duration.ofSeconds(30)) {
                // The listing GET parks on the never-responding stream; the shrunken okhttp read
                // timeout (production default is 10s) bounds it through the socket's soTimeout.
                DadbChromeDevToolsClient(connection, stepTimeoutMillis = 500, httpReadTimeoutMillis = 500)
                    .use { it.getWebViewInfos() }
            }
        } finally {
            stream.release()
        }

        assertThat(openedDestinations).contains("localabstract:webview_devtools_remote_12345")
        assertThat(infos).isEmpty()
    }

    // Each park mode (interruptible Condition.await, interrupt-proof raw-socket read) is exercised
    // against the two bounds that must hold for both: soTimeout, and release-on-close.
    private fun parkModes(): List<Pair<AdbStream, () -> Unit>> = listOf(
        NeverRespondingStream().let { it to it::release },
        InterruptProofNeverRespondingStream().let { it to it::release },
    )

    @Test
    fun `a blocked read throws SocketTimeoutException once soTimeout elapses, whichever way dadb parks`() {
        for ((stream, release) in parkModes()) {
            val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
            socket.soTimeout = 500
            val input = socket.getInputStream()

            try {
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertThrows<SocketTimeoutException> { input.read() }
                }
            } finally {
                release()
            }
        }
    }

    @Test
    fun `close releases a read parked on the adb stream, whichever way dadb parks`() {
        for ((stream, release) in parkModes()) {
            val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
            val reader = ParkedReader(socket.getInputStream())

            socket.close()
            val released = reader.releasedWithin5s()
            release()

            assertWithMessage("read was not released within 5s of close()").that(released).isTrue()
            assertThat(reader.thrown.get()).isInstanceOf(SocketException::class.java)
        }
    }

    @Test
    fun `an external interrupt surfaces as InterruptedIOException and re-asserts the interrupt flag`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val reader = ParkedReader(socket.getInputStream())

        reader.thread.interrupt()
        val released = reader.releasedWithin5s()
        stream.release()

        assertWithMessage("read was not released within 5s of the interrupt").that(released).isTrue()
        assertThat(reader.thrown.get()).isInstanceOf(InterruptedIOException::class.java)
        assertWithMessage("interrupt flag was not re-asserted").that(reader.interruptFlagAfterCatch.get()).isTrue()
    }

    @Test
    fun `connect times out when the adb open handshake never completes`() {
        val park = InterruptProofLatch()
        val socket = AdbSocketFactory.bounded(pool) { _, _ ->
            park.awaitUninterruptibly()
            error("open must not complete")
        }.createSocket()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows<SocketTimeoutException> {
                    socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222), 500)
                }
            }
        } finally {
            park.release()
        }
    }

    @Test
    fun `a connect that completes after the caller timed out closes the abandoned stream`() {
        val gate = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val stream = object : AdbStream {
            override val source = Buffer()
            override val sink = Buffer()
            override fun close() {
                streamClosed.countDown()
            }
        }
        val socket = AdbSocketFactory.bounded(pool) { _, _ ->
            // Parks through the cancellation interrupt AND consumes the flag, like a dadb open
            // whose internals catch InterruptedException without re-asserting: the handshake
            // still completes after the caller stopped waiting.
            while (gate.count > 0) {
                try {
                    gate.await()
                } catch (_: InterruptedException) {
                    // deliberately swallowed
                }
            }
            stream
        }.createSocket()

        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertThrows<SocketTimeoutException> {
                socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222), 200)
            }
        }
        gate.countDown()

        assertWithMessage("the stream opened after the connect timeout was never closed")
            .that(streamClosed.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a read after close fails fast with SocketException`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        socket.close()
        stream.release()

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertThrows<SocketException> { input.read() }
        }
    }

    @Test
    fun `a dadb stream failure propagates as the original exception`() {
        val failure = IOException("dadb transport broke")
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> failingReadStream(failure) }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = assertTimeoutPreemptively<IOException>(Duration.ofSeconds(5)) {
            assertThrows<IOException> { input.read() }
        }
        assertThat(thrown).hasMessageThat().contains("dadb transport broke")
    }

    @Test
    fun `a blocked write throws SocketTimeoutException once soTimeout elapses`() {
        // A wedged transport write: the stream sink has no timeout, so a write blocked on a full TCP
        // send buffer parks the caller forever and ignores Thread.interrupt().
        val park = InterruptProofLatch()
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> parkedWriteStream(park) }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        socket.soTimeout = 500
        val out = socket.getOutputStream()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows<SocketTimeoutException> { out.write("GET /json HTTP/1.1".toByteArray()) }
            }
        } finally {
            park.release()
        }
    }

    @Test
    fun `bounded calls fail fast instead of growing threads when every adb io worker is parked`() {
        val entered = CountDownLatch(ADB_IO_POOL_MAX)
        val park = InterruptProofLatch()

        // Connect the probe socket while the pool is still free; only its read races the
        // saturated pool below.
        val probeStream = NeverRespondingStream()
        val probe = AdbSocketFactory.bounded(pool) { _, _ -> probeStream }.createSocket()
        probe.connect(InetSocketAddress("webview_devtools_remote_probe", 9222))
        probe.soTimeout = 500
        val probeInput = probe.getInputStream()

        val readers = saturate(pool, entered, park)

        try {
            assertWithMessage("saturating reads never parked $ADB_IO_POOL_MAX workers")
                .that(entered.await(10, TimeUnit.SECONDS)).isTrue()

            val thrown = assertTimeoutPreemptively<IOException>(Duration.ofSeconds(2)) {
                assertThrows<IOException> { probeInput.read() }
            }
            // The submit was rejected (pool at cap), not accepted onto a 17th thread or timed out on
            // a worker: the message is the fail-fast rejection, and it is not a SocketTimeoutException.
            assertThat(thrown).isNotInstanceOf(SocketTimeoutException::class.java)
            assertThat(thrown).hasMessageThat().contains("adb I/O workers are busy")
        } finally {
            park.release()
            probeStream.release()
            readers.forEach { it.join(5_000) }
        }
    }

    @Test
    fun `a saturated pool does not starve bounded calls on a separate pool`() {
        // The bounded pool is owned per client (per device): a wedged client that saturates its own
        // pool must not reject calls another client makes on its own pool, or one hung device would
        // blank out webview capture across the process.
        val entered = CountDownLatch(ADB_IO_POOL_MAX)
        val park = InterruptProofLatch()
        val otherPool = newAdbIoExecutor()
        val readers = saturate(pool, entered, park)

        try {
            assertWithMessage("the first pool never saturated")
                .that(entered.await(10, TimeUnit.SECONDS)).isTrue()

            // A read on the second, free pool completes instead of failing fast on exhaustion.
            val healthy = object : AdbStream {
                override val source = Buffer().writeByte(7)
                override val sink = Buffer()
                override fun close() = Unit
            }
            val socket = AdbSocketFactory.bounded(otherPool) { _, _ -> healthy }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_healthy", 9222))

            val byte = assertTimeoutPreemptively<Int>(Duration.ofSeconds(2)) { socket.getInputStream().read() }
            assertThat(byte).isEqualTo(7)
        } finally {
            park.release()
            otherPool.shutdownNow()
            readers.forEach { it.join(5_000) }
        }
    }

    @Test
    fun `bounded close returns promptly even when the stream close blocks forever`() {
        // dadb's AdbStreamImpl.close() sends CLSE through a raw, non-interruptible socket write
        // (under the connection-wide sink monitor), so a wedged transport parks close() forever.
        val park = InterruptProofLatch()
        val closeEntered = CountDownLatch(1)
        val stream = object : AdbStream {
            override val source = Buffer()
            override val sink = Buffer()
            override fun close() {
                closeEntered.countDown()
                park.awaitUninterruptibly()
            }
        }
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) { socket.close() }
            val dispatched = closeEntered.await(5, TimeUnit.SECONDS)
            assertWithMessage("stream close was never attempted after socket close").that(dispatched).isTrue()
        } finally {
            park.release()
        }
    }

    @Test
    fun `a read abandoned by timeout or interrupt marks the socket broken so the next read fails fast`() {
        // An abandoned worker may still consume bytes into its discarded buffer, so a later read on
        // the same socket would silently lose data. Both abandonment triggers must fail it fast.

        // soTimeout abandons the read.
        InterruptProofNeverRespondingStream().let { stream ->
            val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
            socket.soTimeout = 500
            val input = socket.getInputStream()
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertThrows<SocketTimeoutException> { input.read() }
                    assertThrows<SocketException> { input.read() }
                }
            } finally {
                stream.release()
            }
        }

        // An external interrupt abandons the read the same way.
        InterruptProofNeverRespondingStream().let { stream ->
            val socket = AdbSocketFactory.bounded(pool) { _, _ -> stream }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
            val input = socket.getInputStream()
            val reader = ParkedReader(input)
            reader.thread.interrupt()
            try {
                assertWithMessage("interrupted read was not released within 5s")
                    .that(reader.releasedWithin5s()).isTrue()
                assertThat(reader.thrown.get()).isInstanceOf(InterruptedIOException::class.java)
                socket.soTimeout = 500
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertThrows<SocketException> { input.read() }
                }
            } finally {
                stream.release()
            }
        }
    }

    @Test
    fun `a write after a write timeout fails fast with SocketException`() {
        // Same as the read case: the abandoned worker may still flush its bytes later, so a
        // later write would interleave with them and corrupt the exchange.
        val park = InterruptProofLatch()
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> parkedWriteStream(park) }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        socket.soTimeout = 500
        val out = socket.getOutputStream()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows<SocketTimeoutException> { out.write("first".toByteArray()) }
                assertThrows<SocketException> { out.write("second".toByteArray()) }
            }
        } finally {
            park.release()
        }
    }

    @Test
    fun `a JVM error on the worker surfaces as the error itself, not IOException`() {
        val failure = OutOfMemoryError("simulated JVM error")
        val socket = AdbSocketFactory.bounded(pool) { _, _ -> failingReadStream(failure) }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = assertTimeoutPreemptively<OutOfMemoryError>(Duration.ofSeconds(5)) {
            assertThrows<OutOfMemoryError> { input.read() }
        }
        assertThat(thrown).isSameInstanceAs(failure)
    }

    // ── scoping: the raw variant (gRPC channel) must behave like a plain passthrough ──

    @Test
    fun `raw sockets connect, read, and close on the caller thread with no executor handoff`() {
        val openerThread = AtomicReference<Thread>()
        val readThread = AtomicReference<Thread>()
        val closeThread = AtomicReference<Thread>()
        val stream = object : AdbStream {
            override val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readThread.set(Thread.currentThread())
                    sink.writeByte(42)
                    return 1L
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()

            override val sink = Buffer()

            override fun close() {
                closeThread.set(Thread.currentThread())
            }
        }
        val socket = AdbSocketFactory.raw { _, _ ->
            openerThread.set(Thread.currentThread())
            stream
        }.createSocket()

        socket.connect(InetSocketAddress("localhost", 7001))
        val byte = socket.getInputStream().read()
        socket.close()

        assertThat(byte).isEqualTo(42)
        assertThat(openerThread.get()).isSameInstanceAs(Thread.currentThread())
        assertThat(readThread.get()).isSameInstanceAs(Thread.currentThread())
        assertThat(closeThread.get()).isSameInstanceAs(Thread.currentThread())
    }

    @Test
    fun `raw sockets do not enforce soTimeout on a slow read`() {
        val stream = object : AdbStream {
            override val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    Thread.sleep(1_000)
                    sink.writeByte(42)
                    return 1L
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()

            override val sink = Buffer()

            override fun close() = Unit
        }
        val socket = AdbSocketFactory.raw { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("localhost", 7001))
        socket.soTimeout = 100

        // A bounded socket would throw SocketTimeoutException after ~100ms; the raw
        // passthrough has no timeout mechanism and just waits for the byte.
        val byte = socket.getInputStream().read()

        assertThat(byte).isEqualTo(42)
    }
}
