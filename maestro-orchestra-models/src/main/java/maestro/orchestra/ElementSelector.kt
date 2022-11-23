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

package maestro.orchestra

import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class ElementSelector(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val size: SizeSelector? = null,
    val below: ElementSelector? = null,
    val above: ElementSelector? = null,
    val leftOf: ElementSelector? = null,
    val rightOf: ElementSelector? = null,
    val containsChild: ElementSelector? = null,
    val optional: Boolean = false,
    val traits: List<ElementTrait>? = null,
    val index: Int? = null,
    val enabled: Boolean? = null,
) {

    data class SizeSelector(
        val width: Int? = null,
        val height: Int? = null,
        val tolerance: Int? = null,
    )

    fun evaluateScripts(jsEngine: JsEngine): ElementSelector {
        return copy(
            textRegex = textRegex?.evaluateScripts(jsEngine),
            idRegex = idRegex?.evaluateScripts(jsEngine),
            below = below?.evaluateScripts(jsEngine),
            above = above?.evaluateScripts(jsEngine),
            leftOf = leftOf?.evaluateScripts(jsEngine),
            rightOf = rightOf?.evaluateScripts(jsEngine),
            containsChild = containsChild?.evaluateScripts(jsEngine),
        )
    }

    fun description(): String {
        val descriptions = mutableListOf<String>()

        textRegex?.let {
            descriptions.add("\"$it\"")
        }

        idRegex?.let {
            descriptions.add("id: $it")
        }

        below?.let {
            descriptions.add("Below ${it.description()}")
        }

        above?.let {
            descriptions.add("Above ${it.description()}")
        }

        leftOf?.let {
            descriptions.add("Left of ${it.description()}")
        }

        rightOf?.let {
            descriptions.add("Right of ${it.description()}")
        }

        size?.let {
            var description = "Size: ${it.width}x${it.height}"
            it.tolerance?.let { tolerance ->
                description += "(tolerance: $tolerance)"
            }

            descriptions.add(description)
        }

        traits?.let {
            descriptions.add(
                "Has traits: ${traits.joinToString(", ") { it.description }}"
            )
        }

        index?.let {
            descriptions.add("Index: $it")
        }

        val combined = descriptions.joinToString(", ")

        return if (optional) {
            "(Optional) $combined"
        } else {
            combined
        }
    }

}
