package maestro.xctestdriver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.debuglog.IOSDriverLogger
import maestro.ios.MockXCTestInstaller
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.api.NetworkException
import java.net.InetAddress

class XCTestDriverClientTest {

    @Test
    fun `it should return correct message in case of TimeoutException with 3 retries`() {
        // given
        val mockWebServer = MockWebServer()
        // do not enqueue any response
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator(
            installationRetryCount = 0,
            shouldInstall = false
        )
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            IOSDriverLogger(XCTestDriverClient::class.java),
            XCTestClient("localhost", 22087)
        )
        val response = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        assertThat(response.message).contains(
            "A timeout occurred while waiting for a response from the XCUITest server."
        )
        mockXCTestInstaller.assertInstallationRetries(5)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return the 4xx response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mockResponse = MockResponse().apply {
            setResponseCode(401)
            setBody("This is a bad request")
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            IOSDriverLogger(XCTestDriverClient::class.java),
            XCTestClient("localhost", 22087)
        )
        val response = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        val body = response.body?.string()
        val code = response.code
        assertThat(code).isEqualTo(401)
        assertThat(body).isNotNull()
        assertThat(body).isEqualTo("This is a bad request")
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return the 200 response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        val mockResponse = MockResponse().apply {
            setResponseCode(200)
            setBody("This is a valid response")
        }
        mockWebServer.enqueue(mockResponse)
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")

        // when
        val simulator = MockXCTestInstaller.Simulator()
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            IOSDriverLogger(XCTestDriverClient::class.java),
            XCTestClient("localhost", 22087)
        )
        val response = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        val body = response.body?.string()
        val code = response.code
        assertThat(code).isEqualTo(200)
        assertThat(body).isNotNull()
        assertThat(body).isEqualTo("This is a valid response")
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return correct message in case of UnknownHostException without retries`() {
        // given
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("http://nonexistent-domain.local")
        val mapper = jacksonObjectMapper()

        // when
        val simulator = MockXCTestInstaller.Simulator(
            installationRetryCount = 0,
            shouldInstall = false
        )
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            IOSDriverLogger(XCTestDriverClient::class.java),
            XCTestClient("localhost", 22087)
        )
        val response = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        val networkErrorModel = response.body?.use {
            mapper.readValue(it.bytes(), NetworkException.NetworkErrorModel::class.java)
        } ?: throw IllegalStateException("No NetworkError model found for response body")
        assertThat(response.code).isEqualTo(502)
        assertThat(networkErrorModel.userFriendlyMessage).contains(
            "The host for the XCUITest server is unknown."
        )
        mockXCTestInstaller.assertInstallationRetries(0)
        mockWebServer.shutdown()
    }

    @Test
    fun `it should return correct message in case of ConnectExceptions with 3 retries`() {
        // given
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY)
        )
        mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
        val httpUrl = mockWebServer.url("/deviceInfo")
        mockWebServer.shutdown()
        val mapper = jacksonObjectMapper()

        // when
        val simulator = MockXCTestInstaller.Simulator(
            installationRetryCount = 0,
            shouldInstall = false
        )
        val mockXCTestInstaller = MockXCTestInstaller(simulator)
        val xcTestDriverClient = XCTestDriverClient(
            mockXCTestInstaller,
            IOSDriverLogger(XCTestDriverClient::class.java),
            XCTestClient("localhost", 22087)
        )
        val response = xcTestDriverClient.deviceInfo(httpUrl)

        // then
        assertThat(response.code).isEqualTo(502)
        val networkErrorModel = response.body?.use {
            mapper.readValue(it.bytes(), NetworkException.NetworkErrorModel::class.java)
        } ?: throw IllegalStateException("No NetworkError model found for response body")
        assertThat(networkErrorModel.userFriendlyMessage).contains(
            "Unable to establish a connection to the XCUITest server."
        )
        mockXCTestInstaller.assertInstallationRetries(5)
    }
}