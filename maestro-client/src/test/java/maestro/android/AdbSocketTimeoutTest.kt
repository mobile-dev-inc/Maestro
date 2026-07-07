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
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MA-4111: a webview devtools socket that accepts the connection but never responds must not hang
 * the flow forever. dadb parks stream readers on a condition that `AdbStream.close()` never
 * signals (`MessageQueue.stopListening` removes the queue without a `signalAll`), so the fake
 * socket in [AdbSocketFactory] must enforce `soTimeout` itself and release parked readers on
 * `close()`, otherwise OkHttp's read/call timeouts have no effect.
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

    private fun awaitParked(thread: Thread) {
        val deadline = System.currentTimeMillis() + 5_000
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            check(System.currentTimeMillis() < deadline) { "reader thread never parked, state=${thread.state}" }
            Thread.sleep(10)
        }
    }
}
