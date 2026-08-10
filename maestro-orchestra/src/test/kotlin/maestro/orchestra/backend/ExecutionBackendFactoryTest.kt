package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceInfo
import maestro.Maestro
import maestro.device.Platform
import org.junit.jupiter.api.Test

/**
 * Task 4.1b — [ExecutionBackendFactory] picks [DeviceCoreExecutionBackend] iff the opt-in env var is
 * set to "1" AND the connected device is Android; every other combination keeps today's default,
 * [LegacyExecutionBackend], unchanged (same args: `LegacyExecutionBackend(maestro)`).
 *
 * [System.getenv] can't be mutated portably from a JVM test, so these tests inject a fake env lookup
 * via the factory's `env` parameter rather than touching real process env vars — production callers
 * (both wiring sites) always use the default, which reads the real `MAESTRO_DEVICECORE_ASSERT`.
 */
class ExecutionBackendFactoryTest {

    private fun maestroWith(platform: Platform): Maestro {
        val maestro: Maestro = mockk(relaxed = true)
        every { maestro.cachedDeviceInfo } returns DeviceInfo(
            platform = platform,
            widthPixels = 1080,
            heightPixels = 2400,
            widthGrid = 1080,
            heightGrid = 2400,
        )
        return maestro
    }

    @Test
    fun `selects device-core when env=1 and platform is Android`() {
        val maestro = maestroWith(Platform.ANDROID)
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "1" else null }

        assertThat(ExecutionBackendFactory.isDeviceCoreSelected(maestro, env)).isTrue()
        val backend = ExecutionBackendFactory.selectBackend(maestro, appId = "com.example.app", env = env)
        assertThat(backend).isInstanceOf(DeviceCoreExecutionBackend::class.java)
    }

    @Test
    fun `selects legacy when env is unset`() {
        val maestro = maestroWith(Platform.ANDROID)
        val env = { _: String -> null }

        assertThat(ExecutionBackendFactory.isDeviceCoreSelected(maestro, env)).isFalse()
        val backend = ExecutionBackendFactory.selectBackend(maestro, appId = "com.example.app", env = env)
        assertThat(backend).isInstanceOf(LegacyExecutionBackend::class.java)
    }

    @Test
    fun `selects legacy when env is set but not exactly 1`() {
        val maestro = maestroWith(Platform.ANDROID)
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "true" else null }

        assertThat(ExecutionBackendFactory.isDeviceCoreSelected(maestro, env)).isFalse()
        val backend = ExecutionBackendFactory.selectBackend(maestro, appId = "com.example.app", env = env)
        assertThat(backend).isInstanceOf(LegacyExecutionBackend::class.java)
    }

    @Test
    fun `selects legacy when env=1 but platform is not Android`() {
        val maestro = maestroWith(Platform.IOS)
        val env = { key: String -> if (key == ExecutionBackendFactory.DEVICECORE_ENV_VAR) "1" else null }

        assertThat(ExecutionBackendFactory.isDeviceCoreSelected(maestro, env)).isFalse()
        val backend = ExecutionBackendFactory.selectBackend(maestro, appId = "com.example.app", env = env)
        assertThat(backend).isInstanceOf(LegacyExecutionBackend::class.java)
    }

    @Test
    fun `default env parameter reads the real process environment (unset in test runs)`() {
        // No env override passed: exercises the production default (System::getenv). CI/local test
        // runs don't set MAESTRO_DEVICECORE_ASSERT, so this must resolve to legacy.
        val maestro = maestroWith(Platform.ANDROID)
        assertThat(ExecutionBackendFactory.isDeviceCoreSelected(maestro)).isFalse()
        assertThat(ExecutionBackendFactory.selectBackend(maestro, appId = null))
            .isInstanceOf(LegacyExecutionBackend::class.java)
    }
}
