package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CheckSyntaxCommandTest {

    @TempDir lateinit var dir: Path
    private fun write(name: String, text: String) = dir.resolve(name).writeText(text)

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, stdin: String = "", configure: (CheckSyntaxCommand) -> Unit = {}): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            val command = CheckSyntaxCommand().also { it.workingDir = dir; it.stdin = { stdin }; configure(it) }
            val code = CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true).execute(*args)
            return Run(code, out.toString(), err.toString())
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
    }

    private val clean = "appId: com.example.app\n---\n- launchApp\n"
    private val broken = "appId: com.example.app\n---\n- tapOn:\n    txt: Login\n- swipe:\n    dirction: UP\n"

    @Test fun `clean files exit 0 with only the summary`() {
        write("a.yaml", clean); write("b.yaml", clean)
        val (code, out) = run("a.yaml", "b.yaml")
        assertThat(code).isEqualTo(0)
        assertThat(out.trim()).isEqualTo("Checked 2 files: 0 with errors, 0 errors.")
        assertThat(out).doesNotContain("Not checked")
    }

    @Test fun `every finding in every file, by file then line`() {
        write("a.yaml", broken); write("b.yaml", clean); write("c.yaml", broken)
        val (code, out) = run("a.yaml", "b.yaml", "c.yaml")
        assertThat(code).isEqualTo(1)
        assertThat(out.lines().filter { ": error: " in it }).containsExactly(
            "a.yaml:4:5: error: Unknown Property: txt [unknown-property]",
            "a.yaml:7:1: error: Parsing Failed [parsing-failed]",
            "c.yaml:4:5: error: Unknown Property: txt [unknown-property]",
            "c.yaml:7:1: error: Parsing Failed [parsing-failed]",
        ).inOrder()
        // swipe reads its options itself, so an unknown key there is its own message, placed after the item.
        assertThat(out).contains("The property `txt` is not recognized")
        assertThat(out).contains("It seems you provided invalid input with: [dirction]")
        assertThat(out).contains("Checked 3 files: 2 with errors, 4 errors.")
        assertThat(out).doesNotContain("/syntax-checker")
    }

    @Test fun `one file, one error, singular`() {
        write("a.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        assertThat(run("a.yaml").out).contains("Checked 1 file: 1 with errors, 1 error.")
    }

    @Test fun `a broken shared subflow is reported once, under its own path`() {
        write("sub.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        write("a.yaml", "appId: a\n---\n- runFlow: sub.yaml\n"); write("b.yaml", "appId: a\n---\n- runFlow: sub.yaml\n")
        val out = run("a.yaml", "b.yaml").out
        assertThat(out.lines().filter { ": error: " in it }).containsExactly("sub.yaml:4:5: error: Unknown Property: txt [unknown-property]")
        // Neither argument loads, so both count; the subflow isn't an argument, so it isn't counted as a file.
        assertThat(out).contains("Checked 2 files: 2 with errors, 1 error.")
    }

    @Test fun `a referenced file that is not an argument gets all its findings`() {
        dir.resolve("sub").createDirectories()
        write("sub/multi.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n- swipe:\n    dirction: UP\n- assertVisible:\n    txet: w\n")
        write("a.yaml", "appId: a\n---\n- runFlow: sub/multi.yaml\n")
        assertThat(run("a.yaml").out.lines().filter { ": error: " in it }).containsExactly(
            "sub/multi.yaml:4:5: error: Unknown Property: txt [unknown-property]",
            "sub/multi.yaml:9:1: error: Parsing Failed [parsing-failed]",
            "sub/multi.yaml:8:5: error: Unknown Property: txet [unknown-property]",
        ).inOrder()
        val json = jacksonObjectMapper().readTree(run("--format", "json", "a.yaml").out)
        val sub = json["files"].single { it["path"].asText() == "sub/multi.yaml" }
        assertThat(sub["findings"]).hasSize(3)
        // The parser supplies no span end and no accepted names, so the fields are there but null and empty.
        for (finding in sub["findings"]) {
            assertThat(finding["endLine"].isNull).isTrue()
            assertThat(finding["accepted"]).isEmpty()
        }
    }

    @Test fun `a runFlow cycle among referenced files ends`() {
        write("b.yaml", "appId: a\n---\n- runFlow: c.yaml\n")
        write("c.yaml", "appId: a\n---\n- runFlow: b.yaml\n")
        write("top.yaml", "appId: a\n---\n- runFlow: b.yaml\n- back\n")
        val (code, out) = run("top.yaml")
        assertThat(code).isEqualTo(1)
        assertThat(out.lines().filter { ": error: " in it }).isNotEmpty()
    }

    @Test fun `stdin takes a snippet`() {
        val (code, out) = run("-", stdin = "- tapOn:\n    txt: x\n")
        assertThat(code).isEqualTo(1)
        assertThat(out).contains("<stdin>:2:5: error: Unknown Property: txt [unknown-property]")
        assertThat(run("-", stdin = "- launchApp\n").code).isEqualTo(0)
    }

    @Test fun `on stdin, a finding in a referenced file is printed under that file`() {
        dir.resolve("sub").createDirectories()
        write("sub/broken.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        val (code, out) = run("-", stdin = "appId: a\n---\n- runFlow: sub/broken.yaml\n")
        assertThat(code).isEqualTo(1)
        assertThat(out.lines().filter { ": error: " in it })
            .containsExactly("sub/broken.yaml:4:5: error: Unknown Property: txt [unknown-property]")
    }

    @Test fun `stdin with a lone dash above a comment passes`() {
        val (code, out) = run("-", stdin = "-\n  # c\n  tapOn: ok\n- back\n")
        assertThat(code).isEqualTo(0)
        assertThat(out).doesNotContain(": error: ")
    }

    @Test fun `a missing file and a directory exit 2, and the rest are still checked`() {
        write("a.yaml", broken); dir.resolve("folder").createDirectories()
        val (code, out) = run("nope.yaml", "folder", "a.yaml")
        assertThat(code).isEqualTo(2)
        assertThat(out).contains("nope.yaml: error: file does not exist")
        assertThat(out).contains("folder: error: is a directory")
        assertThat(out).contains("a.yaml:4:5: error:")
        assertThat(out).contains("Checked 3 files: 1 with errors, 2 errors, 2 could not be read.")
    }

    @Test fun `a missing file is not a file with errors, it could not be read`() {
        val (code, out) = run("nope.yaml")
        assertThat(code).isEqualTo(2)
        assertThat(out.lines().last { it.isNotBlank() }).isEqualTo(
            "Checked 1 file: 0 with errors, 0 errors, 1 could not be read.",
        )
    }

    @Test fun `three arguments sharing one broken subflow are all with errors`() {
        write("sub.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        for (name in listOf("a.yaml", "b.yaml", "c.yaml")) write(name, "appId: a\n---\n- runFlow: sub.yaml\n")
        val (code, out) = run("a.yaml", "b.yaml", "c.yaml")
        assertThat(code).isEqualTo(1)
        assertThat(out.lines().filter { "affected by" in it || ": error: " in it }).containsExactly(
            "a.yaml: affected by problems in sub.yaml",
            "b.yaml: affected by problems in sub.yaml",
            "c.yaml: affected by problems in sub.yaml",
            "sub.yaml:4:5: error: Unknown Property: txt [unknown-property]",
        ).inOrder()
        assertThat(out).contains("Checked 3 files: 3 with errors, 1 error.")

        val json = jacksonObjectMapper().readTree(run("--format", "json", "a.yaml", "b.yaml", "c.yaml").out)
        assertThat(json["files"].map { it["path"].asText() to it["referenced"].asBoolean() }).containsExactly(
            "a.yaml" to false, "b.yaml" to false, "c.yaml" to false, "sub.yaml" to true,
        ).inOrder()
        for (i in 0..2) assertThat(json["files"][i]["affectedBy"].map { it.asText() }).containsExactly("sub.yaml")
        assertThat(json["files"][3].has("affectedBy")).isFalse()
        assertThat(json["summary"]["files"].asInt()).isEqualTo(3)
        assertThat(json["summary"]["filesWithErrors"].asInt()).isEqualTo(3)
        assertThat(json["summary"]["errors"].asInt()).isEqualTo(1)
        assertThat(json["summary"]["filesNotRead"].asInt()).isEqualTo(0)
    }

    @Test fun `stdin mixed with files exits 2, with the message on stderr`() {
        write("a.yaml", clean)
        val (code, out, err) = run("-", "a.yaml")
        assertThat(code).isEqualTo(2)
        assertThat(out).isEmpty()
        assertThat(err).contains("\"-\" (stdin) can't be combined with files")
    }

    @Test fun `an unreadable file says why`() {
        write("u.yaml", clean)
        val file = dir.resolve("u.yaml").toFile()
        assertThat(file.setReadable(false)).isTrue()
        try {
            val (code, out) = run("u.yaml")
            assertThat(code).isEqualTo(2)
            assertThat(out).contains("u.yaml: error: permission denied\n")
        } finally {
            file.setReadable(true)
        }
    }

    @Test fun `empty or blank stdin is an input error`() {
        for (input in listOf("", "  \n\n")) {
            val (code, out) = run("-", stdin = input)
            assertThat(code).isEqualTo(2)
            assertThat(out).contains("<stdin>: error: input is empty\n")
            assertThat(out).contains("Checked 1 file: 0 with errors, 0 errors, 1 could not be read.")
        }
    }

    @Test fun `an internal error in one file is reported and the other files are still checked`() {
        write("a.yaml", broken); write("b.yaml", clean)
        val (code, out) = run("b.yaml", "a.yaml") { command ->
            command.checkFlow = { text, path, snippet ->
                if (path.fileName.toString() == "b.yaml") throw IllegalStateException("boom")
                YamlCommandReader.checkSyntaxReportingAllProblems(text, path, snippet)
            }
        }
        assertThat(code).isEqualTo(2)
        assertThat(out).contains("b.yaml: error: internal error: IllegalStateException: boom")
        assertThat(out).contains("a.yaml:4:5: error: Unknown Property: txt [unknown-property]")
    }

    @Test fun `an internal error in a referenced file that is not an argument is reported too`() {
        write("sub.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        write("a.yaml", "appId: a\n---\n- runFlow: sub.yaml\n")
        val (code, out) = run("a.yaml") { command ->
            command.checkFlow = { text, path, snippet ->
                if (path.fileName.toString() == "sub.yaml") throw IllegalStateException("boom")
                YamlCommandReader.checkSyntaxReportingAllProblems(text, path, snippet)
            }
        }
        assertThat(code).isEqualTo(2)
        assertThat(out).contains("sub.yaml: error: internal error: IllegalStateException: boom\n")
        assertThat(out).contains(", 1 could not be read.")
    }

    @Test fun `no arguments is a usage error`() {
        assertThat(run().code).isEqualTo(2)
    }

    @Test fun `text output caps at 20 findings per file`() {
        write("a.yaml", "appId: a\n---\n" + (1..25).joinToString("") { "- tapOn:\n    txt: x\n" })
        val out = run("a.yaml").out
        assertThat(out.lines().count { ": error: " in it }).isEqualTo(20)
        assertThat(out).contains("... and 5 more")
        assertThat(out).contains("25 errors.")
    }

    @Test fun `the same file given twice is checked once`() {
        write("a.yaml", broken)
        assertThat(run("a.yaml", "./a.yaml").out.lines().count { ": error: " in it }).isEqualTo(2)
    }

    @Test fun `a broken subflow that is also an argument is reported whichever order the arguments come in`() {
        write("sub.yaml", "appId: a\n---\n- tapOn:\n    txt: x\n")
        write("a.yaml", "appId: a\n---\n- runFlow: sub.yaml\n")
        for (args in listOf(arrayOf("a.yaml", "sub.yaml"), arrayOf("sub.yaml", "a.yaml"))) {
            val (code, out) = run(*args)
            assertThat(code).isEqualTo(1)
            assertThat(out.lines().filter { ": error: " in it })
                .containsExactly("sub.yaml:4:5: error: Unknown Property: txt [unknown-property]")
            assertThat(out).contains("a.yaml: affected by problems in sub.yaml")
            assertThat(out).contains("Checked 2 files: 2 with errors, 1 error.")
        }
    }

    @Test fun `json carries every finding and the summary, uncapped`() {
        write("a.yaml", "appId: a\n---\n" + (1..25).joinToString("") { "- tapOn:\n    txt: x\n" })
        write("b.yaml", clean)
        val (code, out) = run("--format", "json", "a.yaml", "b.yaml", "nope.yaml")
        val json = jacksonObjectMapper().readTree(out)
        assertThat(code).isEqualTo(2)
        assertThat(json["files"].map { it["path"].asText() }).containsExactly("a.yaml", "b.yaml", "nope.yaml").inOrder()
        assertThat(json["files"][0]["findings"]).hasSize(25)
        val first = json["files"][0]["findings"][0]
        assertThat(first["line"].asInt()).isEqualTo(4)
        assertThat(first["endColumn"].isNull).isTrue()
        assertThat(first["code"].asText()).isEqualTo("unknown-property")
        assertThat(first["suggestions"]).isEmpty()
        assertThat(first.has("file")).isFalse()
        assertThat(first.has("source")).isFalse()
        assertThat(json["files"][1]["findings"]).isEmpty()
        assertThat(json["files"][2]["error"].asText()).isEqualTo("file does not exist")
        assertThat(json["summary"]["errors"].asInt()).isEqualTo(25)
        assertThat(json["summary"]["files"].asInt()).isEqualTo(3)
        assertThat(json["summary"]["filesWithErrors"].asInt()).isEqualTo(1)
        assertThat(json["summary"]["filesNotRead"].asInt()).isEqualTo(1)
        assertThat(json["files"][0]["affectedBy"]).isEmpty()
        assertThat(json["summary"].has("notChecked")).isFalse()
    }

    @Test fun `json for a clean run parses and exits 0`() {
        write("a.yaml", clean)
        val (code, out) = run("--format", "json", "a.yaml")
        assertThat(code).isEqualTo(0)
        assertThat(jacksonObjectMapper().readTree(out)["summary"]["errors"].asInt()).isEqualTo(0)
    }

    @Test fun `json mode keeps stdout empty when stdin is mixed with files`() {
        write("a.yaml", clean)
        val (code, out) = run("--format", "json", "-", "a.yaml")
        assertThat(code).isEqualTo(2)
        assertThat(out).isEmpty()
    }
}
