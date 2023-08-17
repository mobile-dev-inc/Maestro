package maestro.studio

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object AuthService {

    private val authTokenFile = Paths.get(
        System.getProperty("user.home"),
        ".mobiledev",
        "authtoken"
    )

    fun routes(routing: Routing) {
        routing.get("/api/auth-token") {
            val authToken = getAuthToken()
            if (authToken == null) {
                call.respond(HttpStatusCode.NotFound, "No auth token found")
            } else {
                call.respond(authToken)
            }
        }
    }

    private fun getAuthToken(): String? {
        System.getProperty("MAESTRO_CLOUD_API_KEY")?.let { if (it.isNotEmpty()) return null }
        if (!authTokenFile.isRegularFile()) return null
        return authTokenFile.readText()
    }
}
