package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for JavaScript interpolation in runFlow and runScript commands.
 * 
 * Validates:
 * 1. JavaScript interpolation in file paths (like ${output.var})
 * 2. Deferred resolution of paths at runtime
 * 3. Commands without JS interpolation work normally
 */
class JsInterpolationTest {

    // === JavaScript Interpolation Tests ===

    @Test
    fun `runScript with JS interpolation preserves pattern for deferred resolution`() {
        // Given: Workspace with JS interpolation in script path
        val workspacePath = getTestResourcePath("workspaces/016a_js-interpolation-runscript/workspace")
        val flowPath = workspacePath.resolve("flows/test-runscript-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.scriptName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should preserve JS interpolation pattern for runtime resolution
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        assertThat(runScriptCommand).isNotNull()
        assertThat(runScriptCommand?.filePath).isEqualTo("scripts/\${output.scriptName}.js")
        // flowPath is no longer set - it's resolved from Orchestra context at runtime
        assertThat(runScriptCommand?.flowPath).isNull()
        // Script content should be empty since it will be loaded at runtime
        assertThat(runScriptCommand?.script).isEmpty()
    }

    @Test
    fun `runFlow with JS interpolation preserves pattern for deferred resolution`() {
        // Given: Workspace with JS interpolation in flow path
        val workspacePath = getTestResourcePath("workspaces/016b_js-interpolation-runflow/workspace")
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        
        // When: Parse flow (should not fail even though ${output.flowName} doesn't exist yet)
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Command should preserve JS interpolation pattern for runtime resolution
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        assertThat(runFlowCommand).isNotNull()
        assertThat(runFlowCommand?.filePath).isEqualTo("subflows/\${output.flowName}.yaml")
        // parentFlowPath is no longer set - it's resolved from Orchestra context at runtime
        assertThat(runFlowCommand?.parentFlowPath).isNull()
        // Commands should be empty since subflow will be loaded at runtime
        assertThat(runFlowCommand?.commands).isEmpty()
    }

    @Test
    fun `commands without JS interpolation load files normally`() {
        // Given: Workspace with normal (no JS interpolation) commands
        val workspacePath = getTestResourcePath("workspaces/016c_js-interpolation-normal-paths/workspace")
        val flowPath = workspacePath.resolve("flows/test-normal-paths.yaml")
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Commands should have loaded content immediately
        val runScriptCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunScriptCommand>()
            .firstOrNull()
        
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        // scriptPath and flowFilePath are always set when there's a file to resolve
        assertThat(runScriptCommand?.filePath).isNotNull()
        assertThat(runFlowCommand?.filePath).isNotNull()
        // These are never set anymore - resolution uses Orchestra context
        assertThat(runScriptCommand?.flowPath).isNull()
        assertThat(runFlowCommand?.parentFlowPath).isNull()
        
        // And content should be loaded
        assertThat(runScriptCommand?.script).isNotEmpty()
        assertThat(runFlowCommand?.commands).isNotEmpty()
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
}

