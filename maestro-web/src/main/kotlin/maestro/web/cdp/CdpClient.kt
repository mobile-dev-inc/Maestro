import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Descriptor for a CDP target (an open tab/page).
 */
@Serializable
data class CdpTarget(
    val id: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String
)

/**
 * A simple client for Chrome DevTools Protocol (CDP).
 *
 * Connects via HTTP to list targets and via WebSocket
 * to evaluate JS expressions with full JSON serialization.
 */
class CdpClient(
    private val host: String = "localhost",
    private val port: Int = 9222
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicInteger(1)
    private val evalMutex = Mutex()

    /**
     * Fetches the list of open CDP targets (tabs/pages).
     */
    suspend fun listTargets(): List<CdpTarget> {
        val endpoint = "http://$host:$port/json"
        val response = httpClient.get(endpoint).bodyAsText()

        return json.decodeFromString(response)
    }

    /**
     * Evaluates a JS expression on the given target, serializing the result via JSON.stringify.
     *
     * @param expression JS code to evaluate.
     * @param target The CDP target descriptor.
     * @return A JSON string of the evaluated result.
     */
    suspend fun evaluate(expression: String, target: CdpTarget): String {
        val wsUrl = target.webSocketDebuggerUrl
        // Wrap expression to catch exceptions and JSON.stringify
        val wrapped = """
            JSON.stringify((() => {
                try { return $expression }
                catch(e) { return { __cdpError: e.toString() } }
            })())
        """.trimIndent()
        val exprJson = Json.encodeToString(JsonPrimitive(wrapped))
        val messageId = idCounter.getAndIncrement()
        val payload = "{" +
                "\"id\":$messageId," +
                "\"method\":\"Runtime.evaluate\"," +
                "\"params\":{\"expression\":$exprJson,\"awaitPromise\":true}" +
                "}"

        return evalMutex.withLock {
            val session = httpClient.webSocketSession {
                url(wsUrl)
            }

            try {
                session.send(Frame.Text(payload))
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        // Wait for matching id
                        if (!text.contains("\"id\":$messageId")) {
                            continue
                        }

                        // Parse JSON
                        val root = json.parseToJsonElement(text).jsonObject
                        val resultObj = root["result"]?.jsonObject
                            ?.get("result")?.jsonObject
                            ?: error("Invalid CDP response: $text")

                        val raw: String = resultObj["value"]?.jsonPrimitive?.content
                            ?: ""

                        if (raw.isEmpty()) {
                            return@withLock ""
                        }

                        // Check for JS error
                        val parsed = json.parseToJsonElement(raw)
                        if (parsed is JsonObject && parsed.jsonObject.containsKey("__cdpError")) {
                            val err = parsed.jsonObject["__cdpError"]?.jsonPrimitive?.content
                            error("JS error: $err")
                        }
                        return@withLock raw
                    }
                }

                error("No CDP response for id=$messageId")
            } finally {
                session.close()
            }
        }
    }

    suspend fun captureScreenshot(target: CdpTarget): ByteArray {
        // First, ensure the Page domain is enabled
//        evaluate("undefined", target, method = "Page.enable", await = false)

        val messageId = idCounter.getAndIncrement()

        // Then request the screenshot
        val payload = buildJsonObject {
            put("id", JsonPrimitive(messageId))
            put("method", JsonPrimitive("Page.captureScreenshot"))
            putJsonObject("params") {
                put("format", JsonPrimitive("png"))
                put("quality", JsonPrimitive(100))
            }
        }.toString()

        // Open WS, send & await
        val wsUrl = target.webSocketDebuggerUrl

        val session = httpClient.webSocketSession { url(wsUrl) }

        try {
            session.send(Frame.Text(payload))
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    if (!text.contains("\"method\":\"Page.captureScreenshot\"") &&
                        !text.contains("\"id\":${messageId}")
                    ) continue

                    // Pull out the base64 data
                    val data = Json.parseToJsonElement(text)
                        .jsonObject["result"]!!.jsonObject["data"]!!.jsonPrimitive.content

                    return Base64.getDecoder().decode(data)
                }
            }
            error("No screenshot response")
        } finally {
            session.close()
        }
    }

    suspend fun openUrl(url: String, target: CdpTarget) {
        // Send a CDP command to open a new tab with the specified URL
        val messageId = idCounter.getAndIncrement()
        val payload = buildJsonObject {
            put("id", JsonPrimitive(messageId))
            put("method", JsonPrimitive("Page.navigate"))
            putJsonObject("params") {
                put("url", JsonPrimitive(url))
            }
        }.toString()

        val session = httpClient.webSocketSession { url(target.webSocketDebuggerUrl) }
        try {
            session.send(Frame.Text(payload))
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    // look for the matching response id
                    if (text.contains("\"id\":$messageId")) {
                        // we could parse title/url from result if needed,
                        // but Page.navigate only returns a frameId normally.
                        return
                    }
                }
            }
            error("No target created")
        } finally {
            session.close()
        }
    }

}

suspend fun main() {
    val client = CdpClient("localhost", 9222)
    val targets = client.listTargets()
    println("Available pages: $targets")

    val page = targets.first()
    val json = client.evaluate("1+1", page)
    println("Result: $json")

    val screenshot = client.captureScreenshot(page)
    println("Screenshot captured, size: ${screenshot.size} bytes")

    // Save screenshot to file or process as needed
    File("local/screenshot.png").writeBytes(screenshot)
}
