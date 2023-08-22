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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.util.Env.withEnv

object YamlCommandReader {

    val MAPPER = ObjectMapper(YAMLFactory().apply {
        disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
    }).apply {
        registerModule(KotlinModule.Builder().build())
    }

    // If it exists, automatically resolves the initFlow file and inlines the commands into the config
    fun readCommands(flowPath: Path): List<MaestroCommand> = mapParsingErrors(flowPath) {
        val (config, commands) = readConfigAndCommands(flowPath)
        val maestroCommands = commands
            .flatMap { it.toCommands(flowPath, config.appId) }
            .withEnv(config.env)

        listOfNotNull(config.toCommand(flowPath), *maestroCommands.toTypedArray())
    }

    fun readConfig(flowPath: Path): YamlConfig {
        val (config) = readConfigAndCommands(flowPath)
        return config
    }

    fun readWorkspaceConfig(configPath: Path): WorkspaceConfig = mapParsingErrors(configPath) {
        val config = configPath.readText()
        if (config.isBlank()) return@mapParsingErrors WorkspaceConfig()
        MAPPER.readValue(config, WorkspaceConfig::class.java)
    }

    // Files to watch for changes. Includes any referenced files.
    fun getWatchFiles(flowPath: Path): List<Path> = mapParsingErrors(flowPath) {
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
        val flowContent = flowPath.readText()

        // Check for sections
        var parser = MAPPER.createParser(flowContent)

        val sectionCount = parser.readValuesAs(JsonNode::class.java)
            .asSequence()
            .toList()
            .filter { !it.isNull }
            .size
        if (sectionCount != 2) {
            throw SyntaxError(
                """
                    Flow files must contain a config section and a commands section separated by "---". For example:
                    
                    appId: com.example
                    ---
                    - launchApp
                """.trimIndent()
            )
        }

        // Parse sections
        parser = MAPPER.createParser(flowContent)

        val config: YamlConfig = parser.readValueAs(YamlConfig::class.java)
        val commands = parser.readValueAs<List<YamlFluentCommand>>(
            object : TypeReference<List<YamlFluentCommand>>() {}
        )
        return config to commands
    }

    private fun <T> mapParsingErrors(path: Path, block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            val message = getErrorMessage(path, e)
            throw SyntaxError(message)
        }
    }

    private fun getErrorMessage(path: Path, e: Throwable): String {
        val prefix = "Failed to parse file: ${path.absolutePathString()}"

        val jsonException = getJsonProcessingException(e) ?: return "$prefix\n${e.message ?: e.toString()}"

        val lineNumber = jsonException.location?.lineNr ?: -1
        val originalMessage = jsonException.originalMessage

        val header = if (lineNumber != -1) "$prefix:$lineNumber" else prefix

        return "$header\n$originalMessage"
    }

    private fun getJsonProcessingException(e: Throwable): JsonProcessingException? {
        if (e is JsonProcessingException) return e
        val cause = e.cause
        if (cause == null || cause == e) return null
        return getJsonProcessingException(cause)
    }
}
