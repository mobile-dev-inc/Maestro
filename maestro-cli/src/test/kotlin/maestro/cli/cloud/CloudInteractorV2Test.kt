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
import maestro.device.DeviceSpec
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

class CloudInteractorV2Test {

    private lateinit var mockApiClient: ApiClient
    private lateinit var mockAuth: Auth

    private lateinit var originalOut: PrintStream
    private lateinit var outputStream: ByteArrayOutputStream

    @TempDir
    lateinit var tempDir: File

    /** Captured arguments from the most recent uploadV2 call. */
    private var uploadV2Called = false
    private var capturedDeviceSpec: DeviceSpec? = null
    private var capturedIncludeTags: List<String>? = null
    private var capturedExcludeTags: List<String>? = null
    private var capturedEnv: Map<String, String>? = null
    private var capturedRepoOwner: String? = null
    private var capturedRepoName: String? = null
    private var capturedBranch: String? = null
    private var capturedCommitSha: String? = null
    private var capturedPullRequestId: String? = null

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

    private fun createFlowFile(appId: String): File {
        return File(tempDir, "flow.yaml").also {
            it.writeText("appId: $appId\n---\n- launchApp\n")
        }
    }

    private fun resetCaptures() {
        uploadV2Called = false
        capturedDeviceSpec = null
        capturedIncludeTags = null
        capturedExcludeTags = null
        capturedEnv = null
        capturedRepoOwner = null
        capturedRepoName = null
        capturedBranch = null
        capturedCommitSha = null
        capturedPullRequestId = null
    }

    private fun stubUploadV2Response(
        platform: String = "Android",
        appBinaryId: String? = null,
    ) {
        resetCaptures()
        val response = UploadResponse(
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

        // Stub all three concrete DeviceSpec subtypes to avoid sealed class instantiation issues.
        // MockK cannot instantiate sealed DeviceSpec so we must use concrete type matchers.
        val captureAndReturn: MockKAnswerScope<UploadResponse, UploadResponse>.(Call) -> UploadResponse = {
            uploadV2Called = true
            capturedDeviceSpec = arg(3)
            capturedIncludeTags = arg(13)
            capturedExcludeTags = arg(14)
            capturedEnv = arg(11)
            capturedRepoOwner = arg(6)
            capturedRepoName = arg(7)
            capturedBranch = arg(8)
            capturedCommitSha = arg(9)
            capturedPullRequestId = arg(10)
            response
        }

        every {
            mockApiClient.uploadV2(
                authToken = any(), appFile = any(), workspaceZip = any(),
                deviceSpec = any<DeviceSpec.Android>(), uploadName = any(), mappingFile = any(),
                repoOwner = any(), repoName = any(), branch = any(),
                commitSha = any(), pullRequestId = any(), env = any(),
                appBinaryId = any(), includeTags = any(), excludeTags = any(),
                disableNotifications = any(), projectId = any(), progressListener = any(),
            )
        } answers captureAndReturn

        every {
            mockApiClient.uploadV2(
                authToken = any(), appFile = any(), workspaceZip = any(),
                deviceSpec = any<DeviceSpec.Ios>(), uploadName = any(), mappingFile = any(),
                repoOwner = any(), repoName = any(), branch = any(),
                commitSha = any(), pullRequestId = any(), env = any(),
                appBinaryId = any(), includeTags = any(), excludeTags = any(),
                disableNotifications = any(), projectId = any(), progressListener = any(),
            )
        } answers captureAndReturn

        every {
            mockApiClient.uploadV2(
                authToken = any(), appFile = any(), workspaceZip = any(),
                deviceSpec = any<DeviceSpec.Web>(), uploadName = any(), mappingFile = any(),
                repoOwner = any(), repoName = any(), branch = any(),
                commitSha = any(), pullRequestId = any(), env = any(),
                appBinaryId = any(), includeTags = any(), excludeTags = any(),
                disableNotifications = any(), projectId = any(), progressListener = any(),
            )
        } answers captureAndReturn

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

    // ==== Input guards (3) ====

    @Test
    fun `uploadV2 throws CliError when flowFile does not exist`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = File("/nonexistent/flow.yaml"),
                appFile = null,
                async = true,
                projectId = "proj_1",
            )
        }
        assertThat(error.message).contains("File does not exist")
    }

    @Test
    fun `uploadV2 throws CliError when mapping file does not exist`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = iosFlowFile(),
                appFile = iosApp(),
                async = true,
                mapping = File("/nonexistent/mapping.txt"),
                projectId = "proj_1",
            )
        }
        assertThat(error.message).contains("File does not exist")
    }

    @Test
    fun `uploadV2 throws CliError when async with report format`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = iosFlowFile(),
                appFile = iosApp(),
                async = true,
                reportFormat = ReportFormat.JUNIT,
                projectId = "proj_1",
            )
        }
        assertThat(error.message).contains("Cannot use --format with --async")
    }

    // ==== App validation (8) ====

    @Test
    fun `uploadV2 with iOS app file succeeds async`() {
        stubUploadV2Response(platform = "IOS")

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        assertThat(uploadV2Called).isTrue()
    }

    @Test
    fun `uploadV2 with web flow and no app file succeeds`() {
        stubUploadV2Response(platform = "WEB")

        val result = createCloudInteractor(webManifestProvider = { webManifest() }).uploadV2(
            flowFile = webFlowFile(),
            appFile = null,
            async = true,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        assertThat(uploadV2Called).isTrue()
    }

    @Test
    fun `uploadV2 with Android appBinaryId resolves platform from server`() {
        stubUploadV2Response(platform = "Android", appBinaryId = "bin_android_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") } returns AppBinaryInfo(
            appBinaryId = "bin_android_1",
            platform = "Android",
            appId = "com.example.maestro.orientation",
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = androidFlowFile(),
            appFile = null,
            async = true,
            appBinaryId = "bin_android_1",
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        verify(exactly = 1) { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") }
    }

    @Test
    fun `uploadV2 with iOS appBinaryId resolves platform from server`() {
        stubUploadV2Response(platform = "IOS", appBinaryId = "bin_ios_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_ios_1") } returns AppBinaryInfo(
            appBinaryId = "bin_ios_1",
            platform = "iOS",
            appId = "com.example.SimpleWebViewApp",
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = null,
            async = true,
            appBinaryId = "bin_ios_1",
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
        verify(exactly = 1) { mockApiClient.getAppBinaryInfo("test-token", "bin_ios_1") }
    }

    @Test
    fun `uploadV2 throws CliError when no app file no binary id and not web flow`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Missing required parameter")
    }

    @Test
    fun `uploadV2 throws CliError when appBinaryId not found on server`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "nonexistent") } throws ApiClient.ApiException(404)

        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "nonexistent",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("not found")
    }

    @Test
    fun `uploadV2 throws CliError when server returns error for appBinaryId`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "bin_err") } throws ApiClient.ApiException(500)

        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "bin_err",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Failed to fetch app binary info")
    }

    @Test
    fun `uploadV2 throws CliError when server returns unsupported platform for appBinaryId`() {
        every { mockApiClient.getAppBinaryInfo("test-token", "bin_symbian") } returns AppBinaryInfo(
            appBinaryId = "bin_symbian",
            platform = "Symbian",
            appId = "com.example.app",
        )

        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                appBinaryId = "bin_symbian",
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("Unsupported platform")
    }

    // ==== DeviceSpec construction — CLI overrides (3) ====

    @Test
    fun `uploadV2 CLI deviceModel overrides default`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceModel = "iPhone-14",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.model).isEqualTo("iPhone-14")
    }

    @Test
    fun `uploadV2 CLI deviceOs overrides default`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceOs = "iOS-18-2",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.os).isEqualTo("iOS-18-2")
    }

    @Test
    fun `uploadV2 CLI deviceLocale overrides default`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceLocale = "fr_FR",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.locale.code).isEqualTo("fr_FR")
    }

    // ==== DeviceSpec construction — legacy fields (3) ====

    @Test
    fun `uploadV2 androidApiLevel maps to spec os`() {
        stubUploadV2Response(platform = "Android", appBinaryId = "bin_android_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") } returns AppBinaryInfo(
            appBinaryId = "bin_android_1",
            platform = "Android",
            appId = "com.example.maestro.orientation",
        )

        createCloudInteractor().uploadV2(
            flowFile = androidFlowFile(),
            appFile = null,
            async = true,
            androidApiLevel = 34,
            appBinaryId = "bin_android_1",
            projectId = "proj_1",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.os).isEqualTo("android-34")
    }

    @Test
    fun `uploadV2 iOSVersion maps to spec os`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            iOSVersion = "17.5",
            projectId = "proj_1",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.os).isEqualTo("iOS-17-5")
    }

    @Test
    fun `uploadV2 deviceOs takes precedence over androidApiLevel`() {
        stubUploadV2Response(platform = "Android", appBinaryId = "bin_android_1")

        every { mockApiClient.getAppBinaryInfo("test-token", "bin_android_1") } returns AppBinaryInfo(
            appBinaryId = "bin_android_1",
            platform = "Android",
            appId = "com.example.maestro.orientation",
        )

        createCloudInteractor().uploadV2(
            flowFile = androidFlowFile(),
            appFile = null,
            async = true,
            androidApiLevel = 30,
            deviceOs = "android-34",
            appBinaryId = "bin_android_1",
            projectId = "proj_1",
        )

        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.os).isEqualTo("android-34")
    }

    // ==== Device validation (3) ====

    @Test
    fun `uploadV2 throws CliError when device model is not supported`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = iosFlowFile(),
                appFile = iosApp(),
                async = true,
                projectId = "proj_1",
                deviceModel = "galaxy_s21",
            )
        }

        assertThat(error.message).contains("not supported")
        assertThat(error.message).contains("galaxy_s21")
    }

    @Test
    fun `uploadV2 throws CliError when OS version is not supported for device`() {
        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = iosFlowFile(),
                appFile = iosApp(),
                async = true,
                projectId = "proj_1",
                deviceOs = "iOS-15-0",
            )
        }

        assertThat(error.message).contains("not supported")
    }

    @Test
    fun `uploadV2 with valid device config and compatible app succeeds`() {
        stubUploadV2Response(platform = "IOS")

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceModel = "iPhone-14",
            deviceOs = "iOS-18-2",
        )

        assertThat(result).isEqualTo(0)
    }

    // ==== Workspace validation (4) ====

    @Test
    fun `uploadV2 throws CliError when workspace flows do not match app id`() {
        val flowFile = createFlowFile("com.nonexistent.app")

        val error = assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = flowFile,
                appFile = iosApp(),
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(error.message).contains("No flows in workspace match app ID")
    }

    @Test
    fun `uploadV2 passes include tags to uploadV2 call`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = taggedFlowDir(),
            appFile = iosApp(),
            async = true,
            includeTags = listOf("smoke"),
            projectId = "proj_1",
        )

        assertThat(uploadV2Called).isTrue()
        assertThat(capturedIncludeTags).isEqualTo(listOf("smoke"))
    }

    @Test
    fun `uploadV2 passes CI metadata to uploadV2 call`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
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

        assertThat(uploadV2Called).isTrue()
        assertThat(capturedRepoOwner).isEqualTo("acme")
        assertThat(capturedRepoName).isEqualTo("app")
        assertThat(capturedBranch).isEqualTo("feature/x")
        assertThat(capturedCommitSha).isEqualTo("abc123")
        assertThat(capturedPullRequestId).isEqualTo("42")
    }

    @Test
    fun `uploadV2 passes env vars to uploadV2 call`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            env = mapOf("API_KEY" to "secret"),
            projectId = "proj_1",
        )

        assertThat(uploadV2Called).isTrue()
        assertThat(capturedEnv).isEqualTo(mapOf("API_KEY" to "secret"))
    }

    // ==== Return codes (5) ====

    @Test
    fun `uploadV2 returns 0 on SUCCESS`() {
        stubUploadV2Response(platform = "IOS")
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1", status = UploadStatus.Status.SUCCESS,
            completed = true, totalTime = 30L, startTime = 0L,
            flows = emptyList(), appPackageId = null, wasAppLaunched = false,
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = false,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `uploadV2 returns 1 on ERROR`() {
        stubUploadV2Response(platform = "IOS")
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1", status = UploadStatus.Status.ERROR,
            completed = true, totalTime = 30L, startTime = 0L,
            flows = emptyList(), appPackageId = null, wasAppLaunched = false,
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = false,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `uploadV2 returns 0 on CANCELED with failOnCancellation false`() {
        stubUploadV2Response(platform = "IOS")
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1", status = UploadStatus.Status.CANCELED,
            completed = true, totalTime = 30L, startTime = 0L,
            flows = emptyList(), appPackageId = null, wasAppLaunched = false,
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = false,
            failOnCancellation = false,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `uploadV2 returns 1 on CANCELED with failOnCancellation true`() {
        stubUploadV2Response(platform = "IOS")
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1", status = UploadStatus.Status.CANCELED,
            completed = true, totalTime = 30L, startTime = 0L,
            flows = emptyList(), appPackageId = null, wasAppLaunched = false,
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = false,
            failOnCancellation = true,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `uploadV2 returns 1 on STOPPED`() {
        stubUploadV2Response(platform = "IOS")
        every { mockApiClient.uploadStatus(any(), any(), any()) } returns UploadStatus(
            uploadId = "upload_1", status = UploadStatus.Status.STOPPED,
            completed = true, totalTime = 30L, startTime = 0L,
            flows = emptyList(), appPackageId = null, wasAppLaunched = false,
        )

        val result = createCloudInteractor().uploadV2(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = false,
            projectId = "proj_1",
        )

        assertThat(result).isEqualTo(1)
    }

    // ==== Validation ordering (2) ====

    @Test
    fun `uploadV2 does not call uploadV2 when app validation fails`() {
        assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = androidFlowFile(),
                appFile = null,
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(uploadV2Called).isFalse()
    }

    @Test
    fun `uploadV2 does not call uploadV2 when workspace validation fails`() {
        val flowFile = createFlowFile("com.nonexistent.app")

        assertThrows<CliError> {
            createCloudInteractor().uploadV2(
                flowFile = flowFile,
                appFile = iosApp(),
                async = true,
                projectId = "proj_1",
            )
        }

        assertThat(uploadV2Called).isFalse()
    }

    // ==== Workspace config merging into DeviceSpec (2) ====

    private fun iosFlowWithConfig(): File = resourceFile("/workspaces/cloud_test/ios_with_config")

    @Test
    fun `uploadV2 merges workspace disableAnimations false into iOS DeviceSpec overriding default true`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowWithConfig(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
        )

        assertThat(uploadV2Called).isTrue()
        val spec = capturedDeviceSpec as DeviceSpec.Ios
        assertThat(spec.disableAnimations).isFalse()
    }

    @Test
    fun `uploadV2 merges workspace snapshotKeyHonorModalViews false into iOS DeviceSpec overriding default true`() {
        stubUploadV2Response(platform = "IOS")

        createCloudInteractor().uploadV2(
            flowFile = iosFlowWithConfig(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
        )

        assertThat(uploadV2Called).isTrue()
        val spec = capturedDeviceSpec as DeviceSpec.Ios
        assertThat(spec.snapshotKeyHonorModalViews).isFalse()
    }
}
