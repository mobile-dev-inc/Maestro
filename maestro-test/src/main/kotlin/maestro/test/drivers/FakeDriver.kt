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

import maestro.DeviceInfo
import maestro.Driver
import maestro.MaestroException
import maestro.Point
import maestro.TreeNode
import java.io.File

class FakeDriver : Driver {

    private var state: State = State.NOT_INITIALIZED
    private var layout: FakeLayoutElement = FakeLayoutElement()
    private var installedApps = mutableSetOf<String>()

    private val events = mutableListOf<Event>()

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
            widthPixels = 1080,
            heightPixels = 1920,
        )
    }

    override fun launchApp(appId: String) {
        ensureOpen()

        if (appId !in installedApps) {
            throw MaestroException.UnableToLaunchApp("App $appId is not installed")
        }

        events.add(Event.LaunchApp(appId))
    }

    override fun stopApp(appId: String) {
        ensureOpen()

        events.add(Event.StopApp(appId))
    }

    override fun clearAppState(appId: String) {
        ensureOpen()

        if (appId !in installedApps) {
            println("App $appId not installed. Skipping clearAppState.")
            return
        }
        events.add(Event.ClearState(appId))
    }

    override fun pullAppState(appId: String, outFile: File) {
        ensureOpen()

        events.add(Event.PullAppState(appId, outFile))
    }

    override fun pushAppState(appId: String, stateFile: File) {
        ensureOpen()

        events.add(Event.PushAppState(appId, stateFile))
    }

    override fun tap(point: Point) {
        ensureOpen()

        events += Event.Tap(point)
    }

    override fun contentDescriptor(): TreeNode {
        ensureOpen()

        return layout.toTreeNode()
    }

    override fun scrollVertical() {
        ensureOpen()

        events += Event.Scroll
    }

    override fun swipe(start: Point, end: Point) {
        ensureOpen()

        events += Event.Swipe(start, end)
    }

    override fun backPress() {
        ensureOpen()

        events += Event.BackPress
    }

    override fun inputText(text: String) {
        ensureOpen()

        events += Event.InputText(text)
    }

    fun setLayout(layout: FakeLayoutElement) {
        this.layout = layout
    }

    fun addInstalledApp(appId: String) {
        installedApps.add(appId)
    }

    fun assertEvents(expected: List<Event>) {
        if (events != expected) {
            throw AssertionError("Expected events: $expected\nActual events: $events")
        }
    }

    fun assertHasEvent(event: Event) {
        if (!events.contains(event)) {
            throw AssertionError("Expected event: $event\nActual events: $events")
        }
    }

    fun assertNoInteraction() {
        if (events.isNotEmpty()) {
            throw AssertionError("Expected no interaction, but got: $events")
        }
    }

    private fun ensureOpen() {
        if (state != State.OPEN) {
            throw IllegalStateException("Driver is not opened yet")
        }
    }

    sealed class Event {

        data class Tap(
            val point: Point
        ) : Event()

        object Scroll : Event()

        object BackPress : Event()

        data class InputText(
            val text: String
        ) : Event()

        data class LaunchApp(
            val appId: String
        ) : Event()

        data class StopApp(
            val appId: String
        ) : Event()

        data class ClearState(
            val appId: String
        ) : Event()

        data class PullAppState(
            val appId: String,
            val outFile: File,
        ) : Event()

        data class PushAppState(
            val appId: String,
            val stateFile: File,
        ) : Event()

        data class Swipe(
            val start: Point,
            val End: Point
        ) : Event()

    }

    private enum class State {
        CLOSED,
        OPEN,
        NOT_INITIALIZED,
    }

}