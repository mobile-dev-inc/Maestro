/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.protobuf.ByteString
import dadb.AdbShellPacket
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import maestro.*
import maestro.MaestroDriverStartupException.AndroidDriverTimeoutException
import maestro.MaestroDriverStartupException.AndroidInstrumentationSetupFailure
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.android.AndroidAppFiles
import maestro.android.AndroidLaunchArguments.toAndroidLaunchArguments
import maestro.android.chromedevtools.AndroidWebViewHierarchyClient
import maestro.utils.BlockingStreamObserver
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.ScreenshotUtils
import maestro.utils.StringUtils.toRegexSafe
import maestro_android.*
import net.dongliu.apk.parser.ApkFile
import okio.*
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.use

private val logger = LoggerFactory.getLogger(Maestro::class.java)

private const val DefaultDriverHostPort = 7001

class AndroidDriver(
    private val dadb: Dadb,
    hostPort: Int? = null,
    private var emulatorName: String = "",
    private val reinstallDriver: Boolean = true,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    ) : Driver {
    private var portForwarder: AutoCloseable? = null
    private var open = false
    private val hostPort: Int = hostPort ?: DefaultDriverHostPort

    private val metrics = metricsProvider.withPrefix("maestro.driver").withTags(mapOf("platform" to "android", "emulatorName" to emulatorName))

    private val channel = ManagedChannelBuilder.forAddress("localhost", this.hostPort)
        .usePlaintext()
        .keepAliveTime(2, TimeUnit.MINUTES)
        .keepAliveTimeout(20, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
    private val blockingStub = MaestroDriverGrpc.newBlockingStub(channel)
    private val blockingStubWithTimeout get() = blockingStub.withDeadlineAfter(120, TimeUnit.SECONDS)
    private val asyncStub = MaestroDriverGrpc.newStub(channel)
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    private val androidWebViewHierarchyClient = AndroidWebViewHierarchyClient(dadb)

    private var instrumentationSession: AdbShellStream? = null
    private var proxySet = false

    private var isLocationMocked = false
    private var chromeDevToolsEnabled = false

    override fun name(): String {
        return "Android Device ($dadb)"
    }

    override fun open() {
        allocateForwarder()
        installMaestroApks()
        startInstrumentationSession(hostPort)

        try {
            awaitLaunch()
        } catch (ignored: InterruptedException) {
            instrumentationSession?.close()
            return
        }
    }

    private fun startInstrumentationSession(port: Int = 7001) {
        val startTime = System.currentTimeMillis()
        val apiLevel = getDeviceApiLevel()

        val instrumentationCommand = buildString {
            append("am instrument -w ")
            if (apiLevel >= 26) append("-m ")
            append("-e debug false ")
            append("-e class 'dev.mobile.maestro.MaestroDriverService#grpcServer' ")
            append("-e port $port ")
            append("dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner &\n")
        }

        open = true
        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            instrumentationSession = dadb.openShell(instrumentationCommand)

            if (instrumentationSession.successfullyStarted()) {
                return
            }

            instrumentationSession?.close()
            Thread.sleep(100)
        }
        throw AndroidInstrumentationSetupFailure("Maestro instrumentation could not be initialized")
    }

    private fun getDeviceApiLevel(): Int {
        val response = dadb.openShell("getprop ro.build.version.sdk").readAll()
        if (response.exitCode != 0) {
            throw IOException("Failed to get device API level: ${response.errorOutput}")
        }
        return response.output.trim().toIntOrNull() ?: throw IOException("Invalid API level: ${response.output}")
    }


    private fun allocateForwarder() {
        portForwarder?.close()

        portForwarder = dadb.tcpForward(
            hostPort,
            hostPort
        )
    }

    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            runCatching {
                dadb.open("tcp:$hostPort").close()
                return
            }
            Thread.sleep(100)
        }

        throw AndroidDriverTimeoutException("Maestro Android driver did not start up in time  ---  emulator [ ${emulatorName} ] & port  [ dadb.open( tcp:${hostPort} ) ]")
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }
        if (isLocationMocked) {
            blockingStubWithTimeout.disableLocationUpdates(emptyRequest {  })
            isLocationMocked = false
        }

        LOGGER.info("[Start] close port forwarder")
        portForwarder?.close()
        LOGGER.info("[Done] close port forwarder")

        LOGGER.info("[Start] Uninstall driver from device")
        if (reinstallDriver) {
            uninstallMaestroDriverApp()
        }
        if (reinstallDriver) {
            uninstallMaestroServerApp()
        }
        LOGGER.info("[Done] Uninstall driver from device")

        LOGGER.info("[Start] Close instrumentation session")
        instrumentationSession?.close()
        instrumentationSession = null
        LOGGER.info("[Done] Close instrumentation session")

        LOGGER.info("[Start] Shutdown GRPC channel")
        channel.shutdown()
        LOGGER.info("[Done] Shutdown GRPC channel")

        androidWebViewHierarchyClient.close()

        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Couldn't close Maestro Android driver due to gRPC timeout")
        }
    }

    override fun deviceInfo(): DeviceInfo {
        return runDeviceCall("deviceInfo") {
            val response = blockingStubWithTimeout.deviceInfo(deviceInfoRequest {})

            DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = response.widthPixels,
                heightPixels = response.heightPixels,
                widthGrid = response.widthPixels,
                heightGrid = response.heightPixels,
            )
        }
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    ) {
        metrics.measured("operation", mapOf("command" to "launchApp", "appId" to appId)) {
            if(!open) // pick device flow, no open() invocation
                open()

            if (!isPackageInstalled(appId)) {
                throw IllegalArgumentException("Package $appId is not installed")
            }

            val arguments = launchArguments.toAndroidLaunchArguments()
            runDeviceCall("launchApp") {
                blockingStubWithTimeout.launchApp(
                    launchAppRequest {
                        this.packageName = appId
                        this.arguments.addAll(arguments)
                    }
                ) ?: throw IllegalStateException("Maestro driver failed to launch app")
            }
        }
    }

    override fun stopApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "stopApp", "appId" to appId)) {
            // Note: If the package does not exist, this call does *not* throw an exception
            shell("am force-stop $appId")
        }
    }

    override fun killApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "killApp", "appId" to appId)) {
            // Kill is the adb command needed to trigger System-initiated Process Death
            shell("am kill $appId")
        }
    }

    override fun clearAppState(appId: String) {
        metrics.measured("operation", mapOf("command" to "clearAppState", "appId" to appId)) {
            if (!isPackageInstalled(appId)) {
                return@measured
            }

            shell("pm clear $appId")
        }
    }

    override fun clearKeychain() {
        // No op
    }

    override fun tap(point: Point) {
        metrics.measured("operation", mapOf("command" to "tap")) {
            runDeviceCall("tap") {
                blockingStubWithTimeout.tap(
                    tapRequest {
                        x = point.x
                        y = point.y
                    }
                ) ?: throw IllegalStateException("Response can't be null")
            }
        }
    }

    override fun longPress(point: Point) {
        metrics.measured("operation", mapOf("command" to "longPress")) {
            dadb.shell("input swipe ${point.x} ${point.y} ${point.x} ${point.y} 3000")
        }
    }

    override fun pressKey(code: KeyCode) {
        metrics.measured("operation", mapOf("command" to "pressKey")) {
            val intCode: Int = when (code) {
                KeyCode.ENTER -> 66
                KeyCode.BACKSPACE -> 67
                KeyCode.BACK -> 4
                KeyCode.VOLUME_UP -> 24
                KeyCode.VOLUME_DOWN -> 25
                KeyCode.HOME -> 3
                KeyCode.LOCK -> 276
                KeyCode.REMOTE_UP -> 19
                KeyCode.REMOTE_DOWN -> 20
                KeyCode.REMOTE_LEFT -> 21
                KeyCode.REMOTE_RIGHT -> 22
                KeyCode.REMOTE_CENTER -> 23
                KeyCode.REMOTE_PLAY_PAUSE -> 85
                KeyCode.REMOTE_STOP -> 86
                KeyCode.REMOTE_NEXT -> 87
                KeyCode.REMOTE_PREVIOUS -> 88
                KeyCode.REMOTE_REWIND -> 89
                KeyCode.REMOTE_FAST_FORWARD -> 90
                KeyCode.POWER -> 26
                KeyCode.ESCAPE -> 111
                KeyCode.TAB -> 62
                KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> 280
                KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> 281
                KeyCode.REMOTE_BUTTON_A -> 96
                KeyCode.REMOTE_BUTTON_B -> 97
                KeyCode.REMOTE_MENU -> 82
                KeyCode.TV_INPUT -> 178
                KeyCode.TV_INPUT_HDMI_1 -> 243
                KeyCode.TV_INPUT_HDMI_2 -> 244
                KeyCode.TV_INPUT_HDMI_3 -> 245
            }

            dadb.shell("input keyevent $intCode")
            Thread.sleep(300)
        }
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        return metrics.measured("operation", mapOf("command" to "contentDescriptor")) {
            val response = callViewHierarchy()

            val document = documentBuilderFactory
                .newDocumentBuilder()
                .parse(response.hierarchy.byteInputStream())

            val baseTree = mapHierarchy(document)

            val treeNode = androidWebViewHierarchyClient.augmentHierarchy(baseTree, chromeDevToolsEnabled)

            if (excludeKeyboardElements) {
                treeNode.excludeKeyboardElements() ?: treeNode
            } else {
                treeNode
            }
        }
    }

    private fun TreeNode.excludeKeyboardElements(): TreeNode? {
        val filtered = children.mapNotNull {
            it.excludeKeyboardElements()
        }.toList()

        val resourceId = attributes["resource-id"]
        if (resourceId != null && resourceId.startsWith("com.google.android.inputmethod.latin:id/")) {
            return null
        }
        return TreeNode(
            attributes = attributes,
            children = filtered,
            clickable = clickable,
            enabled = enabled,
            focused = focused,
            checked = checked,
            selected = selected
        )
    }

    private fun callViewHierarchy(attempt: Int = 1): MaestroAndroid.ViewHierarchyResponse {
        return try {
            blockingStubWithTimeout.viewHierarchy(viewHierarchyRequest {})
        } catch (throwable: StatusRuntimeException) {
            val status = Status.fromThrowable(throwable)
            when (status.code) {
                Status.Code.DEADLINE_EXCEEDED -> {
                    LOGGER.error("Timeout while fetching view hierarchy")
                    throw throwable
                }
                Status.Code.UNAVAILABLE -> {
                    if (throwable.cause is IOException || throwable.message?.contains("io exception", ignoreCase = true) == true) {
                        LOGGER.error("Not able to reach the gRPC server while fetching view hierarchy")
                    } else {
                        LOGGER.error("Received UNAVAILABLE status with message: ${throwable.message}")
                    }
                }
                else -> {
                    LOGGER.error("Unexpected error: ${status.code} - ${throwable.message}")
                }
            }

            // There is a bug in Android UiAutomator that rarely throws an NPE while dumping a view hierarchy.
            // Trying to recover once by giving it a bit of time to settle.
            LOGGER.error("Failed to get view hierarchy: ${status.description}", throwable)

            if (attempt > 0) {
                MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 1000L)
                return callViewHierarchy(attempt - 1)
            }
            throw throwable
        }
    }

    override fun scrollVertical() {
        metrics.measured("operation", mapOf("command" to "scrollVertical")) {
            swipe(SwipeDirection.UP, 400)
        }
    }

    override fun isKeyboardVisible(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isKeyboardVisible")) {
            val root = contentDescriptor().let {
                val deviceInfo = deviceInfo()
                val filtered = it.filterOutOfBounds(
                    width = deviceInfo.widthGrid,
                    height = deviceInfo.heightGrid
                )
                filtered ?: it
            }
            "com.google.android.inputmethod.latin:id" in jacksonObjectMapper().writeValueAsString(root)
        }
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithDirection", "direction" to swipeDirection.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            when (swipeDirection) {
                SwipeDirection.UP -> {
                    val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.DOWN -> {
                    val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.2f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.RIGHT -> {
                    val startX = (deviceInfo.widthGrid * 0.1f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.LEFT -> {
                    val startX = (deviceInfo.widthGrid * 0.9f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }
            }
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithElementPoint", "direction" to direction.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            when (direction) {
                SwipeDirection.UP -> {
                    val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
                }

                SwipeDirection.DOWN -> {
                    val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
                }

                SwipeDirection.RIGHT -> {
                    val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
                }

                SwipeDirection.LEFT -> {
                    val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
                }
            }
        }
    }

    private fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
        metrics.measured("operation", mapOf("command" to "directionalSwipe", "durationMs" to durationMs.toString())) {
            dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
        }
    }

    override fun backPress() {
        metrics.measured("operation", mapOf("command" to "backPress")) {
            dadb.shell("input keyevent 4")
            Thread.sleep(300)
        }
    }

    override fun hideKeyboard() {
        metrics.measured("operation", mapOf("command" to "hideKeyboard")) {
            dadb.shell("input keyevent 4") // 'Back', which dismisses the keyboard before handing over to navigation
            Thread.sleep(300)
            waitForAppToSettle(null, null)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        metrics.measured("operation", mapOf("command" to "takeScreenshot", "compressed" to compressed.toString())) {
            runDeviceCall("takeScreenshot") {
                val response = blockingStubWithTimeout.screenshot(screenshotRequest {})
                out.buffer().use {
                    it.write(response.bytes.toByteArray())
                }
            }
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        return metrics.measured("operation", mapOf("command" to "startScreenRecording")) {

            val deviceScreenRecordingPath = "/sdcard/maestro-screenrecording.mp4"

            val future = CompletableFuture.runAsync({
                val timeLimit = if (getDeviceApiLevel() >= 34) "--time-limit 0" else ""
                try {
                    shell("screenrecord $timeLimit --bit-rate '100000' $deviceScreenRecordingPath")
                } catch (e: IOException) {
                    throw IOException(
                        "Failed to capture screen recording on the device. Note that some Android emulators do not support screen recording. " +
                            "Try using a different Android emulator (eg. Pixel 5 / API 30)",
                        e,
                    )
                }
            }, Executors.newSingleThreadExecutor())

            object : ScreenRecording {
                override fun close() {
                    dadb.shell("killall -INT screenrecord") // Ignore exit code
                    future.get()
                    Thread.sleep(3000)
                    dadb.pull(out, deviceScreenRecordingPath)
                }
            }
        }
    }

    override fun inputText(text: String) {
        metrics.measured("operation", mapOf("command" to "inputText")) {
            runDeviceCall("inputText") {
                blockingStubWithTimeout.inputText(inputTextRequest {
                    this.text = text
                }) ?: throw IllegalStateException("Input Response can't be null")
            }
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        metrics.measured("operation", mapOf("command" to "openLink", "appId" to appId, "autoVerify" to autoVerify.toString(), "browser" to browser.toString())) {
            if (browser) {
                openBrowser(link)
            } else {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\"")
            }

            if (autoVerify) {
                autoVerifyApp(appId)
            }
        }
    }

    private fun autoVerifyApp(appId: String?) {
        if (appId != null) {
            autoVerifyWithAppName(appId)
        }
        autoVerifyChromeAgreement()
    }

    private fun autoVerifyWithAppName(appId: String) {
        val appNameResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val appName = ApkFile(apkFile).apkMeta.name
            apkFile.delete()
            appName
        }
        if (appNameResult.isSuccess) {
            val appName = appNameResult.getOrThrow()
            waitUntilScreenIsStatic(3000)
            val appNameElement = filterByText(appName)
            if (appNameElement != null) {
                tap(appNameElement.bounds.center())
                filterById("android:id/button_once")?.let {
                    tap(it.bounds.center())
                }
            } else {
                val openWithAppElement = filterByText(".*$appName.*")
                if (openWithAppElement != null) {
                    filterById("android:id/button_once")?.let {
                        tap(it.bounds.center())
                    }
                }
            }
        }
    }

    private fun autoVerifyChromeAgreement() {
        filterById("com.android.chrome:id/terms_accept")?.let { tap(it.bounds.center()) }
        waitForAppToSettle(null, null)
        filterById("com.android.chrome:id/negative_button")?.let { tap(it.bounds.center()) }
    }

    private fun filterByText(textRegex: String): UiElement? {
        val textMatcher = Filters.textMatches(textRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(textMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun filterById(idRegex: String): UiElement? {
        val idMatcher = Filters.idMatches(idRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(idMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun openBrowser(link: String) {
        val installedPackages = installedPackages()
        when {
            installedPackages.contains("com.android.chrome") -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\" com.android.chrome")
            }

            installedPackages.contains("org.mozilla.firefox") -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\" org.mozilla.firefox")
            }

            else -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\"")
            }
        }
    }

    private fun installedPackages() = shell("pm list packages").split("\n")
        .map { line: String -> line.split(":".toRegex()).toTypedArray() }
        .filter { parts: Array<String> -> parts.size == 2 }
        .map { parts: Array<String> -> parts[1] }

    override fun setLocation(latitude: Double, longitude: Double) {
        metrics.measured("operation", mapOf("command" to "setLocation")) {
            if (!isLocationMocked) {
                LOGGER.info("[Start] Setting up for mocking location $latitude, $longitude")
                shell("pm grant dev.mobile.maestro android.permission.ACCESS_FINE_LOCATION")
                shell("pm grant dev.mobile.maestro android.permission.ACCESS_COARSE_LOCATION")
                shell("appops set dev.mobile.maestro android:mock_location allow")
                runDeviceCall("enableMockLocationProviders") {
                    blockingStubWithTimeout.enableMockLocationProviders(emptyRequest {  })
                }
                LOGGER.info("[Done] Setting up for mocking location $latitude, $longitude")

                isLocationMocked = true
            }

            runDeviceCall("setLocation") {
                blockingStubWithTimeout.setLocation(
                    setLocationRequest {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                ) ?: error("Set Location Response can't be null")
            }
        }
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // Disable accelerometer based rotation before overriding orientation
        dadb.shell("settings put system accelerometer_rotation 0")

        when(orientation) {
            DeviceOrientation.PORTRAIT -> dadb.shell("settings put system user_rotation 0")
            DeviceOrientation.LANDSCAPE_LEFT -> dadb.shell("settings put system user_rotation 1")
            DeviceOrientation.UPSIDE_DOWN -> dadb.shell("settings put system user_rotation 2")
            DeviceOrientation.LANDSCAPE_RIGHT -> dadb.shell("settings put system user_rotation 3")
        }
    }

    override fun eraseText(charactersToErase: Int) {
        metrics.measured("operation", mapOf("command" to "eraseText", "charactersToErase" to charactersToErase.toString())) {
            runDeviceCall("eraseText") {
                blockingStubWithTimeout.eraseAllText(
                    eraseAllTextRequest {
                        this.charactersToErase = charactersToErase
                    }
                ) ?: throw IllegalStateException("Erase Response can't be null")
            }
        }
    }

    override fun setProxy(host: String, port: Int) {
        metrics.measured("operation", mapOf("command" to "setProxy")) {
            shell("""settings put global http_proxy "${host}:${port}"""")
            proxySet = true
        }
    }

    override fun resetProxy() {
        metrics.measured("operation", mapOf("command" to "resetProxy")) {
            shell("settings put global http_proxy :0")
        }
    }

    override fun isShutdown(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isShutdown")) {
            channel.isShutdown
        }
    }

    override fun isUnicodeInputSupported(): Boolean {
        return false
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        return metrics.measured("operation", mapOf("command" to "waitForAppToSettle", "appId" to appId, "timeoutMs" to timeoutMs.toString())) {
            if (appId != null) {
                waitForWindowToSettle(appId, initialHierarchy, timeoutMs)
            } else {
                ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
            }
        }
    }

    private fun waitForWindowToSettle(
        appId: String,
        initialHierarchy: ViewHierarchy?,
        timeoutMs: Int? = null
    ): ViewHierarchy {
        val endTime = System.currentTimeMillis() + WINDOW_UPDATE_TIMEOUT_MS
        var hierarchy: ViewHierarchy? = null
        do {
            runDeviceCall("isWindowUpdating") {
                val windowUpdating = blockingStubWithTimeout.isWindowUpdating(checkWindowUpdatingRequest {
                    this.appId = appId
                }).isWindowUpdating

                if (windowUpdating) {
                    hierarchy = ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
                }
            }
        } while (System.currentTimeMillis() < endTime)

        return hierarchy ?: ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return metrics.measured("operation", mapOf("command" to "waitUntilScreenIsStatic", "timeoutMs" to timeoutMs.toString())) {
            ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this)
        }
    }

    override fun capabilities(): List<Capability> {
        return metrics.measured("operation", mapOf("command" to "capabilities")) {
            listOf(
                Capability.FAST_HIERARCHY
            )
        }
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        metrics.measured("operation", mapOf("command" to "setPermissions", "appId" to appId)) {
            val mutable = permissions.toMutableMap()
            mutable.remove("all")?.let { value ->
                setAllPermissions(appId, value)
            }

            mutable.forEach { permission ->
                val permissionValue = translatePermissionValue(permission.value)
                translatePermissionName(permission.key).forEach { permissionName ->
                    setPermissionInternal(appId, permissionName, permissionValue)
                }
            }
        }
    }

    override fun addMedia(mediaFiles: List<File>) {
        metrics.measured("operation", mapOf("command" to "addMedia", "mediaFilesCount" to mediaFiles.size.toString())) {
            LOGGER.info("[Start] Adding media files")
            mediaFiles.forEach { addMediaToDevice(it) }
            LOGGER.info("[Done] Adding media files")
        }
    }

    override fun isAirplaneModeEnabled(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isAirplaneModeEnabled")) {
            when (val result = shell("cmd connectivity airplane-mode").trim()) {
                "No shell command implementation.", "" -> {
                    LOGGER.debug("Falling back to old airplane mode read method")
                    when (val fallbackResult = shell("settings get global airplane_mode_on").trim()) {
                        "0" -> false
                        "1" -> true
                        else -> throw IllegalStateException("Received invalid response from while trying to read airplane mode state: $fallbackResult")
                    }
                }

                "disabled" -> false
                "enabled" -> true
                else -> throw IllegalStateException("Received invalid response while trying to read airplane mode state: $result")
            }
        }
    }

    override fun setAirplaneMode(enabled: Boolean) {
        metrics.measured("operation", mapOf("command" to "setAirplaneMode", "enabled" to enabled.toString())) {
            // fallback to old way on API < 28
            if (getDeviceApiLevel() < 28) {
                val num = if (enabled) 1 else 0
                shell("settings put global airplane_mode_on $num")
                // We need to broadcast the change to really apply it
                broadcastAirplaneMode(enabled)
                return@measured
            }
            val value = if (enabled) "enable" else "disable"
            shell("cmd connectivity airplane-mode $value")
        }
    }

    private fun broadcastAirplaneMode(enabled: Boolean) {
        val command = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        try {
            shell(command)
        } catch (e: IOException) {
            if (e.message?.contains("Security exception: Permission Denial:") == true) {
                try {
                    shell("su root $command")
                } catch (e: IOException) {
                    throw MaestroException.NoRootAccess("Failed to broadcast airplane mode change. Make sure to run an emulator with root access for API < 28")
                }
            }
        }
    }

    override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) {
        this.chromeDevToolsEnabled = enabled
    }

    fun setDeviceLocale(country: String, language: String): Int {
        return metrics.measured("operation", mapOf("command" to "setDeviceLocale", "country" to country, "language" to language)) {
            dadb.shell("pm grant dev.mobile.maestro android.permission.CHANGE_CONFIGURATION")
            val response =
                dadb.shell("am broadcast -a dev.mobile.maestro.locale -n dev.mobile.maestro/.receivers.LocaleSettingReceiver --es lang $language --es country $country")
            extractSetLocaleResult(response.output)
        }
    }

    private fun extractSetLocaleResult(result: String): Int {
        val regex = Regex("result=(-?\\d+)")
        val match = regex.find(result)
        return match?.groups?.get(1)?.value?.toIntOrNull() ?: -1
    }

    private fun addMediaToDevice(mediaFile: File) {
        val namedSource = NamedSource(
            mediaFile.name,
            mediaFile.source(),
            mediaFile.extension,
            mediaFile.path
        )
        val responseObserver = BlockingStreamObserver<MaestroAndroid.AddMediaResponse>()
        val requestStream = asyncStub.addMedia(responseObserver)
        val ext =
            MediaExt.values().firstOrNull { it.extName == namedSource.extension } ?: throw IllegalArgumentException(
                "Extension .${namedSource.extension} is not yet supported for add media"
            )

        val buffer = Buffer()
        val source = namedSource.source
        while (source.read(buffer, CHUNK_SIZE) != -1L) {
            requestStream.onNext(
                addMediaRequest {
                    this.payload = payload {
                        data = ByteString.copyFrom(buffer.readByteArray())
                    }
                    this.mediaName = namedSource.name
                    this.mediaExt = ext.extName
                }
            )
            buffer.clear()
        }
        source.close()
        requestStream.onCompleted()
        responseObserver.awaitResult()
    }

    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissionsResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val permissions = ApkFile(apkFile).apkMeta.usesPermissions
            apkFile.delete()
            permissions
        }
        if (permissionsResult.isSuccess) {
            permissionsResult.getOrNull()?.let {
                it.forEach { permission ->
                    setPermissionInternal(appId, permission, translatePermissionValue(permissionValue))
                }
            }
        }
    }

    private fun setPermissionInternal(appId: String, permission: String, permissionValue: String) {
        try {
            shell("pm $permissionValue $appId $permission")
        } catch (exception: Exception) {
            // Ignore if it's something that the user doesn't have control over (e.g. you can't grant / deny INTERNET)
            if (exception.message?.contains("is not a changeable permission type") == false) {
                // Debug level is fine.
                // We don't need to be loud about this. IOExceptions were already caught in shell(..)
                // Remaining issues are likely due to "all" containing permissions that the app doesn't support.
                logger.debug("Failed to set permission $permission for app $appId: ${exception.message}")
            }
        }
    }

    private fun translatePermissionName(name: String): List<String> {
        return when (name) {
            "location" -> listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            )

            "camera" -> listOf("android.permission.CAMERA")
            "contacts" -> listOf(
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS"
            )

            "phone" -> listOf(
                "android.permission.CALL_PHONE",
                "android.permission.ANSWER_PHONE_CALLS",
            )

            "microphone" -> listOf(
                "android.permission.RECORD_AUDIO"
            )

            "bluetooth" -> listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
            )

            "storage" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            )

            "notifications" -> listOf(
                "android.permission.POST_NOTIFICATIONS"
            )

            "medialibrary" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO"
            )

            "calendar" -> listOf(
                "android.permission.WRITE_CALENDAR",
                "android.permission.READ_CALENDAR"
            )

            "sms" -> listOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS"
            )

            else -> listOf(name.replace("[^A-Za-z0-9._]+".toRegex(), ""))
        }
    }

    private fun translatePermissionValue(value: String): String {
        return when (value) {
            "allow" -> "grant"
            "deny" -> "revoke"
            "unset" -> "revoke"
            else -> "revoke"
        }
    }

    private fun mapHierarchy(node: Node): TreeNode {
        val attributes = if (node is Element) {
            val attributesBuilder = mutableMapOf<String, String>()

            if (node.hasAttribute("text")) {
                val text = node.getAttribute("text")
                attributesBuilder["text"] = text
            }

            if (node.hasAttribute("content-desc")) {
                attributesBuilder["accessibilityText"] = node.getAttribute("content-desc")
            }

            if (node.hasAttribute("hintText")) {
                attributesBuilder["hintText"] = node.getAttribute("hintText")
            }

            if (node.hasAttribute("class") && node.getAttribute("class") == TOAST_CLASS_NAME) {
                attributesBuilder["ignoreBoundsFiltering"] = true.toString()
            } else {
                attributesBuilder["ignoreBoundsFiltering"] = false.toString()
            }

            if (node.hasAttribute("resource-id")) {
                attributesBuilder["resource-id"] = node.getAttribute("resource-id")
            }

            if (node.hasAttribute("clickable")) {
                attributesBuilder["clickable"] = node.getAttribute("clickable")
            }

            if (node.hasAttribute("bounds")) {
                attributesBuilder["bounds"] = node.getAttribute("bounds")
            }

            if (node.hasAttribute("enabled")) {
                attributesBuilder["enabled"] = node.getAttribute("enabled")
            }

            if (node.hasAttribute("focused")) {
                attributesBuilder["focused"] = node.getAttribute("focused")
            }

            if (node.hasAttribute("checked")) {
                attributesBuilder["checked"] = node.getAttribute("checked")
            }

            if (node.hasAttribute("scrollable")) {
                attributesBuilder["scrollable"] = node.getAttribute("scrollable")
            }

            if (node.hasAttribute("selected")) {
                attributesBuilder["selected"] = node.getAttribute("selected")
            }

            if (node.hasAttribute("class")) {
                attributesBuilder["class"] = node.getAttribute("class")
            }

            if (node.hasAttribute("important-for-accessibility")) {
                attributesBuilder["important-for-accessibility"] = node.getAttribute("important-for-accessibility")
            }

            if (node.hasAttribute("error")) {
                attributesBuilder["error"] = node.getAttribute("error")
            }

            attributesBuilder
        } else {
            emptyMap()
        }

        val children = mutableListOf<TreeNode>()
        val childNodes = node.childNodes
        (0 until childNodes.length).forEach { i ->
            children += mapHierarchy(childNodes.item(i))
        }

        return TreeNode(
            attributes = attributes.toMutableMap(),
            children = children,
            clickable = node.getBoolean("clickable"),
            enabled = node.getBoolean("enabled"),
            focused = node.getBoolean("focused"),
            checked = node.getBoolean("checked"),
            selected = node.getBoolean("selected"),
        )
    }

    private fun Node.getBoolean(name: String): Boolean? {
        return (this as? Element)
            ?.getAttribute(name)
            ?.let { it == "true" }
    }

    fun installMaestroDriverApp() {
        metrics.measured("operation", mapOf("command" to "installMaestroDriverApp")) {
            if (reinstallDriver) {
                uninstallMaestroDriverApp()
            } else if (isPackageInstalled("dev.mobile.maestro")) {
                return@measured
            }

            val maestroAppApk = File.createTempFile("maestro-app", ".apk")

            Maestro::class.java.getResourceAsStream("/maestro-app.apk")?.let {
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
    }

    private fun installMaestroServerApp() {
        if (reinstallDriver) {
            uninstallMaestroServerApp()
        } else if (isPackageInstalled("dev.mobile.maestro.test")) {
            return
        }

        val maestroServerApk = File.createTempFile("maestro-server", ".apk")

        Maestro::class.java.getResourceAsStream("/maestro-server.apk")?.let {
            val bufferedSink = maestroServerApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }

        install(maestroServerApk)
        if (!isPackageInstalled("dev.mobile.maestro.test")) {
            throw IllegalStateException("dev.mobile.maestro.test was not installed")
        }
        maestroServerApk.delete()
    }

    private fun installMaestroApks() {
        installMaestroDriverApp()
        installMaestroServerApp()
    }

    fun uninstallMaestroDriverApp() {
        metrics.measured("operation", mapOf("command" to "uninstallMaestroDriverApp")) {
            try {
                if (isPackageInstalled("dev.mobile.maestro")) {
                    uninstall("dev.mobile.maestro")
                }
            } catch (e: IOException) {
                logger.warn("Failed to check or uninstall maestro driver app: ${e.message}")
                // Continue with cleanup even if we can't check package status
                try {
                    uninstall("dev.mobile.maestro")
                } catch (e2: IOException) {
                    logger.warn("Failed to uninstall maestro driver app: ${e2.message}")
                    // Just log and continue, don't throw
                }
            }
        }
    }

    private fun uninstallMaestroServerApp() {
        try {
            if (isPackageInstalled("dev.mobile.maestro.test")) {
                uninstall("dev.mobile.maestro.test")
            }
        } catch (e: IOException) {
            logger.warn("Failed to check or uninstall maestro server app: ${e.message}")
            // Continue with cleanup even if we can't check package status
            try {
                uninstall("dev.mobile.maestro.test")
            } catch (e2: IOException) {
                logger.warn("Failed to uninstall maestro server app: ${e2.message}")
                // Just log and continue, don't throw
            }
        }
    }

    private fun uninstallMaestroApks() {
        uninstallMaestroDriverApp()
        uninstallMaestroServerApp()
    }

    private fun install(apkFile: File) {
        try {
            dadb.install(apkFile)
        } catch (installError: IOException) {
            throw IOException("Failed to install apk " + apkFile + ": " + installError.message, installError)
        }
    }

    private fun uninstall(packageName: String) {
        try {
            dadb.uninstall(packageName)
        } catch (error: IOException) {
            throw IOException("Failed to uninstall package " + packageName + ": " + error.message, error)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        try {
            val output: String = shell("pm list packages --user 0 $packageName")
            return output.split("\n".toRegex())
                .map { line -> line.split(":".toRegex()) }
                .filter { parts -> parts.size == 2 }
                .map { parts -> parts[1] }
                .any { linePackageName -> linePackageName == packageName }
        } catch (e: IOException) {
            logger.warn("Failed to check if package $packageName is installed: ${e.message}")
            // If we can't check, we'll assume it's not installed
            throw e
        }
    }

    private fun shell(command: String): String {
        val response: AdbShellResponse = try {
            dadb.shell(command)
        } catch (e: IOException) {
            throw IOException(command, e)
        }

        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    private fun AdbShellStream?.successfullyStarted(): Boolean {
        val output = this?.read()
        return when {
            output is AdbShellPacket.StdError -> false
            output.toString().contains("FAILED", true) -> false
            output.toString().contains("UNABLE", true) -> false
            else -> true
        }
    }

    private fun <T> runDeviceCall(callName: String, call: () -> T): T {
        return try {
            call()
        } catch (throwable: StatusRuntimeException) {
            val status = Status.fromThrowable(throwable)
            when (status.code) {
                Status.Code.DEADLINE_EXCEEDED -> {
                    LOGGER.error("$callName call failed on android with $status", throwable)
                    throw throwable
                }
                Status.Code.UNAVAILABLE -> {
                    if (throwable.cause is IOException || throwable.message?.contains("io exception", ignoreCase = true) == true) {
                        LOGGER.error("Not able to reach the gRPC server while processing $callName command")
                        throw throwable
                    } else {
                        LOGGER.error("Received UNAVAILABLE status with message: ${throwable.message} while processing $callName command", throwable)
                        throw throwable
                    }
                }
                Status.Code.INTERNAL -> {
                    val trailers = Status.trailersFromThrowable(throwable)
                    val errorType = trailers?.get(ERROR_TYPE_KEY)
                    val errorMsg = trailers?.get(ERROR_MSG_KEY)
                    val errorCause = trailers?.get(ERROR_CAUSE_KEY)
                    LOGGER.error("$callName call failed: type=$errorType, message=$errorMsg, cause=$errorCause", throwable)
                    throw throwable.cause ?: throwable
                }
                else -> {
                    LOGGER.error("Unexpected error during $callName: ${status.code} - ${throwable.message} and cause ${throwable.cause}", throwable)
                    throw throwable
                }
            }
        }
    }


    companion object {

        private const val SERVER_LAUNCH_TIMEOUT_MS = 15000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
        private const val WINDOW_UPDATE_TIMEOUT_MS = 750

        private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        private val ERROR_TYPE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-type", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_MSG_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-message", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_CAUSE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-cause", Metadata.ASCII_STRING_MARSHALLER)

        private val LOGGER = LoggerFactory.getLogger(AndroidDriver::class.java)

        private const val TOAST_CLASS_NAME = "android.widget.Toast"
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
        private const val CHUNK_SIZE = 1024L * 1024L * 3L
    }
}
