package conductor.test.drivers

import conductor.ConductorTimer

class FakeTimer {

    private val events = mutableListOf<Event>()

    fun timer(): (ConductorTimer.Reason, Long) -> Unit {
        return { reason, time ->
            events.add(Event(reason, time))
        }
    }

    fun assertNoEvent(reason: ConductorTimer.Reason) {
        if (events.any { it.reason == reason }) {
            throw AssertionError("Timer event for $reason was not expected")
        }
    }

    private data class Event(
        val reason: ConductorTimer.Reason,
        val time: Long,
    )

}