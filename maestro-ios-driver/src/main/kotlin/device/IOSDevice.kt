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

package device

import com.github.michaelbull.result.Result
import hierarchy.ViewHierarchy
import xcuitest.api.DeviceInfo
import okio.Sink
import java.io.InputStream

interface IOSDevice : AutoCloseable {

    val deviceId: String?

    fun open()

    fun deviceInfo(): DeviceInfo

    fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy

    fun tap(x: Int, y: Int)

    fun longPress(x: Int, y: Int, durationMs: Long)

    fun scroll(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double,
    )

    /**
     * Inputs text into the currently focused element.
     */
    fun input(text: String)

    /**
     * Installs application on the device.
     *
     * @param stream - input stream of zipped .app bundle
     */
    fun install(stream: InputStream)

    /**
     * Uninstalls the app.
     *
     * Idempotent. Operation succeeds if app is not installed.
     *
     * @param id = bundle id of the app to uninstall
     */
    fun uninstall(id: String)

    /**
     * Clears state of a given application.
     *
     * @param id = bundle id of the app to clear
     */
    fun clearAppState(id: String)

    /**
     * Clears device keychain.
     */
    fun clearKeychain(): Result<Unit, Throwable>

    /**
     * Launches the app.
     *
     * @param id - bundle id of the app to launch
     */
    fun launch(
        id: String,
        launchArguments: Map<String, Any>,
    )

    /**
     * Terminates the app.
     *
     * @param id - bundle id of the app to terminate
     */
    fun stop(id: String)

    fun isKeyboardVisible(): Boolean

    /**
     * Opens a link
     *
     * @param link - link to open
     */
    fun openLink(link: String): Result<Unit, Throwable>

    /**
     * Takes a screenshot and writes it into output sink
     *
     * @param out - output sink
     */
    fun takeScreenshot(out: Sink, compressed: Boolean)

    /**
     * Start a screen recording
     *
     * @param out - output sink
     */
    fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable>

    /**
     * Sets the geolocation
     *
     * @param lat - latitude
     * @param long - longitude
     */
    fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable>

    /**
     * @return true if the connection to the device (not device itself) is shut down
     */
    fun isShutdown(): Boolean

    /**
     * @return false if 2 consequent screenshots are equal, true if screen is static
     */
    fun isScreenStatic(): Boolean

    fun setPermissions(id: String, permissions: Map<String, String>)

    fun pressKey(name: String)

    fun pressButton(name: String)

    fun eraseText(charactersToErase: Int)

    fun addMedia(path: String)
}

interface IOSScreenRecording : AutoCloseable
