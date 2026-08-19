package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceEnvError
import dev.mobile.devicecore.prototype.api.DeviceResolutionFailure
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Outcome
import maestro.DeviceUnreachableException
import maestro.MaestroException
import maestro.TreeNode

/**
 * Maps device-core results and typed throws onto Maestro's OWN exception taxonomy — in Maestro's
 * domain terms, never a consumer's INFRA/TEST/CRASH vocabulary. There is no decline path: an
 * unmapped/infra failure surfaces as a device-connection exception, a test failure as the matching
 * MaestroException.
 */
object DeviceCoreErrorMapper {

    /** The empty view tree passed to [MaestroException.AssertionFailure] / [MaestroException.ElementNotFound]
     *  — device-core has no serializable view tree, and hierarchyRoot is non-null on both. */
    fun emptyHierarchy(): TreeNode = TreeNode()

    /** Success -> null; a policy-negative [Outcome] -> the matching MaestroException to throw. */
    fun tapOutcomeToException(outcome: Outcome, selectorDesc: String): MaestroException? = when (outcome) {
        is Outcome.Acted -> null
        is Outcome.Absent -> MaestroException.ElementNotFound(
            message = "No visible element found: $selectorDesc",
            hierarchyRoot = emptyHierarchy(),
            debugMessage = "device-core tap resolved Absent (${outcome.via}) for $selectorDesc",
        )
        is Outcome.Crashed -> MaestroException.AppCrash(
            "App ${outcome.appId} crashed during tap on $selectorDesc"
        )
        is Outcome.Blocked -> MaestroException.AssertionFailure(
            message = "Element not actionable: $selectorDesc",
            hierarchyRoot = emptyHierarchy(),
            debugMessage = "device-core tap Blocked for $selectorDesc: ${outcome.detail}",
        )
    }

    /** Launch/connect/inspect infra throws -> Maestro's launch/connection taxonomy. Anything else
     *  is returned unchanged — there is no decline path. */
    fun mapInfraThrow(t: Throwable, operation: String): Throwable = when (t) {
        is DeviceEnvError -> MaestroException.UnableToLaunchApp(
            message = "device-core could not $operation: ${t.message}",
            cause = t,
        )
        is DeviceResolutionFailure,
        is InjectionUnavailable,
        is DeviceCoreUnavailable -> DeviceUnreachableException(operation = operation, cause = t)
        else -> t
    }
}
