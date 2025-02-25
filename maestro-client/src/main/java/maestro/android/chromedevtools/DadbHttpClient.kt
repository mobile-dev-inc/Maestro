package maestro.android.chromedevtools

import dadb.AdbStream
import dadb.Dadb
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import java.net.URI

/**
 * Minimal container for an HTTP response: the status line, status code, headers, and body.
 */
data class HttpResponse(
    val statusLine: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteString
)

/**
 * A very simple HTTP client that connects via [Dadb] to the specified [destination].
 *
 * Usage:
 *
 *  val client = DadbHttpClient(
 *      dadb = myDadb,
 *      destination = "localabstract:some-unix-socket"
 *  )
 *
 *  val response = client.get("http://example.com/some/path?query=123")
 *  println("Status: ${response.statusLine}")
 *  println("Body: ${response.body.utf8()}")
 */
class DadbHttpClient(
    private val dadb: Dadb,
) {

    /**
     * Send a single GET request to [url] via adb [destination] and return the entire [HttpResponse].
     *
     * Opens (and closes) a new [AdbStream] for each request.
     */
    fun get(destination: String, url: String): HttpResponse {
        // Parse the URL
        val uri = URI(url)
        require(uri.scheme.equals("http", ignoreCase = true)) {
            "Only http:// is supported in this example (got ${uri.scheme})"
        }

        val host = uri.host ?: throw IllegalArgumentException("URL must have a host.")
        val port = if (uri.port == -1) 80 else uri.port
        // Build the path + optional query
        val pathWithQuery = buildString {
            append(if (uri.path.isNullOrEmpty()) "/" else uri.path)
            if (!uri.query.isNullOrEmpty()) {
                append("?").append(uri.query)
            }
        }

        // Open an AdbStream to the provided destination
        dadb.open(destination).use { stream ->
            val source: BufferedSource = stream.source
            val sink: BufferedSink = stream.sink

            // Write a simple GET request
            sink.writeUtf8("GET $pathWithQuery HTTP/1.1\r\n")
            sink.writeUtf8("Connection: close\r\n")
            sink.writeUtf8("Host: $host:$port\r\n")
            sink.writeUtf8("\r\n")
            sink.flush()

            // Read the status line (e.g., "HTTP/1.1 200 OK")
            val statusLine = source.readUtf8LineStrict()
            // Extract status code from status line
            val tokens = statusLine.split(" ")
            val statusCode = tokens.getOrNull(1)?.toIntOrNull() ?: -1

            // Read headers until blank line
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) {
                    break
                }
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val headerName = parts[0].trim()
                    val headerValue = parts[1].trim()
                    headers[headerName] = headerValue
                }
            }

            // If we have a Content-Length, read that many bytes. Otherwise read until EOF.
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: -1
            val body = if (contentLength >= 0) {
                source.readByteString(contentLength.toLong())
            } else {
                source.readByteString()
            }

            return HttpResponse(
                statusLine = statusLine,
                statusCode = statusCode,
                headers = headers,
                body = body
            )
        }
    }
}

/**
 * Example usage (ensure you have a valid Dadb instance and a destination that supports HTTP).
 */
fun main() {
    // Acquire a Dadb device. Adjust discovery or create a Dadb instance as needed.
    val dadb = Dadb.discover() ?: error("No devices found")

    dadb.use { d ->
        // Suppose there's a process listening for HTTP requests on localabstract:some_unix_socket
        val client = DadbHttpClient(d)

        // Make an HTTP GET request
        val response = client.get("localabstract:webview_devtools_remote_5664", "http://localhost/json")

        println("Status line: ${response.statusLine}")
        println("Status code: ${response.statusCode}")
        println("Headers:")
        response.headers.forEach { (k, v) ->
            println("  $k: $v")
        }
        println("Body:")
        println(response.body.utf8())
    }
}
