package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.android.AndroidDeviceProvider
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.Condition
import org.slf4j.LoggerFactory

/**
 * Routes a standalone assertVisible/assertNotVisible to maestro-device-core's inspect().
 * Transient session per call: connect -> getByText -> inspect -> map -> (session releases).
 *
 * Platform-parameterized: [platform]/[target] select iOS (default) or Android device-core
 * wiring. [appId] is only consumed on iOS (device-core resolves the bundle id from a system
 * property); Android instead publishes [androidForwardPort] as the adb forward port device-core
 * reads from. iOS and Android share this one class rather than splitting into two, since
 * everything downstream of connect() (getByText -> inspect -> verdict mapping) is identical.
 */
class DeviceCoreAssertRouter(
    private val appId: String,
    private val platform: Platform = Platform.IOS,
    private val target: TargetId = TargetId.IOS_SIM,
    private val androidForwardPort: Int = 8791,
    private val providerFactory: () -> DeviceProvider = { IosDeviceProvider() },
) {
    private val logger = LoggerFactory.getLogger(DeviceCoreAssertRouter::class.java)
    fun canRoute(condition: Condition): Boolean = DeviceCoreRouting.route(condition) != null

    companion object {
        /**
         * Builds the router iff MAESTRO_DEVICECORE_ASSERT=1 and the device is iOS or Android;
         * else null (legacy path).
         */
        fun fromEnvOrNull(maestro: Maestro, appId: String?): DeviceCoreAssertRouter? {
            if (System.getenv("MAESTRO_DEVICECORE_ASSERT") != "1") return null
            return when (maestro.cachedDeviceInfo.platform) {
                Platform.IOS -> {
                    val resolvedAppId = appId
                        ?: error("MAESTRO_DEVICECORE_ASSERT=1 requires an appId in the flow config")
                    DeviceCoreAssertRouter(appId = resolvedAppId)
                }
                Platform.ANDROID -> DeviceCoreAssertRouter(
                    appId = appId.orEmpty(),
                    providerFactory = { AndroidDeviceProvider() },
                    platform = Platform.ANDROID,
                    target = TargetId.ANDROID_EMU,
                )
                else -> null
            }
        }
    }

    suspend fun evaluate(condition: Condition, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        val query = DeviceCoreRouting.route(condition)
            ?: throw IllegalArgumentException("evaluate() called on a non-routable condition; guard with canRoute().")

        // device-core resolves the app-under-test / forward port from these system properties.
        // This is process-global mutable state: it assumes a single flow runs per JVM at a time.
        // A parallel/sharded run in one JVM targeting different apps/devices could race on these.
        when (platform) {
            Platform.IOS -> System.setProperty("devicecore.ios.bundleId", appId)
            Platform.ANDROID -> System.setProperty("devicecore.android.forwardPort", androidForwardPort.toString())
            else -> Unit
        }

        val evidence = try {
            val device = providerFactory().connect(TargetSelector(target))
            val base: Locator = device.screen.getByText(query.text, query.match)
            val locator = query.index?.let { base.nth(it) } ?: base
            locator.inspect()
        } catch (e: DeviceCoreUnavailable) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeviceCoreUnavailable("device-core inspect() failed for '${query.text}': ${e.message}")
        }

        val verdict = AssertVisibleVerdict.pass(evidence, query.mode, screenWidthPts, screenHeightPts)
        logger.info(
            "device-core decided assert{}: text='{}' match={} mode={} -> resolution={} boundsSource={} bounds={} screen={}x{}pts verdict={}",
            query.index?.let { "[$it]" } ?: "",
            query.text,
            query.match,
            query.mode,
            evidence.resolution,
            evidence.bounds.source,
            evidence.bounds.value,
            screenWidthPts,
            screenHeightPts,
            verdict,
        )
        return verdict
    }
}
