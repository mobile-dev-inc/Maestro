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
    var enabled: Boolean? = true,
    var selected: Boolean? = false,
    var checked: Boolean? = false,
    var focused: Boolean? = false,
    var color: Color = Color.BLACK,
    var onClick: (FakeLayoutElement) -> Unit = {},
    val children: MutableList<FakeLayoutElement> = mutableListOf(),
    var mutatingText: (() -> String)? = null,
    var matchesCssFilter: String? = null,
) {

    fun toTreeNode(): TreeNode {
        val attributes = mutableMapOf<String, String>()

        bounds?.let {
            attributes += "bounds" to "${it.left},${it.top},${it.right},${it.bottom}"
        }

        val textNode = if (mutatingText != null) mutatingText!!() else text

        textNode?.let {
            attributes += "text" to it
        }

        id?.let {
            attributes += "resource-id" to it
        }

        enabled?.let {
            attributes += "enabled" to it.toString()
        }

        selected?.let {
            attributes += "selected" to it.toString()
        }

        checked?.let {
            attributes += "checked" to it.toString()
        }

        focused?.let {
            attributes += "focused" to it.toString()
        }

        return TreeNode(
            attributes = attributes,
            clickable = clickable,
            enabled = enabled,
            selected = selected,
            checked = checked,
            focused = focused,
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

        fun translate(x: Int = 0, y: Int = 0): Bounds {
            return Bounds(
                left = left + x,
                top = top + y,
                right = right + x,
                bottom = bottom + y,
            )
        }

        companion object {

            fun ofSize(width: Int, height: Int): Bounds {
                return Bounds(0, 0, width, height)
            }

        }

    }

}