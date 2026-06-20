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

    /**
     * Poll until an event of [type] past [w] satisfies [predicate], or [timeoutMs] elapses.
     *
     * Unlike [forEvents] — which returns as soon as *any* matching-type event arrives — this
     * method keeps polling until the predicate is satisfied.  This is necessary when an
     * operation fires multiple intermediate events of the same type before reaching the expected
     * final state (e.g. eraseText fires TEXT_CHANGED for every deleted character; we must wait
     * for the *last* deletion's event, not bail out on the first intermediate one).
     */
    fun forMatchingEvent(
        ctx: BehaviorContext,
        w: Watermark,
        type: String,
        timeoutMs: Long = 5000,
        predicate: (FixtureEvent) -> Boolean,
    ): FixtureEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val match = ctx.reader.eventsAfter(w, type).firstOrNull(predicate)
            if (match != null) return match
            Thread.sleep(100)
        }
        return ctx.reader.eventsAfter(w, type).firstOrNull(predicate)
    }
}
