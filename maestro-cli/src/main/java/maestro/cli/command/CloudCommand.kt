/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ConfigFileMixin
import maestro.cli.DeviceConfigMixin
import maestro.cli.DisableAnsiMixin
import maestro.cli.EnvMixin
import maestro.cli.ReportOutputMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.TagFilterMixin
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.orchestra.validation.AppMetadataAnalyzer
import maestro.cli.web.WebInteractor
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.FileUtils.isWebFlow
import maestro.cli.util.PrintUtils
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import maestro.orchestra.util.Env.withDefaultEnvVars

@CommandLine.Command(
    name = "cloud",
    description = [
        "Upload your flows on Cloud by using @|yellow `maestro cloud sample/app.apk flows_folder/`|@ (@|cyan https://app.maestro.dev|@)",
        "Provide your application file and a folder with Maestro flows to run them in parallel on multiple devices in the cloud",
        "By default, the command will block until all analyses have completed. You can use the --async flag to run the command asynchronously and exit immediately.",
    ]
)
class CloudCommand : Callable<Int> {

    @CommandLine.Spec
    var spec: CommandLine.Model.CommandSpec? = null

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.Parameters(hidden = true, arity = "0..2", description = ["App file and/or Flow file i.e <appFile> <flowFile>"])
    private lateinit var files: List<File>

    @CommandLine.Mixin
    var configFileMixin = ConfigFileMixin()

    @Option(names = ["--app-file"], description = ["App binary to run your Flows against"])
    private var appFile: File? = null

    @Option(order = 1, names = ["--flows"], description = ["A Flow filepath or a folder path that contains Flows"])
    private lateinit var flowsFile: File

    @Option(order = 0, names = ["--api-key", "--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @Option(order = 1, names = ["--project-id", "--projectId"], description = ["Project Id"])
    private var projectId: String? = null

    @Option(order = 2, names = ["--api-url", "--apiUrl"], description = ["API base URL"])
    private var apiUrl: String? = null

    @Option(order = 3, names = ["--mapping"], description = ["dSYM file (iOS) or Proguard mapping file (Android)"])
    private var mapping: File? = null

    @Option(order = 4, names = ["--repo-owner", "--repoOwner"], description = ["Repository owner (ie: GitHub organization or user slug)"])
    private var repoOwner: String? = null

    @Option(order = 5, names = ["--repo-name", "--repoName"], description = ["Repository name (ie: GitHub repo slug)"])
    private var repoName: String? = null

    @Option(order = 6, names = ["--branch"], description = ["The branch this upload originated from"])
    private var branch: String? = null

    @Option(order = 7, names = ["--commit-sha", "--commitSha"], description = ["The commit SHA of this upload"])
    private var commitSha: String? = null

    @Option(order = 8, names = ["--pull-request-id", "--pullRequestId"], description = ["The ID of the pull request this upload originated from"])
    private var pullRequestId: String? = null

    @CommandLine.Mixin
    var envMixin = EnvMixin()

    @Option(order = 10, names = ["--name"], description = ["Name of the upload"])
    private var uploadName: String? = null

    @Option(order = 11, names = ["--async"], description = ["Run the upload asynchronously"])
    private var async: Boolean = false

    @Deprecated("Use --device-os instead")
    @Option(order = 12, hidden = true, names = ["--android-api-level"], description = ["Android API level to run your flow against"])
    private var androidApiLevel: Int? = null

    @CommandLine.Mixin
    var tagFilterMixin = TagFilterMixin()

    @CommandLine.Mixin
    var reportOutputMixin = ReportOutputMixin()

    @Deprecated("Use --device-os instead")
    @Option(order = 17, hidden = true, names = ["--ios-version"], description = ["iOS version to run your flow against. Please use --device-os instead"])
    private var iOSVersion: String? = null

    @Option(order = 18, names = ["--app-binary-id", "--appBinaryId"], description = ["The ID of the app binary previously uploaded to Maestro Cloud"])
    private var appBinaryId: String? = null

    @CommandLine.Mixin
    var deviceConfigMixin = DeviceConfigMixin()

    @Option(hidden = true, names = ["--fail-on-cancellation"], description = ["Fail the command if the upload is marked as cancelled"])
    private var failOnCancellation: Boolean = false

    @Option(hidden = true, names = ["--fail-on-timeout"], description = ["Fail the command if the upload times outs"])
    private var failOnTimeout: Boolean = true

    @Option(hidden = true, names = ["--disable-notifications"], description = ["Do not send the notifications configured in config.yaml"])
    private var disableNotifications = false

    @Option(hidden = true, names = ["--timeout"], description = ["Minutes to wait until all flows complete"])
    private var resultWaitTimeout = 60

    @CommandLine.ParentCommand
    private val parent: App? = null

    override fun call(): Int {
        TestDebugReporter.install(
            debugOutputPathAsString = null,
            flattenDebugOutput = false,
            printToConsole = parent?.verbose == true,
        )

        if (androidApiLevel != null) {
            PrintUtils.warn("--android-api-level is deprecated and will be removed in a future release. Use --device-os instead (e.g. --device-os=android-34).")
        }
        if (iOSVersion != null) {
            PrintUtils.warn("--ios-version is deprecated and will be removed in a future release. Use --device-os instead (e.g. --device-os=iOS-18-2).")
        }

        validateFiles()
        validateWorkSpace()

        // Upload
        val apiUrl = apiUrl ?: "https://api.copilot.mobile.dev"

        val env = envMixin.env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(flowsFile)

        val apiClient = ApiClient(apiUrl)
        val webManifestProvider = if (flowsFile.isWebFlow()) {
            { WebInteractor.createManifestFromWorkspace(flowsFile) }
        } else null

        return CloudInteractor(
            client = apiClient,
            appFileValidator = { AppMetadataAnalyzer.validateAppFile(it) },
            workspaceValidator = maestro.orchestra.validation.WorkspaceValidator(),
            webManifestProvider = webManifestProvider,
            failOnTimeout = failOnTimeout,
            waitTimeoutMs = TimeUnit.MINUTES.toMillis(resultWaitTimeout.toLong())
        ).upload(
            async = async,
            flowFile = flowsFile,
            appFile = appFile,
            configFile = configFileMixin.configFile,
            mapping = mapping,
            env = env,
            uploadName = uploadName,
            repoOwner = repoOwner,
            repoName = repoName,
            branch = branch,
            commitSha = commitSha,
            pullRequestId = pullRequestId,
            apiKey = apiKey,
            appBinaryId = appBinaryId,
            includeTags = tagFilterMixin.includeTags,
            excludeTags = tagFilterMixin.excludeTags,
            reportFormat = reportOutputMixin.format,
            reportOutput = reportOutputMixin.output,
            failOnCancellation = failOnCancellation,
            testSuiteName = reportOutputMixin.testSuiteName,
            disableNotifications = disableNotifications,
            deviceLocale = deviceConfigMixin.deviceLocale,
            projectId = projectId,
            deviceModel = deviceConfigMixin.deviceModel,
            deviceOs = deviceConfigMixin.deviceOs,
            androidApiLevel = androidApiLevel,
            iOSVersion = iOSVersion
        )
    }

    private fun validateWorkSpace() {
        try {
            PrintUtils.message("Evaluating flow(s)...")
            WorkspaceExecutionPlanner
                .plan(
                    input = setOf(flowsFile.toPath().toAbsolutePath()),
                    includeTags = tagFilterMixin.includeTags,
                    excludeTags = tagFilterMixin.excludeTags,
                    config = configFileMixin.configFile?.toPath()?.toAbsolutePath(),
                )
        } catch (e: Exception) {
            throw CliError("Upload aborted. Received error when evaluating flow(s):\n\n${e.message}")
        }
    }

    private fun validateFiles() {

        if (configFileMixin.configFile != null && configFileMixin.configFile?.exists()?.not() == true) {
            throw CliError("The config file ${configFileMixin.configFile?.absolutePath} does not exist.")
        }

        // Maintains backwards compatibility for this syntax: maestro cloud <appFile> <workspace>
        // App file can be optional now
        if (this::files.isInitialized) {
            when (files.size) {
                2 -> {
                    appFile = files[0]
                    flowsFile = files[1]
                }
                1 -> {
                    flowsFile = files[0]
                }
            }
        }

        val hasWorkspace = this::flowsFile.isInitialized
        val hasApp = appFile != null
                || appBinaryId != null
                || (this::flowsFile.isInitialized && this::flowsFile.get().isWebFlow())

        if (!hasApp && !hasWorkspace) {
            throw CommandLine.MissingParameterException(spec!!.commandLine(), spec!!.findOption("--flows"), "Missing required parameters: '--app-file', " +
                "'--flows'. " +
                "Example:" +
                " maestro cloud --app-file <path> --flows <path>")
        }

        if (!hasApp) throw CommandLine.MissingParameterException(spec!!.commandLine(), spec!!.findOption("--app-file"), "Missing required parameter for option '--app-file' or " +
            "'--app-binary-id'")
        if (!hasWorkspace) throw CommandLine.MissingParameterException(spec!!.commandLine(), spec!!.findOption("--flows"), "Missing required parameter for option '--flows'")

    }

}
