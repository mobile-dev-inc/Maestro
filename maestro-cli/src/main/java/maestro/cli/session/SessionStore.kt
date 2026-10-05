package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class SessionStore(private val keyValueStore: KeyValueStore) {

    fun heartbeat(sessionId: String, platform: Platform, deviceId: String) {
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
                if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat >= STALE_SESSION_MS) {
                    keyValueStore.delete(key)
                }
            }
    }

    fun delete(sessionId: String, platform: Platform, deviceId: String) {
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
                    lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < STALE_SESSION_MS
                }
        }
    }

    fun shouldCloseSession(platform: Platform, deviceId: String): Boolean {
        return activeSessionsForDevice(platform, deviceId).isEmpty()
    }

    fun activeSessionsForDevice(platform: Platform, deviceId: String): List<String> {
        val devicePrefix = "${platform}_${deviceId}_"
        synchronized(keyValueStore) {
            return activeSessions().filter { it.startsWith(devicePrefix) }
        }
    }

    fun hasActiveSessionForDevice(
        sessionId: String,
        platform: Platform,
        deviceId: String
    ): Boolean {
        val currentKey = key(sessionId, platform, deviceId)
        val devicePrefix = "${platform}_${deviceId}_"
        synchronized(keyValueStore) {
            return activeSessions()
                .any { it.startsWith(devicePrefix) && it != currentKey }
        }
    }

    fun tryClaim(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val ownKey = key(sessionId, platform, deviceId)
        val devicePrefix = "${platform}_${deviceId}_"
        synchronized(keyValueStore) {
            return keyValueStore.update { db ->
                db.entries.removeIf { (_, value) ->
                    val lastHeartbeat = value.toLongOrNull()
                    lastHeartbeat != null && now - lastHeartbeat >= STALE_SESSION_MS
                }
                val busy = db.any { (key, value) ->
                    key.startsWith(devicePrefix) && key != ownKey && value.toLongOrNull() != null
                }
                if (!busy) db[ownKey] = now.toString()
                !busy
            }
        }
    }

    fun awaitClaim(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        timeoutMs: Long,
        onBusy: () -> Unit = {},
        pollMs: Long = 1_000,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Boolean {
        val deadline = now() + timeoutMs
        var notified = false
        while (true) {
            if (tryClaim(sessionId, platform, deviceId, now())) return true
            if (now() >= deadline) return false
            if (!notified) {
                onBusy()
                notified = true
            }
            sleep(pollMs)
        }
    }

    private fun key(sessionId: String, platform: Platform, deviceId: String): String {
        return "${platform}_${deviceId}_$sessionId"
    }

    companion object {
        val STALE_SESSION_MS = TimeUnit.SECONDS.toMillis(21)

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
