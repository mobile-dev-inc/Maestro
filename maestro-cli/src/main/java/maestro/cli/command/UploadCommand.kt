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

import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import org.fusesource.jansi.Ansi.ansi
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import maestro.orchestra.util.Env.withDefaultEnvVars

@CommandLine.Command(
    name = "upload",
    hidden = true,
    description = [
        "Deprecated - Use `maestro cloud` instead"
    ]
)
class UploadCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.Parameters(description = ["App binary to run your Flows against"])
    private lateinit var appFile: File

    @CommandLine.Parameters(description = ["Flow file or directory"])
    private lateinit var flowFile: File

    @Option(order = 0, names = ["--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @Option(order = 1, names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.copilot.mobile.dev"

    @Option(order = 2, names = ["--mapping"], description = ["dSYM file (iOS) or Proguard mapping file (Android)"])
    private var mapping: File? = null

    @Option(order = 3, names = ["--repoOwner"], description = ["Repository owner (ie: GitHub organization or user slug)"])
    private var repoOwner: String? = null

    @Option(order = 4, names = ["--repoName"], description = ["Repository name (ie: GitHub repo slug)"])
    private var repoName: String? = null

    @Option(order = 5, names = ["--branch"], description = ["The branch this upload originated from"])
    private var branch: String? = null

    @Option(order = 6, names = ["--pullRequestId"], description = ["The ID of the pull request this upload originated from"])
    private var pullRequestId: String? = null

    @Option(order = 7, names = ["-e", "--env"], description = ["Environment variables to inject into your Flows"])
    private var env: Map<String, String> = emptyMap()

    @Option(order = 8, names = ["--name"], description = ["Name of the upload"])
    private var uploadName: String? = null

    @Option(order = 9, names = ["--android-api-level"], description = ["Android API level to run your flow against"])
    private var androidApiLevel: Int? = null

    @Option(order = 10, names = ["--device-model"], description = ["Device model to run your flow against"])
    private var deviceModel: String? = null

    @Option(order = 11, names = ["--device-os"], description = ["OS version to run your flow against"])
    private var deviceOs: String? = null


    override fun call(): Int {
        println(
            ansi()
                .fgRed()
                .render("'maestro upload' command is deprecated and is going to be removed in one of the future releases. Use 'maestro cloud --async' instead.")
                .fgDefault()
        )

        env = env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(flowFile)

        return CloudInteractor(
            client = ApiClient(apiUrl),
        ).upload(
            async = true,
            flowFile = flowFile,
            appFile = appFile,
            mapping = mapping,
            env = env,
            uploadName = uploadName,
            repoOwner = repoOwner,
            repoName = repoName,
            branch = branch,
            pullRequestId = pullRequestId,
            apiKey = apiKey,
            androidApiLevel = androidApiLevel,
            deviceModel = deviceModel,
            deviceOs = deviceOs
        )
    }

}
