package maestro.xctestdriver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.ios.MockXCTestInstaller
import maestro.utils.network.XCUITestServerError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.api.Error
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class XCTestDriverClientTest {

    @Test
    fun `it should return the 4xx response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val error = Error(errorMessage = "This is bad request, failure", errorCode = "bad-request")
        val mockResponse = MockResponse().apply {
            setResponseCode(401)
            setBody(mapper.writeValueAsString(error))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )


        // then
        assertThrows<XCUITestServerError.BadRequest> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return the 200 response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val expectedDeviceInfo = DeviceInfo(1123, 5000, 1223, 1123)
        val mockResponse = MockResponse().apply {
            setResponseCode(200)
            setBody(mapper.writeValueAsString(expectedDeviceInfo))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName("localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )
        val actualDeviceInfo = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        assertThat(actualDeviceInfo).isEqualTo(expectedDeviceInfo)
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @ParameterizedTest
    @MethodSource("provideAppCrashMessage")
    fun `it should throw app crash exception correctly`(errorMessage: String) {
        // given
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val expectedDeviceInfo = Error(errorMessage = errorMessage, errorCode = "internal")
        val mockResponse = MockResponse().apply {
            setResponseCode(500)
            setBody(mapper.writeValueAsString(expectedDeviceInfo))
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            XCTestClient("localhost", 22087)
        )


        // then
        assertThrows<XCUITestServerError.AppCrash> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `transport timeout is reported as Unreachable wrapping the underlying cause`() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE })
        mockWebServer.start(InetAddress.getByName("localhost"), 0)
        val port = mockWebServer.port
        val httpUrl = mockWebServer.url("/deviceInfo")

        val xcTestDriverClient = XCTestDriverClient(
            installer = MockXCTestInstaller(MockXCTestInstaller.Simulator(), port = port),
            client = XCTestClient("localhost", port),
            okHttpClient = fastTimeoutOkHttpClient(),
        )

        val thrown = assertThrows<XCUITestServerError.Unreachable> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        assertThat(thrown.callName).isEqualTo("deviceInfo")
        // SocketTimeoutException is a subclass of InterruptedIOException; OkHttp's callTimeout
        // can also surface as raw InterruptedIOException, so accept either.
        assertThat(thrown.cause).isInstanceOf(InterruptedIOException::class.java)

        mockWebServer.shutdown()
    }

    @Test
    fun `subsequent calls short-circuit without issuing another HTTP request`() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE })
        // intentionally no second response: a non-short-circuited call would dispatch an HTTP
        // request and either time out again (slow) or get an EOF once the queue is exhausted.
        mockWebServer.start(InetAddress.getByName("localhost"), 0)
        val port = mockWebServer.port
        val httpUrl = mockWebServer.url("/deviceInfo")

        val xcTestDriverClient = XCTestDriverClient(
            installer = MockXCTestInstaller(MockXCTestInstaller.Simulator(), port = port),
            client = XCTestClient("localhost", port),
            okHttpClient = fastTimeoutOkHttpClient(),
        )

        val first = assertThrows<XCUITestServerError.Unreachable> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }
        val second = assertThrows<XCUITestServerError.Unreachable> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }

        assertThat(second).isSameInstanceAs(first)
        assertThat(mockWebServer.requestCount).isEqualTo(1)

        mockWebServer.shutdown()
    }

    @Test
    fun `restartXCTestRunner clears the latch and the next call hits the runner again`() {
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val expectedDeviceInfo = DeviceInfo(1, 2, 3, 4)
        mockWebServer.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE })
        mockWebServer.enqueue(MockResponse().apply {
            setResponseCode(200)
            setBody(mapper.writeValueAsString(expectedDeviceInfo))
        })
        mockWebServer.start(InetAddress.getByName("localhost"), 0)
        val port = mockWebServer.port
        val httpUrl = mockWebServer.url("/deviceInfo")

        // Pin the post-restart XCTestClient to the same mock-server port so the second call lands here.
        val xcTestDriverClient = XCTestDriverClient(
            installer = MockXCTestInstaller(MockXCTestInstaller.Simulator(), port = port),
            client = XCTestClient("localhost", port),
            okHttpClient = fastTimeoutOkHttpClient(),
            reinstallDriver = false,
        )

        assertThrows<XCUITestServerError.Unreachable> {
            xcTestDriverClient.deviceInfo(httpUrl)
        }

        xcTestDriverClient.restartXCTestRunner()

        val actual = xcTestDriverClient.deviceInfo(httpUrl)
        assertThat(actual).isEqualTo(expectedDeviceInfo)
        assertThat(mockWebServer.requestCount).isEqualTo(2)

        mockWebServer.shutdown()
    }

    @Test
    fun `non-transport failures do not trip the latch`() {
        val mockWebServer = MockWebServer()
        val mapper = jacksonObjectMapper()
        val error = Error(errorMessage = "bad request", errorCode = "bad-request")
        repeat(2) {
            mockWebServer.enqueue(MockResponse().apply {
                setResponseCode(401)
                setBody(mapper.writeValueAsString(error))
            })
        }
        mockWebServer.start(InetAddress.getByName("localhost"), 0)
        val port = mockWebServer.port
        val httpUrl = mockWebServer.url("/deviceInfo")

        val xcTestDriverClient = XCTestDriverClient(
            installer = MockXCTestInstaller(MockXCTestInstaller.Simulator(), port = port),
            client = XCTestClient("localhost", port),
            okHttpClient = fastTimeoutOkHttpClient(),
        )

        assertThrows<XCUITestServerError.BadRequest> { xcTestDriverClient.deviceInfo(httpUrl) }
        assertThrows<XCUITestServerError.BadRequest> { xcTestDriverClient.deviceInfo(httpUrl) }

        // Both calls must have reached the server: a tripped latch would have short-circuited the second.
        assertThat(mockWebServer.requestCount).isEqualTo(2)

        mockWebServer.shutdown()
    }

    // Mirrors production: the 200s readTimeout is the bound that fires first in the observed
    // incident, surfacing as a SocketTimeoutException. We don't set callTimeout because OkHttp
    // wraps the cause in a plain InterruptedIOException, which is a different kind of signal.
    private fun fastTimeoutOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    companion object {

        @JvmStatic
        fun provideAppCrashMessage(): Array<String> {
            return arrayOf(
                "Application com.app.id is not running",
                "Lost connection to the application (pid 19985).",
                "Error getting main window kAXErrorCannotComplete",
                "Error getting main window Unknown kAXError value -25218"
            )
        }
    }
}
