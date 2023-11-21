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
        val maestroCommand = MaestroCommand(SetLocationCommand(12.5266, 78.2150, "Set Location to Test Laboratory"))

        // when
        val description = maestroCommand.description()

        // then
        assertThat(description)
            .isEqualTo("Set Location to Test Laboratory")
    }
}
