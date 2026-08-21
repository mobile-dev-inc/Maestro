package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.ResolvedChannel
import dev.mobile.devicecore.prototype.api.SearchedSurface
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced

/**
 * A tiny test factory building real device-core [ElementEvidence] values — no mocks, no
 * stand-ins. Geometry ([ElementEvidence.bounds]) is irrelevant to visibility verdicts, so it is
 * always UNAVAILABLE here; only [Resolution] and [Actionability.visible] vary per case.
 */
object DeviceCoreEvidence {

    /** Resolved and reporting a real (MEASURED) `visible = true` signal. */
    fun resolvedVisible(target: String): ElementEvidence = ElementEvidence(
        target = target,
        resolution = Resolution.Resolved(ResolvedChannel.TEXT),
        actionability = Actionability(
            attached = Signal(true, EvidenceSource.MEASURED),
            visible = Signal(true, EvidenceSource.MEASURED),
            enabled = Signal(true, EvidenceSource.MEASURED),
            hittable = Signal(true, EvidenceSource.MEASURED),
            stable = Signal(false, EvidenceSource.UNAVAILABLE),
        ),
        bounds = Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    /** Not found anywhere on screen. */
    fun absent(target: String): ElementEvidence = ElementEvidence(
        target = target,
        resolution = Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
        actionability = Actionability(
            attached = Signal(false, EvidenceSource.MEASURED),
            visible = Signal(false, EvidenceSource.MEASURED),
            enabled = Signal(false, EvidenceSource.UNAVAILABLE),
            hittable = Signal(false, EvidenceSource.UNAVAILABLE),
            stable = Signal(false, EvidenceSource.UNAVAILABLE),
        ),
        bounds = Sourced(null, EvidenceSource.UNAVAILABLE),
    )
}
