package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import kotlin.io.path.readText

class WorkspaceUtilsTest {

    /** Normalize the same way as production code: toRealPath without following symlinks */
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
    fun `findCommonAncestor with two paths in same directory returns that directory`(@TempDir tempDir: Path) {
        val dir = tempDir.resolve("flows")
        Files.createDirectories(dir)
        val a = dir.resolve("a.yaml")
        val b = dir.resolve("b.yaml")
        Files.writeString(a, "")
        Files.writeString(b, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(a, b))
        assertThat(result.toString()).isEqualTo(normalize(dir).toString())
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
    fun `findCommonAncestor with paths sharing only root returns root-level dir`(@TempDir tempDir: Path) {
        // Two paths whose only shared component is tempDir itself
        Files.createDirectories(tempDir.resolve("alpha/nested"))
        Files.createDirectories(tempDir.resolve("beta/nested"))
        val a = tempDir.resolve("alpha/nested/a.yaml")
        val b = tempDir.resolve("beta/nested/b.yaml")
        Files.writeString(a, "")
        Files.writeString(b, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(a, b))
        assertThat(result.toString()).isEqualTo(normalize(tempDir).toString())
    }

    @Test
    fun `findCommonAncestor with three paths finds correct ancestor`(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("a/x"))
        Files.createDirectories(tempDir.resolve("a/y"))
        Files.createDirectories(tempDir.resolve("a/z"))
        val f1 = tempDir.resolve("a/x/f1.yaml")
        val f2 = tempDir.resolve("a/y/f2.yaml")
        val f3 = tempDir.resolve("a/z/f3.yaml")
        Files.writeString(f1, "")
        Files.writeString(f2, "")
        Files.writeString(f3, "")

        val result = WorkspaceUtils.findCommonAncestor(listOf(f1, f2, f3))
        assertThat(result.toString()).isEqualTo(normalize(tempDir.resolve("a")).toString())
    }

    @Test
    fun `findCommonAncestor throws on empty list`() {
        try {
            WorkspaceUtils.findCommonAncestor(emptyList())
            assertThat(false).isTrue() // should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("paths must not be empty")
        }
    }

    // --- createWorkspaceZip integration tests ---

    @Test
    fun `zip entries have no path traversal segments for dependencies outside flow parent`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   flows/main.yaml        (references ../scripts/outside.js)
        //   scripts/outside.js
        val flowsDir = tempDir.resolve("flows").toFile()
        flowsDir.mkdirs()
        val scriptsDir = tempDir.resolve("scripts").toFile()
        scriptsDir.mkdirs()

        val outsideScript = tempDir.resolve("scripts/outside.js")
        Files.writeString(outsideScript, "console.log('outside');")

        val mainFlow = tempDir.resolve("flows/main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - runScript: ../scripts/outside.js
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        val entryNames = readZipEntryNames(outZip)

        // ZIP entries must not contain ".." or "./" segments
        entryNames.forEach { entry ->
            assertThat(entry).doesNotContain("..")
            assertThat(entry).doesNotContain("./")
        }
        assertThat(entryNames.size).isAtLeast(2)
        assertThat(entryNames.any { it.endsWith("main.yaml") }).isTrue()
        assertThat(entryNames.any { it.endsWith("outside.js") }).isTrue()
        // Entries should be relativized from common ancestor (tempDir), so they include directory names
        assertThat(entryNames.any { it == "flows/main.yaml" }).isTrue()
        assertThat(entryNames.any { it == "scripts/outside.js" }).isTrue()
    }

    @Test
    fun `zip entries are normalized for deeply nested cross-directory runFlow references`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   tests/core/main_flow.yaml    (references ../../shared/setup.yaml)
        //   shared/setup.yaml
        Files.createDirectories(tempDir.resolve("tests/core"))
        Files.createDirectories(tempDir.resolve("shared"))

        val setupFlow = tempDir.resolve("shared/setup.yaml")
        Files.writeString(
            setupFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )

        val mainFlow = tempDir.resolve("tests/core/main_flow.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - launchApp
            - runFlow:
                file: ../../shared/setup.yaml
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        val entryNames = readZipEntryNames(outZip)

        // No ".." or "./" in any entry
        entryNames.forEach { entry ->
            assertThat(entry).doesNotContain("..")
            assertThat(entry).doesNotContain("./")
        }
        assertThat(entryNames).containsExactly("tests/core/main_flow.yaml", "shared/setup.yaml", "config.yaml")
    }

    @Test
    fun `handles symlinks correctly`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   flows/main.yaml
        //   scripts/real.js (actual file)
        //   scripts/link.js -> real.js (symlink pointing to real.js)
        
        // The flow references link.js normally, but link.js is a symlink
        // This tests what happens when a dependency file is actually a symlink
        
        val flowsDir = tempDir.resolve("flows").toFile()
        flowsDir.mkdirs()
        val scriptsDir = tempDir.resolve("scripts").toFile()
        scriptsDir.mkdirs()

        val realScript = tempDir.resolve("scripts/real.js")
        Files.writeString(realScript, "console.log('real');")

        // Create symlink: link.js is a symlink that points to real.js
        val linkScript = tempDir.resolve("scripts/link.js")
        Files.createSymbolicLink(linkScript, realScript)

        val mainFlow = tempDir.resolve("flows/main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - runScript: ../scripts/link.js
            """.trimIndent()
        )

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        // With normalization using toRealPath(NOFOLLOW_LINKS), symlink paths are preserved
        // The ZIP should include the script (as link.js or normalized to real.js)
        val zipUri = URI.create("jar:${outZip.toUri()}")
        val entryNames = mutableListOf<String>()
        FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            Files.walk(fs.getPath("/")).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { entryNames.add(it.toString().removePrefix("/")) }
            }
        }

        // Should have main.yaml and the script (either link.js or real.js, depending on normalization)
        assertThat(entryNames.size).isAtLeast(2)
        assertThat(entryNames.any { it.endsWith("main.yaml") }).isTrue()
        // Script should be included (either as link.js or normalized to real.js)
        assertThat(entryNames.any { it.contains("real.js") || it.contains("link.js") }).isTrue()
    }

    @Test
    fun `handles special characters in file paths`(@TempDir tempDir: Path) {
        // Test paths with spaces, unicode, and other special characters
        val mainFlow = tempDir.resolve("main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - runScript: "scripts/script with spaces.js"
            - addMedia:
              - "images/émoji🎉.png"
            """.trimIndent()
        )

        val scriptsDir = tempDir.resolve("scripts").toFile()
        scriptsDir.mkdirs()
        val scriptWithSpaces = tempDir.resolve("scripts/script with spaces.js")
        Files.writeString(scriptWithSpaces, "console.log('spaces');")

        val imagesDir = tempDir.resolve("images").toFile()
        imagesDir.mkdirs()
        val emojiFile = tempDir.resolve("images/émoji🎉.png")
        Files.writeString(emojiFile, "fake png")

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        // ZIP should be created successfully with special characters
        assertThat(outZip.toFile().exists()).isTrue()
        
        val zipUri = URI.create("jar:${outZip.toUri()}")
        val entryNames = mutableListOf<String>()
        FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            Files.walk(fs.getPath("/")).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { entryNames.add(it.toString().removePrefix("/")) }
            }
        }

        // All files should be included
        assertThat(entryNames.size).isAtLeast(3)
        assertThat(entryNames.any { it.contains("script with spaces.js") }).isTrue()
        assertThat(entryNames.any { it.contains("émoji") || it.contains("🎉") }).isTrue()
    }

    @Test
    fun `handles empty files`(@TempDir tempDir: Path) {
        val mainFlow = tempDir.resolve("main.yaml")
        Files.writeString(
            mainFlow,
            """
            appId: com.example.app
            ---
            - runScript: empty.js
            """.trimIndent()
        )

        val emptyScript = tempDir.resolve("empty.js")
        Files.createFile(emptyScript) // Create empty file

        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        // Should handle empty files gracefully
        assertThat(outZip.toFile().exists()).isTrue()
        
        val zipUri = URI.create("jar:${outZip.toUri()}")
        val entryNames = mutableListOf<String>()
        FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            Files.walk(fs.getPath("/")).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { entryNames.add(it.toString().removePrefix("/")) }
            }
        }

        assertThat(entryNames.size).isAtLeast(2)
        assertThat(entryNames.any { it.endsWith("empty.js") }).isTrue()
    }

    @Test
    fun `single flow file with external dependencies gets synthetic config yaml`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   flows/main.yaml        (references ../shared/helper.yaml)
        //   shared/helper.yaml
        //   flows/sibling.yaml     (should NOT be executed)
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

        // config.yaml should be present
        assertThat(entryNames).contains("config.yaml")

        // config.yaml should restrict execution to only the requested flow
        val configContent = readZipEntry(outZip, "config.yaml")
        assertThat(configContent).contains("flows:")
        assertThat(configContent).contains("flows/main.yaml")
        // Should NOT reference sibling or helper
        assertThat(configContent).doesNotContain("sibling.yaml")
        assertThat(configContent).doesNotContain("helper.yaml")
    }

    @Test
    fun `single flow file without external dependencies gets synthetic config yaml`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   main.yaml  (no external deps)
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

        // config.yaml should still be injected for single-file uploads
        assertThat(entryNames).contains("config.yaml")

        val configContent = readZipEntry(outZip, "config.yaml")
        assertThat(configContent).contains("flows:")
        assertThat(configContent).contains("main.yaml")
    }

    @Test
    fun `directory upload does not get synthetic config yaml`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/workspace/
        //   flow_a.yaml
        //   flow_b.yaml
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

        // No synthetic config.yaml for directory uploads
        assertThat(entryNames).doesNotContain("config.yaml")
        // But the flow files should be there
        assertThat(entryNames).contains("flow_a.yaml")
        assertThat(entryNames).contains("flow_b.yaml")
    }
}

