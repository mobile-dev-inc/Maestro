package maestro.drivers

import com.google.common.truth.Truth.assertThat
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
}
