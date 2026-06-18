package maestro.utils

import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.io.IOException

object GrpcRetry {
    fun <T> withRetry(
        callName: String,
        maxAttempts: Int = 3,
        totalBudgetMs: Long = 30_000L,
        nowMs: () -> Long = System::currentTimeMillis,
        sleepMs: (Long) -> Unit = Thread::sleep,
        onBrokenPipe: () -> Unit = {},
        call: () -> T,
    ): T {
        val deadlineMs = nowMs() + totalBudgetMs
        var lastError: StatusRuntimeException? = null
        repeat(maxAttempts) { attempt ->
            if (nowMs() >= deadlineMs) {
                throw lastError ?: error("Budget exceeded before first attempt")
            }
            try {
                return call()
            } catch (e: StatusRuntimeException) {
                lastError = e
                val code = Status.fromThrowable(e).code
                if (code != Status.Code.UNAVAILABLE) throw e
                if (attempt == maxAttempts - 1) throw e
                if (isBrokenPipe(e)) onBrokenPipe()
                sleepMs(200L)
            }
        }
        throw lastError ?: error("Unreachable: maxAttempts must be > 0")
    }

    private fun isBrokenPipe(e: StatusRuntimeException): Boolean {
        if (e.cause is IOException) return true
        val message = e.message ?: return false
        return message.contains("io exception", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true)
    }
}
