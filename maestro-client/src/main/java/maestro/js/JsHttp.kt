package maestro.js

import maestro.utils.HttpUtils.toMultipartBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

class JsHttp(
    private val httpClient: OkHttpClient
) : ScriptableObject() {

    fun get(
        url: String,
        params: NativeObject?,
    ): Any {
        return executeRequest(url, "GET", params)
    }

    fun post(
        url: String,
        params: NativeObject?,
    ): Any {
        return executeRequest(url, "POST", params)
    }

    fun put(
        url: String,
        params: NativeObject?,
    ): Any {
        return executeRequest(url, "PUT", params)
    }

    fun delete(
        url: String,
        params: NativeObject?,
    ): Any {
        return executeRequest(url, "DELETE", params)
    }

    fun request(
        url: String,
        params: NativeObject?,
    ): Any {
        val method = params
            ?.get("method")
            ?.let {
                if (Undefined.isUndefined(it)) {
                    null
                } else {
                    it.toString()
                }
            }
            ?: "GET"

        return executeRequest(
            url,
            method,
            params,
        )
    }

    private fun executeRequest(
        url: String,
        method: String,
        params: NativeObject?,
    ): Any {
        val requestBuilder = Request.Builder()
            .url(url)

        val body = params
            ?.get("body")
            ?.let {
                if (Undefined.isUndefined(it)) {
                    null
                } else {
                    it.toString()
                }
            }
        val multipartForm = params?.get("multipartForm") as? Map<*, *>

        if (multipartForm == null) {
            requestBuilder.method(method, body?.toRequestBody())
        } else {
            requestBuilder.method(method, multipartForm.toMultipartBody())
        }

        params
            ?.get("headers")
            ?.let {
                if (Undefined.isUndefined(it)) {
                    null
                } else {
                    it as NativeObject
                }
            }
            ?.let {
                it.entries
                    .forEach { (key, value) ->
                        requestBuilder.addHeader(key.toString(), value.toString())
                    }
            }

        val request = requestBuilder.build()

        val response = httpClient
            .newCall(request)
            .execute()

        val resultBuilder = JsObjectBuilder()
        resultBuilder["ok"] = response.isSuccessful
        resultBuilder["status"] = response.code
        resultBuilder["body"] = response.body?.string()
        resultBuilder["headers"] = convertHeaders(response.headers)

        return resultBuilder.build()
    }

    private fun convertHeaders(headers: Headers): NativeObject {
        val headersMap = headers.toMultimap().mapValues { (_, values) -> values.joinToString(",") }
        val headersNativeObject = NativeObject()
        headersMap.forEach { (key, value) -> headersNativeObject.put(key, headersNativeObject, value) }
        return headersNativeObject
    }

    private class JsObjectBuilder {

        private val obj = NativeObject()

        operator fun set(key: String, value: Any?) {
            if (value == null) {
                return
            }

            obj.defineProperty(key, value, PERMANENT)
        }

        fun build(): NativeObject {
            return obj
        }

    }

    override fun getClassName(): String {
        return "JsHttp"
    }

}