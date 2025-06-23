package maestro.mockserver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.auth.ApiKey
import maestro.utils.HttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.nio.file.Paths
import java.util.*
import kotlin.time.Duration.Companion.minutes

data class Auth(
    val teamId: UUID,
    val email: String?,
    val id: UUID?,
    val isMachine: Boolean,
    val role: String?
)

data class MockEvent(
    val timestamp: String,
    val path: String,
    val matched: Boolean,
    val response: Any,
    val statusCode: Int,
    val sessionId: UUID,
    val projectId: UUID,
    val method: String,
    val bodyAsString: String? = null,
    val headers: Map<String, String>? = null,
)

data class GetEventsResponse(
    val events: List<MockEvent>
)

class MockInteractor {
    private val client = HttpClient.build(
        name = "MockInteractor",
        readTimeout = 5.minutes,
        writeTimeout = 5.minutes,
        protocols = listOf(Protocol.HTTP_1_1)
    )

    fun getProjectId(): UUID? {
        val authToken = ApiKey.getToken()

        val request = try {
            Request.Builder()
                .get()
                .header("Authorization", "Bearer $authToken")
                .url("$API_URL/auth")
                .build()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Unexpected char") == true) {
                return null
            } else {
                throw e
            }
        }
        val response = client.newCall(request).execute()

        if (response.code >= 400) error("Invalid token. Please run `maestro logout` and then `maestro login` to retrieve a valid token.")

        response.use {
            try {
                val auth = JSON.readValue(response.body?.bytes(), Auth::class.java)
                return auth.teamId
            } catch (e: Exception) {
                error("Could not retrieve project id: ${e.message}")
            }
        }
        return null
    }

    fun getMockEvents(): List<MockEvent> {
        val authToken = ApiKey.getToken()

        val request = try {
            Request.Builder()
                .get()
                .header("Authorization", "Bearer $authToken")
                .url("$API_URL/mms-events")
                .build()
        } catch (e: Exception) {
            throw e
        }
        val response = client.newCall(request).execute()

        response.use {
            try {
                val response = JSON.readValue(response.body?.bytes(), GetEventsResponse::class.java)
                return response.events
            } catch (e: Exception) {
                error("Could not retrieve mock events: ${e.message}")
            }
        }
        return emptyList()
    }

    companion object {
        private val API_URL by lazy {
            if (System.getProperty("MAESTRO_CLOUD_API_URL").isNullOrEmpty()) {
                "https://api.copilot.mobile.dev"
            } else {
                System.getProperty("MAESTRO_CLOUD_API_URL")
            }
        }

        private val cachedAuthTokenFile by lazy {
            Paths.get(
                System.getProperty("user.home"),
                ".mobiledev",
                "authtoken"
            )
        }

        private val JSON = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    }
}

fun main() {
    MockInteractor().getMockEvents()
}
