package ios.xctest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import hierarchy.Error
import hierarchy.XCUIElement
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.DeviceInfo
import maestro.logger.Logger
import okio.Sink
import okio.buffer
import xcuitest.XCTestDriverClient
import xcuitest.api.GetRunningAppIdResponse
import xcuitest.api.IsScreenStaticResponse
import java.io.File
import java.io.InputStream

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
                val body = response.body?.bytes()?.let { String(it) }

                if (!response.isSuccessful) {
                    val message = "${response.code} ${response.message} - $body"
                    logger.info("Device info failed: $message")
                    throw UnknownFailure(message)
                }

                body ?: throw UnknownFailure("Error: response body missing")

                val deviceInfo = mapper.readValue(body, DeviceInfo::class.java)
                logger.info("Device info $deviceInfo")

                deviceInfo
            }
        }
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

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        return runCatching {
            if (code != 40) throw IllegalStateException("XCTest can only press the enter key (code 40)")
            client.inputText("\n")
        }
    }

    override fun pressKey(name: String) {
        client.pressKey(name).use {}
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        error("Not supported")
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

    override fun input(text: String): Result<Unit, Throwable> {
        return runCatching {
            client.inputText(
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

    override fun pullAppState(id: String, file: File): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
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
        launchArguments: List<String>,
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
                response.body?.let { body ->
                    if (response.isSuccessful) {
                        out.buffer().use {
                            it.write(body.bytes())
                        }
                    } else {
                        val errorResponse = String(body.bytes()).trim()
                        throw UnknownFailure(errorResponse)
                    }
                } ?: throw UnknownFailure("Error - body for snapshot request not available")
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
                response.body?.let { body ->
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
                } ?: throw UnknownFailure("Error - body for isScreenStatic request not available")
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
        client.eraseText(charactersToErase).use {}
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
                val body = response.body?.let { String(it.bytes()) } ?: ""
                val code = response.code
                logger.info("request to resolve running app id failed with exception - Code: $code Body: $body")

                return null
            }

            logger.info("found running app id $runningAppBundleId")

            runningAppBundleId
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
