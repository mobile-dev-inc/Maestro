package ios.xctest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import hierarchy.Error
import hierarchy.XCUIElement
import idb.Idb
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.DeviceInfo
import maestro.logger.Logger
import okio.Sink
import xcuitest.XCTestDriverClient
import xcuitest.api.GetRunningAppIdResponse
import xcuitest.installer.XCTestInstaller
import java.io.File
import java.io.InputStream

class XCTestIOSDevice(
    override val deviceId: String?,
    private val client: XCTestDriverClient,
    private val installer: XCTestInstaller,
    private val logger: Logger,
    private val getInstalledApps: () -> Set<String>,
) : IOSDevice {

    private var closed: Boolean = false

    override fun open() {
        ensureXCUITestChannel()
    }

    private fun ensureXCUITestChannel() {
        logger.info("[Start] Uninstalling xctest ui runner app on $deviceId")
        installer.killAndUninstall()
        logger.info("[Done] Uninstalling xctest ui runner app on $deviceId")
        installer.setup()
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        error("Not supported")
    }

    override fun contentDescriptor(): Result<XCUIElement, Throwable> {
        val appId = activeAppId() ?: error("Unable to obtain active app id")

        return when (val result = getViewHierarchy(appId)) {
            is Ok -> result
            is Err -> Err(result.error)
        }
    }

    private fun getViewHierarchy(appId: String): Result<XCUIElement, Throwable> {
        return try {
            client.subTree(appId).use {
                if (it.isSuccessful) {
                    val xcUiElement = it.body?.let { response ->
                        mapper.readValue(String(response.bytes()), XCUIElement::class.java)
                    } ?: error("View Hierarchy not available, response body is null")
                    Ok(xcUiElement)
                } else {
                    val err = it.body?.let { response ->
                        val errorResponse = String(response.bytes()).trim()
                        val error = mapper.readValue(errorResponse, Error::class.java)
                        when (error.errorCode) {
                            VIEW_HIERARCHY_SNAPSHOT_ERROR_CODE -> Err(IllegalArgumentSnapshotFailure())
                            else -> Err(UnknownFailure(errorResponse))
                        }
                    } ?: Err(UnknownFailure("Error body for view hierarchy request not available"))
                    err
                }
            }
        } catch (exception: Throwable) {
            Err(exception)
        }
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun longPress(x: Int, y: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun scroll(
        xStart: Float,
        yStart: Float,
        xEnd: Float,
        yEnd: Float,
        velocity: Float?
    ): Result<Unit, Throwable> {
        return runCatching {
            val appId = activeAppId() ?: return@runCatching

            client.swipe(
                appId = appId,
                startX = xStart,
                startY = yStart,
                endX = xEnd,
                endY = yEnd,
                velocity = velocity,
            ).use {}
        }
    }

    override fun input(text: String): Result<Unit, Throwable> {
        return runCatching {
            val appId = activeAppId() ?: return@runCatching

            client.inputText(
                appId = appId,
                text = text,
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

    override fun pullAppState(id: String, file: File): Result<Idb.PullResponse, Throwable> {
        error("Not supported")
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun clearAppState(id: String): Result<Idb.RmResponse, Throwable> {
        error("Not supported")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun launch(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun takeScreenshot(out: Sink): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        error("Not supported")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun isShutdown(): Boolean {
        return closed
    }

    override fun close() {
        installer.close()

        closed = true
    }

    private fun activeAppId(): String? {
        val appIds = getInstalledApps()
        logger.info("installed apps: $appIds")

        return client.runningAppId(appIds).use { response ->
            val runningAppBundleId = if (response.isSuccessful) {
                response.body?.let { body ->
                    val responseBody: GetRunningAppIdResponse = mapper.readValue(
                        String(body.bytes()),
                        GetRunningAppIdResponse::class.java
                    )
                    val runningAppId = responseBody.runningAppBundleId
                    logger.info("Running app id response received $runningAppId")
                    runningAppId
                }
            } else {
                logger.info("request to resolve running app id failed with exception - ${response.body?.toString()}")

                return null
            }

            logger.info("found running app id $runningAppBundleId")

            runningAppBundleId
        }
    }

    class IllegalArgumentSnapshotFailure : Throwable("Failed to capture view hierarchy due to kAXErrorIllegalArgument")
    class InputFieldNotFound : Throwable("Unable to find focused input field")
    class UnknownFailure(val errorResponse: String) : Throwable()

    companion object {

        private const val VIEW_HIERARCHY_SNAPSHOT_ERROR_CODE = "illegal-argument-snapshot-failure"

        private val mapper by lazy { jacksonObjectMapper() }

    }

}
