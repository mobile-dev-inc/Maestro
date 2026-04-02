package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.api.AppBinaryInfo
import maestro.cli.api.DeviceConfiguration
import maestro.cli.api.UploadResponse
import maestro.cli.api.UploadStatus
import maestro.cli.auth.Auth
import maestro.cli.model.FlowStatus
import maestro.cli.report.ReportFormat
import maestro.orchestra.validation.AppMetadataAnalyzer
import maestro.orchestra.validation.WorkspaceValidator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

class CloudInteractorTest {

    private lateinit var mockApiClient: ApiClient
    private lateinit var mockAuth: Auth

    private lateinit var originalOut: PrintStream
    private lateinit var outputStream: ByteArrayOutputStream

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockApiClient = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        every { mockAuth.getAuthToken(any(), any()) } returns "test-token"
        every { mockApiClient.getProjects(any()) } returns listOf(
            maestro.cli.api.ProjectResponse(id = "proj_1", name = "Test Project")
        )
        every { mockApiClient.listCloudDevices() } returns mapOf(
            "android" to mapOf("pixel_6" to listOf("android-34", "android-33", "android-31", "android-30", "android-29")),
            "ios" to mapOf(
                "iPhone-11" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
                "iPhone-14" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
            ),
            "web" to mapOf("chromium" to listOf("default")),
        )

        // Capture console output
        originalOut = System.out
        outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
    }

    // ---- Fixtures from test resources ----

    private fun resourceFile(path: String): File =
        File(javaClass.getResource(path)!!.toURI())

    private fun androidFlowFile(): File = resourceFile("/workspaces/cloud_test/android/flow.yaml")
    private fun iosFlowFile(): File = resourceFile("/workspaces/cloud_test/ios/flow.yaml")
    private fun webFlowFile(): File = resourceFile("/workspaces/cloud_test/web/flow.yaml")
    private fun taggedFlowDir(): File = resourceFile("/workspaces/cloud_test/tagged")
    private fun iosApp(): File = resourceFile("/apps/test-ios.zip")
    private fun webManifest(): File = resourceFile("/apps/web-manifest.json")

    /** Creates a flow file with a custom appId in tempDir (for mismatch / error tests). */
    private fun createFlowFile(appId: String): File {
        return File(tempDir, "flow.yaml").also {
            it.writeText("appId: $appId\n---\n- launchApp\n")
        }
    }

    private fun stubUploadResponse(
        platform: String = "Android",
        appBinaryId: String? = null,
    ) {
        every {
            mockApiClient.upload(
                authToken = any(), appFile = any(), workspaceZip = any(),
                uploadName = any(), mappingFile = any(), repoOwner = any(),
                repoName = any(), branch = any(), commitSha = any(),
                pullRequestId = any(), env = any(), appBinaryId = any(), includeTags = any(),
                excludeTags = any(), disableNotifications = any(),
                deviceLocale = any(), progressListener = any(),
                projectId = any(), deviceModel = any(), deviceOs = any(),
                androidApiLevel = any(), iOSVersion = any(),
            )
        } returns UploadResponse(
            orgId = "org_1",
            uploadId = "upload_1",
            appId = "app_1",
            deviceConfiguration = DeviceConfiguration(
                platform = platform,
                deviceName = "Test Device",
                orientation = "portrait",
                osVersion = "33",
                displayInfo = "Test Device",
                deviceLocale = "en_US",
            ),
            appBinaryId = appBinaryId,
        )

        // Stub the upload status for async=true (not polled)
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1",
            status = UploadStatus.Status.SUCCESS,
            completed = true,
            totalTime = 30L,
            startTime = 0L,
            flows = emptyList(),
            appPackageId = null,
            wasAppLaunched = false,
        )
    }

    private fun createCloudInteractor(
        webManifestProvider: (() -> File?)? = null,
    ): CloudInteractor {
        return CloudInteractor(
            client = mockApiClient,
            appFileValidator = { AppMetadataAnalyzer.validateAppFile(it) },
            workspaceValidator = WorkspaceValidator(),
            webManifestProvider = webManifestProvider,
            auth = mockAuth,
            waitTimeoutMs = TimeUnit.SECONDS.toMillis(1),
            minPollIntervalMs = TimeUnit.MILLISECONDS.toMillis(10),
            maxPollingRetries = 2,
            failOnTimeout = true,
        )
    }

    // ---- 1. iOS .app + matching workspace (happy path) ----

    @Test
    fun `upload with iOS app file and matching workspace succeeds`() {
        stubUploadResponse(platform = "IOS")

        val result = createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        verify { mockApiClient.upload(
            authToken = "test-token",
            appFile = any(),
            workspaceZip = any(),
            uploadName = any(),
            mappingFile = any(),
            repoOwner = any(),
            repoName = any(),
            branch = any(),
            commitSha = any(),
            pullRequestId = any(),
            env = any(),
            appBinaryId = isNull(),
            includeTags = any(),
            excludeTags = any(),
            disableNotifications = any(),
            deviceLocale = any(),
            progressListener = any(),
            projectId = "proj_1",
            deviceModel = any(),
            deviceOs = any(),
            androidApiLevel = any(),
            iOSVersion = any(),
        ) }
    }

    // ---- 2. Web flow (no app file) ----

    @Test
    fun `upload with web flow and no app file succeeds`() {
        stubUploadResponse(platform = "WEB")

        val result = createCloudInteractor(webManifestProvider = { webManifest() }).upload(
            flowFile = webFlowFile(),
            appFile = null,
            async = true,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
    }

    // ---- 3. --app-binary-id Android ----

    @Test
    fun `upload with Android appBinaryId resolves platform from server`() {
        stubUploadResponse(platform = "Android", appBinaryId = "bin_android_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") } returns AppBinaryInfo(
            appBinaryId = "bin_android_1",
            platform = "Android",
            appId = "com.example.maestro.orientation",
        )

        val result = createCloudInteractor().upload(
            flowFile = androidFlowFile(),
            appFile = null,
            async = true,
            appBinaryId = "bin_android_1",
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        verify(exactly = 1) { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") }
    }

    // ---- 4. --app-binary-id iOS ----

    @Test
    fun `upload with iOS appBinaryId resolves platform from server`() {
        stubUploadResponse(platform = "IOS", appBinaryId = "bin_ios_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_ios_1") } returns AppBinaryInfo(
            appBinaryId = "bin_ios_1",
            platform = "iOS",
            appId = "com.example.SimpleWebViewApp",
        )

        val result = createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = null,
            async = true,
            appBinaryId = "bin_ios_1",
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        verify(exactly = 1) { mockApiClient.getAppBinaryInfo("test-token", "bin_ios_1") }
    }

    // ---- 5. Missing app file + no binary ID + not web ----

    @Test
    fun `upload throws CliError when no app file, no binary id, and not web flow`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().upload(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Missing required parameter")
    }

    // ---- 6. Workspace with no matching flows ----

    @Test
    fun `upload throws CliError when workspace flows do not match app id`() {
        // Flow has appId=com.example.SimpleWebViewApp but we tell the server the app is "com.different.app"
        val flowFile = createFlowFile("com.nonexistent.app")

        val error = assertThrows<CliError> {
            createCloudInteractor().upload(
                flowFile = flowFile,
                appFile = iosApp(),
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("No flows in workspace match app ID")
    }

    // ---- 7. --app-binary-id not found (404) ----

    @Test
    fun `upload throws CliError when appBinaryId not found on server`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "nonexistent") } throws ApiClient.ApiException(404)

        val error = assertThrows<CliError> {
            createCloudInteractor().upload(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "nonexistent",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("not found")
    }

    // ---- 8. --app-binary-id server error ----

    @Test
    fun `upload throws CliError when server returns error for appBinaryId`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "bin_err") } throws ApiClient.ApiException(500)

        val error = assertThrows<CliError> {
            createCloudInteractor().upload(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "bin_err",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Failed to fetch app binary info")
    }

    // ---- 9. --device-locale passed through ----

    @Test
    fun `upload passes device locale to api client`() {
        stubUploadResponse(platform = "IOS")

        createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            deviceLocale = "fr_FR",
            projectId = "proj_1",
        )

        verify { mockApiClient.upload(
            authToken = any(), appFile = any(), workspaceZip = any(),
            uploadName = any(), mappingFile = any(), repoOwner = any(),
            repoName = any(), branch = any(), commitSha = any(),
            pullRequestId = any(), env = any(), appBinaryId = any(), includeTags = any(),
            excludeTags = any(), disableNotifications = any(),
            deviceLocale = eq("fr_FR"), progressListener = any(),
            projectId = any(), deviceModel = any(), deviceOs = any(),
            androidApiLevel = any(), iOSVersion = any(),
        ) }
    }

    // ---- 10. --include-tags passed through ----

    @Test
    fun `upload passes include tags to workspace validation and api client`() {
        stubUploadResponse(platform = "IOS")

        createCloudInteractor().upload(
            flowFile = taggedFlowDir(),
            appFile = iosApp(),
            async = true,
            includeTags = listOf("smoke"),
            projectId = "proj_1",
        )

        verify { mockApiClient.upload(
            authToken = any(), appFile = any(), workspaceZip = any(),
            uploadName = any(), mappingFile = any(), repoOwner = any(),
            repoName = any(), branch = any(), commitSha = any(),
            pullRequestId = any(), env = any(), appBinaryId = any(),
            includeTags = eq(listOf("smoke")),
            excludeTags = any(), disableNotifications = any(),
            deviceLocale = any(), progressListener = any(),
            projectId = any(), deviceModel = any(), deviceOs = any(),
            androidApiLevel = any(), iOSVersion = any(),
        ) }
    }

    // ---- 11. Unsupported platform from server ----

    @Test
    fun `upload throws CliError when server returns unsupported platform for appBinaryId`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "bin_symbian") } returns AppBinaryInfo(
            appBinaryId = "bin_symbian",
            platform = "Symbian",
            appId = "com.example.app",
        )

        val error = assertThrows<CliError> {
            createCloudInteractor().upload(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "bin_symbian",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Unsupported platform")
    }

    // ---- 12. CI metadata passed through ----

    @Test
    fun `upload passes CI metadata to api client`() {
        stubUploadResponse(platform = "IOS")

        createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            repoOwner = "acme",
            repoName = "app",
            branch = "feature/x",
            commitSha = "abc123",
            pullRequestId = "42",
            projectId = "proj_1",
        )

        verify { mockApiClient.upload(
            authToken = any(), appFile = any(), workspaceZip = any(),
            uploadName = any(), mappingFile = any(),
            repoOwner = eq("acme"), repoName = eq("app"),
            branch = eq("feature/x"), commitSha = eq("abc123"),
            pullRequestId = eq("42"),
            env = any(), appBinaryId = any(), includeTags = any(),
            excludeTags = any(), disableNotifications = any(),
            deviceLocale = any(), progressListener = any(),
            projectId = any(), deviceModel = any(), deviceOs = any(),
            androidApiLevel = any(), iOSVersion = any(),
        ) }
    }

    // ---- 13. Env vars passed through ----

    @Test
    fun `upload passes env vars to api client`() {
        stubUploadResponse(platform = "IOS")

        createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            env = mapOf("API_KEY" to "secret"),
            projectId = "proj_1",
        )

        verify { mockApiClient.upload(
            authToken = any(), appFile = any(), workspaceZip = any(),
            uploadName = any(), mappingFile = any(), repoOwner = any(),
            repoName = any(), branch = any(), commitSha = any(),
            pullRequestId = any(),
            env = eq(mapOf("API_KEY" to "secret")), appBinaryId = any(),
            includeTags = any(), excludeTags = any(),
            disableNotifications = any(), deviceLocale = any(),
            progressListener = any(), projectId = any(),
            deviceModel = any(), deviceOs = any(),
            androidApiLevel = any(), iOSVersion = any(),
        ) }
    }

    // ---- 16. Valid device config and compatible app succeeds ----

    @Test
    fun `upload with valid device config and compatible app succeeds`() {
        stubUploadResponse(platform = "IOS")

        val result = createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceModel = "iPhone-14",
            deviceOs = "iOS-18-2",
        )

        assertThat(result).isEqualTo(0)
    }

    // ---- waitForCompletion tests (existing) ----

    @Test
    fun `waitForCompletion should return 0 when upload completes successfully`() {
        val uploadStatus = createUploadStatus(
          completed = true,
          status = UploadStatus.Status.SUCCESS,
          startTime = 0L,
          totalTime = 30L,
          flows = listOf(
            createFlowResult("flow1", FlowStatus.SUCCESS, 0L, 50L),
            createFlowResult("flow2", FlowStatus.SUCCESS, 0L, 50L)
          )
        )
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns uploadStatus
        val result = createCloudInteractor().waitForCompletion(
            authToken = "token",
            uploadId = "upload123",
            appId = "app123",
            failOnCancellation = false,
            reportFormat = ReportFormat.NOOP,
            reportOutput = null,
            testSuiteName = null,
            uploadUrl = "http://example.com",
            projectId = "project123"
        )

        assertThat(result.status).isEqualTo(UploadStatus.Status.SUCCESS)
        verify(exactly = 1) { mockApiClient.uploadStatus("token", "upload123", "project123") }

        val output = outputStream.toString()
        val cleanOutput = output.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        assertThat(cleanOutput).contains("[Passed] flow1 (50ms)")
        assertThat(cleanOutput).contains("[Passed] flow2 (50ms)")
        assertThat(cleanOutput).contains("2/2 Flows Passed")
        assertThat(cleanOutput).contains("Process will exit with code 0 (SUCCESS)")
        assertThat(cleanOutput).contains("http://example.com")

        val flow1Occurrences = cleanOutput.split("[Passed] flow1 (50ms)").size - 1
        val flow2Occurrences = cleanOutput.split("[Passed] flow2 (50ms)").size - 1
        assertThat(flow1Occurrences).isEqualTo(1)
        assertThat(flow2Occurrences).isEqualTo(1)
    }

    @Test
    fun `waitForCompletion should handle status changes and eventually complete`() {
        val initialStatus = createUploadStatus(
            completed = false,
            status = UploadStatus.Status.RUNNING,
            startTime = 0L,
            totalTime = null,
            flows = listOf(
                createFlowResult("flow1", FlowStatus.RUNNING, 0L, null),
                createFlowResult("flow2", FlowStatus.RUNNING, 0L, null),
                createFlowResult("flow3", FlowStatus.PENDING, 0L, null)
            )
        )

        val intermediateStatus = createUploadStatus(
            completed = false,
            status = UploadStatus.Status.RUNNING,
            startTime = 0L,
            totalTime = null,
            flows = listOf(
                createFlowResult("flow1", FlowStatus.SUCCESS, 0L, 45L),
                createFlowResult("flow2", FlowStatus.RUNNING, 0L, null),
                createFlowResult("flow3", FlowStatus.RUNNING, 0L, null)
            )
        )

        val finalStatus = createUploadStatus(
            completed = true,
            status = UploadStatus.Status.SUCCESS,
            startTime = 0L,
            totalTime = 60L,
            flows = listOf(
                createFlowResult("flow1", FlowStatus.SUCCESS, 0L, 45L),
                createFlowResult("flow2", FlowStatus.ERROR, 0L, 60L),
                createFlowResult("flow3", FlowStatus.STOPPED, 0L, null)
            )
        )

        every { mockApiClient.uploadStatus(any(), any(), any()) } returnsMany listOf(
            initialStatus,
            initialStatus,
            intermediateStatus,
            intermediateStatus,
            intermediateStatus,
            finalStatus
        )

        val result = createCloudInteractor().waitForCompletion(
            authToken = "token",
            uploadId = "upload123",
            appId = "app123",
            failOnCancellation = false,
            reportFormat = ReportFormat.NOOP,
            reportOutput = null,
            testSuiteName = null,
            uploadUrl = "http://example.com",
            projectId = "project123"
        )

        assertThat(result.status).isEqualTo(UploadStatus.Status.SUCCESS)
        verify(exactly = 6) { mockApiClient.uploadStatus("token", "upload123", "project123") }

        val output = outputStream.toString()
        val cleanOutput = output.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        assertThat(cleanOutput).contains("[Passed] flow1 (45ms)")
        assertThat(cleanOutput).contains("[Failed] flow2 (60ms)")
        assertThat(cleanOutput).contains("[Stopped] flow3")
        assertThat(cleanOutput).contains("1/3 Flow Failed")
        assertThat(cleanOutput).contains("Process will exit with code 1 (FAIL)")
        assertThat(cleanOutput).contains("http://example.com")

        val flow1Occurrences = cleanOutput.split("[Passed] flow1 (45ms)").size - 1
        val flow2Occurrences = cleanOutput.split("[Failed] flow2 (60ms)").size - 1
        assertThat(flow1Occurrences).isEqualTo(1)
        assertThat(flow2Occurrences).isEqualTo(1)
    }

    // ---- Helpers ----

    private fun createUploadStatus(completed: Boolean, status: UploadStatus.Status, flows: List<UploadStatus.FlowResult>, startTime: Long?, totalTime: Long?): UploadStatus {
        return UploadStatus(
            uploadId = "upload123",
            status = status,
            completed = completed,
            flows = flows,
            totalTime = totalTime,
            startTime = startTime,
            appPackageId = null,
            wasAppLaunched = false,
        )
    }

    private fun createFlowResult(name: String, status: FlowStatus, startTime: Long = 0L, totalTime: Long?): UploadStatus.FlowResult {
        return UploadStatus.FlowResult(
            name = name,
            status = status,
            errors = emptyList(),
            startTime = startTime,
            totalTime = totalTime
        )
    }
}
