package maestro.android

import dadb.AdbStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
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

private class AdbSocket(private val opener: (host: String, port: Int) -> AdbStream) : Socket() {

    private var stream: AdbStream? = null
    private var closed = false
    private var endpoint: InetSocketAddress? = null

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
