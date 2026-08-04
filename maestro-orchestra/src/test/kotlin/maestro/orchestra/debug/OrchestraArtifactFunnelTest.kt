package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Seam guard: every command output in Orchestra is allocated through
 * [ArtifactsGenerator.allocateCommandArtifact] (the collector funnel), never a raw `File(...)`
 * path of the command's own. A raw path is how artifacts historically fell out of the Cloud
 * bundle one at a time: written somewhere the uploader never reads and recorded nowhere (most
 * recently the assertScreenshot diff). If this test fails, route the new output through the
 * funnel; widen the allowlist only for reads or non-artifact files.
 */
class OrchestraArtifactFunnelTest {

    @Test
    fun `orchestra constructs no raw File paths outside the allowlist`() {
        val orchestra = File("src/main/java/maestro/orchestra/Orchestra.kt")
        assertThat(orchestra.exists()).isTrue()
        val source = orchestra.readText()

        // Lines allowed to construct a File directly: reads and non-artifact temp files only.
        val allowed = setOf(
            "add(File(path))", // assertScreenshot reference-image candidate: a read, not an output
        )

        val rawConstructions = Regex("""\bFile\(""").findAll(source)
            .map { source.lineAt(it.range.first) }
            .filterNot { line -> allowed.any { line.contains(it) } }
            .toList()

        assertThat(rawConstructions).isEmpty()
    }

    private fun String.lineAt(index: Int): String {
        val start = lastIndexOf('\n', index) + 1
        val end = indexOf('\n', index).let { if (it == -1) length else it }
        return substring(start, end).trim()
    }
}
