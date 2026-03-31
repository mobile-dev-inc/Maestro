package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import maestro.cli.api.ApiClient
import maestro.cli.api.UploadStatus
import maestro.cli.auth.Auth
import maestro.cli.model.FlowStatus
import maestro.cli.report.ReportFormat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

class CloudInteractorTest {

    private lateinit var mockApiClient: ApiClient
    private lateinit var mockAuth: Auth
    private lateinit var cloudInteractor: CloudInteractor
    
    private lateinit var originalOut: PrintStream
    private lateinit var outputStream: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        mockApiClient = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        cloudInteractor = CloudInteractor(
            client = mockApiClient,
            auth = mockAuth,
            waitTimeoutMs = TimeUnit.SECONDS.toMillis(1), // Short timeout for testing
            minPollIntervalMs = TimeUnit.MILLISECONDS.toMillis(10), // Short polling for testing
            maxPollingRetries = 2,
            failOnTimeout = true
        )
        
        // Capture console output
        originalOut = System.out
        outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
    }
    
    @AfterEach
    fun tearDown() {
        // Restore original console output
        System.setOut(originalOut)
    }

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
        val result = cloudInteractor.waitForCompletion(
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

        // then
        assertThat(result.status).isEqualTo(UploadStatus.Status.SUCCESS)
        verify(exactly = 1) { mockApiClient.uploadStatus("token", "upload123", "project123") }
        
        // Verify console output
        val output = outputStream.toString()
        // Strip ANSI color codes to get clean text for comparison
        val cleanOutput = output.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        // Now we can check the complete lines as they appear
        assertThat(cleanOutput).contains("[Passed] flow1 (50ms)")
        assertThat(cleanOutput).contains("[Passed] flow2 (50ms)")
        assertThat(cleanOutput).contains("2/2 Flows Passed")
        assertThat(cleanOutput).contains("Process will exit with code 0 (SUCCESS)")
        assertThat(cleanOutput).contains("http://example.com")

        // Verify each flow result appears only once
        val flow1Occurrences = cleanOutput.split("[Passed] flow1 (50ms)").size - 1
        val flow2Occurrences = cleanOutput.split("[Passed] flow2 (50ms)").size - 1
        assertThat(flow1Occurrences).isEqualTo(1)
        assertThat(flow2Occurrences).isEqualTo(1)
    }

    @Test
    fun `waitForCompletion should handle status changes and eventually complete`() {
        // Create different upload statuses for different polling attempts
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

        // Mock API to return different statuses on subsequent calls
        every { mockApiClient.uploadStatus(any(), any(), any()) } returnsMany listOf(
            initialStatus,  
            initialStatus,
            intermediateStatus,
            intermediateStatus,
            intermediateStatus,
            finalStatus
        )

        val result = cloudInteractor.waitForCompletion(
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

        // then
        assertThat(result.status).isEqualTo(UploadStatus.Status.SUCCESS)
        // The overall upload status is SUCCESS, but individual flows can fail
        verify(exactly = 6) { mockApiClient.uploadStatus("token", "upload123", "project123") }
        
        // Verify console output shows the progression
        val output = outputStream.toString()
        // Strip ANSI color codes to get clean text for comparison
        val cleanOutput = output.replace(Regex("\\u001B\\[[;\\d]*m"), "")
        // Should show final results
        assertThat(cleanOutput).contains("[Passed] flow1 (45ms)")
        assertThat(cleanOutput).contains("[Failed] flow2 (60ms)")
        assertThat(cleanOutput).contains("[Stopped] flow3")
        assertThat(cleanOutput).contains("1/3 Flow Failed")
        assertThat(cleanOutput).contains("Process will exit with code 1 (FAIL)")
        assertThat(cleanOutput).contains("http://example.com")
        
        // Verify each flow result appears only once
        val flow1Occurrences = cleanOutput.split("[Passed] flow1 (45ms)").size - 1
        val flow2Occurrences = cleanOutput.split("[Failed] flow2 (60ms)").size - 1
        assertThat(flow1Occurrences).isEqualTo(1)
        assertThat(flow2Occurrences).isEqualTo(1)
    }

    @Test
    fun `getAppBinaryInfo is called when appBinaryId is provided and result used for DeviceSpec`() {
        val binaryInfo = maestro.cli.api.AppBinaryInfo(
            appBinaryId = "app.apk",
            platform = "Android",
            appId = "com.example.app",
        )
        every { mockApiClient.getAppBinaryInfo(any(), "app.apk") } returns binaryInfo
        every { mockAuth.getAuthToken(any(), any()) } returns "test-token"
        every { mockApiClient.getProjects(any()) } returns listOf(
            maestro.cli.api.ProjectResponse(id = "proj_1", name = "My Project")
        )
        var capturedDeviceSpec: maestro.device.DeviceSpec? = null
        // Register instance factory so MockK can create a dummy DeviceSpec for matcher signatures
        val factory = object : io.mockk.MockKGateway.InstanceFactory {
            override fun instantiate(cls: kotlin.reflect.KClass<*>): Any? {
                if (cls == maestro.device.DeviceSpec::class) {
                    return maestro.device.DeviceSpec.fromRequest(maestro.device.DeviceSpecRequest.Android())
                }
                return null
            }
        }
        io.mockk.MockKGateway.implementation().instanceFactoryRegistry.registerFactory(factory)
        every { mockApiClient.upload(
            authToken = any(),
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
            androidApiLevel = any(),
            iOSVersion = any(),
            appBinaryId = any(),
            includeTags = any(),
            excludeTags = any(),
            disableNotifications = any(),
            deviceLocale = any(),
            progressListener = any(),
            projectId = any(),
            deviceModel = any(),
            deviceOs = any(),
        ) } answers {
            capturedDeviceSpec = args.last() as? maestro.device.DeviceSpec
            maestro.cli.api.UploadResponse(
                orgId = "org_1",
                uploadId = "upload_1",
                appId = "app_1",
                deviceConfiguration = maestro.cli.api.DeviceConfiguration(
                    platform = "Android",
                    deviceName = "Pixel 6",
                    orientation = "portrait",
                    osVersion = "35",
                    displayInfo = "Pixel 6",
                    deviceLocale = "en_US"
                ),
                appBinaryId = "app.apk",
            )
        }

        val tempDir = createTempDir()
        val flowFile = File(tempDir, "flow.yaml").also {
            it.writeText("appId: com.example.app\n---\n- launchApp")
        }

        cloudInteractor.upload(
            flowFile = flowFile,
            appFile = null,
            async = true,
            appBinaryId = "app.apk",
            projectId = "proj_1",
        )

        verify(exactly = 1) { mockApiClient.getAppBinaryInfo("test-token", "app.apk") }

        // Verify deviceSpec was captured and has the correct platform
        assertThat(capturedDeviceSpec).isNotNull()
        assertThat(capturedDeviceSpec!!.platform).isEqualTo(maestro.device.Platform.ANDROID)

        tempDir.deleteRecursively()
    }

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
