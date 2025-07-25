package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class MaestroCommandTest {
    @Test
    fun `description (no commands)`() {
        // given
        val maestroCommand = MaestroCommand(null)

        // when
        val description = maestroCommand.description()

        // then
        assertThat(description)
            .isEqualTo("No op")
    }

    @Test
    fun `description (at least one command)`() {
        // given
        val maestroCommand = MaestroCommand(BackPressCommand())

        // when
        val description = maestroCommand.description()

        // then
        assertThat(description)
            .isEqualTo("Press back")
    }

    @Test
    fun `description (with a label)`() {
        // given
        val maestroCommand = MaestroCommand(SetLocationCommand("12.5266", "78.2150", "Set Location to Test Laboratory"))

        // when
        val description = maestroCommand.description()

        // then
        assertThat(description)
            .isEqualTo("Set Location to Test Laboratory")
    }

    @Test
    fun `description (negative coordinates)`() {
        // given
        val maestroCommand = MaestroCommand(SetLocationCommand("-12.5266", "-78.2150", "Set location with negative coordinates"))

        // when
        val description = maestroCommand.description()

        // then
        assertThat(description)
            .isEqualTo("Set location with negative coordinates")
    }

    @Test
    fun `toString (no commands)`() {
        // given
        val maestroCommand = MaestroCommand(null)

        // when
        val toString = maestroCommand.toString()

        // then
        assertThat(toString).isEqualTo("MaestroCommand()")
    }

    @Test
    fun `toString (at least one command)`() {
        // given
        val command = BackPressCommand()
        val maestroCommand = MaestroCommand(command)

        // when
        val toString = maestroCommand.toString()

        // then
        assertThat(toString).isEqualTo("MaestroCommand(backPressCommand=$command)")
    }
}
