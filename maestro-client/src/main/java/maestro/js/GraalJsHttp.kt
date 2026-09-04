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
    private val httpClient: OkHttpClient,
    private val env: Map<String, String> = emptyMap(), // Live view of env, read by JsHttpOptions
) {
    @Volatile
    private var currentScriptDir: java.io.File? = null

    /**
     * Clients derived for non-default [JsHttpOptions]. OkHttp's `newBuilder()` copies the
     * parent's connection pool, dispatcher, interceptors and event listener, so a derived
     * client is cheap and shares connections. Cached so a `repeat` loop issuing the same
     * call a thousand times derives one client rather than a thousand.
     */
    private val derivedClients = ConcurrentHashMap<JsHttpOptions, OkHttpClient>()

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
        val client = clientFor(JsHttpOptions.resolve(params, env))

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

    internal fun clientFor(options: JsHttpOptions): OkHttpClient {
        if (options == JsHttpOptions.DEFAULT) return httpClient

        return derivedClients.computeIfAbsent(options) { opts ->
            httpClient.newBuilder()
                .apply {
                    opts.timeoutMs?.let {
                        callTimeout(it, TimeUnit.MILLISECONDS)
                        readTimeout(it, TimeUnit.MILLISECONDS)
                        writeTimeout(it, TimeUnit.MILLISECONDS)
                    }
                }
                .build()
        }
    }

    private fun timeoutMessage(method: String, request: Request, client: OkHttpClient): String {
        val effectiveMs = client.callTimeoutMillis.takeIf { it > 0 } ?: client.readTimeoutMillis

        return "HTTP $method ${request.url.withoutSecrets()} timed out after $effectiveMs ms. " +
            "Raise it for this request with `timeout: <milliseconds>` in the http params, " +
            "or for the whole run by setting MAESTRO_JS_HTTP_TIMEOUT=<milliseconds>."
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
