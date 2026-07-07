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
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class AdbSocketFactory private constructor(
    private val opener: (host: String, port: Int) -> AdbStream,
    private val bounded: Boolean,
) : SocketFactory() {

    companion object {
        /**
         * Raw passthrough: connect, reads, writes, and close all run on the caller's thread with
         * no timeout enforcement and no executor involvement. For the gRPC channel, which owns
         * its own deadlines and whose sockets live for the whole driver session.
         */
        fun raw(opener: (host: String, port: Int) -> AdbStream): AdbSocketFactory =
            AdbSocketFactory(opener, bounded = false)

        /**
         * Bounded worker handoff: blocking dadb calls run on pooled worker threads so the
         * caller's wait is bounded (connect timeout, soTimeout) and released by close(). For the
         * devtools client, whose webview endpoints can accept a connection and never respond.
         */
        fun bounded(opener: (host: String, port: Int) -> AdbStream): AdbSocketFactory =
            AdbSocketFactory(opener, bounded = true)
    }

    override fun createSocket(): Socket = if (bounded) BoundedAdbSocket(opener) else RawAdbSocket(opener)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket(host?.hostAddress, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)
}

// dadb gives no way to bound or abort a blocked stream read: a reader parks either in
// MessageQueue.take's Condition.await (interruptible, but AdbStream.close() never signals it,
// MessageQueue.stopListening removes the queue without a signalAll) or, if it wins the transport
// read lock, in a raw java.net.Socket read that JDK 17 does NOT release on Thread.interrupt().
// Blocking dadb calls therefore run on these daemon worker threads while the caller waits with a
// timeout. Cancelling a worker releases a Condition.await park immediately; a worker stuck in the
// raw-socket park is abandoned and dies once the transport produces bytes or the adb connection
// closes, but the caller is unblocked either way.
//
// The pool is capped: against a fully wedged transport the cap turns "abandoned threads grow
// without bound" into "the next call fails fast" (awaitBlockingCall maps the rejection to an
// IOException the devtools client degrades on). 16 is generous for one capture: webviews are
// fetched with one HTTP listing and one websocket exchange each, largely sequentially.
internal const val ADB_IO_POOL_MAX = 16
private val adbIoThreadCount = AtomicInteger()
private val adbIoExecutor = ThreadPoolExecutor(
    0,
    ADB_IO_POOL_MAX,
    60L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
) { runnable ->
    Thread(runnable, "AdbSocketIo-${adbIoThreadCount.incrementAndGet()}").apply { isDaemon = true }
}

// Closing a dadb stream can park forever: AdbStreamImpl.close() sends CLSE through a raw,
// non-interruptible socket write under the connection-wide sink monitor. Bounded sockets
// therefore never close streams on the caller thread. Fall back to a throwaway daemon thread
// when the pool is exhausted, because close() must not block and must not be dropped.
private fun closeOffThread(stream: AdbStream) {
    val close = Runnable { runCatching { stream.close() } }
    try {
        adbIoExecutor.execute(close)
    } catch (e: RejectedExecutionException) {
        Thread(close, "AdbStreamClose").apply { isDaemon = true }.start()
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
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getSoTimeout(): Int = 0
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

/**
 * Plain passthrough onto the dadb stream: every call runs on the caller's thread, exactly like
 * the pre-MA-4111 socket. The gRPC channel uses this variant; it multiplexes one long-lived
 * connection and enforces its own deadlines, so a per-call thread hop would only add overhead.
 */
private class RawAdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : BaseAdbSocket() {

    private var stream: AdbStream? = null
    private var closed = false

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (endpoint !is InetSocketAddress) throw UnsupportedOperationException("Endpoint must be InetSocketAddress")
        this.endpoint = endpoint
        stream = opener(endpoint.hostString, endpoint.port)
    }

    override fun connect(endpoint: SocketAddress) {
        connect(endpoint, 0)
    }

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
 * Runs every blocking dadb call on [adbIoExecutor] so the caller's wait is bounded by the
 * connect timeout / soTimeout and can be released by [close] even when the worker is parked in
 * an un-abortable dadb read. The devtools client uses this variant (see MA-4111).
 */
private class BoundedAdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : BaseAdbSocket() {

    @Volatile private var stream: AdbStream? = null
    @Volatile private var closed = false

    // Set once any stream call times out: the abandoned worker may still touch the stream (consume
    // bytes into its discarded buffer, flush its bytes late), so later calls would silently lose or
    // interleave data. Failing them fast is the honest "socket broken" answer.
    @Volatile private var broken = false

    @Volatile private var soTimeoutMillis = 0

    // Blocking dadb calls currently parked on worker threads; close() cancels them so their
    // callers are released.
    private val inFlight = ConcurrentHashMap.newKeySet<Future<*>>()

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (endpoint !is InetSocketAddress) throw UnsupportedOperationException("Endpoint must be InetSocketAddress")
        this.endpoint = endpoint
        // The dadb OPEN handshake blocks on the same transport reads as stream reads, so it gets
        // the same worker handoff. The timeout comes from the caller (OkHttp passes its
        // connectTimeout); 0 still means unbounded, but close() can then release the caller.
        //
        // The worker publishes the opened stream before returning and abandonment claims it with
        // getAndSet, so a handshake that completes after the caller stopped waiting (timeout,
        // interrupt, close) is closed by exactly one side no matter how the two interleave.
        // Checking the interrupt flag on the worker is NOT enough: cancel(true) cannot interrupt
        // a future that completed a moment after the caller's deadline, and dadb internals can
        // consume the flag before the worker returns.
        val abandoned = AtomicBoolean(false)
        val published = AtomicReference<AdbStream?>()
        fun closeAbandonedStream() {
            published.getAndSet(null)?.let { closeOffThread(it) }
        }
        val opened = try {
            awaitBlockingCall(timeout, "Connect to ${endpoint.hostString}") {
                val s = opener(endpoint.hostString, endpoint.port)
                published.set(s)
                if (abandoned.get()) {
                    closeAbandonedStream()
                    throw InterruptedException("Connect to ${endpoint.hostString} abandoned")
                }
                s
            }
        } catch (e: Throwable) {
            abandoned.set(true)
            closeAbandonedStream()
            throw e
        }
        stream = opened
        if (closed) {
            // close() raced with connect() and could not see the stream yet.
            closeOffThread(opened)
            stream = null
            throw SocketException("Socket closed")
        }
    }

    override fun connect(endpoint: SocketAddress) {
        connect(endpoint, 0)
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

    // Bounds a stream read/write by soTimeout and marks the socket broken once one times out.
    private fun <T> boundedStreamCall(operation: String, block: () -> T): T {
        if (broken) throw SocketException("Socket is broken: an earlier read or write timed out")
        try {
            return awaitBlockingCall(soTimeoutMillis, operation, block)
        } catch (e: SocketTimeoutException) {
            broken = true
            throw e
        }
    }

    // Runs a blocking dadb call on a worker thread and bounds the caller's wait, because neither
    // soTimeout nor close() can otherwise release a caller parked inside dadb (see adbIoExecutor).
    private fun <T> awaitBlockingCall(timeoutMillis: Int, operation: String, block: () -> T): T {
        if (closed) throw SocketException("Socket closed")
        val future = try {
            adbIoExecutor.submit(Callable { block() })
        } catch (e: RejectedExecutionException) {
            // Every worker is parked against a wedged transport; fail fast instead of queueing.
            throw IOException("$operation failed: all $ADB_IO_POOL_MAX adb I/O workers are busy", e)
        }
        inFlight.add(future)
        if (closed) {
            // close() ran while the future was being registered and may have missed it.
            future.cancel(true)
            inFlight.remove(future)
            throw SocketException("Socket closed")
        }
        try {
            return if (timeoutMillis > 0) {
                future.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            } else {
                future.get()
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("$operation timed out after ${timeoutMillis}ms")
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

    // Writes get the same handoff as reads: on the adb-server path (Dadb.list / AdbServer.createDadb)
    // the stream sink is Okio.sink(socket).buffer() with no timeout configured, so a write that
    // blocks on a full TCP send buffer would otherwise park the caller forever. (The Dadb.create
    // path has a 10s okio sink timeout, but its enforcement depends on the shared okio watchdog
    // thread staying healthy, and the AdbWriter sink monitor is not covered by it at all.)
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
        stream?.let { closeOffThread(it) }
        stream = null
    }

    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0) { "timeout can't be negative" }
        soTimeoutMillis = timeout
    }

    override fun getSoTimeout(): Int = soTimeoutMillis
}
