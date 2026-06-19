package maestro.conformance.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CmdTest {
    @Test fun `captures stdout and zero exit`() {
        val r = Cmd.run("/bin/echo", "hello")
        assertThat(r.exit).isEqualTo(0)
        assertThat(r.stdout.trim()).isEqualTo("hello")
    }
    @Test fun `captures non-zero exit`() {
        val r = Cmd.run("/bin/sh", "-c", "exit 3")
        assertThat(r.exit).isEqualTo(3)
    }
}
