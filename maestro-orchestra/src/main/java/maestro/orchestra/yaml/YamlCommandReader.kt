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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.ParserErrorContext
import maestro.orchestra.error.ParserErrorRenderer
import maestro.orchestra.error.SyntaxError
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText

object YamlCommandReader {

    private val logger = LoggerFactory.getLogger(YamlCommandReader::class.java)

    // If it exists, automatically resolves the initFlow file and inlines the commands into the config
    fun readCommands(flowPath: Path): List<MaestroCommand> = mapParsingErrors(flowPath) {
        val flow = flowPath.readText()
        MaestroFlowParser.parseFlow(flowPath, flow)
    }

    fun readSingleCommand(flowPath: Path, appId: String, command: String): List<MaestroCommand> = mapParsingErrors(flowPath) {
        MaestroFlowParser.parseCommand(flowPath, appId, command)
    }

    fun readConfig(flowPath: Path) = mapParsingErrors(flowPath) {
        val flow = flowPath.readText()
        MaestroFlowParser.parseConfigOnly(flowPath, flow)
    }

    private val YAML_MAPPER by lazy { ObjectMapper(YAMLFactory()) }

    fun readWorkspaceConfig(configPath: Path): WorkspaceConfig = mapParsingErrors(configPath) {
        val config = configPath.readText()
        if (config.isBlank()) return@mapParsingErrors WorkspaceConfig()
        validateWorkspaceConfigKeys(configPath, config)
        MaestroFlowParser.parseWorkspaceConfig(configPath, config)
    }

    private fun validateWorkspaceConfigKeys(configPath: Path, config: String) {
        val unknownKeys = findUnknownWorkspaceConfigKeys(config) ?: return
        if (unknownKeys.isNotEmpty()) {
            logger.warn(
                "Config file '{}' contains unknown keys: {}. Valid keys are: {}",
                configPath.absolutePathString(),
                unknownKeys,
                WorkspaceConfig.KNOWN_KEYS.sorted(),
            )
        }
    }

    /**
     * Returns the list of top-level keys in the config that are not recognized WorkspaceConfig properties,
     * or null if the content cannot be parsed as a YAML map.
     */
    internal fun findUnknownWorkspaceConfigKeys(config: String): List<Any?>? {
        val topLevelKeys = try {
            YAML_MAPPER.readValue(config, Map::class.java)?.keys ?: return null
        } catch (e: Exception) {
            return null
        }
        return topLevelKeys.filter { it !in WorkspaceConfig.KNOWN_KEYS }
    }

    // Files to watch for changes. Includes any referenced files.
    fun getWatchFiles(flowPath: Path): List<Path> = mapParsingErrors(flowPath) {
        MaestroFlowParser.parseWatchFiles(flowPath)
    }

    fun getConfig(commands: List<MaestroCommand>): MaestroConfig? {
        val configurationCommand = commands
            .map(MaestroCommand::asCommand)
            .filterIsInstance<ApplyConfigurationCommand>()
            .firstOrNull()

        return configurationCommand?.config
    }

    fun formatCommands(commands: List<String>): String = MaestroFlowParser.formatCommands(commands)

    fun checkSyntax(maestroCode: String, flowPath: Path?) = mapParsingErrors(flowPath ?: Paths.get("/syntax-checker/")) {
        MaestroFlowParser.checkSyntax(maestroCode, flowPath)
    }

    private fun <T> mapParsingErrors(path: Path, block: () -> T): T {
        try {
            return block()
        } catch (e: VirtualMachineError) {
            // Don't attempt to pretty-format — the stack is near-exhausted and
            // would trigger kotlin.text.LinesIterator.<clinit> inside the renderer,
            // permanently wedging that class for the whole JVM.
            throw e
        } catch (e: FlowParseException) {
            throw toSyntaxError(e)
        } catch (e: Throwable) {
            val message = fallbackErrorMessage(path, e)
            throw SyntaxError(message)
        }
    }

    private fun toSyntaxError(e: FlowParseException): SyntaxError {
        val ctx = ParserErrorContext(
            title = e.title,
            filePath = e.contentPath.absolutePathString(),
            line = e.location.lineNr,
            column = e.location.columnNr,
            flowSource = e.content,
            message = e.errorMessage,
            docs = e.docs,
        )
        return SyntaxError(
            message = ParserErrorRenderer.renderSummary(ctx),
            detail = ParserErrorRenderer.renderDetail(ctx),
            cause = e,
        )
    }

    private fun fallbackErrorMessage(path: Path, e: Throwable): String {
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
