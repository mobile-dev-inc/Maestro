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

package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator

data class YamlLaunchApp(
    @JsonAlias("url")
    val appId: String?,
    val clearState: Boolean?,
    val clearKeychain: Boolean?,
    val stopApp: Boolean?,
    val permissions: Map<String, String>?,
    val arguments: Map<String, Any>?,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(appId: String): YamlLaunchApp {
            return YamlLaunchApp(
                appId = appId,
                clearState = null,
                clearKeychain = null,
                stopApp = null,
                permissions = null,
                arguments = null,
            )
        }
    }
}
