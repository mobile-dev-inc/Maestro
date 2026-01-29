# Maestro External Plugins Example

This project demonstrates how to create external plugins for Maestro that can be compiled as JAR files and loaded dynamically.

## Plugin Configuration

You can customize the plugin metadata by editing `gradle.properties` or using command-line properties:

```properties
# gradle.properties
pluginName=Maestro Example Plugins
pluginVersion=1.0.0
pluginDescription=Example plugins for Maestro automation framework
pluginJarName=maestro-example-plugins
```

### Command-line Configuration

Override properties when building:

```bash
./gradlew :examples:plugins:pluginJar \
  -PpluginName="My Custom Plugin" \
  -PpluginVersion="2.1.0" \
  -PpluginDescription="My custom Maestro automation plugins" \
  -PpluginJarName="my-custom-plugins"
```

## Building the Plugin

To build the plugin JAR:

```bash
# From the root Maestro project directory
./gradlew :examples:plugins:pluginJar
```

This will:

1. Display the plugin configuration
2. Create a plugin JAR at `examples/plugins/build/libs/{pluginJarName}-plugin.jar`

### Plugin Information

To see the current plugin configuration:

```bash
./gradlew :examples:plugins:pluginInfo
```

## Installing the Plugin

### Default Location

Copy the plugin JAR to the default plugins directory:

```bash
mkdir -p ~/.maestro/plugins
cp examples/plugins/build/libs/maestro-example-plugins-plugin.jar ~/.maestro/plugins/
```

### Custom Location

You can use a custom plugins directory with the `--plugins-dir` option:

```bash
mkdir -p /path/to/custom/plugins
cp examples/plugins/build/libs/maestro-example-plugins-plugin.jar /path/to/custom/plugins/
maestro test --plugins-dir /path/to/custom/plugins your-flow.yaml
```

## Available Plugins

This plugin package includes several example plugins that demonstrate different aspects of the Maestro plugin system:

### 1. WaitCommandPlugin

Simple utility plugin for waiting/delays.

```yaml
- wait: 2.5 # Wait 2.5 seconds
```

```yaml
- wait:
    seconds: 3
```

### 2. LogCommandPlugin

Debug utility plugin for logging messages.

```yaml
- log: "Debug message here"
```

```yaml
- log:
    message: "Detailed log message"
    level: "INFO"
```

### 3. MultiTapCommandPlugin

Demonstrates reusing built-in Maestro tap commands.
Performs multiple taps with configurable delays between taps.

```yaml
# Tap on coordinates multiple times
- multiTap:
    point: "100,200"
    count: 3
    delayMs: 1000
    longPress: false
```

```yaml
# Tap on element multiple times
- multiTap:
    selector:
      text: "Submit Button"
    count: 2
    delayMs: 500
    retryIfNoChange: true
```

```yaml
# Coordinates with individual x,y
- multiTap:
    x: 150
    y: 300
    count: 5
    delayMs: 250
    longPress: true
```

### 4. MultiSwipeCommandPlugin

Demonstrates reusing built-in Maestro swipe commands.
Performs multiple swipes with configurable delays between swipes.

```yaml
# Directional swipes
- multiSwipe:
    direction: "up"
    count: 3
    delayMs: 800
    duration: 500
```

```yaml
# Point-to-point swipes
- multiSwipe:
    direction: "left"
    startPoint: "200,400"
    endPoint: "400,400"
    count: 2
    delayMs: 1500
    duration: 600
```

```yaml
# Swipe on specific element
- multiSwipe:
    direction: "down"
    elementSelector:
      id: "scrollable_list"
    count: 5
    delayMs: 600
    duration: 400
```

### 5. TypewriterCommandPlugin

Demonstrates reusing built-in Maestro input commands.
Types text character by character with realistic delays, simulating human typing.

```yaml
# Simple typewriter effect
- typewriter: "Hello World! This is a test."
```

```yaml
# Advanced typewriter with configuration
- typewriter:
    text: "Search query here"
    elementSelector:
      id: "search_field"
    delayPerCharacterMs: 80
    delayAfterWordMs: 200
    delayAfterSentenceMs: 500
    clearBefore: true
    hideKeyboardAfter: true
```

### 🧪 Test Flow

A comprehensive test flow is available at `test-flow-plugins.yaml` demonstrating all plugins with various configurations.

Test flow demonstrates the full power of the plugin system - from simple extensions to sophisticated command orchestration reusing Maestro's core functionality!

## Creating Custom Plugins

To create your own plugins:

1. **Implement the CommandPlugin interface:**

```kotlin
class MyCustomPlugin : CommandPlugin<MyCommandData> {
    override val commandName: String = "mycommand"
    override val commandClass: Class<MyCommandData> = MyCommandData::class.java

    override fun parseCommand(yamlContent: Any?, location: JsonLocation): MyCommandData {
        // Parse YAML content into your command data
    }

    override suspend fun executeCommand(commandData: MyCommandData, context: PluginExecutionContext): Boolean {
        // Execute your command logic
        return false // Return true if command mutates UI
    }

    override fun getDescription(commandData: MyCommandData): String {
        // Return description for flow display
        return "My custom command"
    }

    override fun validateCommand(commandData: MyCommandData) {
        // Validate command data (optional)
    }

    override fun evaluateScripts(commandData: MyCommandData, jsEngine: JsEngine): MyCommandData {
        // Evaluate JavaScript expressions (optional)
        return commandData
    }
}
```

2. **Create your command data class:**

```kotlin
data class MyCommandData(
    val parameter1: String,
    val parameter2: Int = 0
)
```

3. **Register your plugin in ServiceLoader:**
   Add your plugin class to `src/main/resources/META-INF/services/maestro.plugins.CommandPlugin`:

```
maestro.plugins.MyCustomPlugin
```

4. **Build and install:**

```bash
./gradlew :examples:plugins:pluginJar
cp examples/plugins/build/libs/plugins-plugin.jar ~/.maestro/plugins/
```

## Dependencies

The plugin project includes all necessary dependencies:

- Maestro plugin API (`maestro.plugins`)
- Command models (`maestro.orchestra-models`)
- Jackson for JSON/YAML processing
- Kotlin coroutines
- SLF4J for logging

## Plugin Development Tips

1. **Command Names:** Use descriptive, unique command names to avoid conflicts.
2. **Error Handling:** Provide clear error messages in validation and execution.
3. **JavaScript Support:** Use `evaluateScripts()` to support dynamic values.
4. **UI Mutations:** Return `true` from `executeCommand()` only if your command modifies the UI.
5. **Flow Display:** Provide meaningful descriptions for better flow visualization.
6. **Testing:** Test your plugins thoroughly with various YAML configurations.

## Example Flow

Here's a complete example flow using the plugins:

```yaml
appId: com.example.dummy
---
# Built-in commands still work
- launchApp

# Simple plugin commands
- wait: 2.0
- log: "Test started successfully"
- wait:
    seconds: 1.5
    label: "Just another wait test"

# Plugins below demonstrate reusing built-in Maestro commands

# Test Multi-tap plugin with coordinates
- tapOn: "Multi-tap"
- multiTap:
    x: 200
    y: 300
    count: 3
    delayMs: 1000
    longPress: false

# Test Multi-tap plugin with element selector
- multiTap:
    selector:
      text: "Submit"
    count: 2
    delayMs: 500
- back

# Test Multi-swipe plugin with directional swipes
- tapOn: "Multi-swipe"
- multiSwipe:
    direction: "UP"
    count: 3
    delayMs: 800
    duration: 400

# Test Multi-swipe plugin with point-to-point swipes
- multiSwipe:
    startPoint: "100,400"
    endPoint: "300,200"
    count: 2
    delayMs: 1000
    duration: 600
- back

# Test Typewriter plugin
- tapOn: "Typewriter"
- typewriter:
    text: "Hello World!"
    selector:
      id: "textInput"
    characterDelayMs: 150
    spaceDelayMs: 300
    eraseFirst: true
    hideKeyboardWhenDone: true

# Test Typewriter plugin with a longer text
- typewriter:
    text: "This is a longer text that will be typed character by character with realistic delays."
    selector:
      text: "Type here"
    characterDelayMs: 100
    spaceDelayMs: 200
    eraseFirst: false
    hideKeyboardWhenDone: true
- back
```
