package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import maestro.Maestro
import maestro.device.Platform
import org.junit.jupiter.api.Test

/**
 * Task 4.D2-T1 — [ExecutionBackendFactory.selectDriverKind] picks [DriverKind.DEVICECORE] iff the
 * opt-in env var is set to "1" AND the statically-known target [Platform] is Android; every other
 * combination keeps today's default, [DriverKind.MAESTRO]. The decision is now a pure function of
 * [Platform] — no [Maestro]/`cachedDeviceInfo` involved. [ExecutionBackendFactory.selectBackend] is a
 * separate, dumb switch on the already-decided [DriverKind] that still needs a [Maestro] to build
 * [LegacyExecutionBackend] (same args as before this task: `LegacyExecutionBackend(maestro)`).
 *
 * [System.getenv] can't be mutated portably from a JVM test, so these tests inject a fake env lookup
 * via the factory's `env` parameter rather than touching real process env vars — production callers
 * (both wiring sites) always use the default, which reads the real `MAESTRO_DEVICECORE_ASSERT`.
 */
class ExecutionBackendFactoryTest {

    @Test
    fun `selects device-core when env=1 and platform is Android`() {
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "1" else null }

        assertThat(ExecutionBackendFactory.selectDriverKind(Platform.ANDROID, env)).isEqualTo(DriverKind.DEVICECORE)
    }

    @Test
    fun `selects legacy when env is unset`() {
        val env = { _: String -> null }

        assertThat(ExecutionBackendFactory.selectDriverKind(Platform.ANDROID, env)).isEqualTo(DriverKind.MAESTRO)
    }

    @Test
    fun `selects legacy when env is set but not exactly 1`() {
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "true" else null }

        assertThat(ExecutionBackendFactory.selectDriverKind(Platform.ANDROID, env)).isEqualTo(DriverKind.MAESTRO)
    }

    @Test
    fun `selects legacy when env=1 but platform is not Android`() {
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "1" else null }

        assertThat(ExecutionBackendFactory.selectDriverKind(Platform.IOS, env)).isEqualTo(DriverKind.MAESTRO)
    }

    @Test
    fun `default env parameter reads the real process environment (unset in test runs)`() {
        // No env override passed: exercises the production default (System::getenv). CI/local test
        // runs don't set MAESTRO_DEVICECORE_ASSERT, so this must resolve to legacy.
        assertThat(ExecutionBackendFactory.selectDriverKind(Platform.ANDROID)).isEqualTo(DriverKind.MAESTRO)
    }

    @Test
    fun `selectBackend builds DeviceCoreExecutionBackend for DEVICECORE`() {
        val maestro: Maestro = mockk(relaxed = true)

        val backend = ExecutionBackendFactory.selectBackend(DriverKind.DEVICECORE, maestro, appId = "com.example.app")

        assertThat(backend).isInstanceOf(DeviceCoreExecutionBackend::class.java)
    }

    @Test
    fun `selectBackend builds LegacyExecutionBackend for MAESTRO`() {
        val maestro: Maestro = mockk(relaxed = true)

        val backend = ExecutionBackendFactory.selectBackend(DriverKind.MAESTRO, maestro, appId = "com.example.app")

        assertThat(backend).isInstanceOf(LegacyExecutionBackend::class.java)
    }
}
