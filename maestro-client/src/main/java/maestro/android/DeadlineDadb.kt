package maestro.android

import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.AdbSyncStream
import dadb.Dadb
import dadb.SyncResult
import okio.Sink
import okio.Source
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** A one-shot adb operation (shell, pull, push) outlived [DeadlineDadb.OPERATION_DEADLINE_MS]; its stream was closed. */
class AdbOperationTimeoutException(val operation: String, timeoutMs: Long) :
    IOException("$operation did not finish within ${timeoutMs}ms")

/**
 * Bounds the one-shot dadb operations (shell, pull, push) with a deadline and closes their stream
 * when it passes. Long-lived streams (instrumentation shell, gRPC tunnel) keep their own lifecycle.
 */
internal class DeadlineDadb(private val delegate: Dadb) : Dadb by delegate {

    private val inFlight = ThreadLocal<AtomicReference<AdbStream?>>()

    override fun open(destination: String): AdbStream =
        delegate.open(destination).also { stream -> inFlight.get()?.set(stream) }

    // Default-method ops run on this instance so their open() lands above instead of on the delegate.
    override fun openShell(command: String): AdbShellStream = super.openShell(command)
    override fun openSync(): AdbSyncStream = super.openSync()
    override fun pull(dst: File, remotePath: String): SyncResult = super.pull(dst, remotePath)
    override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult =
        super.push(src, remotePath, mode, lastModifiedMs)

    override fun shell(command: String): AdbShellResponse =
        withDeadline("shell: $command") { super.shell(command) }

    override fun pull(sink: Sink, remotePath: String): SyncResult =
        withDeadline("pull: $remotePath") { super.pull(sink, remotePath) }

    override fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult =
        withDeadline("push: $remotePath") { super.push(source, remotePath, mode, lastModifiedMs) }

    override fun toString(): String = delegate.toString()

    private fun <R> withDeadline(operation: String, block: () -> R): R {
        val opened = AtomicReference<AdbStream?>()
        val future = EXECUTOR.submit(Callable {
            inFlight.set(opened)
            try {
                block()
            } finally {
                inFlight.remove()
            }
        })
        return try {
            future.get(OPERATION_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            runCatching { opened.get()?.close() }
            throw AdbOperationTimeoutException(operation, OPERATION_DEADLINE_MS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    companion object {
        // adb streams carry no socket timeout, so a stuck `pm`/`am`/sync exchange would otherwise
        // block the flow forever (#3658). 60 s is well above the slowest healthy `pm clear` or APK
        // pull on a cold emulator.
        const val OPERATION_DEADLINE_MS = 60_000L

        private val EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "adb-deadline").apply { isDaemon = true }
        }
    }
}

private val LOGGER = LoggerFactory.getLogger(DeadlineDadb::class.java)

/** Runs [block] once more if it hits the deadline: one stuck adb-server stream is not a dead device. */
internal fun <R> retryOnceOnTimeout(block: () -> R): R =
    try {
        block()
    } catch (e: AdbOperationTimeoutException) {
        LOGGER.warn("${e.message}; retrying once")
        block()
    }
