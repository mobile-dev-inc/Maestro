package maestro.orchestra.backend

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
     * The driver for this run, decided from the statically-known target [platform] + the opt-in env
     * var — NO live device RPC (that was the chicken/egg: the old predicate read
     * `maestro.cachedDeviceInfo.platform`, which needs Maestro's driver already open). device-core is
     * chosen iff opted in AND Android; everything else keeps MAESTRO.
     *
     * [env] defaults to the real process environment; tests inject a fake lookup instead of mutating
     * actual process env vars (not portably possible on the JVM).
     */
    fun selectDriverKind(platform: Platform, env: (String) -> String? = System::getenv): DriverKind =
        if (env(DEVICECORE_ENV_VAR) == "1" && platform == Platform.ANDROID) DriverKind.DEVICECORE
        else DriverKind.MAESTRO

    /**
     * Builds the backend for the chosen [driverKind]. MAESTRO returns exactly what both prod call sites
     * constructed before — `LegacyExecutionBackend(maestro)`, unchanged. DEVICECORE returns the
     * device-core backend, which genuinely needs no [maestro].
     */
    fun selectBackend(driverKind: DriverKind, platform: Platform, maestro: Maestro?, appId: String?): ExecutionBackend =
        when (driverKind) {
            DriverKind.DEVICECORE -> DeviceCoreExecutionBackend(platform = platform, appId = appId)
            DriverKind.MAESTRO -> LegacyExecutionBackend(
                requireNotNull(maestro) { "A MAESTRO-kind run requires a non-null maestro to build the legacy backend" }
            )
        }
}
