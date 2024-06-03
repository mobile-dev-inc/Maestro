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

package maestro

import okio.Sink
import java.io.File
import java.util.UUID

interface Driver {

    fun name(): String

    fun open()

    fun close()

    fun deviceInfo(): DeviceInfo

    fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
        sessionId: UUID? = null,
    )

    fun stopApp(appId: String)

    fun killApp(appId: String)

    fun clearAppState(appId: String)

    fun clearKeychain()

    fun tap(point: Point)

    fun longPress(point: Point)

    fun pressKey(code: KeyCode)

    fun contentDescriptor(excludeKeyboardElements: Boolean = false): TreeNode

    fun scrollVertical()

    fun isKeyboardVisible(): Boolean

    fun swipe(start: Point, end: Point, durationMs: Long)

    fun swipe(swipeDirection: SwipeDirection, durationMs: Long)

    fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long)

    fun backPress()

    fun inputText(text: String)

    fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean)

    fun hideKeyboard()

    fun takeScreenshot(out: Sink, compressed: Boolean)

    fun startScreenRecording(out: Sink): ScreenRecording

    fun setLocation(latitude: Double, longitude: Double)

    fun eraseText(charactersToErase: Int)

    fun setProxy(host: String, port: Int)

    fun resetProxy()

    fun isShutdown(): Boolean

    fun isUnicodeInputSupported(): Boolean

    fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean

    fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int? = null): ViewHierarchy?

    fun capabilities(): List<Capability>

    fun setPermissions(appId: String, permissions: Map<String, String>)

    fun addMedia(mediaFiles: List<File>)

    fun isAirplaneModeEnabled(): Boolean

    fun setAirplaneMode(enabled: Boolean)
}
