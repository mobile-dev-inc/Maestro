package maestro.roku

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Sink
import okio.buffer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream

/**
 * HTTP client for Roku External Control Protocol (ECP).
 * All Roku device communication goes through port 8060 via REST API.
 *
 * Reference: roku-test-automation ECP.ts and RokuDevice.ts
 */
class RokuEcpClient(
    val host: String,
    val password: String = "",
    private val ecpPort: Int = 8060,
    private val keypressDelayMs: Long = 100,
    private val maxRetries: Int = 3,
) {
    private val logger = LoggerFactory.getLogger(RokuEcpClient::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val digestNonceCount = AtomicInteger(0)

    private val authClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .authenticator { _, response ->
            if (response.request.header("Authorization") != null) {
                null // Already tried auth, give up
            } else {
                buildDigestAuthRequest(response)
            }
        }
        .build()

    private val baseUrl get() = "http://$host:$ecpPort"

    // --- Key Input ---

    fun sendKeypress(key: String) {
        val encoded = URLEncoder.encode(key, "UTF-8")
        ecpPost("keypress/$encoded")
        if (keypressDelayMs > 0) {
            Thread.sleep(keypressDelayMs)
        }
    }

    fun sendKeyDown(key: String) {
        val encoded = URLEncoder.encode(key, "UTF-8")
        ecpPost("keydown/$encoded")
    }

    fun sendKeyUp(key: String) {
        val encoded = URLEncoder.encode(key, "UTF-8")
        ecpPost("keyup/$encoded")
    }

    fun sendText(text: String) {
        for (char in text) {
            val encoded = URLEncoder.encode("LIT_$char", "UTF-8")
            ecpPost("keypress/$encoded")
            if (keypressDelayMs > 0) {
                Thread.sleep(keypressDelayMs)
            }
        }
    }

    // --- App Lifecycle ---

    fun launchChannel(channelId: String, params: Map<String, String> = emptyMap()) {
        val allParams = params.toMutableMap()
        allParams["RTA_LAUNCH"] = "1" // Prevent restart if already running (from RTA pattern)

        val queryString = allParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        ecpPost("launch/$channelId?$queryString")
    }

    fun getActiveApp(): ActiveApp? {
        val doc = ecpGetXml("query/active-app") ?: return null
        val root = doc.documentElement
        val children = root.childNodes

        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "app") {
                val element = node as Element
                return ActiveApp(
                    id = element.getAttribute("id"),
                    title = element.textContent?.trim() ?: "",
                    type = element.getAttribute("type"),
                    version = element.getAttribute("version"),
                )
            }
        }
        return null
    }

    fun isActiveApp(channelId: String): Boolean {
        val activeApp = getActiveApp() ?: return false
        return activeApp.id == channelId
    }

    // --- Device Info ---

    fun getDeviceInfo(): RokuDeviceInfo? {
        val doc = ecpGetXml("query/device-info") ?: return null
        val root = doc.documentElement
        val fields = mutableMapOf<String, String>()

        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                fields[node.nodeName] = node.textContent?.trim() ?: ""
            }
        }

        return RokuDeviceInfo(
            modelName = fields["model-name"] ?: "Unknown",
            modelNumber = fields["model-number"] ?: "",
            serialNumber = fields["serial-number"] ?: "",
            softwareVersion = fields["software-version"] ?: "",
            uiResolution = fields["ui-resolution"] ?: "1080p",
            friendlyName = fields["friendly-device-name"] ?: fields["device-name"] ?: "",
        )
    }

    // --- View Hierarchy ---

    fun getAppUI(): Document? {
        return ecpGetXml("query/app-ui")
    }

    fun getAppUIRaw(): String? {
        val url = "$baseUrl/query/app-ui"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = executeWithRetry(request) ?: return null
        return try {
            response.body?.string()
        } catch (e: Exception) {
            logger.warn("Failed to read app-ui response", e)
            null
        } finally {
            response.close()
        }
    }

    // --- Screenshot ---

    /**
     * Captures a screenshot from the Roku device. Two-step process:
     * 1. POST /plugin_inspect to generate the screenshot (requires digest auth)
     * 2. GET /pkgs/dev.jpg (or .png) to download it
     *
     * Reference: roku-test-automation RokuDevice.ts:232-298
     */
    fun takeScreenshot(out: Sink) {
        // Step 1: Generate screenshot
        generateScreenshot()

        // Step 2: Download - try jpg first, fall back to png
        val formats = listOf("jpg", "png")
        for (format in formats) {
            val url = "http://$host/pkgs/dev.$format"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                val response = authClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.let { body ->
                        val bufferedOut = out.buffer()
                        bufferedOut.writeAll(body.source())
                        bufferedOut.flush()
                    }
                    response.close()
                    return
                }
                response.close()
            } catch (e: IOException) {
                logger.debug("Failed to download screenshot as $format", e)
            }
        }

        throw IOException("Failed to capture screenshot from Roku device at $host")
    }

    private fun generateScreenshot() {
        val url = "http://$host/plugin_inspect"
        // Use multipart form to match the Roku dev web server's expected format
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mysubmit", "Screenshot")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            val response = authClient.newCall(request).execute()
            response.close()
        } catch (e: IOException) {
            logger.warn("Screenshot generation request failed", e)
        }
    }

    // --- Connectivity ---

    fun isReachable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        authClient.dispatcher.executorService.shutdown()
    }

    // --- Digest Auth ---

    private fun buildDigestAuthRequest(response: Response): Request? {
        val challengeHeader = response.header("WWW-Authenticate") ?: return null
        if (!challengeHeader.startsWith("Digest ", ignoreCase = true)) return null

        val params = parseDigestChallenge(challengeHeader.removePrefix("Digest ").removePrefix("digest "))
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]

        val method = response.request.method
        val uri = response.request.url.encodedPath
        val nc = String.format("%08x", digestNonceCount.incrementAndGet())
        val cnonce = String.format("%08x", System.nanoTime())

        val ha1 = md5Hex("rokudev:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val digestResponse = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        val authValue = buildString {
            append("Digest username=\"rokudev\"")
            append(", realm=\"$realm\"")
            append(", nonce=\"$nonce\"")
            append(", uri=\"$uri\"")
            if (qop != null) {
                append(", qop=$qop")
                append(", nc=$nc")
                append(", cnonce=\"$cnonce\"")
            }
            append(", response=\"$digestResponse\"")
        }

        return response.request.newBuilder()
            .header("Authorization", authValue)
            .build()
    }

    private fun parseDigestChallenge(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)=(?:"([^"]*)"|([\w/]+))""")
        for (match in regex.findAll(header)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        return params
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // --- Internal HTTP helpers ---

    private fun ecpPost(path: String) {
        val url = "$baseUrl/$path"
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody("text/plain".toMediaType()))
            .build()

        val response = executeWithRetry(request)
        if (response == null) {
            logger.warn("ECP POST to $path failed — no successful response after retries")
        }
        response?.close()
    }

    private fun ecpGetXml(path: String): Document? {
        val url = "$baseUrl/$path"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = executeWithRetry(request) ?: return null
        return try {
            val bytes = response.body?.bytes() ?: return null
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            logger.warn("Failed to parse XML from $path", e)
            null
        } finally {
            response.close()
        }
    }

    private fun executeWithRetry(request: Request): Response? {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return response
                }
                response.close()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    logger.debug("ECP request to ${request.url} failed (attempt $attempt/$maxRetries). Retrying.")
                    Thread.sleep(50)
                }
            }
        }

        logger.warn("ECP request to ${request.url} failed after $maxRetries attempts", lastException)
        return null
    }

    data class ActiveApp(
        val id: String,
        val title: String,
        val type: String,
        val version: String,
    )

    data class RokuDeviceInfo(
        val modelName: String,
        val modelNumber: String,
        val serialNumber: String,
        val softwareVersion: String,
        val uiResolution: String,
        val friendlyName: String,
    ) {
        val widthPixels: Int get() = if (uiResolution.contains("1080")) 1920 else 1280
        val heightPixels: Int get() = if (uiResolution.contains("1080")) 1080 else 720
    }
}
