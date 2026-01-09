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

    fun assertEvent(reason: MaestroTimer.Reason, time: Long) {
        val reasonMatching = events.filter { it.reason == reason }
        if (reasonMatching.isEmpty()) throw AssertionError("No timer event for reason $reason found")

        reasonMatching.filter { it.time == time }.firstOrNull()
            ?: throw AssertionError("No timer event for $reason with time $time found (but found times: ${reasonMatching.map { it.time }})")
    }

    private data class Event(
        val reason: MaestroTimer.Reason,
        val time: Long,
    )

}
