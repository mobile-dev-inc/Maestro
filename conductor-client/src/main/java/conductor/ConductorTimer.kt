package conductor

object ConductorTimer {

    var sleep: (Reason, Long) -> Unit = { _, ms -> Thread.sleep(ms) }
        private set

    fun setTimerFunc(sleep: (Reason, Long) -> Unit) {
        this.sleep = sleep
    }

    enum class Reason {
        WAIT_UNTIL_VISIBLE,
        WAIT_TO_SETTLE,
        BUFFER,
    }

}