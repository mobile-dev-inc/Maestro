package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Resolution

enum class AssertMode { VISIBLE, NOT_VISIBLE }

/** Raised when device-core could not decide (socket refused, driver down). Never a pass or fail. */
class DeviceCoreUnavailable(msg: String) : RuntimeException(msg)

object AssertVisibleVerdict {

    /**
     * Milestone-4 visible-proxy: resolved + a MEASURED, positive-area box fully inside the screen.
     *
     * Requires the element's box to be FULLY on-screen, which is stricter than legacy
     * `assertVisible` (an element partially scrolled off is judged not-visible) — a known
     * milestone-4 limitation pending a faithful `visible` pillar.
     */
    fun isVisibleProxy(evidence: ElementEvidence, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        if (evidence.resolution !is Resolution.Resolved) return false
        val bounds = evidence.bounds
        if (bounds.source != EvidenceSource.MEASURED) return false
        val r = bounds.value ?: return false
        if (r.width <= 0 || r.height <= 0) return false
        if (r.x < 0 || r.y < 0) return false
        if (r.x + r.width > screenWidthPts || r.y + r.height > screenHeightPts) return false
        return true
    }

    fun pass(evidence: ElementEvidence, mode: AssertMode, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        if (evidence.resolution is Resolution.Unavailable) {
            throw DeviceCoreUnavailable(
                "device-core could not resolve '${evidence.target}' (Resolution.Unavailable) — " +
                    "this is an infrastructure failure, not an assertion verdict."
            )
        }
        val visible = isVisibleProxy(evidence, screenWidthPts, screenHeightPts)
        return when (mode) {
            AssertMode.VISIBLE -> visible
            AssertMode.NOT_VISIBLE -> !visible
        }
    }
}
