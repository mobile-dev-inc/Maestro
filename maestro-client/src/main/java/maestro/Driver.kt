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

import java.io.File

interface Driver {

    fun name(): String

    fun open()

    fun close()

    fun deviceInfo(): DeviceInfo

    fun launchApp(appId: String)

    fun stopApp(appId: String)

    fun clearAppState(appId: String)

    fun pullAppState(appId: String, outFile: File)

    fun pushAppState(appId: String, stateFile: File)

    fun tap(point: Point)

    fun longPress(point: Point)

    fun contentDescriptor(): TreeNode

    fun scrollVertical()

    fun swipe(start: Point, end: Point)

    fun backPress()

    fun inputText(text: String)

    fun openLink(link: String)

}
