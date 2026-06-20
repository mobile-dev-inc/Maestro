package maestro.conformance.behavior.commands

import maestro.conformance.behavior.BehaviorContext
import maestro.conformance.logcat.FixtureEvent
import maestro.conformance.logcat.Watermark

object Poll {
    fun forEvents(
        ctx: BehaviorContext,
        w: Watermark,
        type: String,
        timeoutMs: Long = 3000,
    ): List<FixtureEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ev = ctx.reader.eventsAfter(w, type)
            if (ev.isNotEmpty()) return ev
            Thread.sleep(100)
        }
        return ctx.reader.eventsAfter(w, type)
    }
}
