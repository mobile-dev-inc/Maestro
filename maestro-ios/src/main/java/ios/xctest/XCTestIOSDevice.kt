package ios.xctest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import hierarchy.ViewHierarchy
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.DeviceInfo
import logger.Logger
import maestro.utils.DepthTracker
import okio.Sink
import okio.buffer
import xcuitest.XCTestDriverClient
import xcuitest.api.GetRunningAppIdResponse
import xcuitest.api.IsScreenStaticResponse
import java.io.InputStream
import java.util.UUID

class XCTestIOSDevice(
    override val deviceId: String?,
    private val client: XCTestDriverClient,
    private val logger: Logger,
    private val getInstalledApps: () -> Set<String>,
) : IOSDevice {

    override fun open() {
        client.restartXCTestRunnerService()
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        return runCatching {
            client.deviceInfo().use { response ->
                response.body.use { body ->
                    val bodyString = body?.bytes()?.let { String(it) }
                    if (!response.isSuccessful) {
                        val message = "${response.code} ${response.message} - $bodyString"
                        logger.info("Device info failed: $message")
                        return Err(UnknownFailure(message))
                    }

                    bodyString ?: throw UnknownFailure("Error: response body missing")

                    val deviceInfo = mapper.readValue(bodyString, DeviceInfo::class.java)
                    logger.info("Device info $deviceInfo")

                    deviceInfo
                }
            }
        }
    }

    override fun viewHierarchy(): Result<ViewHierarchy, Throwable> {
        val installedApps = getInstalledApps()
        val result = runCatching {
            val viewHierarchy = client.viewHierarchy(installedApps)
            DepthTracker.trackDepth(viewHierarchy.depth)
            logger.info("Depth received: ${viewHierarchy.depth}")
            viewHierarchy
        }
        return result
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        return runCatching {
            client.tap(
                x = x.toFloat(),
                y = y.toFloat(),
            ).use {}
        }
    }

    override fun longPress(x: Int, y: Int, durationMs: Long): Result<Unit, Throwable> {
        return runCatching {
            client.tap(
                x = x.toFloat(),
                y = y.toFloat(),
                duration = durationMs.toDouble() / 1000
            ).use {}
        }
    }

    override fun pressKey(name: String) {
        client.pressKey(name).use {}
    }

    override fun pressButton(name: String) {
        client.pressButton(name).use {}
    }

    override fun scroll(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double,
    ): Result<Unit, Throwable> {
        return runCatching {
            client.swipe(
                appId = activeAppId() ?: error("Unable to obtain active app id"),
                startX = xStart,
                startY = yStart,
                endX = xEnd,
                endY = yEnd,
                duration = duration
            ).use {}
        }
    }

    fun scrollV2(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double,
    ): Result<Unit, Throwable> {
        return runCatching {
            client.swipeV2(
                installedApps = getInstalledApps(),
                startX = xStart,
                startY = yStart,
                endX = xEnd,
                endY = yEnd,
                duration = duration,
            ).use {}
        }
    }

    override fun input(text: String): Result<Unit, Throwable> {
        return runCatching {
            val appIds = getInstalledApps()
            client.inputText(
                text = text,
                appIds = appIds,
            ).use {
                if (!it.isSuccessful) {
                    if (it.code == 404) {
                        throw InputFieldNotFound()
                    }
                }
            }
        }
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun clearAppState(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun launch(
        id: String,
        launchArguments: Map<String, Any>,
        maestroSessionId: UUID?,
    ): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean): Result<Unit, Throwable> {
        return runCatching {
            client.screenshot(compressed).use { response ->
                response.body.use { body ->
                    body ?: throw UnknownFailure("Error - body for snapshot request not available")

                    if (response.isSuccessful) {
                        out.buffer().use {
                            it.write(body.bytes())
                        }
                    } else {
                        val errorResponse = String(body.bytes()).trim()
                        throw UnknownFailure(errorResponse)
                    }
                }
            }
        }
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        error("Not supported")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun isShutdown(): Boolean {
        return !client.isChannelAlive()
    }

    override fun close() {
        client.close()
    }

    override fun isScreenStatic(): Result<Boolean, Throwable> {
        return runCatching {
            client.isScreenStatic().use { response ->
                response.body.use { body ->
                    body ?: throw UnknownFailure("Error - body for isScreenStatic request not available")

                    if (response.isSuccessful) {
                        val responseBody: IsScreenStaticResponse = mapper.readValue(
                            String(body.bytes()),
                            IsScreenStaticResponse::class.java
                        )
                        val isScreenStatic = responseBody.isScreenStatic
                        logger.info("Screen diff request finished with isScreenStatic = $isScreenStatic")
                        isScreenStatic
                    } else {
                        val errorResponse = String(body.bytes()).trim()
                        logger.info("Screen diff request failed with error = $errorResponse")
                        throw UnknownFailure(errorResponse)
                    }
                }
            }
        }
    }

    override fun setPermissions(id: String, permissions: Map<String, String>): Result<Unit, Throwable> {
        return runCatching {
            val mutable = permissions.toMutableMap()
            if (mutable.containsKey("all")) {
                val value = mutable.remove("all")
                allPermissions.forEach {
                    when (value) {
                        "allow" -> mutable.putIfAbsent(it, "allow")
                        "deny" -> mutable.putIfAbsent(it, "deny")
                        "unset" -> mutable.putIfAbsent(it, "unset")
                        else -> throw IllegalArgumentException("Permission 'all' can be set to 'allow', 'deny' or 'unset', not '$value'")
                    }
                }
            }

            client.setPermissions(mutable)
        }
    }

    override fun eraseText(charactersToErase: Int) {
        val appIds = getInstalledApps()
        client.eraseText(charactersToErase, appIds).use {}
    }

    private fun activeAppId(): String? {
        val appIds = getInstalledApps()
        logger.info("installed apps: $appIds")

        return client.runningAppId(appIds).use { response ->
            response.body.use { body ->
                val runningAppBundleId = if (response.isSuccessful) {
                    body?.let {
                        val responseBody: GetRunningAppIdResponse = mapper.readValue(
                            String(it.bytes()),
                            GetRunningAppIdResponse::class.java
                        )
                        val runningAppId = responseBody.runningAppBundleId
                        logger.info("Running app id response received $runningAppId")
                        runningAppId
                    }
                } else {
                    val bodyString = response.body?.let { String(it.bytes()) } ?: ""
                    val code = response.code
                    logger.info("request to resolve running app id failed with exception - Code: $code Body: $bodyString")

                    return null
                }

                logger.info("found running app id $runningAppBundleId")

                runningAppBundleId
            }
        }
    }

    class IllegalArgumentSnapshotFailure : Throwable("Failed to capture view hierarchy due to kAXErrorIllegalArgument")
    class InputFieldNotFound : Throwable("Unable to find focused input field")
    class UnknownFailure(errorResponse: String) : Throwable(errorResponse)

    companion object {

        private const val VIEW_HIERARCHY_SNAPSHOT_ERROR_CODE = "illegal-argument-snapshot-failure"

        private val mapper by lazy { jacksonObjectMapper() }

        private val allPermissions = listOf(
            "notifications"
        )
    }

}
