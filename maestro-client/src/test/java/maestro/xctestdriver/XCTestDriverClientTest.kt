package maestro.xctestdriver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.ios.MockXCTestInstaller
import maestro.utils.network.XCUITestServerError
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
import java.net.InetAddress

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
