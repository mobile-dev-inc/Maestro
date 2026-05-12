package maestro.cli.mcp.visualizer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import maestro.cli.Dependencies
import maestro.cli.util.getFreePort
import maestro.device.DeviceService
import maestro.device.Platform
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal data class DeviceStreamState(
    val status: String,
    val platform: String? = null,
    val deviceId: String? = null,
    val streamUrl: String? = null,
    val message: String? = null,
)

private data class DeviceStreamTarget(val platform: String, val deviceId: String)

// Mirrors simulator-server's stdin protocol.
// Coordinates are normalized [0.0, 1.0]; key codes are HID Usage IDs.
internal data class DeviceInputCommand(
    val kind: String,
    val action: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val code: Int? = null,
    val name: String? = null,
) {
    fun toStdinLine(): String? = when (kind) {
        "touch" -> "touch $action $x,$y"
        "key" -> "key $action $code"
        "button" -> "button $action $name"
        else -> null
    }
}

internal class McpVisualizerServer private constructor(
    val port: Int,
    private val server: ApplicationEngine,
    private val scope: CoroutineScope,
    private val deviceStream: DeviceStream,
    private val eventRegistration: AutoCloseable,
) : AutoCloseable {

    override fun close() {
        eventRegistration.close()
        scope.cancel()
        deviceStream.close()
        server.stop(0, 0)
    }

    companion object {
        private fun readVisualizerHtml(): String =
            McpVisualizerServer::class.java.getResource("/mcp-visualizer/index.html")?.readText()
                ?: "<!doctype html><p>Visualizer resource missing — build the CLI first.</p>"

        fun start(port: Int? = null): McpVisualizerServer {
            val resolvedPort = port ?: getFreePort(host = "127.0.0.1")
            val mapper = jacksonObjectMapper()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val events = SseBroadcaster(mapper)
            val deviceStates = SseBroadcaster(mapper)
            val deviceStream = DeviceStream(onStateChange = { deviceStates.publish(it) })

            suspend fun ApplicationCall.respondJson(value: Any, status: HttpStatusCode = HttpStatusCode.OK) {
                respondText(mapper.writeValueAsString(value), ContentType.Application.Json, status)
            }

            suspend fun deviceStreamTargets(): List<DeviceStreamTarget> =
                withContext(Dispatchers.IO) {
                    DeviceService.listConnectedDevices()
                        .filter { it.platform != Platform.WEB }
                        .map { DeviceStreamTarget(it.platform.name.lowercase(), it.instanceId) }
                }

            val eventRegistration = McpVisualizerEvents.register { event ->
                scope.launch {
                    events.publish(event)
                    if (event is VisualizerEvent.MaestroConnected && event.platform != "web") {
                        deviceStream.start(event.platform, event.deviceId)
                    }
                }
            }

            val server = embeddedServer(
                port = resolvedPort,
                factory = Netty,
                configure = { shutdownTimeout = 0; shutdownGracePeriod = 0 },
                host = "127.0.0.1",
            ) {
                routing {
                    get("/") { call.respondText(readVisualizerHtml(), ContentType.Text.Html) }
                    get("/api/events/stream") { events.stream(call) }
                    get("/api/device/state") { deviceStates.stream(call, deviceStream.state) }
                    get("/api/device/targets") { call.respondJson(mapOf("devices" to deviceStreamTargets())) }
                    post("/api/device/start") {
                        data class Request(val platform: String? = null, val deviceId: String? = null)
                        val request = runCatching { mapper.readValue<Request>(call.receiveText()) }.getOrNull()
                        val platform = request?.platform
                        val deviceId = request?.deviceId
                        if (platform.isNullOrBlank() || deviceId.isNullOrBlank()) {
                            call.respondJson(mapOf("error" to "platform and deviceId are required"), HttpStatusCode.BadRequest)
                            return@post
                        }
                        call.respondJson(deviceStream.start(platform, deviceId))
                    }
                    post("/api/device/input") {
                        val command = runCatching { mapper.readValue<DeviceInputCommand>(call.receiveText()) }
                            .getOrNull()
                        val line = command?.toStdinLine()
                        if (line == null) {
                            call.respondJson(mapOf("error" to "invalid input command"), HttpStatusCode.BadRequest)
                            return@post
                        }
                        if (!deviceStream.sendInput(line)) {
                            call.respondJson(mapOf("error" to "no active device stream"), HttpStatusCode.Conflict)
                            return@post
                        }
                        call.respondJson(mapOf("ok" to true))
                    }
                }
            }.start(wait = false)

            System.err.println("mcp_visualizer_ready http://127.0.0.1:$resolvedPort")

            return McpVisualizerServer(
                port = resolvedPort,
                server = server,
                scope = scope,
                deviceStream = deviceStream,
                eventRegistration = eventRegistration,
            )
        }
    }
}

private class DeviceStream(
    private val onStateChange: suspend (DeviceStreamState) -> Unit,
) : AutoCloseable {
    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private val stdinLock = Any()

    @Volatile
    var state: DeviceStreamState = DeviceStreamState(status = "idle")
        private set

    suspend fun start(platform: String, deviceId: String): DeviceStreamState {
        val current = state
        if (current.platform == platform && current.deviceId == deviceId &&
            (current.status == "starting" || current.status == "streaming")) {
            return current
        }

        close()
        setState(DeviceStreamState(status = "starting", platform = platform, deviceId = deviceId))

        runCatching {
            Dependencies.installSimulatorServer()
            val p = ProcessBuilder(
                Dependencies.simulatorServerBinary().absolutePath,
                platform, "--id", deviceId,
            ).redirectErrorStream(false).start()
            process = p
            stdinWriter = OutputStreamWriter(p.outputStream)
            // Drain stderr so the child's pipe never fills, and surface its output for debugging.
            Thread({
                runCatching { BufferedReader(InputStreamReader(p.errorStream)).forEachLine { System.err.println("[simulator-server] $it") } }
            }, "mcp-visualizer-simulator-stderr").apply { isDaemon = true }.start()
            val streamUrl = awaitStreamReady(p)
            setState(DeviceStreamState(
                status = "streaming",
                platform = platform,
                deviceId = deviceId,
                streamUrl = streamUrl,
            ))
        }.onFailure { error ->
            close()
            setState(DeviceStreamState(
                status = "error",
                platform = platform,
                deviceId = deviceId,
                message = error.message ?: error.toString(),
            ))
        }

        return state
    }

    fun sendInput(line: String): Boolean {
        val writer = stdinWriter ?: return false
        synchronized(stdinLock) {
            return try {
                writer.write(line)
                writer.write("\n")
                writer.flush()
                true
            } catch (e: Throwable) {
                System.err.println("[mcp-visualizer] failed to write input to simulator-server: ${e.message}")
                false
            }
        }
    }

    override fun close() {
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        val p = process ?: return
        p.destroy()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        process = null
    }

    private suspend fun setState(next: DeviceStreamState) {
        state = next
        onStateChange(next)
    }

    private fun awaitStreamReady(process: Process): String {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: error("simulator-server exited before announcing stream_ready")
            System.err.println("[simulator-server] $line")
            if (line.startsWith("stream_ready ")) {
                // Drain the rest in the background so the child's stdout pipe doesn't block.
                Thread({ runCatching { reader.forEachLine { System.err.println("[simulator-server] $it") } } },
                    "mcp-visualizer-simulator-stdout").apply { isDaemon = true }.start()
                return line.removePrefix("stream_ready ").trim()
            }
        }
        error("simulator-server did not announce stream_ready within 30s")
    }
}

private class SseBroadcaster(private val mapper: ObjectMapper) {
    private val clients = CopyOnWriteArrayList<SseClient>()

    suspend fun publish(value: Any) {
        val message = "data: ${mapper.writeValueAsString(value)}\n\n"
        clients.toList().forEach { client ->
            try {
                client.write(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RuntimeException("Error writing SSE message", e).also { e.printStackTrace() }
                clients.remove(client)
            }
        }
    }

    suspend fun stream(call: ApplicationCall, initialValue: Any? = null) {
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val client = SseClient(this)
            clients.add(client)
            try {
                if (initialValue != null) {
                    client.write("data: ${mapper.writeValueAsString(initialValue)}\n\n")
                }
                awaitCancellation()
            } finally {
                clients.remove(client)
            }
        }
    }
}

private class SseClient(private val channel: ByteWriteChannel) {
    private val mutex = Mutex()

    suspend fun write(message: String) {
        mutex.withLock {
            channel.writeStringUtf8(message)
            channel.flush()
        }
    }
}
