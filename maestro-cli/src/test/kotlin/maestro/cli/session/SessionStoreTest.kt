package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        SessionStore.setKeyValueStoreForTest(
            KeyValueStore(tempDir.resolve("sessions").toFile())
        )
    }

    @AfterEach
    fun tearDown() {
        SessionStore.setKeyValueStoreForTest(null)
    }

    @Test
    fun `iOS heartbeat does not make Android session see another active session`() {
        SessionStore.heartbeat("ios-session", Platform.IOS)

        val seesOther = SessionStore.hasActiveSessions("android-session", Platform.ANDROID)

        assertThat(seesOther).isFalse()
    }

    @Test
    fun `another active session on the same platform is detected`() {
        SessionStore.heartbeat("android-1", Platform.ANDROID)

        val seesOther = SessionStore.hasActiveSessions("android-2", Platform.ANDROID)

        assertThat(seesOther).isTrue()
    }

    @Test
    fun `own session entry is not counted as another active session`() {
        SessionStore.heartbeat("self", Platform.ANDROID)

        val seesOther = SessionStore.hasActiveSessions("self", Platform.ANDROID)

        assertThat(seesOther).isFalse()
    }
}
