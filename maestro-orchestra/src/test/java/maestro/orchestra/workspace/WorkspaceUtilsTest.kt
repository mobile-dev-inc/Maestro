package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import kotlin.io.path.readText

class WorkspaceUtilsTest {

    private fun normalize(path: Path): Path = try {
        path.toRealPath(LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) {
        path.toAbsolutePath().normalize()
    }

    private fun readZipEntryNames(zipPath: Path): List<String> {
        val zipUri = URI.create("jar:${zipPath.toUri()}")
        return FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            Files.walk(fs.getPath("/")).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .map { it.toString().removePrefix("/") }
                    .toList()
            }
        }
    }

    private fun readZipEntry(zipPath: Path, entryName: String): String {
        val zipUri = URI.create("jar:${zipPath.toUri()}")
        return FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            fs.getPath(entryName).readText()
        }
    }

    // --- findCommonAncestor unit tests ---

    @Test
    fun `findCommonAncestor with single path returns its parent`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("flows/main.yaml")
        Files.createDirectories(file.parent)
        Files.writeString(file, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(file))
        assertThat(result.toString()).isEqualTo(normalize(file).parent.toString())
    }

    @Test
    fun `findCommonAncestor with sibling directories returns their parent`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("flows"))
        Files.createDirectories(tempDir.resolve("scripts"))
        val flow = tempDir.resolve("flows/main.yaml")
        val script = tempDir.resolve("scripts/helper.js")
        Files.writeString(flow, "")
        Files.writeString(script, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(flow, script))
        assertThat(result.toString()).isEqualTo(normalize(tempDir).toString())
    }

    @Test
    fun `findCommonAncestor with deeply nested paths finds correct ancestor`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("a/b/c/d"))
        Files.createDirectories(tempDir.resolve("a/b/e/f"))
        val deep1 = tempDir.resolve("a/b/c/d/file1.yaml")
        val deep2 = tempDir.resolve("a/b/e/f/file2.yaml")
        Files.writeString(deep1, "")
        Files.writeString(deep2, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(deep1, deep2))
        assertThat(result.toString()).isEqualTo(normalize(tempDir.resolve("a/b")).toString())
    }

    @Test
    fun `findCommonAncestor throws on empty list`() {
        assertThrows<IllegalArgumentException> {
            WorkspaceUtils.findCommonAncestor(emptyList())
        }.also {
            assertThat(it.message).contains("paths must not be empty")
        }
    }

    // --- createWorkspaceZip integration tests ---

    @Test
    fun `single flow file with external dependencies gets synthetic config yaml`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("flows"))
        Files.createDirectories(tempDir.resolve("shared"))

        val helperFlow = tempDir.resolve("shared/helper.yaml")
        Files.writeString(
            helperFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val siblingFlow = tempDir.resolve("flows/sibling.yaml")
        Files.writeString(
            siblingFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val mainFlow = tempDir.resolve("flows/main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            - runFlow:
                file: ../shared/helper.yaml
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        val entryNames = readZipEntryNames(outZip)

        assertThat(entryNames).contains("config.yaml")

        val configContent = readZipEntry(outZip, "config.yaml")
        assertThat(configContent).contains("flows:")
        assertThat(configContent).contains("flows/main.yaml")
        assertThat(configContent).doesNotContain("sibling.yaml")
        assertThat(configContent).doesNotContain("helper.yaml")
    }

    @Test
    fun `single flow file without external dependencies gets synthetic config yaml`(@TempDir tempDir: Path) {
        val mainFlow = tempDir.resolve("main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        val entryNames = readZipEntryNames(outZip)

        assertThat(entryNames).contains("config.yaml")

        val configContent = readZipEntry(outZip, "config.yaml")
        assertThat(configContent).contains("flows:")
        assertThat(configContent).contains("main.yaml")
    }

    @Test
    fun `directory upload does not get synthetic config yaml`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir)

        Files.writeString(
            workspaceDir.resolve("flow_a.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )
        Files.writeString(
            workspaceDir.resolve("flow_b.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip)

        val entryNames = readZipEntryNames(outZip)

        assertThat(entryNames).doesNotContain("config.yaml")
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(entryNames).contains("flow_b.yaml")
    }
}
