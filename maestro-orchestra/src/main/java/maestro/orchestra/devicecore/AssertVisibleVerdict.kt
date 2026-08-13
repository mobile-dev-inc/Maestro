package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Resolution

enum class AssertMode { VISIBLE, NOT_VISIBLE }

/** Raised when device-core could not decide (socket refused, driver down, or an owed capability). Never a pass or fail. */
class DeviceCoreUnavailable(msg: String) : RuntimeException(msg)

/**
 * Turns a device-core [ElementEvidence] into an assertVisible / assertNotVisible verdict off
 * device-core's OWN visibility signal — no Maestro-side geometry. An element is VISIBLE iff
 * device-core resolved it AND its visible signal is a real (non-UNAVAILABLE) `true`; an ABSENT
 * element is not visible. Anything device-core cannot cleanly answer throws [DeviceCoreUnavailable].
 */
object AssertVisibleVerdict {

    fun pass(evidence: ElementEvidence, mode: AssertMode): Boolean {
        val visible = isVisible(evidence)
        return when (mode) {
            AssertMode.VISIBLE -> visible
            AssertMode.NOT_VISIBLE -> !visible
        }
    }

    private fun isVisible(evidence: ElementEvidence): Boolean = when (val r = evidence.resolution) {
        is Resolution.Resolved -> {
            val signal = evidence.actionability.visible
            if (signal.source == EvidenceSource.UNAVAILABLE) {
                throw DeviceCoreUnavailable(
                    "device-core resolved '${evidence.target}' but reported no visibility signal " +
                        "(actionability.visible.source=UNAVAILABLE) — an owed device-core capability, " +
                        "not an assertion verdict."
                )
            }
            signal.value
        }
        is Resolution.Absent -> false
        is Resolution.Ambiguous -> throw DeviceCoreUnavailable(
            "device-core matched '${evidence.target}' ambiguously (count=${r.count}) — no single-element " +
                "visibility verdict."
        )
        Resolution.Unavailable -> throw DeviceCoreUnavailable(
            "device-core could not resolve '${evidence.target}' (Resolution.Unavailable) — " +
                "an infrastructure failure, not an assertion verdict."
        )
    }
}
