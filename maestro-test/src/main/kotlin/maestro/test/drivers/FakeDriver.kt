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

package maestro.test.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
import maestro.Driver
import maestro.KeyCode
import maestro.MaestroException
import maestro.OnDeviceElementQuery
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.utils.ScreenshotUtils
import okio.Sink
import okio.buffer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class FakeDriver : Driver {

    private var state: State = State.NOT_INITIALIZED
    private var layout: FakeLayoutElement = FakeLayoutElement()
    private var installedApps = mutableSetOf<String>()

    private val events = mutableListOf<Event>()

    private var copiedText: String? = null

    private var currentText: String = ""

    private var airplaneMode: Boolean = false

    // If true, keyboard will remain visible even after hideKeyboard() is called.
    var keyboardRemainsVisible: Boolean = false

    override fun name(): String {
        return "Fake Device"
    }

    override fun open() {
        if (state == State.OPEN) {
            throw IllegalStateException("Already open")
        }

        state = State.OPEN
    }

    override fun close() {
        if (state == State.CLOSED) {
            throw IllegalStateException("Already closed")
        }

        if (state == State.NOT_INITIALIZED) {
            throw IllegalStateException("Not open yet")
        }

        state = State.CLOSED
    }

    override fun deviceInfo(): DeviceInfo {
        ensureOpen()

        return DeviceInfo(
            platform = Platform.IOS,
            widthPixels = 1080,
            heightPixels = 1920,
            widthGrid = 540,
            heightGrid = 960,
        )
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        ensureOpen()

        events += Event.SetOrientation(orientation)
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    ) {
        ensureOpen()

        if (appId !in installedApps) {
            throw MaestroException.UnableToLaunchApp("App $appId is not installed")
        }

        events.add(
            Event.LaunchApp(
                appId = appId,
                launchArguments = launchArguments,
            )
        )
    }

    override fun stopApp(appId: String) {
        ensureOpen()

        events.add(Event.StopApp(appId))
    }

    override fun killApp(appId: String) {
        ensureOpen()

        events.add(Event.KillApp(appId))
    }

    override fun clearAppState(appId: String) {
        ensureOpen()

        if (appId !in installedApps) {
            println("App $appId not installed. Skipping clearAppState.")
            return
        }
        events.add(Event.ClearState(appId))
    }

    override fun clearKeychain() {
        ensureOpen()

        events.add(Event.ClearKeychain)
    }

    override fun tap(point: Point) {
        ensureOpen()

        layout.dispatchClick(point.x, point.y)

        events += Event.Tap(point)
    }

    override fun longPress(point: Point) {
        ensureOpen()

        events += Event.LongPress(point)
    }

    override fun pressKey(code: KeyCode) {
        ensureOpen()

        if (code == KeyCode.BACKSPACE) {
            currentText = currentText.dropLast(1)
        }

        events += Event.PressKey(code)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        ensureOpen()

        return layout.toTreeNode()
    }

    override fun scrollVertical() {
        ensureOpen()

        events += Event.Scroll
    }

    override fun isKeyboardVisible(): Boolean {
        ensureOpen()

        if (keyboardRemainsVisible) {
            return true
        }

        return !events.contains(Event.HideKeyboard)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        ensureOpen()

        events += Event.Swipe(start, end, durationMs)
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        ensureOpen()

        events += Event.SwipeWithDirection(swipeDirection, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        ensureOpen()
        val todo = mutableListOf(layout)
        while (todo.isNotEmpty()) {
            val next = todo.removeLast()
            todo.addAll(next.children)
            if (next.bounds != null) {
                when (direction) {
                    SwipeDirection.UP -> next.bounds = next.bounds!!.translate(x = 0, y = -300)
                    SwipeDirection.DOWN -> next.bounds = next.bounds!!.translate(x = 0, y = 300)
                    SwipeDirection.RIGHT -> next.bounds = next.bounds!!.translate(x = -300, y = 0)
                    SwipeDirection.LEFT -> next.bounds = next.bounds!!.translate(x = 300, y = 0)
                }
            }
        }
        events += Event.SwipeElementWithDirection(elementPoint, direction, durationMs)
    }

    override fun backPress() {
        ensureOpen()

        events += Event.BackPress
    }

    override fun hideKeyboard() {
        ensureOpen()

        events += Event.HideKeyboard
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        ensureOpen()

        val deviceInfo = deviceInfo()
        val image = BufferedImage(
            deviceInfo.widthPixels,
            deviceInfo.heightPixels,
            BufferedImage.TYPE_INT_ARGB,
        )

        val canvas = image.graphics
        layout.draw(canvas)
        canvas.dispose()

        ImageIO.write(
            image,
            "png",
            out.buffer().outputStream(),
        )

        events += Event.TakeScreenshot
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        ensureOpen()

        out.buffer().writeUtf8("Screen recording").close()

        events += Event.StartRecording

        return object : ScreenRecording {
            override fun close() {
                events += Event.StopRecording
            }
        }
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        ensureOpen()

        events += Event.SetLocation(latitude, longitude)
    }

    override fun eraseText(charactersToErase: Int) {
        ensureOpen()

        currentText = if (charactersToErase == MAX_ERASE_CHARACTERS) {
            ""
        } else {
            currentText.dropLast(charactersToErase)
        }
        events += Event.EraseAllText
    }

    override fun inputText(text: String) {
        ensureOpen()

        currentText += text

        events += Event.InputText(text)
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        ensureOpen()

        if (browser) {
            events += Event.OpenBrowser(link)
        } else {
            events += Event.OpenLink(link, autoVerify)
        }
    }

    override fun setProxy(host: String, port: Int) {
        ensureOpen()

        events += Event.SetProxy(host, port)
    }

    override fun resetProxy() {
        ensureOpen()

        events += Event.ResetProxy
    }

    override fun isShutdown(): Boolean {
        return state != State.OPEN
    }

    override fun isUnicodeInputSupported(): Boolean {
        return false
    }

    fun setLayout(layout: FakeLayoutElement) {
        this.layout = layout
    }

    fun addInstalledApp(appId: String) {
        installedApps.add(appId)
    }

    fun assertEvents(expected: List<Event>) {
        assertThat(events)
            .containsAtLeastElementsIn(expected)
            .inOrder()
    }

    fun assertEventCount(event: Event, expectedCount: Int) {
        assertThat(events.count { it == event })
            .isEqualTo(expectedCount)
    }

    fun assertHasEvent(event: Event) {
        if (!events.contains(event)) {
            throw AssertionError("Expected event: $event\nActual events: $events")
        }
    }

    fun assertNoEvent(event: Event) {
        if (events.contains(event)) {
            throw AssertionError("Expected no event: $event\nActual events: $events")
        }
    }

    fun assertAnyEvent(condition: ((event: Event) -> Boolean)) {
        assertThat(events.any { condition(it) }).isTrue()
    }

    fun assertAllEvent(condition: ((event: Event) -> Boolean)) {
        assertThat(events.all { condition(it) }).isTrue()
    }

    fun assertNoInteraction() {
        if (events.isNotEmpty()) {
            throw AssertionError("Expected no interaction, but got: $events")
        }
    }

    fun assertCurrentTextInput(expected: String) {
        assertThat(currentText).isEqualTo(expected)
    }

    private fun ensureOpen() {
        if (state != State.OPEN) {
            throw IllegalStateException("Driver is not opened yet")
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy {
        return ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, 0.005, this)
    }

    override fun capabilities(): List<Capability> {
        return emptyList()
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        ensureOpen()

        events.add(Event.SetPermissions(appId, permissions))
    }

    override fun addMedia(mediaFiles: List<File>) {
        ensureOpen()

        mediaFiles.forEach { _ -> events.add(Event.AddMedia) }
    }

    override fun isAirplaneModeEnabled(): Boolean {
        return this.airplaneMode
    }

    override fun setAirplaneMode(enabled: Boolean) {
        this.airplaneMode = enabled
    }

    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> {
        if (query is OnDeviceElementQuery.Css) {
            return searchCssRecursive(layout, query.css)
        } else {
            return super.queryOnDeviceElements(query)
        }
    }

    private fun searchCssRecursive(element: FakeLayoutElement, css: String): List<TreeNode> {
        val result = mutableListOf<TreeNode>()

        if (element.matchesCssFilter == css) {
            result.add(element.toTreeNode())
        }

        for (child in element.children) {
            result.addAll(searchCssRecursive(child, css))
        }

        return result
    }

    sealed class Event {

        data class Tap(
            val point: Point
        ) : Event(), UserInteraction

        data class LongPress(
            val point: Point
        ) : Event(), UserInteraction

        object Scroll : Event(), UserInteraction

        object BackPress : Event(), UserInteraction

        object HideKeyboard : Event(), UserInteraction

        data class InputText(
            val text: String
        ) : Event(), UserInteraction

        data class Swipe(
            val start: Point,
            val End: Point,
            val durationMs: Long
        ) : Event(), UserInteraction

        data class SwipeWithDirection(val swipeDirection: SwipeDirection, val durationMs: Long) : Event(),
            UserInteraction

        data class SwipeElementWithDirection(
            val point: Point,
            val swipeDirection: SwipeDirection,
            val durationMs: Long
        ) : Event(), UserInteraction

        data class LaunchApp(
            val appId: String,
            val launchArguments: Map<String, Any> = emptyMap()
        ) : Event(), UserInteraction

        data class StopApp(
            val appId: String
        ) : Event()

        data class KillApp(
            val appId: String
        ) : Event()

        data class ClearState(
            val appId: String
        ) : Event()

        data class OpenLink(
            val link: String,
            val autoLink: Boolean = false
        ) : Event()

        data class OpenBrowser(
            val link: String,
        ) : Event()

        data class PressKey(
            val code: KeyCode,
        ) : Event()

        data class SetOrientation(
            val orientation: DeviceOrientation,
        ) : Event()

        object TakeScreenshot : Event()

        object ClearKeychain : Event()

        data class SetLocation(
            val latitude: Double,
            val longitude: Double,
        ) : Event()

        object EraseAllText : Event()

        data class SetProxy(
            val host: String,
            val port: Int,
        ) : Event()

        object ResetProxy : Event()

        data class SetPermissions(
            val appId: String,
            val permissions: Map<String, String>,
        ) : Event()

        object AddMedia : Event()

        object StartRecording : Event()

        object StopRecording : Event()
    }

    interface UserInteraction

    private enum class State {
        CLOSED,
        OPEN,
        NOT_INITIALIZED,
    }

    companion object {

        private val MAPPER = jacksonObjectMapper()
        private const val MAX_ERASE_CHARACTERS = 50
    }
}
