package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Outcome
import maestro.MaestroException

/**
 * Reads an assertVisible verdict off a waited [ActionEvidence]'s [Outcome] — device-core's own
 * answer, not Maestro-side geometry. [Outcome.Acted] is a pass (null); [Outcome.Absent] and
 * [Outcome.Blocked] are a false verdict ([MaestroException.AssertionFailure]); [Outcome.Crashed]
 * is an [MaestroException.AppCrash]. Mirrors [DeviceCoreErrorMapper.tapOutcomeToException] but
 * Absent is a not-visible assertion failure here, not ElementNotFound.
 */
object WaitOutcomeVerdict {
    fun toException(evidence: ActionEvidence, selectorDesc: String, timeoutMs: Long): MaestroException? =
        when (val o = evidence.outcome) {
            is Outcome.Acted -> null
            is Outcome.Absent -> MaestroException.AssertionFailure(
                message = "Assertion is false: $selectorDesc is not visible within ${timeoutMs}ms",
                debugMessage = "device-core waitFor Absent (${o.via}, cap=${o.capMs}ms) for $selectorDesc",
            )
            is Outcome.Blocked -> MaestroException.AssertionFailure(
                message = "Assertion is false: $selectorDesc is not visible within ${timeoutMs}ms",
                debugMessage = "device-core waitFor Blocked for $selectorDesc: ${o.detail} " +
                    "(visible=${evidence.actionability.visible.value}, " +
                    "source=${evidence.actionability.visible.source})",
            )
            is Outcome.Crashed -> MaestroException.AppCrash(
                "App ${o.appId} crashed during assertVisible on $selectorDesc"
            )
        }
}
