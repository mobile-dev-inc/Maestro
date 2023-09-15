package xcuitest.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import logger.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import util.PrintUtils
import xcuitest.XCTestClient
import xcuitest.api.NetworkException.Companion.toUserNetworkException
import xcuitest.installer.XCTestInstaller

class NetworkErrorHandler(
    private val xcTestInstaller: XCTestInstaller,
    private val logger: Logger
) {

    private var retry = 0

    companion object {
        const val RETRY_RESPONSE_CODE = 503
        const val NO_RETRY_RESPONSE_CODE = 502
        private const val MAX_RETRY = 5
        private val mapper = jacksonObjectMapper()
    }

    fun getRetrialResponse(networkException: NetworkException, request: Request): Response {
        val userNetworkModel = networkException.toUserNetworkException()
        val error = Error(errorMessage = userNetworkModel.userFriendlyMessage, errorCode = "network-error")
        val json = mapper.writeValueAsString(error)
        val responseBody = json.toResponseBody("application/json; charset=utf-8".toMediaType())
        logger.info("Got Network exception in network layer: $networkException")
        return if (networkException.shouldRetryDriverInstallation()) {
            logger.info("Retrying the installation of driver from network layer by returning fake response code $RETRY_RESPONSE_CODE")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .message(userNetworkModel.userFriendlyMessage)
                .body(responseBody)
                .code(RETRY_RESPONSE_CODE)
                .build()
        } else {
            logger.info("Not retrying the installation of driver from network layer")
            logger.info("Network exception $networkException and mapped user network exception $userNetworkModel")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .message(userNetworkModel.userFriendlyMessage)
                .body(responseBody)
                .code(NO_RETRY_RESPONSE_CODE)
                .build()
        }
    }

    private fun NetworkException.shouldRetryDriverInstallation(): Boolean {
        return when (this) {
            is NetworkException.ConnectionException,
            is NetworkException.TimeoutException,
            is NetworkException.UnknownNetworkException -> true
            is NetworkException.UnknownHostException -> false
        }
    }

    fun retryConnection(
        call: Call,
        response: Response,
        reInitializeInstaller: (XCTestClient) -> Unit
    ): Response {
        return if (retry < MAX_RETRY) {
            xcTestInstaller.start()?.let {
                reInitializeInstaller(it)
            }
            response.close()
            retry++
            logger.info("ℹ️ Retrying connection to the XCUITest server for ${retry}...")
            PrintUtils.log("ℹ️ Retrying connection to the XCUITest server for ${retry}...")
            call.clone().execute()
        } else {
            logger.error("⚠️ Error: ${response.message}")
            PrintUtils.log("⚠️ Error: ${response.message}")
            resetRetryCount()
            return Response.Builder()
                .request(call.request())
                .protocol(Protocol.HTTP_1_1)
                .message(response.message)
                .body(response.body)
                .code(NO_RETRY_RESPONSE_CODE)
                .build()
        }
    }

    fun retryConnection(
        chain: Interceptor.Chain,
        networkException: NetworkException,
        reInitializeInstaller: (XCTestClient) -> Unit
    ): Response {
        logger.info("Got Network exception in application layer: $networkException")
        return if (networkException.shouldRetryDriverInstallation() && retry < MAX_RETRY) {
            xcTestInstaller.start()?.let {
                reInitializeInstaller(it)
            }
            retry++
            logger.info("ℹ️ Retrying connection to the XCUITest server for ${retry}...")
            PrintUtils.log("ℹ️ Retrying connection to the XCUITest server for ${retry}...")
            chain.call().clone().execute()
        } else {
            val userNetworkException = networkException.toUserNetworkException()
            val error = Error(errorMessage = userNetworkException.userFriendlyMessage, errorCode = "network-error")
            val json = mapper.writeValueAsString(error)
            val responseBody = json.toResponseBody("application/json; charset=utf-8".toMediaType())
            logger.error("⚠️ Error: ${userNetworkException.userFriendlyMessage}")
            PrintUtils.log("⚠️ Error: ${userNetworkException.userFriendlyMessage}")
            resetRetryCount()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .message(userNetworkException.userFriendlyMessage)
                .body(responseBody)
                .code(NO_RETRY_RESPONSE_CODE)
                .build()
        }
    }

    fun resetRetryCount() {
        retry = 0
    }
}