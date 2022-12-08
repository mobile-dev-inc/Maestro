package maestro.networkproxy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.http.HttpHeaders
import com.github.tomakehurst.wiremock.http.Response
import okio.buffer
import okio.gzip
import okio.source

object NetworkProxyUtils {

    private val jsonMapper = jacksonObjectMapper()

    fun Response.isJsonContent(): Boolean {
        return headers.isJsonContent()
    }

    fun Response.decodedBodyString(): String {
        val bodyStr = if (headers.isGzip()) {
            bodyStream.source().gzip().buffer().readUtf8()
        } else {
            bodyAsString
        }

        return if (isJsonContent()) {
            try {
                val jsonObj = jsonMapper.readValue(bodyStr, Any::class.java)
                jsonMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(jsonObj)
            } catch (ignored: Exception) {
                // Ignore invalid JSON
                bodyStr
            }
        } else {
            bodyStr
        }
    }

    fun HttpHeaders.isJsonContent(): Boolean {
        return getHeader("Content-Type")
            .takeIf { it.isPresent }
            ?.values()
            ?.any { it.contains("application/json") }
            ?: false
    }

    fun HttpHeaders.isGzip(): Boolean {
        return getHeader("Content-Encoding")
            .containsValue("gzip")
    }

    fun HttpHeaders.toMap(): Map<String, String> {
        return all()
            .associateBy(
                keySelector = { it.key() },
                valueTransform = { it.values().joinToString(",") }
            )
    }

}