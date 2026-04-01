package maestro.orchestra.validation

sealed class AppValidationException(message: String) : RuntimeException(message) {
    class MissingAppSource : AppValidationException("Missing required parameter for option '--app-file' or '--app-binary-id'")
    class UnrecognizedAppFile : AppValidationException("Could not determine platform. Provide a valid --app-file or --app-binary-id.")
    class AppBinaryNotFound(val appBinaryId: String) : AppValidationException("App binary '$appBinaryId' not found. Check your --app-binary-id.")
    class UnsupportedPlatform(val platform: String) : AppValidationException("Unsupported platform '$platform' returned by server. Please update your CLI.")
    class AppBinaryFetchError(val statusCode: Int?) : AppValidationException("Failed to fetch app binary info. Status code: $statusCode")
    class IncompatibleiOSVersion(val appMinVersion: String, val deviceOsVersion: Int) : AppValidationException(
        "App requires iOS $appMinVersion but device is configured for iOS $deviceOsVersion. " +
        "Set --device-os to a compatible version."
    )
    class UnsupportedAndroidApiLevel(val apiLevel: Int, val supported: List<String>) : AppValidationException(
        "Android API level $apiLevel is not supported. " +
        "Supported versions: ${supported.joinToString(", ")}"
    )
}
