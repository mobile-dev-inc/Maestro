package maestro.cli.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.util.PrintUtils.err
import maestro.cli.util.PrintUtils.info
import maestro.cli.util.PrintUtils.success
import maestro.cli.util.getFreePort
import java.awt.Desktop
import java.net.URI

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
                ?: ApiKey.getToken()
                ?: triggerSignInFlow() // Otherwise, trigger the sign-in flow
        }
        return apiKey // Check for API key
            ?: ApiKey.getToken()
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
        ApiKey.setToken(token)
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

}