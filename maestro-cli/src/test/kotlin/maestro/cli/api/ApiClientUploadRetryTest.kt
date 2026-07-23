package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * `runMaestroTest` creates a fresh upload and one run per flow on every POST, so the CLI may only
 * repeat it when the request provably never landed (MA-4145).
 */
class ApiClientUploadRetryTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `losing the connection after the request was sent does not re-upload`() {
        // Same "we don't know what it did" state as a read timeout, without the wait. Enqueued
        // repeatedly so OkHttp's own retries fail fast instead of blocking on an empty queue.
        // Reaching the abort message proves our retry loop never ran — it throws a different one.
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        val error = assertThrows<CliError> { upload() }

        assertThat(error.message).contains("may still have been accepted")
        assertThat(error.message).contains("/project/proj_test/maestro-test")
    }

    @Test
    fun `gateway 502 does not re-upload`() {
        // The proxy gave up waiting, not the backend — it may have finished creating every run.
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))

        val error = assertThrows<CliError> { upload() }

        assertThat(error.message).contains("may still have been accepted")
        assertThat(error.message).contains("/project/proj_test/maestro-test")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `4xx still fails immediately without the ambiguity warning`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad workspace"))

        val error = assertThrows<CliError> { upload() }

        assertThat(error.message).contains("Bad workspace")
        assertThat(error.message).doesNotContain("may already have been accepted")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `failing before the request lands still retries`() {
        // Dropped at connect, so the server never saw a request: repeating it is safe.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))

        val response = upload(maxRetryCount = 1)

        assertThat(response.uploadId).isEqualTo("mupload_test")
    }

    private fun upload(maxRetryCount: Int = 3): UploadResponse {
        val workspaceZip = tempDir.resolve("workspace.zip").apply { writeText("not really a zip") }

        return ApiClient(server.url("/").toString().trimEnd('/')).upload(
            authToken = "token",
            appFile = null,
            appBinaryId = "app_binary_test",
            workspaceZip = workspaceZip,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            disableNotifications = false,
            projectId = "proj_test",
            androidApiLevel = null,
            maxRetryCount = maxRetryCount,
        )
    }

    private companion object {
        val UPLOAD_RESPONSE = """
            {
              "orgId": "org_test",
              "uploadId": "mupload_test",
              "appId": "app_test",
              "appBinaryId": "app_binary_test",
              "deviceConfiguration": {
                "platform": "IOS",
                "deviceName": "iPhone 15",
                "orientation": "PORTRAIT",
                "osVersion": "17",
                "displayInfo": "iPhone 15",
                "deviceLocale": "en_US"
              }
            }
        """.trimIndent()
    }
}
