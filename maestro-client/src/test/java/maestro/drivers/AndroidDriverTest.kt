package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.Dadb
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceUnreachableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.SocketTimeoutException

class AndroidDriverTest {

    @Test
    fun `clearAppState surfaces a dadb transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<DeviceUnreachableException> { driver.clearAppState("com.example.app") }
        assertThat(thrown.cause).isInstanceOf(SocketTimeoutException::class.java)
    }

    @Test
    fun `setPermissions surfaces a dadb transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("camera" to "allow"))
        }
    }

    @Test
    fun `setPermissions still swallows a non-changeable permission error`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } returns AdbShellResponse(
            output = "android.permission.INTERNET is not a changeable permission type",
            errorOutput = "",
            exitCode = 255,
        )

        val driver = AndroidDriver(dadb)

        // Best-effort path: a non-transport grant failure must NOT throw.
        driver.setPermissions("com.example.app", mapOf("camera" to "allow"))
    }
}
