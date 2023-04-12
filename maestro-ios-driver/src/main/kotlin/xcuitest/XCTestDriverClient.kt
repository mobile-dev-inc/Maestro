package xcuitest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.api.GetRunningAppRequest
import maestro.logger.Logger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xcuitest.api.EraseTextRequest
import xcuitest.api.InputTextRequest
import xcuitest.api.PressButtonRequest
import xcuitest.api.PressKeyRequest
import xcuitest.api.SetPermissionsRequest
import xcuitest.api.SwipeRequest
import xcuitest.api.TouchRequest
import xcuitest.installer.XCTestInstaller
import java.io.IOException
import java.util.concurrent.TimeUnit

class XCTestDriverClient(
    private val host: String = "localhost",
    private val port: Int = 22087,
    private val installer: XCTestInstaller,
    private val logger: Logger,
) {

    private var isShuttingDown = false

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            isShuttingDown = true
        })
    }

    fun restartXCTestRunnerService() {
        logger.info("[Start] Uninstalling xctest ui runner app")
        installer.uninstall()
        logger.info("[Done] Uninstalling xctest ui runner app")
        installer.start()
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addRetryOnErrorInterceptor()
        .addReturnOkOnShutdownInterceptor()
        .build()

    class XCTestDriverUnreachable(message: String) : IOException(message)

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

    fun screenshot(compressed: Boolean): Response {
        val url = xctestAPIBuilder("screenshot")
            .addQueryParameter("compressed", compressed.toString())
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun isScreenStatic(): Response {
        val url = xctestAPIBuilder("isScreenStatic")
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun runningAppId(appIds: Set<String>): Response {
        return executeJsonRequest("runningApp", GetRunningAppRequest(appIds))
    }

    fun swipe(
        appId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double
    ): Response {
        return executeJsonRequest("swipe", SwipeRequest(
            appId = appId,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration
        ))
    }

    fun swipeV2(
        appId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double
    ): Response {
        return executeJsonRequest("swipeV2", SwipeRequest(
            appId = appId,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration
        ))
    }

    fun inputText(
        text: String,
    ): Response {
        return executeJsonRequest("inputText", InputTextRequest(text))
    }

    fun tap(
        x: Float,
        y: Float,
        duration: Double? = null
    ): Response {
        return executeJsonRequest("touch", TouchRequest(
            x = x,
            y = y,
            duration = duration
        ))
    }

    fun pressKey(name: String): Response {
        return executeJsonRequest("pressKey", PressKeyRequest(name))
    }

    fun pressButton(name: String): Response {
        return executeJsonRequest("pressButton", PressButtonRequest(name))
    }

    fun eraseText(charactersToErase: Int): Response {
        return executeJsonRequest("eraseText", EraseTextRequest(charactersToErase))
    }

    fun deviceInfo(): Response {
        return executeJsonRequest("deviceInfo", Unit)
    }

    fun isChannelAlive(): Boolean {
        return installer.isChannelAlive()
    }

    fun close() {
        installer.close()
    }

    fun setPermissions(permissions: Map<String, String>) {
        executeJsonRequest("setPermissions", SetPermissionsRequest(permissions))
    }

    private fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
        return HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .addPathSegment(pathSegment)
            .port(port)
    }

    private fun executeJsonRequest(url: String, body: Any): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(xctestAPIBuilder(url).build())
            .post(bodyData)

        return okHttpClient.newCall(requestBuilder.build()).execute()
    }

    private fun OkHttpClient.Builder.addRetryOnErrorInterceptor() = addInterceptor(Interceptor {
        val request = it.request()
        try {
            it.proceed(request)
        } catch (connectException: IOException) {
            if (installer.start()) {
                it.proceed(request)
            } else {
                throw XCTestDriverUnreachable("Failed to reach out XCUITest Server in RetryOnError")
            }
        }
    })

    private fun OkHttpClient.Builder.addReturnOkOnShutdownInterceptor() = addNetworkInterceptor(Interceptor {
        val request = it.request()
        try {
            it.proceed(request)
        } catch (connectException: IOException) {
            // Fake an Ok response when shutting down and receiving an error
            // to prevent a stack trace in the cli when running maestro studio.
            if (isShuttingDown) {
                Response.Builder()
                    .request(it.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .build()
            } else {
                Response.Builder()
                    .request(it.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .build()
            }
        }
    })
}
