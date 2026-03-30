package maestro.device

data class AppValidationResult(
    val platform: Platform,
    val appIdentifier: String,
)

object AppValidator {

    /**
     * Validates an Android app binary's metadata.
     * Requires arm64-v8a architecture support.
     */
    fun fromAndroidMetadata(
        packageId: String,
        supportedArchitectures: List<String>,
    ): AppValidationResult {
        require("arm64-v8a" in supportedArchitectures) {
            "APK does not support arm64-v8a architecture. Found: $supportedArchitectures"
        }
        return AppValidationResult(Platform.ANDROID, packageId)
    }

    /**
     * Validates an iOS app binary's metadata.
     */
    fun fromIosMetadata(bundleId: String): AppValidationResult {
        return AppValidationResult(Platform.IOS, bundleId)
    }

    /**
     * Validates a web app config.
     */
    fun fromWebMetadata(url: String): AppValidationResult {
        return AppValidationResult(Platform.WEB, url)
    }
}
