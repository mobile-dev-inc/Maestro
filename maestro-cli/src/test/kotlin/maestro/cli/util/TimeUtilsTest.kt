package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimeUtilsTest {

    @Test
    fun `forDisplay keeps millisecond precision below a second`() {
        assertThat(TimeUtils.forDisplay(45.milliseconds)).isEqualTo(45.milliseconds)
        assertThat(TimeUtils.forDisplay(400.milliseconds)).isEqualTo(400.milliseconds)
        assertThat(TimeUtils.forDisplay(999.milliseconds)).isEqualTo(999.milliseconds)
        assertThat(TimeUtils.forDisplay(Duration.ZERO)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `forDisplay rounds down from a second upwards`() {
        assertThat(TimeUtils.forDisplay(1_000.milliseconds)).isEqualTo(1.seconds)
        assertThat(TimeUtils.forDisplay(1_499.milliseconds)).isEqualTo(1.seconds)
    }

    @Test
    fun `forDisplay rounds up from the half second`() {
        assertThat(TimeUtils.forDisplay(1_500.milliseconds)).isEqualTo(2.seconds)
        assertThat(TimeUtils.forDisplay(1_600.milliseconds)).isEqualTo(2.seconds)
    }

    @Test
    fun `forDisplay keeps whole seconds unchanged`() {
        assertThat(TimeUtils.forDisplay(2.seconds)).isEqualTo(2.seconds)
    }

    @Test
    fun `forDisplay rounds durations spanning minutes`() {
        assertThat(TimeUtils.forDisplay(90_400.milliseconds)).isEqualTo(90.seconds)
        assertThat(TimeUtils.forDisplay(1_915_947.milliseconds)).isEqualTo(1_916.seconds)
    }
}
