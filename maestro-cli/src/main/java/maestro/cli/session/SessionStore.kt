package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

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
        val key = key(sessionId, platform)
        synchronized(keyValueStore) {
            keyValueStore.delete(key)
        }
    }

    fun activeSessions(): List<String> {
        synchronized(keyValueStore) {
            val currentTime = System.currentTimeMillis()
            val activeSessionKeys = keyValueStore
                .keys()
                .filter { key ->
                    val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                    val isActive = lastHeartbeat != null && 
                                   currentTime - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)
                    if (!isActive) {
                        keyValueStore.delete(key)
                    }
                    isActive
                }
            return activeSessionKeys
        }
    }

    fun hasActiveSessions(
        sessionId: String,
        platform: Platform
    ): Boolean {
        synchronized(keyValueStore) {
            val currentKey = key(sessionId, platform)
            val activeSessions = activeSessions()
            val result = activeSessions.isNotEmpty() && activeSessions.any { it != currentKey }
            return result
        }
    }

    private fun key(sessionId: String, platform: Platform): String {
        return "${platform}_$sessionId"
    }
}