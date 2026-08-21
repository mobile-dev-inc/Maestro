package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceEnvError
import dev.mobile.devicecore.prototype.api.DeviceResolutionFailure
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Outcome
import kotlinx.coroutines.CancellationException
import maestro.DeviceUnreachableException
import maestro.MaestroException

/**
 * Maps device-core results and typed throws onto Maestro's OWN exception taxonomy — in Maestro's
 * domain terms, never a consumer's INFRA/TEST/CRASH vocabulary. There is no decline path: an
 * unmapped/infra failure surfaces as a device-connection exception, a test failure as the matching
 * MaestroException.
 *
 * Device-core has no serializable view tree, so the assertion exceptions it produces carry no
 * `hierarchyRoot` — it is left null. That keeps this mapper free of `maestro.TreeNode`, which the
 * migration removes.
 */
object DeviceCoreErrorMapper {

    /** Success -> null; a policy-negative [Outcome] -> the matching MaestroException to throw. */
    fun tapOutcomeToException(outcome: Outcome, selectorDesc: String): MaestroException? = when (outcome) {
        is Outcome.Acted -> null
        is Outcome.Absent -> MaestroException.ElementNotFound(
            message = "No visible element found: $selectorDesc",
            debugMessage = "device-core tap resolved Absent (${outcome.via}) for $selectorDesc",
        )
        is Outcome.Crashed -> MaestroException.AppCrash(
            "App ${outcome.appId} crashed during tap on $selectorDesc"
        )
        is Outcome.Blocked -> MaestroException.AssertionFailure(
            message = "Element not actionable: $selectorDesc",
            debugMessage = "device-core tap Blocked for $selectorDesc: ${outcome.detail}",
        )
    }

    /** Launch/connect/inspect infra throws -> Maestro's launch/connection taxonomy. Anything else
     *  is returned unchanged — there is no decline path. */
    fun mapInfraThrow(t: Throwable, operation: String): Throwable {
        // device-core CONVENTIONS.md: rethrow cancellation BEFORE mapping — on the JVM
        // CancellationException is-a IllegalStateException, so mapping would launder it.
        if (t is CancellationException) throw t
        return when (t) {
            is DeviceEnvError -> MaestroException.UnableToLaunchApp(
                message = "device-core could not $operation: ${t.message}",
                cause = t,
            )
            is DeviceResolutionFailure,
            is InjectionUnavailable -> DeviceUnreachableException(operation = operation, cause = t)
            // device-core's roadmap throws (iOS waitFor, Android nth) are raw Kotlin Errors. Map them
            // to a clean NotImplemented instead of letting them surface as a crash. General fix —
            // future-proofs every roadmap-throw, not just waitFor.
            is NotImplementedError -> MaestroException.NotImplemented(
                "device-core has not implemented $operation: ${t.message}"
            )
            else -> t
        }
    }
}
