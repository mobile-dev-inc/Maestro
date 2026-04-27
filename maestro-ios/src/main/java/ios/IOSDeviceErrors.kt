package ios

sealed class IOSDeviceErrors : Throwable() {
    data class AppCrash(val errorMessage: String): IOSDeviceErrors()
    data class OperationTimeout(val errorMessage: String): IOSDeviceErrors()

    // Surfaced when the underlying transport (e.g. the XCTest runner's HTTP socket) is
    // unreachable. Translated from XCUITestServerError.Unreachable inside XCTestIOSDevice
    // so callers above the device-abstraction layer don't need to know about OkHttp types.
    class Unreachable(val callName: String, cause: Throwable): IOSDeviceErrors() {
        init { initCause(cause) }
        override val message: String = "Device became unreachable while processing $callName"
    }
}