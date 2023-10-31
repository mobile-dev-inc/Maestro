package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.cli.device.Platform
import maestro.utils.MaestroDirectory
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.div

object SessionStore {

    private val keyValueStore by lazy {
        KeyValueStore(
            (MaestroDirectory.getMaestroDirectory() / "sessions").apply {
                parent.createDirectories()
            }.toFile()
        )
    }

    fun heartbeat(sessionId: String, platform: Platform) {
        synchronized(keyValueStore) {
            keyValueStore.set(
                key(sessionId, platform),
                System.currentTimeMillis().toString()
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

    fun hasActiveSessions(
        sessionId: String,
        platform: Platform
    ): Boolean {
        synchronized(keyValueStore) {
            return activeSessions()
                .any { it != key(sessionId, platform) }
        }
    }

    fun <T> withExclusiveLock(block: () -> T): T {
        return keyValueStore.withExclusiveLock(block)
    }

    private fun key(sessionId: String, platform: Platform): String {
        return "${platform}_$sessionId"
    }

}