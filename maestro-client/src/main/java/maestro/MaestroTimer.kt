package maestro

object MaestroTimer {

    var sleep: (Reason, Long) -> Unit = { _, ms -> Thread.sleep(ms) }
        private set

    fun setTimerFunc(sleep: (Reason, Long) -> Unit) {
        this.sleep = sleep
    }

    fun <T> withTimeout(timeoutMs: Long, block: () -> T?): T? {
        val endTime = System.currentTimeMillis() + timeoutMs

        do {
            val result = block()

            if (result != null) {
                return result
            }
        } while (System.currentTimeMillis() < endTime)

        return null
    }

    enum class Reason {
        WAIT_UNTIL_VISIBLE,
        WAIT_TO_SETTLE,
        BUFFER,
    }

}