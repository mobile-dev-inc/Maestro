package ios.xctest

import com.google.common.truth.Truth.assertThat
import ios.IOSDeviceErrors
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller
import java.net.SocketTimeoutException

class XCTestIOSDeviceTest {

    @Test
    fun `transport unreachable from XCTestDriverClient is translated to IOSDeviceErrors Unreachable`() {
        val cause = SocketTimeoutException("simulated read timeout")
        val driverClient = XCTestDriverClient(
            installer = NoopInstaller,
            // The interceptor short-circuits before any socket use, so the port is never reached.
            client = XCTestClient("localhost", 1),
            okHttpClient = throwingOkHttpClient(cause),
        )
        val device = XCTestIOSDevice(
            deviceId = "test-device",
            client = driverClient,
            getInstalledApps = { emptySet() },
        )

        val thrown = assertThrows<IOSDeviceErrors.Unreachable> {
            device.tap(x = 10, y = 20)
        }

        assertThat(thrown.callName).isEqualTo("touch")
        assertThat(thrown.cause).isInstanceOf(maestro.utils.network.XCUITestServerError.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `subsequent calls also throw Unreachable because the underlying latch short-circuits`() {
        val cause = SocketTimeoutException("simulated read timeout")
        val driverClient = XCTestDriverClient(
            installer = NoopInstaller,
            // The interceptor short-circuits before any socket use, so the port is never reached.
            client = XCTestClient("localhost", 1),
            okHttpClient = throwingOkHttpClient(cause),
        )
        val device = XCTestIOSDevice(
            deviceId = "test-device",
            client = driverClient,
            getInstalledApps = { emptySet() },
        )

        assertThrows<IOSDeviceErrors.Unreachable> { device.tap(10, 20) }
        // A second call must also surface as Unreachable — proves the translation arm catches
        // the cached Unreachable that the latch in XCTestDriverClient re-throws on every call.
        assertThrows<IOSDeviceErrors.Unreachable> { device.tap(30, 40) }
    }

    private fun throwingOkHttpClient(cause: Throwable): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { _ -> throw cause }
            .build()

    private object NoopInstaller : XCTestInstaller {
        override fun start(): XCTestClient = error("not used in this test")
        override fun uninstall(): Boolean = error("not used in this test")
        override fun isChannelAlive(): Boolean = error("not used in this test")
        override fun close() {}
    }
}
