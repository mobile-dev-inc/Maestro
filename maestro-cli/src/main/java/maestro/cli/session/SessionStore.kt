package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Represents information about an active Maestro session
 */
data class SessionInfo(
    val id: String,
    val platform: Platform,
    val lastHeartbeat: Long
)

object SessionStore {

    private val keyValueStore by lazy {
        KeyValueStore(
            dbFile = Paths
                .get(System.getProperty("user.home"), ".maestro", "sessions")
                .toFile()
                .also { it.parentFile.mkdirs() }
        )
    }

    fun heartbeat(sessionId: String, platform: Platform) {
        synchronized(keyValueStore) {
            keyValueStore.set(
                key = key(sessionId, platform),
                value = System.currentTimeMillis().toString(),
            )

            pruneInactiveSessions()
        }
    }

    private fun pruneInactiveSessions() {
        keyValueStore.keys()
            .forEach { key ->
                val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat >= TimeUnit.SECONDS.toMillis(21)) {
                    keyValueStore.delete(key)
                }
            }
    }

    fun delete(sessionId: String, platform: Platform) {
        synchronized(keyValueStore) {
            keyValueStore.delete(
                key(sessionId, platform)
            )
        }
    }

    /**
     * Returns a list of all active sessions as strings (legacy method)
     */
    fun activeSessionKeys(): List<String> {
        synchronized(keyValueStore) {
            return keyValueStore
                .keys()
                .filter { key ->
                    val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                    lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)
                }
        }
    }
    
    /**
     * Returns a list of all active sessions with their information
     */
    fun activeSessions(): List<SessionInfo> {
        synchronized(keyValueStore) {
            return keyValueStore
                .keys()
                .mapNotNull { key ->
                    val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                    if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)) {
                        // Extract session ID and platform from the key
                        val parts = key.split("_", limit = 2)
                        if (parts.size == 2) {
                            try {
                                val platform = Platform.valueOf(parts[0])
                                val sessionId = parts[1]
                                SessionInfo(sessionId, platform, lastHeartbeat)
                            } catch (e: IllegalArgumentException) {
                                null // Invalid platform
                            }
                        } else {
                            null // Invalid key format
                        }
                    } else {
                        null // Inactive session
                    }
                }
        }
    }

    fun hasActiveSessions(
        sessionId: String,
        platform: Platform
    ): Boolean {
        synchronized(keyValueStore) {
            return activeSessionKeys()
                .any { it != key(sessionId, platform) }
        }
    }

    private fun key(sessionId: String, platform: Platform): String {
        return "${platform}_$sessionId"
    }
}
