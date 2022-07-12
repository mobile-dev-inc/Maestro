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

package conductor.orchestra.yaml

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import conductor.orchestra.CommandReader
import conductor.orchestra.ConductorCommand
import okio.Source
import okio.buffer

class YamlCommandReader : CommandReader {

    private val mapper by lazy {
        ObjectMapper(YAMLFactory())
            .apply {
                registerModule(KotlinModule.Builder().build())
            }
    }

    override fun readCommands(source: Source): List<ConductorCommand> {
        val fluentCommands = try {
            mapper.readValue(
                source.buffer().inputStream(),
                object : TypeReference<List<YamlFluentCommand>>() {}
            )
        } catch (e: MismatchedInputException) {
            val message = e.message ?: throw e

            when {
                message.contains("Unrecognized field") -> throw CommandReader.SyntaxError
                message.contains("Cannot construct instance") -> throw CommandReader.SyntaxError
                message.contains("Cannot deserialize") -> throw CommandReader.SyntaxError
                message.contains("No content to map") -> throw CommandReader.NoInputException
                else -> throw e
            }
        }

        return fluentCommands
            .map { it.toCommand() }
    }
}
