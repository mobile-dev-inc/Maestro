package maestro.cli.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.device.DeviceOrientation
import maestro.device.DeviceSpec
import maestro.device.DeviceSpecRequest
import maestro.device.locale.DeviceLocale
import maestro.device.serialization.DeviceSpecModule
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ApiClientV2Test {

    private lateinit var mockServer: MockWebServer
    private lateinit var apiClient: ApiClient

    private val json = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(DeviceSpecModule())

    private val defaultDeviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Android())

    private fun createTempZip(): java.nio.file.Path {
        val tmp = Files.createTempFile("workspace", ".zip")
        tmp.toFile().writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) // minimal zip header
        return tmp
    }

    private fun createTempAppFile(): java.nio.file.Path {
        val tmp = Files.createTempFile("app", ".zip")
        tmp.toFile().writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        return tmp
    }

    private fun enqueueSuccessResponse(appBinaryId: String = "binary-123") {
        val responseJson = json.writeValueAsString(
            mapOf(
                "orgId" to "org-1",
                "uploadId" to "upload-1",
                "appId" to "app-1",
                "appBinaryId" to appBinaryId,
                "deviceSpec" to mapOf(
                    "platform" to "ANDROID",
                    "model" to "pixel_6",
                    "orientation" to "PORTRAIT",
                    "osVersion" to "android-33",
                    "deviceName" to "Maestro_ANDROID_pixel_6_android-33",
                    "locale" to mapOf("code" to "en_US", "platform" to "ANDROID")
                )
            )
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMultipartRequest(request: okhttp3.mockwebserver.RecordedRequest): Pair<String, Map<String, Any>> {
        val body = request.body.readUtf8()
        val contentType = request.getHeader("Content-Type")!!
        val boundary = contentType.substringAfter("boundary=")

        // Extract parts
        val parts = body.split("--$boundary").filter { it.trim() != "" && it.trim() != "--" }

        var requestJson: Map<String, Any> = emptyMap()
        val partNames = mutableListOf<String>()

        for (part in parts) {
            val nameMatch = Regex("name=\"([^\"]+)\"").find(part)
            val name = nameMatch?.groupValues?.get(1) ?: continue
            partNames.add(name)

            if (name == "request") {
                // The JSON body is after the blank line
                val jsonStr = part.substringAfter("\r\n\r\n").trim()
                requestJson = json.readValue(jsonStr, Map::class.java) as Map<String, Any>
            }
        }

        return Pair(body, requestJson)
    }

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        apiClient = ApiClient(mockServer.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `uploadV2 sends request to v2 project run-maestro-test endpoint`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        assertEquals("/v2/project/proj-42/run-maestro-test", recorded.path)
    }

    @Test
    fun `uploadV2 request JSON has deviceSpec object and no flat device fields`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val (_, requestJson) = parseMultipartRequest(recorded)

        // deviceSpec should be present as an object
        assertNotNull(requestJson["deviceSpec"])
        assertTrue(requestJson["deviceSpec"] is Map<*, *>)

        // Flat device fields should NOT be present
        assertNull(requestJson["deviceModel"])
        assertNull(requestJson["deviceOs"])
        assertNull(requestJson["androidApiLevel"])
        assertNull(requestJson["iOSVersion"])
        assertNull(requestJson["deviceLocale"])
        assertNull(requestJson["isWeb"])
        assertNull(requestJson["agent"])
        assertNull(requestJson["projectId"])
    }

    @Test
    fun `uploadV2 includes CI metadata in request JSON`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = "owner1",
            repoName = "repo1",
            branch = "main",
            commitSha = "abc123",
            pullRequestId = "42",
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val (_, requestJson) = parseMultipartRequest(recorded)

        assertEquals("owner1", requestJson["repoOwner"])
        assertEquals("repo1", requestJson["repoName"])
        assertEquals("main", requestJson["branch"])
        assertEquals("abc123", requestJson["commitSha"])
        assertEquals("42", requestJson["pullRequestId"])
    }

    @Test
    fun `uploadV2 includes tags env and disableNotifications in request JSON`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = mapOf("KEY" to "VALUE"),
            appBinaryId = "binary-123",
            includeTags = listOf("smoke"),
            excludeTags = listOf("flaky"),
            disableNotifications = true,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val (_, requestJson) = parseMultipartRequest(recorded)

        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("smoke"), requestJson["includeTags"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("flaky"), requestJson["excludeTags"])
        @Suppress("UNCHECKED_CAST")
        val env = requestJson["env"] as Map<String, String>
        assertEquals("VALUE", env["KEY"])
        assertEquals(true, requestJson["disableNotifications"])
    }

    @Test
    fun `uploadV2 does not include app_binary part when appBinaryId is set and appFile is null`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(!body.contains("name=\"app_binary\""), "app_binary part should not be present")
    }

    @Test
    fun `uploadV2 includes app_binary part when appFile is provided`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()
        val appFile = createTempAppFile()

        apiClient.uploadV2(
            authToken = "token",
            appFile = appFile,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = null,
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"app_binary\""), "app_binary part should be present")
    }

    @Test
    fun `uploadV2 always includes workspace multipart part`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        val recorded = mockServer.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"workspace\""), "workspace part should be present")
    }

    @Test
    fun `uploadV2 parses response with deviceSpec into UploadResponse`() {
        enqueueSuccessResponse()
        val workspace = createTempZip()

        val response = apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        assertEquals("org-1", response.orgId)
        assertEquals("upload-1", response.uploadId)
        assertEquals("app-1", response.appId)
        assertNotNull(response.deviceConfiguration)
        assertEquals("ANDROID", response.deviceConfiguration!!.platform)
        assertEquals("pixel_6", response.deviceConfiguration!!.deviceName)
        assertEquals("PORTRAIT", response.deviceConfiguration!!.orientation)
        assertEquals("android-33", response.deviceConfiguration!!.osVersion)
        assertEquals("Maestro_ANDROID_pixel_6_android-33", response.deviceConfiguration!!.displayInfo)
        assertEquals("en_US", response.deviceConfiguration!!.deviceLocale)
    }

    @Test
    fun `uploadV2 captures appBinaryId in response`() {
        enqueueSuccessResponse(appBinaryId = "new-binary-456")
        val workspace = createTempZip()

        val response = apiClient.uploadV2(
            authToken = "token",
            appFile = null,
            workspaceZip = workspace,
            deviceSpec = defaultDeviceSpec,
            uploadName = null,
            mappingFile = null,
            repoOwner = null,
            repoName = null,
            branch = null,
            commitSha = null,
            pullRequestId = null,
            env = null,
            appBinaryId = "binary-123",
            includeTags = emptyList(),
            excludeTags = emptyList(),
            disableNotifications = false,
            projectId = "proj-42",
        )

        assertEquals("new-binary-456", response.appBinaryId)
    }
}
