# Maestro Plugins

Plugin system for extending Maestro with custom commands.

## Overview

The plugin system allows you to create custom commands that can be used in Maestro test flows. Plugins are automatically discovered from the classpath using Java's ServiceLoader mechanism.

## Creating a Plugin

### 1. Implement CommandPlugin

Create a class that implements `CommandPlugin<T>` where T is your command data class:

```kotlin
package com.example.plugins

import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import com.fasterxml.jackson.core.JsonLocation

data class MyCommandData(val message: String)

class MyCustomPlugin : CommandPlugin<MyCommandData> {
    override val commandName = "myCommand"
    override val commandClass = MyCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): MyCommandData {
        return when (yamlContent) {
            is String -> MyCommandData(yamlContent)
            is Map<*, *> -> MyCommandData(yamlContent["message"] as String)
            else -> throw IllegalArgumentException("Invalid format")
        }
    }
    
    override suspend fun executeCommand(
        commandData: MyCommandData, 
        context: PluginExecutionContext
    ): Boolean {
        println(commandData.message)
        return false // return true if command mutates UI
    }
    
    override fun getDescription(commandData: MyCommandData): String {
        return "My command: ${commandData.message}"
    }
}
```

### 2. Register with ServiceLoader

Create a file at `src/main/resources/META-INF/services/maestro.plugins.CommandPlugin`:

```
com.example.plugins.MyCustomPlugin
```

List one fully-qualified class name per line for each plugin you want to register.

### 3. Package as JAR

Build your plugin as a JAR and add it to Maestro's classpath. The plugin will be automatically discovered and registered at runtime.

## Using Plugins in YAML

Once registered, use your plugin in Maestro test flows:

```yaml
appId: com.example.app
---
# Simple string format
- myCommand: "Hello World"

# Object format
- myCommand:
    message: "Hello World"
    label: "Greeting"
    optional: true
```

## Built-in Plugins

### WaitPlugin

Pauses test execution for a specified duration.

**Usage:**
```yaml
# Wait 2 seconds
- wait: 2

# Wait with object notation
- wait:
    seconds: 2.5

# Wait with expression
- wait: ${DELAY_TIME}
```

## Plugin Discovery

Plugins are discovered in this order:

1. **Built-in plugins** - Manually registered plugins bundled with Maestro
2. **Classpath plugins** - Auto-discovered via ServiceLoader from JARs

If a plugin with the same command name is registered multiple times, the first registration wins and subsequent attempts are logged and skipped.

## Thread Safety

The plugin registry is thread-safe:
- Atomic initialization ensures plugins load exactly once
- ConcurrentHashMap provides thread-safe storage
- Multiple threads can safely register and retrieve plugins concurrently

## Testing

Test your plugins using the same infrastructure:

```kotlin
class MyPluginTest {
    @BeforeEach
    fun setup() {
        PluginRegistry.clear()
        PluginRegistry.registerPlugin(MyCustomPlugin())
    }
    
    @Test
    fun testPlugin() {
        val plugin = PluginRegistry.getPlugin("myCommand")
        assertNotNull(plugin)
    }
}