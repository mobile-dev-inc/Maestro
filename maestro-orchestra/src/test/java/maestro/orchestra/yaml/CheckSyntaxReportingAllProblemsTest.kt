package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.orchestra.error.SyntaxProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class CheckSyntaxReportingAllProblemsTest {
    @TempDir lateinit var dir: Path
    private fun flowPath() = dir.resolve("flow.yaml")
    private fun check(yaml: String, snippet: Boolean = false) =
        YamlCommandReader.checkSyntaxReportingAllProblems(yaml.trimIndent(), flowPath(), snippet)

    /** What `maestro test` loads from [yaml], written to its own file beside the flow. */
    private fun load(yaml: String) = dir.resolve("loaded.yaml").also { it.writeText(yaml) }.let { YamlCommandReader.readCommands(it) }

    @Test fun `a clean flow has no findings`() {
        assertThat(check("appId: com.example.app\n---\n- launchApp\n- tapOn: Login")).isEmpty()
    }

    @Test fun `one finding per broken command, across all three stages`() {
        val found = check("""
            appId: com.example.app
            ---
            - tapOn:
                txt: Login
            - assertVisible:
                text: ok
                enabled: maybe
            - tapOn:
                text: x
                traits: BOGUS
            - runFlow: missing.yaml
            - back
        """)
        assertThat(found.map { it.code }).containsExactly(
            "unknown-property", "incorrect-format", "parsing-failed", "invalid-file-path",
        ).inOrder()
        // 4:5 the key; 7:5 the `enabled` key, found in the item because Jackson gives `enabled: maybe` no
        // position (-1:-1); 8:3 and 11:3 the command's own position, which toCommands errors carry.
        assertThat(found.map { it.line to it.column }).containsExactly(4 to 5, 7 to 5, 8 to 3, 11 to 3).inOrder()
        assertThat(found.map { it.title }).containsAtLeast("Incorrect Format: enabled", "Invalid File Path")
        assertThat(found.all { it.file == flowPath() }).isTrue()
    }

    @Test fun `a template expression in an Int field is a type error at the value`() {
        val found = check("appId: a\n---\n- inputRandomText:\n    length: \${LEN}\n")
        assertThat(found.single().title).isEqualTo("Incorrect Format: length")
        assertThat(found.single().line to found.single().column).isEqualTo(4 to 13)
    }

    @Test fun `a command with two unknown keys is reported once, at the first`() {
        val found = check("appId: a\n---\n- tapOn:\n    txt: Login\n    idd: b")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("4:5 Unknown Property: txt")
    }

    @Test fun `a problem in a runFlow target is attributed to the target`() {
        dir.resolve("sub.yaml").writeText("appId: a\n---\n- tapOn:\n    txt: x\n")
        val found = check("appId: a\n---\n- runFlow: sub.yaml")
        assertThat(found.single().file).isEqualTo(dir.resolve("sub.yaml"))
        assertThat(found.single().title).isEqualTo("Unknown Property: txt")
        // Jackson gives an unknown key in a selector no position, and the parent's items don't point into the
        // target's text, so through the parent it's at the target's start; the target's own check places it.
        assertThat(found.single().line to found.single().column).isEqualTo(1 to 1)
        assertThat(found.single().source).isEqualTo("appId: a\n---\n- tapOn:\n    txt: x\n")
        val own = YamlCommandReader.checkSyntaxReportingAllProblems(found.single().source, dir.resolve("sub.yaml"))
        assertThat(own.single().line to own.single().column).isEqualTo(4 to 5)
    }

    @Test fun `a broken config is reported and the commands are still checked`() {
        val found = check("name: no app id\n---\n- tapOn:\n    txt: x")
        assertThat(found.map { it.title }).containsExactly("Config Field Required", "Unknown Property: txt").inOrder()
        assertThat(found.map { it.code }).containsExactly("config-field-required", "unknown-property").inOrder()
    }

    @Test fun `a bare command list is an error as a file and fine as a snippet`() {
        assertThat(check("- launchApp").single().code).isEqualTo("config-section-required")
        assertThat(check("- launchApp", snippet = true)).isEmpty()
        assertThat(check("tapOn: Login", snippet = true)).isEmpty()
        assertThat(check("tapOn:\n  txt: Login", snippet = true).single().code).isEqualTo("unknown-property")
    }

    // Review Focus
    @Test fun `malformed YAML is one finding with a position`() {
        val found = check("appId: a\n---\n- tapOn: \"unclosed")
        assertThat(found.single().code).isEqualTo("parsing-failed")
        assertThat(found.single().line).isEqualTo(3)
    }

    @Test fun `a runFlow cycle is a finding, not a crash, and the next command is still checked`() {
        // b and c run each other. The checked flow is outside the cycle so it can carry a second, broken
        // command: any error in a file of the cycle would stop the recursion before it overflows.
        dir.resolve("b.yaml").writeText("appId: a\n---\n- runFlow: c.yaml\n")
        dir.resolve("c.yaml").writeText("appId: a\n---\n- runFlow: b.yaml\n")
        val found = check("appId: a\n---\n- runFlow: b.yaml\n- tapOn:\n    txt: x\n")
        // The loader finds a cycle by overflowing the stack, so the file and position of its finding can vary.
        assertThat(found.map { it.code }).containsExactly("parsing-failed", "unknown-property").inOrder()
        assertThat(found.last().line to found.last().column).isEqualTo(5 to 5)
    }

    @Test fun `empty, comment-only and config-only text are findings`() {
        mapOf("" to "config-section-required", "# nothing here" to "config-section-required", "appId: a" to "commands-section-required")
            .forEach { (text, code) -> assertWithMessage(text).that(check(text).map { it.code }).containsExactly(code) }
    }

    @Test fun `a one-line command list is still checked`() {
        assertThat(check("appId: a\n---\n[back, scroll]")).isEmpty()
        assertThat(check("appId: a\n---\n[back, notACommand]").single().title).isEqualTo("Invalid Command: notACommand")
    }

    @Test fun `CRLF line endings keep their positions`() {
        val found = YamlCommandReader.checkSyntaxReportingAllProblems("appId: a\r\n---\r\n- tapOn:\r\n    txt: x\r\n", flowPath())
        assertThat(found.single().line to found.single().column).isEqualTo(4 to 5)
    }

    @Test fun `what passes the check loads`() {
        val yaml = "appId: com.example.app\n---\n- launchApp\n- tapOn: Login\n"
        assertThat(YamlCommandReader.checkSyntaxReportingAllProblems(yaml, flowPath())).isEmpty()
        assertThat(load(yaml)).isNotEmpty()
    }

    // Flows that load must pass, however the per-command pass slices them (final review C1).
    private fun assertLoadsAndPasses(yaml: String) {
        assertWithMessage(yaml).that(YamlCommandReader.checkSyntaxReportingAllProblems(yaml, flowPath())).isEmpty()
        assertWithMessage(yaml).that(load(yaml)).isNotEmpty()
    }

    @Test fun `a lone dash with a comment before its command loads and passes`() {
        assertLoadsAndPasses("appId: a\n---\n- launchApp\n-\n  # why we tap\n  tapOn: ok\n- back\n")
    }

    @Test fun `a lone dash with a blank line before its command loads and passes`() {
        assertLoadsAndPasses("appId: a\n---\n- launchApp\n-\n\n  tapOn: ok\n- back\n")
    }

    @Test fun `a lone dash with a comment as the first item loads and passes`() {
        assertLoadsAndPasses("appId: a\n---\n-\n  # first\n  launchApp\n- back\n")
    }

    @Test fun `a lone dash with a comment in a snippet passes`() {
        val snippet = "-\n  # c\n  tapOn: ok\n- back\n"
        assertThat(YamlCommandReader.checkSyntaxReportingAllProblems(snippet, flowPath(), snippet = true)).isEmpty()
        assertThat(load("appId: a\n---\n$snippet")).isNotEmpty()
    }

    @Test fun `a unicode line separator in a string loads and passes`() {
        assertLoadsAndPasses("appId: a\n---\n- inputText: \"one\u2028two\"\n- tapOn: ok\n- back\n")
        assertLoadsAndPasses("appId: a\n---\n- inputText: \"one\u2029two\"\n- tapOn: ok\n- back\n")
        assertLoadsAndPasses("appId: a\n---\n- inputText: \"one\u0085two\"\n- tapOn: ok\n- back\n")
    }

    // The same inputs with a real problem after them: the load fails, so only the slicer decides what's reported.
    @Test fun `a lone dash above a comment or blank line is sliced as one item`() {
        val dashes = listOf("-", "-   # c # d")
        for ((dash, gap) in dashes.flatMap { dash -> listOf("  # why we tap", "", "  #nospace").map { dash to it } }) {
            val found = YamlCommandReader.checkSyntaxReportingAllProblems(
                "appId: a\n---\n$dash\n$gap\n  launchApp\n$dash\n$gap\n  tapOn: ok\n- tapOn:\n    txt: x\n", flowPath(),
            )
            assertWithMessage("%s / %s", dash, gap).that(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("10:5 Unknown Property: txt")
        }
    }

    @Test fun `unicode line breaks count as line breaks when slicing`() {
        for (separator in listOf("\u2028", "\u2029", "\u0085")) {
            val found = YamlCommandReader.checkSyntaxReportingAllProblems(
                "appId: a\n---\n- inputText: \"one${separator}two\"\n- tapOn: ok\n- tapOn:\n    txt: x\n", flowPath(),
            )
            // SnakeYAML counts the separator as a line break, so `txt` is on line 7.
            assertWithMessage("U+%04X".format(separator[0].code)).that(found.map { "${it.line}:${it.column} ${it.title}" })
                .containsExactly("7:5 Unknown Property: txt")
        }
    }

    // A list where a value was expected used to make wrapException itself throw (final review C2).
    @Test fun `a list where a value was expected is a finding, and the next command is still checked`() {
        val found = check("appId: a\n---\n- pressKey:\n    - a\n- tapOn:\n    txt: x\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly(
            "4:5 Incorrect Format", "6:5 Unknown Property: txt",
        ).inOrder()
    }

    @Test fun `a nested list in the config tags is a finding`() {
        val found = check("appId: a\ntags: [[android]]\n---\n- back\n")
        assertThat(found.map { it.title }).containsExactly("Incorrect Format: tags")
    }

    @Test fun `a nested list in containsDescendants is a finding`() {
        val found = check("appId: a\n---\n- tapOn:\n    containsDescendants:\n      - [x]\n")
        // The selector is read on its own, so Jackson's error names no field and has no position: the item's start.
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("3:3 Incorrect Format")
    }

    // Jackson's path for a position-less type error starts at whatever value a nested readValue began with (the
    // command's options, a selector, a nested command), so the key is used only when exactly one key in the item
    // matches the whole path; otherwise the item's start, never a valid value (fix-wave re-review).
    @Test fun `a position-less type error with two keys of its path in the item goes at the item start`() {
        val found = check("appId: a\n---\n- assertVisible:\n    childOf:\n      enabled: true\n    text: ok\n    enabled: maybe\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("3:3 Incorrect Format: enabled")
    }

    @Test fun `a position-less type error in a nested command with two keys of its path goes at the item start`() {
        val found = check(
            "appId: a\n---\n- repeat:\n    times: 2\n    commands:\n      - assertVisible:\n          enabled: true\n" +
                "      - assertVisible:\n          enabled: maybe\n",
        )
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("3:3 Incorrect Format: enabled")
    }

    @Test fun `a position-less type error in a nested selector goes at its key when it is the only one`() {
        val found = check("appId: a\n---\n- assertVisible:\n    text: ok\n    childOf:\n      enabled: maybe\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("6:7 Incorrect Format: enabled")
    }

    // Jackson places some errors at the outer parser's position after the command, which the per-command pass
    // turns into the end of the file: a position outside the item goes at its key, or the item's start.
    @Test fun `an unknown key in a command with its own deserializer goes at the command, not the end of the file`() {
        val found = check("appId: a\n---\n- launchApp\n- swipe:\n    directon: LEFT\n- tapOn:\n    txt: x\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly(
            "4:3 Parsing Failed", "7:5 Unknown Property: txt",
        ).inOrder()
    }

    @Test fun `a bad value in a command with its own deserializer goes at the command`() {
        val found = check("appId: a\n---\n- launchApp\n- swipe:\n    direction: SIDEWAYS\n\n\n# c\n- back\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("4:3 Parsing Failed")
        val orientation = check("appId: a\n---\n- launchApp\n- setOrientation:\n    orientaton: PORTRAIT\n- back\n")
        assertThat(orientation.map { "${it.line}:${it.column}" }).containsExactly("4:3")
    }

    @Test fun `an unknown key in repeat or retry goes at the key`() {
        for (command in listOf("repeat:\n    times: 2", "retry:\n    maxRetries: 1")) {
            val found = check("appId: a\n---\n- launchApp\n- $command\n    bogus: 1\n    commands:\n      - back\n- back\n")
            assertWithMessage(command).that(found.map { "${it.line}:${it.column} ${it.title}" })
                .containsExactly("6:5 Unknown Property: bogus")
        }
    }

    // A cycle loads the checked file again from disk, through the file it runs: that load's error is the checked
    // file's own, found by its own item, and isn't reported a second time at the runFlow.
    @Test fun `a runFlow cycle back to the checked file reports its problem once, at the problem`() {
        dir.resolve("c.yaml").writeText("appId: a\n---\n- runFlow: d.yaml\n")
        val d = "appId: a\n---\n- runFlow: c.yaml\n- assertVisible:\n    text: ok\n    enabled: maybe\n"
        dir.resolve("d.yaml").writeText(d)
        val found = YamlCommandReader.checkSyntaxReportingAllProblems(d, dir.resolve("d.yaml"))
        assertThat(found.map { "${it.file.fileName}:${it.line}:${it.column} ${it.title}" })
            .containsExactly("d.yaml:6:5 Incorrect Format: enabled")
    }

    @Test fun `an error in onFlowStart goes at its key`() {
        val found = check("appId: a\nonFlowStart: [ {tapOn: {id: x, enabled: maybe}} ]\n---\n- back\n")
        assertThat(found.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("2:32 Incorrect Format: enabled")
        val block = check("appId: a\nonFlowComplete:\n  - tapOn:\n      id: x\n      enabled: maybe\n---\n- back\n")
        assertThat(block.map { "${it.line}:${it.column} ${it.title}" }).containsExactly("5:7 Incorrect Format: enabled")
    }

    // The config is read whole, so an error a command's own deserializer raises in a hook lands after it: each
    // hook command is checked on its own lines instead.
    private fun found(file: String) =
        YamlCommandReader.checkSyntaxReportingAllProblems(dir.resolve(file).toFile().readText(), dir.resolve(file))
            .map { "${it.file.fileName}:${it.line}:${it.column} ${it.title}" }

    @Test fun `an error in a hook command is placed at that command`() {
        dir.resolve("s1.yaml").writeText(
            "appId: x\nonFlowStart:\n  - tapOn: \"A\"\n  - swipe:\n      direction: SIDEWAYS\n" +
                "onFlowComplete:\n  - tapOn:\n      bogus: 1\n---\n- tapOn: \"B\"\n",
        )
        assertThat(found("s1.yaml")).containsExactly("s1.yaml:4:5 Parsing Failed", "s1.yaml:8:7 Unknown Property: bogus").inOrder()
        dir.resolve("s2.yaml").writeText("appId: x\nonFlowStart:\n  - tapOn: \"A\"\n  - swipe:\n      direction: SIDEWAYS\n---\n- tapOn: \"B\"\n")
        assertThat(found("s2.yaml")).containsExactly("s2.yaml:4:5 Parsing Failed")
        dir.resolve("s4.yaml").writeText("appId: x\nonFlowStart:\n  - swipe:\n      direction: SIDEWAYS\ntags:\n  - t\n---\n- tapOn: \"B\"\n")
        assertThat(found("s4.yaml")).containsExactly("s4.yaml:3:5 Parsing Failed")
    }

    @Test fun `codes come from titles`() {
        assertThat(SyntaxProblem.codeOf("Unknown Property: txt")).isEqualTo("unknown-property")
        assertThat(SyntaxProblem.codeOf("Invalid File Path")).isEqualTo("invalid-file-path")
    }
}
