package maestro.cli.cloud

import maestro.cli.CliError
import maestro.cli.analytics.Analytics
import maestro.cli.analytics.CloudUploadStartedEvent
import maestro.cli.analytics.CloudUploadTriggeredEvent
import maestro.cli.api.ApiClient
import maestro.cli.api.DeviceConfiguration
import maestro.cli.api.OrgResponse
import maestro.cli.api.ProjectResponse
import maestro.cli.api.UploadStatus
import maestro.cli.auth.Auth
import maestro.device.Platform
import maestro.cli.insights.AnalysisDebugFiles
import maestro.cli.model.FlowStatus
import maestro.cli.model.RunningFlow
import maestro.cli.model.RunningFlows
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.HtmlInsightsAnalysisReporter
import maestro.cli.report.ReportFormat
import maestro.cli.report.ReporterFactory
import maestro.cli.util.FileUtils.isWebFlow
import maestro.cli.util.FileUtils.isZip
import maestro.cli.util.PrintUtils
import maestro.cli.util.WorkspaceUtils
import maestro.cli.view.ProgressBar
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.input.interactiveSelectList
import maestro.cli.analytics.CloudRunFinishedEvent
import maestro.cli.analytics.CloudUploadSucceededEvent
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel.Companion.toViewModel
import maestro.cli.view.TestSuiteStatusView.uploadUrl
import maestro.cli.view.box
import maestro.cli.view.cyan
import maestro.cli.view.render
import maestro.cli.web.WebInteractor
import maestro.utils.TemporaryDirectory
import okio.BufferedSink
import okio.buffer
import okio.sink
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.String
import kotlin.io.path.absolute
import kotlin.time.Duration.Companion.milliseconds

val terminalStatuses = listOf<FlowStatus>(FlowStatus.CANCELED, FlowStatus.STOPPED, FlowStatus.SUCCESS, FlowStatus.ERROR)

class CloudInteractor(
    private val client: ApiClient,
    private val auth: Auth = Auth(client),
    private val waitTimeoutMs: Long = TimeUnit.MINUTES.toMillis(30),
    private val minPollIntervalMs: Long = TimeUnit.SECONDS.toMillis(10),
    private val maxPollingRetries: Int = 5,
    private val failOnTimeout: Boolean = true,
) {

    fun upload(
        flowFile: File,
        appFile: File?,
        async: Boolean,
        mapping: File? = null,
        apiKey: String? = null,
        uploadName: String? = null,
        repoOwner: String? = null,
        repoName: String? = null,
        branch: String? = null,
        commitSha: String? = null,
        pullRequestId: String? = null,
        env: Map<String, String> = emptyMap(),
        androidApiLevel: Int? = null,
        iOSVersion: String? = null,
        appBinaryId: String? = null,
        failOnCancellation: Boolean = false,
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
        reportFormat: ReportFormat = ReportFormat.NOOP,
        reportOutput: File? = null,
        testSuiteName: String? = null,
        disableNotifications: Boolean = false,
        deviceLocale: String? = null,
        projectId: String? = null,
        deviceModel: String? = null,
        deviceOs: String? = null,
    ): Int {
        if (appBinaryId == null && appFile == null && !flowFile.isWebFlow()) throw CliError("Missing required parameter for option '--app-file' or '--app-binary-id'")
        if (!flowFile.exists()) throw CliError("File does not exist: ${flowFile.absolutePath}")
        if (mapping?.exists() == false) throw CliError("File does not exist: ${mapping.absolutePath}")
        if (async && reportFormat != ReportFormat.NOOP) throw CliError("Cannot use --format with --async")

        // In case apiKey is provided use that, else fallback to signIn and org Selection
        val authToken: String = auth.getAuthToken(apiKey, triggerSignIn = false) ?:
          selectOrganization(auth.getAuthToken(apiKey, triggerSignIn = true) ?:
          throw CliError("Failed to get authentication token"))

        // Fetch and select project if not provided
        val selectedProjectId = projectId ?: selectProject(authToken)

        // Track cloud upload triggered - this fires as soon as the command is validated and ready to proceed
        val triggeredPlatform = when {
            androidApiLevel != null -> "android"
            iOSVersion != null || deviceOs != null -> "ios"
            flowFile.isWebFlow() -> "web"
            else -> "unknown"
        }
        Analytics.trackEvent(CloudUploadTriggeredEvent(
            projectId = selectedProjectId,
            platform = triggeredPlatform,
            isBinaryUpload = appBinaryId != null,
            usesEnvironment = env.isNotEmpty(),
            deviceModel = deviceModel,
            deviceOs = deviceOs
        ))
      
        PrintUtils.message("Uploading Flow(s)...")

        TemporaryDirectory.use { tmpDir ->
            val workspaceZip = tmpDir.resolve("workspace.zip")
            WorkspaceUtils.createWorkspaceZip(flowFile.toPath().absolute(), workspaceZip)
            val progressBar = ProgressBar(20)

            // Binary id or Binary file
            val appFileToSend = getAppFile(appFile, appBinaryId, tmpDir, flowFile)

            // Track cloud upload start after we have the response with actual platform
            Analytics.trackEvent(CloudUploadStartedEvent(
                projectId = selectedProjectId,
                platform = triggeredPlatform,
                isBinaryUpload = appBinaryId != null,
                usesEnvironment = env.isNotEmpty(),
                deviceModel = deviceModel,
                deviceOs = deviceOs
            ))

            val response = client.upload(
                authToken = authToken,
                appFile = appFileToSend?.toPath(),
                workspaceZip = workspaceZip,
                uploadName = uploadName,
                mappingFile = mapping?.toPath(),
                repoOwner = repoOwner,
                repoName = repoName,
                branch = branch,
                commitSha = commitSha,
                pullRequestId = pullRequestId,
                env = env,
                androidApiLevel = androidApiLevel,
                iOSVersion = iOSVersion,
                appBinaryId = appBinaryId,
                includeTags = includeTags,
                excludeTags = excludeTags,
                disableNotifications = disableNotifications,
                deviceLocale = deviceLocale,
                projectId = selectedProjectId,
                progressListener = { totalBytes, bytesWritten ->
                    progressBar.set(bytesWritten.toFloat() / totalBytes.toFloat())
                },
                deviceModel = deviceModel,
                deviceOs = deviceOs
            )

            // Track finish after upload completion
            val platform = response.deviceConfiguration?.platform?.lowercase() ?: "unknown"
            Analytics.trackEvent(CloudUploadSucceededEvent(
                projectId = selectedProjectId,
                platform = platform,
                isBinaryUpload = appBinaryId != null,
                usesEnvironment = env.isNotEmpty(),
                deviceModel = deviceModel,
                deviceOs = deviceOs
            ))

            val project = requireNotNull(selectedProjectId)
            val appId = response.appId
            val uploadUrl = uploadUrl(project, appId, response.uploadId, client.domain)
            val deviceMessage =
                if (response.deviceConfiguration != null) printDeviceInfo(response.deviceConfiguration) else ""

            val uploadResponse = printMaestroCloudResponse(
                async,
                authToken,
                failOnCancellation,
                reportFormat,
                reportOutput,
                testSuiteName,
                uploadUrl,
                deviceMessage,
                appId,
                response.appBinaryId,
                response.uploadId,
                selectedProjectId,
            )

            Analytics.trackEvent(CloudRunFinishedEvent(
                projectId = selectedProjectId,
                totalFlows = uploadResponse.flows.size,
                totalPassedFlows = uploadResponse.flows.count { it.status == FlowStatus.SUCCESS },
                totalFailedFlows = uploadResponse.flows.count { it.status == FlowStatus.ERROR },
                appPackageId = uploadResponse.appPackageId ?: "",
                wasAppLaunched = uploadResponse.wasAppLaunched
            ))

            Analytics.flush()
            
            return when (uploadResponse.status) {
                UploadStatus.Status.SUCCESS -> 0
                UploadStatus.Status.ERROR -> 1
                UploadStatus.Status.CANCELED -> if (failOnCancellation) 1 else 0
                UploadStatus.Status.STOPPED -> 1
                else -> 1
            }
        }
    }

    private fun selectProject(authToken: String): String {
        val projects = try {
            client.getProjects(authToken)
        } catch (e: ApiClient.ApiException) {
            throw CliError("Failed to fetch projects. Status code: ${e.statusCode}")
        } catch (e: Exception) {
            throw CliError("Failed to fetch projects: ${e.message}")
        }

        if (projects.isEmpty()) {
            throw CliError("No projects found. Please create a project first at https://console.mobile.dev")
        }

        return when (projects.size) {
            1 -> {
                val project = projects.first()
                PrintUtils.info("Using project: ${project.name} (${project.id})")
                project.id
            }
            else -> {
                val selectedProject = pickProject(projects)
                PrintUtils.info("Selected project: ${selectedProject.name} (${selectedProject.id})")
                selectedProject.id
            }
        }
    }

    fun pickProject(projects: List<ProjectResponse>): ProjectResponse {
        val terminal = Terminal()
        val choices = projects.map { "${it.name} (${it.id})" }
        
        val selection = terminal.interactiveSelectList(
            choices,
            title = "Multiple projects found. Please select one (Bypass this prompt by using --project-id=<>):"
        )
        
        if (selection == null) {
            terminal.println("No project selected")
            throw CliError("Project selection was cancelled")
        }
        
        val selectedIndex = choices.indexOf(selection)
        return projects[selectedIndex]
    }

    private fun selectOrganization(authToken: String): String {
        val orgs = try {
            client.getOrgs(authToken)
        } catch (e: ApiClient.ApiException) {
            throw CliError("Failed to fetch organizations. Status code: ${e.statusCode}")
        } catch (e: Exception) {
            throw CliError("Failed to fetch organizations: ${e.message}")
        }

        return when (orgs.size) {
            1 -> {
                val org = orgs.first()
                PrintUtils.message("Using organization: ${org.name} (${org.id})")
                authToken
            }
            else -> {
                val selectedOrg = pickOrganization(orgs)
                PrintUtils.info("Selected organization: ${selectedOrg.name} (${selectedOrg.id})")
                // Switch to the selected organization to get org-scoped token
                try {
                    client.switchOrg(authToken, selectedOrg.id)
                } catch (e: ApiClient.ApiException) {
                    throw CliError("Failed to switch to organization. Status code: ${e.statusCode}")
                } catch (e: Exception) {
                    throw CliError("Failed to switch to organization: ${e.message}")
                }
            }
        }
    }

    fun pickOrganization(orgs: List<OrgResponse>): OrgResponse {
        val terminal = Terminal()
        val choices = orgs.map { "${it.name} (${it.id})" }
        
        val selection = terminal.interactiveSelectList(
            choices,
            title = "Multiple organizations found. Please select one (Bypass this prompt by using --api-key=<>):",
        )
        
        if (selection == null) {
            terminal.println("No organization selected")
            throw CliError("Organization selection was cancelled")
        }
        
        val selectedIndex = choices.indexOf(selection)
        return orgs[selectedIndex]
    }

    private fun getAppFile(
        appFile: File?,
        appBinaryId: String?,
        tmpDir: Path,
        flowFile: File
    ): File? {
        when {
            appBinaryId != null -> return null

            appFile != null -> if (appFile.isZip()) {
                return appFile
            } else {
                val archiver = ArchiverFactory.createArchiver(ArchiveFormat.ZIP)

                // An awkward API of Archiver that has a different behaviour depending on
                // whether we call a vararg method or a normal method. The *arrayOf() construct
                // forces compiler to choose vararg method.
                @Suppress("RemoveRedundantSpreadOperator")
                return archiver.create(appFile.name + ".zip", tmpDir.toFile(), *arrayOf(appFile.absoluteFile))
            }

            flowFile.isWebFlow() -> return WebInteractor.createManifestFromWorkspace(flowFile)

            else -> return null
        }
    }

    private fun printMaestroCloudResponse(
        async: Boolean,
        authToken: String,
        failOnCancellation: Boolean,
        reportFormat: ReportFormat,
        reportOutput: File?,
        testSuiteName: String?,
        uploadUrl: String,
        deviceInfoMessage: String,
        appId: String,
        appBinaryIdResponse: String?,
        uploadId: String,
        projectId: String
    ): UploadStatus {
        if (async) {
            PrintUtils.message("✅ Upload successful!")

            println(deviceInfoMessage)
            PrintUtils.info("View the results of your upload below:")
            PrintUtils.info(uploadUrl.cyan())

            if (appBinaryIdResponse != null) PrintUtils.info("App binary id: ${appBinaryIdResponse.cyan()}\n")

            // Return a simple UploadStatus for async case
            return UploadStatus(
                uploadId = uploadId,
                status = UploadStatus.Status.SUCCESS,
                completed = true,
                totalTime = null,
                startTime = null,
                flows = emptyList(),
                appPackageId = null,
                wasAppLaunched = false,
            )
        } else {
            println(deviceInfoMessage)
            
            // Print the upload URL
            PrintUtils.info("Visit Maestro Cloud for more details about this upload:")
            PrintUtils.info(uploadUrl.cyan())
            println()

            if (appBinaryIdResponse != null) PrintUtils.info("App binary id: ${appBinaryIdResponse.cyan()}\n")

            PrintUtils.info("Waiting for runs to be completed...")

            return waitForCompletion(
                authToken = authToken,
                uploadId = uploadId,
                appId = appId,
                failOnCancellation = failOnCancellation,
                reportFormat = reportFormat,
                reportOutput = reportOutput,
                testSuiteName = testSuiteName,
                uploadUrl = uploadUrl,
                projectId = projectId,
            )
        }
    }

    private fun printDeviceInfo(deviceConfiguration: DeviceConfiguration): String {
        val platform = Platform.fromString(deviceConfiguration.platform)
        PrintUtils.info("\n")

        val version = deviceConfiguration.osVersion
        val lines = listOf(
            "Maestro cloud device specs:\n* @|magenta ${deviceConfiguration.displayInfo} - ${deviceConfiguration.deviceLocale}|@\n",
            "To change OS version use this option: @|magenta ${if (platform == Platform.IOS) "--device-os=<version>" else "--android-api-level=<version>"}|@",
            "To change devices use this option: @|magenta --device-model=<device_model>|@",
            "To change device locale use this option: @|magenta --device-locale=<device_locale>|@",
            "To create a similar device locally, run: @|magenta `maestro start-device --platform=${
                platform.toString().lowercase()
            } --os-version=$version --device-locale=${deviceConfiguration.deviceLocale}`|@"
        )

        return lines.joinToString("\n").render().box()
    }


    internal fun waitForCompletion(
        authToken: String,
        uploadId: String,
        appId: String,
        failOnCancellation: Boolean,
        reportFormat: ReportFormat,
        reportOutput: File?,
        testSuiteName: String?,
        uploadUrl: String,
        projectId: String?
    ): UploadStatus {
        val startTime = System.currentTimeMillis()

        var pollingInterval = minPollIntervalMs
        var retryCounter = 0
        val printedFlows = mutableSetOf<UploadStatus.FlowResult>()

        do {
            val upload: UploadStatus = try {
                client.uploadStatus(authToken, uploadId, projectId)
            } catch (e: ApiClient.ApiException) {
                if (e.statusCode == 429) {
                    // back off through extending sleep duration with 25%
                    pollingInterval = (pollingInterval * 1.25).toLong()
                    Thread.sleep(pollingInterval)
                    continue
                }

                if (e.statusCode == 500 || e.statusCode == 502 || e.statusCode == 404) {
                    if (++retryCounter <= maxPollingRetries) {
                        // retry on 500
                        Thread.sleep(pollingInterval)
                        continue
                    }
                }

                throw CliError("Failed to fetch the status of an upload $uploadId. Status code = ${e.statusCode}")
            }

            for (uploadFlowResult in upload.flows) {
                if(printedFlows.contains(uploadFlowResult)) { continue }
                if(!terminalStatuses.contains(uploadFlowResult.status)) { continue }

                printedFlows.add(uploadFlowResult);
                TestSuiteStatusView.showFlowCompletion(
                  uploadFlowResult.toViewModel()
                )
            }

            if (upload.completed) {
                val runningFlows = RunningFlows(
                    flows = upload.flows.map { flowResult ->
                        RunningFlow(
                            flowResult.name,
                            flowResult.status,
                            duration = flowResult.totalTime?.milliseconds,
                            startTime = flowResult.startTime
                        )
                    },
                    duration = upload.totalTime?.milliseconds,
                    startTime = upload.startTime
                )
                return handleSyncUploadCompletion(
                    upload = upload,
                    runningFlows = runningFlows,
                    appId = appId,
                    failOnCancellation = failOnCancellation,
                    reportFormat = reportFormat,
                    reportOutput = reportOutput,
                    testSuiteName = testSuiteName,
                    uploadUrl = uploadUrl
                )
            }

            Thread.sleep(pollingInterval)
        } while (System.currentTimeMillis() - startTime < waitTimeoutMs)

        val displayedMin = TimeUnit.MILLISECONDS.toMinutes(waitTimeoutMs)

        PrintUtils.warn("Waiting for flows to complete has timed out ($displayedMin minutes)")
        PrintUtils.warn("* To extend the timeout, run maestro with this option `maestro cloud --timeout=<timeout in minutes>`")

        PrintUtils.warn("* Follow the results of your upload here:\n$uploadUrl")

        if (failOnTimeout) {
            PrintUtils.message("Process will exit with code 1 (FAIL)")
            PrintUtils.message("* To change exit code on Timeout, run maestro with this option: `maestro cloud --fail-on-timeout=<true|false>`")
        } else {
            PrintUtils.message("Process will exit with code 0 (SUCCESS)")
            PrintUtils.message("* To change exit code on Timeout, run maestro with this option: `maestro cloud --fail-on-timeout=<true|false>`")
        }

        // Fetch the latest upload status before returning
        return try {
            client.uploadStatus(authToken, uploadId, projectId)
        } catch (e: Exception) {
            // If we can't fetch the latest status, return a timeout status
            UploadStatus(
                uploadId = uploadId,
                status = UploadStatus.Status.ERROR,
                completed = false,
                totalTime = null,
                startTime = null,
                flows = emptyList(),
                appPackageId = null,
                wasAppLaunched = false,
            )
        }
    }

    private fun handleSyncUploadCompletion(
        upload: UploadStatus,
        runningFlows: RunningFlows,
        appId: String,
        failOnCancellation: Boolean,
        reportFormat: ReportFormat,
        reportOutput: File?,
        testSuiteName: String?,
        uploadUrl: String,
    ): UploadStatus {
        TestSuiteStatusView.showSuiteResult(
            upload.toViewModel(
                TestSuiteStatusView.TestSuiteViewModel.UploadDetails(
                    uploadId = upload.uploadId,
                    appId = appId,
                    domain = client.domain,
                )
            ),
            uploadUrl
        )

        val isCancelled = upload.status == UploadStatus.Status.CANCELED
        val isFailure = upload.status == UploadStatus.Status.ERROR
        val containsFailure =
            upload.flows.find { it.status == FlowStatus.ERROR } != null // status can be cancelled but also contain flow with failure

        val failed = isFailure || containsFailure || isCancelled && failOnCancellation

        val reportOutputSink = reportFormat.fileExtension
            ?.let { extension ->
                (reportOutput ?: File("report$extension"))
                    .sink()
                    .buffer()
            }

        if (reportOutputSink != null) {
            saveReport(
                reportFormat,
                !failed,
                createSuiteResult(!failed, upload, runningFlows),
                reportOutputSink,
                testSuiteName
            )
        }


        if (!failed) {
            PrintUtils.message("Process will exit with code 0 (SUCCESS)")
            if (isCancelled) {
                PrintUtils.message("* To change exit code on Cancellation, run maestro with this option: `maestro cloud --fail-on-cancellation=<true|false>`")
            }
        } else {
            PrintUtils.message("Process will exit with code 1 (FAIL)")
            if (isCancelled && !containsFailure) {
                PrintUtils.message("* To change exit code on cancellation, run maestro with this option: `maestro cloud --fail-on-cancellation=<true|false>`")
            }
        }

        return upload
    }

    private fun saveReport(
        reportFormat: ReportFormat,
        passed: Boolean,
        suiteResult: TestExecutionSummary.SuiteResult,
        reportOutputSink: BufferedSink,
        testSuiteName: String?
    ) {
        ReporterFactory.buildReporter(reportFormat, testSuiteName)
            .report(
                TestExecutionSummary(
                    passed = passed,
                    suites = listOf(suiteResult)
                ),
                reportOutputSink,
            )
    }

    private fun createSuiteResult(
        passed: Boolean,
        upload: UploadStatus,
        runningFlows: RunningFlows
    ): TestExecutionSummary.SuiteResult {
        return TestExecutionSummary.SuiteResult(
            passed = passed,
            flows = upload.flows.map { uploadFlowResult ->
                val failure = uploadFlowResult.errors.firstOrNull()
                val currentRunningFlow = runningFlows.flows.find { it.name == uploadFlowResult.name }
                TestExecutionSummary.FlowResult(
                    name = uploadFlowResult.name,
                    fileName = null,
                    status = uploadFlowResult.status,
                    failure = if (failure != null) TestExecutionSummary.Failure(failure) else null,
                    duration = currentRunningFlow?.duration,
                    startTime = currentRunningFlow?.startTime
                )
            },
            duration = runningFlows.duration,
            startTime = runningFlows.startTime
        )
    }

    fun analyze(
        apiKey: String?,
        debugFiles: AnalysisDebugFiles,
        debugOutputPath: Path,
    ): Int {
        val authToken = auth.getAuthToken(apiKey)
        if (authToken == null) throw CliError("Failed to get authentication token")

        PrintUtils.info("\n\uD83D\uDD0E Analyzing Flow(s)...")

        try {
            val response = client.analyze(authToken, debugFiles)

            if (response.htmlReport.isNullOrEmpty()) {
                PrintUtils.info(response.output)
                return 0
            }

            val outputFilePath = HtmlInsightsAnalysisReporter().report(response.htmlReport, debugOutputPath)
            val os = System.getProperty("os.name").lowercase(Locale.getDefault())

            val formattedOutput = response.output.replace(
                "{{outputFilePath}}",
                "file:${if (os.contains("win")) "///" else "//"}${outputFilePath}\n"
            )

            PrintUtils.info(formattedOutput);
            return 0;
        } catch (error: CliError) {
            PrintUtils.err("Unexpected error while analyzing Flow(s): ${error.message}")
            return 1
        }
    }
}
