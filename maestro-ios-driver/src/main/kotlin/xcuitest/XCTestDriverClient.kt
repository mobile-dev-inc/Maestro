package xcuitest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hierarchy.ViewHierarchy
import maestro.api.GetRunningAppRequest
import logger.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import xcuitest.api.*
import xcuitest.installer.XCTestInstaller
import java.io.IOException
import java.net.ConnectException
import xcuitest.api.NetworkErrorHandler
import xcuitest.api.NetworkErrorHandler.Companion.RETRY_RESPONSE_CODE
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class XCTestDriverClient(
    private val installer: XCTestInstaller,
    private val logger: Logger,
    private val httpInterceptor: HttpLoggingInterceptor? = null
) {
    private lateinit var client: XCTestClient
    constructor(installer: XCTestInstaller, logger: Logger, client: XCTestClient): this(installer, logger) {
        this.client = client
    }

    private var isShuttingDown = false

    private val networkErrorHandler by lazy { NetworkErrorHandler(installer, logger) }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            isShuttingDown = true
        })
        httpInterceptor?.level = HttpLoggingInterceptor.Level.BODY
    }

    fun restartXCTestRunnerService() {
        logger.info("[Start] Uninstalling xctest ui runner app")
        installer.uninstall()
        logger.info("[Done] Uninstalling xctest ui runner app")
        logger.info("[Start] Installing xctest ui runner app")
        client = installer.start()
            ?: throw XCTestDriverUnreachable("Failed to reach XCUITest Server in restart")
        logger.info("[Done] Installing xctest ui runner app")
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .apply {
            httpInterceptor?.let {
                this.addInterceptor(it)
            }
        }
        .addRetryInterceptor()
        .addRetryAndShutdownInterceptor()
        .build()

    class XCTestDriverUnreachable(message: String) : IOException(message)

    private val mapper = jacksonObjectMapper()

    fun viewHierarchy(installedApps: Set<String>): ViewHierarchy {
        return executeJsonRequest(
            "viewHierarchy",
            ViewHierarchyRequest(installedApps),
            ViewHierarchy::class
        )
    }

    fun screenshot(compressed: Boolean): Response {
        val url = client.xctestAPIBuilder("screenshot")
            .addQueryParameter("compressed", compressed.toString())
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun isScreenStatic(): Response {
        val url = client.xctestAPIBuilder("isScreenStatic")
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        return okHttpClient.newCall(request).execute()
    }

    fun runningAppId(appIds: Set<String>): Response {
        return executeJsonRequestUNCHECKED("runningApp", GetRunningAppRequest(appIds))
    }

    fun swipe(
        appId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ): Response {
        return executeJsonRequestUNCHECKED(
            "swipe", SwipeRequest(
                appId = appId,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration
            )
        )
    }

    fun swipeV2(
        installedApps: Set<String>,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ): Response {
        return executeJsonRequestUNCHECKED("swipeV2", SwipeRequest(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            appIds = installedApps
        ))
    }

    fun inputText(
        text: String,
        appIds: Set<String>,
    ): Response {
        return executeJsonRequestUNCHECKED("inputText", InputTextRequest(text, appIds))
    }

    fun tap(
        x: Float,
        y: Float,
        duration: Double? = null,
    ): Response {
        return executeJsonRequestUNCHECKED("touch", TouchRequest(
            x = x,
            y = y,
            duration = duration
        ))
    }

    fun pressKey(name: String): Response {
        return executeJsonRequestUNCHECKED("pressKey", PressKeyRequest(name))
    }

    fun pressButton(name: String): Response {
        return executeJsonRequestUNCHECKED("pressButton", PressButtonRequest(name))
    }

    fun eraseText(charactersToErase: Int, appIds: Set<String>): Response {
        return executeJsonRequestUNCHECKED("eraseText", EraseTextRequest(charactersToErase, appIds))
    }

    fun deviceInfo(httpUrl: HttpUrl = client.xctestAPIBuilder("deviceInfo").build()): Response {
        return executeJsonRequestUNCHECKED(httpUrl, Unit)
    }

    fun isChannelAlive(): Boolean {
        return installer.isChannelAlive()
    }

    fun close() {
        installer.close()
    }

    fun setPermissions(permissions: Map<String, String>): Response {
        val response = executeJsonRequestUNCHECKED("setPermissions", SetPermissionsRequest(permissions))
        return response.use { it }
    }

    private fun executeJsonRequestUNCHECKED(pathSegment: String, body: Any): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(client.xctestAPIBuilder(pathSegment).build())
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute()
    }

    private fun executeJsonRequestUNCHECKED(httpUrl: HttpUrl, body: Any): Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(httpUrl)
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute()
    }

    private fun executeJsonRequest(url: String, body: Any): Response {
        val response = executeJsonRequestUNCHECKED(url, body)

        if (!response.isSuccessful) {
            val responseBodyAsString = response.body?.bytes()?.let {
                String(it)
            }
            val code = response.code
            logger.error("Request for $url failed, status code ${code}, body: $responseBodyAsString")
            error("Request for $url failed, status code ${code}, body: $responseBodyAsString")
        }

        return response
    }

    private inline fun <reified T: Any> executeJsonRequest(url: String, body: Any, type: KClass<T>): T {
        val response = executeJsonRequest(url, body)

        return response.body.use { responseBody ->
            responseBody ?: error("Missing response body for mapping to $type")

            mapper.readValue(responseBody.bytes(), T::class.java)
                ?: error("Response body '${String(responseBody.bytes()).take(10)}...' not mappable to $type")
        }
    }

    private fun OkHttpClient.Builder.addRetryInterceptor() = addInterceptor(Interceptor { chain ->
        val response = try {
             chain.proceed(chain.request())
        } catch (ioException: IOException) {
            val networkException = mapNetworkException(ioException)
            return@Interceptor networkErrorHandler.retryConnection(chain, networkException) {
                client = it
            }
        }

        return@Interceptor when (response.code) {
            RETRY_RESPONSE_CODE -> {
                networkErrorHandler.retryConnection(chain.call(), response) {
                    logger.info("Reinitialized the xctest client after reestablishing connection")
                    client = it
                }
            }
            else -> {
                networkErrorHandler.resetRetryCount()
                response
            }
        }
    })

    private fun OkHttpClient.Builder.addRetryAndShutdownInterceptor() = addNetworkInterceptor(Interceptor {
        val request = it.request()
        try {
            it.proceed(request)
        } catch (ioException: IOException) {
            // Fake an Ok response when shutting down and receiving an error
            // to prevent a stack trace in the cli when running maestro studio.

            if (isShuttingDown) {
                val message = "Shutting down xctest server"
                val responseBody = """
                    { "message" : "$message" }
                """.trimIndent().toResponseBody("application/json; charset=utf-8".toMediaType())

                Response.Builder()
                    .request(it.request())
                    .protocol(Protocol.HTTP_1_1)
                    .message(message)
                    .body(responseBody)
                    .code(200)
                    .build()
            } else {
                val networkException = mapNetworkException(ioException)
                return@Interceptor networkErrorHandler.getRetrialResponse(networkException, request)
            }
        }
    })

    private fun mapNetworkException(e: IOException): NetworkException {
        return when (e) {
            is SocketTimeoutException -> NetworkException.TimeoutException("Socket timeout")
            is ConnectException -> NetworkException.ConnectionException("Connection error")
            is UnknownHostException -> NetworkException.UnknownHostException("Unknown host")
            else -> {
                logger.info("Exception $e is not mapped io exception")
                NetworkException.UnknownNetworkException(e.message ?: e.stackTraceToString())
            }
        }
    }
}
