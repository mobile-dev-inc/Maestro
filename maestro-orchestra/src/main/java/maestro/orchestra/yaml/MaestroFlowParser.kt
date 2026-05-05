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
import com.fasterxml.jackson.core.TreeNode
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
import maestro.orchestra.SourceInfo
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.MediaFileNotFound
import maestro.orchestra.util.Env.withEnv
import org.intellij.lang.annotations.Language
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

// TODO:
//  - Sanity check: Parsed workspace equality check for 10 users on cloud

private val yamlFluentCommandConstructor = YamlFluentCommand::class.primaryConstructor!!
private val yamlFluentCommandParameters = yamlFluentCommandConstructor.parameters
private val yamlFluentCommandSourceInfoParameter = yamlFluentCommandParameters.first { it.name == "_sourceInfo" }
private val objectCommands = yamlFluentCommandConstructor.parameters
    .filter { it.name != "_sourceInfo" }
    .map { it.name!! }

private const val PARSE_CONTEXT_ATTR = "maestroParseContext"

// Per-parse cache so per-command provenance is O(1) instead of re-tokenizing
// the full YAML for every command. Offsets reference [source] verbatim — no
// normalization — so SourceInfo offsets stay aligned with whatever was on disk.
private class ParseContext(val source: String, val path: String?) {
    val lines: List<String> = source.lines()
    private val lineStarts: IntArray = computeLineStarts(source)

    fun offsetOf(line: Int, column: Int): Int {
        val idx = (line - 1).coerceIn(0, lineStarts.size - 1)
        return (lineStarts[idx] + (column - 1).coerceAtLeast(0)).coerceAtMost(source.length)
    }
}

// Returns the offset of the first character of each line, mirroring how
// String.lines() splits on CRLF, CR, or LF. Result size always equals
// source.lines().size, so lineStarts[i] is the offset of the i-th line.
private fun computeLineStarts(source: String): IntArray {
    val starts = mutableListOf(0)
    var i = 0
    while (i < source.length) {
        when (source[i]) {
            '\r' -> {
                i++
                if (i < source.length && source[i] == '\n') i++ // CRLF
                starts.add(i)
            }
            '\n' -> {
                i++
                starts.add(i)
            }
            else -> i++
        }
    }
    return starts.toIntArray()
}

private fun buildSourceInfo(ctx: ParseContext, start: JsonLocation, end: JsonLocation): SourceInfo {
    val startLine = start.lineNr.coerceAtLeast(1)
    val startColumn = start.columnNr.coerceAtLeast(1)
    var endLine = end.lineNr.coerceAtLeast(startLine)
    // YAML has no closing brace, so Jackson reports END_OBJECT on the next
    // sibling's line. Walk back past blank lines, comments, and
    // same-or-less-indented `- ` list items to recover the true end of this command.
    val startIndent = ctx.lines.getOrNull(startLine - 1)?.indentWidth() ?: 0
    while (endLine > startLine) {
        val raw = ctx.lines.getOrNull(endLine - 1) ?: break
        val indent = raw.indentWidth()
        val trimmed = raw.trimStart()
        val isBlank = trimmed.isEmpty()
        val isOuter = indent <= startIndent
        val isComment = isOuter && trimmed.startsWith("#")
        val isSiblingListItem = isOuter && (trimmed.startsWith("- ") || trimmed == "-")
        if (isBlank || isComment || isSiblingListItem) endLine-- else break
    }
    val endColumn = (ctx.lines.getOrNull(endLine - 1)?.length ?: 0) + 1
    return SourceInfo(
        source = ctx.source,
        path = ctx.path,
        startLine = startLine,
        startColumn = startColumn,
        startOffset = ctx.offsetOf(startLine, startColumn),
        endLine = endLine,
        endColumn = endColumn,
        endOffset = ctx.offsetOf(endLine, endColumn),
    )
}

private fun String.indentWidth(): Int {
    var n = 0
    while (n < length && this[n] == ' ') n++
    return n
}

// Each lambda receives a YamlFluentCommand pre-populated with sourceInfo, and
// fills in the matching command field via copy().
private val stringCommands = mapOf<String, (YamlFluentCommand) -> YamlFluentCommand>(
    "launchApp" to { it.copy(launchApp = YamlLaunchApp(
        appId = null,
        clearState = null,
        clearKeychain = null,
        stopApp = null,
        permissions = null,
        arguments = null,
    )) },
    "stopApp" to { it.copy(stopApp = YamlStopApp()) },
    "killApp" to { it.copy(killApp = YamlKillApp()) },
    "clearState" to { it.copy(clearState = YamlClearState(appId = null)) },
    "clearKeychain" to { it.copy(clearKeychain = YamlActionClearKeychain()) },
    "eraseText" to { it.copy(eraseText = YamlEraseText(charactersToErase = null)) },
    "inputRandomText" to { it.copy(inputRandomText = YamlInputRandomText(length = 8)) },
    "inputRandomNumber" to { it.copy(inputRandomNumber = YamlInputRandomNumber(length = 8)) },
    "inputRandomEmail" to { it.copy(inputRandomEmail = YamlInputRandomEmail()) },
    "inputRandomPersonName" to { it.copy(inputRandomPersonName = YamlInputRandomPersonName()) },
    "inputRandomCityName" to { it.copy(inputRandomCityName = YamlInputRandomCityName()) },
    "inputRandomCountryName" to { it.copy(inputRandomCountryName = YamlInputRandomCountryName()) },
    "inputRandomColorName" to { it.copy(inputRandomColorName = YamlInputRandomColorName()) },
    "back" to { it.copy(back = YamlActionBack()) },
    "hideKeyboard" to { it.copy(hideKeyboard = YamlActionHideKeyboard()) },
    "hide keyboard" to { it.copy(hideKeyboard = YamlActionHideKeyboard()) },
    "pasteText" to { it.copy(pasteText = YamlActionPasteText()) },
    "scroll" to { it.copy(scroll = YamlActionScroll()) },
    "waitForAnimationToEnd" to { it.copy(waitForAnimationToEnd = YamlWaitForAnimationToEndCommand(timeout = null)) },
    "stopRecording" to { it.copy(stopRecording = YamlStopRecording()) },
    "toggleAirplaneMode" to { it.copy(toggleAirplaneMode = YamlToggleAirplaneMode()) },
    "assertNoDefectsWithAI" to { it.copy(assertNoDefectsWithAI = YamlAssertNoDefectsWithAI()) },
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

@Suppress("DEPRECATION")
private fun SourceInfo.asJsonLocation(): JsonLocation =
    JsonLocation(path, startOffset.toLong(), startLine, startColumn)

private fun wrapException(error: Throwable, parser: JsonParser, contentPath: Path, content: String): Exception {
    findException<FlowParseException>(error)?.let { return it }
    findException<ToCommandsException>(error)?.let { e ->
        val location = e.sourceInfo.asJsonLocation()
        return when (e.cause) {
            is InvalidFlowFile -> FlowParseException(
                location = location,
                contentPath = contentPath,
                content = content,
                title = "Invalid File Path",
                errorMessage = e.cause.message,
            )
            is MediaFileNotFound -> FlowParseException(
                location = location,
                contentPath = contentPath,
                content = content,
                title = "Media File Not Found",
                errorMessage = e.cause.message,
            )
            else -> FlowParseException(
                location = location,
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
    findException<ConfigParseError>(error)?.let { e ->
        return when (e.errorType) {
            "missing_app_target" -> FlowParseException(
                location = e.location ?: parser.currentLocation(),
                contentPath = contentPath,
                content = content,
                title = "Config Field Required",
                errorMessage = """
                    |Either 'url' or 'appId' must be specified in the config section.
                    |
                    |For mobile apps, use:
                    |```yaml
                    |appId: com.example.app
                    |---
                    |- launchApp
                    |```
                    |
                    |For web apps, use:
                    |```yaml
                    |url: https://example.com
                    |---
                    |- launchApp
                    |```
                """.trimMargin("|"),
                docs = DOCS_FIRST_FLOW,
            )
            else -> FlowParseException(
                location = e.location ?: parser.currentLocation(),
                contentPath = contentPath,
                content = content,
                title = "Config Parse Error",
                errorMessage = "Unknown config validation error: ${e.errorType}",
            )
        }
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
        val parseCtx = requireNotNull(ctxt.getAttribute(PARSE_CONTEXT_ATTR) as? ParseContext) {
            "YamlFluentCommand must be read via an ObjectReader with $PARSE_CONTEXT_ATTR set"
        }
        return when (p.currentToken) {
            JsonToken.VALUE_STRING -> parseStringCommand(p, parseCtx)
            JsonToken.START_OBJECT -> parseObjectCommand(p, ctxt, parseCtx)
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

    private fun parseStringCommand(parser: JsonParser, ctx: ParseContext): YamlFluentCommand {
        // tokenLocation = start of the current token; currentLocation = position
        // after it. For VALUE_STRING tokens these differ — using both gives the
        // full scalar range.
        val commandLocation = parser.currentTokenLocation()
        val commandText = parser.text
        val command = stringCommands[commandText]
        if (command != null) {
            val end = parser.currentLocation()
            val base = YamlFluentCommand(_sourceInfo = buildSourceInfo(ctx, commandLocation, end))
            return command(base)
        }
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

    private fun parseObjectCommand(parser: JsonParser, ctxt: DeserializationContext, ctx: ParseContext): YamlFluentCommand {
        val commandLocation = parser.currentTokenLocation()
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
        // Use ctxt.readValue (not parser.codec.readValue) so context attributes
        // propagate into nested YamlFluentCommand deserializations (e.g.
        // YamlRunFlow.commands lists).
        val command = ctxt.readValue<Any>(parser, commandType)

        val nextToken = parser.nextToken()
        if (nextToken == JsonToken.END_OBJECT) {
            val endLocation = parser.currentLocation()
            return yamlFluentCommandConstructor.callBy(mapOf(
                yamlFluentCommandSourceInfoParameter to buildSourceInfo(ctx, commandLocation, endLocation),
                commandParameter to command,
            ))
        }

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
        val substringCommands = if (invalidCommand.length < 3) emptyList() else allCommands.filter { it.contains(invalidCommand) || invalidCommand.contains(it) }
        val similarCommands = invalidCommand.findSimilar(allCommands, threshold = 3)
        val suggestions = (prefixCommands + similarCommands + substringCommands).distinct()
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
        val ctx = parseContextFor(flow, flowPath)
        MAPPER.createParser(flow).use { parser ->
            try {
                val config = parseConfig(parser, ctx)
                val commands = parseCommands(parser, ctx)
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
        val ctx = parseContextFor(command, flowPath)
        MAPPER.createParser(command).use { parser ->
            try {
                val reader = MAPPER.readerFor(YamlFluentCommand::class.java)
                    .withAttribute(PARSE_CONTEXT_ATTR, ctx)
                return reader.readValue<YamlFluentCommand>(parser).toCommands(flowPath, appId)
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, command)
            }
        }
    }

    fun parseConfigOnly(flowPath: Path, flow: String): YamlConfig {
        val ctx = parseContextFor(flow, flowPath)
        MAPPER.createParser(flow).use { parser ->
            try {
                return parseConfig(parser, ctx)
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    private fun parseContextFor(source: String, path: Path?): ParseContext =
        ParseContext(source = source, path = path?.absolute()?.toString())

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
        val ctx = parseContextFor(flow, flowPath)
        MAPPER.createParser(flow).use { parser ->
            try {
                parseConfig(parser, ctx)
                val commands = parseCommands(parser, ctx)
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

    fun checkSyntax(maestroCode: String, flowPath: Path?) {
        MAPPER.createParser(maestroCode).use { parser ->
            val node = parser.readValueAsTree<TreeNode>()
            if (node.isArray) {
                checkCommandListSyntax(maestroCode, flowPath)
            } else if (node.isObject && parser.nextToken() != null) {
                checkFlowSyntax(maestroCode, flowPath)
            } else {
                checkCommandSyntax(maestroCode, flowPath)
            }
        }
    }

    private fun checkCommandListSyntax(maestroCode: String, flowPath: Path?) {
        val ctx = parseContextFor(maestroCode, flowPath)
        MAPPER.createParser(maestroCode).use { parser ->
            try {
                parseCommands(parser, ctx)
            } catch (e: Throwable) {
                throw wrapException(e, parser, Paths.get("/syntax-checker"), maestroCode)
            }
        }
    }

    private fun checkCommandSyntax(command: String, flowPath: Path?) {
        val ctx = parseContextFor(command, flowPath)
        MAPPER.createParser(command).use { parser ->
            try {
                MAPPER.readerFor(YamlFluentCommand::class.java)
                    .withAttribute(PARSE_CONTEXT_ATTR, ctx)
                    .readValue<YamlFluentCommand>(parser)
            } catch (e: Throwable) {
                throw wrapException(e, parser, Paths.get("/syntax-checker"), command)
            }
        }
    }

    private fun checkFlowSyntax(flow: String, flowPath: Path?) {
        val ctx = parseContextFor(flow, flowPath)
        MAPPER.createParser(flow).use { parser ->
            try {
                parseConfig(parser, ctx)
                parseCommands(parser, ctx)
            } catch (e: Throwable) {
                throw wrapException(e, parser, Paths.get("/syntax-checker"), flow)
            }
        }
    }

    private fun parseCommands(parser: JsonParser, ctx: ParseContext): List<YamlFluentCommand> {
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

        val reader = (parser.codec as ObjectMapper).readerFor(YamlFluentCommand::class.java)
            .withAttribute(PARSE_CONTEXT_ATTR, ctx)
        val commands = mutableListOf<YamlFluentCommand>()
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            val command = reader.readValue<YamlFluentCommand>(parser)
            commands.add(command)
        }
        return commands
    }

    private fun parseConfig(parser: JsonParser, ctx: ParseContext): YamlConfig {
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

        val reader = (parser.codec as ObjectMapper).readerFor(YamlConfig::class.java)
            .withAttribute(PARSE_CONTEXT_ATTR, ctx)
        return reader.readValue<YamlConfig>(parser)
    }
}
