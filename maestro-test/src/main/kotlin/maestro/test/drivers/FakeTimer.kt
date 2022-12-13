package maestro.test.drivers

import maestro.utils.MaestroTimer

class FakeTimer {

    private val events = mutableListOf<Event>()

    fun timer(): (MaestroTimer.Reason, Long) -> Unit {
        return { reason, time ->
            events.add(Event(reason, time))
        }
    }

    fun assertNoEvent(reason: MaestroTimer.Reason) {
        if (events.any { it.reason == reason }) {
            throw AssertionError("Timer event for $reason was not expected")
        }
    }

    private data class Event(
        val reason: MaestroTimer.Reason,
        val time: Long,
    )

}
