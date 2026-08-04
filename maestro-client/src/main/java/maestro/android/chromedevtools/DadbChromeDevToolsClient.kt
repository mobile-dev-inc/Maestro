package maestro.android.chromedevtools

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.DeviceConnectionException
import maestro.Maestro
import maestro.TreeNode
import maestro.android.AdbSocketFactory
import maestro.android.AndroidDeviceConnection
import maestro.android.boundedAdbCall
import maestro.android.newAdbIoExecutor
import maestro.utils.HttpClient
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.use
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

private data class RuntimeResponse<T>(
    val result: RemoteObject<T>
)

private data class RemoteObject<T>(
    val type: String,
    val value: T,
)

data class WebViewInfo(
    val socketName: String,
    val webSocketDebuggerUrl: String,
    val visible: Boolean,
    val attached: Boolean,
    val empty: Boolean,
    val screenX: Int,
    val screenY: Int,
    val width: Int,
    val height: Int,
)

private data class WebViewResponse(
    val description: String,
    val webSocketDebuggerUrl: String,
)

private data class WebViewDescription(
    val visible: Boolean,
    val attached: Boolean,
    val empty: Boolean,
    val screenX: Int,
    val screenY: Int,
    val width: Int,
    val height: Int,
)

private data class DevToolsResponse<T>(
    val id: Int,
    val result: T,
)

// `getByAddress(hostname, bytes)` preserves the hostname through OkHttp's
// `InetSocketAddress`, so `AdbSocketFactory` opens `localabstract:<webview-socket>`
// instead of `localabstract:localhost` (which `getLoopbackAddress()` would yield).
internal class DummyDns : Dns {
    override fun lookup(hostname: String) = listOf(
        java.net.InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1))
    )
}

class DadbChromeDevToolsClient internal constructor(
    private val connection: AndroidDeviceConnection,
    // Test seams; production uses the defaults via the public constructor. [stepTimeoutMillis]
    // bounds each devtools step (the per-webview websocket wait and the discovery shell call);
    // [httpReadTimeoutMillis] overrides the okhttp read timeout that bounds the /json listing
    // reads (null keeps HttpClient.build's default).
    private val stepTimeoutMillis: Long,
    httpReadTimeoutMillis: Long?,
) : ChromeDevToolsClient {

    constructor(connection: AndroidDeviceConnection) : this(
        connection,
        stepTimeoutMillis = DEVTOOLS_STEP_TIMEOUT_MS,
        httpReadTimeoutMillis = null,
    )

    private val json = jacksonObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)

    // Bounded worker pool for every dadb call this client makes (webview sockets and discovery),
    // owned here so a wedged device cannot starve captures on other devices in the process. Shut
    // down in close().
    private val adbIoExecutor = newAdbIoExecutor()

    private val okhttp = HttpClient.build("DadbChromeDevToolsClient").newBuilder()
        .socketFactory(AdbSocketFactory.bounded(adbIoExecutor) { host, _ -> connection.open("localabstract:$host") })
        .dns(DummyDns())
        .apply { httpReadTimeoutMillis?.let { readTimeout(it, TimeUnit.MILLISECONDS) } }
        .build()

    private val script = Maestro::class.java.getResourceAsStream("/maestro-web.js")?.let {
        it.bufferedReader().use { br ->
            br.readText()
        }
    } ?: error("Could not read maestro web script")

    override fun close() {
        okhttp.dispatcher.executorService.shutdown()
        okhttp.connectionPool.evictAll()
        okhttp.cache?.close()
        adbIoExecutor.shutdown()
    }

    override fun getWebViewTreeNodes(): List<TreeNode> {
        return getWebViewInfos()
            .filter { it.visible }
            .mapNotNull { info ->
                degradeTo(null, "Failed to retrieve WebView hierarchy from chrome devtools: ${info.socketName} ${info.webSocketDebuggerUrl}") {
                    evaluateScript<RuntimeResponse<TreeNode>>(info.socketName, info.webSocketDebuggerUrl, "$script; maestro.viewportX = ${info.screenX}; maestro.viewportY = ${info.screenY}; maestro.viewportWidth = ${info.width}; maestro.viewportHeight = ${info.height}; window.maestro.getContentDescription();").result.value
                }
            }
    }

    // Degrade policy for every step of a webview capture. A transport wedge is a benign per-step skip
    // and degrades to [fallback]: a timed-out websocket wait (TimeoutException) or a dead / timed-out
    // / adbd-refused adb socket (IOException). Everything else fails loudly rather than yield a
    // native-only hierarchy, which surfaces only as untappable-element flake. Handled explicitly ahead
    // of the IOException branch: DeviceConnectionException (a dead device must abort, not degrade) and
    // InterruptedException (re-assert the flag and let cancellation land).
    private inline fun <T> degradeTo(fallback: T, message: String, block: () -> T): T =
        try {
            block()
        } catch (e: DeviceConnectionException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: TimeoutException) {
            logger.warn(message, e)
            fallback
        } catch (e: IOException) {
            // A cancelled read parked in okhttp surfaces here as an InterruptedIOException with the
            // flag set; propagate it so the capture stops. A socket-timeout wedge is the same type but
            // leaves the flag clear, so it still degrades.
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw e
            }
            logger.warn(message, e)
            fallback
        }

    inline fun <reified T> evaluateScript(socketName: String, webSocketDebuggerUrl: String, script: String) = makeRequest<T>(
        socketName = socketName,
        webSocketDebuggerUrl = webSocketDebuggerUrl,
        method = "Runtime.evaluate",
        params = mapOf(
            "expression" to script,
            "returnByValue" to true,
        ),
    )

    inline fun <reified T> makeRequest(socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T {
        val resultTypeReference = object : TypeReference<T>() {}
        return makeRequest(resultTypeReference, socketName, webSocketDebuggerUrl, method, params)
    }

    fun <T> makeRequest(resultTypeReference: TypeReference<T>, socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T {
        val request = json.writeValueAsString(mapOf("id" to 1, "method" to method, "params" to params))
        val url = webSocketDebuggerUrl.replace("ws", "http").toHttpUrl().newBuilder()
            .host(socketName)
            .build()
        val response = makeSingleWebsocketRequest(url, request)
        return try {
            val resultType = TypeFactory.defaultInstance().constructType(resultTypeReference)
            val responseType = TypeFactory.defaultInstance()
                .constructParametricType(DevToolsResponse::class.java, resultType)
            json.readValue<DevToolsResponse<T>>(response, responseType).result
        } catch (e: JsonProcessingException) {
            // Throw a type degradeTo won't swallow (like the /json path) so a malformed reply fails
            // the capture loudly instead of degrading to a native-only hierarchy.
            throw IllegalStateException("Failed to parse DOM snapshot from $method on $socketName: $response", e)
        }
    }

    fun getWebViewInfos(): List<WebViewInfo> {
        val socketNames = degradeTo(emptySet(), "Failed to discover WebView devtools sockets. Skipping WebView capture.") {
            getWebViewSocketNames()
        }
        return socketNames.flatMap { socketName ->
            degradeTo(emptyList(), "Failed to list WebViews on $socketName. Skipping this socket.") {
                getWebViewInfos(socketName)
            }
        }
    }

    fun makeSingleWebsocketRequest(url: HttpUrl, message: String): String {
        val future = CompletableFuture<String>()
        val ws = okhttp.newWebSocket(
            Request.Builder()
                .url(url)
                .build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    future.complete(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    future.completeExceptionally(t)
                }
            }
        )
        ws.send(message)
        try {
            return try {
                future.get(stepTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw TimeoutException("Timed out waiting for websocket response")
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            // close(1000) waits on a graceful handshake an unresponsive endpoint never answers,
            // parking the reader worker until the read timeout; cancel() releases it immediately, so
            // it runs on the success path too.
            ws.cancel()
        }
    }

    private fun getWebViewInfos(socketName: String): List<WebViewInfo> {
        val call = okhttp.newCall(Request.Builder()
            .url("http://$socketName/json")
            .header("Host", "localhost:9222") // Expected by devtools server
            .build())

        // Throws on failure; the caller's per-socket degradeTo owns the warn-and-skip.
        call.execute().use { response ->
            if (response.code != 200) {
                logger.warn("Request to get WebView infos failed with code ${response.code}. Defaulting to empty list.")
                return emptyList()
            }

            val body = response.body?.string() ?: throw IllegalStateException("No body found")

            return try {
                json.readValue<List<WebViewResponse>>(body).mapNotNull { parsed ->
                    // Description is empty for eg. service workers
                    if (parsed.description.isBlank()) return@mapNotNull null
                    val description = json.readValue(parsed.description, WebViewDescription::class.java)
                    WebViewInfo(
                        socketName = socketName,
                        webSocketDebuggerUrl = parsed.webSocketDebuggerUrl,
                        visible = description.visible,
                        attached = description.attached,
                        empty = description.empty,
                        screenX = description.screenX,
                        screenY = description.screenY,
                        width = description.width,
                        height = description.height,
                    )
                }.filter { it.attached && it.visible && !it.empty }
            } catch (e: JsonProcessingException) {
                throw IllegalStateException("Failed to parse WebView chrome dev tools response:\n$body", e)
            }
        }
    }

    private fun getWebViewSocketNames(): Set<String> {
        // Discovery rides the same wedgeable dadb transport as the webview streams (MA-4111), so
        // it gets the same worker handoff and deadline: without one, a wedged transport parks the
        // capture in an un-abortable shell read before any webview socket is even opened.
        val response = boundedAdbCall(adbIoExecutor, stepTimeoutMillis, "WebView socket discovery") {
            connection.shell("cat /proc/net/unix")
        }
        if (response.exitCode != 0) {
            throw IllegalStateException("Failed get WebView socket names. Command 'cat /proc/net/unix' failed: ${response.allOutput}")
        }
        return response.allOutput.trim().lines().mapNotNull { line ->
            line.split(Regex("\\s+")).lastOrNull()?.takeIf { it.startsWith(WEB_VIEW_SOCKET_PREFIX) }?.substring(1)
        }.toSet()
    }

    companion object {
        private const val WEB_VIEW_SOCKET_PREFIX = "@webview_devtools_remote_"

        // One bound for every devtools step: the per-webview websocket wait and the
        // `cat /proc/net/unix` discovery shell call. Both answer in milliseconds on a healthy device.
        private const val DEVTOOLS_STEP_TIMEOUT_MS = 5_000L

        private val logger = LoggerFactory.getLogger(Maestro::class.java)
    }
}
