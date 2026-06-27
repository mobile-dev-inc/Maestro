package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class SessionStore(private val keyValueStore: KeyValueStore) {

    fun heartbeat(sessionId: String, platform: Platform, deviceId: String, driverHostPort: Int? = null) {
        synchronized(keyValueStore) {
            keyValueStore.set(
                key = key(sessionId, platform, deviceId),
                value = SessionValue(
                    lastHeartbeat = System.currentTimeMillis(),
                    driverHostPort = driverHostPort,
                ).encode(),
            )

            pruneInactiveSessions()
        }
    }

    private fun pruneInactiveSessions(now: Long = System.currentTimeMillis()) {
        keyValueStore.keys()
            .forEach { key ->
                val session = keyValueStore.get(key)?.let(SessionValue::parse)
                if (session?.isActive(now) != true) {
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
            return activeSessionRecords().map { it.key }
        }
    }

    fun shouldCloseSession(platform: Platform, deviceId: String): Boolean {
        synchronized(keyValueStore) {
            return activeSessionRecordsForDevice(platform, deviceId).isEmpty()
        }
    }

    fun activeSessionsForDevice(platform: Platform, deviceId: String): List<String> {
        synchronized(keyValueStore) {
            return activeSessionRecordsForDevice(platform, deviceId).map { it.key }
        }
    }

    fun activeSessionForDevice(
        sessionId: String,
        platform: Platform,
        deviceId: String,
    ): ActiveSession? {
        val currentKey = key(sessionId, platform, deviceId)
        synchronized(keyValueStore) {
            return activeSessionRecordsForDevice(platform, deviceId)
                .firstOrNull { it.key != currentKey }
                ?.let { ActiveSession(driverHostPort = it.value.driverHostPort) }
        }
    }

    fun hasActiveSessionForDevice(
        sessionId: String,
        platform: Platform,
        deviceId: String
    ): Boolean {
        return activeSessionForDevice(sessionId, platform, deviceId) != null
    }

    private fun key(sessionId: String, platform: Platform, deviceId: String): String {
        return "${platform}_${deviceId}_$sessionId"
    }

    private fun devicePrefix(platform: Platform, deviceId: String): String {
        return "${platform}_${deviceId}_"
    }

    private fun activeSessionRecordsForDevice(platform: Platform, deviceId: String): List<SessionRecord> {
        val devicePrefix = devicePrefix(platform, deviceId)
        return activeSessionRecords().filter { it.key.startsWith(devicePrefix) }
    }

    private fun activeSessionRecords(now: Long = System.currentTimeMillis()): List<SessionRecord> {
        return keyValueStore.keys()
            .mapNotNull { key ->
                val session = keyValueStore.get(key)?.let(SessionValue::parse) ?: return@mapNotNull null
                if (session.isActive(now)) {
                    SessionRecord(key, session)
                } else {
                    null
                }
            }
    }

    data class ActiveSession(
        val driverHostPort: Int?,
    )

    private data class SessionRecord(
        val key: String,
        val value: SessionValue,
    )

    private data class SessionValue(
        val lastHeartbeat: Long,
        val driverHostPort: Int?,
    ) {
        fun isActive(now: Long): Boolean {
            return now - lastHeartbeat < ACTIVE_SESSION_TIMEOUT_MS
        }

        fun encode(): String {
            return listOfNotNull(
                lastHeartbeat.toString(),
                driverHostPort?.let { "driverHostPort:$it" },
            ).joinToString("|")
        }

        companion object {
            fun parse(value: String): SessionValue? {
                val parts = value.split("|")
                val lastHeartbeat = parts.firstOrNull()?.toLongOrNull() ?: return null
                val driverHostPort = parts
                    .firstOrNull { it.startsWith("driverHostPort:") }
                    ?.substringAfter(":")
                    ?.toIntOrNull()
                return SessionValue(lastHeartbeat, driverHostPort)
            }
        }
    }

    companion object {
        private val ACTIVE_SESSION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(21)

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
