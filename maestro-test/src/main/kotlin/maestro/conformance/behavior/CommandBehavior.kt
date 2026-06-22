package maestro.conformance.behavior

import maestro.conformance.logcat.LogcatEventReader
import maestro.conformance.logcat.Watermark
import maestro.drivers.AndroidDriver

class BehaviorContext(
    val driver: AndroidDriver,
    val reader: LogcatEventReader,
    val serial: String,
    val apiLevel: Int,
    val appId: String,
    /** Sends a MARK to the fixture and returns the resulting watermark. Wired in Task 11. */
    val markWatermark: () -> Watermark,
)

interface CommandBehavior {
    val name: String
    val coverage: Coverage
    /**
     * Frameworks this behavior applies to (e.g. setOf("compose")); null = all frameworks.
     * Use for capabilities that only exist on one toolkit — e.g. Compose `mergeDescendants` has
     * no native equivalent. The runner skips the command on frameworks it doesn't apply to, so the
     * matrix shows a blank (not a failure) for the inapplicable cell.
     */
    val frameworks: Set<String>? get() = null
    fun run(ctx: BehaviorContext): CommandOutcome
}
