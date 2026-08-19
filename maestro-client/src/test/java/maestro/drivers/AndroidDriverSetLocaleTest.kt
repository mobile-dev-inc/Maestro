package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.android.AndroidDeviceConnection
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [AndroidDriver.setDeviceLocale] (MA-4105).
 *
 * We never touch a real device: a fake [AndroidDeviceConnection] stands in for adb, scripted to
 * return chosen `getprop` values. We then check which commands the driver ran — in particular
 * whether it fired the (detached) locale broadcast at all, and how it reported the outcome.
 */
class AndroidDriverSetLocaleTest {

    // A fake adb reply. The driver's shell() calls AdbShellResponse.orThrow(), which reads exitCode
    // (must be 0 for success) and returns output — so we stub exactly those two.
    private fun reply(text: String): AdbShellResponse = mockk {
        every { exitCode } returns 0
        every { output } returns text
    }

    // A driver wired to [connection] with a tiny, zero-wait retry budget so tests finish instantly.
    private fun driver(connection: AndroidDeviceConnection) = AndroidDriver(
        connection = connection,
        localeRetry = LocaleRetryPolicy(maxAttempts = 2, verifyPolls = 2, pollIntervalMs = 0L, graceVerifyPolls = 4),
    )

    @Test
    fun `skips the broadcast when the fresh golden already matches (en_US)`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        // Fresh golden: persist.sys.locale is EMPTY, so the effective locale comes from ro.product.locale.
        every { connection.shell("getprop persist.sys.locale") } returns reply("")
        every { connection.shell("getprop ro.product.locale") } returns reply("en-US")

        val result = driver(connection).setDeviceLocale(country = "US", language = "en")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_SUCCESS)
        // Fix #1: the en_US majority fires ZERO broadcasts, so it can't trigger the storm.
        verify(exactly = 0) { connection.execDetached(any()) }
    }

    @Test
    fun `skips the broadcast when persist_sys_locale already matches`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US")

        val result = driver(connection).setDeviceLocale(country = "US", language = "en")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_SUCCESS)
        verify(exactly = 0) { connection.execDetached(any()) }
        // ro.product.locale isn't even consulted once persist.sys.locale answers.
        verify(exactly = 0) { connection.shell("getprop ro.product.locale") }
    }

    @Test
    fun `fires a DETACHED broadcast and verifies the change via getprop`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        // First read = current locale (en-US); after the broadcast, getprop reports the new locale.
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") andThen reply("fr-FR")

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_SUCCESS)
        // Fix #2: fired once, detached (non-blocking), with the correct locale extras.
        verify(exactly = 1) {
            connection.execDetached(match { it.contains("--es lang fr") && it.contains("--es country FR") })
        }
    }

    @Test
    fun `reports failure when the locale never applies, even through the grace window`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED)
        // Bounded retry (maxAttempts = 2) + a grace poll — a fast, classified failure, never a multi-minute block.
        verify(exactly = 2) { connection.execDetached(any()) }
    }

    @Test
    fun `succeeds when the locale flips late, during the grace window`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        // getprop lags: it reads en-US through the initial check + both retry attempts
        // (1 + maxAttempts*verifyPolls = 5 reads), then the prop finally flips during the grace poll.
        val reads = AtomicInteger(0)
        every { connection.shell("getprop persist.sys.locale") } answers {
            if (reads.incrementAndGet() <= 5) reply("en-US") else reply("fr-FR")
        }

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_SUCCESS)
        // The grace window catches the late flip without re-firing the broadcast.
        verify(exactly = 2) { connection.execDetached(any()) }
    }
}
