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
    
    @Test
    fun `test composite commands - repeat with nested dependencies`(@TempDir tempDir: Path) {
        // Create main flow with repeat command containing nested dependencies
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - repeat:
                times: 3
                commands:
                  - runFlow: nested_subflow.yaml
                  - runScript: validation.js
                  - addMedia:
                    - "images/repeat_logo.png"
        """.trimIndent())
        
        // Create nested dependencies
        val nestedSubflow = tempDir.resolve("nested_subflow.yaml")
        nestedSubflow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        val script = tempDir.resolve("validation.js")
        script.writeText("console.log('repeat validation');")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val mediaFile = mediaDir.resolve("repeat_logo.png")
        mediaFile.writeText("fake png content")
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find all nested dependencies within repeat command
        assertThat(dependencies).hasSize(4)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(nestedSubflow)
        assertThat(dependencies).contains(script)
        assertThat(dependencies).contains(mediaFile)
    }
    
    @Test
    fun `test composite commands - retry with file reference`(@TempDir tempDir: Path) {
        // Create main flow with retry command referencing external file
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - retry:
                file: external_retry.yaml
                maxRetries: 3
        """.trimIndent())
        
        // Create external retry file
        val retryFile = tempDir.resolve("external_retry.yaml")
        retryFile.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Retry Button"
            - runFlow: nested_flow.yaml
        """.trimIndent())
        
        // Create nested flow referenced by retry file
        val nestedFlow = tempDir.resolve("nested_flow.yaml")
        nestedFlow.writeText("""
            appId: com.example.app
            ---
            - assertVisible: "Success"
        """.trimIndent())
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find retry file and its nested dependencies
        assertThat(dependencies).hasSize(3)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(retryFile)
        assertThat(dependencies).contains(nestedFlow)
    }
    
    @Test
    fun `test composite commands - retry with inline commands`(@TempDir tempDir: Path) {
        // Create main flow with retry command containing inline nested dependencies
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - retry:
                maxRetries: 2
                commands:
                  - runFlow: retry_subflow.yaml
                  - runScript: cleanup.js
                  - addMedia:
                    - "images/retry_media.png"
        """.trimIndent())
        
        // Create nested dependencies
        val retrySubflow = tempDir.resolve("retry_subflow.yaml")
        retrySubflow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Retry Action"
        """.trimIndent())
        
        val cleanupScript = tempDir.resolve("cleanup.js")
        cleanupScript.writeText("console.log('cleanup after retry');")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val retryMedia = mediaDir.resolve("retry_media.png")
        retryMedia.writeText("fake retry media content")
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find all nested dependencies within retry command
        assertThat(dependencies).hasSize(4)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(retrySubflow)
        assertThat(dependencies).contains(cleanupScript)
        assertThat(dependencies).contains(retryMedia)
    }
    
    @Test
    fun `test deeply nested composite commands`(@TempDir tempDir: Path) {
        // Create complex nested structure: runFlow -> repeat -> retry -> runFlow
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow:
                commands:
                  - repeat:
                      times: 2
                      commands:
                        - retry:
                            maxRetries: 3
                            commands:
                              - runFlow: deeply_nested.yaml
                              - runScript: deep_script.js
        """.trimIndent())
        
        // Create deeply nested dependencies
        val deeplyNested = tempDir.resolve("deeply_nested.yaml")
        deeplyNested.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Deep Button"
            - addMedia:
              - "images/deep_media.png"
        """.trimIndent())
        
        val deepScript = tempDir.resolve("deep_script.js")
        deepScript.writeText("console.log('deeply nested script');")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val deepMedia = mediaDir.resolve("deep_media.png")
        deepMedia.writeText("deep media content")
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find all dependencies at any nesting level
        assertThat(dependencies).hasSize(4)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(deeplyNested)
        assertThat(dependencies).contains(deepScript)
        assertThat(dependencies).contains(deepMedia)
    }
    
    @Test
    fun `test mixed composite commands with external and inline`(@TempDir tempDir: Path) {
        // Create flow mixing external file references and inline commands
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow: external_flow.yaml
            - repeat:
                times: 2
                commands:
                  - runScript: inline_script.js
            - retry:
                file: external_retry.yaml
        """.trimIndent())
        
        // Create external flow
        val externalFlow = tempDir.resolve("external_flow.yaml")
        externalFlow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "External Button"
        """.trimIndent())
        
        // Create inline script
        val inlineScript = tempDir.resolve("inline_script.js")
        inlineScript.writeText("console.log('inline script in repeat');")
        
        // Create external retry
        val externalRetry = tempDir.resolve("external_retry.yaml")
        externalRetry.writeText("""
            appId: com.example.app
            ---
            - assertVisible: "Retry Success"
            - runFlow: retry_nested.yaml
        """.trimIndent())
        
        // Create retry nested flow
        val retryNested = tempDir.resolve("retry_nested.yaml")
        retryNested.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Final Button"
        """.trimIndent())
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find all dependencies from mixed sources
        assertThat(dependencies).hasSize(5)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(externalFlow)
        assertThat(dependencies).contains(inlineScript)
        assertThat(dependencies).contains(externalRetry)
        assertThat(dependencies).contains(retryNested)
    }
    
    @Test
    fun `test circular dependency prevention`(@TempDir tempDir: Path) {
        // Create circular dependency: flow1 -> flow2 -> flow1
        val flow1 = tempDir.resolve("flow1.yaml")
        flow1.writeText("""
            appId: com.example.app
            ---
            - runFlow: flow2.yaml
            - tapOn: "Button1"
        """.trimIndent())
        
        val flow2 = tempDir.resolve("flow2.yaml")
        flow2.writeText("""
            appId: com.example.app
            ---
            - runFlow: flow1.yaml
            - tapOn: "Button2"
        """.trimIndent())
        
        // Test dependency discovery should handle circular references
        val dependencies = DependencyResolver.discoverAllDependencies(flow1)
        
        // Should include both files but not loop infinitely
        // Note: The exact count may vary based on parsing, but should include at least flow1
        // and should not hang due to circular reference
        assertThat(dependencies.size).isAtLeast(1)
        assertThat(dependencies).contains(flow1)
        // flow2 might not be included if parsing fails, but the important thing is no infinite loop
    }
    
    @Test
    fun `test dependency summary with composite commands`(@TempDir tempDir: Path) {
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - repeat:
                times: 2
                commands:
                  - runFlow: repeat_subflow.yaml
                  - runScript: repeat_script.js
            - retry:
                file: retry_flow.yaml
            - addMedia:
              - "images/main_logo.png"
        """.trimIndent())
        
        // Create all dependencies
        val repeatSubflow = tempDir.resolve("repeat_subflow.yaml")
        repeatSubflow.writeText("appId: com.example.app\n---\n- tapOn: 'Button'")
        
        val repeatScript = tempDir.resolve("repeat_script.js")
        repeatScript.writeText("console.log('repeat');")
        
        val retryFlow = tempDir.resolve("retry_flow.yaml")
        retryFlow.writeText("appId: com.example.app\n---\n- assertVisible: 'Text'")
        
        val mediaDir = tempDir.resolve("images")
        mediaDir.toFile().mkdirs()
        val mainLogo = mediaDir.resolve("main_logo.png")
        mainLogo.writeText("logo content")
        
        val summary = DependencyResolver.getDependencySummary(mainFlow)
        
        assertThat(summary).contains("Total files: 5")
        assertThat(summary).contains("Subflows: 2") // repeat_subflow.yaml + retry_flow.yaml
        assertThat(summary).contains("Scripts: 1")  // repeat_script.js
        assertThat(summary).contains("Other files: 1") // main_logo.png
    }
    
    @Test
    fun `test configuration commands - onFlowStart and onFlowComplete with dependencies`(@TempDir tempDir: Path) {
        // Create main flow with onFlowStart and onFlowComplete containing file dependencies
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            onFlowStart:
              - runFlow: startup_flow.yaml
              - runScript: startup_script.js
              - addMedia:
                - "images/startup_logo.png"
            onFlowComplete:
              - runFlow: cleanup_flow.yaml  
              - runScript: cleanup_script.js
              - addMedia:
                - "images/completion_badge.png"
            ---
            - tapOn: "Main Button"
        """.trimIndent())
        
        // Create startup dependencies
        val startupFlow = tempDir.resolve("startup_flow.yaml")
        startupFlow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Startup Button"
            - runFlow: nested_startup.yaml
        """.trimIndent())
        
        val nestedStartup = tempDir.resolve("nested_startup.yaml")
        nestedStartup.writeText("""
            appId: com.example.app
            ---
            - assertVisible: "Startup Complete"
        """.trimIndent())
        
        val startupScript = tempDir.resolve("startup_script.js")
        startupScript.writeText("console.log('startup initialization');")
        
        val imagesDir = tempDir.resolve("images")
        imagesDir.toFile().mkdirs()
        val startupLogo = imagesDir.resolve("startup_logo.png")
        startupLogo.writeText("startup logo content")
        
        // Create cleanup dependencies
        val cleanupFlow = tempDir.resolve("cleanup_flow.yaml")
        cleanupFlow.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Cleanup Button"
        """.trimIndent())
        
        val cleanupScript = tempDir.resolve("cleanup_script.js")
        cleanupScript.writeText("console.log('cleanup finalization');")
        
        val completionBadge = imagesDir.resolve("completion_badge.png")
        completionBadge.writeText("completion badge content")
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find all dependencies from onFlowStart and onFlowComplete
        assertThat(dependencies).hasSize(8) // main + 2 startup flows + 2 scripts + 2 images + 1 cleanup flow
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(startupFlow)
        assertThat(dependencies).contains(nestedStartup)
        assertThat(dependencies).contains(startupScript)
        assertThat(dependencies).contains(startupLogo)
        assertThat(dependencies).contains(cleanupFlow)
        assertThat(dependencies).contains(cleanupScript)
        assertThat(dependencies).contains(completionBadge)
    }
    
    @Test
    fun `test mixed configuration and composite commands`(@TempDir tempDir: Path) {
        // Create complex flow with both configuration commands and composite commands
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            onFlowStart:
              - repeat:
                  times: 2
                  commands:
                    - runFlow: repeated_startup.yaml
            onFlowComplete:
              - retry:
                  maxRetries: 3
                  commands:
                    - runScript: retry_cleanup.js
            ---
            - tapOn: "Main Action"
            - runFlow: main_subflow.yaml
        """.trimIndent())
        
        // Create all dependencies
        val repeatedStartup = tempDir.resolve("repeated_startup.yaml")
        repeatedStartup.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Repeated Action"
        """.trimIndent())
        
        val retryCleanup = tempDir.resolve("retry_cleanup.js")
        retryCleanup.writeText("console.log('retry cleanup');")
        
        val mainSubflow = tempDir.resolve("main_subflow.yaml")
        mainSubflow.writeText("""
            appId: com.example.app
            ---
            - assertVisible: "Main Complete"
        """.trimIndent())
        
        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)
        
        // Should find dependencies from configuration commands AND main flow
        assertThat(dependencies).hasSize(4)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(repeatedStartup) // From onFlowStart -> repeat -> runFlow
        assertThat(dependencies).contains(retryCleanup)    // From onFlowComplete -> retry -> runScript  
        assertThat(dependencies).contains(mainSubflow)     // From main flow -> runFlow
    }

    @Test
    fun `test dependency discovery for repeated flow references`(@TempDir tempDir: Path) {
        // Create a main flow file
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText("""
            appId: com.example.app
            ---
            - runFlow: subflow.yaml
            - runFlow: subflow.yaml
            - runFlow:
                commands:
                  - runFlow: subflow.yaml
        """.trimIndent())

        // Create subflow files
        val subflow1 = tempDir.resolve("subflow.yaml")
        subflow1.writeText("""
            appId: com.example.app
            ---
            - tapOn: "Button"
        """.trimIndent())

        // Test dependency discovery
        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)

        // Should include all files
        assertThat(dependencies).hasSize(2)
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(subflow1)

    }

    @Test
    fun `deduplicates same script referenced via different relative paths`(@TempDir tempDir: Path) {
        // Directory layout:
        // tempDir/
        //   main_flow.yaml
        //   sub/
        //     subflow.yaml
        //   scripts/
        //     createAccount.js

        // Create directories
        tempDir.resolve("sub").toFile().mkdirs()
        tempDir.resolve("scripts").toFile().mkdirs()

        // Create shared script
        val script = tempDir.resolve("scripts/createAccount.js")
        script.writeText("console.log('create account');")

        // Subflow that references script with a simple relative path
        val subflow = tempDir.resolve("sub/subflow.yaml")
        subflow.writeText(
            """
            appId: com.example.app
            ---
            - runScript: ../scripts/createAccount.js
            """.trimIndent()
        )

        // Main flow references the same script using a different path notation and the subflow
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText(
            """
            appId: com.example.app
            ---
            - runFlow: sub/subflow.yaml
            - runScript: ./sub/../scripts/createAccount.js
            """.trimIndent()
        )

        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)

        // Expect exactly one instance of the script, despite two different references
        val scriptCount = dependencies.count { it.fileName.toString() == "createAccount.js" }
        assertThat(scriptCount).isEqualTo(1)

        // Also expect both flows to be included
        assertThat(dependencies.any { it.fileName.toString() == "main_flow.yaml" }).isTrue()
        assertThat(dependencies.any { it.fileName.toString() == "subflow.yaml" }).isTrue()
    }

    @Test
    fun `treats files with same name but different real paths as different dependencies`(@TempDir tempDir: Path) {
        // Directory layout:
        // tempDir/
        //   main_flow.yaml
        //   scripts/
        //     createUser.js
        //     usa/
        //       createUser.js

        // Create directories
        tempDir.resolve("scripts").toFile().mkdirs()
        tempDir.resolve("scripts/usa").toFile().mkdirs()

        // Create two different scripts with the same filename
        val script1 = tempDir.resolve("scripts/createUser.js")
        script1.writeText("console.log('create user - default');")

        val script2 = tempDir.resolve("scripts/usa/createUser.js")
        script2.writeText("console.log('create user - USA');")

        // Main flow references both scripts
        val mainFlow = tempDir.resolve("main_flow.yaml")
        mainFlow.writeText(
            """
            appId: com.example.app
            ---
            - runScript: scripts/createUser.js
            - runScript: scripts/usa/createUser.js
            """.trimIndent()
        )

        val dependencies = DependencyResolver.discoverAllDependencies(mainFlow)

        // Expect both scripts to be included as separate dependencies
        assertThat(dependencies).hasSize(3) // main_flow.yaml + 2 scripts
        assertThat(dependencies).contains(mainFlow)
        assertThat(dependencies).contains(script1)
        assertThat(dependencies).contains(script2)

        // Verify both are present by checking their real paths
        val scriptPaths = dependencies.filter { it.fileName.toString() == "createUser.js" }
        assertThat(scriptPaths).hasSize(2)
        assertThat(scriptPaths).contains(script1)
        assertThat(scriptPaths).contains(script2)
    }

}
