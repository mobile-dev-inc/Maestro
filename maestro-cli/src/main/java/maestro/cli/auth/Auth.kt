package maestro.cli.auth

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.awt.Desktop
import java.net.URI
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import maestro.cli.api.ApiClient
import maestro.cli.util.PrintUtils.err
import maestro.cli.util.PrintUtils.info
import maestro.cli.util.PrintUtils.message
import maestro.cli.util.PrintUtils.success
import maestro.cli.util.getFreePort
import maestro.cli.util.EnvUtils

private const val SUCCESS_HTML = """
    <!DOCTYPE html>
<html>
<head>
    <title>Authentication Successful</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-white from-blue-500 to-purple-600 min-h-screen flex items-center justify-center">
<div class="bg-white p-8 rounded-lg border border-gray-300 max-w-md w-full mx-4">
    <div class="text-center">
        <svg class="w-16 h-16 text-green-500 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path>
        </svg>
        <h1 class="text-2xl font-bold text-gray-800 mb-2">Authentication Successful!</h1>
        <p class="text-gray-600">You can close this window and return to the CLI.</p>
    </div>
</div>
</body>
</html>
    """

private const val FAILURE_HTML = """
    <!DOCTYPE html>
<html>
<head>
    <title>Authentication Failed</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-white min-h-screen flex items-center justify-center">
<div class="bg-white p-8 rounded-lg border border-gray-300 max-w-md w-full mx-4">
    <div class="text-center">
        <svg class="w-16 h-16 text-red-500 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
        </svg>
        <h1 class="text-2xl font-bold text-gray-800 mb-2">Authentication Failed</h1>
        <p class="text-gray-600">Something went wrong. Please try again.</p>
    </div>
</div>
</body>
</html>
"""

class Auth(
    private val apiClient: ApiClient
) {

    fun getAuthToken(apiKey: String?, triggerSignIn: Boolean = true): String? {
        if (triggerSignIn) {
            return apiKey // Check for API key
                ?: getCachedAuthToken() // Otherwise, if the user has already logged in, use the cached auth token
                ?: EnvUtils.maestroCloudApiKey() // Resolve API key from shell if set
                ?: triggerSignInFlow() // Otherwise, trigger the sign-in flow
        }
        return apiKey // Check for API key
            ?: getCachedAuthToken() // Otherwise, if the user has already logged in, use the cached auth token
            ?: EnvUtils.maestroCloudApiKey() // Resolve API key from shell if set
    }

    fun getCachedAuthToken(): String? {
        if (!cachedAuthTokenFile.exists()) return null
        if (cachedAuthTokenFile.isDirectory()) return null
        val cachedAuthToken = cachedAuthTokenFile.readText()
        return cachedAuthToken
//        return if (apiClient.isAuthTokenValid(cachedAuthToken)) {
//            cachedAuthToken
//        } else {
//            message("Existing Authentication token is invalid or expired")
//            cachedAuthTokenFile.deleteIfExists()
//            null
//        }
    }

    fun triggerSignInFlow(): String {
        val deferredToken = CompletableDeferred<String>()

        val port = getFreePort()
        val server = embeddedServer(Netty, configure = { shutdownTimeout = 0; shutdownGracePeriod = 0 }, port = port) {
            routing {
                get("/callback") {
                    handleCallback(call, deferredToken)
                }
            }
        }.start(wait = false)

        val authUrl = apiClient.getAuthUrl(port.toString())
        info("Your browser has been opened to visit:\n\n\t$authUrl")

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(authUrl))
        } else {
            err("Failed to open browser on this platform. Please open the above URL in your preferred browser.")
            throw UnsupportedOperationException("Failed to open browser automatically on this platform. Please open the above URL in your preferred browser.")
        }

        val token = runBlocking {
            deferredToken.await()
        }
        server.stop(0, 0)
        setCachedAuthToken(token)
        success("Authentication completed.")
        return token
    }

    private suspend fun handleCallback(call: ApplicationCall, deferredToken: CompletableDeferred<String>) {
        val code = call.request.queryParameters["code"]
        if (code.isNullOrEmpty()) {
            err("No authorization code received. Please try again.")
            call.respondText(FAILURE_HTML, ContentType.Text.Html)
            return
        }

        val newApiKey = apiClient.exchangeToken(code)

        call.respondText(SUCCESS_HTML, ContentType.Text.Html)
        deferredToken.complete(newApiKey)
    }

    private fun setCachedAuthToken(token: String?) {
        cachedAuthTokenFile.parent.createDirectories()
        if (token == null) {
            cachedAuthTokenFile.deleteIfExists()
        } else {
            cachedAuthTokenFile.writeText(token)
        }
    }

    companion object {

        private val cachedAuthTokenFile by lazy {
            Paths.get(
                System.getProperty("user.home"),
                ".mobiledev",
                "authtoken"
            )
        }

    }

}