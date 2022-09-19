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

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.util.WorkspaceUtils
import maestro.utils.TemporaryDirectory
import org.fusesource.jansi.Ansi.ansi
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "upload",
)
class UploadCommand : Callable<Int> {

    @CommandLine.Parameters
    private lateinit var uploadName: String

    @CommandLine.Parameters
    private lateinit var appFile: File

    @CommandLine.Parameters
    private lateinit var flowFile: File

    @Option(names = ["--apiKey"])
    private var apiKey: String? = null

    @Option(names = ["--apiUrl"])
    private var apiUrl: String = "https://api.mobile.dev"

    @Option(names = ["--repoOwner"])
    private var repoOwner: String? = null

    @Option(names = ["--repoName"])
    private var repoName: String? = null

    @Option(names = ["--branch"])
    private var branch: String? = null

    @Option(names = ["--pullRequestId"])
    private var pullRequestId: String? = null

    @Option(names = ["-e", "--env"])
    private var env: Map<String, String> = emptyMap()

    private lateinit var client: ApiClient

    private val cachedAuthTokenFile: Path = Paths.get(System.getProperty("user.home"), ".mobiledev", "authtoken")

    override fun call(): Int {
        if (!flowFile.exists()) throw CliError("File does not exist: ${flowFile.absolutePath}")

        client = ApiClient(apiUrl)

        val authToken = apiKey ?: getCachedAuthToken() ?: triggerSignInFlow()

        message("Uploading Flow(s)...")

        TemporaryDirectory.use { tmpDir ->
            val workspaceZip = tmpDir.resolve("workspace.zip")
            WorkspaceUtils.createWorkspaceZip(flowFile.toPath().absolute(), workspaceZip)
            println()
            val uploadProgress = UploadProgress(20)
            val (teamId, appId, uploadId) = client.upload(
                authToken,
                appFile.toPath(),
                workspaceZip,
                uploadName,
                repoOwner,
                repoName,
                branch,
                pullRequestId
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
            repeat(progressWidth) { ansi.a("█")}
            repeat(width - progressWidth) { ansi.a("░")}
            print(ansi)
        }
    }

    private fun getCachedAuthToken(): String? {
        if (!cachedAuthTokenFile.exists()) return null
        if (cachedAuthTokenFile.isDirectory()) return null
        val cachedAuthToken = cachedAuthTokenFile.readText()
        return if (client.isAuthTokenValid(cachedAuthToken)) {
            cachedAuthToken
        } else {
            message("Existing auth token is invalid or expired")
            cachedAuthTokenFile.deleteIfExists()
            null
        }
    }

    private fun setCachedAuthToken(token: String?) {
        cachedAuthTokenFile.parent.createDirectories()
        if (token == null) {
            cachedAuthTokenFile.deleteIfExists()
        } else {
            cachedAuthTokenFile.writeText(token)
        }
    }

    private fun triggerSignInFlow(): String {
        message("No auth token found")
        val email = prompt("Sign In or Sign Up using your company email address:")
        var isLogin = true
        val requestToken = client.magicLinkLogin(email, AUTH_SUCCESS_REDIRECT_URL).getOrElse { loginError ->
            if (loginError.code == 403 && loginError.body?.string()?.contains("not an authorized email address") == true) {
                isLogin = false
                message("No existing team found for this email domain")
                val team = prompt("Enter a team name to create your team:")
                client.magicLinkSignUp(email, team, AUTH_SUCCESS_REDIRECT_URL).getOrElse { signUpError ->
                    throw CliError(signUpError.body?.string() ?: signUpError.message)
                }
            } else {
                throw CliError(loginError.body?.string() ?: loginError.message)
            }
        }

        if (isLogin) {
            message("We sent a login link to $email. Click on the link there to finish logging in...")
        } else {
            message("We sent an email to $email. Click on the link there to finish creating your account...")
        }

        while (true) {
            val errResponse = when (val result = client.magicLinkGetToken(requestToken)) {
                is Ok -> {
                    if (isLogin) {
                        message("✅ Login successful")
                    } else {
                        message("✅ Team created successfully")
                    }
                    setCachedAuthToken(result.value)
                    return result.value
                }
                is Err -> result.error
            }
            val errorMessage = errResponse.body?.string() ?: errResponse.message
            if (
                "Login process not complete" !in errorMessage
                && "Email is not authorized" !in errorMessage
            ) {
                throw CliError("Failed to get auth token (${errResponse.code}): $errorMessage")
            }
            Thread.sleep(1000)
        }
    }

    companion object {

        private const val AUTH_SUCCESS_REDIRECT_URL = "https://console.mobile.dev/auth/success"

        private fun message(message: String) {
            println(ansi().render("@|cyan \n$message |@"))
        }

        private fun prompt(message: String): String {
            print(ansi().render("\n@|yellow,bold $message\n> |@"))
            try {
                return readln().trim()
            } catch (e: IOException) {
                exitProcess(1)
            }
        }
    }
}
