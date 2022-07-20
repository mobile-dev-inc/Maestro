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

package ios

import com.github.michaelbull.result.Result
import ios.device.AccessibilityNode
import ios.device.DeviceInfo
import java.io.InputStream

interface IOSDevice : AutoCloseable {

    fun deviceInfo(): Result<DeviceInfo, Throwable>

    fun contentDescriptor(): Result<List<AccessibilityNode>, Throwable>

    fun tap(x: Int, y: Int): Result<Unit, Throwable>

    fun scroll(xStart: Int, yStart: Int, xEnd: Int, yEnd: Int): Result<Unit, Throwable>

    /**
     * Inputs text into the currently focused element.
     */
    fun input(text: String): Result<Unit, Throwable>

    /**
     * Installs application on the device.
     *
     * @param stream - input stream of zipped .app bundle
     */
    fun install(stream: InputStream): Result<Unit, Throwable>

    /**
     * Uninstalls the app.
     *
     * Idempotent. Operation succeeds if app is not installed.
     *
     * @param id = bundle id of the app to uninstall
     */
    fun uninstall(id: String): Result<Unit, Throwable>

    /**
     * Launches the app.
     *
     * @param id - bundle id of the app to launch
     * @param isWarmup - in case it is warmup and we're not waiting for the logs, the app can be launched from foreground
     */
    fun launch(id: String): Result<Unit, Throwable>

    /**
     * Terminates the app.
     *
     * @param id - bundle id of the app to terminate
     */
    fun stop(id: String): Result<Unit, Throwable>

}
