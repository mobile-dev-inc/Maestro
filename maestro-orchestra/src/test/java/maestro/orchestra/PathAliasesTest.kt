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
 * Tests for path aliases and JavaScript interpolation in runFlow and runScript commands.
 * 
 * Validates:
 * 1. Path aliases (like ~js, ~sf) are correctly resolved during parsing
 * 2. Different workspace config structures (config-in-root, config-in-folder)
 * 3. JavaScript interpolation in file paths (like ${output.var})
 * 4. Error handling for invalid paths and aliases
 */
class PathAliasesTest {
    
    @AfterEach
    fun cleanup() {
        WorkingDirectory.baseDir = File(System.getProperty("user.dir"))
        WorkingDirectory.pathAliases = emptyMap()
    }

    // === Config Structure Tests ===

    @Test
    fun `config-in-root structure with path aliases`() {
        // Given: Workspace with config in root directory
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow that uses path aliases
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands are parsed correctly
        assertThat(commands).isNotEmpty()
        
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("~sf/subflow.yaml")
        
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        assertThat(runScriptCommand?.sourceDescription).isEqualTo("~js/script.js")
    }

    @Test
    fun `config-in-folder structure with relative path aliases`() {
        // Given: Workspace with config in .maestro folder
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-folder/workspace")
        val maestroDir = workspacePath.resolve(".maestro")
        setupWorkingDirectory(maestroDir)
        
        // Configure path aliases with relative parent paths
        WorkingDirectory.pathAliases = mapOf(
            "~js" to "../scripts",
            "~sf" to "../subflows"
        )
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow that uses path aliases
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands are parsed correctly
        assertThat(commands).isNotEmpty()
        
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("~sf/subflow.yaml")
        
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand?.sourceDescription).isEqualTo("~js/script.js")
    }

    // === Path Alias Resolution Tests ===

    @Test
    fun `runScript command with path alias loads script content`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow with runScript using path alias
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Script content should be loaded successfully
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        assertThat(runScriptCommand?.sourceDescription).isEqualTo("~js/script.js")
        assertThat(runScriptCommand?.script).contains("console.log")
    }

    @Test
    fun `runFlow command with path alias loads subflow`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow with runFlow using path alias
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Subflow should be loaded successfully
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("~sf/subflow.yaml")
        assertThat(runFlowCommand?.commands).isNotEmpty()
    }

    @Test
    fun `resolvePathWithAliases handles absolute paths correctly`() {
        // Given: Workspace with aliases
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
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
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
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
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
        setupWorkingDirectory(workspacePath)
        
        // When: Resolve just the alias itself (no additional path)
        val resolved = WorkingDirectory.resolve("~js")
        
        // Then: Should resolve to the scripts directory
        assertThat(resolved.exists()).isTrue()
        assertThat(resolved.isDirectory).isTrue()
        assertThat(resolved.name).isEqualTo("scripts")
    }

    // === JavaScript Interpolation Tests ===

    @Test
    fun `runScript with JS interpolation preserves pattern for deferred resolution`() {
        // Given: Workspace with JS interpolation in script path
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runscript-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.scriptName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should preserve JS interpolation pattern for runtime resolution
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
    fun `runFlow with JS interpolation preserves pattern for deferred resolution`() {
        // Given: Workspace with JS interpolation in flow path
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.flowName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should preserve JS interpolation pattern for runtime resolution
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
    fun `commands without JS interpolation have null deferred fields`() {
        // Given: Workspace with normal (no JS interpolation) commands
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
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

    // === Error Handling Tests ===

    @Test
    fun `invalid alias in runScript path throws error during parsing`() {
        // Given: Workspace with aliases configured
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases_config-in-root/workspace")
        setupWorkingDirectory(workspacePath)
        
        // Create a temporary flow with invalid alias
        val tempFlow = workspacePath.resolve("flows").resolve("temp-invalid.yaml")
        tempFlow.toFile().writeText("""
            appId: com.example
            ---
            - runScript: '~invalid/nonexistent.js'
        """.trimIndent())
        
        try {
            // When/Then: Should throw error because path doesn't exist
            assertThrows<SyntaxError> {
                YamlCommandReader.readCommands(tempFlow)
            }
        } finally {
            tempFlow.toFile().delete()
        }
    }

    // === Helper Methods ===

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

