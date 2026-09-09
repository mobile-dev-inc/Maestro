package maestro.js

import maestro.utils.HttpUtils.toMultipartBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.graalvm.polyglot.HostAccess.Export
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GraalJsHttp(
    private val httpClient: OkHttpClient
) {
    @Volatile
    private var currentScriptDir: java.io.File? = null

    /**
     * Clients derived for a custom `timeout`, keyed by that timeout. OkHttp's `newBuilder()`
     * copies the parent's connection pool, dispatcher, interceptors and event listener, so a
     * derived client is cheap and shares connections. Cached so a `repeat` loop issuing the
     * same call a thousand times derives one client rather than a thousand.
     */
    private val derivedClients = ConcurrentHashMap<Long, OkHttpClient>()

    fun setCurrentScriptDir(scriptDir: String?) {
      currentScriptDir = scriptDir?.let { java.io.File(it) }
    }

    @JvmOverloads
    @Export
    fun get(
        url: String,
        params: Map<String, Any>? = null,
    ): Any {
        return executeRequest(url, "GET", params)
    }

    @JvmOverloads
    @Export
    fun post(
        url: String,
        params: Map<String, Any>? = null,
    ): Any {
        return executeRequest(url, "POST", params)
    }

    @JvmOverloads
    @Export
    fun put(
        url: String,
        params: Map<String, Any>? = null,
    ): Any {
        return executeRequest(url, "PUT", params)
    }

    @JvmOverloads
    @Export
    fun delete(
        url: String,
        params: Map<String, Any>? = null,
    ): Any {
        return executeRequest(url, "DELETE", params)
    }

    @JvmOverloads
    @Export
    fun request(
        url: String,
        params: Map<String, Any>? = null,
    ): Any {
        val method = params?.get("method") as? String ?: "GET"
        return executeRequest(
            url,
            method,
            params,
        )
    }

    private fun executeRequest(
        url: String,
        method: String,
        params: Map<String, Any>?,
    ): Any {
        val requestBuilder = Request.Builder()
            .url(url)

        val body = params?.get("body") as? String
        val multipartForm = params?.get("multipartForm") as? Map<*, *>

        if (multipartForm == null) {
            requestBuilder.method(method, body?.toRequestBody())
        } else {
            requestBuilder.method(method, multipartForm.toMultipartBody(currentScriptDir))
        }

        val headers: Map<*, *> = params?.get("headers") as? Map<*, *> ?: emptyMap<Any, Any>()

        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key.toString(), value.toString())
        }

        val request = requestBuilder.build()
        val client = clientFor(timeoutMsOf(params))

        val response = try {
            client
                .newCall(request)
                .execute()
        } catch (e: InterruptedIOException) {
            // OkHttp reports a socket timeout as SocketTimeoutException and a call timeout as
            // InterruptedIOException; the same type also carries thread interruption, which
            // leaves the interrupt flag set (see DadbChromeDevToolsClient). Only the timeouts
            // get the friendlier message; interruption keeps propagating untouched.
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw e
            }
            // InterruptedIOException is the common supertype of both timeout cases, so callers
            // catching either the old type or IOException still match.
            throw InterruptedIOException(timeoutMessage(method, request, client)).apply { initCause(e) }
        }

        return ProxyObject.fromMap(mapOf(
            "ok" to response.isSuccessful,
            "status" to response.code,
            "body" to response.body?.string(),
            "headers" to convertHeaders(response.headers)
        ))
    }

    internal fun clientFor(timeoutMs: Long?): OkHttpClient {
        if (timeoutMs == null) return httpClient

        return derivedClients.computeIfAbsent(timeoutMs) { ms ->
            httpClient.newBuilder()
                .callTimeout(ms, TimeUnit.MILLISECONDS)
                .connectTimeout(ms, TimeUnit.MILLISECONDS)
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .writeTimeout(ms, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /**
     * Reads the request's own `timeout`, in milliseconds — `http.post(url, { timeout: 900000 })`.
     *
     * Accepts a JS number or a numeric string, since JS callers reach for either. A value that
     * is present but unusable throws rather than silently falling back to the default, which
     * would leave the flow behaving as though the override had been applied.
     */
    internal fun timeoutMsOf(params: Map<String, Any>?): Long? {
        val value = params?.get("timeout") ?: return null

        val millis = when (value) {
            is Number -> value.toLong()
            is CharSequence -> value.toString().trim().let {
                if (it.isEmpty()) return null
                it.toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "`timeout` must be a whole number of milliseconds, but was \"$it\""
                    )
            }
            else -> throw IllegalArgumentException(
                "`timeout` must be a whole number of milliseconds, but was ${value.javaClass.simpleName}"
            )
        }

        if (millis <= 0) {
            throw IllegalArgumentException(
                "`timeout` must be a positive number of milliseconds, but was $millis"
            )
        }

        return millis
    }

    private fun timeoutMessage(method: String, request: Request, client: OkHttpClient): String {
        val effectiveMs = client.callTimeoutMillis.takeIf { it > 0 } ?: client.readTimeoutMillis

        return "HTTP $method ${request.url.withoutSecrets()} timed out after $effectiveMs ms. " +
            "Raise it with `timeout: <milliseconds>` in the http params."
    }

    /** Keeps the path, which is what makes the error useful, but drops credentials and query. */
    private fun okhttp3.HttpUrl.withoutSecrets(): String = newBuilder()
        .username("")
        .password("")
        .query(null)
        .build()
        .toString()

    private fun convertHeaders(headers: Headers): ProxyObject {
        val headersMap = headers.toMultimap().mapValues { (_, values) ->
            values.joinToString(",")
        }
        return ProxyObject.fromMap(headersMap)
    }

}
