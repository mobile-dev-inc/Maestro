package maestro.orchestra.backend

import dev.mobile.devicecore.prototype.api.adaptors.android.AndroidDeviceProvider
import maestro.Maestro
import maestro.device.Platform

/**
 * Chooses the [ExecutionBackend] for a prod `test`-flow run (task 4.1b). Lives in maestro-orchestra
 * (not maestro-cli) because [DeviceCoreExecutionBackend]'s default provider comes from device-core's
 * prototype artifact, which is only an `implementation` dependency of this module — putting the
 * factory here keeps that dependency out of maestro-cli's compile classpath.
 *
 * Opt-in only, via an env var (not a CLI flag — no Picocli plumbing): set
 * `MAESTRO_DEVICECORE_ASSERT=1` on an Android run to route it through device-core. Every other case
 * (env unset, env not "1", or a non-Android platform) keeps today's default: [LegacyExecutionBackend].
 */
object ExecutionBackendFactory {

    const val DEVICECORE_ENV_VAR = "MAESTRO_DEVICECORE_ASSERT"

    /**
     * True iff this run should use the device-core backend. Exposed separately from [selectBackend]
     * so both call sites can derive the matching `platform =` override (device-core only supports
     * Android) from the exact same condition, instead of re-deriving it and risking drift.
     *
     * [env] defaults to the real process environment; tests inject a fake lookup instead of mutating
     * actual process env vars (not portably possible on the JVM).
     */
    fun isDeviceCoreSelected(maestro: Maestro, env: (String) -> String? = System::getenv): Boolean =
        env(DEVICECORE_ENV_VAR) == "1" && maestro.cachedDeviceInfo.platform == Platform.ANDROID

    /**
     * Builds the backend for this run. When [isDeviceCoreSelected] is false this returns exactly what
     * both prod call sites constructed before this factory existed —
     * `LegacyExecutionBackend(maestro)` — with no other change to args or behavior.
     */
    fun selectBackend(maestro: Maestro, appId: String?, env: (String) -> String? = System::getenv): ExecutionBackend =
        if (isDeviceCoreSelected(maestro, env)) {
            DeviceCoreExecutionBackend(
                appId = appId,
                providerFactory = { AndroidDeviceProvider() },
                screenSize = maestro.cachedDeviceInfo.widthPixels to maestro.cachedDeviceInfo.heightPixels,
            )
        } else {
            LegacyExecutionBackend(maestro)
        }
}
