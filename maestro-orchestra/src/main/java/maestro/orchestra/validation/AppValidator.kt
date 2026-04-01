package maestro.orchestra.validation

import maestro.device.AppValidationResult
import maestro.device.Platform
import java.io.File
import maestro.device.DeviceSpec

/**
 * Validates and resolves app metadata from a local file, a remote binary ID, or a web manifest.
 *
 * Dependencies are injected as functions so this class stays free of CLI/API-specific types.
 *
 * @param appFileValidator validates a local app file and returns its metadata, or null if unrecognized
 * @param appBinaryInfoProvider fetches app binary info from a remote server by binary ID. Returns a Triple of (appBinaryId, platform, appId).
 * @param webManifestProvider provides a web manifest file for web flows
 * @param iosMinOSVersionProvider extracts the minimum OS version from an iOS app binary file
 */
class AppValidator(
    private val appFileValidator: (File) -> AppValidationResult?,
    private val appBinaryInfoProvider: ((String) -> AppBinaryInfoResult)? = null,
    private val webManifestProvider: (() -> File?)? = null,
    private val iosMinOSVersionProvider: ((File) -> IosMinOSVersion?)? = null,
) {

    data class AppBinaryInfoResult(
        val appBinaryId: String,
        val platform: String,
        val appId: String,
    )

    data class IosMinOSVersion(val major: Int, val full: String)

    fun validate(appFile: File?, appBinaryId: String?): AppValidationResult {
        return when {
            appFile != null -> validateLocalAppFile(appFile)
            appBinaryId != null -> validateAppBinaryId(appBinaryId)
            webManifestProvider != null -> validateWebManifest()
            else -> throw AppValidationException.MissingAppSource()
        }
    }

    private fun validateLocalAppFile(appFile: File): AppValidationResult {
        return appFileValidator(appFile)
            ?: throw AppValidationException.UnrecognizedAppFile()
    }

    private fun validateAppBinaryId(appBinaryId: String): AppValidationResult {
        val provider = appBinaryInfoProvider
            ?: throw AppValidationException.MissingAppSource()

        val info = provider(appBinaryId)

        val platform = try {
            Platform.fromString(info.platform)
        } catch (e: IllegalArgumentException) {
            throw AppValidationException.UnsupportedPlatform(info.platform)
        }

        return AppValidationResult(
            platform = platform,
            appIdentifier = info.appId,
        )
    }

    private fun validateWebManifest(): AppValidationResult {
        val manifest = webManifestProvider?.invoke()
        return manifest?.let { appFileValidator(it) }
            ?: throw AppValidationException.UnrecognizedAppFile()
    }

    fun validateDeviceCompatibility(
      appFile: File?,
      deviceSpec: DeviceSpec,
    ) {
        when (deviceSpec.platform) {
            Platform.IOS -> {
                if (appFile == null) return
                val minOSVersion = iosMinOSVersionProvider?.invoke(appFile) ?: return
                if (minOSVersion.major > deviceSpec.osVersion) {
                    throw AppValidationException.IncompatibleIOSVersion(
                        appMinVersion = minOSVersion.full,
                        deviceOsVersion = deviceSpec.osVersion,
                    )
                }
            }
            Platform.ANDROID -> return
            Platform.WEB -> return
        }
    }
}
