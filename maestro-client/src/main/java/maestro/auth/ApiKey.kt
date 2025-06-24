package maestro.auth

import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class ApiKey {
    companion object {
        private val cachedAuthTokenFile by lazy {
            Paths.get(
                System.getProperty("user.home"),
                ".mobiledev",
                "authtoken"
            )
        }

        private fun maestroCloudApiKey(): String? {
            return System.getenv("MAESTRO_CLOUD_API_KEY")
        }

        private fun getCachedAuthToken(): String? {
            if (!cachedAuthTokenFile.exists()) return null
            if (cachedAuthTokenFile.isDirectory()) return null
            val cachedAuthToken = cachedAuthTokenFile.readText()
            return cachedAuthToken
        }

        fun getToken(): String? {
            return maestroCloudApiKey() ?: // Resolve API key from shell if set
            getCachedAuthToken() // Otherwise, if the user has already logged in, use the cached auth token
        }

        fun setToken(token: String?) {
            cachedAuthTokenFile.parent.toFile().mkdirs()
            if (token == null) {
                cachedAuthTokenFile.deleteIfExists()
                return
            }
            cachedAuthTokenFile.toFile().writeText(token)
        }
    }
}