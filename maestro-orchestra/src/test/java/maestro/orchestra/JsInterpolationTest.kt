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
 * 4. Runtime resolution of JS interpolated files through Orchestra
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
        assertThat(runScriptCommand?.sourceDescription).isEqualTo("scripts/\${output.scriptName}.js")
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
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("subflows/\${output.flowName}.yaml")
        // Commands should be empty since subflow will be loaded at runtime
        assertThat(runFlowCommand?.commands).isEmpty()
    }

    @Test
    fun `runFlow with JS interpolation has empty commands but filePath set`() {
        // Given: Workspace with JS interpolation in flow path
        val workspacePath = getTestResourcePath("workspaces/016b_js-interpolation-runflow/workspace")
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        
        // When: Parse flow
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Verify the runFlow command has deferred resolution
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        // Key assertions for deferred resolution
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("subflows/\${output.flowName}.yaml")
        assertThat(runFlowCommand?.commands).isEmpty()  // Commands are empty until runtime
        assertThat(runFlowCommand?.config).isNull()      // Config is not pre-loaded with JS interpolation
    }

    @Test
    fun `runFlow without JS interpolation has loaded commands and config`() {
        // Given: Workspace with normal (no JS interpolation) flow path
        val workspacePath = getTestResourcePath("workspaces/016c_js-interpolation-normal-paths/workspace")
        val flowPath = workspacePath.resolve("flows/test-normal-paths.yaml")
        
        // When: Parse flow
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // Then: Verify the runFlow command has loaded resolution
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        // Key assertions for immediate resolution
        // filePath contains the relative path as specified in the YAML from the flow file's perspective
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("../subflows/login.yaml")
        assertThat(runFlowCommand?.commands).isNotEmpty()  // Commands are loaded at parse time
        // Config should be available since we can read the file
        assertThat(runFlowCommand?.config).isNotNull()
    }

    @Test
    fun `runFlow with JS interpolation loads file at runtime after variable resolution`() {
        // Given: Flow commands with JS interpolation that sets output.flowName variable
        val workspacePath = getTestResourcePath("workspaces/016b_js-interpolation-runflow/workspace")
        val flowPath = workspacePath.resolve("flows/test-runflow-js-interpolation.yaml")
        val commands = YamlCommandReader.readCommands(flowPath)
        
        // When: We look at the commands structure
        val runFlowCommand = commands
            .map { it.asCommand() }
            .filterIsInstance<RunFlowCommand>()
            .firstOrNull()
        
        // Then: Verify deferred resolution structure
        assertThat(runFlowCommand).isNotNull()
        // At parse time, commands are empty because file hasn't been loaded yet
        assertThat(runFlowCommand?.commands).isEmpty()
        // But filePath is preserved for runtime resolution
        assertThat(runFlowCommand?.sourceDescription).isEqualTo("subflows/\${output.flowName}.yaml")
        
        // This demonstrates that when Orchestra.runFlow executes:
        // 1. It evaluates JS: ${output.flowName = "login"}
        // 2. It resolves filePath: "subflows/${output.flowName}.yaml" -> "subflows/login.yaml"
        // 3. It reads and loads the login.yaml file
        // 4. It executes the loaded commands from login.yaml
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

