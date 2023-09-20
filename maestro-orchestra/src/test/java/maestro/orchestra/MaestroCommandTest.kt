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
}
