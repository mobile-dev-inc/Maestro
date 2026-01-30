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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize(`as` = YamlElementSelector::class)
data class YamlElementSelector(
    val text: String? = null,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val tolerance: Int? = null,
    val optional: Boolean? = null,
    val retryTapIfNoChange: Boolean? = null,
    val waitUntilVisible: Boolean? = null,
    val point: String? = null,
    val start: String? = null,
    val end: String? = null,
    val below: YamlElementSelectorUnion? = null,
    val above: YamlElementSelectorUnion? = null,
    val leftOf: YamlElementSelectorUnion? = null,
    val rightOf: YamlElementSelectorUnion? = null,
    val containsChild: YamlElementSelectorUnion? = null,
    val containsDescendants: List<YamlElementSelectorUnion>? = null,
    val traits: String? = null,
    val index: String? = null,
    val enabled: Boolean? = null,
    val selected: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val repeat: Int? = null,
    val delay: Int? = null,
    val waitToSettleTimeoutMs: Int? = null,
    val childOf: YamlElementSelectorUnion? = null,
    val label: String? = null,
    val css: String? = null,
) : YamlElementSelectorUnion {
    // Map to store custom identifier fields from YAML
    private val customFields: MutableMap<String, String> = mutableMapOf()
    
    @com.fasterxml.jackson.annotation.JsonAnySetter
    fun setCustomField(key: String, value: Any?) {
        // Only store string values as custom identifiers
        if (value is String) {
            customFields[key] = value
        }
    }
    
    fun getCustomFields(): Map<String, String> = customFields.toMap()
}
