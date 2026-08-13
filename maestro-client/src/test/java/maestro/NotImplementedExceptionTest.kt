package maestro

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NotImplementedExceptionTest {
    @Test
    fun `NotImplemented is a MaestroException carrying the message`() {
        val e = MaestroException.NotImplemented("device-core does not implement inputText")
        assertThat(e).isInstanceOf(MaestroException::class.java)
        assertThat(e.message).contains("inputText")
    }
}
