package maestro.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dadb.UninstallResult
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
import java.io.File
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

    /**
     * Blocks reads on a latch that `close()` intentionally does NOT release, mirroring how a dadb
     * stream reader parked in `MessageQueue.take` (`Condition.await`) is never woken by
     * `stopListening`. The park is interruptible, exactly like `Condition.await`.
     */
    private class NeverRespondingStream : AdbStream {
        private val latch = CountDownLatch(1)

        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                latch.await()
                return -1L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override val sink = Buffer()

        override fun close() = Unit

        fun release() = latch.countDown()
    }

    /**
     * Parks until [release] and keeps parking through `Thread.interrupt()`, mirroring the OTHER
     * dadb park mode: the reader that wins `MessageQueue.take`'s transport read lock blocks in
     * `readMessage()` on the raw `java.net.Socket`, which JDK 17 does not release on interrupt
     * (the flag is set but the read stays blocked until bytes arrive).
     */
    private class InterruptProofLatch {
        private val latch = CountDownLatch(1)

        fun awaitUninterruptibly() {
            var interrupted = false
            while (latch.count > 0) {
                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun release() = latch.countDown()
    }

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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ ->
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
    fun `a read after close fails fast with SocketException`() {
        val stream = NeverRespondingStream()
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
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
        val socket = AdbSocketFactory { _, _ -> stream }.createSocket()
        socket.connect(InetSocketAddress("webview_devtools_remote_12345", 9222))
        val input = socket.getInputStream()

        val thrown = assertTimeoutPreemptively<IOException>(Duration.ofSeconds(5)) {
            assertThrows<IOException> { input.read() }
        }
        assertThat(thrown).hasMessageThat().contains("dadb transport broke")
    }

    private fun awaitParked(thread: Thread) {
        val deadline = System.currentTimeMillis() + 5_000
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            check(System.currentTimeMillis() < deadline) { "reader thread never parked, state=${thread.state}" }
            Thread.sleep(10)
        }
    }
}
