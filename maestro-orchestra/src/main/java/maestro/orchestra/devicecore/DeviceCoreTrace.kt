package maestro.orchestra.devicecore

/**
 * The per-step verdict recorded on the differential trace: a step passed, failed its own
 * assertion/action, or errored out (infra/unimplemented). Distinct from device-core's own
 * [dev.mobile.devicecore.prototype.api.Outcome] — this is Maestro's coarse pass/fail/error, the
 * thing the differential gate compares between backends.
 */
enum class Verdict { PASS, FAIL, ERROR }

/**
 * The element a step actually acted on / resolved, flattened for the trace. Geometry is in device
 * coordinates; [centerX]/[centerY] are the tap injection point (for a tap) or the bounds center
 * (for an assert). [text]/[resourceId]/[index] echo the selector that named it. Every field is
 * optional-safe: device-core does not always carry bounds or a matched channel, so absent geometry
 * reads 0 and an unnamed channel reads null.
 */
data class ChosenElement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int,
    val text: String?,
    val resourceId: String?,
    val index: Int?,
)
