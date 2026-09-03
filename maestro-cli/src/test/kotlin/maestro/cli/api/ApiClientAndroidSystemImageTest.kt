package maestro.cli.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.SystemImageTag
import maestro.device.serialization.DeviceSpecModule
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * `ApiClient.upload` no longer has an `androidSystemImage` param — it takes a typed
 * `deviceSpec: DeviceSpec?` instead, and it's a dumb pass-through: whatever the caller sends is
 * what lands in the multipart `request` part. (The `--device-os` shape branching — deriving a
 * `DeviceSpec.Android` from a full 'system-images;...' path and nulling the loose fields — is
 * `CloudInteractor`'s job, covered by CloudInteractorTest.) This exercises the wire contract at
 * the `ApiClient.upload` boundary: a `deviceSpec` is Jackson-serialized under the "deviceSpec"
 * key, a loose `deviceOs` still goes under "deviceOs", and "androidSystemImage" never appears.
 */
class ApiClientAndroidSystemImageTest {

    private lateinit var server: MockWebServer

    // DeviceSpecModule registers the sparse serializer that emits only non-default intent fields
    // (os, tag, abi, locale) under their wire names — the same module ApiClient registers on its
    // own mapper for the real send.
    private val mapper = jacksonObjectMapper().registerModule(DeviceSpecModule())

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(UPLOAD_RESPONSE))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a passed deviceSpec sends a deviceSpec part carrying the tag intent`() {
        val spec = DeviceSpec.Android(
            model = "pixel_6",
            os = "android-36",
            tag = SystemImageTag.GOOGLE_APIS_PLAYSTORE,
            cpuArchitecture = CPU_ARCHITECTURE.ARM64,
        )

        upload(deviceSpec = spec)

        val requestPart = capturedRequestPart()
        assertThat(requestPart).containsKey("deviceSpec")
        assertThat(requestPart).doesNotContainKey("deviceOs")
        assertThat(requestPart).doesNotContainKey("androidSystemImage")

        @Suppress("UNCHECKED_CAST")
        val specJson = requestPart["deviceSpec"] as Map<String, Any?>
        val roundTripped = mapper.convertValue(specJson, DeviceSpec::class.java) as DeviceSpec.Android
        assertThat(roundTripped).isEqualTo(spec)
    }

    @Test
    fun `no deviceSpec keeps sending the loose deviceOs and never sends androidSystemImage`() {
        upload(deviceOs = "android-34")

        val requestPart = capturedRequestPart()
        assertThat(requestPart).doesNotContainKey("deviceSpec")
        assertThat(requestPart).doesNotContainKey("androidSystemImage")
        assertThat(requestPart["deviceOs"]).isEqualTo("android-34")
    }

    @Test
    fun `neither deviceOs nor deviceSpec sends neither key`() {
        upload()

        val requestPart = capturedRequestPart()
        assertThat(requestPart).doesNotContainKey("deviceOs")
        assertThat(requestPart).doesNotContainKey("deviceSpec")
        assertThat(requestPart).doesNotContainKey("androidSystemImage")
    }

    private fun upload(deviceOs: String? = null, deviceSpec: DeviceSpec? = null) {
        val workspaceZip = tempDir.resolve("workspace.zip").apply { writeText("not really a zip") }

        ApiClient(server.url("/").toString().trimEnd('/')).upload(
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
            deviceOs = deviceOs,
            deviceSpec = deviceSpec,
        )
    }

    private fun capturedRequestPart(): Map<String, Any?> {
        val recorded: RecordedRequest = server.takeRequest()
        val contentType = recorded.getHeader("Content-Type")!!
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)!!.groupValues[1]

        val reader = MultipartReader(Buffer().apply { write(recorded.body.readByteArray()) }, boundary)
        reader.use {
            while (true) {
                val part = it.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"] ?: ""
                val body = part.body.readUtf8()
                if (Regex("name=\"request\"").containsMatchIn(disposition)) {
                    return mapper.readValue(body)
                }
            }
        }
        throw AssertionError("No 'request' part found in the multipart body")
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
                "deviceName": "pixel_6",
                "orientation": "PORTRAIT",
                "osVersion": "33",
                "displayInfo": "pixel_6",
                "deviceLocale": "en_US"
              }
            }
        """.trimIndent()
    }
}
