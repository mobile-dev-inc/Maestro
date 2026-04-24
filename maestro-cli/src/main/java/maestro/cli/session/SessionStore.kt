package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class SessionStore(private val keyValueStore: KeyValueStore) {

    fun heartbeat(sessionId: String, platform: Platform, deviceId: String? = null) {
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

    fun delete(sessionId: String, platform: Platform, deviceId: String? = null) {
        synchronized(keyValueStore) {
            keyValueStore.delete(
                key(sessionId, platform, deviceId)
            )
        }
    }

    fun activeSessions(): List<String> {
        synchronized(keyValueStore) {
            return keyValueStore
                .keys()
                .filter { key ->
                    val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                    lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)
                }
        }
    }

    fun shouldCloseSession(platform: Platform, deviceId: String? = null): Boolean {
        return activeSessionsForDevice(platform, deviceId).isEmpty()
    }

    fun activeSessionsForDevice(platform: Platform, deviceId: String? = null): List<String> {
        val devicePrefix = "${platform}_${deviceId ?: "unknown"}_"
        synchronized(keyValueStore) {
            return activeSessions().filter { it.startsWith(devicePrefix) }
        }
    }

    fun hasActiveSessionForDevice(
        sessionId: String,
        platform: Platform,
        deviceId: String? = null
    ): Boolean {
        val currentKey = key(sessionId, platform, deviceId)
        val devicePrefix = "${platform}_${deviceId ?: "unknown"}_"
        synchronized(keyValueStore) {
            return activeSessions()
                .any { it.startsWith(devicePrefix) && it != currentKey }
        }
    }

    private fun key(sessionId: String, platform: Platform, deviceId: String? = null): String {
        return "${platform}_${deviceId ?: "unknown"}_$sessionId"
    }

    companion object {
        val default by lazy {
            SessionStore(
                KeyValueStore(
                    dbFile = Paths
                        .get(System.getProperty("user.home"), ".maestro", "sessions")
                        .toFile()
                        .also { it.parentFile.mkdirs() }
                )
            )
        }
    }
}
