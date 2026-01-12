package maestro.js

import maestro.utils.HttpUtils.toMultipartBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.graalvm.polyglot.HostAccess.Export
import org.graalvm.polyglot.proxy.ProxyObject

class GraalJsHttp(
    private val httpClient: OkHttpClient
) {
    @Volatile
    private var currentScriptDir: java.io.File? = null

    fun setCurrentScriptDir(scriptPath: String?) {
      currentScriptDir = scriptPath?.let { java.io.File(it).parentFile }
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

        val response = httpClient
            .newCall(request)
            .execute()

        return ProxyObject.fromMap(mapOf(
            "ok" to response.isSuccessful,
            "status" to response.code,
            "body" to response.body?.string(),
            "headers" to convertHeaders(response.headers)
        ))
    }

    private fun convertHeaders(headers: Headers): ProxyObject {
        val headersMap = headers.toMultimap().mapValues { (_, values) ->
            values.joinToString(",")
        }
        return ProxyObject.fromMap(headersMap)
    }

}
