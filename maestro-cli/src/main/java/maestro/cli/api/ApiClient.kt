package maestro.cli.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import maestro.cli.runner.ResultView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.IOException
import okio.buffer
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

class ApiClient(
    private val baseUrl: String,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val knownCIEnvVars = listOf("CI", "JENKINS_HOME", "BITRISE_IO")
    private val BASE_RETRY_DELAY_MS = 3000L

    fun magicLinkLogin(email: String, redirectUrl: String): Result<String, Response> {
        return post<Map<String, Any>>(
            "/magiclink/login", mapOf(
                "deviceId" to "",
                "email" to email,
                "redirectUrl" to redirectUrl,
                "agent" to "cli",
            )
        ).map { it["requestToken"].toString() }
    }

    fun magicLinkSignUp(email: String, teamName: String, redirectUrl: String): Result<String, Response> {
        return post(
            "/magiclink/signup", mapOf(
                "deviceId" to "",
                "userEmail" to email,
                "teamName" to teamName,
                "redirectUrl" to redirectUrl,
                "agent" to "cli",
            )
        )
    }

    fun magicLinkGetToken(requestToken: String): Result<String, Response> {
        return post<Map<String, Any>>(
            "/magiclink/gettoken", mapOf(
                "requestToken" to requestToken,
            )
        ).map { it["authToken"].toString() }
    }

    fun isAuthTokenValid(authToken: String): Boolean {
        val request = try {
            Request.Builder()
                .get()
                .header("Authorization", "Bearer $authToken")
                .url("$baseUrl/auth")
                .build()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Unexpected char") == true) {
                return false
            } else {
                throw e
            }
        }
        val response = client.newCall(request).execute()

        response.use {
            return response.isSuccessful
        }
    }

    private fun getAgent(): String {
        for (ciVar in knownCIEnvVars) {
            try {
                val value = System.getenv(ciVar).lowercase()
                if (value.isNotEmpty() && value != "0" && value != "false") return "ci"
            } catch (e: Exception) {}
        }

        return "cli"
    }

    fun uploadStatus(
        authToken: String,
        uploadId: String,
    ): UploadStatus {
        val request = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url("$baseUrl/v2/upload/$uploadId/status")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(statusCode = null)
        }

        response.use {
            if (!response.isSuccessful) {
                throw ApiException(
                    statusCode = response.code
                )
            }

            return JSON.readValue(response.body?.bytes(), UploadStatus::class.java)
        }
    }

    fun render(
        screenRecording: File,
        frames: List<ResultView.Frame>,
        progressListener: (totalBytes: Long, bytesWritten: Long) -> Unit = { _, _ -> },
    ): String {
        val baseUrl = "http://localhost:3333"
//        val baseUrl = "https://maestro-record.ngrok.io"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("screenRecording", screenRecording.name, screenRecording.asRequestBody("application/mp4".toMediaType()).observable(progressListener))
            .addFormDataPart("frames", JSON.writeValueAsString(frames))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/render")
            .post(body)
            .build()
        val response = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CliError("Upload request failed (${response.code}): ${response.body?.string()}")
            }
            JSON.readValue(response.body?.bytes(), RenderResponse::class.java)
        }
        return "$baseUrl${response.url}"
    }

    fun upload(
        authToken: String,
        appFile: Path,
        workspaceZip: Path,
        uploadName: String?,
        mappingFile: Path?,
        repoOwner: String?,
        repoName: String?,
        branch: String?,
        pullRequestId: String?,
        env: Map<String, String>? = null,
        androidApiLevel: Int?,
        maxRetryCount: Int = 3,
        completedRetries: Int = 0,
        progressListener: (totalBytes: Long, bytesWritten: Long) -> Unit = { _, _ -> },
    ): UploadResponse {
        if (!appFile.exists()) throw CliError("App file does not exist: ${appFile.absolutePathString()}")
        if (!workspaceZip.exists()) throw CliError("Workspace zip does not exist: ${workspaceZip.absolutePathString()}")

        val requestPart = mutableMapOf<String, Any>()
        if (uploadName != null) {
            requestPart["benchmarkName"] = uploadName
        }
        repoOwner?.let { requestPart["repoOwner"] = it }
        repoName?.let { requestPart["repoName"] = it }
        branch?.let { requestPart["branch"] = it }
        pullRequestId?.let { requestPart["pullRequestId"] = it }
        env?.let { requestPart["env"] = it }
        requestPart["agent"] = getAgent()
        androidApiLevel?.let { requestPart["androidApiLevel"] = it }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("app_binary", "app.zip", appFile.toFile().asRequestBody("application/zip".toMediaType()).observable(progressListener))
            .addFormDataPart("workspace", "workspace.zip", workspaceZip.toFile().asRequestBody("application/zip".toMediaType()))
            .addFormDataPart("request", JSON.writeValueAsString(requestPart))

        if (mappingFile != null) {
            bodyBuilder.addFormDataPart("mapping", "mapping.txt", mappingFile.toFile().asRequestBody("text/plain".toMediaType()))
        }

        val body = bodyBuilder.build()

        val request = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url("$baseUrl/v2/upload")
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        response.use {
            if (!response.isSuccessful) {
                if (response.code >= 500 && completedRetries < maxRetryCount) {
                    PrintUtils.message("Request failed, retrying...")
                    Thread.sleep(BASE_RETRY_DELAY_MS + (2000 * completedRetries))
                    
                    return upload(
                        authToken,
                        appFile,
                        workspaceZip,
                        uploadName,
                        mappingFile,
                        repoOwner,
                        repoName,
                        branch,
                        pullRequestId,
                        env,
                        androidApiLevel,
                        maxRetryCount,
                        completedRetries + 1,
                        progressListener,
                    )
                } else {
                    throw CliError("Upload request failed (${response.code}): ${response.body?.string()}")
                }
            }

            val responseBody = JSON.readValue(response.body?.bytes(), Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val analysisRequest = responseBody["analysisRequest"] as Map<String, Any>
            val uploadId = analysisRequest["id"] as String
            val teamId = analysisRequest["teamId"] as String
            val appId = responseBody["targetId"] as String

            return UploadResponse(teamId, appId, uploadId)
        }
    }

    private inline fun <reified T> post(path: String, body: Any): Result<T, Response> {
        val bodyBytes = JSON.writeValueAsBytes(body)
        val request = Request.Builder()
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
            .url("$baseUrl$path")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return Err(response)
        val parsed = JSON.readValue(response.body?.bytes(), T::class.java)
        return Ok(parsed)
    }

    private fun RequestBody.observable(
        progressListener: (totalBytes: Long, bytesWritten: Long) -> Unit,
    ) = object : RequestBody() {

        override fun contentLength() = this@observable.contentLength()

        override fun contentType() = this@observable.contentType()

        override fun writeTo(sink: BufferedSink) {
            val forwardingSink = object : ForwardingSink(sink) {

                private var bytesWritten = 0L

                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    bytesWritten += byteCount
                    progressListener(contentLength(), bytesWritten)
                }
            }.buffer()
            progressListener(contentLength(), 0)
            this@observable.writeTo(forwardingSink)
            forwardingSink.flush()
        }
    }

    data class ApiException(
        val statusCode: Int?,
    ) : Exception("Request failed. Status code: $statusCode")

    companion object {

        private val JSON = jacksonObjectMapper()
    }
}

data class UploadResponse(
    val teamId: String,
    val appId: String,
    val uploadId: String,
)

data class UploadStatus(
    val uploadId: UUID,
    val status: Status,
    val completed: Boolean,
    val flows: List<FlowResult>,
) {

    data class FlowResult(
        val name: String,
        val status: Status,
    )

    enum class Status {
        PENDING,
        RUNNING,
        SUCCESS,
        ERROR,
        CANCELED,
        WARNING,
    }
}

data class RenderResponse(
    val url: String
)
