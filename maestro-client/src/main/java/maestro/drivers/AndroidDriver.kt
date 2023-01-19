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

import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException.UnableToTakeScreenshot
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.android.AndroidAppFiles
import maestro.android.asManifest
import maestro.android.resolveLauncherActivity
import maestro.utils.MaestroTimer
import maestro_android.MaestroDriverGrpc
import maestro_android.deviceInfoRequest
import maestro_android.eraseAllTextRequest
import maestro_android.inputTextRequest
import maestro_android.tapRequest
import maestro_android.viewHierarchyRequest
import okio.Sink
import okio.buffer
import okio.sink
import okio.source
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.xml.parsers.DocumentBuilderFactory

class AndroidDriver(
    private val dadb: Dadb,
    private val hostPort: Int,
) : Driver {

    private val channel = ManagedChannelBuilder.forAddress("localhost", hostPort)
        .usePlaintext()
        .build()
    private val blockingStub = MaestroDriverGrpc.newBlockingStub(channel)
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    private var instrumentationSession: AdbShellStream? = null
    private var proxySet = false

    override fun name(): String {
        return "Android Device ($dadb)"
    }

    override fun open() {
        uninstallMaestroApks()
        installMaestroApks()

        instrumentationSession = dadb.openShell()
        instrumentationSession?.write(
            "am instrument -w -m -e debug false " +
                "-e class 'dev.mobile.maestro.MaestroDriverService#grpcServer' " +
                "dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner &\n"
        )

        try {
            awaitLaunch()
        } catch (ignored: InterruptedException) {
            instrumentationSession?.close()
            return
        }

        allocateForwarder()
    }

    private fun allocateForwarder() {
        PORT_TO_FORWARDER[hostPort]?.close()
        PORT_TO_ALLOCATION_POINT[hostPort]?.let {
            LOGGER.warn("Port $hostPort was already allocated. Allocation point: $it")
        }

        PORT_TO_FORWARDER[hostPort] = dadb.tcpForward(
            hostPort,
            7001
        )
        PORT_TO_ALLOCATION_POINT[hostPort] = Exception().stackTraceToString()
    }

    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < SERVER_LAUNCH_TIMEOUT_MS) {
            try {
                dadb.open("tcp:7001").close()
                return
            } catch (ignored: Exception) {
                // Continue
            }

            Thread.sleep(100)
        }

        throw TimeoutException("Maestro Android driver did not start up in time")
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }

        PORT_TO_FORWARDER[hostPort]?.close()
        PORT_TO_FORWARDER.remove(hostPort)
        PORT_TO_ALLOCATION_POINT.remove(hostPort)
        uninstallMaestroApks()
        instrumentationSession?.close()
        instrumentationSession = null
        channel.shutdown()

        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Couldn't close Maestro Android driver due to gRPC timeout")
        }
    }

    override fun deviceInfo(): DeviceInfo {
        val response = blockingStub.deviceInfo(deviceInfoRequest {})

        return DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels,
            widthGrid = response.widthPixels,
            heightGrid = response.heightPixels,
        )
    }

    override fun launchApp(appId: String) {
        if (!isPackageInstalled(appId)) {
            throw IllegalArgumentException("Package $appId is not installed")
        }

        try {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val manifest = apkFile.asManifest()
            runCatching {
                val launcherActivity = manifest.resolveLauncherActivity(appId)
                val shellResponse = dadb.shell("am start-activity -n $appId/${launcherActivity}")
                if (shellResponse.errorOutput.isNotEmpty()) shell("monkey --pct-syskeys 0 -p $appId 1")
            }.onFailure { shell("monkey --pct-syskeys 0 -p $appId 1") }
        } catch (ioException: IOException) {
            shell("monkey --pct-syskeys 0 -p $appId 1")
        } catch (saxException: SAXException) {
            shell("monkey --pct-syskeys 0 -p $appId 1")
        }
    }

    override fun stopApp(appId: String) {
        // Note: If the package does not exist, this call does *not* throw an exception
        shell("am force-stop $appId")
    }

    override fun clearAppState(appId: String) {
        if (!isPackageInstalled(appId)) {
            return
        }

        shell("pm clear $appId")
    }

    override fun clearKeychain() {
        // No op
    }

    override fun pullAppState(appId: String, outFile: File) {
        AndroidAppFiles.pull(dadb, appId, outFile)
    }

    override fun pushAppState(appId: String, stateFile: File) {
        AndroidAppFiles.push(dadb, appId, stateFile)
    }

    override fun tap(point: Point) {
        blockingStub.tap(
            tapRequest {
                x = point.x
                y = point.y
            }
        ) ?: throw IllegalStateException("Response can't be null")
    }

    override fun longPress(point: Point) {
        dadb.shell("input swipe ${point.x} ${point.y} ${point.x} ${point.y} 3000")
    }

    override fun pressKey(code: KeyCode) {
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
        }

        dadb.shell("input keyevent $intCode")
    }

    override fun contentDescriptor(): TreeNode {
        val response = try {
            blockingStub.viewHierarchy(viewHierarchyRequest {})
        } catch (ignored: Exception) {
            // There is a bug in Android UiAutomator that rarely throws an NPE while dumping a view hierarchy.
            // Trying to recover once by giving it a bit of time to settle.

            MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 1000L)
            blockingStub.viewHierarchy(viewHierarchyRequest {})
        }

        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(response.hierarchy.byteInputStream())

        return mapHierarchy(document)
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when(swipeDirection) {
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
                val startY = (deviceInfo.heightGrid * 0.1f).toInt()
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

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when(direction) {
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

    private fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun backPress() {
        dadb.shell("input keyevent 4")
    }

    override fun hideKeyboard() {
        dadb.shell("input keyevent 66")
        dadb.shell("input keyevent 111")
    }

    override fun takeScreenshot(out: Sink) {
        val deviceScreenshotPath = "/sdcard/maestro-screenshot.png"

        val adbShellResponse = dadb.shell("screencap -p $deviceScreenshotPath")
        if (adbShellResponse.exitCode != 0) {
            throw UnableToTakeScreenshot("Failed to take screenshot: ${adbShellResponse.errorOutput}")
        }

        dadb.pull(out, deviceScreenshotPath)
        dadb.shell("rm $deviceScreenshotPath")
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        val deviceScreenRecordingPath = "/sdcard/maestro-screenrecording.mp4"

        val future = CompletableFuture.runAsync({
            try {
                shell("screenrecord --bit-rate '100000' $deviceScreenRecordingPath")
            } catch (e: IOException) {
                throw IOException(
                    "Failed to capture screen recording on the device. Note that some Android emulators do not support screen recording. " +
                        "Try using a different Android emulator (eg. Pixel 5 / API 30)",
                    e,
                )
            }
        }, Executors.newSingleThreadExecutor())

        return object : ScreenRecording {
            override fun close() {
                dadb.shell("killall -INT screenrecord") // Ignore exit code
                future.get()
                Thread.sleep(3000)
                dadb.pull(out, deviceScreenRecordingPath)
            }
        }
    }

    override fun inputText(text: String) {
        blockingStub.inputText(inputTextRequest {
            this.text = text
        }) ?: throw IllegalStateException("Input Response can't be null")
    }

    override fun openLink(link: String) {
        dadb.shell("am start -a android.intent.action.VIEW -d \"$link\"")
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        blockingStub.eraseAllText(
            eraseAllTextRequest {
                this.charactersToErase = charactersToErase
            }
        ) ?: throw IllegalStateException("Erase Response can't be null")
    }

    override fun setProxy(host: String, port: Int) {
        shell("""settings put global http_proxy "${host}:${port}"""")
        proxySet = true
    }

    override fun resetProxy() {
        shell("settings put global http_proxy :0")
    }

    override fun isShutdown(): Boolean {
        return channel.isShutdown
    }

    override fun isUnicodeInputSupported(): Boolean {
        return false
    }

    private fun mapHierarchy(node: Node): TreeNode {
        val attributes = if (node is Element) {
            val attributesBuilder = mutableMapOf<String, String>()

            if (node.hasAttribute("text")) {
                val text = node.getAttribute("text")

                if (text.isNotBlank()) {
                    attributesBuilder["text"] = text
                } else if (node.hasAttribute("content-desc")) {
                    // Using content-desc as fallback for text
                    attributesBuilder["text"] = node.getAttribute("content-desc")
                } else {
                    attributesBuilder["text"] = text
                }
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

            if (node.hasAttribute("selected")) {
                attributesBuilder["selected"] = node.getAttribute("selected")
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
            attributes = attributes,
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

    private fun installMaestroApks() {
        val maestroAppApk = File.createTempFile("maestro-app", ".apk")
        val maestroServerApk = File.createTempFile("maestro-server", ".apk")
        Maestro::class.java.getResourceAsStream("/maestro-app.apk")?.let {
            val bufferedSink = maestroAppApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        Maestro::class.java.getResourceAsStream("/maestro-server.apk")?.let {
            val bufferedSink = maestroServerApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        install(maestroAppApk)
        if (!isPackageInstalled("dev.mobile.maestro")) {
            throw IllegalStateException("dev.mobile.maestro was not installed")
        }
        install(maestroServerApk)
    }

    private fun uninstallMaestroApks() {
        if (isPackageInstalled("dev.mobile.maestro.test")) {
            uninstall("dev.mobile.maestro.test")
        }
        if (isPackageInstalled("dev.mobile.maestro")) {
            uninstall("dev.mobile.maestro")
        }
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
        val output: String = shell("pm list packages --user 0 $packageName")
        return output.split("\n".toRegex())
            .map { line -> line.split(":".toRegex()) }
            .filter { parts -> parts.size == 2 }
            .map { parts -> parts[1] }
            .any { linePackageName -> linePackageName == packageName }
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

    companion object {

        private const val SERVER_LAUNCH_TIMEOUT_MS = 15000

        private val LOGGER = LoggerFactory.getLogger(AndroidDriver::class.java)

        private val PORT_TO_FORWARDER = mutableMapOf<Int, AutoCloseable>()
        private val PORT_TO_ALLOCATION_POINT = mutableMapOf<Int, String>()

    }
}
