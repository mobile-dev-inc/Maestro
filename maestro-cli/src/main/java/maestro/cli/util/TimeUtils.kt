package maestro.cli.util

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object TimeUtils {

    fun durationInSeconds(startTimeInMillis: Long?, endTimeInMillis: Long?): Duration {
        if (startTimeInMillis == null || endTimeInMillis == null) return Duration.ZERO
        return ((endTimeInMillis - startTimeInMillis) / 1000f).roundToLong().seconds
    }

    fun durationInSeconds(durationInMillis: Long): Duration {
        return ((durationInMillis) / 1000f).roundToLong().seconds
    }
}