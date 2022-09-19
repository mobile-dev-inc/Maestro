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

package maestro.test.drivers

import maestro.TreeNode
import java.awt.Color
import java.awt.Graphics

data class FakeLayoutElement(
    var id: String? = null,
    var text: String? = null,
    var bounds: Bounds? = null,
    var clickable: Boolean = false,
    var color: Color = Color.BLACK,
    var onClick: (FakeLayoutElement) -> Unit = {},
    val children: MutableList<FakeLayoutElement> = mutableListOf(),
) {

    fun toTreeNode(): TreeNode {
        val attributes = mutableMapOf<String, String>()

        bounds?.let {
            attributes += "bounds" to "${it.left},${it.top},${it.right},${it.bottom}"
        }

        text?.let {
            attributes += "text" to it
        }

        id?.let {
            attributes += "resource-id" to it
        }

        return TreeNode(
            attributes = attributes,
            clickable = clickable,
            children = children.map { it.toTreeNode() }
        )
    }

    fun element(builder: FakeLayoutElement.() -> Unit): FakeLayoutElement {
        val child = FakeLayoutElement()
        child.builder()
        children.add(child)
        return child
    }

    fun draw(graphics: Graphics) {
        bounds?.let { b ->
            val previousColor = graphics.color

            graphics.color = color
            graphics.drawRect(
                b.left,
                b.top,
                b.right - b.left,
                b.bottom - b.top
            )

            text?.let {
                graphics.drawString(it, b.left, b.top)
            }

            graphics.color = previousColor
        }

        children.forEach { it.draw(graphics) }
    }

    fun dispatchClick(x: Int, y: Int): Boolean {
        children.forEach {
            if (it.dispatchClick(x, y)) {
                return true
            }
        }

        return if (bounds?.contains(x, y) == true) {
            onClick(this)
            true
        } else {
            false
        }
    }

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {

        fun contains(x: Int, y: Int): Boolean {
            return x in left..right && y in top..bottom
        }

    }

}