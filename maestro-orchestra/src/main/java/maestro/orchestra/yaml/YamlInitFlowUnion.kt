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

package maestro.orchestra.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode

@JsonDeserialize(using = YamlInitFlowDeserializer::class)
sealed interface YamlInitFlowUnion

class StringInitFlow(
    val path: String
) : YamlInitFlowUnion

class YamlInitFlow(
    val commands: List<YamlFluentCommand>
) : YamlInitFlowUnion

class YamlInitFlowDeserializer : JsonDeserializer<YamlInitFlowUnion>() {

    override fun deserialize(parser: JsonParser, ctx: DeserializationContext): YamlInitFlowUnion {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        return if (root is TextNode) {
            StringInitFlow(root.textValue())
        } else {
            val commands = mapper.convertValue(root, object : TypeReference<List<YamlFluentCommand>>() {})
            YamlInitFlow(commands)
        }
    }
}
