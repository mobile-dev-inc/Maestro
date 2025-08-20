package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class DependencyResolverTest {
    
    @Test
    fun `test dependency discovery for single flow file`(@TempDir tempDir: Path) {
        // Create a main flow file
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow: subflow1.yaml
            - runFlow: subflow2.yaml
            - runScript: validation.js
            - addMedia:
              - "images/logo.png"
        """.trimIndent())
        
        // Create subflow files
        val subflow1 = tempDir.resolve("subflow1.yaml")
        subflow1.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        val subflow2 = tempDir.resolve("subflow2.yaml")
        subflow2.writeText("""
            appId: com.example.app
            ---
            - runFlow: nested_subflow.yaml
        """.trimIndent())
        
        // Create nested subflow
        val nestedSubflow = tempDir.resolve("nested_subflow.yaml")
        nestedSubflow.writeText("""
            appId: com.example.app
            ---
            - assertVisible: "Text"
        """.trimIndent())
        
        // Create script file
        val script = tempDir.resolve("validation.js")
        script.writeText("console.log('validation script');")
        
        // Create media file
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val mediaFile = mediaDir.resolve("logo.png")
        mediaFile.writeText("fake png content")
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should include all files
        assertThat(dependencies).hasSize(6)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(subflow1)
        assertThat(dependencies).contains(subflow2)
        assertThat(dependencies).contains(nestedSubflow)
        assertThat(dependencies).contains(script)
        assertThat(dependencies).contains(mediaFile)
    }
    
    @Test
    fun `test dependency summary generation`(@TempDir tempDir: Path) {
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow: subflow.yaml
            - runScript: script.js
            - addMedia:
              - "images/logo.png"
        """.trimIndent())
        
        val subflow = tempDir.resolve("subflow.yaml")
        subflow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        val script = tempDir.resolve("script.js")
        script.writeText("console.log('test');")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val mediaFile = mediaDir.resolve("logo.png")
        mediaFile.writeText("fake png content")
        
        val summary = DependencyResolver.getDependencySummary(mainFlow)
        
        assertThat(summary).contains("Total files: 4")
        assertThat(summary).contains("Subflows: 1")
        assertThat(summary).contains("Scripts: 1")
        assertThat(summary).contains("Other files: 1")
    }
    
    @Test
    fun `test enhanced dependency discovery finds all types`(@TempDir tempDir: Path) {
        // Create a main flow file with runScript and addMedia
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow: subflow.yaml
            - runScript: script.js
            - addMedia:
              - "images/logo.png"
        """.trimIndent())
        
        val subflow = tempDir.resolve("subflow.yaml")
        subflow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        val script = tempDir.resolve("script.js")
        script.writeText("console.log('test');")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val mediaFile = mediaDir.resolve("logo.png")
        mediaFile.writeText("fake png content")
        
        // Test enhanced discovery (should find all dependencies)
        val enhancedDependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        assertThat(enhancedDependencies).hasSize(4)
        assertThat(enhancedDependencies).contains(script)
        assertThat(enhancedDependencies).contains(mediaFile)
        assertThat(enhancedDependencies).contains(subflow)
    }
}
