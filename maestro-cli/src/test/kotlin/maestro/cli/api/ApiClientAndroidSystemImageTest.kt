package maestro.cli.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.device.SystemImageTag
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * The tag is an accepted-but-unadvertised backend input. It travels as a plain string field
 * inside the multipart "request" part, and MUST be absent entirely when the flag is unset so
 * an upload without the flag stays byte-identical to today's.
 */
class ApiClientAndroidSystemImageTest {

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
    fun `the playstore tag is sent as its SDK-canonical string`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))

        upload(androidSystemImage = SystemImageTag.GOOGLE_APIS_PLAYSTORE)

        assertThat(requestPart()["androidSystemImage"]).isEqualTo("google_apis_playstore")
    }

    @Test
    fun `the default tag is still sent explicitly when the flag names it`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))

        upload(androidSystemImage = SystemImageTag.GOOGLE_APIS)

        assertThat(requestPart()["androidSystemImage"]).isEqualTo("google_apis")
    }

    @Test
    fun `an unset flag omits the key entirely`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))

        upload(androidSystemImage = null)

        assertThat(requestPart()).doesNotContainKey("androidSystemImage")
    }

    @Test
    fun `a retried upload still carries the tag`() {
        // Dropped at connect, so the server never saw a request: the CLI repeats it. The tag
        // must survive the recursive self-call, not just the first attempt.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))

        upload(androidSystemImage = SystemImageTag.GOOGLE_APIS_PLAYSTORE, maxRetryCount = 1)

        assertThat(requestPart()["androidSystemImage"]).isEqualTo("google_apis_playstore")
    }

    /**
     * Pulls the JSON "request" part out of the next multipart body the server actually
     * received. A connection dropped at start is still recorded, as a request with an empty
     * body, so those are skipped rather than parsed.
     */
    private fun requestPart(): Map<*, *> {
        var body = ""
        while (body.isEmpty()) {
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
                ?: error("The server never received a request with a body")
            body = recorded.body.readUtf8()
        }
        val json = body.substringAfter("name=\"request\"")
            .substringAfter("{")
            .let { "{" + it.substringBeforeLast("}") + "}" }
        return ObjectMapper().readValue(json, Map::class.java)
    }

    private fun upload(
        androidSystemImage: SystemImageTag?,
        maxRetryCount: Int = 3,
    ): UploadResponse {
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
            androidSystemImage = androidSystemImage,
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
                "platform": "ANDROID",
                "deviceName": "Pixel 6",
                "orientation": "PORTRAIT",
                "osVersion": "33",
                "displayInfo": "Pixel 6",
                "deviceLocale": "en_US"
              }
            }
        """.trimIndent()
    }
}
