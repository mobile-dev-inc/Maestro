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
    fun `directory upload without any workspace config does not get a synthetic config yaml`(@TempDir tempDir: Path) {
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

    @Test
    fun `directory upload with workspace config yaml preserves it at zip root`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir)

        val originalConfig = """
            flows:
              - flow_a.yaml
        """.trimIndent()
        Files.writeString(workspaceDir.resolve("config.yaml"), originalConfig)
        Files.writeString(
            workspaceDir.resolve("flow_a.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip)

        val entryNames = readZipEntryNames(outZip)
        assertThat(entryNames).contains("config.yaml")
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(readZipEntry(outZip, "config.yaml")).isEqualTo(originalConfig)
    }

    @Test
    fun `configOverride with non-default filename inside workspace injects override content`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir.resolve("nested"))

        val overrideContent = """
            flows:
              - flow_a.yaml
        """.trimIndent()
        val overrideConfig = workspaceDir.resolve("nested/config2.yaml")
        Files.writeString(overrideConfig, overrideContent)
        Files.writeString(
            workspaceDir.resolve("flow_a.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip, configOverride = overrideConfig)

        val entryNames = readZipEntryNames(outZip)
        assertThat(entryNames).contains("config.yaml")
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(entryNames).doesNotContain("nested/config2.yaml")
        assertThat(readZipEntry(outZip, "config.yaml")).isEqualTo(overrideContent)
    }

    @Test
    fun `configOverride outside the workspace injects override content`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        val externalDir = tempDir.resolve("external")
        Files.createDirectories(workspaceDir)
        Files.createDirectories(externalDir)

        val overrideContent = """
            flows:
              - flow_a.yaml
            includeTags:
              - smoke
        """.trimIndent()
        val overrideConfig = externalDir.resolve("my-config.yaml")
        Files.writeString(overrideConfig, overrideContent)
        Files.writeString(
            workspaceDir.resolve("flow_a.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip, configOverride = overrideConfig)

        val entryNames = readZipEntryNames(outZip)
        assertThat(entryNames).contains("config.yaml")
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(readZipEntry(outZip, "config.yaml")).isEqualTo(overrideContent)
    }

    @Test
    fun `configOverride strips every workspace-config-shaped yaml in the workspace`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir.resolve("subdir"))

        // Root-level config that would otherwise be preserved
        Files.writeString(
            workspaceDir.resolve("config.yaml"),
            """
            flows:
              - flow_a.yaml
            """.trimIndent()
        )

        // Additional config-shaped YAML (e.g. regression_config.yaml) that PR #3150
        // taught the planner to recognize as a workspace config, not a flow.
        Files.writeString(
            workspaceDir.resolve("subdir/regression_config.yaml"),
            """
            includeTags:
              - regression
            """.trimIndent()
        )

        Files.writeString(
            workspaceDir.resolve("flow_a.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val overrideContent = """
            flows:
              - flow_a.yaml
        """.trimIndent()
        val overrideConfig = tempDir.resolve("override.yaml")
        Files.writeString(overrideConfig, overrideContent)

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip, configOverride = overrideConfig)

        val entryNames = readZipEntryNames(outZip)
        assertThat(entryNames).contains("config.yaml")
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(entryNames).doesNotContain("subdir/regression_config.yaml")
        assertThat(readZipEntry(outZip, "config.yaml")).isEqualTo(overrideContent)
    }

    @Test
    fun `directory upload strips app binaries, archives, and OS metadata but keeps assets`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir.resolve("assets"))
        Files.createDirectories(workspaceDir.resolve("scripts"))

        Files.writeString(
            workspaceDir.resolve("flow.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        // Junk that must not be uploaded.
        Files.write(workspaceDir.resolve("sample.apk"), ByteArray(16) { 0x1 })
        Files.write(workspaceDir.resolve("debug.aab"), ByteArray(16) { 0x2 })
        Files.write(workspaceDir.resolve("legacy.ipa"), ByteArray(16) { 0x3 })
        Files.write(workspaceDir.resolve("fixtures.zip"), ByteArray(16) { 0x4 })
        Files.write(workspaceDir.resolve("backup.tar.gz"), ByteArray(16) { 0x5 })
        Files.write(workspaceDir.resolve(".DS_Store"), ByteArray(16) { 0x6 })
        Files.write(workspaceDir.resolve("assets/.DS_Store"), ByteArray(16) { 0x7 })

        // Legitimate assets that must be kept.
        Files.write(workspaceDir.resolve("assets/test.jpg"), ByteArray(32) { 0x8 })
        Files.write(workspaceDir.resolve("assets/demo.mp4"), ByteArray(32) { 0x9 })
        Files.writeString(workspaceDir.resolve("scripts/helper.js"), "console.log('ok');")
        Files.writeString(workspaceDir.resolve("assets/fixture.txt"), "hello")

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip)

        val entryNames = readZipEntryNames(outZip)

        assertThat(entryNames).containsAtLeast(
            "flow.yaml",
            "assets/test.jpg",
            "assets/demo.mp4",
            "assets/fixture.txt",
            "scripts/helper.js",
        )
        assertThat(entryNames).containsNoneOf(
            "sample.apk",
            "debug.aab",
            "legacy.ipa",
            "fixtures.zip",
            "backup.tar.gz",
            ".DS_Store",
            "assets/.DS_Store",
        )
    }

    @Test
    fun `directory upload drops files under VCS, IDE, and build directories`(@TempDir tempDir: Path) {
        val workspaceDir = tempDir.resolve("workspace")
        Files.createDirectories(workspaceDir.resolve(".git/objects"))
        Files.createDirectories(workspaceDir.resolve(".idea"))
        Files.createDirectories(workspaceDir.resolve("node_modules/foo"))
        Files.createDirectories(workspaceDir.resolve("build/reports"))

        Files.writeString(workspaceDir.resolve(".git/HEAD"), "ref: refs/heads/main")
        Files.write(workspaceDir.resolve(".git/objects/abc"), ByteArray(16))
        Files.writeString(workspaceDir.resolve(".idea/workspace.xml"), "<xml/>")
        Files.writeString(workspaceDir.resolve("node_modules/foo/index.js"), "exports = {};")
        Files.writeString(workspaceDir.resolve("build/reports/report.html"), "<html/>")

        Files.writeString(
            workspaceDir.resolve("flow.yaml"),
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip)

        val entryNames = readZipEntryNames(outZip)

        assertThat(entryNames).contains("flow.yaml")
        assertThat(entryNames.filter { it.startsWith(".git/") }).isEmpty()
        assertThat(entryNames.filter { it.startsWith(".idea/") }).isEmpty()
        assertThat(entryNames.filter { it.startsWith("node_modules/") }).isEmpty()
        assertThat(entryNames.filter { it.startsWith("build/") }).isEmpty()
    }

    @Test
    fun `createWorkspaceZip throws when configOverride does not exist`(@TempDir tempDir: Path) {
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

        val outZip = tempDir.resolve("workspace.zip")
        val missing = tempDir.resolve("does-not-exist.yaml")

        assertThrows<java.io.FileNotFoundException> {
            WorkspaceUtils.createWorkspaceZip(workspaceDir, outZip, configOverride = missing)
        }
    }
}
