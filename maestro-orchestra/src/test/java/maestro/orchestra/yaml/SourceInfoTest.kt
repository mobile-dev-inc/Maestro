package maestro.orchestra.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.SourceInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

internal class SourceInfoTest {

    @Test
    fun `attaches sourceInfo to top-level commands`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- launchApp
            |- tapOn:
            |    text: Login
            |- inputText: hello
            |- back
        """.trimMargin("|")

        val commands = parseFlow(Paths.get("/spike/flow.yaml"), yaml)
        val userCommands = commands.drop(1) // drop synthetic applyConfig

        userCommands[0].sourceInfo!!.let {
            it.assertMatches("launchApp")
            assertThat(it.path).isEqualTo("/spike/flow.yaml")
            assertThat(it.source).isEqualTo(yaml)
        }
        userCommands[1].sourceInfo!!.assertMatches("""
            |tapOn:
            |    text: Login
        """.trimMargin("|"))
        userCommands[2].sourceInfo!!.assertMatches("inputText: hello")
        userCommands[3].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `equality ignores sourceInfo`() {
        val yaml1 = "appId: com.example.app\n---\n- inputText: hello\n"
        val yaml2 = "appId: com.example.app\n---\n\n\n- inputText: hello\n"
        val a = parseFlow(Paths.get("/a.yaml"), yaml1).last()
        val b = parseFlow(Paths.get("/b.yaml"), yaml2).last()
        assertThat(a.sourceInfo!!.startLine).isNotEqualTo(b.sourceInfo!!.startLine)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    // The deserializer must use ctxt.readValue (not parser.codec.readValue) so that
    // the parse-context attribute propagates into nested YamlFluentCommand lists.
    // Without that, inner commands either lose source info or fail outright.

    @Test
    fun `propagates sourceInfo into repeat block commands`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- repeat:
            |    times: 2
            |    commands:
            |      - tapOn: foo
            |      - back
        """.trimMargin("|")

        val repeat = parseFlow(Paths.get("/a.yaml"), yaml)
            .mapNotNull { it.asCommand() as? RepeatCommand }
            .single()

        val tap = repeat.commands[0].sourceInfo!!
        tap.assertMatches("tapOn: foo")
        assertThat(tap.source).isEqualTo(yaml)

        val back = repeat.commands[1].sourceInfo!!
        back.assertMatches("back")
        assertThat(back.source).isSameInstanceAs(tap.source) // shared reference, not duplicated
    }

    @Test
    fun `propagates sourceInfo into runFlow inline commands`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- runFlow:
            |    commands:
            |      - assertTrue: ${'$'}{1 == 1}
            |      - back
        """.trimMargin("|")

        val runFlow = parseFlow(Paths.get("/a.yaml"), yaml)
            .mapNotNull { it.asCommand() as? RunFlowCommand }
            .single()

        runFlow.commands[0].sourceInfo!!.assertMatches("assertTrue: \${1 == 1}")
        runFlow.commands[1].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `propagates sourceInfo into retry block commands`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- retry:
            |    maxRetries: 3
            |    commands:
            |      - tapOn: foo
            |      - back
        """.trimMargin("|")

        val retry = parseFlow(Paths.get("/a.yaml"), yaml)
            .mapNotNull { it.asCommand() as? RetryCommand }
            .single()

        retry.commands[0].sourceInfo!!.assertMatches("tapOn: foo")
        retry.commands[1].sourceInfo!!.assertMatches("back")
    }

    // Trim heuristic: end-line walk-back is YAML-shape guesswork. The cases below
    // are most likely to silently regress if Jackson reports different end positions.

    @Test
    fun `multi-line command at end of file has correct endLine`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- tapOn:
            |    text: Login
        """.trimMargin("|")

        parseFlow(Paths.get("/a.yaml"), yaml).last().sourceInfo!!.assertMatches("""
            |tapOn:
            |    text: Login
        """.trimMargin("|"))
    }

    @Test
    fun `last command inside a repeat block has correct endLine`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- repeat:
            |    times: 2
            |    commands:
            |      - tapOn:
            |          text: Login
            |- back
        """.trimMargin("|")

        val repeat = parseFlow(Paths.get("/a.yaml"), yaml)
            .mapNotNull { it.asCommand() as? RepeatCommand }
            .single()
        repeat.commands.single().sourceInfo!!.assertMatches("""
            |tapOn:
            |          text: Login
        """.trimMargin("|"))
    }

    @Test
    fun `block scalar value containing a # line is not trimmed`() {
        // A '#' line inside a YAML block scalar is part of the value, not a YAML
        // comment. The trim heuristic must only trim outer-indent comments.
        val yaml = """
            |appId: com.example.app
            |---
            |- evalScript: |
            |    let x = 1
            |    # this is a JS comment, part of the script
            |- back
        """.trimMargin("|")

        parseFlow(Paths.get("/a.yaml"), yaml)[1].sourceInfo!!.assertMatches("""
            |evalScript: |
            |    let x = 1
            |    # this is a JS comment, part of the script
        """.trimMargin("|"))
    }

    @Test
    fun `command followed by trailing comment keeps endLine on the command`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- tapOn:
            |    text: Login
            |# trailing comment
            |- back
        """.trimMargin("|")

        parseFlow(Paths.get("/a.yaml"), yaml)[1].sourceInfo!!.assertMatches("""
            |tapOn:
            |    text: Login
        """.trimMargin("|"))
    }

    @Test
    fun `evaluateScripts preserves sourceInfo`() {
        // Orchestra calls evaluateScripts on every command before executing,
        // so if this drops sourceInfo, runtime/error consumers never see it.
        val yaml = "appId: com.example.app\n---\n- inputText: hello\n"
        val original = parseFlow(Paths.get("/a.yaml"), yaml).last()
        assertThat(original.sourceInfo).isNotNull()

        val evaluated = original.evaluateScripts(GraalJsEngine(platform = "ios"))
        assertThat(evaluated.sourceInfo).isEqualTo(original.sourceInfo)
    }

    @Test
    fun `onFlowStart hook commands carry sourceInfo`() {
        val yaml = """
            |appId: com.example.app
            |onFlowStart:
            |  - launchApp
            |  - back
            |---
            |- inputText: hello
        """.trimMargin("|")

        val commands = parseFlow(Paths.get("/a.yaml"), yaml)
        val config = (commands.first().asCommand() as ApplyConfigurationCommand).config
        val hookCommands = config.onFlowStart!!.commands

        hookCommands[0].sourceInfo!!.assertMatches("launchApp")
        hookCommands[1].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `runFlow referencing a child file uses the child's source`(@TempDir tempDir: File) {
        val parentYaml = """
            |appId: com.example.app
            |---
            |- runFlow: child.yaml
        """.trimMargin("|")
        val childYaml = """
            |appId: com.example.app
            |---
            |- inputText: from child
            |- back
        """.trimMargin("|")

        val parentFile = File(tempDir, "parent.yaml").apply { writeText(parentYaml) }
        File(tempDir, "child.yaml").writeText(childYaml)

        val runFlow = parseFlow(parentFile.toPath(), parentYaml)
            .mapNotNull { it.asCommand() as? RunFlowCommand }
            .single()

        // Inner commands of a file-referenced runFlow include the child's
        // synthetic ApplyConfigurationCommand at index 0 (no sourceInfo) followed
        // by the user's commands.
        val userCommands = runFlow.commands.filter { it.asCommand() !is ApplyConfigurationCommand }
        userCommands.forEach { cmd ->
            val info = cmd.sourceInfo!!
            assertThat(info.path).endsWith("child.yaml")
            assertThat(info.source).isEqualTo(childYaml)
        }
        userCommands[0].sourceInfo!!.assertMatches("inputText: from child")
        userCommands[1].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `outer repeat command's sourceInfo spans the entire block`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- repeat:
            |    times: 2
            |    commands:
            |      - tapOn: foo
            |      - back
            |- inputText: after
        """.trimMargin("|")

        val repeat = parseFlow(Paths.get("/a.yaml"), yaml)
            .first { it.asCommand() is RepeatCommand }
        repeat.sourceInfo!!.assertMatches("""
            |repeat:
            |    times: 2
            |    commands:
            |      - tapOn: foo
            |      - back
        """.trimMargin("|"))
    }

    @Test
    fun `outer runFlow command's sourceInfo spans the entire block`() {
        val yaml = """
            |appId: com.example.app
            |---
            |- runFlow:
            |    commands:
            |      - tapOn: foo
            |- back
        """.trimMargin("|")

        val runFlow = parseFlow(Paths.get("/a.yaml"), yaml)
            .first { it.asCommand() is RunFlowCommand }
        runFlow.sourceInfo!!.assertMatches("""
            |runFlow:
            |    commands:
            |      - tapOn: foo
        """.trimMargin("|"))
    }

    @Test
    fun `runFlow child's onFlowStart and onFlowComplete hooks carry the child file's source`(
        @TempDir tempDir: File,
    ) {
        // The parent flow references a child via `runFlow: child.yaml`. The child
        // declares its own onFlowStart/onFlowComplete hooks. Those hook commands
        // are parsed by the recursive readConfig of the child file, so their
        // sourceInfo should reference the child, not the parent.
        val parentYaml = """
            |appId: com.example.app
            |---
            |- runFlow: child.yaml
        """.trimMargin("|")
        val childYaml = """
            |appId: com.example.app
            |onFlowStart:
            |  - launchApp
            |onFlowComplete:
            |  - back
            |---
            |- inputText: from child
        """.trimMargin("|")

        val parentFile = File(tempDir, "parent.yaml").apply { writeText(parentYaml) }
        File(tempDir, "child.yaml").writeText(childYaml)

        val runFlow = parseFlow(parentFile.toPath(), parentYaml)
            .mapNotNull { it.asCommand() as? RunFlowCommand }
            .single()
        val childConfig = runFlow.config!!

        val onStart = childConfig.onFlowStart!!.commands.single().sourceInfo!!
        onStart.assertMatches("launchApp")
        assertThat(onStart.path).endsWith("child.yaml")
        assertThat(onStart.source).isEqualTo(childYaml)

        val onComplete = childConfig.onFlowComplete!!.commands.single().sourceInfo!!
        onComplete.assertMatches("back")
        assertThat(onComplete.path).endsWith("child.yaml")
        assertThat(onComplete.source).isEqualTo(childYaml)
    }

    // Line-ending handling: SourceInfo.source must be byte-identical to the
    // input regardless of separator style, and offsets must index into that
    // verbatim source. These tests pin both halves: substrings extracted via
    // (startOffset, endOffset) match, and source itself still contains the
    // original CRLF/CR bytes.

    @Test
    fun `CRLF input keeps offsets aligned with the original source`() {
        val yaml = "appId: com.example.app\r\n---\r\n- launchApp\r\n- inputText: hello\r\n- back\r\n"

        val userCommands = parseFlow(Paths.get("/a.yaml"), yaml).drop(1)

        userCommands[0].sourceInfo!!.let {
            it.assertMatches("launchApp")
            assertThat(it.source).isEqualTo(yaml) // verbatim, CRLF preserved
        }
        userCommands[1].sourceInfo!!.assertMatches("inputText: hello")
        userCommands[2].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `CR-only input keeps offsets aligned with the original source`() {
        val yaml = "appId: com.example.app\r---\r- launchApp\r- inputText: hello\r- back\r"

        val userCommands = parseFlow(Paths.get("/a.yaml"), yaml).drop(1)

        userCommands[0].sourceInfo!!.let {
            it.assertMatches("launchApp")
            assertThat(it.source).isEqualTo(yaml) // verbatim, CR preserved
        }
        userCommands[1].sourceInfo!!.assertMatches("inputText: hello")
        userCommands[2].sourceInfo!!.assertMatches("back")
    }

    @Test
    fun `sourceInfo is dropped on json round-trip`() {
        val yaml = "appId: com.example.app\n---\n- inputText: hello\n"
        val parsed = parseFlow(Paths.get("/a.yaml"), yaml).last()
        assertThat(parsed.sourceInfo).isNotNull()

        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val json = mapper.writeValueAsString(parsed)
        val roundTripped = mapper.readValue(json, MaestroCommand::class.java)

        assertThat(roundTripped.sourceInfo).isNull()
        assertThat(roundTripped).isEqualTo(parsed) // equality ignores sourceInfo
        assertThat(json).doesNotContain("sourceInfo")
        assertThat(json).doesNotContain(yaml) // origin source must not be serialized
    }
}

// Wrapper that asserts every produced command's sourceInfo (including nested ones
// inside runFlow/repeat/retry) is internally consistent. Tests should call this
// instead of MaestroFlowParser.parseFlow so consistency is checked tacitly.
private fun parseFlow(path: Path, yaml: String): List<MaestroCommand> {
    val commands = MaestroFlowParser.parseFlow(path, yaml)
    commands.forEach { it.assertSourceInfoConsistentRecursive() }
    return commands
}

private fun MaestroCommand.assertSourceInfoConsistentRecursive() {
    sourceInfo?.assertConsistent()
    when (val c = asCommand()) {
        is RepeatCommand -> c.commands.forEach { it.assertSourceInfoConsistentRecursive() }
        is RetryCommand -> c.commands.forEach { it.assertSourceInfoConsistentRecursive() }
        is RunFlowCommand -> c.commands.forEach { it.assertSourceInfoConsistentRecursive() }
        else -> Unit
    }
}

// Asserts the substring source[startOffset, endOffset) equals [expected]. Combined
// with assertConsistent (run by the parseFlow wrapper), this transitively verifies
// line/column/offset agreement: if the offsets cover the right text and offsets
// agree with line+column, then line+column also describe the right region.
private fun SourceInfo.assertMatches(expected: String) {
    assertThat(source.substring(startOffset, endOffset)).isEqualTo(expected)
}

private fun SourceInfo.assertConsistent() {
    assertThat(startLine).isAtLeast(1)
    assertThat(startColumn).isAtLeast(1)
    assertThat(endLine).isAtLeast(startLine)
    if (endLine == startLine) assertThat(endColumn).isAtLeast(startColumn)
    assertThat(startOffset).isAtMost(endOffset)
    assertThat(endOffset).isAtMost(source.length)
    // Cross-check by reconstructing (line, column) FROM the reported offset
    // using String.lines() as the trusted reference. Going offset → (line,
    // column) — the opposite direction from the production scanner — means
    // the two paths can't share the same separator-handling bug.
    assertThat(lineColumnAt(startOffset)).isEqualTo(startLine to startColumn)
    assertThat(lineColumnAt(endOffset)).isEqualTo(endLine to endColumn)
}

private fun SourceInfo.lineColumnAt(offset: Int): Pair<Int, Int> {
    val before = source.substring(0, offset.coerceIn(0, source.length))
    val precedingLines = before.lines()
    return precedingLines.size to (precedingLines.last().length + 1)
}
