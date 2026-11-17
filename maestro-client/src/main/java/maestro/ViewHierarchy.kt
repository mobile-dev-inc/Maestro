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

// Set to "true" on a node that draws its descendants only inside its bounds (an iOS scroll view)
const val CLIPS_CHILDREN_ATTRIBUTE = "clipsChildren"

// Written by filterOutOfBounds: the screen narrowed by the clipping ancestors, when they narrowed it
const val CLIP_BOUNDS_ATTRIBUTE = "clipBounds"

// Attributes that change when a node moves, not when it becomes a different node.
private val POSITION_ATTRIBUTES = setOf("bounds", CLIP_BOUNDS_ATTRIBUTE)

@JvmInline
value class ViewHierarchy(val root: TreeNode) {
    companion object {
        fun from(driver: Driver, excludeKeyboardElements: Boolean): ViewHierarchy {
            val deviceInfo = driver.deviceInfo()
            val root = driver.contentDescriptor(excludeKeyboardElements).let {
                val filtered = it.filterOutOfBounds(
                    width = deviceInfo.widthGrid,
                    height = deviceInfo.heightGrid
                )
                filtered ?: it
            }
            return ViewHierarchy(root)
        }
    }

    fun isVisible(node: TreeNode): Boolean {
        if (!node.attributes.containsKey("bounds")) {
            return false
        }

        val center = node.toUiElement().visibleBounds?.center()
            ?: return false

        val elementAtPosition = getElementAt(root, center.x, center.y)

        return node == elementAtPosition
    }

    fun refreshElement(node: TreeNode): TreeNode? {
        val matches = root.aggregate()
            .filter {
                (it.attributes - POSITION_ATTRIBUTES) == (node.attributes - POSITION_ATTRIBUTES)
            }

        if (matches.size != 1) {
            return null
        }

        return matches[0]
    }

    fun getElementAt(
        node: TreeNode,
        x: Int,
        y: Int
    ): TreeNode? {
        return node
            .children
            .asReversed()
            .asSequence()
            .mapNotNull {
                val elementWithinChild = if (it.children.isNotEmpty()) {
                    getElementAt(it, x, y)
                } else {
                    null
                }

                elementWithinChild
                    ?: if (it.attributes.containsKey("bounds")) {
                        val bounds = it.toUiElement().visibleBounds

                        if (bounds != null && bounds.contains(x, y)) {
                            it
                        } else {
                            null
                        }
                    } else {
                        null
                    }
            }
            .firstOrNull()
    }

    fun aggregate(): List<TreeNode> {
        return root.aggregate()
    }
}

fun TreeNode.filterOutOfBounds(width: Int, height: Int): TreeNode? {
    val screen = Bounds(x = 0, y = 0, width = width, height = height)
    return filterOutOfBounds(screen = screen, clip = screen)
}

// clip is null when a clipping ancestor leaves no area at all
private fun TreeNode.filterOutOfBounds(screen: Bounds, clip: Bounds?): TreeNode? {
    if (attributes.containsKey("ignoreBoundsFiltering") && attributes["ignoreBoundsFiltering"] == "true") {
        return this
    }

    // parent can have missing bounds
    val bounds = kotlin.runCatching { toUiElementOrNull()?.bounds }.getOrNull()

    val childrenClip = if (bounds != null && bounds.area() > 0 && attributes[CLIPS_CHILDREN_ATTRIBUTE] == "true") {
        clip?.intersect(bounds)
    } else {
        clip
    }

    val filtered = children.mapNotNull {
        it.filterOutOfBounds(screen, childrenClip)
    }.toList()

    val visiblePercentage = bounds?.visibleFraction(clip) ?: 0.0

    if (visiblePercentage < 0.1 && filtered.isEmpty()) {
        return null
    }

    return TreeNode(
        attributes = attributesWithClipBounds(bounds, screen, clip),
        children = filtered,
        clickable = clickable,
        enabled = enabled,
        focused = focused,
        checked = checked,
        selected = selected,
    )
}

private fun TreeNode.attributesWithClipBounds(bounds: Bounds?, screen: Bounds, clip: Bounds?): MutableMap<String, String> {
    // A driver may have filtered once already; a stale clip must not survive.
    val current = if (attributes.containsKey(CLIP_BOUNDS_ATTRIBUTE)) {
        (attributes - CLIP_BOUNDS_ATTRIBUTE).toMutableMap()
    } else {
        attributes
    }

    if (clip == screen || bounds == null || bounds.width <= 0 || bounds.height <= 0) {
        return current
    }

    val clipBounds = clip ?: Bounds(x = 0, y = 0, width = 0, height = 0)
    return (current + (CLIP_BOUNDS_ATTRIBUTE to clipBounds.toBoundsString())).toMutableMap()
}
