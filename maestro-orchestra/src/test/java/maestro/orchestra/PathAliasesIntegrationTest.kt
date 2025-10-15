package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.WorkingDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/**
 * Integration tests for path aliases and JavaScript interpolation in runFlow and runScript commands.
 * 
 * These tests verify that:
 * 1. Path aliases (like ~js, ~sf) are correctly resolved
 * 2. JavaScript interpolation in file paths (like ${output.var}) works
 * 3. Both features can be combined
 */
class PathAliasesIntegrationTest {
    
    @AfterEach
    fun cleanup() {
        // Reset WorkingDirectory to defaults
        WorkingDirectory.baseDir = File(System.getProperty("user.dir"))
        WorkingDirectory.pathAliases = emptyMap()
    }

    @Test
    fun `runScript with JS interpolation placeholder is parsed correctly`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runscript-js-interpolation.yaml")
        
        // When: Parse flow with runScript using JS interpolation in path
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: RunScript command preserves the JS interpolation pattern
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        // The scriptPath should contain the original pattern with JS variable
        assertThat(runScriptCommand?.scriptPath).contains("\${output.scriptName}")
        assertThat(runScriptCommand?.scriptPath).contains("~js/")
        assertThat(runScriptCommand?.flowPath).isNotNull()
    }

    @Test
    fun `runFlow with JS interpolation placeholder is parsed correctly`() {
        // Given: Workspace with path aliases configured
        val workspacePath = getTestResourcePath("workspaces/017_js-interpolation/workspace")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        
        // When: Parse flow with runFlow using JS interpolation in path
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: RunFlow command preserves the JS interpolation pattern
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        // The flowFilePath should contain the original pattern with JS variable
        assertThat(runFlowCommand?.flowFilePath).contains("\${output.flowName}")
        assertThat(runFlowCommand?.flowFilePath).contains("~sf/")
        assertThat(runFlowCommand?.parentFlowPath).isNotNull()
    }

    @Test
    fun `config-in-root structure with path aliases`() {
        // Given: Workspace with config in root directory
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-root")
        setupWorkingDirectory(workspacePath)
        
        val flowPath = workspacePath.resolve("flows/flow.yaml")
        
        // When: Parse flow that uses path aliases
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands are parsed correctly
        assertThat(commands).isNotEmpty()
        
        // Verify runFlow command is present with correct source description
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("~sf/subflow.yaml")
        
        // Verify runScript command is present with correct source description
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
        val workspacePath = getTestResourcePath("workspaces/016_path-aliases/config-in-folder")
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
        
        // Verify commands use the aliased paths
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

    private fun getTestResourcePath(relativePath: String): Path {
        val projectDir = System.getenv("PROJECT_DIR") 
            ?: System.getProperty("user.dir")
        // Check if we're already in the maestro-orchestra directory
        val basePath = if (projectDir.endsWith("maestro-orchestra")) {
            Path.of(projectDir, "src/test/resources", relativePath)
        } else {
            Path.of(projectDir, "maestro-orchestra/src/test/resources", relativePath)
        }
        return basePath
    }

    private fun setupWorkingDirectory(workspacePath: Path) {
        // Load config.yaml if it exists
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

