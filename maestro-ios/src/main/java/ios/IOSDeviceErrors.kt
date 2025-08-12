package ios

sealed class IOSDeviceErrors : Throwable() {
    data class AppCrash(val errorMessage: String): IOSDeviceErrors()
    data class OperationTimeout(val errorMessage: String): IOSDeviceErrors()
}