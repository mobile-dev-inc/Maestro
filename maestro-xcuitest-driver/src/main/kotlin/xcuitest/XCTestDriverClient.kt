package xcuitest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.api.GetRunningAppRequest
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

private enum class RequestType {
    SUBTREE, RUNNING_APP_ID
}

object XCTestDriverClient {
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val mapper = jacksonObjectMapper()

    fun subTree(appId: String): Response {
        val type = RequestType.SUBTREE
        val url = buildUrl(type, appId)
        val request = buildRequest(type, url, null)

        return okHttpClient.newCall(request).execute()
    }

    fun runningAppId(appIds: Set<String>): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val appIdsRequest = GetRunningAppRequest(appIds)
        val body = mapper.writeValueAsString(appIdsRequest).toRequestBody(mediaType)

        val type = RequestType.RUNNING_APP_ID
        val url = buildUrl(type, null)
        val request = buildRequest(type, url, body)

        return okHttpClient.newCall(request).execute()
    }

    private fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
        return HttpUrl.Builder()
            .scheme("http")
            .host("localhost")
            .addPathSegment(pathSegment)
            .port(9080)
    }

    private fun buildUrl(requestType: RequestType, appId: String?): HttpUrl {
        return when (requestType) {
            RequestType.RUNNING_APP_ID -> xctestAPIBuilder("runningApp")
                .build()
            RequestType.SUBTREE -> xctestAPIBuilder("subTree")
                .addQueryParameter("appId", appId)
                .build()
        }
    }

    private fun buildRequest(requestType: RequestType,
                             httpUrl: HttpUrl,
                             body: RequestBody?): Request {
        return when (requestType) {
            RequestType.RUNNING_APP_ID -> Request.Builder()
                .addHeader("Content-Type", "application/json")
                .url(httpUrl)
                .post(body!!)
                .build()
            RequestType.SUBTREE -> Request.Builder()
                .get()
                .url(httpUrl)
                .build()
        }
    }
}