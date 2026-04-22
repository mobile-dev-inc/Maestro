package maestro.cli.stream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.copyAndClose
import maestro.cli.CliError
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

private const val LOG_PREFIX = "[simulator-server]"
private const val STDERR_TAIL_CAPACITY = 4096

class StreamServer private constructor(
    private val process: Process,
    private val stdinWriter: OutputStreamWriter,
    private val ktor: NettyApplicationEngine,
    private val httpClient: HttpClient,
) : AutoCloseable {

    override fun close() {
        runCatching { ktor.stop(gracePeriodMillis = 500, timeoutMillis = 2000) }
        runCatching { httpClient.close() }
        runCatching { stdinWriter.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    data class InputCommand(
        val kind: String,
        val action: String? = null,
        // simulator-server expects normalized device coordinates in [0.0, 1.0].
        val x: Double? = null,
        val y: Double? = null,
        val x2: Double? = null,
        val y2: Double? = null,
        val code: Int? = null,
        val name: String? = null,
        val dx: Double? = null,
        val dy: Double? = null,
        val text: String? = null,
        val orientation: String? = null,
    ) {
        fun toStdinLine(): String? = when (kind) {
            "touch" -> buildString {
                append("touch ").append(action).append(" ").append(x).append(",").append(y)
                if (x2 != null && y2 != null) append(" ").append(x2).append(",").append(y2)
            }
            "key" -> "key $action $code"
            "button" -> "button $action $name"
            "wheel" -> "wheel $x,$y --dx $dx --dy $dy"
            "rotate" -> "rotate $orientation"
            "paste" -> "paste ${text ?: ""}"
            else -> null
        }
    }

    companion object {
        fun start(
            simulatorServerBinary: File,
            platform: String,
            deviceId: String,
            browserPort: Int,
            verbose: Boolean = false,
        ): StreamServer {
            val process = ProcessBuilder(
                simulatorServerBinary.absolutePath,
                platform, "--id", deviceId,
            ).redirectErrorStream(false).start()

            val stdinWriter = OutputStreamWriter(process.outputStream)
            val stderrTail = StderrTail()
            drainInBackground(process.errorStream, "simulator-server-stderr") { line ->
                if (verbose) System.err.println("$LOG_PREFIX $line")
                stderrTail.append(line)
            }
            val streamUrl = awaitStreamReady(process, verbose, stderrTail)

            val httpClient = HttpClient(CIO) {
                // Stream runs indefinitely; disable read/request idle timeouts.
                install(HttpTimeout) {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 10_000
                }
            }
            val mapper = jacksonObjectMapper()

            val ktor = embeddedServer(Netty, port = browserPort) {
                routing {
                    staticResources("/", "web/stream") { default("index.html") }
                    get("/info") {
                        val json = mapper.writeValueAsString(mapOf("platform" to platform, "deviceId" to deviceId))
                        call.respondText(json, ContentType.Application.Json)
                    }
                    get("/stream.mjpeg") { proxyStream(httpClient, streamUrl) }
                    post("/input") { handleInput(mapper, stdinWriter) }
                }
            }.start(wait = false)

            return StreamServer(process, stdinWriter, ktor, httpClient)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.proxyStream(
    httpClient: HttpClient,
    streamUrl: String,
) {
    httpClient.prepareGet(streamUrl).execute { response ->
        val contentType = response.headers["Content-Type"]?.let { ContentType.parse(it) }
            ?: ContentType.Application.OctetStream
        call.respondBytesWriter(contentType = contentType, status = HttpStatusCode.OK) {
            response.bodyAsChannel().copyAndClose(this)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleInput(
    mapper: ObjectMapper,
    stdinWriter: OutputStreamWriter,
) {
    val cmd = mapper.readValue<StreamServer.InputCommand>(call.receiveText())
    val line = cmd.toStdinLine() ?: run {
        call.respond(HttpStatusCode.BadRequest, "unknown input kind: ${cmd.kind}")
        return
    }
    synchronized(stdinWriter) {
        stdinWriter.write(line)
        stdinWriter.write("\n")
        stdinWriter.flush()
    }
    call.respond(HttpStatusCode.NoContent)
}

private fun awaitStreamReady(process: Process, verbose: Boolean, stderrTail: StderrTail): String {
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
        val line = reader.readLine() ?: run {
            // Give the stderr drainer a moment to flush the fatal diagnostic
            // that usually arrives just before EOF on stdout.
            process.waitFor(500, TimeUnit.MILLISECONDS)
            throw CliError("simulator-server exited before announcing stream_ready${stderrTail.suffix()}")
        }
        if (verbose) println("$LOG_PREFIX $line")
        if (line.startsWith("stream_ready ")) {
            // Keep draining stdout so the subprocess's pipe buffer never fills,
            // whether we're logging or not.
            drainInBackground(reader, "simulator-server-stdout") { l ->
                if (verbose) println("$LOG_PREFIX $l")
            }
            return line.removePrefix("stream_ready ").trim()
        }
    }
    throw CliError("simulator-server did not announce stream_ready within 30s${stderrTail.suffix()}")
}

/**
 * Launches a daemon thread that reads [source] line by line and calls [onLine] for each.
 * Errors (e.g. pipe closed on subprocess exit) are intentionally swallowed.
 */
private fun drainInBackground(source: InputStream, name: String, onLine: (String) -> Unit) {
    drainInBackground(BufferedReader(InputStreamReader(source)), name, onLine)
}

private fun drainInBackground(reader: BufferedReader, name: String, onLine: (String) -> Unit) {
    Thread({
        runCatching { reader.forEachLine(onLine) }
    }, name).apply { isDaemon = true }.start()
}

/** Rolling tail of subprocess stderr, kept small and thread-safe for inclusion in error messages. */
private class StderrTail(private val capacity: Int = STDERR_TAIL_CAPACITY) {
    private val buf = StringBuilder()

    fun append(line: String) = synchronized(buf) {
        buf.append(line).append('\n')
        if (buf.length > capacity) buf.delete(0, buf.length - capacity)
    }

    fun suffix(): String {
        val tail = synchronized(buf) { buf.toString() }.trim()
        return if (tail.isEmpty()) "" else ". simulator-server stderr:\n$tail"
    }
}
