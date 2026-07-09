package maestro.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbStream
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Socket contract shared by both factory variants, asserted against each. Behavior that
 * deliberately diverges between them (timeouts, executor handoff, close semantics under a
 * wedged transport) lives in [AdbSocketTimeoutTest].
 */
class AdbSocketFactoryTest {

    // The bounded variant runs its dadb calls on this pool; the raw variant ignores it. One pool
    // per test instance (JUnit makes a fresh instance per method), shut down after each test.
    private val pool: ExecutorService = newAdbIoExecutor()

    @AfterEach
    fun shutDownPool() = pool.shutdownNow().let { }

    private enum class Variant(val factory: (ExecutorService, (host: String, port: Int) -> AdbStream) -> AdbSocketFactory) {
        RAW({ _, opener -> AdbSocketFactory.raw(opener) }),
        BOUNDED({ executor, opener -> AdbSocketFactory.bounded(executor, opener) }),
    }

    private fun forEachVariant(test: (Variant) -> Unit) = Variant.entries.forEach(test)

    @Test
    fun `factory creates a socket that opens an ADB stream on connect`() {
        forEachVariant { variant ->
            var openedHost: String? = null
            var openedPort: Int? = null
            val factory = variant.factory(pool) { host, port ->
                openedHost = host
                openedPort = port
                fakeStream()
            }

            val socket = factory.createSocket()
            socket.connect(InetSocketAddress("localhost", 8080))

            assertWithMessage("$variant").that(openedHost).isEqualTo("localhost")
            assertWithMessage("$variant").that(openedPort).isEqualTo(8080)
        }
    }

    @Test
    fun `getInputStream reads data from ADB stream`() {
        forEachVariant { variant ->
            val source = Buffer().writeUtf8("hello from device")
            val socket = connectSocket(variant, source = source)

            val data = socket.getInputStream().bufferedReader().readText()

            assertWithMessage("$variant").that(data).isEqualTo("hello from device")
        }
    }

    @Test
    fun `getOutputStream writes data to ADB stream`() {
        forEachVariant { variant ->
            val sink = Buffer()
            val socket = connectSocket(variant, sink = sink)

            socket.getOutputStream().write("hello to device".toByteArray())

            assertWithMessage("$variant").that(sink.readUtf8()).isEqualTo("hello to device")
        }
    }

    @Test
    fun `getInputStream throws when not connected`() {
        forEachVariant { variant ->
            val socket = variant.factory(pool) { _, _ -> fakeStream() }.createSocket()

            assertThrows<SocketException>("$variant") { socket.getInputStream() }
        }
    }

    @Test
    fun `getOutputStream throws when not connected`() {
        forEachVariant { variant ->
            val socket = variant.factory(pool) { _, _ -> fakeStream() }.createSocket()

            assertThrows<SocketException>("$variant") { socket.getOutputStream() }
        }
    }

    @Test
    fun `close closes the underlying ADB stream`() {
        forEachVariant { variant ->
            // The bounded variant dispatches the stream close to a worker so a wedged transport
            // cannot park close(); it must still happen, just not necessarily before close() returns.
            val streamClosed = CountDownLatch(1)
            val stream = object : AdbStream {
                override val source = Buffer()
                override val sink = Buffer()
                override fun close() {
                    streamClosed.countDown()
                }
            }
            val socket = variant.factory(pool) { _, _ -> stream }.createSocket()
            socket.connect(InetSocketAddress("localhost", 8080))

            socket.close()

            assertWithMessage("$variant").that(socket.isClosed).isTrue()
            assertWithMessage("$variant").that(streamClosed.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `close is idempotent`() {
        forEachVariant { variant ->
            val socket = connectSocket(variant)

            socket.close()
            socket.close()

            assertWithMessage("$variant").that(socket.isClosed).isTrue()
        }
    }

    @Test
    fun `isConnected returns false before connect and true after`() {
        forEachVariant { variant ->
            val socket = variant.factory(pool) { _, _ -> fakeStream() }.createSocket()

            assertWithMessage("$variant").that(socket.isConnected).isFalse()

            socket.connect(InetSocketAddress("localhost", 8080))

            assertWithMessage("$variant").that(socket.isConnected).isTrue()
        }
    }

    @Test
    fun `isConnected returns false after close`() {
        forEachVariant { variant ->
            val socket = connectSocket(variant)

            socket.close()

            assertWithMessage("$variant").that(socket.isConnected).isFalse()
        }
    }

    @Test
    fun `output stream flushes on each write`() {
        forEachVariant { variant ->
            val sink = Buffer()
            val socket = connectSocket(variant, sink = sink)
            val out = socket.getOutputStream()

            out.write("first".toByteArray())
            assertWithMessage("$variant").that(sink.readUtf8()).isEqualTo("first")

            out.write("second".toByteArray(), 0, 6)
            assertWithMessage("$variant").that(sink.readUtf8()).isEqualTo("second")
        }
    }

    private fun fakeStream(
        source: Buffer = Buffer(),
        sink: Buffer = Buffer(),
    ): AdbStream = object : AdbStream {
        override val source = source
        override val sink = sink
        override fun close() {}
    }

    private fun connectSocket(
        variant: Variant,
        source: Buffer = Buffer(),
        sink: Buffer = Buffer(),
    ): Socket {
        val socket = variant.factory(pool) { _, _ -> fakeStream(source, sink) }.createSocket()
        socket.connect(InetSocketAddress("localhost", 8080))
        return socket
    }
}
