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

import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import maestro.orchestra.MaestroCommand
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.MediaFileNotFound
import maestro.orchestra.util.Env.withEnv
import org.intellij.lang.annotations.Language
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

// TODO:
//  - Sanity check: Parsed workspace equality check for 10 users on cloud

private val yamlFluentCommandConstructor = YamlFluentCommand::class.primaryConstructor!!
private val yamlFluentCommandParameters = yamlFluentCommandConstructor.parameters
private val yamlFluentCommandLocationParameter = yamlFluentCommandParameters.first { it.name == "_location" }
private val objectCommands = yamlFluentCommandConstructor.parameters.map { it.name!! }

private val stringCommands = mapOf<String, (JsonLocation) -> YamlFluentCommand>(
    "launchApp" to { location -> YamlFluentCommand(
        _location =  location,
        launchApp = YamlLaunchApp(
            appId = null,
            clearState = null,
            clearKeychain = null,
            stopApp = null,
            permissions = null,
            arguments = null,
        ),
    )},
    "stopApp" to { location -> YamlFluentCommand(
        _location = location,
        stopApp = YamlStopApp()
    )},
    "killApp" to { location -> YamlFluentCommand(
        _location = location,
        killApp = YamlKillApp()
    )},
    "clearState" to { location -> YamlFluentCommand(
        _location = location,
        clearState = YamlClearState(
            appId = null,
        )
    )},
    "clearKeychain" to { location -> YamlFluentCommand(
        _location = location,
        clearKeychain = YamlActionClearKeychain(),
    )},
    "eraseText" to { location -> YamlFluentCommand(
        _location = location,
        eraseText = YamlEraseText(charactersToErase = null)
    )},
    "inputRandomText" to { location -> YamlFluentCommand(
        _location = location,
        inputRandomText = YamlInputRandomText(length = 8),
    )},
    "inputRandomNumber" to { location -> YamlFluentCommand(
        _location = location,
        inputRandomNumber = YamlInputRandomNumber(length = 8),
    )},
    "inputRandomEmail" to { location -> YamlFluentCommand(
        _location = location,
        inputRandomEmail = YamlInputRandomEmail(),
    )},
    "inputRandomPersonName" to { location -> YamlFluentCommand(
        _location = location,
        inputRandomPersonName = YamlInputRandomPersonName(),
    )},
    "back" to { location -> YamlFluentCommand(
        _location = location,
        back = YamlActionBack(),
    )},
    "hideKeyboard" to { location -> YamlFluentCommand(
        _location = location,
        hideKeyboard = YamlActionHideKeyboard(),
    )},
    "hide keyboard" to { location -> YamlFluentCommand(
        _location = location,
        hideKeyboard = YamlActionHideKeyboard(),
    )},
    "pasteText" to { location -> YamlFluentCommand(
        _location = location,
        pasteText = YamlActionPasteText(),
    )},
    "scroll" to { location -> YamlFluentCommand(
        _location = location,
        scroll = YamlActionScroll(),
    )},
    "waitForAnimationToEnd" to { location -> YamlFluentCommand(
        _location = location,
        waitForAnimationToEnd = YamlWaitForAnimationToEndCommand(timeout = null)
    )},
    "stopRecording" to { location -> YamlFluentCommand(
        _location = location,
        stopRecording = YamlStopRecording()
    )},
    "toggleAirplaneMode" to { location -> YamlFluentCommand(
        _location = location,
        toggleAirplaneMode = YamlToggleAirplaneMode()
    )},
    "assertNoDefectsWithAI" to { location -> YamlFluentCommand(
        _location = location,
        assertNoDefectsWithAI = YamlAssertNoDefectsWithAI()
    )},
)

private val allCommands = (stringCommands.keys + objectCommands).distinct()

private const val DOCS_FIRST_FLOW = "https://docs.maestro.dev/getting-started/writing-your-first-flow"
private const val DOCS_COMMANDS = "https://docs.maestro.dev/api-reference/commands"

private class ParseException(
    val location: JsonLocation,
    val title: String,
    @Language("markdown") val errorMessage: String,
    val docs: String? = null,
) : RuntimeException("$title: $errorMessage")

private inline fun <reified T : Throwable> findException(e: Throwable): T? {
    return findException(e, T::class.java)
}

private fun <T : Throwable> findException(e: Throwable, type: Class<T>): T? {
    return if (type.isInstance(e)) type.cast(e) else e.cause?.let { findException(it, type) }
}

private fun wrapException(error: Throwable, parser: JsonParser, contentPath: Path, content: String): Exception {
    findException<FlowParseException>(error)?.let { return it }
    findException<ToCommandsException>(error)?.let { e ->
        return when (e.cause) {
            is InvalidFlowFile -> FlowParseException(
                location = e.location,
                contentPath = contentPath,
                content = content,
                title = "Invalid File Path",
                errorMessage = e.cause.message,
            )
            is MediaFileNotFound -> FlowParseException(
                location = e.location,
                contentPath = contentPath,
                content = content,
                title = "Media File Not Found",
                errorMessage = e.cause.message,
            )
            else -> FlowParseException(
                location = e.location,
                contentPath = contentPath,
                content = content,
                title = "Parsing Failed",
                errorMessage = e.message ?: "Failed to parse content",
            )
        }
    }
    findException<ParseException>(error)?.let { e ->
        return FlowParseException(
            location = e.location,
            contentPath = contentPath,
            content = content,
            title = e.title,
            errorMessage = e.errorMessage,
            docs = e.docs
        )
    }
    findException<MissingKotlinParameterException>(error)?.let { e ->
        return FlowParseException(
            location = e.location ?: parser.currentLocation(),
            contentPath = contentPath,
            content = content,
            title = "Config Field Required: ${e.parameter.name}",
            errorMessage = """
                |The config section is missing a required field: `${e.parameter.name}`. Eg.
                |
                |```yaml
                |appId: com.example.app
                |---
                |- launchApp
                |```
            """.trimMargin("|"),
            docs = DOCS_FIRST_FLOW,
        )
    }
    findException<UnrecognizedPropertyException>(error)?.let { e ->
        val propertyName = e.path.lastOrNull()?.fieldName ?: "<unknown>"
        return FlowParseException(
            location = e.location ?: parser.currentLocation(),
            contentPath = contentPath,
            content = content,
            title = "Unknown Property: $propertyName",
            errorMessage = """
                |The property `$propertyName` is not recognized
            """.trimMargin("|"),
        )
    }
    findException<MismatchedInputException>(error)?.let { e ->
        val path = e.path.joinToString(".") { it.fieldName }
        return FlowParseException(
            location = e.location ?: parser.currentLocation(),
            contentPath = contentPath,
            content = content,
            title = "Incorrect Format: ${e.path.last().fieldName}",
            errorMessage = """
                |The format for $path is incorrect
            """.trimMargin("|"),
        )
    }
    return FlowParseException(
        parser = parser,
        contentPath = contentPath,
        content = content,
        title = "Parsing Failed",
        errorMessage = error.message ?: "Failed to parse content",
    )
}

private fun String.levenshtein(other: String): Int {
    val dp = Array(length + 1) { IntArray(other.length + 1) }
    for (i in 0..length) dp[i][0] = i
    for (j in 0..other.length) dp[0][j] = j

    for (i in 1..length)
        for (j in 1..other.length)
            dp[i][j] = if (this[i - 1] == other[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])

    return dp[length][other.length]
}

private fun String.findSimilar(others: Iterable<String>, threshold: Int) =
    others.sortedBy { levenshtein(it) }.takeWhile { it.levenshtein(this) <= threshold }

private object YamlCommandDeserializer : JsonDeserializer<YamlFluentCommand>() {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): YamlFluentCommand {
        return when (p.currentToken) {
            JsonToken.VALUE_STRING -> parseStringCommand(p)
            JsonToken.START_OBJECT -> parseObjectCommand(p)
            else -> throw ParseException(
                location = p.currentLocation(),
                title = "Invalid Command",
                errorMessage = """
                    |Invalid command format. Expected: "<commandName>: <options>" eg. "tapOn: submit"
                """.trimMargin("|"),
                docs = DOCS_COMMANDS,
            )
        }
    }

    private fun parseStringCommand(parser: JsonParser): YamlFluentCommand {
        val commandLocation = parser.currentLocation()
        val commandText = parser.text
        val command = stringCommands[commandText]
        if (command != null) return command(parser.currentLocation())
        if (commandText in objectCommands) {
            throw ParseException(
                location = commandLocation,
                title = "Missing Command Options",
                errorMessage = """
                    |The command `$commandText` requires additional options.
                """.trimMargin("|"),
                // TODO: Add docs link
            )
        }
        throw ParseException(
            location = commandLocation,
            title = "Invalid Command: $commandText",
            errorMessage = """
                |`$commandText` is not a valid command.
                |
                |${suggestCommandMessage(commandText)}
            """.trimMargin("|").trim(),
            docs = DOCS_COMMANDS,
        )
    }

    private fun parseObjectCommand(parser: JsonParser): YamlFluentCommand {
        val commandLocation = parser.currentLocation()
        val commandName = parser.nextFieldName()
        val commandParameter = yamlFluentCommandParameters.firstOrNull { it.name == commandName }
        if (commandParameter == null) {
            throw ParseException(
                location = parser.currentLocation(),
                title = "Invalid Command: $commandName",
                errorMessage = """
                    |`$commandName` is not a valid command.
                    |
                    |${suggestCommandMessage(commandName)}
                """.trimMargin("|").trim(),
            )
        }
        if (parser.nextToken() == JsonToken.VALUE_NULL) {
            throw ParseException(
                location = parser.currentLocation(),
                title = "Incorrect Command Format: $commandName",
                errorMessage = """
                    |The command `$commandName` requires additional options.
                """.trimMargin("|"),
            )
        }
        val commandType = (parser.codec as ObjectMapper).constructType(commandParameter.type.javaType)
        val command = parser.codec.readValue<Any>(parser, commandType)
        val fluentCommand = yamlFluentCommandConstructor.callBy(mapOf(
            yamlFluentCommandLocationParameter to commandLocation,
            commandParameter to command,
        ))

        val nextToken = parser.nextToken()
        if (nextToken == JsonToken.END_OBJECT) return fluentCommand

        if (nextToken == JsonToken.FIELD_NAME) {
            val fieldName = parser.currentName()
            throw ParseException(
                location = parser.currentLocation(),
                title = "Invalid Command Format: $commandName",
                errorMessage = """
                    |Found unexpected top-level field: `$fieldName`. Missing an indent or dash?
                    |
                    |Example of correctly formatted list of commands:
                    |```yaml
                    |- tapOn:
                    |    text: submit
                    |    optional: true
                    |- inputText: hello
                    |```
                """.trimMargin("|"),
            )
        }
        throw ParseException(
            location = commandLocation,
            title = "Invalid Command Format: $commandName",
            errorMessage = """
                |Commands must be in the format: `<commandName>: <options>` eg. `tapOn: submit`
                |
                |Example of correctly formatted list of commands:
                |```yaml
                |- tapOn:
                |    text: submit
                |    optional: true
                |- inputText: hello
                |```
            """.trimMargin("|"),
        )
    }

    private fun suggestCommandMessage(invalidCommand: String): String {
        val prefixCommands = if (invalidCommand.length < 3) emptyList() else allCommands.filter { it.startsWith(invalidCommand) || invalidCommand.startsWith(it) }
        val substringCommmands = if (invalidCommand.length < 3) emptyList() else allCommands.filter { it.contains(invalidCommand) || invalidCommand.contains(it) }
        val similarCommands = invalidCommand.findSimilar(allCommands, threshold = 3)
        val suggestions = (prefixCommands + similarCommands + substringCommmands).distinct()
        return when {
            suggestions.isEmpty() -> ""
            suggestions.size == 1 -> "Did you mean `${suggestions.first()}`?"
            else -> "Did you mean one of: ${suggestions.joinToString(", ")}"
        }
    }
}

class FlowParseException(
    location: JsonLocation,
    val contentPath: Path,
    val content: String,
    val title: String,
    @Language("markdown") val errorMessage: String,
    val docs: String? = null,
) : JsonProcessingException("$title\n$errorMessage", location) {
    constructor(parser: JsonParser, contentPath: Path, content: String, title: String, @Language("markdown") errorMessage: String, docs: String? = null) : this(
        location = parser.currentLocation(),
        contentPath = contentPath,
        content = content,
        title = title,
        errorMessage = errorMessage,
        docs = docs,
    )
}

object MaestroFlowParser {

    private val MAPPER = ObjectMapper(YAMLFactory().apply {
        disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
    }).apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(SimpleModule().apply {
            addDeserializer(YamlFluentCommand::class.java, YamlCommandDeserializer)
        })
    }

    fun parseFlow(flowPath: Path, flow: String): List<MaestroCommand> {
        MAPPER.createParser(flow).use { parser ->
            try {
                val config = parseConfig(parser)
                val commands = parseCommands(parser)
                val maestroCommands = commands
                    .flatMap { it.toCommands(flowPath, config.appId) }
                    .withEnv(config.env)
                return listOfNotNull(config.toCommand(flowPath), *maestroCommands.toTypedArray())
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    fun parseCommand(flowPath: Path, appId: String, command: String): List<MaestroCommand> {
        val flow = flowPath.readText()
        MAPPER.createParser(flow).use { parser ->
            try {
                return MAPPER.readValue(command, YamlFluentCommand::class.java).toCommands(flowPath, appId)
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    fun parseConfigOnly(flowPath: Path, flow: String): YamlConfig {
        MAPPER.createParser(flow).use { parser ->
            try {
                return parseConfig(parser)
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    fun parseWorkspaceConfig(configPath: Path, workspaceConfig: String): WorkspaceConfig {
        MAPPER.createParser(workspaceConfig).use { parser ->
            try {
                return parser.readValueAs(WorkspaceConfig::class.java)
            } catch (e: Throwable) {
                throw wrapException(e, parser, configPath, workspaceConfig)
            }
        }
    }

    fun parseWatchFiles(flowPath: Path): List<Path> {
        val flow = flowPath.readText()
        MAPPER.createParser(flow).use { parser ->
            try {
                parseConfig(parser)
                val commands = parseCommands(parser)
                val commandWatchFiles = commands.flatMap { it.getWatchFiles(flowPath) }
                return (listOf(flowPath) + commandWatchFiles)
                    .filter { it.absolute().parent?.isDirectory() ?: false }
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    fun formatCommands(commands: List<String>): String {
        return MAPPER.writeValueAsString(commands.map { MAPPER.readTree(it) })
    }

    private fun parseCommands(parser: JsonParser): List<YamlFluentCommand> {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw ParseException(
                location = parser.currentLocation(),
                title = "Commands Section Required",
                errorMessage = """
                    |Flow files must have a list of commands after the config section. Eg:
                    |
                    |```yaml
                    |appId: com.example.app
                    |---
                    |- launchApp
                    |```
                """.trimMargin("|"),
                docs = DOCS_FIRST_FLOW,
            )
        }

        val commands = mutableListOf<YamlFluentCommand>()
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            val command = parser.readValueAs(YamlFluentCommand::class.java)
            commands.add(command)
        }
        return commands
    }

    private fun parseConfig(parser: JsonParser): YamlConfig {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw ParseException(
                location = parser.currentLocation(),
                title = "Config Section Required",
                errorMessage = """
                    |Flow files must start with a config section. Eg:
                    |
                    |```yaml
                    |appId: com.example.app # <-- config section
                    |---
                    |- launchApp
                    |```
                """.trimMargin("|"),
                docs = DOCS_FIRST_FLOW,
            )
        }

        return parser.readValueAs(YamlConfig::class.java)
    }
}