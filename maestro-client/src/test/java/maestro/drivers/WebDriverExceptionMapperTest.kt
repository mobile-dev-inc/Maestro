package maestro.drivers

import com.google.common.truth.Truth.assertThat
import maestro.DeviceUnreachableException
import maestro.MaestroException
import org.junit.jupiter.api.Test
import org.openqa.selenium.NoSuchElementException
import org.openqa.selenium.NoSuchSessionException
import org.openqa.selenium.SessionNotCreatedException
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriverException

class WebDriverExceptionMapperTest {

    @Test
    fun `SessionNotCreatedException maps to DeviceUnreachableException`() {
        val e = SessionNotCreatedException("chrome failed to start")

        val result = WebDriverExceptionMapper.toMaestroException(e, "open")

        assertThat(result).isInstanceOf(DeviceUnreachableException::class.java)
        assertThat((result as DeviceUnreachableException).callName).isEqualTo("open")
        assertThat(result.cause).isSameInstanceAs(e)
    }

    @Test
    fun `NoSuchSessionException maps to DeviceUnreachableException`() {
        val e = NoSuchSessionException("session lost")

        val result = WebDriverExceptionMapper.toMaestroException(e, "tap")

        assertThat(result).isInstanceOf(DeviceUnreachableException::class.java)
    }

    @Test
    fun `chrome-not-reachable message maps to DeviceUnreachableException`() {
        val e = WebDriverException("chrome not reachable")

        val result = WebDriverExceptionMapper.toMaestroException(e, "tap")

        assertThat(result).isInstanceOf(DeviceUnreachableException::class.java)
    }

    @Test
    fun `net ERR message maps to DeviceUnreachableException`() {
        val e = WebDriverException("net::ERR_CONNECTION_REFUSED")

        val result = WebDriverExceptionMapper.toMaestroException(e, "openLink")

        assertThat(result).isInstanceOf(DeviceUnreachableException::class.java)
    }

    @Test
    fun `TimeoutException maps to DriverTimeout`() {
        val e = TimeoutException("timed out waiting")

        val result = WebDriverExceptionMapper.toMaestroException(e, "contentDescriptor")

        assertThat(result).isInstanceOf(MaestroException.DriverTimeout::class.java)
        assertThat(result.cause).isSameInstanceAs(e)
    }

    @Test
    fun `StaleElementReferenceException maps to AssertionFailure`() {
        val e = StaleElementReferenceException("stale")

        val result = WebDriverExceptionMapper.toMaestroException(e, "tap")

        assertThat(result).isInstanceOf(MaestroException.AssertionFailure::class.java)
        assertThat(result.cause).isSameInstanceAs(e)
    }

    @Test
    fun `NoSuchElementException maps to AssertionFailure`() {
        val e = NoSuchElementException("no such element")

        val result = WebDriverExceptionMapper.toMaestroException(e, "inputText")

        assertThat(result).isInstanceOf(MaestroException.AssertionFailure::class.java)
    }

    @Test
    fun `generic WebDriverException maps to AssertionFailure with callName in message`() {
        val e = WebDriverException("something odd happened")

        val result = WebDriverExceptionMapper.toMaestroException(e, "swipe")

        assertThat(result).isInstanceOf(MaestroException.AssertionFailure::class.java)
        assertThat(result.message).contains("swipe")
    }
}
