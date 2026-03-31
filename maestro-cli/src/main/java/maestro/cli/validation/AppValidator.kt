package maestro.cli.validation

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.util.AppMetadataAnalyzer
import maestro.device.AppValidationResult
import maestro.device.Platform
import java.io.File

class AppValidator(
    private val client: ApiClient,
    private val webManifestProvider: (() -> File?)? = null,
) {

    fun validate(appFile: File?, appBinaryId: String?, authToken: String? = null): AppValidationResult {
        return when {
            appFile != null -> validateLocalAppFile(appFile)
            appBinaryId != null -> validateAppBinaryId(appBinaryId, requireNotNull(authToken) { "authToken required for app binary validation" })
            webManifestProvider != null -> validateWebManifest()
            else -> throw CliError("Missing required parameter for option '--app-file' or '--app-binary-id'")
        }
    }

    private fun validateLocalAppFile(appFile: File): AppValidationResult {
        return AppMetadataAnalyzer.validateAppFile(appFile)
            ?: throw CliError("Could not determine platform. Provide a valid --app-file or --app-binary-id.")
    }

    private fun validateAppBinaryId(appBinaryId: String, authToken: String): AppValidationResult {
        val info = try {
            client.getAppBinaryInfo(authToken, appBinaryId)
        } catch (e: ApiClient.ApiException) {
            if (e.statusCode == 404) throw CliError("App binary '$appBinaryId' not found. Check your --app-binary-id.")
            throw CliError("Failed to fetch app binary info. Status code: ${e.statusCode}")
        }

        val platform = try {
            Platform.fromString(info.platform)
        } catch (e: IllegalArgumentException) {
            throw CliError("Unsupported platform '${info.platform}' returned by server. Please update your CLI.")
        }

        return AppValidationResult(
            platform = platform,
            appIdentifier = info.appId,
        )
    }

    private fun validateWebManifest(): AppValidationResult {
        val manifest = webManifestProvider?.invoke()
        return manifest?.let { AppMetadataAnalyzer.validateAppFile(it) }
            ?: throw CliError("Could not determine platform. Provide a valid --app-file or --app-binary-id.")
    }
}
