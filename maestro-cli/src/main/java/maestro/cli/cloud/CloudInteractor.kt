package maestro.cli.cloud

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.api.UploadStatus
import maestro.cli.auth.Auth
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.ReportFormat
import maestro.cli.report.ReporterFactory
import maestro.cli.util.FileUtils.isZip
import maestro.cli.util.PrintUtils
import maestro.cli.util.WorkspaceUtils
import maestro.cli.view.ProgressBar
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel.Companion.toViewModel
import maestro.cli.view.TestSuiteStatusView.uploadUrl
import maestro.utils.TemporaryDirectory
import okio.BufferedSink
import okio.buffer
import okio.sink
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolute

class CloudInteractor(
    private val client: ApiClient,
    private val auth: Auth = Auth(client),
    private val waitTimeoutMs: Long = TimeUnit.MINUTES.toMillis(30),
    private val minPollIntervalMs: Long = TimeUnit.SECONDS.toMillis(10),
    private val maxPollingRetries: Int = 3,
    private val failOnTimeout: Boolean = false,
) {

    fun upload(
        flowFile: File,
        appFile: File,
        async: Boolean,
        mapping: File? = null,
        apiKey: String? = null,
        uploadName: String? = null,
        repoOwner: String? = null,
        repoName: String? = null,
        branch: String? = null,
        pullRequestId: String? = null,
        env: Map<String, String> = emptyMap(),
        androidApiLevel: Int? = null,
        failOnCancellation: Boolean = false,
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
        reportFormat: ReportFormat = ReportFormat.NOOP,
        reportOutput: File? = null,
    ): Int {
        if (!flowFile.exists()) throw CliError("File does not exist: ${flowFile.absolutePath}")
        if (mapping?.exists() == false) throw CliError("File does not exist: ${mapping.absolutePath}")
        if (async && reportFormat != ReportFormat.NOOP) throw CliError("Cannot use --format with --async")

        val authToken = apiKey              // Check for API key
            ?: auth.getCachedAuthToken()    // Otherwise, if the user has already logged in, use the cached auth token
            ?: auth.triggerSignInFlow()     // Otherwise, trigger the sign-in flow

        PrintUtils.message("Uploading Flow(s)...")

        TemporaryDirectory.use { tmpDir ->
            val workspaceZip = tmpDir.resolve("workspace.zip")
            WorkspaceUtils.createWorkspaceZip(flowFile.toPath().absolute(), workspaceZip, includeTags, excludeTags)
            println()
            val progressBar = ProgressBar(20)

            val appFileToSend = if (appFile.isZip()) {
                appFile
            } else {
                val archiver = ArchiverFactory.createArchiver(ArchiveFormat.ZIP)

                // An awkward API of Archiver that has a different behaviour depending on
                // whether we call a vararg method or a normal method. The *arrayOf() construct
                // forces compiler to choose vararg method.
                @Suppress("RemoveRedundantSpreadOperator")
                archiver.create(appFile.name + ".zip", tmpDir.toFile(), *arrayOf(appFile.absoluteFile))
            }

            val (teamId, appId, uploadId) = client.upload(
                authToken,
                appFileToSend.toPath(),
                workspaceZip,
                uploadName,
                mapping?.toPath(),
                repoOwner,
                repoName,
                branch,
                pullRequestId,
                env,
                androidApiLevel,
                includeTags,
                excludeTags,
            ) { totalBytes, bytesWritten ->
                progressBar.set(bytesWritten.toFloat() / totalBytes.toFloat())
            }
            println()

            if (async) {
                PrintUtils.message("✅ Upload successful! View the results of your upload below:")
                PrintUtils.message(uploadUrl(uploadId, teamId, appId))

                return 0
            } else {
                PrintUtils.message("Visit the web console for more details about the upload: ${uploadUrl(uploadId, teamId, appId)}")
                PrintUtils.message("Waiting for analyses to complete...")
                println()

                return waitForCompletion(
                    authToken = authToken,
                    uploadId = uploadId,
                    teamId = teamId,
                    appId = appId,
                    failOnCancellation = failOnCancellation,
                    reportFormat = reportFormat,
                    reportOutput = reportOutput,
                )
            }
        }
    }

    private fun waitForCompletion(
        authToken: String,
        uploadId: String,
        teamId: String,
        appId: String,
        failOnCancellation: Boolean,
        reportFormat: ReportFormat,
        reportOutput: File?,
    ): Int {
        val startTime = System.currentTimeMillis()

        val reportedCompletions = mutableSetOf<String>()

        var pollingInterval = minPollIntervalMs
        var retryCounter = 0
        do {
            val upload = try {
                client.uploadStatus(authToken, uploadId)
            } catch (e: ApiClient.ApiException) {
                if (e.statusCode == 429) {
                    // back off through extending sleep duration with 25%
                    pollingInterval = (pollingInterval * 1.25).toLong()
                    Thread.sleep(pollingInterval)
                    continue
                }

                if (e.statusCode == 500) {
                    if (++retryCounter <= maxPollingRetries) {
                        // retry on 500
                        Thread.sleep(pollingInterval)
                        continue
                    }
                }

                throw CliError("Failed to fetch the status of an upload $uploadId. Status code = ${e.statusCode}")
            }

            upload.flows
                .filter {
                    it.name !in reportedCompletions && it.status in COMPLETED_STATUSES
                }
                .forEach {
                    TestSuiteStatusView.showFlowCompletion(
                        it.toViewModel()
                    )

                    reportedCompletions.add(it.name)
                }

            if (upload.completed) {
                return handleSyncUploadCompletion(
                    upload = upload,
                    teamId = teamId,
                    appId = appId,
                    failOnCancellation = failOnCancellation,
                    reportFormat = reportFormat,
                    reportOutput = reportOutput,
                )
            }

            Thread.sleep(pollingInterval)
        } while (System.currentTimeMillis() - startTime < waitTimeoutMs)

        PrintUtils.warn("Upload did not complete in time due to an issue on mobile.dev side. Follow the results of your upload below:")
        println(uploadUrl(uploadId, teamId, appId))

        return if (failOnTimeout) {
            PrintUtils.err("FAIL")

            1
        } else {
            PrintUtils.warn("SKIPPED")

            0
        }
    }

    private fun handleSyncUploadCompletion(
        upload: UploadStatus,
        teamId: String,
        appId: String,
        failOnCancellation: Boolean,
        reportFormat: ReportFormat,
        reportOutput: File?,
    ): Int {
        TestSuiteStatusView.showSuiteResult(
            upload.toViewModel(
                TestSuiteStatusView.TestSuiteViewModel.UploadDetails(
                    uploadId = upload.uploadId,
                    teamId = teamId,
                    appId = appId,
                )
            )
        )

        val passed = if (upload.status == UploadStatus.Status.CANCELED && failOnCancellation) {
            false
        } else upload.status != UploadStatus.Status.ERROR

        val reportOutputSink = reportFormat.fileExtension
            ?.let { extension ->
                (reportOutput ?: File("report$extension"))
                    .sink()
                    .buffer()
            }

        if (reportOutputSink != null) {
            saveReport(reportFormat, passed, upload, reportOutputSink)
        }

        return if (!passed) {
            1
        } else {
            0
        }
    }

    private fun saveReport(
        reportFormat: ReportFormat,
        passed: Boolean,
        upload: UploadStatus,
        reportOutputSink: BufferedSink
    ) {
        ReporterFactory.buildReporter(reportFormat)
            .report(
                TestExecutionSummary(
                    passed = passed,
                    suites = listOf(
                        TestExecutionSummary.SuiteResult(
                            passed = passed,
                            flows = upload.flows.map { flow ->
                                TestExecutionSummary.FlowResult(
                                    name = flow.name,
                                    fileName = null,
                                    status = FlowStatus.from(flow.status),
                                )
                            }
                        )
                    )
                ),
                reportOutputSink,
            )
    }

    companion object {

        private val COMPLETED_STATUSES = setOf(
            UploadStatus.Status.CANCELED,
            UploadStatus.Status.WARNING,
            UploadStatus.Status.SUCCESS,
            UploadStatus.Status.ERROR,
        )

    }

}
