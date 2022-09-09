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

@JvmInline
value class ViewHierarchy(val root: TreeNode) {
    companion object {
        fun from(driver: Driver): ViewHierarchy =
            ViewHierarchy(driver.contentDescriptor())
    }

    fun isVisible(node: TreeNode): Boolean {
        if (!node.attributes.containsKey("bounds")) {
            return false
        }

        val center = node.toUiElement().bounds.center()

        val elementAtPosition = getElementAt(root, center.x, center.y)

        return node == elementAtPosition
    }

    fun refreshElement(node: TreeNode): TreeNode? {
        val matches = root.aggregate()
            .filter {
                (it.attributes - "bounds") == (node.attributes - "bounds")
            }

        if (matches.size != 1) {
            return null
        }

        return matches[0]
    }

    private fun getElementAt(
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
                        val bounds = it.toUiElement().bounds

                        if (bounds.contains(x, y)) {
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
