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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.NoInputException
import maestro.orchestra.SyntaxError
import okio.Source
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.File

object YamlCommandReader {

    private val mapper by lazy {
        ObjectMapper(YAMLFactory())
            .apply {
                registerModule(KotlinModule.Builder().build())
            }
    }

    // If it exists, automatically resolves the initFlow file and inlines the commands into the config
    fun readCommands(flowFile: File): List<MaestroCommand> {
        val commands = flowFile.source().use { source ->
            readCommandsRaw(source)
        }
        return resolveInitFlowCommands(flowFile, commands)
    }

    // Parse out the init flow commands from the config. Assumes that initFlow commands are inlined into the config
    fun parseInitFlowCommands(commands: List<MaestroCommand>): List<MaestroCommand> {
        val config = getConfig(commands) ?: return emptyList()
        val initFlow = config["initFlow"] ?: return emptyList()
        if (initFlow is String) throw IllegalArgumentException("initFlow file references aren't supported by this method")
        val initFlowBytes = mapper.writeValueAsBytes(initFlow)
        val source = ByteArrayInputStream(initFlowBytes).source()
        return readCommandsRaw(source)
    }

    // Files to watch for changes. Includes any referenced files.
    fun getWatchFiles(flowFile: File): List<File> {
        return listOfNotNull(
            flowFile,
            getInitFlowFile(flowFile)
        ).filter { it.parentFile.isDirectory }
    }

    // Parses the commands as-is
    private fun readCommandsRaw(source: Source): List<MaestroCommand> {
        return mapParsingErrors {
            mapper.readValue(
                source.buffer().inputStream(),
                object : TypeReference<List<YamlFluentCommand>>() {}
            ).map { it.toCommand() }
        }
    }

    private fun resolveInitFlowCommands(flowFile: File, commands: List<MaestroCommand>): List<MaestroCommand> {
        val config = getConfig(commands) ?: return commands

        val initFlow = config["initFlow"] ?: return commands
        if (initFlow !is String) return commands

        val initFlowFile = getInitFlowFile(flowFile, initFlow)
        val initCommands = mapParsingErrors {
            mapper.readValue(initFlowFile, object : TypeReference<List<*>>() {})
        }

        val newConfig = config.mapValues { (key, value) ->
            if (key == "initFlow") {
                initCommands
            } else {
                value
            }
        }

        return commands.map { command ->
            if (command.applyConfigurationCommand == null) {
                command
            } else {
                MaestroCommand(
                    applyConfigurationCommand = ApplyConfigurationCommand(newConfig)
                )
            }
        }
    }

    private fun getInitFlowFile(flowFile: File): File? {
        val commands = flowFile.source().use {
            readCommandsRaw(it)
        }
        val config = getConfig(commands) ?: return null
        val initFlow = config["initFlow"]
        if (initFlow !is String) return null
        return getInitFlowFile(flowFile, initFlow)
    }

    private fun getInitFlowFile(flowFile: File, initFlow: String): File {
        val initFlowFile = File(initFlow)
        val absoluteInitFlowFile = if (initFlowFile.isAbsolute) {
            initFlowFile
        } else {
            flowFile.parentFile.resolve(initFlowFile)
        }
        if (!absoluteInitFlowFile.exists() || absoluteInitFlowFile.isDirectory) {
            throw InvalidInitFlowFile(absoluteInitFlowFile)
        }
        return absoluteInitFlowFile
    }

    private fun getConfig(commands: List<MaestroCommand>): Map<String, *>? {
        return commands.firstNotNullOfOrNull { it.applyConfigurationCommand }?.config
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
        }
    }
}
