package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.Dadb
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceUnreachableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

class AndroidDriverTest {

    @Test
    fun `clearAppState surfaces a dadb broken pipe as DeviceUnreachableException`() {
        // This is the production case: pm list packages over a wedged adb transport throws
        // SocketException("Broken pipe"), which must classify as infra, not a test failure.
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketException("Broken pipe")

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<DeviceUnreachableException> { driver.clearAppState("com.example.app") }
        assertThat(thrown.cause).isInstanceOf(SocketException::class.java)
    }

    @Test
    fun `clearAppState surfaces a bare dadb IOException as DeviceUnreachableException`() {
        // Guards the no-enumeration intent: a plain IOException (not a Socket* subtype) thrown by
        // dadb.shell() is still transport death and must surface as infra.
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws IOException("connection closed")

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<DeviceUnreachableException> { driver.clearAppState("com.example.app") }
        assertThat(thrown.cause).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `clearAppState surfaces a dadb transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<DeviceUnreachableException> { driver.clearAppState("com.example.app") }
        assertThat(thrown.cause).isInstanceOf(SocketTimeoutException::class.java)
    }

    @Test
    fun `clearAppState surfaces a non-zero exitCode as IOException not DeviceUnreachableException`() {
        // Boundary guard: when the transport works but the command fails (non-zero exitCode),
        // shell() throws a plain IOException. That is a legitimate device-answered signal and must
        // NOT be reclassified as a transport failure.
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } returns AdbShellResponse(
            output = "",
            errorOutput = "cmd: Failure",
            exitCode = 1,
        )

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<IOException> { driver.clearAppState("com.example.app") }
        assertThat(thrown).isNotInstanceOf(DeviceUnreachableException::class.java)
    }

    @Test
    fun `setPermissions surfaces a dadb broken pipe as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketException("Broken pipe")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("camera" to "allow"))
        }
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

    @Test
    fun `setPermissions all surfaces an APK-pull transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        // setAllPermissions -> AndroidAppFiles.getApkFile -> dadb.shell("pm list packages -f ...")
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("all" to "allow"))
        }
    }

    @Test
    fun `setPermissions all surfaces an APK-pull broken pipe as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketException("Broken pipe")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("all" to "allow"))
        }
    }
}
