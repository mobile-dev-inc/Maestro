package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

object SessionStore {

    private val defaultKeyValueStore by lazy {
        KeyValueStore(
            dbFile = Paths
                .get(System.getProperty("user.home"), ".maestro", "sessions")
                .toFile()
                .also { it.parentFile.mkdirs() }
        )
    }

    @Volatile
    private var keyValueStoreOverride: KeyValueStore? = null

    private val keyValueStore: KeyValueStore
        get() = keyValueStoreOverride ?: defaultKeyValueStore

    internal fun setKeyValueStoreForTest(store: KeyValueStore?) {
        keyValueStoreOverride = store
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
            val currentKey = key(sessionId, platform)
            val platformPrefix = "${platform}_"
            return activeSessions()
                .any { it != currentKey && it.startsWith(platformPrefix) }
        }
    }

    private fun key(sessionId: String, platform: Platform): String {
        return "${platform}_$sessionId"
    }

}