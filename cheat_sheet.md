# Maestro Commands Cheat Sheet

Complete reference for all Maestro commands, element selectors, and flow syntax.

## Table of Contents

- [Flow File Structure](#flow-file-structure)
- [Navigation Commands](#navigation-commands)
- [Interaction Commands](#interaction-commands)
- [Input Commands](#input-commands)
- [Assertion Commands](#assertion-commands)
- [App Lifecycle Commands](#app-lifecycle-commands)
- [Device Commands](#device-commands)
- [Flow Control Commands](#flow-control-commands)
- [Script Commands](#script-commands)
- [Media Commands](#media-commands)
- [Element Selectors](#element-selectors)
- [Conditions](#conditions)
- [Common Parameters](#common-parameters)

---

## Flow File Structure

```yaml
appId: com.example.app  # Required: App bundle ID or package name
---
- launchApp           # Commands go here
- tapOn: "Button"
- assertVisible: "Success"
```

### Configuration Options

```yaml
appId: com.example.app
name: "My Test Flow"
tags:
  - tag1
  - tag2
onFlowStart:
  - runFlow: setup.yaml
onFlowComplete:
  - runFlow: teardown.yaml
---
# Commands...
```

---

## Navigation Commands

### swipe

Swipe on screen with directional or point-based control.

**Direction-based:**
```yaml
- swipe:
    direction: UP    # UP, DOWN, LEFT, RIGHT
    duration: 400    # Optional: milliseconds (default: 400)
```

**Point-based:**
```yaml
- swipe:
    start: 100,500
    end: 100,200
    duration: 3000
```

**Relative coordinates:**
```yaml
- swipe:
    startRelative: "50%,80%"
    endRelative: "50%,20%"
    duration: 400
```

**Element-based:**
```yaml
- swipe:
    direction: LEFT
    element:
      text: "Item"
```

**Parameters:**
- `direction`: `UP`, `DOWN`, `LEFT`, `RIGHT`
- `startPoint`: Absolute coordinates (deprecated, use start)
- `endPoint`: Absolute coordinates (deprecated, use end)
- `start`: "x,y" coordinates
- `end`: "x,y" coordinates
- `startRelative`: "x%,y%" percentage coordinates
- `endRelative`: "x%,y%" percentage coordinates
- `element`: Element selector to swipe on
- `duration`: Swipe duration in milliseconds (default: 400)
- `waitToSettleTimeoutMs`: Time to wait for UI to settle after swipe
- `label`: Custom label for this command
- `optional`: If true, continue on failure

---

### scroll

Simple vertical scroll.

```yaml
- scroll
```

**Parameters:**
- `label`: Custom label
- `optional`: Continue on failure

---

### scrollUntilVisible

Scroll until an element becomes visible.

```yaml
- scrollUntilVisible:
    element:
      text: "Target Item"
    direction: DOWN              # UP, DOWN (default: DOWN)
    speed: 40                    # 0-100, higher is faster (default: 40)
    visibilityPercentage: 100    # 0-100 (default: 100)
    timeout: 20000               # Milliseconds (default: 20000)
    centerElement: false         # Center element after scrolling (default: false)
```

**Parameters:**
- `element`: Element selector to find
- `direction`: `UP` or `DOWN`
- `speed`: Scroll speed 0-100 (default: 40)
- `visibilityPercentage`: Required visibility 0-100% (default: 100)
- `timeout`: Max time to search in milliseconds (default: 20000)
- `centerElement`: Center element after found (default: false)
- `waitToSettleTimeoutMs`: Time to wait for UI to settle
- `label`: Custom label
- `optional`: Continue on failure

---

### backPress

Press the back button (Android).

```yaml
- backPress
```

---

## Interaction Commands

### tapOn

Tap on an element or point.

**Tap on element:**
```yaml
- tapOn: "Button text"
# or with selector
- tapOn:
    text: "Button"
    index: 0
```

**Tap on point:**
```yaml
- tapOn:
    point: "50%,50%"    # Percentage coordinates
# or
- tapOn:
    point: "200,400"    # Absolute coordinates
```

**Long press:**
```yaml
- tapOn:
    text: "Item"
    longPress: true
```

**Double tap:**
```yaml
- tapOn:
    text: "Item"
    repeat: 2
    repeatDelay: 100    # Milliseconds between taps
```

**Tap on relative point within element:**
```yaml
- tapOn:
    text: "Item"
    relativePoint: "90%,50%"   # Tap at specific point within the element
```

**Parameters:**
- Element selector (when tapping on element)
- `point`: Coordinates as "x,y" (absolute) or "x%,y%" (percentage)
- `relativePoint`: Point relative to element bounds "x%,y%"
- `retryIfNoChange`: Retry if screen doesn't change (default: true)
- `waitUntilVisible`: Wait for element to be visible (default: true)
- `longPress`: Long press instead of tap (default: false)
- `repeat`: Number of taps (1 for single, 2 for double, etc.)
- `repeatDelay`: Milliseconds between repeated taps (default: 100)
- `waitToSettleTimeoutMs`: Wait for UI to settle after tap
- `label`: Custom label
- `optional`: Continue on failure

---

### copyTextFrom

Copy text from an element to clipboard.

```yaml
- copyTextFrom:
    text: "Username"
```

The copied text is stored in the Maestro clipboard and can be accessed in JavaScript as `maestro.copiedText`.

**Parameters:**
- Element selector
- `label`: Custom label
- `optional`: Continue on failure

---

### setClipboard

Set clipboard content.

```yaml
- setClipboard: "Text to copy"
```

**Parameters:**
- `text`: Text to set in clipboard
- `label`: Custom label
- `optional`: Continue on failure

---

### pasteText

Paste text from clipboard.

```yaml
- pasteText
```

---

### hideKeyboard

Hide the on-screen keyboard.

```yaml
- hideKeyboard
```

---

## Input Commands

### inputText

Input text into focused field.

```yaml
- inputText: "Hello World"
# or with variable
- inputText: ${username}
```

**Parameters:**
- `text`: Text to input (supports variables)
- `label`: Custom label
- `optional`: Continue on failure

---

### inputRandom

Input random generated data.

```yaml
- inputRandom:
    inputType: TEXT              # Type of random data
    length: 8                    # Length (default: 8)
```

**Input Types:**
- `NUMBER`: Random number
- `TEXT`: Random text
- `TEXT_EMAIL_ADDRESS`: Random email
- `TEXT_PERSON_NAME`: Random person name
- `TEXT_CITY_NAME`: Random city name
- `TEXT_COUNTRY_NAME`: Random country name
- `TEXT_COLOR`: Random color name

**Examples:**
```yaml
- inputRandom:
    inputType: TEXT_EMAIL_ADDRESS

- inputRandom:
    inputType: NUMBER
    length: 10
```

---

### eraseText

Erase text from focused field.

```yaml
- eraseText          # Erase all text
# or
- eraseText: 5       # Erase 5 characters
```

**Parameters:**
- `charactersToErase`: Number of characters to erase (null = all)
- `label`: Custom label
- `optional`: Continue on failure

---

### pressKey

Press a hardware or virtual key.

```yaml
- pressKey: Enter
```

**Available Keys:**
- `Enter`
- `Backspace`
- `Back`
- `Home`
- `Lock`
- `Volume Up`
- `Volume Down`
- `Power`
- `Tab`
- `Escape`
- `Remote Dpad Up`
- `Remote Dpad Down`
- `Remote Dpad Left`
- `Remote Dpad Right`
- `Remote Dpad Center`
- `Remote Media Play Pause`
- `Remote Media Stop`
- `Remote Media Next`
- `Remote Media Previous`
- `Remote Media Rewind`
- `Remote Media Fast Forward`
- `Remote System Navigation Up`
- `Remote System Navigation Down`
- `Remote Button A`
- `Remote Button B`
- `Remote Menu`
- `TV Input`
- `TV Input HDMI 1`
- `TV Input HDMI 2`
- `TV Input HDMI 3`

**Note:** Key names are case-insensitive.

---

## Assertion Commands

### assertVisible / assertTrue

Assert that a condition is true.

```yaml
# Simple element visibility
- assertVisible: "Success message"

# With timeout
- assertTrue:
    visible:
      text: "Success"
    timeout: 5000    # Milliseconds

# Complex condition
- assertTrue:
    visible:
      id: "success_icon"
      enabled: true
```

**Shorthand:**
```yaml
- assertVisible: "Text"
- assertVisible:
    id: "element_id"
```

---

### assertNotVisible / assertFalse

Assert that an element is not visible.

```yaml
- assertNotVisible: "Error message"

- assertTrue:
    notVisible:
      text: "Error"
```

---

### assertWithAI

AI-powered assertion (requires Maestro Cloud API key).

```yaml
- assertWithAI: "The login button is blue"
```

**Parameters:**
- `assertion`: Description of what to assert
- `optional`: Continue on failure (default: true)
- `label`: Custom label

---

### assertNoDefectsWithAI

Check for visual defects using AI (requires Maestro Cloud API key).

```yaml
- assertNoDefectsWithAI
```

---

### extractTextWithAI

Extract text using AI (requires Maestro Cloud API key).

```yaml
- extractTextWithAI:
    query: "What is the user's name?"
    outputVariable: userName
```

The extracted text is stored in `output.userName`.

---

## App Lifecycle Commands

### launchApp

Launch an application.

```yaml
- launchApp

# With options
- launchApp:
    appId: com.other.app
    clearState: true
    clearKeychain: true
    stopApp: true

# With permissions
- launchApp:
    permissions:
      all: allow
      # or specific permissions
      notifications: allow
      location: deny

# With launch arguments
- launchApp:
    launchArguments:
      key1: value1
      key2: value2
```

**Parameters:**
- `appId`: App to launch (default: from config)
- `clearState`: Clear app data before launch
- `clearKeychain`: Clear keychain before launch (iOS)
- `stopApp`: Stop app before launching (default: true)
- `permissions`: Permission settings (see below)
- `launchArguments`: Arguments to pass to app
- `label`: Custom label
- `optional`: Continue on failure

**Permission Values:**
- `allow`: Grant permission
- `deny`: Deny permission
- `unset`: Leave permission unset
- `all`: Special key to set all permissions

---

### stopApp

Stop an application.

```yaml
- stopApp
# or
- stopApp:
    appId: com.example.app
```

---

### killApp

Force kill an application.

```yaml
- killApp
# or
- killApp:
    appId: com.example.app
```

---

### clearState

Clear app data/cache.

```yaml
- clearState
# or
- clearState:
    appId: com.example.app
```

---

### clearKeychain

Clear iOS keychain.

```yaml
- clearKeychain
```

---

### setPermissions

Set app permissions.

```yaml
- setPermissions:
    appId: com.example.app
    permissions:
      notifications: allow
      location: deny
      camera: allow
```

---

## Device Commands

### setLocation

Set device GPS location.

```yaml
- setLocation:
    latitude: "37.7749"
    longitude: "-122.4194"
```

**Parameters:**
- `latitude`: Latitude as string
- `longitude`: Longitude as string
- `label`: Custom label
- `optional`: Continue on failure

---

### travel

Simulate traveling along a path.

```yaml
- travel:
    points:
      - latitude: "37.7749"
        longitude: "-122.4194"
      - latitude: "37.7849"
        longitude: "-122.4094"
    speedMPS: 10    # Speed in meters per second (optional)
```

---

### setOrientation

Set device orientation.

```yaml
- setOrientation: portrait
# or
- setOrientation: landscape
```

**Values:**
- `portrait`
- `landscape`

---

### setAirplaneMode

Enable or disable airplane mode.

```yaml
- setAirplaneMode: Enable
# or
- setAirplaneMode: Disable
```

---

### toggleAirplaneMode

Toggle airplane mode on/off.

```yaml
- toggleAirplaneMode
```

---

### openLink

Open a URL.

```yaml
- openLink: https://example.com

# Open in browser
- openLink:
    link: https://example.com
    browser: true

# With auto-verify (Android App Links)
- openLink:
    link: https://example.com
    autoVerify: true
```

**Parameters:**
- `link`: URL to open
- `browser`: Force open in browser (default: false)
- `autoVerify`: Auto-verify app link (Android) (default: false)
- `label`: Custom label
- `optional`: Continue on failure

---

### takeScreenshot

Take a screenshot.

```yaml
- takeScreenshot: screenshot.png
# or
- takeScreenshot: /path/to/screenshot.png
```

---

### startRecording

Start screen recording.

```yaml
- startRecording: recording.mp4
```

---

### stopRecording

Stop screen recording.

```yaml
- stopRecording
```

---

### addMedia

Add media files to device.

```yaml
- addMedia:
    - /path/to/photo1.jpg
    - /path/to/photo2.jpg
```

---

## Flow Control Commands

### repeat

Repeat commands multiple times or while a condition is true.

**Repeat N times:**
```yaml
- repeat:
    times: 3
    commands:
      - tapOn: "Button"
      - scroll
```

**Repeat while condition:**
```yaml
- repeat:
    while:
      visible:
        text: "Load More"
    commands:
      - tapOn: "Load More"
      - scroll
```

**Repeat while with max attempts:**
```yaml
- repeat:
    while:
      visible:
        text: "Load More"
    times: 10    # Max 10 iterations
    commands:
      - tapOn: "Load More"
```

**Parameters:**
- `times`: Number of times to repeat (can use variable: `${count}`)
- `while`: Condition to check (continues while true)
- `commands`: List of commands to repeat
- `label`: Custom label
- `optional`: Continue on failure

---

### retry

Retry commands if they fail.

```yaml
- retry:
    maxRetries: 3
    commands:
      - tapOn: "Flaky Button"
      - assertVisible: "Success"
```

**Parameters:**
- `maxRetries`: Maximum number of retry attempts
- `commands`: Commands to retry
- `label`: Custom label
- `optional`: Continue on failure

---

### runFlow

Run another flow file.

```yaml
- runFlow: subflow.yaml

# With condition
- runFlow:
    file: subflow.yaml
    when:
      platform: Android

# With environment variables
- runFlow:
    file: subflow.yaml
    env:
      USERNAME: john
      PASSWORD: secret
```

**Inline flow:**
```yaml
- runFlow:
    commands:
      - tapOn: "Button"
      - assertVisible: "Success"
```

**Parameters:**
- `file`: Path to flow file
- `commands`: Inline commands
- `when`: Condition to check before running
- `env`: Environment variables to pass
- `label`: Custom label
- `optional`: Continue on failure

---

### waitForAnimationToEnd

Wait for animations to complete.

```yaml
- waitForAnimationToEnd
# or with timeout
- waitForAnimationToEnd:
    timeout: 3000    # Milliseconds
```

---

## Script Commands

### defineVariables / env

Define environment variables.

```yaml
- defineVariables:
    USERNAME: john_doe
    API_KEY: ${SECRET_KEY}
```

**Shorthand:**
```yaml
- env:
    VAR_NAME: value
```

---

### runScript

Run a JavaScript file.

```yaml
- runScript: script.js

# With condition
- runScript:
    file: script.js
    when:
      platform: iOS

# With environment variables
- runScript:
    file: script.js
    env:
      INPUT: ${someValue}
```

---

### evalScript

Evaluate inline JavaScript.

```yaml
- evalScript: ${output.counter = 0}
- evalScript: ${output.result = 2 + 2}
```

**Access output:**
```yaml
- evalScript: ${output.name = "John"}
- inputText: ${output.name}
```

**Common patterns:**
```yaml
# Math operations
- evalScript: ${output.count = 5 + 3}

# String operations
- evalScript: ${output.greeting = "Hello " + output.name}

# Arrays
- evalScript: ${output.items = [1, 2, 3]}
- evalScript: ${output.length = output.items.length}

# Conditionals
- evalScript: ${output.isValid = output.age > 18}
```

---

## Element Selectors

Element selectors identify UI elements for interaction or assertion.

### Basic Selectors

**Text (regex):**
```yaml
text: "Button"
text: ".*button.*"    # Regex pattern
```

**ID (regex):**
```yaml
id: "button_id"
id: ".*_button"       # Regex pattern
```

**Size:**
```yaml
size:
  width: 100
  height: 50
  tolerance: 10       # Optional: pixel tolerance
```

### Positional Selectors

**Below another element:**
```yaml
text: "Email"
below:
  text: "Username"
```

**Above another element:**
```yaml
text: "Submit"
above:
  text: "Cancel"
```

**Left of another element:**
```yaml
id: "icon"
leftOf:
  text: "Title"
```

**Right of another element:**
```yaml
id: "icon"
rightOf:
  text: "Label"
```

### Hierarchical Selectors

**Contains child:**
```yaml
id: "container"
containsChild:
  text: "Item"
```

**Contains descendants:**
```yaml
id: "list"
containsDescendants:
  - text: "Item 1"
  - text: "Item 2"
```

**Child of:**
```yaml
text: "Item"
childOf:
  id: "parent_container"
```

### State Selectors

**Enabled/Disabled:**
```yaml
text: "Button"
enabled: true    # or false
```

**Selected/Not selected:**
```yaml
text: "Option"
selected: true   # or false
```

**Checked/Unchecked:**
```yaml
text: "Checkbox"
checked: true    # or false
```

**Focused/Not focused:**
```yaml
text: "Input"
focused: true    # or false
```

### Other Selectors

**Traits:**
```yaml
text: "Label"
traits:
  - TEXT          # Has text
  - SQUARE        # Is square
  - LONG_TEXT     # Has long text
```

**Index:**
```yaml
text: "Item"
index: 0         # First matching element (0-based)
```

**CSS (Web only):**
```yaml
css: "div.container > button"
```

### Combining Selectors

Multiple selectors can be combined:

```yaml
- tapOn:
    text: "Submit"
    enabled: true
    below:
      text: "Password"
    index: 0
```

---

## Conditions

Conditions control when commands execute.

### Platform Condition

```yaml
- tapOn:
    text: "Android Button"
  when:
    platform: Android

- tapOn:
    text: "iOS Button"
  when:
    platform: iOS
```

**Platforms:** `Android`, `iOS`, `Web`

### Visibility Condition

```yaml
- tapOn:
    text: "Close"
  when:
    visible:
      text: "Dialog"
```

### Not Visible Condition

```yaml
- tapOn:
    text: "Show"
  when:
    notVisible:
      text: "Content"
```

### Script Condition

```yaml
- tapOn:
    text: "Premium Feature"
  when:
    scriptCondition: ${output.isPremium === true}
```

### Combined Conditions

```yaml
- tapOn:
    text: "Button"
  when:
    platform: Android
    visible:
      id: "container"
    scriptCondition: ${output.count > 0}
```

All conditions must be true (AND logic).

---

## Common Parameters

These parameters are available on most commands:

### label

Custom label for the command in logs:

```yaml
- tapOn:
    text: "Submit"
    label: "Submit registration form"
```

### optional

Continue flow even if command fails:

```yaml
- assertVisible:
    text: "Optional notification"
    optional: true
```

---

## JavaScript Variables

Maestro provides JavaScript support for dynamic values.

### Built-in Variables

**output:** Persistent storage across flow
```yaml
- evalScript: ${output.myVar = "value"}
- inputText: ${output.myVar}
```

**maestro.copiedText:** Last copied text
```yaml
- copyTextFrom:
    text: "Username"
- evalScript: ${output.user = maestro.copiedText}
```

### Environment Variables

Pass variables via command line:
```bash
maestro test flow.yaml -e USERNAME=john -e PASSWORD=secret
```

Access in flow:
```yaml
- inputText: ${USERNAME}
- inputText: ${PASSWORD}
```

### String Interpolation

```yaml
- evalScript: ${output.name = "John"}
- assertVisible: "Hello ${output.name}"
- inputText: "User: ${output.name}"
```

### JavaScript Operations

```yaml
# Arithmetic
- evalScript: ${output.result = 10 * 2 + 5}

# String operations
- evalScript: ${output.fullName = output.firstName + " " + output.lastName}

# Conditionals
- evalScript: ${output.canSubmit = output.age >= 18 && output.agreed}

# Array operations
- evalScript: ${output.list = [1, 2, 3]}
- evalScript: ${output.firstItem = output.list[0]}
- evalScript: ${output.length = output.list.length}

# Object operations
- evalScript: ${output.user = {name: "John", age: 30}}
- evalScript: ${output.userName = output.user.name}
```

---

## Examples

### Complete Login Flow

```yaml
appId: com.example.app
---
- launchApp:
    clearState: true

- tapOn: "Login"

- tapOn:
    id: "email_input"
- inputText: ${EMAIL}

- tapOn:
    id: "password_input"
- inputText: ${PASSWORD}

- tapOn:
    text: "Submit"
    label: "Submit login form"

- assertVisible:
    text: "Welcome"
    timeout: 5000
```

### Scroll and Tap Pattern

```yaml
- scrollUntilVisible:
    element:
      text: "Settings"
    direction: DOWN
    centerElement: true

- tapOn: "Settings"

- assertVisible: "Settings Screen"
```

### Conditional Flow

```yaml
- runFlow:
    commands:
      - tapOn: "Skip"
    when:
      visible:
        text: "Tutorial"

- tapOn: "Continue"
```

### Loop with Counter

```yaml
- evalScript: ${output.counter = 0}

- repeat:
    times: 5
    commands:
      - tapOn: "Increment"
      - evalScript: ${output.counter = output.counter + 1}

- assertVisible: "Count: 5"
```

### Data Entry with Random Values

```yaml
- tapOn:
    id: "name_field"
- inputRandom:
    inputType: TEXT_PERSON_NAME

- tapOn:
    id: "email_field"
- inputRandom:
    inputType: TEXT_EMAIL_ADDRESS

- tapOn:
    id: "city_field"
- inputRandom:
    inputType: TEXT_CITY_NAME
```

---

## Tips and Best Practices

1. **Use meaningful labels** for better logs:
   ```yaml
   - tapOn:
       text: "Submit"
       label: "Submit payment form"
   ```

2. **Add timeouts for slow elements:**
   ```yaml
   - assertVisible:
       text: "Loaded"
       timeout: 10000
   ```

3. **Use optional for non-critical assertions:**
   ```yaml
   - assertVisible:
       text: "Promotional banner"
       optional: true
   ```

4. **Prefer scrollUntilVisible over scroll:**
   ```yaml
   - scrollUntilVisible:
       element:
         text: "Bottom Item"
   ```

5. **Use element indices for duplicate text:**
   ```yaml
   - tapOn:
       text: "Edit"
       index: 1    # Second "Edit" button
   ```

6. **Combine selectors for precision:**
   ```yaml
   - tapOn:
       text: "Submit"
       enabled: true
       below:
         text: "Terms and Conditions"
   ```

7. **Store values in output for reuse:**
   ```yaml
   - copyTextFrom:
       text: "Order ID"
   - evalScript: ${output.orderId = maestro.copiedText}
   - assertVisible: "Order ${output.orderId}"
   ```

---

## Documentation

For more information, visit:
- Official Documentation: https://maestro.mobile.dev
- GitHub: https://github.com/mobile-dev-inc/maestro
