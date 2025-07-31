# Plugin Template

This template can be copied to create new Maestro plugins.

## Quick Start

1. **Copy this directory structure** to create your plugin project
2. **Update `gradle.properties`** with your plugin information:

   ```properties
   pluginName=My Awesome Plugin
   pluginVersion=1.0.0
   pluginDescription=My custom Maestro automation commands
   pluginJarName=my-awesome-plugin
   ```

3. **Implement your plugin classes** in `src/main/kotlin/`
4. **Update ServiceLoader configuration** in `src/main/resources/META-INF/services/maestro.plugins.CommandPlugin`
5. **Build and test** your plugin

## File Structure

```
your-plugin-project/
├── build.gradle.kts          # Build configuration (copy from examples/plugins)
├── gradle.properties         # Plugin metadata configuration
├── README.md                 # Your plugin documentation
└── src/main/
    ├── kotlin/               # Your plugin source code
    │   └── your/package/
    │       ├── YourPlugin.kt
    │       └── YourCommandData.kt
    └── resources/
        └── META-INF/services/
            └── maestro.plugins.CommandPlugin  # ServiceLoader configuration
```

## Example Plugin Implementation

### 1. Command Data Class

```kotlin
data class MyCommandData(
    val parameter1: String,
    val parameter2: Int = 0,
    val optional: Boolean = false
)
```

### 2. Plugin Implementation

```kotlin
package your.package

import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation

class MyPlugin : CommandPlugin<MyCommandData> {
    override val commandName: String = "mycommand"
    override val commandClass: Class<MyCommandData> = MyCommandData::class.java

    override fun parseCommand(yamlContent: Any?, location: JsonLocation): MyCommandData {
        return when (yamlContent) {
            is String -> MyCommandData(parameter1 = yamlContent)
            is Map<*, *> -> {
                val param1 = yamlContent["parameter1"] as? String
                    ?: throw IllegalArgumentException("parameter1 is required")
                val param2 = (yamlContent["parameter2"] as? Number)?.toInt() ?: 0
                val optional = yamlContent["optional"] as? Boolean ?: false
                MyCommandData(param1, param2, optional)
            }
            else -> throw IllegalArgumentException("Invalid command format")
        }
    }

    override suspend fun executeCommand(commandData: MyCommandData, context: PluginExecutionContext): Boolean {
        // Your command logic here
        println("Executing: ${commandData.parameter1}")

        // Return true if command modifies UI, false otherwise
        return false
    }

    override fun getDescription(commandData: MyCommandData): String {
        return "My command: ${commandData.parameter1}"
    }

    override fun validateCommand(commandData: MyCommandData) {
        if (commandData.parameter1.isBlank()) {
            throw IllegalArgumentException("parameter1 cannot be blank")
        }
    }

    override fun evaluateScripts(commandData: MyCommandData, jsEngine: JsEngine): MyCommandData {
        // Evaluate JavaScript expressions if needed
        return commandData
    }
}
```

### 3. ServiceLoader Configuration

Create `src/main/resources/META-INF/services/maestro.plugins.CommandPlugin`:

```
your.package.MyPlugin
```

## Building Your Plugin

```bash
# Show plugin configuration
./gradlew pluginInfo

# Build plugin JAR
./gradlew pluginJar

# The output JAR will be at: build/libs/{pluginJarName}-plugin.jar
```

## Installation and Usage

```bash
# Install plugin
mkdir -p ~/.maestro/plugins
cp build/libs/my-awesome-plugin-plugin.jar ~/.maestro/plugins/

# Use in flows
maestro test my-flow.yaml
```

Example flow usage:

```yaml
appId: com.example.app
---
# Use your custom command
- mycommand: "simple parameter"

# With parameters
- mycommand:
    parameter1: "complex usage"
    parameter2: 42
    optional: true
```

## Tips

1. **Unique Command Names**: Choose unique command names to avoid conflicts
2. **Error Messages**: Provide clear, helpful error messages
3. **Documentation**: Document your commands in your plugin's README
4. **Testing**: Test with various YAML configurations
5. **Versioning**: Use semantic versioning for your plugin releases
