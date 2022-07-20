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

data class UiElement(
    val treeNode: TreeNode,
    val bounds: Bounds,
) {

    companion object {

        fun TreeNode.toUiElement(): UiElement {
            return toUiElementOrNull()
                ?: throw IllegalStateException("Node has no bounds")
        }

        fun TreeNode.toUiElementOrNull(): UiElement? {
            // TODO needs different impl for iOS
            val boundsStr = attributes["bounds"]
                ?: return null

            val boundsArr = boundsStr
                .replace("][", ",")
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.toInt() }

            return UiElement(
                this,
                Bounds(
                    x = boundsArr[0],
                    y = boundsArr[1],
                    width = boundsArr[2] - boundsArr[0],
                    height = boundsArr[3] - boundsArr[1]
                ),
            )
        }
    }
}
