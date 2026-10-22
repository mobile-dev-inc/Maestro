package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sessionStore: SessionStore

    @BeforeEach
    fun setUp() {
        val sessionsFile = tempDir.resolve("sessions").toFile()
        sessionStore = SessionStore(KeyValueStore(sessionsFile))
    }

    // --- Basic behavior ---

    @Test
    fun `heartbeat creates a session that appears in activeSessions`() {
        sessionStore.heartbeat("session-1", Platform.IOS, "device-A")

        assertThat(sessionStore.activeSessions()).hasSize(1)
    }

    @Test
    fun `delete removes a session from activeSessions`() {
        sessionStore.heartbeat("session-1", Platform.IOS, "device-A")
        sessionStore.delete("session-1", Platform.IOS, "device-A")

        assertThat(sessionStore.activeSessions()).isEmpty()
    }

    // --- Task #16: Session key format migration ---

    @Test
    fun `old format keys should not match hasActiveSessionForDevice`() {
        // Manually write an old-format key (pre-upgrade)
        val kvStore = KeyValueStore(tempDir.resolve("sessions-migration").toFile())
        kvStore.set("ANDROID_old-session-uuid", System.currentTimeMillis().toString())
        val store = SessionStore(kvStore)

        // Looking for sessions on a specific device should not match old-format keys
        val hasSession = store.hasActiveSessionForDevice(
            "new-session", Platform.ANDROID, "emulator-5554"
        )

        assertThat(hasSession).isFalse()
    }

    // --- Per-device session isolation ---

    @Test
    fun `hasActiveSessionForDevice excludes current session`() {
        sessionStore.heartbeat("session-1", Platform.IOS, "device-A")

        val hasOther = sessionStore.hasActiveSessionForDevice(
            "session-1", Platform.IOS, "device-A"
        )

        // Should not find itself
        assertThat(hasOther).isFalse()
    }

    @Test
    fun `hasActiveSessionForDevice detects another session on same device`() {
        sessionStore.heartbeat("session-1", Platform.IOS, "device-A")
        sessionStore.heartbeat("session-2", Platform.IOS, "device-A")

        val hasOther = sessionStore.hasActiveSessionForDevice(
            "session-1", Platform.IOS, "device-A"
        )

        assertThat(hasOther).isTrue()
    }

    @Test
    fun `hasActiveSessionForDevice ignores sessions on different devices`() {
        sessionStore.heartbeat("session-1", Platform.IOS, "device-A")
        sessionStore.heartbeat("session-2", Platform.IOS, "device-B")

        val hasOther = sessionStore.hasActiveSessionForDevice(
            "session-1", Platform.IOS, "device-A"
        )

        // session-2 is on device-B, should not count
        assertThat(hasOther).isFalse()
    }

    // --- Task #17: Per-device session close ---

    @Test
    fun `activeSessionsForDevice only returns sessions for that device`() {
        sessionStore.heartbeat("s1", Platform.IOS, "device-A")
        sessionStore.heartbeat("s2", Platform.ANDROID, "emulator-5554")
        sessionStore.heartbeat("s3", Platform.IOS, "device-B")

        val deviceASessions = sessionStore.activeSessionsForDevice(Platform.IOS, "device-A")

        assertThat(deviceASessions).hasSize(1)
        assertThat(deviceASessions.single()).isEqualTo("IOS_device-A_s1")
    }

    @Test
    fun `shouldCloseSession returns true when no sessions remain for device even if other devices have sessions`() {
        sessionStore.heartbeat("ios-session", Platform.IOS, "device-A")
        sessionStore.heartbeat("android-session", Platform.ANDROID, "emulator-5554")

        sessionStore.delete("android-session", Platform.ANDROID, "emulator-5554")

        val shouldClose = sessionStore.shouldCloseSession(Platform.ANDROID, "emulator-5554")
        assertThat(shouldClose).isTrue()
    }

    @Test
    fun `shouldCloseSession returns false when another session remains on the same device`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "emulator-5554")
        sessionStore.heartbeat("session-2", Platform.ANDROID, "emulator-5554")

        sessionStore.delete("session-1", Platform.ANDROID, "emulator-5554")

        val shouldClose = sessionStore.shouldCloseSession(Platform.ANDROID, "emulator-5554")
        assertThat(shouldClose).isFalse()
    }

    // --- Prune behavior ---

    @Test
    fun `pruneInactiveSessions removes entries older than 21 seconds`() {
        val kvStore = KeyValueStore(tempDir.resolve("sessions-prune").toFile())
        val store = SessionStore(kvStore)

        // Write a stale entry directly (22 seconds ago)
        kvStore.set("IOS_device-A_stale-session", (System.currentTimeMillis() - 22000).toString())
        // Write a fresh entry
        store.heartbeat("fresh-session", Platform.IOS, "device-A")

        // heartbeat triggers pruneInactiveSessions internally
        val active = store.activeSessions()
        assertThat(active).hasSize(1)
        assertThat(active.single()).isEqualTo("IOS_device-A_fresh-session")
    }

    // --- Device claims ---

    @Test
    fun `tryClaim succeeds on a free device and records the session`() {
        val claimed = sessionStore.tryClaim("session-1", Platform.ANDROID, "emulator-5554")

        assertThat(claimed).isTrue()
        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_emulator-5554_session-1")
    }

    @Test
    fun `tryClaim fails while another live session holds the device`() {
        sessionStore.heartbeat("holder", Platform.ANDROID, "emulator-5554")

        val claimed = sessionStore.tryClaim("newcomer", Platform.ANDROID, "emulator-5554")

        assertThat(claimed).isFalse()
        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_emulator-5554_holder")
    }

    @Test
    fun `only the first of two claims on the same device succeeds`() {
        val first = sessionStore.tryClaim("session-1", Platform.ANDROID, "emulator-5554")
        val second = sessionStore.tryClaim("session-2", Platform.ANDROID, "emulator-5554")

        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `tryClaim by the holding session is idempotent`() {
        sessionStore.tryClaim("session-1", Platform.ANDROID, "emulator-5554")

        assertThat(sessionStore.tryClaim("session-1", Platform.ANDROID, "emulator-5554")).isTrue()
    }

    @Test
    fun `tryClaim ignores sessions on other devices and platforms`() {
        sessionStore.heartbeat("other-device", Platform.ANDROID, "emulator-5556")
        sessionStore.heartbeat("other-platform", Platform.IOS, "emulator-5554")

        assertThat(sessionStore.tryClaim("session-1", Platform.ANDROID, "emulator-5554")).isTrue()
    }

    @Test
    fun `tryClaim prunes a crashed holder whose heartbeat is stale`() {
        val kvStore = KeyValueStore(tempDir.resolve("sessions-claim-stale").toFile())
        val store = SessionStore(kvStore)
        kvStore.set("ANDROID_emulator-5554_crashed", (System.currentTimeMillis() - 22000).toString())

        assertThat(store.tryClaim("session-1", Platform.ANDROID, "emulator-5554")).isTrue()
        assertThat(store.activeSessions()).containsExactly("ANDROID_emulator-5554_session-1")
    }

    @Test
    fun `old format keys do not block a claim`() {
        val kvStore = KeyValueStore(tempDir.resolve("sessions-claim-old").toFile())
        kvStore.set("ANDROID_old-session-uuid", System.currentTimeMillis().toString())
        val store = SessionStore(kvStore)

        assertThat(store.tryClaim("session-1", Platform.ANDROID, "emulator-5554")).isTrue()
    }

    @Test
    fun `awaitClaim without a timeout fails at once without waiting or writing`() {
        sessionStore.heartbeat("holder", Platform.ANDROID, "emulator-5554")
        var busyCalls = 0
        val sleeps = mutableListOf<Long>()

        val claimed = sessionStore.awaitClaim(
            "newcomer", Platform.ANDROID, "emulator-5554",
            timeoutMs = 0,
            onBusy = { busyCalls++ },
            sleep = { sleeps += it },
        )

        assertThat(claimed).isFalse()
        assertThat(busyCalls).isEqualTo(0)
        assertThat(sleeps).isEmpty()
        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_emulator-5554_holder")
    }

    @Test
    fun `awaitClaim waits for the holder to finish, then claims`() {
        sessionStore.heartbeat("holder", Platform.ANDROID, "emulator-5554")
        var busyCalls = 0
        var polls = 0

        val claimed = sessionStore.awaitClaim(
            "newcomer", Platform.ANDROID, "emulator-5554",
            timeoutMs = 60_000,
            onBusy = { busyCalls++ },
            sleep = {
                polls++
                if (polls == 3) sessionStore.delete("holder", Platform.ANDROID, "emulator-5554")
            },
        )

        assertThat(claimed).isTrue()
        assertThat(busyCalls).isEqualTo(1)
        assertThat(polls).isEqualTo(3)
        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_emulator-5554_newcomer")
    }

    @Test
    fun `awaitClaim gives up at the deadline without writing`() {
        sessionStore.heartbeat("holder", Platform.ANDROID, "emulator-5554")
        var clock = System.currentTimeMillis()

        val claimed = sessionStore.awaitClaim(
            "newcomer", Platform.ANDROID, "emulator-5554",
            timeoutMs = 5_000,
            now = { clock },
            sleep = { clock += it; sessionStore.heartbeat("holder", Platform.ANDROID, "emulator-5554") },
        )

        assertThat(claimed).isFalse()
        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_emulator-5554_holder")
    }
}
