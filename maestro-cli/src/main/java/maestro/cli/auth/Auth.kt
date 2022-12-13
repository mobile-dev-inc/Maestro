package maestro.cli.auth

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.util.PrintUtils
import maestro.cli.util.PrintUtils.message
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class Auth(
    private val client: ApiClient,
) {

    fun getCachedAuthToken(): String? {
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

    fun triggerSignInFlow(): String {
        message("No auth token found")
        val email = PrintUtils.prompt("Sign In or Sign Up using your email address:")
        var isLogin = true
        val requestToken = client.magicLinkLogin(email, AUTH_SUCCESS_REDIRECT_URL).getOrElse { loginError ->
            val errorBody = try {
                loginError.body?.string()
            } catch (e: Exception) {
                e.message
            }

            if (loginError.code == 403 && errorBody?.contains("not an authorized email address") == true) {
                isLogin = false
                message("No existing team found for this email domain")
                val team = PrintUtils.prompt("Enter a team name to create your team:")
                client.magicLinkSignUp(email, team, AUTH_SUCCESS_REDIRECT_URL).getOrElse { signUpError ->
                    throw CliError(signUpError.body?.string() ?: signUpError.message)
                }
            } else {
                throw CliError(
                    errorBody ?: loginError.message
                )
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

    private fun setCachedAuthToken(token: String?) {
        cachedAuthTokenFile.parent.createDirectories()
        if (token == null) {
            cachedAuthTokenFile.deleteIfExists()
        } else {
            cachedAuthTokenFile.writeText(token)
        }
    }

    companion object {

        private const val AUTH_SUCCESS_REDIRECT_URL = "https://console.mobile.dev/auth/success"

        private val cachedAuthTokenFile by lazy {
            Paths.get(
                System.getProperty("user.home"),
                ".mobiledev",
                "authtoken"
            )
        }

    }

}