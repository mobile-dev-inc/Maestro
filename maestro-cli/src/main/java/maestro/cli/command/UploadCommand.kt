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

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.auth.Auth
import maestro.cli.util.FileUtils.isZip
import maestro.cli.util.PrintUtils.message
import maestro.cli.util.WorkspaceUtils
import maestro.utils.TemporaryDirectory
import org.fusesource.jansi.Ansi.ansi
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import kotlin.io.path.absolute

@CommandLine.Command(
    name = "upload",
)
class UploadCommand : Callable<Int> {

    @CommandLine.Parameters(description = ["App binary to run your Flows against"])
    private lateinit var appFile: File

    @CommandLine.Parameters(description = ["Flow file or directory"])
    private lateinit var flowFile: File

    @Option(order = 0, names = ["--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @Option(order = 1, names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

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

    private lateinit var client: ApiClient

    override fun call(): Int {
        if (!flowFile.exists()) throw CliError("File does not exist: ${flowFile.absolutePath}")
        if (mapping?.exists() == false) throw CliError("File does not exist: ${mapping?.absolutePath}")

        client = ApiClient(apiUrl)
        val auth = Auth(client)

        val authToken = apiKey              // Check for API key
            ?: auth.getCachedAuthToken()    // Otherwise, if the user has already logged in, use the cached auth token
            ?: auth.triggerSignInFlow()     // Otherwise, trigger the sign-in flow

        message("Uploading Flow(s)...")

        TemporaryDirectory.use { tmpDir ->
            val workspaceZip = tmpDir.resolve("workspace.zip")
            WorkspaceUtils.createWorkspaceZip(flowFile.toPath().absolute(), workspaceZip)
            println()
            val uploadProgress = UploadProgress(20)

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
            ) { totalBytes, bytesWritten ->
                uploadProgress.set(totalBytes, bytesWritten)
            }
            println()
            message("✅ Upload successful! View the results of your upload below:")
            message("https://console.mobile.dev/uploads/$uploadId?teamId=$teamId&appId=$appId")
        }
        return 0
    }

    class UploadProgress(private val width: Int) {

        private var progressWidth: Int? = null

        fun set(totalBytes: Long, bytesWritten: Long) {
            val progress = bytesWritten / totalBytes.toDouble()
            val progressWidth = (progress * width).toInt()
            if (progressWidth == this.progressWidth) return
            this.progressWidth = progressWidth
            val ansi = ansi()
            ansi.cursorToColumn(0)
            ansi.fgCyan()
            repeat(progressWidth) { ansi.a("█") }
            repeat(width - progressWidth) { ansi.a("░") }
            print(ansi)
        }
    }

}
