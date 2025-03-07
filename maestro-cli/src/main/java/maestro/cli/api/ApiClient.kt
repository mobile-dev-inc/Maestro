package maestro.cli.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import maestro.cli.CliError
import maestro.cli.analytics.Analytics
import maestro.cli.analytics.AnalyticsReport
import maestro.cli.insights.AnalysisDebugFiles
import maestro.cli.model.FlowStatus
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.utils.HttpClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
import java.util.Scanner
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

class ApiClient(
    private val baseUrl: String,
) {

    private val client = HttpClient.build(
        name = "ApiClient",
        readTimeout = 5.minutes,
        writeTimeout = 5.minutes,
        protocols = listOf(Protocol.HTTP_1_1),
        interceptors = listOf(SystemInformationInterceptor()),
    )

    val domain: String
        get() {
            val regex = "https?://[^.]+.([a-zA-Z0-9.-]*).*".toRegex()
            val matchResult = regex.matchEntire(baseUrl)
            val domain = if (!matchResult?.groups?.get(1)?.value.isNullOrEmpty()) {
                matchResult?.groups?.get(1)?.value
            } else {
                matchResult?.groups?.get(0)?.value
            }
            return domain ?: "mobile.dev"
        }

    fun sendErrorReport(exception: Exception, commandLine: String) {
        post<Unit>(
            path = "/maestro/error",
            body = mapOf(
                "exception" to exception,
                "commandLine" to commandLine
            )
        )
    }

    fun sendScreenReport(maxDepth: Int) {
        post<Unit>(
            path = "/maestro/screen",
            body = mapOf(
                "maxDepth" to maxDepth
            )
        )
    }

    fun sendAnalyticsReport(analyticsReport: AnalyticsReport) {
        post<Unit>(
            path = "/maestro/analytics",
            body = analyticsReport,
        )
    }

    fun getLatestCliVersion(): CliVersion {
        val request = Request.Builder()
            .header("X-FRESH-INSTALL", if (!Analytics.hasRunBefore) "true" else "false")
            .url("$baseUrl/maestro/version")
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

            return JSON.readValue(response.body?.bytes(), CliVersion::class.java)
        }
    }

    fun getAuthUrl(port: String): String {
        return "$baseUrl/maestroLogin/authUrl?port=$port"
    }

    fun exchangeToken(code: String): String {
        val requestBody = code.toRequestBody("text/plain".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/maestroLogin/exchange")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            return response.body?.string() ?: throw IOException("Empty response body")
        }
    }

    fun isAuthTokenValid(authToken: String): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/maestroLogin/valid")
            .header("Authorization", "Bearer $authToken")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            return !(!response.isSuccessful && (response.code == 401 || response.code == 403))
        }
    }

    private fun getAgent(): String {
        return CiUtils.getCiProvider() ?: "cli"
    }

    fun uploadStatus(
        authToken: String,
        uploadId: String,
        projectId: String?,
    ): UploadStatus {
        val baseUrl = if (projectId != null) {
            "$baseUrl/v2/project/$projectId/upload/$uploadId"
        } else {
            "$baseUrl/v2/upload/$uploadId/status?includeErrors=true"
        }
        val request = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url(baseUrl)
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
        frames: List<AnsiResultView.Frame>,
        progressListener: (totalBytes: Long, bytesWritten: Long) -> Unit = { _, _ -> },
    ): String {
        val baseUrl = "https://maestro-record.ngrok.io"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "screenRecording",
                screenRecording.name,
                screenRecording.asRequestBody("application/mp4".toMediaType()).observable(progressListener)
            )
            .addFormDataPart("frames", JSON.writeValueAsString(frames))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/render")
            .post(body)
            .build()
        val response = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CliError("Render request failed (${response.code}): ${response.body?.string()}")
            }
            JSON.readValue(response.body?.bytes(), RenderResponse::class.java)
        }
        return response.id
    }

    fun getRenderState(id: String): RenderState {
        val baseUrl = "https://maestro-record.ngrok.io"
        val request = Request.Builder()
            .url("$baseUrl/render/$id")
            .get()
            .build()
        val response = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CliError("Get render state request failed (${response.code}): ${response.body?.string()}")
            }
            JSON.readValue(response.body?.bytes(), RenderState::class.java)
        }
        val downloadUrl = if (response.downloadUrl == null) null else "$baseUrl${response.downloadUrl}"
        return response.copy(downloadUrl = downloadUrl)
    }

    fun upload(
        authToken: String,
        appFile: Path?,
        workspaceZip: Path,
        uploadName: String?,
        mappingFile: Path?,
        repoOwner: String?,
        repoName: String?,
        branch: String?,
        commitSha: String?,
        pullRequestId: String?,
        env: Map<String, String>? = null,
        androidApiLevel: Int?,
        iOSVersion: String? = null,
        appBinaryId: String? = null,
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
        maxRetryCount: Int = 3,
        completedRetries: Int = 0,
        disableNotifications: Boolean,
        deviceLocale: String? = null,
        progressListener: (totalBytes: Long, bytesWritten: Long) -> Unit = { _, _ -> },
        projectId: String? = null,
        deviceModel: String? = null,
        deviceOs: String? = null,
    ): UploadResponse {
        if (appBinaryId == null && appFile == null) throw CliError("Missing required parameter for option '--app-file' or '--app-binary-id'")
        if (appFile != null && !appFile.exists()) throw CliError("App file does not exist: ${appFile.absolutePathString()}")
        if (!workspaceZip.exists()) throw CliError("Workspace zip does not exist: ${workspaceZip.absolutePathString()}")

        val requestPart = mutableMapOf<String, Any>()
        if (uploadName != null) {
            requestPart["benchmarkName"] = uploadName
        }
        repoOwner?.let { requestPart["repoOwner"] = it }
        repoName?.let { requestPart["repoName"] = it }
        branch?.let { requestPart["branch"] = it }
        commitSha?.let { requestPart["commitSha"] = it }
        pullRequestId?.let { requestPart["pullRequestId"] = it }
        env?.let { requestPart["env"] = it }
        requestPart["agent"] = getAgent()
        androidApiLevel?.let { requestPart["androidApiLevel"] = it }
        iOSVersion?.let { requestPart["iOSVersion"] = it }
        appBinaryId?.let { requestPart["appBinaryId"] = it }
        deviceLocale?.let { requestPart["deviceLocale"] = it }
        projectId?.let { requestPart["projectId"] = it }
        deviceModel?.let { requestPart["deviceModel"] = it }
        deviceOs?.let { requestPart["deviceOs"] = it }
        if (includeTags.isNotEmpty()) requestPart["includeTags"] = includeTags
        if (excludeTags.isNotEmpty()) requestPart["excludeTags"] = excludeTags
        if (disableNotifications) requestPart["disableNotifications"] = true

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "workspace",
                "workspace.zip",
                workspaceZip.toFile().asRequestBody("application/zip".toMediaType())
            )
            .addFormDataPart("request", JSON.writeValueAsString(requestPart))

        if (appFile != null) {
            bodyBuilder.addFormDataPart(
                "app_binary",
                "app.zip",
                appFile.toFile().asRequestBody("application/zip".toMediaType()).observable(progressListener)
            )
        }

        if (mappingFile != null) {
            bodyBuilder.addFormDataPart(
                "mapping",
                "mapping.txt",
                mappingFile.toFile().asRequestBody("text/plain".toMediaType())
            )
        }

        val body = bodyBuilder.build()

        fun retry(message: String, e: Throwable? = null): UploadResponse {
            if (completedRetries >= maxRetryCount) {
                e?.printStackTrace()
                throw CliError(message)
            }

            PrintUtils.message("$message, retrying (${completedRetries + 1}/$maxRetryCount)...")
            Thread.sleep(BASE_RETRY_DELAY_MS + (2000 * completedRetries))

            return upload(
                authToken = authToken,
                appFile = appFile,
                workspaceZip = workspaceZip,
                uploadName = uploadName,
                mappingFile = mappingFile,
                repoOwner = repoOwner,
                repoName = repoName,
                branch = branch,
                commitSha = commitSha,
                pullRequestId = pullRequestId,
                env = env,
                androidApiLevel = androidApiLevel,
                iOSVersion = iOSVersion,
                includeTags = includeTags,
                excludeTags = excludeTags,
                maxRetryCount = maxRetryCount,
                completedRetries = completedRetries + 1,
                progressListener = progressListener,
                appBinaryId = appBinaryId,
                disableNotifications = disableNotifications,
                deviceLocale = deviceLocale,
                projectId = projectId,
            )
        }

        val url = if (projectId != null) {
            "$baseUrl/v2/project/$projectId/runMaestroTest"
        } else {
            throw CliError("A Project ID is required for uploading to the cloud")
        }
        val response = try {
            val request = Request.Builder()
                .header("Authorization", "Bearer $authToken")
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute()
        } catch (e: IOException) {
            return retry("Upload failed due to socket exception", e)
        }

        response.use {
            if (!response.isSuccessful) {
                val errorMessage = response.body?.string().takeIf { it?.isNotEmpty() == true } ?: "Unknown"

                if (response.code == 403 && errorMessage.contains("Your trial has not started yet", ignoreCase = true)) {
                    println("\n\u001B[31;1m[ERROR]\u001B[0m Your trial has not started yet.")
                    print("\u001B[34;1m[INPUT]\u001B[0m Please enter your company name to start the trial: ")

                    val scanner = Scanner(System.`in`)
                    val companyName = scanner.nextLine().trim()

                    if (companyName.isNotEmpty()) {
                        println("\u001B[33;1m[INFO]\u001B[0m Starting your trial for company: \u001B[36;1m$companyName\u001B[0m...")

                        val isTrialStarted = startTrial(authToken, companyName);
                        if(isTrialStarted) {
                            println("\u001B[32;1m[SUCCESS]\u001B[0m Trial successfully started. Enjoy your 7-day free trial!\n")
                            return upload(
                                authToken = authToken,
                                appFile = appFile,
                                workspaceZip = workspaceZip,
                                uploadName = uploadName,
                                mappingFile = mappingFile,
                                repoOwner = repoOwner,
                                repoName = repoName,
                                branch = branch,
                                commitSha = commitSha,
                                pullRequestId = pullRequestId,
                                env = env,
                                androidApiLevel = androidApiLevel,
                                iOSVersion = iOSVersion,
                                includeTags = includeTags,
                                excludeTags = excludeTags,
                                maxRetryCount = maxRetryCount,
                                completedRetries = completedRetries + 1,
                                progressListener = progressListener,
                                appBinaryId = appBinaryId,
                                disableNotifications = disableNotifications,
                                deviceLocale = deviceLocale,
                            )
                        } else {
                          println("\u001B[31;1m[ERROR]\u001B[0m Failed to start trial. Please check your details and try again.")
                        }
                    } else {
                      println("\u001B[31;1m[ERROR]\u001B[0m Company name is required for starting a trial.")
                    }
                }

                if (response.code >= 500) {
                    return retry("Upload failed with status code ${response.code}: $errorMessage")
                } else {
                    throw CliError("Upload request failed (${response.code}): $errorMessage")
                }
            }

            val responseBody = JSON.readValue(response.body?.bytes(), Map::class.java)

            return if (projectId != null) {
                parseRobinUploadResponse(responseBody)
            } else {
                parseMaestroCloudUpload(responseBody)
            }
        }
    }

    private fun startTrial(authToken: String, companyName: String): Boolean {
        println("Starting your trial...")
        val url = "$baseUrl/v2/start-trial"

        val jsonBody = """{ "companyName": "$companyName" }""".toRequestBody("application/json".toMediaType())
        val trialRequest = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url(url)
            .post(jsonBody)
            .build()

        try {
            val response = client.newCall(trialRequest).execute()
            if (response.isSuccessful) return true;
            println("\u001B[31m${response.body?.string()}\u001B[0m");
            return false
        } catch (e: IOException) {
            println("\u001B[31;1m[ERROR]\u001B[0m We're experiencing connectivity issues, please try again in sometime, reach out to the slack channel in case if this doesn't work.")
            return false
        }
    }

    private fun parseRobinUploadResponse(responseBody: Map<*, *>): UploadResponse {
        @Suppress("UNCHECKED_CAST")
        val orgId = responseBody["orgId"] as String
        val uploadId = responseBody["uploadId"] as String
        val appId = responseBody["appId"] as String
        val appBinaryId = responseBody["appBinaryId"] as String

        val deviceConfigMap = responseBody["deviceConfiguration"] as Map<String, Any>
        val platform = deviceConfigMap["platform"].toString().uppercase()
        val deviceConfiguration = DeviceConfiguration(
            platform = platform,
            deviceName = deviceConfigMap["deviceName"] as String,
            orientation = deviceConfigMap["orientation"] as String,
            osVersion = deviceConfigMap["osVersion"] as String,
            displayInfo = deviceConfigMap["displayInfo"] as String,
            deviceLocale = deviceConfigMap["deviceLocale"] as? String
        )

        return RobinUploadResponse(
            orgId = orgId,
            uploadId = uploadId,
            deviceConfiguration = deviceConfiguration,
            appId = appId,
            appBinaryId = appBinaryId
        )
    }

    private fun parseMaestroCloudUpload(responseBody: Map<*, *>): UploadResponse {
        @Suppress("UNCHECKED_CAST")
        val analysisRequest = responseBody["analysisRequest"] as Map<String, Any>
        val uploadId = analysisRequest["id"] as String
        val teamId = analysisRequest["teamId"] as String
        val appId = responseBody["targetId"] as String
        val appBinaryIdResponse = responseBody["appBinaryId"] as? String
        val deviceInfoStr = responseBody["deviceInfo"] as? Map<String, Any>

        val deviceInfo = deviceInfoStr?.let {
            DeviceInfo(
                platform = it["platform"] as String,
                displayInfo = it["displayInfo"] as String,
                isDefaultOsVersion = it["isDefaultOsVersion"] as Boolean,
                deviceLocale = responseBody["deviceLocale"] as String
            )
        }

        return MaestroCloudUploadResponse(teamId, appId, uploadId, appBinaryIdResponse, deviceInfo)
    }


    private inline fun <reified T> post(path: String, body: Any): Result<T, Response> {
        val bodyBytes = JSON.writeValueAsBytes(body)
        val request = Request.Builder()
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
            .url("$baseUrl$path")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return Err(response)
        if (Unit is T) return Ok(Unit)
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

    fun analyze(
        authToken: String,
        debugFiles: AnalysisDebugFiles,
    ): AnalyzeResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = JSON.writeValueAsString(debugFiles).toRequestBody(mediaType)

        val url = "$baseUrl/v2/analyze"

        val request = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url(url)
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        response.use {
            if (!response.isSuccessful) {
                val errorMessage = response.body?.string().takeIf { it?.isNotEmpty() == true } ?: "Unknown"
                throw CliError("Analyze request failed (${response.code}): $errorMessage")
            }

            val parsed = JSON.readValue(response.body?.bytes(), AnalyzeResponse::class.java)

            return parsed;
        }
    }


    data class ApiException(
        val statusCode: Int?,
    ) : Exception("Request failed. Status code: $statusCode")

    companion object {
        private const val BASE_RETRY_DELAY_MS = 3000L
        private val JSON = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

sealed class UploadResponse

@JsonIgnoreProperties(ignoreUnknown = true)
data class RobinUploadResponse(
    val orgId: String,
    val uploadId: String,
    val appId: String,
    val deviceConfiguration: DeviceConfiguration?,
    val appBinaryId: String?,
) : UploadResponse()

@JsonIgnoreProperties(ignoreUnknown = true)
data class MaestroCloudUploadResponse(
    val teamId: String,
    val appId: String,
    val uploadId: String,
    val appBinaryId: String?,
    val deviceInfo: DeviceInfo?
) : UploadResponse()

data class DeviceConfiguration(
    val platform: String,
    val deviceName: String,
    val orientation: String,
    val osVersion: String,
    val displayInfo: String,
    val deviceLocale: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceInfo(
    val platform: String,
    val displayInfo: String,
    val isDefaultOsVersion: Boolean,
    val deviceLocale: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UploadStatus(
    val uploadId: String,
    val status: Status,
    val completed: Boolean,
    val flows: List<FlowResult>,
) {

    data class FlowResult(
        val name: String,
        val status: FlowStatus,
        val errors: List<String>,
        val cancellationReason: CancellationReason? = null
    )

    enum class Status {
        PENDING,
        PREPARING,
        INSTALLING,
        RUNNING,
        SUCCESS,
        ERROR,
        CANCELED,
        WARNING,
        STOPPED
    }

    // These values must match backend monorepo models
    // in package models.benchmark.BenchmarkCancellationReason
    enum class CancellationReason {
        BENCHMARK_DEPENDENCY_FAILED,
        INFRA_ERROR,
        OVERLAPPING_BENCHMARK,
        TIMEOUT,
        CANCELED_BY_USER,
        RUN_EXPIRED,
    }
}

data class RenderResponse(
    val id: String,
)

data class RenderState(
    val status: String,
    val positionInQueue: Int?,
    val currentTaskProgress: Float?,
    val error: String?,
    val downloadUrl: String?,
)

data class CliVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<CliVersion> {

    override fun compareTo(other: CliVersion): Int {
        return COMPARATOR.compare(this, other)
    }

    override fun toString(): String {
        return "$major.$minor.$patch"
    }

    companion object {

        private val COMPARATOR = compareBy<CliVersion>({ it.major }, { it.minor }, { it.patch })

        fun parse(versionString: String): CliVersion? {
            val parts = versionString.split('.')
            if (parts.size != 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            return CliVersion(major, minor, patch)
        }
    }
}

class SystemInformationInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val newRequest = chain.request().newBuilder()
            .header("X-UUID", Analytics.uuid)
            .header("X-VERSION", EnvUtils.getVersion().toString())
            .header("X-OS", EnvUtils.OS_NAME)
            .header("X-OSARCH", EnvUtils.OS_ARCH)
            .build()

        return chain.proceed(newRequest)
    }
}

data class Insight(
    val category: String,
    val reasoning: String,
)

class AnalyzeResponse(
    val htmlReport: String?,
    val output: String,
    val insights: List<Insight>
)
