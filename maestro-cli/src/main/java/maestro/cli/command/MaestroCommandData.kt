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
 */

package maestro.cli.command

import com.fasterxml.jackson.annotation.JsonInclude
import maestro.TreeNode

/** Unified structure for all Maestro commands */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaestroCommandData(val event: String, val content: CommandContent) {
    companion object {
        fun createTap(
                x: Int,
                y: Int,
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "tapOn",
                    content =
                            CommandContent(
                                    point = Coordinates(x, y),
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }

        fun createTapOnId(
                id: String,
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            // Check for duplicate IDs in the hierarchy
            val duplicateCount = preEventHierarchy?.let { countElementsWithId(it, id) } ?: 0
            val index = if (duplicateCount > 1) {
                // If there are duplicates, find the index of the current element
                preEventHierarchy?.let { findElementIndex(it, id) } ?: 0
            } else {
                null
            }

            return MaestroCommandData(
                    event = "tapOn",
                    content =
                            CommandContent(
                                    id = id,
                                    index = index,
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }

        private fun countElementsWithId(node: TreeNode, targetId: String): Int {
            var count = if (node.attributes["resource-id"] == targetId) 1 else 0
            node.children.forEach { child ->
                count += countElementsWithId(child, targetId)
            }
            return count
        }

        private fun findElementIndex(node: TreeNode, targetId: String): Int {
            var index = 0
            fun traverse(current: TreeNode) {
                if (current.attributes["resource-id"] == targetId) {
                    return
                }
                index++
                current.children.forEach { traverse(it) }
            }
            traverse(node)
            return index
        }

        fun createSwipe(
                startX: Int,
                startY: Int,
                endX: Int,
                endY: Int,
                duration: Int = 300,
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "swipe",
                    content =
                            CommandContent(
                                    start = Coordinates(startX, startY),
                                    end = Coordinates(endX, endY),
                                    duration = duration,
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }

        fun createInputText(
                text: String,
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "inputText",
                    content =
                            CommandContent(
                                    text = text,
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }

        fun createEraseText(
                count: Int,
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "eraseText",
                    content =
                            CommandContent(
                                    count = count,
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }

        fun createBack(
                preEventHierarchy: TreeNode? = null,
                hierarchy: TreeNode? = null
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "back",
                    content =
                            CommandContent(
                                    preEventHierarchy =
                                            preEventHierarchy?.let {
                                                ViewHierarchyData.fromTreeNode(it)
                                            },
                                    hierarchy =
                                            hierarchy?.let { ViewHierarchyData.fromTreeNode(it) }
                            )
            )
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommandContent(
        val point: Coordinates? = null,
        val id: String? = null,
        val index: Int? = null,
        val start: Coordinates? = null,
        val end: Coordinates? = null,
        val duration: Int? = null,
        val text: String? = null,
        val count: Int? = null,
        val preEventHierarchy: ViewHierarchyData? = null,
        val hierarchy: ViewHierarchyData? = null
)

data class Coordinates(val x: Int, val y: Int)

/** Represents view hierarchy data in a JSON-serializable format */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ViewHierarchyData(
        val className: String,
        val resourceId: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val bounds: List<Int>? = null,
        val children: List<ViewHierarchyData>? = null
) {
    companion object {
        fun fromTreeNode(node: TreeNode): ViewHierarchyData {
            val children = node.children.map { fromTreeNode(it) }

            return ViewHierarchyData(
                    className = node.attributes["class"] ?: "",
                    resourceId = node.attributes["resource-id"],
                    text = node.attributes["text"],
                    contentDescription = node.attributes["content-desc"],
                    bounds = parseBounds(node.attributes["bounds"]),
                    children = children
            )
        }

        internal fun parseBounds(bounds: String?): List<Int>? {
            if (bounds == null) return null
            
            // Format is "[left,top][right,bottom]"
            val pattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
            val matchResult = pattern.matchEntire(bounds) ?: return null
            
            return listOf(
                matchResult.groupValues[1].toIntOrNull() ?: return null, // left
                matchResult.groupValues[2].toIntOrNull() ?: return null, // top
                matchResult.groupValues[3].toIntOrNull() ?: return null, // right
                matchResult.groupValues[4].toIntOrNull() ?: return null  // bottom
            )
        }
    }
}
