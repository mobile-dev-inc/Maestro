package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
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

    suspend fun evaluate(condition: Condition, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        val query = DeviceCoreRouting.route(condition)
            ?: throw IllegalArgumentException("evaluate() called on a non-routable condition; guard with canRoute().")

        // device-core resolves the app-under-test from this system property (resolveBundleId()).
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
