package maestro.drivers

import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.KeyCode
import maestro.android.AndroidDeviceConnection
import org.junit.jupiter.api.Test

class AndroidDriverPressKeyTest {

    private val connection = mockk<AndroidDeviceConnection>(relaxed = true) {
        every { shell(any()) } returns mockk<AdbShellResponse> {
            every { exitCode } returns 0
            every { output } returns ""
        }
    }

    @Test
    fun `pressKey Tab sends KEYCODE_TAB`() {
        AndroidDriver(connection = connection).pressKey(KeyCode.TAB)

        verify(exactly = 1) { connection.shell("input keyevent 61") }
    }
}
