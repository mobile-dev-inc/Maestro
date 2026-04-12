package maestro.android

import com.google.common.truth.Truth.assertThat
import dadb.AdbStream
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.SocketException

class AdbSocketFactoryTest {

    @Test
    fun `factory creates a socket that opens an ADB stream on connect`() {
        var openedHost: String? = null
        var openedPort: Int? = null
        val factory = AdbSocketFactory { host, port ->
            openedHost = host
            openedPort = port
            fakeStream()
        }

        val socket = factory.createSocket()
        socket.connect(InetSocketAddress("localhost", 8080))

        assertThat(openedHost).isEqualTo("localhost")
        assertThat(openedPort).isEqualTo(8080)
    }

    @Test
    fun `getInputStream reads data from ADB stream`() {
        val source = Buffer().writeUtf8("hello from device")
        val socket = connectSocket(source = source)

        val data = socket.getInputStream().bufferedReader().readText()

        assertThat(data).isEqualTo("hello from device")
    }

    @Test
    fun `getOutputStream writes data to ADB stream`() {
        val sink = Buffer()
        val socket = connectSocket(sink = sink)

        socket.getOutputStream().write("hello to device".toByteArray())

        assertThat(sink.readUtf8()).isEqualTo("hello to device")
    }

    @Test
    fun `getInputStream throws when not connected`() {
        val socket = AdbSocketFactory { _, _ -> fakeStream() }.createSocket()

        assertThrows<SocketException> { socket.getInputStream() }
    }

    @Test
    fun `getOutputStream throws when not connected`() {
        val socket = AdbSocketFactory { _, _ -> fakeStream() }.createSocket()

        assertThrows<SocketException> { socket.getOutputStream() }
    }

    @Test
    fun `close closes the underlying ADB stream`() {
        var streamClosed = false
        val stream = object : AdbStream {
            override val source = Buffer()
            override val sink = Buffer()
            override fun close() {
                streamClosed = true
            }
        }
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("localhost", 8080))

        socket.close()

        assertThat(socket.isClosed).isTrue()
        assertThat(streamClosed).isTrue()
    }

    @Test
    fun `close is idempotent`() {
        val socket = connectSocket()

        socket.close()
        socket.close()

        assertThat(socket.isClosed).isTrue()
    }

    @Test
    fun `isConnected returns false before connect and true after`() {
        val socket = AdbSocketFactory { _, _ -> fakeStream() }.createSocket()

        assertThat(socket.isConnected).isFalse()

        socket.connect(InetSocketAddress("localhost", 8080))

        assertThat(socket.isConnected).isTrue()
    }

    @Test
    fun `isConnected returns false after close`() {
        val socket = connectSocket()

        socket.close()

        assertThat(socket.isConnected).isFalse()
    }

    @Test
    fun `output stream flushes on each write`() {
        val sink = Buffer()
        val socket = connectSocket(sink = sink)
        val out = socket.getOutputStream()

        out.write("first".toByteArray())
        assertThat(sink.readUtf8()).isEqualTo("first")

        out.write("second".toByteArray(), 0, 6)
        assertThat(sink.readUtf8()).isEqualTo("second")
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
        source: Buffer = Buffer(),
        sink: Buffer = Buffer(),
    ): java.net.Socket {
        val socket = AdbSocketFactory { _, _ -> fakeStream(source, sink) }.createSocket()
        socket.connect(InetSocketAddress("localhost", 8080))
        return socket
    }
}
