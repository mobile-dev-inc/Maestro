package maestro.cli.util

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object TimeUtils {

    /**
     * Console output: whole seconds, except below a second where rounding would erase the
     * value entirely, so millisecond precision is kept. Reports keep the millisecond-precision
     * [Duration] they are given.
     */
    fun forDisplay(duration: Duration): Duration =
        if (duration < 1.seconds) duration
        else (duration.inWholeMilliseconds / 1000f).roundToLong().seconds
}
