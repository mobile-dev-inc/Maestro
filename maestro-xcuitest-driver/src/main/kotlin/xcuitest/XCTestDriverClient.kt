package xcuitest

import api.SwipeRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.api.GetRunningAppRequest
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

object XCTestDriverClient {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val mapper = jacksonObjectMapper()

    fun subTree(appId: String): Response {
        val url = xctestAPIBuilder("subTree")
            .addQueryParameter("appId", appId)
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun runningAppId(appIds: Set<String>): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val appIdsRequest = GetRunningAppRequest(appIds)
        val body = mapper.writeValueAsString(appIdsRequest).toRequestBody(mediaType)

        val url = xctestAPIBuilder("runningApp")
            .build()
        val request = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(url)
            .post(body)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun swipe(
        appId: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        velocity: Float? = null
    ): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = SwipeRequest(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            velocity = velocity
        )
        val body = mapper.writeValueAsString(request).toRequestBody(mediaType)

        val url = xctestAPIBuilder("swipe")
            .addQueryParameter("appId", appId)
            .build()

        val httpRequest = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(url)
            .post(body)
            .build()

        return okHttpClient.newCall(httpRequest).execute()
    }

    private fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
        return HttpUrl.Builder()
            .scheme("http")
            .host("localhost")
            .addPathSegment(pathSegment)
            .port(9080)
    }

}