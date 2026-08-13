package maestro.device

import maestro.android.AndroidDeviceConnection
import maestro.android.AndroidOperationFailedException
import maestro.android.orThrow
import maestro.android.orThrowOnFailure
import okio.buffer
import okio.sink
import okio.source
import org.slf4j.LoggerFactory
import java.io.File

/**
 * How hard [AndroidDeviceLocaleSetter.setDeviceLocale] retries the detached locale broadcast while
 * confirming the change via `getprop`. Injectable so tests can shrink the poll budget.
 */
data class LocaleRetryPolicy(
    val maxAttempts: Int = 3,
    val verifyPolls: Int = 32,
    val pollIntervalMs: Long = 250L,
)

/**
 * Emulator locale provisioning: installs the Maestro driver app, sets the device locale by
 * broadcasting to the driver app's `LocaleSettingReceiver`, and uninstalls it.
 *
 * Carved out of the legacy `maestro.drivers.AndroidDriver` (which implemented the now-deleted
 * `maestro.Driver` facade and the matching engine) during the device-core converge — the driver
 * stack was deleted, but [maestro.device.DeviceService.startDevice] still boots an emulator and sets
 * its locale (used by `maestro start-device` and the device-core `selectDevice` boot path), so the
 * few provisioning methods it needed live on here, free of the removed facade.
 */
class AndroidDeviceLocaleSetter(
    private val connection: AndroidDeviceConnection,
    private val reinstallDriver: Boolean = true,
    private val localeRetry: LocaleRetryPolicy = LocaleRetryPolicy(),
) {

    private val logger = LoggerFactory.getLogger(AndroidDeviceLocaleSetter::class.java)

    fun installMaestroDriverApp() {
        if (reinstallDriver) {
            uninstallMaestroDriverApp()
        } else if (isPackageInstalled("dev.mobile.maestro")) {
            return
        }

        val maestroAppApk = File.createTempFile("maestro-app", ".apk")

        AndroidDeviceLocaleSetter::class.java.getResourceAsStream("/maestro-app.apk")?.let {
            val bufferedSink = maestroAppApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }

        install(maestroAppApk)
        if (!isPackageInstalled("dev.mobile.maestro")) {
            throw IllegalStateException("dev.mobile.maestro was not installed")
        }
        maestroAppApk.delete()
    }

    fun uninstallMaestroDriverApp() {
        bestEffortUninstall("dev.mobile.maestro")
    }

    fun setDeviceLocale(country: String, language: String): Int {
        val target = "$language-$country"

        if (currentEffectiveLocaleTag().equals(target, ignoreCase = true)) {
            logger.info("Device locale already $target; skipping locale broadcast")
            return SET_LOCALE_RESULT_SUCCESS
        }

        shell("pm grant dev.mobile.maestro android.permission.CHANGE_CONFIGURATION")

        var applied = false
        var attempt = 0
        while (!applied && attempt < localeRetry.maxAttempts) {
            attempt++
            // Detached fire: do NOT wait on am's ordered receiver result.
            connection.execDetached(
                "nohup am broadcast -a dev.mobile.maestro.locale " +
                    "-n dev.mobile.maestro/.receivers.LocaleSettingReceiver " +
                    "--es lang $language --es country $country >/dev/null 2>&1 &"
            )
            applied = awaitLocaleApplied(target)
        }

        return if (applied) {
            logger.info("Device locale is $target")
            SET_LOCALE_RESULT_SUCCESS
        } else {
            logger.warn("Device locale did not reach $target after ${localeRetry.maxAttempts} attempts")
            SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED
        }
    }

    private fun currentEffectiveLocaleTag(): String {
        val persisted = shell("getprop persist.sys.locale").trim()
        return if (persisted.isNotEmpty()) persisted else shell("getprop ro.product.locale").trim()
    }

    private fun awaitLocaleApplied(target: String): Boolean {
        repeat(localeRetry.verifyPolls) {
            if (shell("getprop persist.sys.locale").trim().equals(target, ignoreCase = true)) return true
            if (localeRetry.pollIntervalMs > 0) Thread.sleep(localeRetry.pollIntervalMs)
        }
        return false
    }

    /**
     * Best-effort uninstall: swallow both a rejected uninstall (AndroidOperationFailedException) and a
     * transport blip — a subsequent install re-surfaces a real death.
     */
    private fun bestEffortUninstall(packageName: String) {
        try {
            if (isPackageInstalled(packageName)) {
                uninstall(packageName)
            }
        } catch (e: AndroidOperationFailedException) {
            logger.warn("Failed to check or uninstall $packageName: ${e.message}")
            try {
                uninstall(packageName)
            } catch (e2: AndroidOperationFailedException) {
                logger.warn("Failed to uninstall $packageName: ${e2.message}")
            }
        }
    }

    private fun install(apkFile: File) {
        connection.install(apkFile).orThrowOnFailure()
    }

    private fun uninstall(packageName: String) {
        connection.uninstall(packageName).orThrowOnFailure()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        try {
            val output: String = shell("pm list packages --user 0 $packageName")
            return output.split("\n".toRegex())
                .map { line -> line.split(":".toRegex()) }
                .filter { parts -> parts.size == 2 }
                .map { parts -> parts[1] }
                .any { linePackageName -> linePackageName == packageName }
        } catch (e: AndroidOperationFailedException) {
            logger.warn("Failed to check if package $packageName is installed: ${e.message}")
            throw e
        }
    }

    private fun shell(command: String): String = connection.shell(command).orThrow()

    companion object {
        const val SET_LOCALE_RESULT_SUCCESS = 0
        const val SET_LOCALE_RESULT_LOCALE_NOT_VALID = 1
        const val SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED = 2
    }
}
