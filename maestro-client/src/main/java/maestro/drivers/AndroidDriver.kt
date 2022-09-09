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
import maestro.MaestroTimer
import maestro.Point
import maestro.TreeNode
import maestro.android.AndroidAppFiles
import maestro_android.MaestroDriverGrpc
import maestro_android.deviceInfoRequest
import maestro_android.tapRequest
import maestro_android.viewHierarchyRequest
import okio.buffer
import okio.sink
import okio.source
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
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
    private var forwarder: AutoCloseable? = null

    override fun name(): String {
        return "Android Device ($dadb)"
    }

    override fun open() {
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

        forwarder = dadb.tcpForward(
            hostPort,
            7001
        )
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
        forwarder?.close()
        forwarder = null
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
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels
        )
    }

    override fun launchApp(appId: String) {
        if (!isPackageInstalled(appId)) {
            throw IllegalArgumentException("Package $appId is not installed")
        }

        shell("am force-stop $appId")
        shell("monkey --pct-syskeys 0 -p $appId 1")
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
        dadb.shell("input swipe 500 1000 700 -900 2000")
    }

    override fun swipe(start: Point, end: Point) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} 2000")
    }

    override fun backPress() {
        dadb.shell("input keyevent 4")
    }

    override fun inputText(text: String) {
        dadb.shell("input text \"$text\"")
    }

    override fun openLink(link: String) {
        dadb.shell("am start -d $link")
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
            clickable = (node as? Element)
                ?.getAttribute("clickable")
                ?.let { it == "true" }
                ?: false
        )
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
        val output: String = shell("pm list packages $packageName")
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

        private const val SERVER_LAUNCH_TIMEOUT_MS = 5000
    }
}
