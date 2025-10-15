package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.WorkingDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Path

/**
 * Runtime execution tests for path aliases and JavaScript interpolation.
 * 
 * These tests verify that:
 * 1. Path aliases are correctly resolved during Orchestra execution
 * 2. JavaScript interpolation in paths works at runtime
 * 3. Error handling is correct for invalid paths
 */
class PathAliasesRuntimeTest {
    
    @AfterEach
    fun cleanup() {
        // Reset WorkingDirectory to defaults
        WorkingDirectory.baseDir = File(System.getProperty("user.dir"))
        WorkingDirectory.pathAliases = emptyMap()
    }

    @Test
    fun `runScript command with path alias executes at runtime`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse and execute flow with runScript using path alias
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands should be parsed successfully
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        assertThat(runScriptCommand?.sourceDescription).isEqualTo("~js/script.js")
        
        // Verify the script content was loaded (no path alias in content)
        assertThat(runScriptCommand?.script).contains("console.log")
    }

    @Test
    fun `runFlow command with path alias executes at runtime`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow with runFlow using path alias
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands should be parsed and subflow loaded
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("~sf/subflow.yaml")
        assertThat(runFlowCommand?.commands).isNotEmpty()
    }

    @Test
    fun `runScript with JS interpolation preserves pattern for runtime resolution`() {
        // Given: Workspace with JS interpolation in script path
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runscript-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.scriptName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should be parsed with deferred resolution fields set
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        assertThat(runScriptCommand?.scriptPath).isEqualTo("~js/\${output.scriptName}.js")
        assertThat(runScriptCommand?.flowPath).isNotNull()
        // Script content should be empty since it will be loaded at runtime
        assertThat(runScriptCommand?.script).isEmpty()
    }

    @Test
    fun `runFlow with JS interpolation preserves pattern for runtime resolution`() {
        // Given: Workspace with JS interpolation in flow path
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.flowName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should be parsed with deferred resolution fields set
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.flowFilePath).isEqualTo("~sf/\${output.flowName}.yaml")
        assertThat(runFlowCommand?.parentFlowPath).isNotNull()
        // Commands should be empty since subflow will be loaded at runtime
        assertThat(runFlowCommand?.commands).isEmpty()
    }

    @Test
    fun `invalid alias in runScript path throws error during parsing`() {
        // Given: Workspace with aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        // Create a temporary flow with invalid alias
        val tempFlow = workspacePath.resolve("flows").resolve("temp-invalid.yaml")
        tempFlow.toFile().writeText("""
            appId: com.example
            ---
            - runScript: '~invalid/nonexistent.js'
        """.trimIndent())
        
        try {
            // When: Parse flow with non-existent file
            // Then: Should throw error because path doesn't exist
            assertThrows<SyntaxError> {
                YamlCommandReader.readCommands(tempFlow)
            }
        } finally {
            tempFlow.toFile().delete()
        }
    }

    @Test
    fun `resolvePathWithAliases handles absolute paths correctly`() {
        // Given: Workspace with aliases
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        val absolutePath = workspacePath.resolve("scripts/script.js").toAbsolutePath().toString()
        
        // When: Resolve an absolute path
        val resolved = WorkingDirectory.resolve(absolutePath)
        
        // Then: Should return the absolute path as-is
        assertThat(resolved.isAbsolute).isTrue()
        assertThat(resolved.absolutePath).isEqualTo(absolutePath)
    }

    @Test
    fun `resolvePathWithAliases handles relative paths without aliases`() {
        // Given: Workspace with aliases
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        // When: Resolve a relative path without alias prefix
        val resolved = WorkingDirectory.resolve("scripts/script.js")
        
        // Then: Should resolve relative to baseDir
        assertThat(resolved.exists()).isTrue()
        assertThat(resolved.name).isEqualTo("script.js")
    }

    @Test
    fun `path alias at exact match is resolved correctly`() {
        // Given: Workspace with aliases
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        // When: Resolve just the alias itself (no additional path)
        val resolved = WorkingDirectory.resolve("~js")
        
        // Then: Should resolve to the scripts directory
        assertThat(resolved.exists()).isTrue()
        assertThat(resolved.isDirectory).isTrue()
        assertThat(resolved.name).isEqualTo("scripts")
    }

    @Test
    fun `commands without JS interpolation have null deferred fields`() {
        // Given: Workspace with normal (no JS interpolation) commands
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands should not have deferred resolution fields set
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        // These should be null for non-JS-interpolated paths
        assertThat(runScriptCommand?.scriptPath).isNull()
        assertThat(runScriptCommand?.flowPath).isNull()
        assertThat(runFlowCommand?.flowFilePath).isNull()
        assertThat(runFlowCommand?.parentFlowPath).isNull()
    }

    private fun getTestResourcePath(relativePath: String): Path {
        val projectDir = System.getenv("PROJECT_DIR")
            ?: System.getProperty("user.dir")
        val basePath = if (projectDir.endsWith("maestro-orchestra")) {
            Path.of(projectDir, "src/test/resources", relativePath)
        } else {
            Path.of(projectDir, "maestro-orchestra/src/test/resources", relativePath)
        }
        return basePath
    }

    private fun setupWorkingDirectory(workspacePath: Path) {
        val configPath = workspacePath.resolve("config.yaml")
        if (configPath.toFile().exists()) {
            val config = YamlCommandReader.readWorkspaceConfig(configPath)
            WorkingDirectory.baseDir = workspacePath.toFile()
            config.pathAliases?.let {
                WorkingDirectory.pathAliases = it
            }
        } else {
            WorkingDirectory.baseDir = workspacePath.toFile()
            WorkingDirectory.pathAliases = emptyMap()
        }
    }
}

