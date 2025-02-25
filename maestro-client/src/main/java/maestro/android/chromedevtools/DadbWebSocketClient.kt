package maestro.android.chromedevtools

import dadb.AdbStream
import dadb.Dadb
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.use
import java.io.Closeable
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.experimental.xor
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * AdbStream represents an underlying transport that provides a [BufferedSource] and [BufferedSink].
 * We assume it's already connected at the TCP-like level so we can perform an HTTP handshake.
 *
 * interface AdbStream : AutoCloseable {
 *     val source: BufferedSource
 *     val sink: BufferedSink
 * }
 */

/**
 * A minimal set of WebSocket opcodes from RFC 6455
 */
object Opcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

/**
 * A sealed class representing different WebSocket frames.
 */
sealed class Frame(val opcode: Int, open val payload: ByteString) {
    data class TextFrame(override val payload: ByteString) : Frame(Opcode.TEXT, payload)
    data class BinaryFrame(override val payload: ByteString) : Frame(Opcode.BINARY, payload)
    data class CloseFrame(override val payload: ByteString) : Frame(Opcode.CLOSE, payload)
    data class PingFrame(override val payload: ByteString) : Frame(Opcode.PING, payload)
    data class PongFrame(override val payload: ByteString) : Frame(Opcode.PONG, payload)
    data class ContinuationFrame(override val payload: ByteString) : Frame(Opcode.CONTINUATION, payload)
}

/**
 * A simple WebSocket client that:
 * 1) Performs the handshake.
 * 2) Sends and receives WebSocket frames over the provided AdbStream.
 */
class DadbWebSocketClient(
    private val adbStream: AdbStream,
    private val url: String
) : Closeable {

    private val source: BufferedSource = adbStream.source
    private val sink: BufferedSink = adbStream.sink

    // Whether the handshake has completed
    private var isConnected = false

    /**
     * Parse the URL and perform a WebSocket handshake.
     * Throws an exception if the handshake fails.
     */
    fun connect() {
        val uri = URI(url)
        require(uri.scheme.equals("ws", ignoreCase = true)) {
            "Only ws:// is supported in this example (got ${uri.scheme})"
        }

        val host = uri.host ?: throw IllegalArgumentException("URL must have a host.")
        // For a simple example, if port is missing, default to 80 for ws
        val port = if (uri.port == -1) 80 else uri.port
        val pathWithQuery = buildString {
            append(if (uri.path.isNullOrEmpty()) "/" else uri.path)
            if (!uri.query.isNullOrEmpty()) {
                append("?").append(uri.query)
            }
        }

        // Generate a Sec-WebSocket-Key
        val wsKey = generateWebSocketKey()

        // Send the HTTP upgrade request
        sink.writeUtf8("GET $pathWithQuery HTTP/1.1\r\n")
        sink.writeUtf8("Host: $host:$port\r\n")
        sink.writeUtf8("Upgrade: websocket\r\n")
        sink.writeUtf8("Connection: Upgrade\r\n")
        sink.writeUtf8("Sec-WebSocket-Key: $wsKey\r\n")
        sink.writeUtf8("Sec-WebSocket-Version: 13\r\n")
        sink.writeUtf8("\r\n")
        sink.flush()

        // Read the response status line
        val statusLine = source.readUtf8LineStrict()
        if (!statusLine.contains("101")) {
            throw IllegalStateException("Failed to switch protocols: $statusLine")
        }

        // Read headers until an empty line
        var secWebSocketAccept: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) break
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val headerName = parts[0].trim()
                val headerValue = parts[1].trim()
                if (headerName.equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                    secWebSocketAccept = headerValue
                }
            }
        }

        // Validate the Sec-WebSocket-Accept
        val expectedAccept = computeAcceptKey(wsKey)
        if (secWebSocketAccept == null || secWebSocketAccept != expectedAccept) {
            throw IllegalStateException(
                "Invalid Sec-WebSocket-Accept. expected=$expectedAccept, got=$secWebSocketAccept"
            )
        }

        isConnected = true
    }

    /**
     * Read a single WebSocket frame from the stream.
     * This blocks until a frame is available or an error occurs.
     */
    fun readFrame(): Frame {
        // The first byte: FIN bit (1 bit), RSV1-3 (3 bits), Opcode (4 bits)
        val b0 = source.readByte().toInt() and 0xFF
        val fin = (b0 shr 7) and 1
        val opcode = b0 and 0x0F

        // The second byte: Mask bit (1 bit), Payload length (7 bits)
        val b1 = source.readByte().toInt() and 0xFF
        val maskFlag = (b1 shr 7) and 1
        var payloadLen = (b1 and 0x7F).toLong()

        when (payloadLen) {
            126L -> {
                // 126 means the next 2 bytes define the length
                payloadLen = source.readShort().toLong() and 0xFFFF
            }
            127L -> {
                // 127 means the next 8 bytes define the length
                payloadLen = source.readLong()
            }
        }

        // If the mask bit is set, we need to read the mask key (4 bytes)
        val maskKey = if (maskFlag == 1) {
            source.readByteArray(4)
        } else {
            null
        }

        // Read the payload
        val payload = source.readByteString(payloadLen)

        // Unmask if needed
        val realPayload = if (maskKey != null) unmask(payload, maskKey) else payload

        return when (opcode) {
            Opcode.TEXT -> Frame.TextFrame(realPayload)
            Opcode.BINARY -> Frame.BinaryFrame(realPayload)
            Opcode.CLOSE -> Frame.CloseFrame(realPayload)
            Opcode.PING -> Frame.PingFrame(realPayload)
            Opcode.PONG -> Frame.PongFrame(realPayload)
            Opcode.CONTINUATION -> Frame.ContinuationFrame(realPayload)
            else -> {
                // For unknown opcodes in a robust implementation, you might close the connection
                throw IllegalStateException("Unknown opcode: $opcode")
            }
        }
    }

    /**
     * Send a WebSocket frame over the connection.
     * By RFC 6455, clients MUST set the mask bit on every frame they send.
     */
    fun sendFrame(frame: Frame) {
        if (!isConnected) {
            throw IllegalStateException("Not connected; did you call connect() successfully?")
        }

        // Determine the opcode
        val opcode = frame.opcode
        val payload = frame.payload

        // Set FIN = 1 (we don't handle fragmentation in this simple example)
        val b0 = (0x80 or (opcode and 0x0F)).toByte()

        // We must mask the frame from a client
        val maskKey = ByteArray(4).apply { Random.nextBytes(this) }
        val maskedPayload = mask(payload, maskKey)
        val length = maskedPayload.size.toLong()

        val header = mutableListOf<Byte>()
        header.add(b0)

        // Now, build the second byte + extended length if needed
        when {
            length < 126 -> {
                // second byte: 10000000 (0x80 for mask) + length
                header.add((0x80 or (length.toInt() and 0x7F)).toByte())
            }
            length <= 0xFFFF -> {
                // second byte: 10000000 + 126
                header.add((0x80 or 126).toByte())
                // 16-bit length
                header.add(((length ushr 8) and 0xFF).toByte())
                header.add((length and 0xFF).toByte())
            }
            else -> {
                // second byte: 10000000 + 127
                header.add((0x80 or 127).toByte())
                // 64-bit length
                for (shift in (7 downTo 0)) {
                    header.add(((length ushr (shift * 8)) and 0xFF).toByte())
                }
            }
        }

        // Write header
        sink.write(header.toByteArray())

        // Write masking key
        sink.write(maskKey)

        // Write masked payload
        sink.write(maskedPayload)

        sink.flush()
    }

    /**
     * Convenience method to send a text message.
     */
    fun sendText(text: String) {
        val payload = text.encodeUtf8()
        sendFrame(Frame.TextFrame(payload))
    }

    /**
     * Convenience method to send a ping.
     */
    fun sendPing(payload: ByteString = ByteString.EMPTY) {
        sendFrame(Frame.PingFrame(payload))
    }

    /**
     * Convenience method to send a pong.
     */
    fun sendPong(payload: ByteString = ByteString.EMPTY) {
        sendFrame(Frame.PongFrame(payload))
    }

    /**
     * Convenience method to send a close frame.
     */
    fun sendClose(payload: ByteString = ByteString.EMPTY) {
        sendFrame(Frame.CloseFrame(payload))
    }

    /**
     * Closes the underlying AdbStream.
     */
    override fun close() {
        adbStream.close()
    }

    /**
     * Utility to generate the random base64 key for the handshake.
     */
    private fun generateWebSocketKey(): String {
        val randomBytes = ByteArray(16).also { Random.nextBytes(it) }
        return Base64.getEncoder().encodeToString(randomBytes)
    }

    /**
     * Compute the Sec-WebSocket-Accept from the key, as per RFC 6455.
     */
    private fun computeAcceptKey(key: String): String {
        val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(key.toByteArray(Charsets.UTF_8))
        sha1.update(magicString.toByteArray(Charsets.UTF_8))
        val result = sha1.digest()
        return Base64.getEncoder().encodeToString(result)
    }

    /**
     * Apply mask to a payload. For client -> server frames, we must always mask.
     */
    private fun mask(payload: ByteString, key: ByteArray): ByteArray {
        val bytes = payload.toByteArray()
        for (i in bytes.indices) {
            bytes[i] = bytes[i] xor key[i % 4]
        }
        return bytes
    }

    /**
     * Unmask a masked payload from the server (rare, since servers usually do not mask).
     */
    private fun unmask(payload: ByteString, key: ByteArray): ByteString {
        val bytes = payload.toByteArray()
        for (i in bytes.indices) {
            bytes[i] = bytes[i] xor key[i % 4]
        }
        return ByteString.of(*bytes)
    }
}


fun main() {
    (Dadb.discover() ?: throw IllegalStateException("No devices found")).use { dadb ->
        measureTimeMillis {
            dadb.open("localabstract:chrome_devtools_remote").use { stream ->
                DadbWebSocketClient(stream, "ws://localhost:9222/devtools/page/14").use { client ->
                    client.connect()
                    client.sendText("""
                        {
                            "id": 1,
                            "method": "DOMSnapshot.getSnapshot",
                            "params": {
                                "depth": -1
                            }
                        }
                    """.trimIndent())
                    println(client.readFrame().payload.string(Charsets.UTF_8))
                }
            }
        }.also { println(it) }
    }
}