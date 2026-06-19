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
    fun run(ctx: BehaviorContext): CommandOutcome
}
