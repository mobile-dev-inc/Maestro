package maestro.studio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import maestro.auth.ApiKey
import java.io.IOException
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class OpenAiTokenRequest(
    val token: String
)

data class AuthResponse(
    val authToken: String?,
    val openAiToken: String?,
)

object AuthService {

    private val mobileDevDir = Paths.get(System.getProperty("user.home"), ".mobiledev")
    private val openAiTokenFile = mobileDevDir.resolve("openaitoken")

    fun routes(routing: Routing) {
        routing.get("/api/auth-token") {
            val authToken = ApiKey.getToken()
            if (authToken == null) {
                call.respond(HttpStatusCode.NotFound, "No auth token found")
            } else {
                call.respond(authToken)
            }
        }
        routing.get("/api/auth") {
            val authToken = ApiKey.getToken()
            val openAiToken = getOpenAiToken()
            val response = AuthResponse(authToken, openAiToken)
            val responseString = jacksonObjectMapper().writeValueAsString(response)
            call.respond(responseString)
        }
        routing.post("/api/auth/openai-token") {
            val request = call.parseBody<OpenAiTokenRequest>()
            try {
                setOpenAiToken(request.token)
                call.respond(HttpStatusCode.OK)
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, "Failed to save OpenAI token: ${e.message}")
            }
        }
        routing.delete("/api/auth/openai-token") {
            try {
                setOpenAiToken(null)
                call.respond(HttpStatusCode.OK)
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, "Failed to delete OpenAI token: ${e.message}")
            }
        }
    }

    private fun getOpenAiToken(): String? {
        if (!openAiTokenFile.isRegularFile()) return null
        return openAiTokenFile.readText()
    }

    private fun setOpenAiToken(token: String?) {
        if (token == null) {
            openAiTokenFile.deleteIfExists()
        } else {
            openAiTokenFile.parent.createDirectories()
            openAiTokenFile.writeText(token)
        }
    }
}
