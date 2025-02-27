package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.cli.device.Platform
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

    fun heartbeat(sessionId: String, platform: Platform, deviceId: String?) {
        synchronized(keyValueStore) {
            keyValueStore.set(
                key = key(sessionId, platform, deviceId),
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

    fun delete(sessionId: String, platform: Platform, deviceId: String?) {
        synchronized(keyValueStore) {
            keyValueStore.delete(
                key(sessionId, platform, deviceId)
            )
        }
    }

    fun hasActiveSessionOnDevice(
        platform: Platform,
        deviceId: String?
    ): Boolean {
        synchronized(keyValueStore) {
            return keyValueStore.keys().any { key ->
                key.contains("${platform}_$deviceId") && isHeartbeatActive(key)
            }
        }
    }

    private fun isHeartbeatActive(key: String): Boolean {
        val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
        return lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)
    }

    private fun key(sessionId: String, platform: Platform, deviceId: String?): String {
        return "${platform}_${sessionId}_$deviceId"
    }

}
