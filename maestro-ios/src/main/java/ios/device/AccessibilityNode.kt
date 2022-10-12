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

package ios.device

import com.google.gson.annotations.SerializedName

data class AccessibilityNode(
    val frame: Frame?,
    val title: String?,
    val type: String?,
    @SerializedName("AXUniqueId") val axUniqueId: String?,
    @SerializedName("AXLabel") val axLabel: String?,
    @SerializedName("AXValue") val axValue: String?,
    val enabled: Boolean?,
) {

    data class Frame(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
}
