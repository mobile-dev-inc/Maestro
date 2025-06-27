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

import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import kotlin.math.abs

typealias ElementFilter = (List<TreeNode>) -> List<TreeNode>

typealias ElementLookupPredicate = (TreeNode) -> Boolean

object Filters {

    val INDEX_COMPARATOR: Comparator<TreeNode> = compareBy(
        { it.toUiElementOrNull()?.bounds?.y ?: Int.MAX_VALUE },
        { it.toUiElementOrNull()?.bounds?.x ?: Int.MAX_VALUE },
    )

    fun intersect(filters: List<ElementFilter>): ElementFilter = { nodes ->
        filters
            .map { it(nodes).toSet() }
            .reduceOrNull { a, b -> a.intersect(b) }
            ?.toList() ?: nodes
    }

    fun compose(first: ElementFilter, second: ElementFilter): ElementFilter = compose(listOf(first, second))

    fun compose(filters: List<ElementFilter>): ElementFilter = { nodes ->
        filters
            .fold(nodes) { acc, filter ->
                filter(acc)
            }
    }

    fun ElementLookupPredicate.asFilter(): ElementFilter = { nodes ->
        nodes.filter { this(it) }
    }

    fun nonClickable(): ElementFilter {
        return { nodes -> nodes.filter { it.clickable == false } }
    }

    fun textMatches(regex: Regex): ElementFilter {
        return { nodes ->
            val textMatches = nodes.filter {
                it.attributes["text"]?.let { value ->
                    val strippedValue = value.replace('\n', ' ')

                    regex.matches(value)
                            || regex.pattern == value
                            || regex.matches(strippedValue)
                            || regex.pattern == strippedValue
                } ?: false
            }.toSet()

            val hintTextMatches = nodes.filter {
                it.attributes["hintText"]?.let { value ->
                    val strippedValue = value.replace('\n', ' ')

                    regex.matches(value)
                            || regex.pattern == value
                            || regex.matches(strippedValue)
                            || regex.pattern == strippedValue
                } ?: false
            }

            val accessibilityTextMatches = nodes.filter {
                it.attributes["accessibilityText"]?.let { value ->
                    val strippedValue = value.replace('\n', ' ')

                    regex.matches(value)
                            || regex.pattern == value
                            || regex.matches(strippedValue)
                            || regex.pattern == strippedValue
                } ?: false
            }.toSet()

            textMatches.union(hintTextMatches).union(accessibilityTextMatches).toList()
        }
    }

    fun idMatches(regex: Regex): ElementFilter {
        return { nodes ->
            val exactMatches = nodes
                .filter {
                    it.attributes["resource-id"]?.let { value ->
                        regex.matches(value)
                    } ?: false
                }
                .toSet()

            val idWithoutPrefixMatches = nodes
                .filter {
                    it.attributes["resource-id"]?.let { value ->
                        regex.matches(value.substringAfterLast('/'))
                    } ?: false
                }
                .toSet()

            exactMatches
                .union(idWithoutPrefixMatches)
                .toList()
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

    fun below(otherFilter: ElementFilter): ElementFilter {
        return relativeTo(otherFilter) { it, other -> it.bounds.y > other.bounds.y }
    }

    fun above(otherFilter: ElementFilter): ElementFilter {
        return relativeTo(otherFilter) { it, other -> it.bounds.y < other.bounds.y }
    }

    fun leftOf(otherFilter: ElementFilter): ElementFilter {
        return relativeTo(otherFilter) { it, other -> it.bounds.x < other.bounds.x }
    }

    fun rightOf(otherFilter: ElementFilter): ElementFilter {
        return relativeTo(otherFilter) { it, other -> it.bounds.x > other.bounds.x }
    }

    fun relativeTo(otherFilter: ElementFilter, predicate: (UiElement, UiElement) -> Boolean): ElementFilter {
        return { nodes ->
            val matchingOthers = otherFilter(nodes)
                .mapNotNull { it.toUiElementOrNull() }

            nodes
                .mapNotNull { it.toUiElementOrNull() }
                .flatMap {
                    matchingOthers
                        .filter { other -> predicate(it, other) }
                        .map { other -> it to it.distanceTo(other) }
                }
                .sortedBy { (_, distance) -> distance }
                .map { (element, _) -> element.treeNode }
        }
    }

    fun containsChild(other: UiElement): ElementLookupPredicate {
        val otherNode = other.treeNode
        return {
            it.children
                .any { child -> child == otherNode }
        }
    }

    fun containsDescendants(filters: List<ElementFilter>): ElementFilter {
        fun ElementFilter.matches(node: TreeNode): Boolean {
            return invoke(listOf(node)).isNotEmpty() || node.children.any { matches(it) }
        }
        return { nodes ->
            nodes.filter { node ->
                filters.all { filter ->
                    node.children.any { filter.matches(it) }
                }
            }
        }
    }

    fun hasText(): ElementLookupPredicate {
        return {
            it.attributes["text"] != null
        }
    }

    fun isSquare(): ElementLookupPredicate {
        return {
            it.toUiElementOrNull()
                ?.let { element ->
                    abs(1.0f - (element.bounds.width / element.bounds.height.toFloat())) < 0.03f
                } ?: false
        }
    }

    fun hasLongText(): ElementLookupPredicate {
        return {
            (it.attributes["text"]?.length ?: 0) > 200
        }
    }

    fun index(idx: Int): ElementFilter {
        return { nodes ->
            listOfNotNull(
                nodes
                    .sortedWith(INDEX_COMPARATOR)
                    .getOrNull(idx)
            )
        }
    }

    fun clickableFirst(): ElementFilter {
        return { nodes ->
            nodes.sortedByDescending { it.clickable }
        }
    }

    fun enabled(expected: Boolean): ElementFilter {
        return { nodes ->
            nodes.filter { it.enabled == expected }
        }
    }

    fun selected(expected: Boolean): ElementFilter {
        return { nodes ->
            nodes.filter { it.selected == expected }
        }
    }

    fun checked(expected: Boolean): ElementFilter {
        return { nodes ->
            nodes.filter { it.checked == expected }
        }
    }

    fun focused(expected: Boolean): ElementFilter {
        return { nodes ->
            nodes.filter { it.focused == expected }
        }
    }

    fun deepestMatchingElement(filter: ElementFilter): ElementFilter {
        return { nodes ->
            filter(nodes)
                .map {
                    val matchingChildren = deepestMatchingElement(filter)(it.children)

                    matchingChildren.lastOrNull()
                        ?: it
                }
        }
    }

    fun css(maestro: Maestro, cssSelector: String): ElementFilter {
        return { nodes ->
            val matchingNodes = maestro.findElementsByOnDeviceQuery(
                timeoutMs = 5000,
                query = OnDeviceElementQuery.Css(css = cssSelector),
            )?.elements?.map { it.treeNode } ?: emptyList()

            nodes.filter { node ->
                matchingNodes.any { it == node }
            }
        }
    }

}
