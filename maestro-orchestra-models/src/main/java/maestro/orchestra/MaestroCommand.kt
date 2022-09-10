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

package maestro.orchestra

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize

/**
 * The Mobile.dev platform uses this class in the backend and hence the custom
 * serialization logic. The earlier implementation of this class had a nullable field for
 * each command. Sometime in the future we may move this serialization logic to the backend
 * itself, where it would be more relevant.
 */
@JsonSerialize(using = MaestroCommandSerializer::class)
data class MaestroCommand(
    val command: Command?,
) {

    fun injectEnv(envParameters: Map<String, String>): MaestroCommand {
        return copy(command = command?.injectEnv(envParameters))
    }

    fun description(): String {
        return command?.description() ?: "No op"
    }
}

class MaestroCommandSerializer : JsonSerializer<MaestroCommand>() {
    override fun serialize(
        value: MaestroCommand,
        gen: JsonGenerator,
        serializers: SerializerProvider
    ) {
        val commandNameKey = value.command!!::class.java.simpleName
            .replaceFirstChar(Char::lowercaseChar)

        val commandNameKeyForServer = when (commandNameKey) {
            "tapOnElementCommand" -> "tapOnElement"
            "tapOnPointCommand" -> "tapOnPoint"
            else -> commandNameKey
        }

        with(gen) {
            writeStartObject()
            writeObjectField(commandNameKeyForServer, value.command)
            writeEndObject()
        }
    }
}
