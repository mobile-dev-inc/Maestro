package maestro.orchestra.workspace

import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class AliasResolutionEndToEndTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var testDir: Path
    private lateinit var configFile: Path

    @BeforeEach
    fun setUp() {
        testDir = tempDir.toPath().resolve("end-to-end-test")
        configFile = testDir.resolve("config.yaml")
        
        // Create test directory structure
        testDir.toFile().mkdirs()
        testDir.resolve("flows/screens").toFile().mkdirs()
        testDir.resolve("flows/components").toFile().mkdirs()
        testDir.resolve("flows/utils").toFile().mkdirs()
        
        // Copy test files
        copyTestFile("config.yaml", configFile)
        copyTestFile("flows/screens/login.yaml", testDir.resolve("flows/screens/login.yaml"))
        copyTestFile("flows/components/button.yaml", testDir.resolve("flows/components/button.yaml"))
        copyTestFile("flows/utils/helper.yaml", testDir.resolve("flows/utils/helper.yaml"))
    }

    @AfterEach
    fun tearDown() {
        WorkspaceConfigProvider.workspaceConfig = null
    }

    @Test
    fun `end-to-end test should load workspace config with paths and resolve aliases`() {
        // Load workspace config
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        
        // Verify paths are loaded correctly
        assertThat(workspaceConfig.paths).isNotNull()
        assertThat(workspaceConfig.paths).containsEntry("@screens", "flows/screens")
        assertThat(workspaceConfig.paths).containsEntry("!components", "flows/components")
        assertThat(workspaceConfig.paths).containsEntry("~utils", "flows/utils")
        
        // Set global workspace config
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Test alias resolution in PathResolver
        val resolvedLogin = PathResolver.resolveAliases("@screens/login.yaml", workspaceConfig)
        assertThat(resolvedLogin).isEqualTo("flows/screens/login.yaml")
        
        val resolvedButton = PathResolver.resolveAliases("!components/button.yaml", workspaceConfig)
        assertThat(resolvedButton).isEqualTo("flows/components/button.yaml")
        
        val resolvedHelper = PathResolver.resolveAliases("~utils/helper.yaml", workspaceConfig)
        assertThat(resolvedHelper).isEqualTo("flows/utils/helper.yaml")
    }

    @Test
    fun `end-to-end test should resolve aliases in runFlow commands`() {
        // Load workspace config and set globally
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Test that alias resolution works in PathResolver
        val loginAlias = PathResolver.resolveAliases("@screens/login.yaml", workspaceConfig)
        assertThat(loginAlias).isEqualTo("flows/screens/login.yaml")
        
        val buttonAlias = PathResolver.resolveAliases("!components/button.yaml", workspaceConfig)
        assertThat(buttonAlias).isEqualTo("flows/components/button.yaml")
        
        // Test that the global WorkspaceConfigProvider is working
        assertThat(WorkspaceConfigProvider.workspaceConfig).isNotNull()
        assertThat(WorkspaceConfigProvider.workspaceConfig?.paths).isNotNull()
    }

    @Test
    fun `end-to-end test should handle unknown aliases gracefully`() {
        // Load workspace config and set globally
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Test unknown alias - should remain unchanged
        val unknownAlias = PathResolver.resolveAliases("@unknown/path.yaml", workspaceConfig)
        assertThat(unknownAlias).isEqualTo("@unknown/path.yaml")
        
        // Test regular path - should remain unchanged
        val regularPath = PathResolver.resolveAliases("regular/path.yaml", workspaceConfig)
        assertThat(regularPath).isEqualTo("regular/path.yaml")
    }

    @Test
    fun `end-to-end test should validate alias paths exist`() {
        // Load workspace config
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        
        // Validate alias paths - should pass since our test files exist
        val errors = PathResolver.validateAliasPaths(workspaceConfig, testDir.toFile())
        assertThat(errors).isEmpty()
    }

    @Test
    fun `end-to-end test should detect non-existent alias paths`() {
        // Create a config with non-existent alias paths
        val invalidConfig = WorkspaceConfig(
            paths = mapOf(
                "@invalid" to "non-existent/path",
                "@screens" to "flows/screens" // This one exists
            )
        )
        
        // Validate should detect the non-existent path
        val errors = PathResolver.validateAliasPaths(invalidConfig, testDir.toFile())
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("non-existent path") }).isTrue()
    }

    @Test
    fun `end-to-end test should handle circular references`() {
        // Create a config with circular references
        val circularConfig = WorkspaceConfig(
            paths = mapOf(
                "@a" to "@b/path",
                "@b" to "@a/path"
            )
        )
        
        // Validate should detect circular references
        val errors = PathResolver.validateAliasPaths(circularConfig, testDir.toFile())
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("Circular references are not supported") }).isTrue()
    }

    private fun copyTestFile(sourcePath: String, targetPath: Path) {
        val sourceFile = javaClass.getResource("/end-to-end-test/$sourcePath")
        assertThat(sourceFile).isNotNull()
        
        val content = sourceFile.readText()
        targetPath.toFile().writeText(content)
    }
}
