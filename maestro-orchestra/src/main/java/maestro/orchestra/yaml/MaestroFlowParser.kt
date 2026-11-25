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
import com.fasterxml.jackson.databind.JsonMappingException
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
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.error.SyntaxProblem
import maestro.orchestra.util.Env.EnvVariableMissingValueError
import maestro.orchestra.util.Env.withEnv
import maestro.utils.FileAccessScope
import maestro.utils.PathOutsideScope
import org.intellij.lang.annotations.Language
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap
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
//
// Internal rather than private: this map is the only record of which commands may be written as a
// bare string (`- back`), and maestro.orchestra.yaml.schema.FlowCommandSchema reads its keys so the
// published schema cannot drift from what the parser accepts.
internal val stringCommands = mapOf<String, (YamlFluentCommand) -> YamlFluentCommand>(
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
    "toggleDarkMode" to { it.copy(toggleDarkMode = YamlToggleDarkMode()) },
    "assertDarkMode" to { it.copy(assertDarkMode = YamlAssertDarkMode()) },
    "assertLightMode" to { it.copy(assertLightMode = YamlAssertLightMode()) },
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
    // A path that resolves outside the workspace is its own well-typed error; hand it
    // back as-is instead of folding it into a generic parse-failure message.
    findException<PathOutsideScope>(error)?.let { return it }
    findException<EnvVariableMissingValueError>(error)?.let { e ->
        val keys = e.keys.joinToString(", ")
        return FlowParseException(
            parser = parser,
            contentPath = contentPath,
            content = content,
            title = "Environment variable has no value: $keys",
            errorMessage = e.message ?: "Environment variable has no value",
        )
    }
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
        // A list index has no field name (`tags: [[android]]`), and a value read on its own has no path at all
        // (`pressKey: [a]`): the title names the nearest field, or none.
        val path = buildString {
            for (reference in e.path) {
                val name = reference.fieldName
                if (name == null) append("[${reference.index}]") else append(if (isEmpty()) name else ".$name")
            }
        }
        val field = e.path.lastOrNull { it.fieldName != null }?.fieldName
        return FlowParseException(
            location = e.location ?: parser.currentLocation(),
            contentPath = contentPath,
            content = content,
            title = if (field != null) "Incorrect Format: $field" else "Incorrect Format",
            errorMessage = if (path.isEmpty()) "The format is incorrect" else """
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

    fun parseFlow(flowPath: Path, flow: String, scope: FileAccessScope): List<MaestroCommand> {
        val ctx = parseContextFor(flow, flowPath)
        MAPPER.createParser(flow).use { parser ->
            try {
                val config = parseConfig(parser, ctx)
                val resolution = ResolutionContext(flowPath = flowPath, appId = config.appId, scope = scope)
                val commands = parseCommands(parser, ctx)
                val maestroCommands = commands
                    .flatMap { it.toCommands(resolution) }
                    .withEnv(config.env)
                return listOfNotNull(config.toCommand(flowPath, scope), *maestroCommands.toTypedArray())
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, flow)
            }
        }
    }

    fun parseCommand(flowPath: Path, appId: String, command: String, scope: FileAccessScope): List<MaestroCommand> {
        val ctx = parseContextFor(command, flowPath)
        MAPPER.createParser(command).use { parser ->
            try {
                val reader = MAPPER.readerFor(YamlFluentCommand::class.java)
                    .withAttribute(PARSE_CONTEXT_ATTR, ctx)
                val resolution = ResolutionContext(flowPath = flowPath, appId = appId, scope = scope)
                return reader.readValue<YamlFluentCommand>(parser).toCommands(resolution)
            } catch (e: Throwable) {
                throw wrapException(e, parser, flowPath, command)
            }
        }
    }

    fun parseConfigOnly(flowPath: Path, flow: String, scope: FileAccessScope): YamlConfig {
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

    /**
     * Every problem in [text], where [checkSyntax] throws the first. Runs the parser's own stages
     * (Jackson, `toCommands`) one top-level command at a time, so a broken command doesn't hide
     * the ones after it. Never throws for a problem in the content, a stack overflow included.
     *
     * With [snippet] the text may also be a bare command list or a single command, checked with
     * [YamlCommandReader.SNIPPET_APP_ID]. Nothing is read from [flowPath]; relative references resolve
     * beside it, so it should be absolute.
     */
    fun checkSyntaxReportingAllProblems(
        text: String,
        flowPath: Path,
        snippet: Boolean = false,
        scope: FileAccessScope = FileAccessScope.everything,
    ): List<SyntaxProblem> = try {
        AllProblemsCheck(text, flowPath, snippet, scope).run()
    } catch (e: StackOverflowError) {
        listOf(AllProblemsCheck.stackOverflow(flowPath, text, 1, 1))
    }

    /** One run of [checkSyntaxReportingAllProblems]. */
    private class AllProblemsCheck(
        val text: String,
        val flowPath: Path,
        val snippet: Boolean,
        val scope: FileAccessScope,
    ) {
        private val ctx = parseContextFor(text, flowPath)

        // The text's lines and the break after each ("" after the last), split where SnakeYAML and so Jackson
        // count a line break, so a line number from either indexes [lines].
        private val lines: List<String>
        private val breaks: List<String>

        init {
            val lines = ArrayList<String>()
            val breaks = ArrayList<String>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val breakLength = when (text[i]) {
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                    '\n', '\u0085', '\u2028', '\u2029' -> 1
                    else -> 0
                }
                if (breakLength == 0) {
                    i++
                    continue
                }
                lines += text.substring(start, i)
                breaks += text.substring(i, i + breakLength)
                i += breakLength
                start = i
            }
            lines += text.substring(start)
            breaks += ""
            this.lines = lines
            this.breaks = breaks
        }

        fun run(): List<SyntaxProblem> {
            val documents = try {
                Yaml(LoaderOptions().apply { codePointLimit = Int.MAX_VALUE }).composeAll(StringReader(text)).toList()
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                return wholeText()
            }
            val blockList = { node: Node -> node is SequenceNode && node.flowStyle == DumperOptions.FlowStyle.BLOCK }
            val perCommand = when {
                documents.size == 2 && documents[0] is MappingNode && blockList(documents[1]) ->
                    flow(documents[0], documents[1] as SequenceNode)
                snippet && documents.size == 1 && blockList(documents[0]) ->
                    commandList(documents[0] as SequenceNode, YamlCommandReader.SNIPPET_APP_ID)
                snippet && documents.size == 1 && (documents[0] is MappingNode || documents[0] is ScalarNode) ->
                    singleCommand(documents[0])
                else -> return wholeText()
            }
            // The per-command pass works on sliced copies of the text; the whole-text load is what `maestro
            // test` runs. Where they disagree on whether the text loads, the load wins, so the check never
            // fails a flow that loads or passes one that doesn't, whatever the slicing gets wrong.
            val whole = wholeText()
            return when {
                perCommand.isEmpty() -> whole
                whole.isEmpty() -> emptyList()
                else -> perCommand
            }
        }

        private fun flow(config: Node, commands: SequenceNode): List<SyntaxProblem> {
            val out = mutableListOf<SyntaxProblem>()
            var appId = YamlCommandReader.SNIPPET_APP_ID
            MAPPER.createParser(text).use { parser ->
                // The config is the item, so an error in `onFlowStart` / `onFlowComplete` is placed as a command's is.
                firstProblem(parser, config.startMark.line + 1, config.startMark.column + 1, config) {
                    val config = parseConfig(parser, ctx)
                    config.toCommand(flowPath, scope)
                    emptyList<MaestroCommand>().withEnv(config.env)
                    appId = config.appId
                }?.let { out += it }
            }
            // The config is read whole, so an error a hook command's own deserializer raises carries the position
            // after it, inside the config. When the config fails, each hook command is checked on its own lines,
            // and what they find replaces the config's one problem when it is one of theirs (the same title); a
            // problem of the config's own is kept beside them.
            if (out.isNotEmpty()) {
                val hooks = (config as? MappingNode)?.value.orEmpty()
                    .filter { (it.keyNode as? ScalarNode)?.value in listOf("onFlowStart", "onFlowComplete") }
                    .mapNotNull { (it.valueNode as? SequenceNode)?.takeIf { list -> list.flowStyle == DumperOptions.FlowStyle.BLOCK } }
                val inHooks = hooks.flatMap { commandList(it, appId, end = itemLines(it).last - 1) }
                if (inHooks.any { it.title == out.single().title }) out.clear()
                out += inHooks
            }
            return out + commandList(commands, appId)
        }

        /** Each of [commands] on its own lines; the last one's lines end at [end] (0-based), the end of the text by default. */
        private fun commandList(commands: SequenceNode, appId: String, end: Int = lines.lastIndex): List<SyntaxProblem> {
            val items = commands.value
            val starts = items.map { itemStartLine(it) }
            return items.flatMapIndexed { i, item ->
                val first = starts[i]
                val last = if (i + 1 < items.size) starts[i + 1] - 1 else end
                // Rebuilds the whole text once per item, so this is quadratic: about 4 s for one flow of 6,000
                // commands (18,000 lines) in the final review, which is why it isn't cached yet.
                val padded = lines.indices.joinToString("") { n -> (if (n in first..last) lines[n] else "") + breaks[n] }
                listOfNotNull(stages(padded, item, appId, skipReload = true) { parser -> parseCommands(parser, ctx) })
            }
        }

        private fun singleCommand(node: Node): List<SyntaxProblem> {
            return listOfNotNull(stages(text, node, YamlCommandReader.SNIPPET_APP_ID) { parser ->
                listOf(MAPPER.readerFor(YamlFluentCommand::class.java)
                    .withAttribute(PARSE_CONTEXT_ATTR, ctx)
                    .readValue<YamlFluentCommand>(parser))
            })
        }

        /**
         * Jackson, then `toCommands`, over [input]: the first problem, at [item] when it has no position inside
         * it. [skipReload] as for [firstProblem].
         */
        private fun stages(
            input: String,
            item: Node,
            appId: String,
            skipReload: Boolean = false,
            read: (JsonParser) -> List<YamlFluentCommand>,
        ): SyntaxProblem? {
            val line = item.startMark.line + 1
            val column = item.startMark.column + 1
            MAPPER.createParser(input).use { parser ->
                return firstProblem(parser, line, column, item, skipReload) {
                    val resolution = ResolutionContext(flowPath = flowPath, appId = appId, scope = scope)
                    read(parser).forEach { it.toCommands(resolution) }
                }
            }
        }

        /**
         * Runs [block] and returns the problem it throws, if any: the parser's own error, placed at
         * [line]:[column] when it has no position. A stack overflow (a `runFlow` cycle) is a problem too; any
         * other VM error is rethrown. When `wrapException` itself fails on the error, the problem is a
         * `Parsing Failed` with the original error's message, so one odd value can't end the whole check.
         *
         * With an [item], a position in this file outside the item's lines isn't trusted: some errors carry the
         * outer parser's position after the item was read, which in a sliced copy is the end of the file. It
         * goes at the key the error's path names when that is exactly one key, otherwise at the item's start.
         * With [skipReload], an error from loading this same file again from disk (a `runFlow` cycle back to
         * it) is dropped: the file's own items report it, at its real position.
         */
        private fun firstProblem(
            parser: JsonParser,
            line: Int,
            column: Int,
            item: Node? = null,
            skipReload: Boolean = false,
            block: () -> Unit,
        ): SyntaxProblem? {
            try {
                block()
                return null
            } catch (e: StackOverflowError) {
                return stackOverflow(flowPath, text, line, column)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                val wrapped = try {
                    wrapException(e, parser, flowPath, text)
                } catch (inner: VirtualMachineError) {
                    throw inner
                } catch (@Suppress("TooGenericExceptionCaught") inner: Throwable) {
                    return problem("Parsing Failed", e.message ?: e.toString(), line, column)
                }
                // A file loaded from disk comes back through readCommands, as a SyntaxError around its own error.
                val reloaded = findException<SyntaxError>(e)?.let { findException<FlowParseException>(it) }
                if (skipReload && reloaded?.contentPath == flowPath) return null
                val placed = problem(wrapped, line, column)
                if (item == null || placed.file != flowPath) return placed
                val inItem = wrapped is FlowParseException && wrapped.location.columnNr > 0 &&
                    wrapped.location.lineNr in itemLines(item)
                if (inItem) return placed
                // Jackson gives some type errors (`enabled: maybe`) no position, but a path to the field: its key
                // in the item is closer than the item's start.
                val key = findException<MismatchedInputException>(e)?.path?.let { keyAt(item, it) }
                return if (key == null) placed.copy(line = line, column = column)
                else placed.copy(line = key.startMark.line + 1, column = key.startMark.column + 1)
            }
        }

        /** The 1-based lines [item] covers: from its start to its last line that isn't blank or only a comment. */
        private fun itemLines(item: Node): IntRange {
            val start = item.startMark.line
            var end = minOf(item.endMark.line, lines.lastIndex)
            if (item.endMark.column == 0 && end > start) end--
            while (end > start && lines[end].trim().let { it.isEmpty() || it.startsWith("#") }) end--
            return (start + 1)..(end + 1)
        }

        /**
         * The key [path] leads to in [item], or null when that isn't exactly one key. Jackson's path starts at the
         * value a nested `readValue` began with (a command's options, a selector, a nested command), not at the
         * item, so it is followed from every node in the item; two matches (`enabled` in the command and in its
         * `childOf`) mean the key can't be told apart, and the caller keeps the item's start.
         */
        private fun keyAt(item: Node, path: List<JsonMappingException.Reference>): Node? {
            if (path.isEmpty() || path.last().fieldName == null) return null
            val matches = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
            val visited = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
            fun walk(node: Node) {
                if (!visited.add(node)) return // an alias can make the tree a graph
                follow(node, path, 0)?.let { matches += it }
                when (node) {
                    is MappingNode -> node.value.forEach { walk(it.valueNode) }
                    is SequenceNode -> node.value.forEach { walk(it) }
                    else -> Unit
                }
            }
            walk(item)
            return matches.singleOrNull()
        }

        /** Follows [path] from its [index]th step at [node]: the key node its last field name names, or null. */
        private fun follow(node: Node, path: List<JsonMappingException.Reference>, index: Int): Node? {
            val step = path[index]
            val name = step.fieldName
            val (key, value) = if (name != null) {
                val tuple = (node as? MappingNode)?.value
                    ?.filter { (it.keyNode as? ScalarNode)?.value == name }?.singleOrNull() ?: return null
                tuple.keyNode to tuple.valueNode
            } else {
                null to ((node as? SequenceNode)?.value?.getOrNull(step.index) ?: return null)
            }
            return if (index == path.lastIndex) key else follow(value, path, index + 1)
        }

        /**
         * The text isn't a shape that can be split into commands (malformed YAML, no config, no list, a
         * one-line list): the existing whole-text path, whose first problem is the only one.
         */
        private fun wholeText(): List<SyntaxProblem> {
            val found = MAPPER.createParser(text).use { parser ->
                firstProblem(parser, 1, 1) { if (snippet) snippetWholeText() else parseFlow(flowPath, text, scope) }
            } ?: return emptyList()
            return listOf(found)
        }

        /** The shape dispatch [checkSyntax] does, then `toCommands`. */
        private fun snippetWholeText() {
            val (node, isFlow) = MAPPER.createParser(text).use { parser ->
                val node = parser.readValueAsTree<TreeNode>()
                node to (node != null && parser.nextToken() != null)
            }
            when {
                node == null || (node.isObject && isFlow) -> parseFlow(flowPath, text, scope)
                node.isArray -> {
                    MAPPER.createParser(text).use { parser ->
                        try {
                            val resolution = ResolutionContext(flowPath, YamlCommandReader.SNIPPET_APP_ID, scope)
                            parseCommands(parser, ctx).forEach { it.toCommands(resolution) }
                        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                            throw wrapException(e, parser, flowPath, text)
                        }
                    }
                }
                else -> parseCommand(flowPath, YamlCommandReader.SNIPPET_APP_ID, text, scope)
            }
        }

        private fun itemStartLine(item: Node): Int {
            // `-` alone on a line above its command, with only blank or comment lines between: the item starts
            // at the dash.
            val line = item.startMark.line
            val before = lines.getOrNull(line)?.take(item.startMark.column)?.trim().orEmpty()
            if (before.isNotEmpty()) return line
            var above = line - 1
            while (above >= 0 && lines[above].trim().let { it.isEmpty() || it.startsWith("#") }) above--
            return if (above >= 0 && isLoneDash(lines[above], item.startMark.column)) above else line
        }

        /** [line] is a `-` with nothing after it but a comment, indented less than [column]. */
        private fun isLoneDash(line: String, column: Int): Boolean {
            val indent = line.length - line.trimStart().length
            val rest = line.trimStart().removePrefix("-").takeIf { line.trimStart().startsWith("-") } ?: return false
            val restIsComment = rest.isEmpty() || (rest[0].isWhitespace() && rest.trim().let { it.isEmpty() || it.startsWith("#") })
            return indent < column && restIsComment
        }

        /**
         * [error] as a problem. Without a position it's put at [line]:[column], or at the start of a
         * referenced file, whose text the item's position doesn't point into.
         */
        private fun problem(error: Exception, line: Int, column: Int): SyntaxProblem = when (error) {
            is FlowParseException -> {
                val inThisFile = error.contentPath == flowPath
                val positioned = error.location.lineNr > 0 && error.location.columnNr > 0
                SyntaxProblem(
                    file = error.contentPath,
                    line = if (positioned) error.location.lineNr else if (inThisFile) line else 1,
                    column = if (positioned) error.location.columnNr else if (inThisFile) column else 1,
                    code = SyntaxProblem.codeOf(error.title),
                    title = error.title,
                    message = error.errorMessage,
                    docs = error.docs,
                    source = error.content,
                )
            }
            is PathOutsideScope -> problem("Flow Path Outside Workspace", error.message ?: error.path, line, column)
            else -> problem("Parsing Failed", error.message ?: error.toString(), line, column)
        }

        private fun problem(title: String, message: String, line: Int, column: Int) =
            problem(flowPath, text, title, message, line, column)

        companion object {
            fun stackOverflow(flowPath: Path, text: String, line: Int, column: Int) =
                problem(flowPath, text, "Parsing Failed", "java.lang.StackOverflowError", line, column)

            private fun problem(file: Path, source: String, title: String, message: String, line: Int, column: Int) =
                SyntaxProblem(
                    file = file,
                    line = line,
                    column = column,
                    code = SyntaxProblem.codeOf(title),
                    title = title,
                    message = message,
                    source = source,
                )
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
