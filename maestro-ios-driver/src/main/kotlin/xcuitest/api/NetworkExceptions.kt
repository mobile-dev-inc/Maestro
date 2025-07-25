package xcuitest.api

import java.io.IOException

sealed class NetworkException(message: String) : IOException(message) {
    class TimeoutException(message: String) : NetworkException(message)
    class ConnectionException(message: String) : NetworkException(message)
    class UnknownHostException(message: String) : NetworkException(message)
    class UnknownNetworkException(message: String): NetworkException(message)

    companion object {
        private fun NetworkException.displayErrorMessage(): String {
            return when (this) {
                is TimeoutException -> "A timeout occurred while waiting for a response from the XCUITest server."
                is ConnectionException -> "Unable to establish a connection to the XCUITest server."
                is UnknownHostException -> "The host for the XCUITest server is unknown."
                is UnknownNetworkException -> "An unknown network error occurred while communicating with the XCUITest server."
            } + " If the issue persists, consider raising a GitHub issue with the error message and any available logs for further assistance."
        }

        fun NetworkException.toUserNetworkException(): NetworkErrorModel {
            return NetworkErrorModel(
                displayErrorMessage(),
                stackTraceToString()
            )
        }
    }

    data class NetworkErrorModel(val userFriendlyMessage: String, val stackTrace: String)

}


