package maestro.drivers

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceUnreachableException
import maestro.Driver
import maestro.MaestroException
import maestro.Point
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.openqa.selenium.NoSuchElementException
import org.openqa.selenium.NoSuchSessionException
import org.openqa.selenium.SessionNotCreatedException
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebDriverException

class SeleniumExceptionTranslatorTest {

    private val delegate: Driver = mockk(relaxed = true)
    private val wrapped: Driver = SeleniumExceptionTranslator.wrap(delegate)

    @Test
    fun `StaleElementReferenceException is translated to MaestroException`() {
        every { delegate.tap(any()) } throws StaleElementReferenceException("stale")

        assertThrows<MaestroException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `NoSuchElementException is translated to MaestroException`() {
        every { delegate.tap(any()) } throws NoSuchElementException("no such")

        assertThrows<MaestroException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `SessionNotCreatedException is translated to DeviceUnreachableException`() {
        every { delegate.tap(any()) } throws SessionNotCreatedException("chrome not started")

        assertThrows<DeviceUnreachableException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `NoSuchSessionException is translated to DeviceUnreachableException`() {
        every { delegate.tap(any()) } throws NoSuchSessionException("session lost")

        assertThrows<DeviceUnreachableException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `WebDriverException with chrome-not-reachable message is translated to DeviceUnreachableException`() {
        every { delegate.tap(any()) } throws WebDriverException("chrome not reachable")

        assertThrows<DeviceUnreachableException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `WebDriverException with network error message is translated to DeviceUnreachableException`() {
        every { delegate.tap(any()) } throws WebDriverException("net::ERR_CONNECTION_REFUSED")

        assertThrows<DeviceUnreachableException> { wrapped.tap(Point(0, 0)) }
    }

    @Test
    fun `non-Selenium exception is propagated unchanged`() {
        val original = IllegalStateException("something else")
        every { delegate.tap(any()) } throws original

        val thrown = assertThrows<IllegalStateException> { wrapped.tap(Point(0, 0)) }
        assertThat(thrown).isSameInstanceAs(original)
    }

    @Test
    fun `MaestroException from delegate is propagated unchanged`() {
        val original = MaestroException.UnableToClearState("clear failed")
        every { delegate.tap(any()) } throws original

        val thrown = assertThrows<MaestroException.UnableToClearState> { wrapped.tap(Point(0, 0)) }
        assertThat(thrown).isSameInstanceAs(original)
    }

    @Test
    fun `successful call returns the delegate's result`() {
        every { delegate.isKeyboardVisible() } returns true

        assertThat(wrapped.isKeyboardVisible()).isTrue()
    }
}
