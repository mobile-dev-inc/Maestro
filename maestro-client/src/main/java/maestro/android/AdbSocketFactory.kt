package maestro.android

import dadb.AdbStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class AdbSocketFactory private constructor(
    private val opener: (host: String, port: Int) -> AdbStream,
    // Non-null ⇒ bounded worker handoff on this pool; null ⇒ raw caller-thread passthrough.
    private val executor: ExecutorService?,
) : SocketFactory() {

    companion object {
        /**
         * Raw passthrough: connect, reads, writes, and close all run on the caller's thread with no
         * timeout enforcement. For the gRPC channel, which owns its own deadlines.
         */
        fun raw(opener: (host: String, port: Int) -> AdbStream): AdbSocketFactory =
            AdbSocketFactory(opener, executor = null)

        /**
         * Bounded worker handoff: blocking dadb calls run on [executor] so the caller's wait is
         * bounded (connect timeout, soTimeout) and released by close(). For the devtools client,
         * whose webview endpoints can accept a connection and never respond.
         */
        fun bounded(executor: ExecutorService, opener: (host: String, port: Int) -> AdbStream): AdbSocketFactory =
            AdbSocketFactory(opener, executor)
    }

    override fun createSocket(): Socket =
        executor?.let { BoundedAdbSocket(it, opener) } ?: RawAdbSocket(opener)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket(host?.hostAddress, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)
}

// dadb can't bound or abort a blocked stream read: the reader parks in Condition.await (which
// AdbStream.close() never signals) or in a raw java.net.Socket read that JDK 17 won't release on
// interrupt. So blocking dadb calls run on these daemon workers while the caller waits with a
// timeout; a parked worker is abandoned and dies when the transport produces bytes or closes. The
// pool is capped so abandoned threads can't grow without bound (the next call fails fast) and owned
// per client (one per device) so a wedged device can't starve captures on healthy ones. 16 is
// generous: each webview is one HTTP listing plus one websocket exchange, largely sequential.
internal const val ADB_IO_POOL_MAX = 16
private val adbIoThreadCount = AtomicInteger()

// One bounded worker pool per devtools client, created here and shut down in the client's close().
internal fun newAdbIoExecutor(): ExecutorService = ThreadPoolExecutor(
    0,
    ADB_IO_POOL_MAX,
    60L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
) { runnable ->
    Thread(runnable, "AdbSocketIo-${adbIoThreadCount.incrementAndGet()}").apply { isDaemon = true }
}

// Submits one blocking dadb call to the client's capped pool. Pool exhaustion fails fast instead
// of queueing: every worker is parked against a wedged transport (see newAdbIoExecutor).
private fun <T> submitAdbCall(executor: ExecutorService, operation: String, block: () -> T): Future<T> =
    try {
        executor.submit(Callable { block() })
    } catch (e: RejectedExecutionException) {
        throw IOException("$operation failed: all $ADB_IO_POOL_MAX adb I/O workers are busy", e)
    }

// Timed wait with the shared timeout policy: cancel the worker (abandoning it if parked in a
// raw socket read, see newAdbIoExecutor) and unblock the caller.
private fun <T> Future<T>.awaitBounded(timeoutMillis: Long, operation: String): T =
    try {
        get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        cancel(true)
        throw SocketTimeoutException("$operation timed out after ${timeoutMillis}ms")
    }

// Runs one blocking dadb call on the shared capped pool and bounds the caller's wait, for module
// callers whose dadb calls do not flow through a BoundedAdbSocket (e.g. the devtools client's
// shell discovery): the same un-abortable dadb parks apply. An ExecutionException rethrows its
// cause unwrapped so typed failures (DeviceConnectionException, JVM Errors) survive the handoff;
// an interrupt of the waiting caller propagates raw so the caller decides whether it aborts.
internal fun <T> boundedAdbCall(executor: ExecutorService, timeoutMillis: Long, operation: String, block: () -> T): T {
    val future = submitAdbCall(executor, operation, block)
    return try {
        future.awaitBounded(timeoutMillis, operation)
    } catch (e: InterruptedException) {
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

// Throwaway close threads currently parked against a wedged transport. Bounded so a persistently
// wedged device cannot spawn them without limit, which would recreate the JVM-level starvation the
// pool cap prevents (close() can itself park forever on that same transport).
internal val pendingCloseThreads = AtomicInteger()

// Closing a dadb stream can park forever: AdbStreamImpl.close() sends CLSE through a raw,
// non-interruptible socket write under the connection-wide sink monitor. Bounded sockets therefore
// never close streams on the caller thread; they hand the close to the pool, or to a throwaway daemon
// thread when the pool is exhausted.
private fun closeOffThread(executor: ExecutorService, stream: AdbStream) {
    try {
        executor.execute { runCatching { stream.close() } }
    } catch (e: RejectedExecutionException) {
        // Pool saturated by a wedged transport. Spawn a throwaway closer, but bound how many pile up:
        // past the cap, drop the close. A stream on a dead transport is reaped when the connection
        // closes anyway, and a parked close accomplishes nothing until the transport recovers.
        if (pendingCloseThreads.incrementAndGet() > ADB_IO_POOL_MAX) {
            pendingCloseThreads.decrementAndGet()
            return
        }
        Thread({
            try {
                runCatching { stream.close() }
            } finally {
                pendingCloseThreads.decrementAndGet()
            }
        }, "AdbStreamClose").apply { isDaemon = true }.start()
    }
}

/** Shared address bookkeeping and socket-option no-ops for the fake sockets below. */
private abstract class BaseAdbSocket : Socket() {

    protected var endpoint: InetSocketAddress? = null

    // Address/port info (used by gRPC OkHttp transport for logging)
    override fun getInetAddress(): InetAddress = endpoint?.address ?: InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = endpoint?.port ?: 0
    override fun getLocalPort(): Int = 0
    override fun getRemoteSocketAddress(): SocketAddress = endpoint ?: InetSocketAddress(0)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(0)
    override fun isBound(): Boolean = true

    // No-ops for socket configuration (called by gRPC OkHttp transport)
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay(): Boolean = false
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getKeepAlive(): Boolean = false
    override fun setSoLinger(on: Boolean, linger: Int) = Unit
    override fun getSoLinger(): Int = -1
    override fun setSendBufferSize(size: Int) = Unit
    override fun getSendBufferSize(): Int = 0
    override fun setReceiveBufferSize(size: Int) = Unit
    override fun getReceiveBufferSize(): Int = 0
    override fun setReuseAddress(on: Boolean) = Unit
    override fun getReuseAddress(): Boolean = false
    override fun setTrafficClass(tc: Int) = Unit
    override fun getTrafficClass(): Int = 0
    override fun setOOBInline(on: Boolean) = Unit
    override fun getOOBInline(): Boolean = false
    override fun isInputShutdown() = false
    override fun isOutputShutdown() = false
    override fun shutdownInput() = Unit
    override fun shutdownOutput() = Unit
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) = Unit
    override fun sendUrgentData(data: Int) = Unit
    override fun bind(bindpoint: SocketAddress?) = Unit
}

/** Plain passthrough onto the dadb stream: every call runs on the caller's thread. Used by the
 *  gRPC channel, which multiplexes one long-lived connection and enforces its own deadlines. */
private class RawAdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : BaseAdbSocket() {

    private var stream: AdbStream? = null
    private var closed = false

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (endpoint !is InetSocketAddress) throw UnsupportedOperationException("Endpoint must be InetSocketAddress")
        this.endpoint = endpoint
        stream = opener(endpoint.hostString, endpoint.port)
    }

    // The raw passthrough has no timeout mechanism; soTimeout (set by the gRPC transport) is ignored.
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getSoTimeout(): Int = 0

    override fun getInputStream(): InputStream {
        val s = stream ?: throw SocketException("Socket is not connected")
        return s.source.inputStream()
    }

    override fun getOutputStream(): OutputStream {
        val s = stream ?: throw SocketException("Socket is not connected")
        return object : FilterOutputStream(s.sink.outputStream()) {
            override fun write(b: ByteArray) {
                super.write(b)
                flush()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                flush()
            }
        }
    }

    override fun isConnected(): Boolean = stream != null && !closed

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        stream?.close()
        stream = null
        closed = true
    }
}

/**
 * Runs every blocking dadb call on the client's pool so the caller's wait is bounded by the connect
 * timeout / soTimeout and released by [close] even when the worker is parked in an un-abortable dadb
 * read. Used by the devtools client.
 */
private class BoundedAdbSocket(
    private val executor: ExecutorService,
    private val opener: (host: String, port: Int) -> AdbStream,
) : BaseAdbSocket() {

    @Volatile private var stream: AdbStream? = null
    @Volatile private var closed = false

    // Set once a stream call is abandoned mid-flight: the abandoned worker may still touch the stream
    // (consume or flush bytes late), so later calls would lose or interleave data. Fail fast instead.
    @Volatile private var broken = false

    @Volatile private var soTimeoutMillis = 0

    // Blocking dadb calls currently parked on worker threads; close() cancels them so their
    // callers are released.
    private val inFlight = ConcurrentHashMap.newKeySet<Future<*>>()

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (endpoint !is InetSocketAddress) throw UnsupportedOperationException("Endpoint must be InetSocketAddress")
        this.endpoint = endpoint
        // The OPEN handshake blocks on the same transport reads as stream reads, so it gets the same
        // worker handoff (timeout from OkHttp's connectTimeout; 0 is unbounded but close() can still
        // release the caller). The worker publishes the stream with a CAS; an abandoning caller
        // claims it with getAndSet(ABANDONED), so whoever loses owns the close and a late handshake
        // is closed exactly once however the two interleave.
        val handoff = AtomicReference<Any?>()
        val opened = try {
            awaitBlockingCall(timeout, "Connect to ${endpoint.hostString}") {
                val s = opener(endpoint.hostString, endpoint.port)
                if (!handoff.compareAndSet(null, s)) {
                    closeOffThread(executor, s)
                    throw InterruptedException("Connect to ${endpoint.hostString} abandoned")
                }
                s
            }
        } catch (e: Throwable) {
            (handoff.getAndSet(ABANDONED) as? AdbStream)?.let { closeOffThread(executor, it) }
            throw e
        }
        stream = opened
        if (closed) {
            // close() raced with connect() and could not see the stream yet.
            closeOffThread(executor, opened)
            stream = null
            throw SocketException("Socket closed")
        }
    }

    override fun getInputStream(): InputStream {
        val s = stream ?: throw SocketException("Socket is not connected")
        val delegate = s.source.inputStream()
        return object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                // The worker reads into its own buffer: once a caller times out and abandons a
                // read, a late completion must not write into an array the caller has recycled.
                val chunk = ByteArray(len)
                val n = boundedStreamCall("Read") { delegate.read(chunk, 0, len) }
                if (n > 0) System.arraycopy(chunk, 0, b, off, n)
                return n
            }

            override fun available(): Int = delegate.available()
            override fun close() = delegate.close()
        }
    }

    // Bounds a stream read/write by soTimeout and marks the socket broken once one is abandoned.
    private fun <T> boundedStreamCall(operation: String, block: () -> T): T {
        if (broken) throw SocketException("Socket is broken: an earlier read or write was abandoned mid-call")
        try {
            return awaitBlockingCall(soTimeoutMillis, operation, block)
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException and the interrupt path both extend InterruptedIOException,
            // and both abandon the worker mid-call the same way.
            broken = true
            throw e
        }
    }

    // Runs a blocking dadb call on a worker thread and bounds the caller's wait, because neither
    // soTimeout nor close() can otherwise release a caller parked inside dadb (see newAdbIoExecutor).
    private fun <T> awaitBlockingCall(timeoutMillis: Int, operation: String, block: () -> T): T {
        if (closed) throw SocketException("Socket closed")
        val future = submitAdbCall(executor, operation, block)
        inFlight.add(future)
        if (closed) {
            // close() ran while the future was being registered and may have missed it.
            future.cancel(true)
            inFlight.remove(future)
            throw SocketException("Socket closed")
        }
        try {
            return if (timeoutMillis > 0) {
                future.awaitBounded(timeoutMillis.toLong(), operation)
            } else {
                future.get()
            }
        } catch (e: CancellationException) {
            // close() cancelled the call.
            throw SocketException("Socket closed").apply { initCause(e) }
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("$operation interrupted").apply { initCause(e) }
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            when {
                cause is Error -> throw cause // an OutOfMemoryError etc must never be masked as an IOException
                closed -> throw SocketException("Socket closed").apply { initCause(cause) }
                cause is IOException -> throw cause
                else -> throw IOException("$operation failed on the adb stream", cause)
            }
        } finally {
            inFlight.remove(future)
        }
    }

    // Writes get the same handoff as reads: the stream sink has no timeout, so a write blocked on a
    // full TCP send buffer would otherwise park the caller forever.
    override fun getOutputStream(): OutputStream {
        val s = stream ?: throw SocketException("Socket is not connected")
        val delegate = s.sink.outputStream()
        return object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                // The worker writes from its own copy: an abandoned write can still flush later
                // and must not read an array the caller has recycled.
                val chunk = b.copyOfRange(off, off + len)
                boundedStreamCall("Write") {
                    delegate.write(chunk, 0, chunk.size)
                    delegate.flush()
                }
            }

            override fun flush() {
                boundedStreamCall("Flush") { delegate.flush() }
            }

            override fun close() = delegate.close()
        }
    }

    override fun isConnected(): Boolean = stream != null && !closed

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        inFlight.forEach { it.cancel(true) }
        stream?.let { closeOffThread(executor, it) }
        stream = null
    }

    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0) { "timeout can't be negative" }
        soTimeoutMillis = timeout
    }

    override fun getSoTimeout(): Int = soTimeoutMillis

    private companion object {
        // Swapped into the connect handoff by an abandoning caller so the worker's publish CAS fails.
        val ABANDONED = Any()
    }
}
