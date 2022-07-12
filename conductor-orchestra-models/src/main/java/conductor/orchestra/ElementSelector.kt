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

package conductor.orchestra

data class ElementSelector(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val size: SizeSelector? = null,
    val optional: Boolean = false,
) {

    data class SizeSelector(
        val width: Int? = null,
        val height: Int? = null,
        val tolerance: Int? = null,
    )

    fun description(): String {
        val descriptions = mutableListOf<String>()

        textRegex?.let {
            descriptions.add("\"$it\"")
        }

        idRegex?.let {
            descriptions.add("id: $it")
        }

        size?.let {
            var description = "Size: ${it.width}x${it.height}"
            it.tolerance?.let { tolerance ->
                description += "(tolerance: $tolerance)"
            }

            descriptions.add(description)
        }

        val combined = descriptions.joinToString(", ")

        return if (optional) {
            "(Optional) $combined"
        } else {
            combined
        }
    }

}
