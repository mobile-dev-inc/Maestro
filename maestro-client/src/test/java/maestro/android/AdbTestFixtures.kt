package maestro.android

import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dadb.UninstallResult
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.File
import java.util.concurrent.CountDownLatch

// Fixtures shared by AdbSocketTimeoutTest and DadbChromeDevToolsClientTest.
// AndroidDeviceConnectionTest keeps its own FakeDadb on purpose: it stubs a different surface.

internal class FakeDadb(
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

/**
 * Accepts the connection but never produces a byte, mirroring a crashed or suspended renderer
 * behind a stale `webview_devtools_remote_*` socket, or a dadb stream reader parked in
 * `MessageQueue.take`'s `Condition.await` that `stopListening` never wakes. The park is
 * interruptible (like `Condition.await`); `close()` is recorded in [closed] but does not
 * release it.
 */
internal class NeverRespondingStream : AdbStream {
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
 * Parks until [release] and keeps parking through `Thread.interrupt()`, mirroring the OTHER
 * dadb park mode: the reader that wins `MessageQueue.take`'s transport read lock blocks in
 * `readMessage()` on the raw `java.net.Socket`, which JDK 17 does not release on interrupt
 * (the flag is set but the read stays blocked until bytes arrive).
 */
internal class InterruptProofLatch {
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

internal fun awaitParked(thread: Thread) {
    val deadline = System.currentTimeMillis() + 5_000
    while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
        check(System.currentTimeMillis() < deadline) { "thread never parked, state=${thread.state}" }
        Thread.sleep(10)
    }
}
