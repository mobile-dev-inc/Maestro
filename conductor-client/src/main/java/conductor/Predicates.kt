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

package conductor

import conductor.UiElement.Companion.toUiElement
import conductor.UiElement.Companion.toUiElementOrNull
import kotlin.math.abs

typealias ElementLookupPredicate = (TreeNode) -> Boolean

object Predicates {

    fun allOf(vararg predicates: ElementLookupPredicate): ElementLookupPredicate = allOf(predicates.toList())

    fun allOf(predicates: List<ElementLookupPredicate>): ElementLookupPredicate = { node ->
        predicates.all { it(node) }
    }

    fun textMatches(text: String): ElementLookupPredicate {
        return {
            it.attributes["text"]?.let { value ->
                text == value
            } ?: false
        }
    }

    fun textMatches(regex: Regex): ElementLookupPredicate {
        return {
            it.attributes["text"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
    }

    fun idMatches(regex: Regex): ElementLookupPredicate {
        return {
            it.attributes["resource-id"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
    }

    fun sizeMatches(
        width: Int? = null,
        height: Int? = null,
        tolerance: Int? = null,
    ): ElementLookupPredicate {
        fun predicate(it: TreeNode): Boolean {
            if (it.attributes["bounds"] == null) {
                return false
            }

            val uiElement = it.toUiElement()

            val finalTolerance = tolerance ?: 0
            if (width != null) {
                if (abs(uiElement.bounds.width - width) > finalTolerance) {
                    return false
                }
            }

            if (height != null) {
                if (abs(uiElement.bounds.height - height) > finalTolerance) {
                    return false
                }
            }

            return true
        }

        return { predicate(it) }
    }

    fun below(other: UiElement): ElementLookupPredicate {
        return {
            val uiElement = it.toUiElementOrNull()

            uiElement != null && uiElement.bounds.y > other.bounds.y
        }
    }

    fun above(other: UiElement): ElementLookupPredicate {
        return {
            val uiElement = it.toUiElementOrNull()

            uiElement != null && uiElement.bounds.y < other.bounds.y
        }
    }

    fun leftOf(other: UiElement): ElementLookupPredicate {
        return {
            val uiElement = it.toUiElementOrNull()

            uiElement != null && uiElement.bounds.x < other.bounds.x
        }
    }

    fun rightOf(other: UiElement): ElementLookupPredicate {
        return {
            val uiElement = it.toUiElementOrNull()

            uiElement != null && uiElement.bounds.x > other.bounds.x
        }
    }

}
