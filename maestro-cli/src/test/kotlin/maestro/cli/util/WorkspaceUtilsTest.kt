package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class WorkspaceUtilsTest {

    @Test
    fun `includes files outside workspace directory using path traversal`(@TempDir tempDir: Path) {
        // Layout:
        // tempDir/
        //   flows/main.yaml
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

        // Create ZIP
        val outZip = tempDir.resolve("workspace.zip")
        WorkspaceUtils.createWorkspaceZip(mainFlow, outZip)

        // Open ZIP FS and collect entry names
        val zipUri = URI.create("jar:${outZip.toUri()}")
        val entryNames = mutableListOf<String>()
        FileSystems.newFileSystem(zipUri, emptyMap<String, Any>()).use { fs ->
            Files.walk(fs.getPath("/")).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .forEach { entryNames.add(it.toString().removePrefix("/")) }
            }
        }

        // Current behavior: Path traversal entries are NOT rejected
        // The script is outside the flows/ directory, so relativize produces "../scripts/outside.js"
        // This entry IS created in the ZIP (no validation/rejection happens)
        val hasTraversalEntry = entryNames.any { it.contains("..") && it.contains("scripts/outside.js") }
        val hasScriptEntry = entryNames.any { it.endsWith("outside.js") || it.endsWith("scripts/outside.js") }
        
        // Either the traversal path is preserved OR normalization resolves it - both are acceptable
        // The key point: NO rejection happens, the ZIP is created successfully
        assertThat(hasTraversalEntry || hasScriptEntry).isTrue()
        assertThat(entryNames.size).isAtLeast(2) // Should have at least main.yaml and the script
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
}

