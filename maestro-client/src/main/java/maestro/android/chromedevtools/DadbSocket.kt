package maestro.android.chromedevtools

import dadb.AdbStream
import dadb.Dadb
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

fun OkHttpClient.Builder.dadb(dadb: Dadb): OkHttpClient.Builder {
    dns(DadbDns())
    socketFactory(DadbSocketFactory(dadb))
    return this
}

class DadbDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.endsWith(".adb")) throw IllegalArgumentException("Invalid hostname. Eg. tcp.8000.adb, localabstract.chrome_devtools_remote.adb")
        return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
    }
}

class DadbSocketFactory(private val dadb: Dadb) : SocketFactory() {

    override fun createSocket(): Socket {
        return DadbStreamSocket(dadb)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int
    ): Socket {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket {
        throw UnsupportedOperationException("Not implemented")
    }

}

class DadbStreamSocket(private val dadb: Dadb) : Socket() {

    private var closed = false
    private var adbStream: AdbStream? = null

    override fun getInputStream(): InputStream {
        val adbStream = this.adbStream ?: throw SocketException("Socket is not connected")
        return adbStream.source.inputStream()
    }

    override fun getOutputStream(): OutputStream {
        val adbStream = this.adbStream ?: throw SocketException("Socket is not connected")
        return object : FilterOutputStream(adbStream.sink.outputStream()) {

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

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (endpoint !is InetSocketAddress) throw UnsupportedOperationException("Endpoint must be a InetSocketAddress: $endpoint (${endpoint::class})")
        val destination = endpoint.hostName.removeSuffix(".adb").replace(".", ":")
        this.adbStream = dadb.open(destination)
    }

    override fun isClosed() = closed

    override fun close() {
        if (isClosed()) return
        adbStream?.close()
        adbStream = null
        closed = true
    }

    override fun setSoTimeout(timeout: Int) = Unit

    override fun isInputShutdown() = false

    override fun isOutputShutdown() = false


    override fun connect(endpoint: SocketAddress?) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun bind(bindpoint: SocketAddress?) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getInetAddress(): InetAddress {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getLocalAddress(): InetAddress {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getPort(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getLocalPort(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getRemoteSocketAddress(): SocketAddress {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getLocalSocketAddress(): SocketAddress {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getChannel(): SocketChannel {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setTcpNoDelay(on: Boolean) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getTcpNoDelay(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setSoLinger(on: Boolean, linger: Int) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getSoLinger(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun sendUrgentData(data: Int) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setOOBInline(on: Boolean) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getOOBInline(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getSoTimeout(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setSendBufferSize(size: Int) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getSendBufferSize(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setReceiveBufferSize(size: Int) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getReceiveBufferSize(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setKeepAlive(on: Boolean) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getKeepAlive(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setTrafficClass(tc: Int) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getTrafficClass(): Int {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setReuseAddress(on: Boolean) {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun getReuseAddress(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun shutdownInput() {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun shutdownOutput() {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun isConnected(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun isBound(): Boolean {
        throw UnsupportedOperationException("Not implemented")
    }

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) {
        throw UnsupportedOperationException("Not implemented")
    }
}