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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class AdbSocketFactory(private val opener: (host: String, port: Int) -> AdbStream) : SocketFactory() {

    override fun createSocket(): Socket = AdbSocket(opener)

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
private val adbIoThreadCount = AtomicInteger()
private val adbIoExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "AdbSocketIo-${adbIoThreadCount.incrementAndGet()}").apply { isDaemon = true }
}

private class AdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : Socket() {

    @Volatile private var stream: AdbStream? = null
    @Volatile private var closed = false
    private var endpoint: InetSocketAddress? = null

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
        val opened = awaitBlockingCall(timeout, "Connect to ${endpoint.hostString}") {
            val s = opener(endpoint.hostString, endpoint.port)
            if (Thread.currentThread().isInterrupted) {
                // The caller timed out or closed the socket and abandoned this handshake.
                s.close()
                throw InterruptedException("Connect to ${endpoint.hostString} abandoned")
            }
            s
        }
        stream = opened
        if (closed) {
            // close() raced with connect() and could not see the stream yet.
            opened.close()
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
                val n = awaitBlockingCall(soTimeoutMillis, "Read") { delegate.read(chunk, 0, len) }
                if (n > 0) System.arraycopy(chunk, 0, b, off, n)
                return n
            }

            override fun available(): Int = delegate.available()
            override fun close() = delegate.close()
        }
    }

    // Runs a blocking dadb call on a worker thread and bounds the caller's wait, because neither
    // soTimeout nor close() can otherwise release a caller parked inside dadb (see adbIoExecutor).
    private fun <T> awaitBlockingCall(timeoutMillis: Int, operation: String, block: () -> T): T {
        if (closed) throw SocketException("Socket closed")
        val future = adbIoExecutor.submit(Callable { block() })
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
                closed -> throw SocketException("Socket closed").apply { initCause(cause) }
                cause is IOException -> throw cause
                else -> throw IOException("$operation failed on the adb stream", cause)
            }
        } finally {
            inFlight.remove(future)
        }
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
        closed = true
        inFlight.forEach { it.cancel(true) }
        stream?.close()
        stream = null
    }

    // Address/port info (used by gRPC OkHttp transport for logging)
    override fun getInetAddress(): InetAddress = endpoint?.address ?: InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = endpoint?.port ?: 0
    override fun getLocalPort(): Int = 0
    override fun getRemoteSocketAddress(): SocketAddress = endpoint ?: InetSocketAddress(0)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(0)
    override fun isBound(): Boolean = true

    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0) { "timeout can't be negative" }
        soTimeoutMillis = timeout
    }

    override fun getSoTimeout(): Int = soTimeoutMillis

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
