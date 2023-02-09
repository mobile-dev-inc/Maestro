package maestro.studio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

data class Auth(
    val teamId: UUID,
    val email: String?,
    val id: UUID?,
    val isMachine: Boolean,
    val role: String?
)

data class GetEventsResponse(
    val events: List<MockEvent>
)

class MockInteractor(
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun getCachedAuthToken(): String? {
        if (!cachedAuthTokenFile.exists()) return null
        if (cachedAuthTokenFile.isDirectory()) return null
        return cachedAuthTokenFile.readText()
    }

    fun getProjectId(): UUID? {
        val authToken = getCachedAuthToken() ?: return null

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
        val authToken = getCachedAuthToken()

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
        private const val API_URL = "https://api.mobile.dev"

        private val cachedAuthTokenFile by lazy {
            Paths.get(
                System.getProperty("user.home"),
                ".mobiledev",
                "authtoken"
            )
        }

        private val JSON = jacksonObjectMapper()

    }
}

fun main() {
    MockInteractor().getMockEvents()
}