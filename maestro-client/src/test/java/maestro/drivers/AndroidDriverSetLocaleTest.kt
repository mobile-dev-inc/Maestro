package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.android.AndroidDeviceConnection
import org.junit.jupiter.api.Test

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
    private fun driver(
        connection: AndroidDeviceConnection,
        blockingBroadcastTimeoutMs: Long = 15_000L,
    ) = AndroidDriver(
        connection = connection,
        localeRetry = LocaleRetryPolicy(
            maxAttempts = 2,
            verifyPolls = 2,
            pollIntervalMs = 0L,
            blockingBroadcastTimeoutMs = blockingBroadcastTimeoutMs,
        ),
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
    fun `succeeds when getprop never confirms but the blocking broadcast returns success`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR
        every { connection.shell(match { it.startsWith("am broadcast") }) } returns
            reply("Broadcasting: Intent { act=dev.mobile.maestro.locale }\nBroadcast completed: result=0, data=\"fr_FR\"")

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_SUCCESS)
    }

    @Test
    fun `reports the receiver's failure when the blocking broadcast returns a non-zero result`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR
        every { connection.shell(match { it.startsWith("am broadcast") }) } returns
            reply("Broadcast completed: result=2, data=\"Failed to set locale fr_FR\"")

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED)
        // Bounded detached retries first, then exactly one blocking broadcast for the authoritative result.
        verify(exactly = 2) { connection.execDetached(any()) }
        verify(exactly = 1) { connection.shell(match { it.startsWith("am broadcast") }) }
    }

    @Test
    fun `maps the receiver's validation-failed code (3) to a validation failure`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR
        every { connection.shell(match { it.startsWith("am broadcast") }) } returns
            reply("Broadcast completed: result=3, data=\"Failed to set locale fr_FR: boom\"")

        val result = driver(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_LOCALE_VALIDATION_FAILED)
    }

    @Test
    fun `falls back to a classified failure when the blocking broadcast does not return in time`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR
        // A busy app / ANR: the ordered broadcast never returns within the bound.
        every { connection.shell(match { it.startsWith("am broadcast") }) } answers {
            Thread.sleep(1_000)
            reply("Broadcast completed: result=0, data=\"fr_FR\"")
        }

        val result = driver(connection, blockingBroadcastTimeoutMs = 50L)
            .setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDriver.SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED)
    }
}
