package maestro.cli.validation

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.api.AppBinaryInfo
import maestro.cli.util.AppMetadataAnalyzer
import maestro.device.AppValidationResult
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class AppValidatorTest {

    private lateinit var mockClient: ApiClient
    private val authToken = "test-token"

    @BeforeEach
    fun setUp() {
        mockClient = mockk(relaxed = true)
        mockkObject(AppMetadataAnalyzer)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AppMetadataAnalyzer)
    }

    @Test
    fun `validates local app file successfully`() {
        val appFile = File("app.apk")
        val expected = AppValidationResult(Platform.ANDROID, "com.example.app")
        every { AppMetadataAnalyzer.validateAppFile(appFile) } returns expected

        val validator = AppValidator(client = mockClient)
        val result = validator.validate(appFile = appFile, appBinaryId = null)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `throws CliError when local app file validation returns null`() {
        val appFile = File("unknown.bin")
        every { AppMetadataAnalyzer.validateAppFile(appFile) } returns null

        val validator = AppValidator(client = mockClient)

        val error = assertThrows<CliError> {
            validator.validate(appFile = appFile, appBinaryId = null)
        }
        assertThat(error.message).contains("Could not determine platform")
    }

    @Test
    fun `validates app binary id from server successfully`() {
        val binaryId = "bin_123"
        every { mockClient.getAppBinaryInfo(authToken, binaryId) } returns AppBinaryInfo(
            appBinaryId = binaryId,
            platform = "Android",
            appId = "com.example.app",
        )

        val validator = AppValidator(client = mockClient)
        val result = validator.validate(appFile = null, appBinaryId = binaryId, authToken = authToken)

        assertThat(result.platform).isEqualTo(Platform.ANDROID)
        assertThat(result.appIdentifier).isEqualTo("com.example.app")
    }

    @Test
    fun `validates iOS app binary id from server`() {
        val binaryId = "bin_456"
        every { mockClient.getAppBinaryInfo(authToken, binaryId) } returns AppBinaryInfo(
            appBinaryId = binaryId,
            platform = "iOS",
            appId = "com.example.ios",
        )

        val validator = AppValidator(client = mockClient)
        val result = validator.validate(appFile = null, appBinaryId = binaryId, authToken = authToken)

        assertThat(result.platform).isEqualTo(Platform.IOS)
        assertThat(result.appIdentifier).isEqualTo("com.example.ios")
    }

    @Test
    fun `throws CliError when app binary id not found (404)`() {
        every { mockClient.getAppBinaryInfo(authToken, "missing") } throws ApiClient.ApiException(404)

        val validator = AppValidator(client = mockClient)

        val error = assertThrows<CliError> {
            validator.validate(appFile = null, appBinaryId = "missing", authToken = authToken)
        }
        assertThat(error.message).contains("not found")
    }

    @Test
    fun `throws CliError when server returns unsupported platform`() {
        every { mockClient.getAppBinaryInfo(authToken, "bin_bad") } returns AppBinaryInfo(
            appBinaryId = "bin_bad",
            platform = "Symbian",
            appId = "com.example.app",
        )

        val validator = AppValidator(client = mockClient)

        val error = assertThrows<CliError> {
            validator.validate(appFile = null, appBinaryId = "bin_bad", authToken = authToken)
        }
        assertThat(error.message).contains("Unsupported platform")
    }

    @Test
    fun `throws CliError on server error for app binary id`() {
        every { mockClient.getAppBinaryInfo(authToken, "bin_err") } throws ApiClient.ApiException(500)

        val validator = AppValidator(client = mockClient)

        val error = assertThrows<CliError> {
            validator.validate(appFile = null, appBinaryId = "bin_err", authToken = authToken)
        }
        assertThat(error.message).contains("Failed to fetch app binary info")
    }

    @Test
    fun `validates web flow via manifest provider`() {
        val webManifest = File.createTempFile("manifest", ".json").also {
            it.writeText("""{"url": "https://example.com"}""")
            it.deleteOnExit()
        }
        every { AppMetadataAnalyzer.validateAppFile(webManifest) } returns AppValidationResult(Platform.WEB, "https://example.com")

        val validator = AppValidator(
            client = mockClient,
            webManifestProvider = { webManifest },
        )
        val result = validator.validate(appFile = null, appBinaryId = null)

        assertThat(result.platform).isEqualTo(Platform.WEB)
        assertThat(result.appIdentifier).isEqualTo("https://example.com")
    }

    @Test
    fun `throws CliError when no app file, no binary id, and no web manifest provider`() {
        val validator = AppValidator(client = mockClient)

        val error = assertThrows<CliError> {
            validator.validate(appFile = null, appBinaryId = null)
        }
        assertThat(error.message).contains("Missing required parameter")
    }

    @Test
    fun `throws CliError when web manifest provider returns null`() {
        val validator = AppValidator(
            client = mockClient,
            webManifestProvider = { null },
        )

        val error = assertThrows<CliError> {
            validator.validate(appFile = null, appBinaryId = null)
        }
        assertThat(error.message).contains("Could not determine platform")
    }
}
