package maestro.plugins

import maestro.js.JsEngine
import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import maestro.orchestra.InputTextCommand
import com.fasterxml.jackson.core.JsonLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PluginRegistryTest {
    
    // Test command data classes
    data class TestCommandData(
        val value: String,
        val number: Int = 0
    )
    
    data class AnotherCommandData(
        val message: String
    )
    
    // Mock plugin implementations
    class TestPlugin : CommandPlugin<TestCommandData> {
        override val commandName = "testCommand"
        override val commandClass = TestCommandData::class.java
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
            return when (yamlContent) {
                is Map<*, *> -> {
                    val value = yamlContent["value"] as? String ?: "default"
                    val number = yamlContent["number"] as? Int ?: 0
                    TestCommandData(value, number)
                }
                is String -> TestCommandData(yamlContent)
                else -> TestCommandData("default")
            }
        }
        
        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
            return true
        }
        
        override fun getDescription(commandData: TestCommandData): String {
            return "Test command: ${commandData.value}"
        }
    }
    
    class AnotherPlugin : CommandPlugin<AnotherCommandData> {
        override val commandName = "anotherCommand"
        override val commandClass = AnotherCommandData::class.java
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): AnotherCommandData {
            val message = when (yamlContent) {
                is Map<*, *> -> yamlContent["message"] as? String ?: "default"
                is String -> yamlContent
                else -> "default"
            }
            return AnotherCommandData(message)
        }
        
        override suspend fun executeCommand(commandData: AnotherCommandData, context: PluginExecutionContext): Boolean {
            return true
        }
        
        override fun getDescription(commandData: AnotherCommandData): String {
            return "Another command: ${commandData.message}"
        }
    }
    
    class InvalidNamePlugin : CommandPlugin<TestCommandData> {
        override val commandName = "invalid command" // Contains space
        override val commandClass = TestCommandData::class.java
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
            return TestCommandData("test")
        }
        
        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
            return true
        }
    }
    
    class BlankNamePlugin : CommandPlugin<TestCommandData> {
        override val commandName = "" // Blank name
        override val commandClass = TestCommandData::class.java
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
            return TestCommandData("test")
        }
        
        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
            return true
        }
    }
    
    @BeforeEach
    fun setup() {
        PluginRegistry.clear()
    }
    
    @AfterEach
    fun tearDown() {
        PluginRegistry.clear()
    }
    
    // ========== Initialization Tests ==========
    
    @Test
    fun `initialize should register built-in plugins`() {
        PluginRegistry.initialize()
        
        val registeredCommands = PluginRegistry.getRegisteredCommands()
        assertTrue(registeredCommands.isNotEmpty())
        assertTrue(registeredCommands.contains("wait"))
    }
    
    @Test
    fun `initialize should be idempotent - multiple calls don't duplicate plugins`() {
        PluginRegistry.initialize()
        val firstSize = PluginRegistry.getRegisteredCommands().size
        
        PluginRegistry.initialize()
        PluginRegistry.initialize()
        val secondSize = PluginRegistry.getRegisteredCommands().size
        
        assertEquals(firstSize, secondSize)
    }
    
    @Test
    fun `initialize should be thread-safe - concurrent initialization`() {
        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val initCounter = AtomicInteger(0)
        val threads = mutableListOf<Thread>()
        
        // Create threads that will all try to initialize at the same time
        repeat(threadCount) {
            threads.add(thread {
                try {
                    barrier.await() // Wait for all threads to be ready
                    PluginRegistry.initialize()
                    initCounter.incrementAndGet()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
        }
        
        // Wait for all threads to complete
        threads.forEach { it.join() }
        
        // All threads should have completed initialization
        assertEquals(threadCount, initCounter.get())
        
        // Should have registered plugins only once
        val registeredCommands = PluginRegistry.getRegisteredCommands()
        assertTrue(registeredCommands.isNotEmpty())
    }
    
    @Test
    fun `getPlugin should auto-initialize if not initialized`() {
        // Don't call initialize explicitly
        val plugin = PluginRegistry.getPlugin("wait")
        
        assertNotNull(plugin)
        assertEquals("wait", plugin?.commandName)
    }
    
    // ========== Plugin Registration Tests ==========
    
    @Test
    fun `registerPlugin should successfully register a plugin`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        
        PluginRegistry.registerPlugin(plugin)
        
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
        assertNotNull(PluginRegistry.getPlugin("testCommand"))
    }
    
    @Test
    fun `registerPlugin should register multiple plugins`() {
        PluginRegistry.initialize()
        val plugin1 = TestPlugin()
        val plugin2 = AnotherPlugin()
        
        PluginRegistry.registerPlugin(plugin1)
        PluginRegistry.registerPlugin(plugin2)
        
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
        assertTrue(PluginRegistry.isPluginCommand("anotherCommand"))
    }
    
    @Test
    fun `registerPlugin should throw when registering duplicate command name`() {
        PluginRegistry.initialize()
        val plugin1 = TestPlugin()
        val plugin2 = TestPlugin() // Same command name
        
        PluginRegistry.registerPlugin(plugin1)
        
        assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(plugin2)
        }
    }
    
    @Test
    fun `registerPlugin should throw when command name is blank`() {
        PluginRegistry.initialize()
        val plugin = BlankNamePlugin()
        
        val exception = assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(plugin)
        }
        
        assertTrue(exception.message!!.contains("cannot be blank"))
    }
    
    @Test
    fun `registerPlugin should throw when command name contains spaces`() {
        PluginRegistry.initialize()
        val plugin = InvalidNamePlugin()
        
        val exception = assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(plugin)
        }
        
        assertTrue(exception.message!!.contains("cannot contain spaces"))
    }
    
    // ========== Plugin Retrieval Tests ==========
    
    @Test
    fun `getPlugin by name should return registered plugin`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val retrieved = PluginRegistry.getPlugin("testCommand")
        
        assertNotNull(retrieved)
        assertEquals("testCommand", retrieved?.commandName)
    }
    
    @Test
    fun `getPlugin by name should return null for unregistered command`() {
        PluginRegistry.initialize()
        
        val retrieved = PluginRegistry.getPlugin("nonexistent")
        
        assertNull(retrieved)
    }
    
    @Test
    fun `getPlugin by class should return registered plugin`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val retrieved = PluginRegistry.getPlugin(TestCommandData::class.java)
        
        assertNotNull(retrieved)
        assertEquals("testCommand", retrieved?.commandName)
    }
    
    @Test
    fun `getPlugin by class should return null for unregistered class`() {
        PluginRegistry.initialize()
        
        val retrieved = PluginRegistry.getPlugin(String::class.java)
        
        assertNull(retrieved)
    }
    
    @Test
    fun `isPluginCommand should return true for registered command`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
    }
    
    @Test
    fun `isPluginCommand should return false for unregistered command`() {
        PluginRegistry.initialize()
        
        assertFalse(PluginRegistry.isPluginCommand("nonexistent"))
    }
    
    @Test
    fun `getRegisteredCommands should return all command names`() {
        PluginRegistry.initialize()
        val plugin1 = TestPlugin()
        val plugin2 = AnotherPlugin()
        PluginRegistry.registerPlugin(plugin1)
        PluginRegistry.registerPlugin(plugin2)
        
        val commands = PluginRegistry.getRegisteredCommands()
        
        assertTrue(commands.contains("testCommand"))
        assertTrue(commands.contains("anotherCommand"))
        assertTrue(commands.contains("wait")) // Built-in
    }
    
    // ========== Parse Plugin Command Tests ==========
    
    @Test
    fun `parsePluginCommand should parse and return PluginCommand`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val yamlContent = mapOf("value" to "test", "number" to 42)
        val location = mockk<JsonLocation>()
        
        val command = PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        
        assertNotNull(command)
        assertTrue(command is PluginCommand)
        assertEquals("testCommand", (command as PluginCommand).pluginName)
        assertEquals("Test command: test", command.pluginDescription)
    }
    
    @Test
    fun `parsePluginCommand should extract label and optional from yaml`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val yamlContent = mapOf(
            "value" to "test",
            "label" to "My Label",
            "optional" to true
        )
        val location = mockk<JsonLocation>()
        
        val command = PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        
        assertNotNull(command)
        val pluginCommand = command as PluginCommand
        assertEquals("My Label", pluginCommand.label)
        assertTrue(pluginCommand.optional)
    }
    
    @Test
    fun `parsePluginCommand should return null for unregistered command`() {
        PluginRegistry.initialize()
        
        val yamlContent = mapOf("value" to "test")
        val location = mockk<JsonLocation>()
        
        val command = PluginRegistry.parsePluginCommand("nonexistent", yamlContent, location)
        
        assertNull(command)
    }
    
    @Test
    fun `parsePluginCommand should throw PluginValidationException on parse error`() {
        PluginRegistry.initialize()
        
        // Register a plugin that will throw during parsing
        val plugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "failingCommand"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                throw RuntimeException("Parse failed")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
        }
        PluginRegistry.registerPlugin(plugin)
        
        val yamlContent = mapOf("value" to "test")
        val location = mockk<JsonLocation>()
        
        val exception = assertThrows(PluginValidationException::class.java) {
            PluginRegistry.parsePluginCommand("failingCommand", yamlContent, location)
        }
        
        assertTrue(exception.message!!.contains("Failed to parse plugin command"))
    }
    
    // ========== Execute Plugin Command Tests ==========
    
    @Test
    fun `executePluginCommand should execute successfully`() = runBlocking {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "testCommand",
            commandData = commandData,
            pluginDescription = "Test",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        val result = PluginRegistry.executePluginCommand(pluginCommand, context)
        
        assertTrue(result)
    }
    
    @Test
    fun `executePluginCommand should throw when command is not PluginCommand`() {
        PluginRegistry.initialize()
        
        // Use a real Command implementation instead of mockk for sealed interfaces
        val command = InputTextCommand(text = "test")
        val context = mockk<PluginExecutionContext>()
        
        assertThrows(PluginExecutionException::class.java) {
            runBlocking {
                PluginRegistry.executePluginCommand(command, context)
            }
        }
    }
    
    @Test
    fun `executePluginCommand should throw when plugin not found`() {
        PluginRegistry.initialize()
        
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "nonexistent",
            commandData = commandData,
            pluginDescription = "Test",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        assertThrows(PluginExecutionException::class.java) {
            runBlocking {
                PluginRegistry.executePluginCommand(pluginCommand, context)
            }
        }
    }
    
    @Test
    fun `executePluginCommand should throw PluginExecutionException on execution error`() {
        PluginRegistry.initialize()
        
        // Register a plugin that will throw during execution
        val plugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "failingExecution"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                throw RuntimeException("Execution failed")
            }
        }
        PluginRegistry.registerPlugin(plugin)
        
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "failingExecution",
            commandData = commandData,
            pluginDescription = "Test",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        val exception = assertThrows(PluginExecutionException::class.java) {
            runBlocking {
                PluginRegistry.executePluginCommand(pluginCommand, context)
            }
        }
        
        assertTrue(exception.message!!.contains("Failed to execute plugin command"))
    }
    
    // ========== Clear Tests ==========
    
    @Test
    fun `clear should remove all registered plugins`() {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
        
        PluginRegistry.clear()
        
        // After clear, registry should be empty and uninitialized
        // But accessing it should auto-initialize with built-ins
        val commands = PluginRegistry.getRegisteredCommands()
        assertFalse(commands.contains("testCommand"))
    }
    
    @Test
    fun `clear should reset initialization state`() {
        PluginRegistry.initialize()
        val firstCommands = PluginRegistry.getRegisteredCommands()
        
        PluginRegistry.clear()
        
        // After clear, should be able to initialize again
        PluginRegistry.initialize()
        val secondCommands = PluginRegistry.getRegisteredCommands()
        
        assertEquals(firstCommands.size, secondCommands.size)
    }
    
    // ========== Concurrent Access Tests ==========
    
    @Test
    fun `concurrent plugin registration should be thread-safe`() {
        PluginRegistry.initialize()
        val threadCount = 10
        val successCounter = AtomicInteger(0)
        val threads = mutableListOf<Thread>()
        
        repeat(threadCount) { index ->
            threads.add(thread {
                try {
                    // Each thread tries to register a uniquely named plugin
                    val plugin = object : CommandPlugin<TestCommandData> {
                        override val commandName = "command$index"
                        override val commandClass = TestCommandData::class.java
                        
                        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                            return TestCommandData("test")
                        }
                        
                        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                            return true
                        }
                    }
                    PluginRegistry.registerPlugin(plugin)
                    successCounter.incrementAndGet()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
        }
        
        threads.forEach { it.join() }
        
        // All registrations should succeed
        assertEquals(threadCount, successCounter.get())
        
        // All commands should be registered
        repeat(threadCount) { index ->
            assertTrue(PluginRegistry.isPluginCommand("command$index"))
        }
    }
    
    @Test
    fun `concurrent plugin retrieval should be thread-safe`() = runBlocking {
        PluginRegistry.initialize()
        val plugin = TestPlugin()
        PluginRegistry.registerPlugin(plugin)
        
        val jobCount = 100
        
        // Launch many concurrent retrieval operations
        val jobs = coroutineScope {
            (1..jobCount).map {
                async {
                    val retrieved = PluginRegistry.getPlugin("testCommand")
                    assertNotNull(retrieved)
                    assertEquals("testCommand", retrieved?.commandName)
                }
            }
        }
        
        jobs.awaitAll()
    }
    
    @Test
    fun `concurrent initialization and registration should be thread-safe`() {
        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val threads = mutableListOf<Thread>()
        val successCounter = AtomicInteger(0)
        
        repeat(threadCount) { index ->
            threads.add(thread {
                try {
                    barrier.await() // Synchronize start
                    
                    // Half threads initialize, half register
                    if (index % 2 == 0) {
                        PluginRegistry.initialize()
                    } else {
                        val plugin = object : CommandPlugin<TestCommandData> {
                            override val commandName = "concurrent$index"
                            override val commandClass = TestCommandData::class.java
                            
                            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                                return TestCommandData("test")
                            }
                            
                            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                                return true
                            }
                        }
                        PluginRegistry.registerPlugin(plugin)
                    }
                    successCounter.incrementAndGet()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
        }
        
        threads.forEach { it.join() }
        
        // All operations should complete
        assertEquals(threadCount, successCounter.get())
    }
}