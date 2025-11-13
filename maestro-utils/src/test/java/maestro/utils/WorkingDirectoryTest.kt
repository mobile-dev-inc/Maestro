package maestro.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class WorkingDirectoryTest {

    @AfterEach
    fun cleanup() {
        // Reset to defaults after each test
        WorkingDirectory.baseDir = File(System.getProperty("user.dir"))
        WorkingDirectory.pathAliases = emptyMap()
    }

    @Test
    fun `resolve returns file relative to baseDir when no aliases configured`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        
        val result = WorkingDirectory.resolve("subdir/file.txt")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("subdir/file.txt").toAbsolutePath().toString())
    }

    @Test
    fun `resolve returns absolute file when given absolute path`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        val absolutePath = "/tmp/absolute/path.txt"
        
        val result = WorkingDirectory.resolve(absolutePath)
        
        assertThat(result.isAbsolute).isTrue()
        assertThat(result.absolutePath).isEqualTo(absolutePath)
    }

    @Test
    fun `resolve replaces tilde prefix alias with configured path`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~js" to "scripts")
        
        val result = WorkingDirectory.resolve("~js/script.js")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("scripts/script.js").toAbsolutePath().toString())
    }

    @Test
    fun `resolve replaces custom alias with configured path`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("@components" to "src/components")
        
        val result = WorkingDirectory.resolve("@components/Button.tsx")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("src/components/Button.tsx").toAbsolutePath().toString())
    }

    @Test
    fun `resolve handles multiple aliases`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf(
            "~js" to "scripts",
            "~sf" to "subflows",
            "~media" to "assets/media"
        )
        
        val jsResult = WorkingDirectory.resolve("~js/helper.js")
        val sfResult = WorkingDirectory.resolve("~sf/login.yaml")
        val mediaResult = WorkingDirectory.resolve("~media/image.png")
        
        assertThat(jsResult.absolutePath).isEqualTo(tempDir.resolve("scripts/helper.js").toAbsolutePath().toString())
        assertThat(sfResult.absolutePath).isEqualTo(tempDir.resolve("subflows/login.yaml").toAbsolutePath().toString())
        assertThat(mediaResult.absolutePath).isEqualTo(tempDir.resolve("assets/media/image.png").toAbsolutePath().toString())
    }

    @Test
    fun `resolve handles alias with relative parent path`(@TempDir tempDir: Path) {
        val flowsDir = tempDir.resolve("flows")
        flowsDir.createDirectories()
        WorkingDirectory.baseDir = flowsDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~js" to "../scripts")
        
        val result = WorkingDirectory.resolve("~js/script.js")
        
        assertThat(result.absolutePath).isEqualTo(flowsDir.resolve("../scripts/script.js").toAbsolutePath().toString())
    }

    @Test
    fun `resolve returns original path when alias not found`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~js" to "scripts")
        
        val result = WorkingDirectory.resolve("~other/file.txt")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("~other/file.txt").toAbsolutePath().toString())
    }

    @Test
    fun `resolve only replaces at start of path`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~js" to "scripts")
        
        val result = WorkingDirectory.resolve("dir/~js/script.js")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("dir/~js/script.js").toAbsolutePath().toString())
    }

    @Test
    fun `resolve handles exact alias match without slash`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~base" to "base/path")
        
        val result = WorkingDirectory.resolve("~base")
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("base/path").toAbsolutePath().toString())
    }

    @Test
    fun `resolve with File returns absolute file when file is absolute`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        val absoluteFile = File("/tmp/absolute.txt")
        
        val result = WorkingDirectory.resolve(absoluteFile)
        
        assertThat(result.isAbsolute).isTrue()
        assertThat(result).isEqualTo(absoluteFile)
    }

    @Test
    fun `resolve with File returns file relative to baseDir when file is relative`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        val relativeFile = File("relative/path.txt")
        
        val result = WorkingDirectory.resolve(relativeFile)
        
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("relative/path.txt").toAbsolutePath().toString())
    }

    @Test
    fun `integration test - config-in-root structure`(@TempDir tempDir: Path) {
        // Given: Simulated workspace with config-in-root structure
        val configDir = tempDir.resolve("config-in-root")
        configDir.createDirectories()
        configDir.resolve("scripts").createDirectories()
        configDir.resolve("subflows").createDirectories()
        
        configDir.resolve("scripts/script.js").apply {
            createFile()
            writeText("console.log('test');")
        }
        
        configDir.resolve("subflows/subflow.yaml").apply {
            createFile()
            writeText("- tapOn: Button")
        }
        
        // Configure WorkingDirectory as if config.yaml was loaded
        WorkingDirectory.baseDir = configDir.toFile()
        WorkingDirectory.pathAliases = mapOf(
            "~js" to "scripts",
            "~sf" to "subflows"
        )
        
        // When: Resolve paths as they would appear in flow.yaml
        val scriptPath = WorkingDirectory.resolve("~js/script.js")
        val subflowPath = WorkingDirectory.resolve("~sf/subflow.yaml")
        
        // Then: Paths are resolved correctly
        assertThat(scriptPath.absolutePath).isEqualTo(configDir.resolve("scripts/script.js").toAbsolutePath().toString())
        assertThat(subflowPath.absolutePath).isEqualTo(configDir.resolve("subflows/subflow.yaml").toAbsolutePath().toString())
        
        // Verify files exist at resolved paths
        assertThat(scriptPath.exists()).isTrue()
        assertThat(subflowPath.exists()).isTrue()
    }

    @Test
    fun `integration test - config-in-folder structure`(@TempDir tempDir: Path) {
        // Given: Simulated workspace with config-in-folder structure
        val rootDir = tempDir.resolve("config-in-folder")
        rootDir.createDirectories()
        
        val maestroDir = rootDir.resolve(".maestro")
        maestroDir.createDirectories()
        
        rootDir.resolve("scripts").createDirectories()
        rootDir.resolve("subflows").createDirectories()
        
        rootDir.resolve("scripts/script.js").apply {
            createFile()
            writeText("console.log('test');")
        }
        
        rootDir.resolve("subflows/subflow.yaml").apply {
            createFile()
            writeText("- tapOn: Button")
        }
        
        // Configure WorkingDirectory as if config.yaml in .maestro folder was loaded
        WorkingDirectory.baseDir = maestroDir.toFile()
        WorkingDirectory.pathAliases = mapOf(
            "~js" to "../scripts",
            "~sf" to "../subflows"
        )
        
        // When: Resolve paths as they would appear in flow.yaml
        val scriptPath = WorkingDirectory.resolve("~js/script.js")
        val subflowPath = WorkingDirectory.resolve("~sf/subflow.yaml")
        
        // Then: Paths are resolved correctly
        assertThat(scriptPath.absolutePath).isEqualTo(maestroDir.resolve("../scripts/script.js").toAbsolutePath().toString())
        assertThat(subflowPath.absolutePath).isEqualTo(maestroDir.resolve("../subflows/subflow.yaml").toAbsolutePath().toString())
        
        // Verify files exist at resolved paths
        assertThat(scriptPath.exists()).isTrue()
        assertThat(subflowPath.exists()).isTrue()
    }

    @Test
    fun `resolve handles empty aliases map`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = emptyMap()
        
        val result = WorkingDirectory.resolve("~js/script.js")
        
        // Should return path as-is when no aliases configured
        assertThat(result.absolutePath).isEqualTo(tempDir.resolve("~js/script.js").toAbsolutePath().toString())
    }

    @Test
    fun `resolve handles alias followed by backslash`(@TempDir tempDir: Path) {
        WorkingDirectory.baseDir = tempDir.toFile()
        WorkingDirectory.pathAliases = mapOf("~js" to "scripts")
        
        // Backslash doesn't match our alias pattern (we look for "/"), but the alias part should still work
        val result = WorkingDirectory.resolve("~js/sub\\script.js")
        
        // The path should have scripts in it
        assertThat(result.absolutePath).contains("scripts")
    }
}

