package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YamlSleepCommandTest {

    @Test
    fun `fromValue with Int`() {
        val command = YamlSleepCommand.fromValue(100)
        assertThat(command.time).isEqualTo(100L)
    }

    @Test
    fun `fromValue with Long`() {
        val command = YamlSleepCommand.fromValue(100L)
        assertThat(command.time).isEqualTo(100L)
    }

    @Test
    fun `fromValue with String (ms)`() {
        val command = YamlSleepCommand.fromValue("100ms")
        assertThat(command.time).isEqualTo(100L)
    }

    @Test
    fun `fromValue with String (s)`() {
        val command = YamlSleepCommand.fromValue("1s")
        assertThat(command.time).isEqualTo(1000L)
    }

    @Test
    fun `fromValue with invalid input`() {
        assertThrows(IllegalArgumentException::class.java) {
            YamlSleepCommand.fromValue(true)
        }
    }
}
