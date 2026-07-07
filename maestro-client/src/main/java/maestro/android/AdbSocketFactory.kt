package maestro.android

import dadb.AdbStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

// Fires soTimeout interrupts for reads parked inside a dadb stream. Single shared daemon thread;
// tasks only exist while a read with a non-zero soTimeout is in flight.
private val readWatchdog = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "AdbSocketReadWatchdog").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

private class AdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : Socket() {

    private var stream: AdbStream? = null
    @Volatile private var closed = false
    private var endpoint: InetSocketAddress? = null

    @Volatile private var soTimeoutMillis = 0

    // Guards the handoff between a blocked reader, the timeout watchdog, and close().
    private val readState = Any()
    private var readerThread: Thread? = null
    private var readTimedOut = false

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
        val delegate = s.source.inputStream()
        return object : InputStream() {
            override fun read(): Int = interruptibleRead { delegate.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = interruptibleRead { delegate.read(b, off, len) }
            override fun available(): Int = delegate.available()
            override fun close() = delegate.close()
        }
    }

    // dadb parks stream readers on a condition that AdbStream.close() never signals
    // (MessageQueue.stopListening removes the queue without a signalAll), so the only way to
    // enforce soTimeout, or to unblock a read on close(), is to interrupt the parked reader.
    private fun interruptibleRead(read: () -> Int): Int {
        synchronized(readState) {
            if (closed) throw SocketException("Socket closed")
            readerThread = Thread.currentThread()
            readTimedOut = false
        }
        val timeoutMillis = soTimeoutMillis
        val watchdog = if (timeoutMillis > 0) {
            readWatchdog.schedule({
                synchronized(readState) {
                    readTimedOut = true
                    readerThread?.interrupt()
                }
            }, timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } else {
            null
        }
        try {
            return read()
        } catch (t: Throwable) {
            synchronized(readState) {
                when {
                    readTimedOut -> throw SocketTimeoutException("Read timed out after ${timeoutMillis}ms")
                    closed -> throw SocketException("Socket closed")
                    t is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("Read interrupted").apply { initCause(t) }
                    }
                    else -> throw t
                }
            }
        } finally {
            watchdog?.cancel(false)
            synchronized(readState) {
                readerThread = null
                // Swallow an interrupt this socket raised itself so it can't leak into later code.
                if (readTimedOut || closed) Thread.interrupted()
            }
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
        synchronized(readState) {
            if (closed) return
            closed = true
            readerThread?.interrupt()
        }
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
