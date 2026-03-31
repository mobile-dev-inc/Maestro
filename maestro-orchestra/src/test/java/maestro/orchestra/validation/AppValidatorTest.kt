package maestro.orchestra.validation

import com.google.common.truth.Truth.assertThat
import maestro.device.AppValidationResult
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class AppValidatorTest {

    private val androidResult = AppValidationResult(Platform.ANDROID, "com.example.app")
    private val iosResult = AppValidationResult(Platform.IOS, "com.example.ios")
    private val webResult = AppValidationResult(Platform.WEB, "https://example.com")

    @Test
    fun `validates local app file successfully`() {
        val appFile = File("app.apk")
        val validator = AppValidator(appFileValidator = { androidResult })

        val result = validator.validate(appFile = appFile, appBinaryId = null)

        assertThat(result).isEqualTo(androidResult)
    }

    @Test
    fun `throws UnrecognizedAppFile when local app file validation returns null`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.UnrecognizedAppFile> {
            validator.validate(appFile = File("unknown.bin"), appBinaryId = null)
        }
    }

    @Test
    fun `validates app binary id from provider successfully`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "Android", "com.example.app")
            },
        )

        val result = validator.validate(appFile = null, appBinaryId = "bin_123")

        assertThat(result.platform).isEqualTo(Platform.ANDROID)
        assertThat(result.appIdentifier).isEqualTo("com.example.app")
    }

    @Test
    fun `validates iOS app binary id from provider`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "iOS", "com.example.ios")
            },
        )

        val result = validator.validate(appFile = null, appBinaryId = "bin_456")

        assertThat(result.platform).isEqualTo(Platform.IOS)
        assertThat(result.appIdentifier).isEqualTo("com.example.ios")
    }

    @Test
    fun `throws UnsupportedPlatform when provider returns unknown platform`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "Symbian", "com.example.app")
            },
        )

        val error = assertThrows<AppValidationException.UnsupportedPlatform> {
            validator.validate(appFile = null, appBinaryId = "bin_bad")
        }
        assertThat(error.platform).isEqualTo("Symbian")
    }

    @Test
    fun `throws MissingAppSource when appBinaryId given but no provider`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.MissingAppSource> {
            validator.validate(appFile = null, appBinaryId = "bin_123")
        }
    }

    @Test
    fun `validates web flow via manifest provider`() {
        val webManifest = File.createTempFile("manifest", ".json").also {
            it.writeText("""{"url": "https://example.com"}""")
            it.deleteOnExit()
        }

        val validator = AppValidator(
            appFileValidator = { webResult },
            webManifestProvider = { webManifest },
        )

        val result = validator.validate(appFile = null, appBinaryId = null)

        assertThat(result.platform).isEqualTo(Platform.WEB)
        assertThat(result.appIdentifier).isEqualTo("https://example.com")
    }

    @Test
    fun `throws MissingAppSource when no app file, no binary id, and no web manifest provider`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.MissingAppSource> {
            validator.validate(appFile = null, appBinaryId = null)
        }
    }

    @Test
    fun `throws UnrecognizedAppFile when web manifest provider returns null`() {
        val validator = AppValidator(
            appFileValidator = { null },
            webManifestProvider = { null },
        )

        assertThrows<AppValidationException.UnrecognizedAppFile> {
            validator.validate(appFile = null, appBinaryId = null)
        }
    }
}
