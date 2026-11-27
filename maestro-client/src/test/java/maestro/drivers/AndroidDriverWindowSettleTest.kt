package maestro.drivers

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.ViewHierarchy
import maestro.android.AndroidDeviceConnection
import maestro_android.MaestroDriverGrpc
import maestro_android.checkWindowUpdatingResponse
import maestro_android.deviceInfo
import maestro_android.viewHierarchyResponse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the wait [AndroidDriver.waitForAppToSettle] does when it is told which app to
 * watch, as it is before a tap on an element.
 *
 * We never touch a real device: a fake device server answers `isWindowUpdating` the way the real
 * one does - at once when the app is not in front, and otherwise only after listening for half a
 * second.
 */
class AndroidDriverWindowSettleTest {

    private val windowChecks = AtomicInteger()

    private fun connection(windowUpdating: (check: Int) -> Boolean): AndroidDeviceConnection {
        val emptyScreen = viewHierarchyResponse { hierarchy = "<hierarchy rotation=\"0\"/>" }
        val screenSize = deviceInfo { widthPixels = 1080; heightPixels = 2400 }
        val server = mockk<MaestroDriverGrpc.MaestroDriverBlockingStub>()
        every { server.isWindowUpdating(any()) } answers {
            val updating = windowUpdating(windowChecks.incrementAndGet())
            checkWindowUpdatingResponse { isWindowUpdating = updating }
        }
        every { server.viewHierarchy(any()) } returns emptyScreen
        every { server.deviceInfo(any()) } returns screenSize
        return AndroidDeviceConnection.forTest(dadb = mockk(relaxed = true), blockingStubProvider = { server })
    }

    // The server listened for its whole period and saw no change.
    private fun quietListen(): Boolean {
        Thread.sleep(SERVER_LISTEN_MS)
        return false
    }

    private fun settle(connection: AndroidDeviceConnection) {
        val driver = AndroidDriver(connection = connection)
        driver.waitForAppToSettle(ViewHierarchy.from(driver, false), APP_ID, null)
    }

    @Test
    fun `a window that stays quiet for a whole listen is not listened to again`() {
        settle(connection { quietListen() })

        assertThat(windowChecks.get()).isEqualTo(1)
    }

    @Test
    fun `a window that was updating is checked again once the app has settled`() {
        settle(connection { check -> if (check == 1) true else quietListen() })

        assertThat(windowChecks.get()).isEqualTo(2)
    }

    @Test
    fun `an app that is not in front is still polled for the whole wait`() {
        // The server answers at once here, so the driver polls every 50ms until its 750ms are up.
        settle(connection { false })

        assertThat(windowChecks.get()).isAtLeast(5)
    }

    private companion object {
        const val APP_ID = "com.example.app"
        // A little over the server's 500ms, as a real call is once the round trip is added.
        const val SERVER_LISTEN_MS = 520L
    }
}
