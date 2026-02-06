package dev.mobile.maestro.utils

import android.util.Log

object Retry {
    private const val TAG = "Maestro"

    /**
     * Retries a block with exponential backoff until it returns a non-null value or max attempts is reached.
     *
     * @param maxAttempts Maximum number of attempts before giving up
     * @param baseDelayMs Base delay in milliseconds (will be multiplied by 2^attempt)
     * @param operationName Name of the operation for logging purposes
     * @param block The block to execute that returns a nullable value
     * @return The result of the block if successful, null if all attempts failed
     */
    fun <T> withExponentialBackoff(
        maxAttempts: Int,
        baseDelayMs: Long,
        operationName: String,
        block: () -> T?
    ): T? {
        for (attempt in 1..maxAttempts) {
            val result = block()
            if (result != null) return result

            if (attempt < maxAttempts) {
                val delayMs = baseDelayMs * (1 shl (attempt - 1)) // 2^(attempt-1)
                Log.w(TAG, "$operationName attempt $attempt failed, retrying after ${delayMs}ms...")
                Thread.sleep(delayMs)
            }
        }
        return null
    }
}
