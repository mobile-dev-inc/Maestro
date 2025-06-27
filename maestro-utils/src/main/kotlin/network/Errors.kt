package maestro.utils.network

class InputFieldNotFound : Throwable("Unable to find focused input field")
class UnknownFailure(errorResponse: String) : Throwable(errorResponse)

sealed class XCUITestServerResult<out T> {
    data class Success<T>(val data: T): XCUITestServerResult<T>()
    data class Failure(val errors: XCUITestServerError): XCUITestServerResult<Nothing>()
}

sealed class XCUITestServerError: Throwable() {
    data class UnknownFailure(val errorResponse: String) : XCUITestServerError()
    data class NetworkError(val errorResponse: String): XCUITestServerError()
    data class AppCrash(val errorResponse: String): XCUITestServerError()
    data class OperationTimeout(val errorResponse: String, val operation: String): XCUITestServerError()
    data class BadRequest(val errorResponse: String, val clientMessage: String): XCUITestServerError()
}