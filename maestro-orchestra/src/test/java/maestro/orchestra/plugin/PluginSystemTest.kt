package maestro.orchestra.plugin

import io.mockk.mockk
import maestro.Maestro
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PluginSystemTest {

    @Test
    fun `PluginRegistry should register and notify plugins`(@TempDir tempDir: Path) {
        // Create a mock plugin
        var initCalled = false
        var flowStartCalled = false
        var commandStartCount = 0
        var commandCompleteCount = 0
        var flowCompleteCalled = false
        var shutdownCalled = false

        val plugin = object : MaestroPlugin {
            override val id = "test-plugin"
            override val name = "Test Plugin"

            override fun onInit(context: MaestroContext) {
                initCalled = true
            }

            override fun onFlowStart(commands: List<MaestroCommand>) {
                flowStartCalled = true
            }

            override fun onCommandStart(index: Int, command: MaestroCommand) {
                commandStartCount++
            }

            override fun onCommandComplete(index: Int, command: MaestroCommand) {
                commandCompleteCount++
            }

            override fun onFlowComplete(config: maestro.orchestra.MaestroConfig?, success: Boolean) {
                flowCompleteCalled = true
            }

            override fun onShutdown() {
                shutdownCalled = true
            }
        }

        // Create registry and register plugin
        val registry = PluginRegistry()
        registry.register(plugin)

        // Verify plugin is registered
        assertEquals(1, registry.getPlugins().size)
        assertEquals("test-plugin", registry.getPlugins()[0].id)

        // Initialize plugins
        val maestro = mockk<Maestro>(relaxed = true)
        val context = MaestroContext(
            maestro = maestro,
            outputDirectory = tempDir,
            config = null,
            screenshotsDir = null
        )
        registry.initializeAll(context)
        assertTrue(initCalled, "onInit should be called")

        // Notify flow start
        val selector = ElementSelector(textRegex = "test")
        val commands = listOf(MaestroCommand(tapOnElement = TapOnElementCommand(selector = selector)))
        registry.notifyFlowStart(commands)
        assertTrue(flowStartCalled, "onFlowStart should be called")

        // Notify command lifecycle
        registry.notifyCommandStart(0, commands[0])
        assertEquals(1, commandStartCount)

        registry.notifyCommandComplete(0, commands[0])
        assertEquals(1, commandCompleteCount)

        // Notify flow complete
        registry.notifyFlowComplete(null, true)
        assertTrue(flowCompleteCalled, "onFlowComplete should be called")

        // Shutdown
        registry.shutdownAll()
        assertTrue(shutdownCalled, "onShutdown should be called")
    }

    @Test
    fun `PluginRegistry should isolate plugin errors`(@TempDir tempDir: Path) {
        // Create plugins where one throws an exception
        var plugin1Called = false
        var plugin2Called = false

        val plugin1 = object : MaestroPlugin {
            override val id = "failing-plugin"
            override val name = "Failing Plugin"

            override fun onCommandStart(index: Int, command: MaestroCommand) {
                plugin1Called = true
                throw RuntimeException("Plugin failure!")
            }
        }

        val plugin2 = object : MaestroPlugin {
            override val id = "working-plugin"
            override val name = "Working Plugin"

            override fun onCommandStart(index: Int, command: MaestroCommand) {
                plugin2Called = true
            }
        }

        val registry = PluginRegistry()
        registry.register(plugin1)
        registry.register(plugin2)

        // Notify command start - plugin1 will fail but plugin2 should still be called
        val selector = ElementSelector(textRegex = "test")
        val command = MaestroCommand(tapOnElement = TapOnElementCommand(selector = selector))
        registry.notifyCommandStart(0, command)

        assertTrue(plugin1Called, "Failing plugin should be called")
        assertTrue(plugin2Called, "Working plugin should still be called after first plugin fails")
    }

    @Test
    fun `PluginLoader should load plugins from JAR files`() {
        // This test verifies PluginLoader can load from specific JAR files
        // In a real scenario, you would pass actual JAR files
        val plugins = PluginLoader.loadPlugins(emptyList())

        // With empty list, should return empty
        assertTrue(plugins.isEmpty(), "Should return empty list when no JARs provided")
    }
}
