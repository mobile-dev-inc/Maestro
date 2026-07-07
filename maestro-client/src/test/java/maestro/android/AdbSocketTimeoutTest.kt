package maestro.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbShellResponse
import dadb.AdbStream
import maestro.android.chromedevtools.DadbChromeDevToolsClient
import maestro.android.chromedevtools.WebViewInfo
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * MA-4111: a webview devtools socket that accepts the connection but never responds must not hang
 * the flow forever. dadb parks stream readers in one of two ways, neither of which `soTimeout` or
 * `AdbStream.close()` can bound on its own: in `MessageQueue.take`'s `Condition.await`
 * (interruptible, but `stopListening` removes the queue without a `signalAll`) or, for the reader
 * holding the transport read lock, in a raw `java.net.Socket` read that `Thread.interrupt()` does
 * not release on JDK 17. The fake socket in [AdbSocketFactory] must therefore bound the caller's
 * wait itself for both park modes, otherwise OkHttp's read/call timeouts have no effect.
 */
class AdbSocketTimeoutTest {

    // NeverRespondingStream, InterruptProofLatch, FakeDadb, awaitParked: see AdbTestFixtures.kt.

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

    @Test
    fun `getWebViewInfos degrades to an empty list when a devtools socket accepts but never responds`() {
        val stream = NeverRespondingStream()
        val openedDestinations = mutableListOf<String>()
        val dadb = FakeDadb(
            onShell = { command ->
                assertThat(command).isEqualTo("cat /proc/net/unix")
                AdbShellResponse(
                    "0000000000000000: 00000002 00000000 00010000 0001 01 54321 @webview_devtools_remote_12345\n",
                    "",
                    0,
                )
            },
            onOpen = { destination ->
                openedDestinations += destination
                stream
            },
        )
        val connection = AndroidDeviceConnection.forTest(dadb = dadb)

        val infos = try {
            assertTimeoutPreemptively<List<WebViewInfo>>(Duration.ofSeconds(30)) {
                DadbChromeDevToolsClient(connection).use { it.getWebViewInfos() }
            }
        } finally {
            stream.release()
        }

        assertThat(openedDestinations).contains("localabstract:webview_devtools_remote_12345")
        assertThat(infos).isEmpty()
    }

    @Test
    fun `a blocked read throws SocketTimeoutException once soTimeout elapses`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        socket.soTimeout = 500
        val input = socket.getInputStream()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows<SocketTimeoutException> { input.read() }
            }
        } finally {
            stream.release()
        }
    }

    @Test
    fun `close releases a read parked on the adb stream`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val reader = Thread {
            try {
                input.read()
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                done.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }
        awaitParked(reader)

        socket.close()
        val released = done.await(5, TimeUnit.SECONDS)
        stream.release()

        assertWithMessage("read was not released within 5s of close()").that(released).isTrue()
        assertThat(thrown.get()).isInstanceOf(SocketException::class.java)
    }

    @Test
    fun `soTimeout is enforced even when the parked read ignores thread interrupts`() {
        val stream = InterruptProofNeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        socket.soTimeout = 500
        val input = socket.getInputStream()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows<SocketTimeoutException> { input.read() }
            }
        } finally {
            stream.release()
        }
    }

    @Test
    fun `close releases a read parked in an interrupt-proof source`() {
        val stream = InterruptProofNeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val reader = Thread {
            try {
                input.read()
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                done.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }
        awaitParked(reader)

        socket.close()
        val released = done.await(5, TimeUnit.SECONDS)
        stream.release()

        assertWithMessage("read was not released within 5s of close()").that(released).isTrue()
        assertThat(thrown.get()).isInstanceOf(SocketException::class.java)
    }

    @Test
    fun `an external interrupt surfaces as InterruptedIOException and re-asserts the interrupt flag`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = AtomicReference<Throwable>()
        val flagReasserted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val reader = Thread {
            try {
                input.read()
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
        awaitParked(reader)

        reader.interrupt()
        val released = done.await(5, TimeUnit.SECONDS)
        stream.release()

        assertWithMessage("read was not released within 5s of the interrupt").that(released).isTrue()
        assertThat(thrown.get()).isInstanceOf(InterruptedIOException::class.java)
        assertWithMessage("interrupt flag was not re-asserted").that(flagReasserted.get()).isTrue()
    }

    @Test
    fun `connect times out when the adb open handshake never completes`() {
        val park = InterruptProofLatch()
        val socket = AdbSocketFactory.bounded { _, _ ->
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
        val socket = AdbSocketFactory.bounded { _, _ ->
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
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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
        val stream = object : AdbStream {
            override val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw failure
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override val sink = Buffer()
            override fun close() = Unit
        }
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = assertTimeoutPreemptively<IOException>(Duration.ofSeconds(5)) {
            assertThrows<IOException> { input.read() }
        }
        assertThat(thrown).hasMessageThat().contains("dadb transport broke")
    }

    @Test
    fun `a blocked write throws SocketTimeoutException once soTimeout elapses`() {
        // Mirrors a wedged transport write. On the adb-server path (Dadb.list / AdbServer.createDadb)
        // the stream sink is Okio.sink(socket).buffer() with NO timeout configured, so a write that
        // blocks on a full TCP send buffer parks the caller forever and ignores Thread.interrupt().
        val park = InterruptProofLatch()
        val stream = object : AdbStream {
            override val source = Buffer()
            override val sink = object : Sink {
                override fun write(source: Buffer, byteCount: Long) = park.awaitUninterruptibly()
                override fun flush() = Unit
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun close() = Unit
        }
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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
        fun parkedStream() = object : AdbStream {
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

        // Connect the probe socket while the pool is still free; only its read races the
        // saturated pool below.
        val probeStream = NeverRespondingStream()
        val probe = AdbSocketFactory.bounded { _, _ -> probeStream }.createSocket()
        probe.connect(InetSocketAddress("webview_devtools_remote_probe", 9222))
        probe.soTimeout = 500
        val probeInput = probe.getInputStream()

        val readers = (1..ADB_IO_POOL_MAX).map { n ->
            val socket = AdbSocketFactory.bounded { _, _ -> parkedStream() }.createSocket()
            socket.connect(InetSocketAddress("webview_devtools_remote_$n", 9222))
            val input = socket.getInputStream()
            Thread {
                while (entered.count > 0) {
                    try {
                        input.read()
                        break
                    } catch (_: IOException) {
                        // A worker from a previous test may still be winding down and briefly
                        // occupy a slot; retry until this reader parks a worker of its own.
                        Thread.sleep(50)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        try {
            assertWithMessage("saturating reads never parked $ADB_IO_POOL_MAX workers")
                .that(entered.await(10, TimeUnit.SECONDS)).isTrue()

            val thrown = assertTimeoutPreemptively<IOException>(Duration.ofSeconds(2)) {
                assertThrows<IOException> { probeInput.read() }
            }
            // Fail-fast pool exhaustion, not a timed-out worker on an unbounded extra thread.
            assertThat(thrown).isNotInstanceOf(SocketTimeoutException::class.java)
            val alive = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("AdbSocketIo-") }
            assertWithMessage("adb io worker threads exceeded the pool cap")
                .that(alive).isAtMost(ADB_IO_POOL_MAX)
        } finally {
            park.release()
            probeStream.release()
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
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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
    fun `a read after a read timeout fails fast with SocketException`() {
        // The abandoned worker may still consume bytes into its discarded buffer, so a later
        // read on the same socket would silently lose data; it must fail fast instead.
        val stream = InterruptProofNeverRespondingStream()
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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

    @Test
    fun `a write after a write timeout fails fast with SocketException`() {
        // Same as the read case: the abandoned worker may still flush its bytes later, so a
        // later write would interleave with them and corrupt the exchange.
        val park = InterruptProofLatch()
        val stream = object : AdbStream {
            override val source = Buffer()
            override val sink = object : Sink {
                override fun write(source: Buffer, byteCount: Long) = park.awaitUninterruptibly()
                override fun flush() = Unit
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun close() = Unit
        }
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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
        val stream = object : AdbStream {
            override val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw failure
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override val sink = Buffer()
            override fun close() = Unit
        }
        val socket = AdbSocketFactory.bounded { _, _ -> stream }.createSocket()
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
