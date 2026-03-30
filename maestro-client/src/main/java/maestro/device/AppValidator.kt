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
        // Pure Java/Kotlin APKs have no native libraries and thus no lib/ entries — always accepted.
        // APKs with native libraries must include arm64-v8a.
        require(supportedArchitectures.isEmpty() || "arm64-v8a" in supportedArchitectures) {
            "APK does not support arm64-v8a architecture. Found: $supportedArchitectures"
        }
        return AppValidationResult(Platform.ANDROID, packageId)
    }

    /**
     * Validates an iOS app binary's metadata.
     * Requires a simulator build (DTPlatformName = "iphonesimulator") and a non-null minimum OS version.
     */
    fun fromIosMetadata(
        bundleId: String,
        platformName: String?,
        minimumOSVersion: String?,
    ): AppValidationResult {
        require(platformName == "iphonesimulator") {
            "App build target '${platformName}' not supported, set build target to 'iphonesimulator'"
        }
        require(minimumOSVersion != null) {
            "App minimum deployment target version is not set"
        }
        return AppValidationResult(Platform.IOS, bundleId)
    }

    /**
     * Validates a web app config.
     */
    fun fromWebMetadata(url: String): AppValidationResult {
        return AppValidationResult(Platform.WEB, url)
    }
}
