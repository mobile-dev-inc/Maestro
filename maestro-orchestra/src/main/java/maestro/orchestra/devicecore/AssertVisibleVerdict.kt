package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Resolution
import maestro.orchestra.backend.BackendUnsupportedOperation

enum class AssertMode { VISIBLE, NOT_VISIBLE }

/** Raised when device-core's transport/driver is down (infra only). Never a pass or fail. */
class DeviceCoreUnavailable(msg: String) : RuntimeException(msg)

/**
 * Turns a device-core [ElementEvidence] into an assertVisible / assertNotVisible verdict off
 * device-core's OWN visibility signal — no Maestro-side geometry. Visibility is a first-class
 * device-core pillar: `inspect(): ElementEvidence` carries `actionability.visible: Signal` (sourced
 * from the platform's `isVisibleToUser`). An element is VISIBLE iff device-core resolved it AND its
 * visible signal is a real (non-UNAVAILABLE) `true`; an ABSENT element is not visible.
 *
 * Anything device-core cannot cleanly answer THROWS instead of fabricating a verdict from evidence it
 * did not actually produce:
 *  - Owed/ambiguous — a [Resolution.Resolved] element whose visible signal is UNAVAILABLE (an owed
 *    device-core capability), or [Resolution.Ambiguous] (no single element to score) — THROWS
 *    [BackendUnsupportedOperation]. Orchestra's lifecycle maps that to ERROR (a "gap" in the harness's
 *    OWED bucket), the same treatment as any other not-yet-built verb.
 *  - Infra — [Resolution.Unavailable] (driver/transport down) — THROWS [DeviceCoreUnavailable]. That
 *    stays infra-only: a genuine transport failure, not a coverage gap.
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
                throw BackendUnsupportedOperation(
                    "device-core resolved '${evidence.target}' but reported no visibility signal " +
                        "(actionability.visible.source=UNAVAILABLE) — an owed device-core capability, " +
                        "not an assertion verdict."
                )
            }
            signal.value
        }
        is Resolution.Absent -> false
        is Resolution.Ambiguous -> throw BackendUnsupportedOperation(
            "device-core matched '${evidence.target}' ambiguously (count=${r.count}) — no single-element " +
                "visibility verdict."
        )
        Resolution.Unavailable -> throw DeviceCoreUnavailable(
            "device-core could not resolve '${evidence.target}' (Resolution.Unavailable) — " +
                "an infrastructure failure, not an assertion verdict."
        )
    }
}
