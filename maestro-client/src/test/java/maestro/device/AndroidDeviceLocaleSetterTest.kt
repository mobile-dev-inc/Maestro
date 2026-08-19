package maestro.device

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.android.AndroidDeviceConnection
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AndroidDeviceLocaleSetter.setDeviceLocale] (MA-4105).
 *
 * We never touch a real device: a fake [AndroidDeviceConnection] stands in for adb, scripted to
 * return chosen `getprop` values. We then check which commands the setter ran — in particular
 * whether it fired the (detached) locale broadcast at all, and how it reported the outcome.
 */
class AndroidDeviceLocaleSetterTest {

    // A fake adb reply. setter's shell() calls AdbShellResponse.orThrow(), which reads exitCode
    // (must be 0 for success) and returns output — so we stub exactly those two.
    private fun reply(text: String): AdbShellResponse = mockk {
        every { exitCode } returns 0
        every { output } returns text
    }

    // A setter wired to [connection] with a tiny, zero-wait retry budget so tests finish instantly.
    private fun setter(connection: AndroidDeviceConnection) = AndroidDeviceLocaleSetter(
        connection = connection,
        localeRetry = LocaleRetryPolicy(maxAttempts = 2, verifyPolls = 2, pollIntervalMs = 0L),
    )

    @Test
    fun `skips the broadcast when the fresh golden already matches (en_US)`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        // Fresh golden: persist.sys.locale is EMPTY, so the effective locale comes from ro.product.locale.
        every { connection.shell("getprop persist.sys.locale") } returns reply("")
        every { connection.shell("getprop ro.product.locale") } returns reply("en-US")

        val result = setter(connection).setDeviceLocale(country = "US", language = "en")

        assertThat(result).isEqualTo(AndroidDeviceLocaleSetter.SET_LOCALE_RESULT_SUCCESS)
        // Fix #1: the en_US majority fires ZERO broadcasts, so it can't trigger the storm.
        verify(exactly = 0) { connection.execDetached(any()) }
    }

    @Test
    fun `skips the broadcast when persist_sys_locale already matches`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US")

        val result = setter(connection).setDeviceLocale(country = "US", language = "en")

        assertThat(result).isEqualTo(AndroidDeviceLocaleSetter.SET_LOCALE_RESULT_SUCCESS)
        verify(exactly = 0) { connection.execDetached(any()) }
        // ro.product.locale isn't even consulted once persist.sys.locale answers.
        verify(exactly = 0) { connection.shell("getprop ro.product.locale") }
    }

    @Test
    fun `fires a DETACHED broadcast and verifies the change via getprop`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        // First read = current locale (en-US); after the broadcast, getprop reports the new locale.
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") andThen reply("fr-FR")

        val result = setter(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDeviceLocaleSetter.SET_LOCALE_RESULT_SUCCESS)
        // Fix #2: fired once, detached (non-blocking), with the correct locale extras.
        verify(exactly = 1) {
            connection.execDetached(match { it.contains("--es lang fr") && it.contains("--es country FR") })
        }
    }

    @Test
    fun `retries then reports failure when the locale never applies`() {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("getprop persist.sys.locale") } returns reply("en-US") // never becomes fr-FR

        val result = setter(connection).setDeviceLocale(country = "FR", language = "fr")

        assertThat(result).isEqualTo(AndroidDeviceLocaleSetter.SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED)
        // Bounded retry (maxAttempts = 2) — a fast, classified failure, never a multi-minute block.
        verify(exactly = 2) { connection.execDetached(any()) }
    }
}
