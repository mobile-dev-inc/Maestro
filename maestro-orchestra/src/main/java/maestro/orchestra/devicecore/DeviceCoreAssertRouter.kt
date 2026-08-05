package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.Condition
import org.slf4j.LoggerFactory

/**
 * Routes a standalone assertVisible/assertNotVisible to maestro-device-core's inspect().
 * Transient session per call: connect -> getByText -> inspect -> map -> (session releases).
 */
class DeviceCoreAssertRouter(
    private val appId: String,
    private val providerFactory: () -> DeviceProvider = { IosDeviceProvider() },
) {
    private val logger = LoggerFactory.getLogger(DeviceCoreAssertRouter::class.java)
    fun canRoute(condition: Condition): Boolean = DeviceCoreRouting.route(condition) != null

    companion object {
        /** Builds the router iff MAESTRO_DEVICECORE_ASSERT=1 and the device is iOS; else null (legacy path). */
        fun fromEnvOrNull(maestro: Maestro, appId: String?): DeviceCoreAssertRouter? {
            if (System.getenv("MAESTRO_DEVICECORE_ASSERT") != "1") return null
            if (maestro.cachedDeviceInfo.platform != Platform.IOS) return null
            val resolvedAppId = appId ?: error("MAESTRO_DEVICECORE_ASSERT=1 requires an appId in the flow config")
            return DeviceCoreAssertRouter(appId = resolvedAppId)
        }
    }

    suspend fun evaluate(condition: Condition, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        val query = DeviceCoreRouting.route(condition)
            ?: throw IllegalArgumentException("evaluate() called on a non-routable condition; guard with canRoute().")

        // device-core resolves the app-under-test from this system property (resolveBundleId()).
        // This is process-global mutable state: it assumes a single flow runs per JVM at a time.
        // A parallel/sharded run in one JVM targeting different apps could race on this property.
        System.setProperty("devicecore.ios.bundleId", appId)

        val evidence = try {
            val device = providerFactory().connect(TargetSelector(TargetId.IOS_SIM))
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
