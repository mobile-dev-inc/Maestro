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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.error.NoInputException
import maestro.orchestra.error.SyntaxError
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory

object YamlCommandReader {

    private val YAML = YAMLFactory()

    private val MAPPER = ObjectMapper(YAML).apply {
        registerModule(KotlinModule.Builder().build())
    }

    // If it exists, automatically resolves the initFlow file and inlines the commands into the config
    fun readCommands(flowPath: Path): List<MaestroCommand> = mapParsingErrors {
        val (config, commands) = readConfigAndCommands(flowPath)
        val maestroCommands = commands
            .flatMap { it.toCommands(flowPath, config.appId) }
            .map { it.injectEnv(config.env) }

        listOfNotNull(config.toCommand(flowPath), *maestroCommands.toTypedArray())
    }

    // Files to watch for changes. Includes any referenced files.
    fun getWatchFiles(flowPath: Path): List<Path> = mapParsingErrors {
        val (config, commands) = readConfigAndCommands(flowPath)
        val configWatchFiles = config.getWatchFiles(flowPath)
        val commandWatchFiles = commands.flatMap { it.getWatchFiles(flowPath) }
        (listOf(flowPath) + configWatchFiles + commandWatchFiles)
            .filter { it.absolute().parent?.isDirectory() ?: false }
    }

    fun getConfig(commands: List<MaestroCommand>): MaestroConfig? {
        val configurationCommand = commands
            .map(MaestroCommand::asCommand)
            .filterIsInstance<ApplyConfigurationCommand>()
            .firstOrNull()

        return configurationCommand?.config
    }

    private fun readConfigAndCommands(flowPath: Path): Pair<YamlConfig, List<YamlFluentCommand>> {
        val parser = YAML.createParser(flowPath.inputStream())
        val nodes = parser.readValuesAs(JsonNode::class.java)
            .asSequence()
            .toList()
            .filter { !it.isNull }
        if (nodes.size != 2) {
            throw SyntaxError(
                "Flow files must contain a config section and a commands section. " +
                    "Found ${nodes.size} section${if (nodes.size == 1) "" else "s"}: $flowPath"
            )
        }
        val config: YamlConfig = MAPPER.convertValue(nodes[0], YamlConfig::class.java)
        val commands = MAPPER.convertValue(
            nodes[1],
            object : TypeReference<List<YamlFluentCommand>>() {}
        )
        return config to commands
    }

    private fun <T> mapParsingErrors(block: () -> T): T {
        try {
            return block()
        } catch (e: JsonMappingException) {
            val message = e.message ?: throw SyntaxError("Invalid syntax")

            when {
                message.contains("Unrecognized field") -> throw SyntaxError(message)
                message.contains("Cannot construct instance") -> throw SyntaxError(message)
                message.contains("Cannot deserialize") -> throw SyntaxError(message)
                message.contains("No content to map") -> throw NoInputException
                else -> throw SyntaxError(message)
            }
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: throw e

            when {
                message.contains("value failed for JSON property") -> throw SyntaxError(message)
                message.contains("Unrecognized field") -> throw SyntaxError(message)
                message.contains("Cannot construct instance") -> throw SyntaxError(message)
                message.contains("Cannot deserialize") -> throw SyntaxError(message)
                else -> throw e
            }
        }
    }
}
