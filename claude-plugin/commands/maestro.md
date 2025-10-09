---
description: AI assisted development and maintenance of Maestro flows
argument-hint: Optional description eg. "Build a new flow for sign in"
---

# Maestro Assistant

You are helping a tester build or fix their Maestro flow.

# Core Principles

* **Ensure valid Maestro syntax** - Consult the Maestro syntax reference below to ensure valid Maestro syntax.
* **Root cause failures** - If the flow fails, first root cause the issue by inspecting the maestro output, the hierarchy, and the screen. Only after the root cause has been identified should you attempt a fix.
* **Check your work** - When changing anything, make sure to run the flow to validate that everything works.
* **Use temporary flow files** - If it's more efficient to run just part of a flow, create a temporary flow file to execute instead of running the whole flow file.

# Maestro CLI

* **Running a flow** - `maestro test SignIn.yaml`
* **Getting the hierarchy** - `maestro hierarchy --compact`
* **Checking Maestro syntax** - `maestro check-syntax SignIn.yaml`
* **Discovering running devices** - `adb devices; xcrun simctl list devices booted`
* **Getting the appId** - `adb shell pm list packages` or `xcrun simctl listapps Booted`

# Rules
* **Flows must be repeatable** - Ensure flows can be run multiple times and always pass. Eg. Ensure flows start by launching the app with clear state (unless specifically indicated by the user).
* **Understand the app before generating code** - DO NOT assume anything about the app. Run the app, execute the existing flow, examine the screen and hierarchy in order to decide what commands to generate.
* **Build step-by-step** - DO NOT generate a bunch of code at once. Build the flow iteratively, checking your work along the way.

==========================

# Maestro YAML Complete Syntax Reference

**Format:** Flow = Config + Commands
**Structure:** `config\n---\ncommands[]`

---

## 1. CONFIG SECTION

```yaml
appId: com.example.app          # Required (mobile) OR url (web)
url: https://example.com        # Required (web) OR appId (mobile)
name: Flow Name                 # Optional
tags: [tag1, tag2]              # Optional
env:                            # Optional: environment variables
  KEY: value
onFlowStart:                    # Optional: pre-flow commands
  commands: []
onFlowComplete:                 # Optional: post-flow commands (always runs)
  commands: []
# Extension fields (stored in ext map):
jsEngine: rhino                 # Optional: rhino (default) | graaljs
androidWebViewHierarchy: devtools # Optional
# Any custom fields can be added and accessed via ext map
---
```

**Validation:** Must have `appId` OR `url`
**Note:** If `url` is provided, it becomes the internal `appId` value

---

## 2. ELEMENT SELECTOR

**Shorthand:** String = text regex
**Full form:** Object with fields below

### Fields

```yaml
text: ".*regex.*"               # String: Text/accessibility text regex
id: "view_id"                   # String: ID regex
index: 0                        # String: 0-based index (supports expressions)
point: "50%,50%"                # String: Relative % or absolute px coordinates
width: 100                      # Int: Width (px)
height: 100                     # Int: Height (px)
tolerance: 10                   # Int: Size tolerance (px)
enabled: true                   # Boolean: Enabled state
checked: true                   # Boolean: Checked state
focused: true                   # Boolean: Focused state
selected: true                  # Boolean: Selected state
traits: "text square"           # String: Space-separated: text|square|long-text
below: selector                 # Selector: Relative positioning
above: selector                 # Selector: Relative positioning
leftOf: selector                # Selector: Relative positioning
rightOf: selector               # Selector: Relative positioning
childOf: selector               # Selector: Parent-child relationship
containsChild: selector         # Selector: Has direct child
containsDescendants: [sel1, sel2] # Selector[]: Has all descendants
css: "div.class"                # String: Web CSS selector
waitUntilVisible: false         # Boolean: Wait for element to be visible
```

**Traits:** `text`, `square`, `long-text`

---

## 3. CONDITION

Used in `when` (runFlow/runScript) and `while` (repeat)

```yaml
when:
  platform: ios                 # android|ios|web (case insensitive)
  visible: selector             # Element visible
  notVisible: selector          # Element not visible
  true: "${expr}"               # JavaScript expression
  optional: false               # Continue on condition failure (default: false)
```

**Multiple conditions = AND logic**

---

## 4. COMMANDS (40 types)

### 4.1 Interaction

**tapOn** / **doubleTapOn** / **longPressOn**
```yaml
- tapOn: "text"                 # Shorthand
- tapOn:
    selector_fields             # Any selector field
    repeat: 3                   # Number of taps
    delay: 500                  # ms between taps (default: 100)
    retryTapIfNoChange: false   # Retry if no screen change
    waitToSettleTimeoutMs: 500  # Max settle wait (capped: 30000)
    waitUntilVisible: false     # Wait for element to be visible
```

**scroll**
```yaml
- scroll
```

**scrollUntilVisible**
```yaml
- scrollUntilVisible:
    element: selector           # Required
    direction: DOWN             # UP|DOWN|LEFT|RIGHT (default: DOWN)
    timeout: 20000              # ms (default: 20000)
    speed: 40                   # 0-100 (default: 40)
    visibilityPercentage: 100   # 0-100 (default: 100)
    centerElement: false        # Boolean (default: false)
    waitToSettleTimeoutMs: 500
```

**swipe**
```yaml
# Directional
- swipe:
    direction: LEFT             # UP|DOWN|LEFT|RIGHT
    duration: 400               # ms (default: 400)

# Coordinates (relative %)
- swipe:
    start: "90%,50%"
    end: "10%,50%"
    duration: 400

# Coordinates (absolute px)
- swipe:
    start: "100,200"
    end: "300,400"

# From element
- swipe:
    from: selector
    direction: UP
    duration: 400
```

**back**
```yaml
- back                          # Android only
```

---

### 4.2 Input

**inputText**
```yaml
- inputText: "text"             # Shorthand
- inputText:
    text: "value"
```

**inputRandom[Type]**
```yaml
- inputRandomText               # length: 8 default
- inputRandomText:
    length: 10
- inputRandomNumber             # length: 8 default
- inputRandomNumber:
    length: 10
- inputRandomEmail
- inputRandomPersonName
- inputRandomCityName
- inputRandomCountryName
- inputRandomColorName
```

**eraseText**
```yaml
- eraseText                     # Erase all
- eraseText: 100                # Erase up to N chars
```

**copyTextFrom**
```yaml
- copyTextFrom: "text"          # Shorthand
- copyTextFrom:
    selector_fields
```
**Output:** `maestro.copiedText`

**pasteText**
```yaml
- pasteText
```

**hideKeyboard**
```yaml
- hideKeyboard
```

**pressKey**
```yaml
- pressKey: Enter
```

**Keys:** Enter, Backspace, Back, Home, Lock, Volume Up/Down, Escape, Power, Tab, Remote Dpad Up/Down/Left/Right/Center, Remote Media Play Pause/Stop/Next/Previous/Rewind/Fast Forward, Remote System Navigation Up/Down, Remote Button A/B, Remote Menu, TV Input, TV Input HDMI 1/2/3

---

### 4.3 Assertions

**assertVisible** / **assertNotVisible**
```yaml
- assertVisible: "text"         # Shorthand
- assertVisible:
    selector_fields
```

**assertTrue**
```yaml
- assertTrue: "${expr}"         # Shorthand
- assertTrue:
    condition: "${expr}"
```

**assertWithAI**
```yaml
- assertWithAI: "assertion text" # Shorthand
- assertWithAI:
    assertion: "text"
    optional: true              # Default: true
```
**Requires:** `MAESTRO_CLOUD_API_KEY`

**assertNoDefectsWithAI**
```yaml
- assertNoDefectsWithAI         # optional: true default
```
**Requires:** `MAESTRO_CLOUD_API_KEY`

---

### 4.4 AI

**extractTextWithAI**
```yaml
- extractTextWithAI: "query"    # Shorthand
- extractTextWithAI:
    query: "text"
    outputVariable: aiOutput    # Default: aiOutput
    optional: true              # Default: true
```
**Requires:** `MAESTRO_CLOUD_API_KEY`

---

### 4.5 App Lifecycle

**launchApp**
```yaml
- launchApp                     # Uses config appId
- launchApp: "com.app.id"       # Shorthand
- launchApp:
    appId: "com.app.id"
    clearState: true
    clearKeychain: true         # iOS only
    stopApp: false              # Default: true
    permissions:
      all: deny                 # allow|deny
      notifications: unset      # allow|deny|unset
      android.permission.NAME: deny
    arguments:                  # Key-value map
      key: value
```

**stopApp**
```yaml
- stopApp                       # Uses config appId
- stopApp: "com.app.id"         # Shorthand
```

**killApp**
```yaml
- killApp                       # Uses config appId
- killApp: "com.app.id"         # Shorthand
```
**Note:** Android = process death; iOS/Web = stopApp alias

**clearState**
```yaml
- clearState                    # Uses config appId
- clearState: "com.app.id"      # Shorthand
```

**clearKeychain**
```yaml
- clearKeychain                 # iOS only
```

---

### 4.6 Media

**addMedia**
```yaml
- addMedia:
    - "path/to/file1.jpg"
    - "path/to/file2.mp4"
```
**Formats:** PNG, JPEG, JPG, GIF, MP4

**takeScreenshot**
```yaml
- takeScreenshot: "name"        # Shorthand
- takeScreenshot:
    path: "name"
```

**startRecording**
```yaml
- startRecording: "path"        # Shorthand
- startRecording:
    path: "path"
```

**stopRecording**
```yaml
- stopRecording
```

---

### 4.7 Device Control

**setLocation**
```yaml
- setLocation:
    latitude: "37.7749"
    longitude: "-122.4194"
```
**Note:** Android API 31+ only

**setOrientation**
```yaml
- setOrientation: PORTRAIT      # Shorthand
```
**Values:** PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, UPSIDE_DOWN (case insensitive)

**setAirplaneMode**
```yaml
- setAirplaneMode: enabled      # enabled|disabled
```
**Android only**

**toggleAirplaneMode**
```yaml
- toggleAirplaneMode
```
**Android only**

**travel**
```yaml
- travel:
    points:
      - "37.7749,-122.4194"
      - "37.7750,-122.4195"
    speed: 7900                 # meters/second
```

---

### 4.8 Flow Control

**runFlow**
```yaml
- runFlow: "path.yaml"          # Shorthand
- runFlow:
    file: "path.yaml"
    when: condition             # Optional
    env:                        # Optional
      KEY: value
# OR inline
- runFlow:
    commands: []
    env:
      KEY: value
```

**runScript**
```yaml
- runScript: "path.js"          # Shorthand
- runScript:
    file: "path.js"
    when: condition             # Optional
    env:                        # Optional
      KEY: value
```

**evalScript**
```yaml
- evalScript: "${code}"         # Shorthand
- evalScript:
    script: "code"
```

**repeat**
```yaml
- repeat:
    times: 3                    # Optional
    while: condition            # Optional
    commands: []                # Required
```

**retry**
```yaml
- retry:
    maxRetries: 3               # 0-3, default: 1
    file: "flow.yaml"           # file OR commands
    commands: []
    env:
      KEY: value
```

---

### 4.9 Wait

These are usually unnecessary. Commands with element selectors (eg. tapOn) automatically wait for the element to show up. Only use if absolutely necessary.

**waitForAnimationToEnd**
```yaml
- waitForAnimationToEnd
- waitForAnimationToEnd:
    timeout: 5000               # ms
```

**extendedWaitUntil**
```yaml
- extendedWaitUntil:
    visible: selector           # visible OR notVisible (not both)
    timeout: 10000              # ms
# OR
- extendedWaitUntil:
    notVisible: selector
    timeout: 10000
```

---

### 4.10 Navigation

**openLink**
```yaml
- openLink: "https://example.com" # Shorthand
- openLink:
    link: "url"
    browser: false              # Force browser (Android)
    autoVerify: false           # Auto-verify app links (Android)
```

---

## 5. COMMON COMMAND ARGUMENTS

All commands support:
```yaml
label: "Custom label"           # Masks sensitive data
optional: false                 # Continue on failure (default: false, AI cmds: true)
```

---

## 6. JAVASCRIPT

### 6.1 Inline Injection
```yaml
- inputText: ${1 + 1}
- inputText: ${"Hello " + MY_NAME}
```

### 6.2 Built-in Objects
```javascript
maestro.copiedText              // Text from copyTextFrom
maestro.platform                // 'ios' or 'android'
output                          // Global output object
output.myScript = {value: 1}    // Namespaced output
relativePoint(0.5, 0.5)         // Converts decimals to percentages
http.get(url)                   // HTTP requests
http.post(url, body)
http.put(url, body)
http.delete(url)
http.request({method, url, headers, body})
json()                          // JSON parsing
console.log()                   // Logging
```

### 6.3 Script File
```javascript
// Access env directly
var name = MY_NAME.toUpperCase()

// Set output
output.myFlow = name
```

---

## 7. ENVIRONMENT VARIABLES

### 7.1 Flow env
```yaml
env:
  USERNAME: user@example.com
  PASSWORD: "${PASSWORD || 'default'}" # Default value
---
- inputText: ${USERNAME}
```

### 7.2 CLI env
```bash
maestro test -e USERNAME=user -e PASSWORD=123 flow.yaml
```

### 7.3 System env (MAESTRO_* prefix)
```bash
export MAESTRO_FOO=bar
```
```yaml
- inputText: ${MAESTRO_FOO}
```

### 7.4 Special env vars
- `MAESTRO_CLOUD_API_KEY` - AI commands
- `MAESTRO_CLI_AI_KEY` - External AI service
- `MAESTRO_CLI_AI_MODEL` - gpt-4o | claude-3-5-sonnet-20240620
- `MAESTRO_USE_GRAALJS` - Use GraalJS engine
- `MAESTRO_DRIVER_STARTUP_TIMEOUT` - Driver timeout (default: 15000ms)
- `MAESTRO_DISABLE_UPDATE_CHECK` - Disable version check

### 7.5 Built-in parameters
- `MAESTRO_FILENAME` - Current flow filename

---

## 8. WORKSPACE CONFIG (config.yaml)

```yaml
flows:
  - "subFolder/*"               # Flow patterns
includeTags: [tag1]
excludeTags: [tag2]

executionOrder:
  continueOnFailure: false      # Default: true
  flowsOrder: [flowA, flowB]

testOutputDir: test_output_directory

baselineBranch: main            # Cloud only

# Deprecated fields:
# disableRetries: false         # Deprecated: not supported on cloud
# local:
#   deterministicOrder: false   # Deprecated: use executionOrder instead

notifications:                  # Cloud only
  email:
    enabled: true
    recipients: [email@example.com]
    onSuccess: false            # Default: false
  slack:
    enabled: true
    channels: [channel-name]
    apiKey: key
    onSuccess: false

platform:
  ios:
    snapshotKeyHonorModalViews: false
    disableAnimations: true
  android:
    disableAnimations: true
```

---

## 9. PARSING RULES

### 9.1 Case Sensitivity
- **Commands:** Case sensitive
- **Platform, Direction, Orientation:** Case insensitive
- **Keys:** Case insensitive

### 9.2 Defaults
- `optional: false` (except AI commands: `true`)
- `swipe.duration: 400`
- `inputRandom*.length: 8`
- `scrollUntilVisible.direction: DOWN`
- `scrollUntilVisible.timeout: 20000`
- `scrollUntilVisible.speed: 40`
- `scrollUntilVisible.visibilityPercentage: 100`
- `scrollUntilVisible.centerElement: false`
- `tapOn.delay: 100`
- `waitToSettleTimeoutMs: 30000` (max cap)

### 9.3 Validation
- Config requires `appId` OR `url`
- Commands section required after `---`
- Paths relative to flow file
- Referenced files must exist
- `extendedWaitUntil`: Either `visible` OR `notVisible` (not both)
- `runFlow`/`retry`: Either `file` OR `commands` (not both)
- `swipe`: Cannot mix `direction` with `start/end`

### 9.4 String Interpolation
- `${expression}` evaluates JavaScript
- Available in all string fields
- Access env vars directly by name

---

## 10. SELECTOR SHORTHAND

```yaml
# These are equivalent:
- tapOn: "Button text"
- tapOn:
    text: "Button text"

# These are equivalent:
- assertVisible: "text"
- assertVisible:
    text: "text"
```

---

## 11. HOOKS

```yaml
onFlowStart:
  commands:
    - runFlow: setup.yaml
    - runScript: setup.js

onFlowComplete:
  commands:
    - runFlow: teardown.yaml
    - runScript: teardown.js
```

**Behavior:**
- onFlowStart failure → marks flow failed, skips body, runs onFlowComplete
- onFlowComplete failure → marks flow failed
- Hooks in subflows execute based on subflow duration

---

## 12. REGEX

All `text` and `id` selectors support regex:
```yaml
text: ".*brown fox.*"           # Partial match
text: "[0-9]{6}"                # 6 digits
text: "\\[required\\]"          # Escape special chars: \[ \]
```

---

## 13. COORDINATES

**Relative (%):** `"50%,50%"` (0-100%)
**Absolute (px):** `"100,200"`
**Format:** `"x,y"`

---

## 14. ENUMS

**Platform:** ANDROID, IOS, WEB (case insensitive)
**SwipeDirection:** UP, DOWN, LEFT, RIGHT (case insensitive)
**ScrollDirection:** UP, DOWN, LEFT, RIGHT (case insensitive)
**DeviceOrientation:** PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, UPSIDE_DOWN (case insensitive)
**ElementTrait:** text, square, long-text (space-separated string)
**AirplaneMode:** enabled, disabled (case insensitive)
**InputRandomType:** TEXT, NUMBER, TEXT_EMAIL_ADDRESS, TEXT_PERSON_NAME, TEXT_CITY_NAME, TEXT_COUNTRY_NAME, TEXT_COLOR

---

## 15. LIMITS & CONSTRAINTS

- `maxRetries: 0-3`
- `speed: 0-100`
- `visibilityPercentage: 0-100`
- `waitToSettleTimeoutMs: max 30000`
- Relative coordinates: 0-100%
- Index: 0-based

---

## 16. PLATFORM-SPECIFIC

**Android only:** back, setAirplaneMode, toggleAirplaneMode, setLocation (API 31+)
**iOS only:** clearKeychain
**Android:** Unicode not supported in inputText
**iOS:** eraseText & hideKeyboard can be flaky

---

## 17. FILE PATHS

- Relative to calling flow file (runFlow, runScript)
- Relative to workspace root (takeScreenshot)
- Must exist and be readable

---

## 18. AI COMMANDS

**Requirements:**
- `MAESTRO_CLOUD_API_KEY` env var
- Default `optional: true`

**Commands:** assertWithAI, assertNoDefectsWithAI, extractTextWithAI

**Reports:** HTML/JSON in `~/.maestro/tests/`

---

## 19. EXAMPLES

### Minimal Flow
```yaml
appId: com.example.app
---
- launchApp
- tapOn: "Button"
- assertVisible: "Success"
```

### Complex Selector
```yaml
- tapOn:
    text: "Submit"
    below: "Username"
    enabled: true
    index: 0
```

### Conditional Flow
```yaml
- runFlow:
    when:
      platform: ios
      visible: "Alert"
    file: dismiss-alert.yaml
```

### Loop
```yaml
- repeat:
    times: 10
    while:
      notVisible: "End of list"
    commands:
      - scroll
      - takeScreenshot: "page-${output.counter}"
      - evalScript: ${output.counter = (output.counter || 0) + 1}
```

### JavaScript
```yaml
- runScript: script.js
- inputText: ${output.myScript.result}
```

**script.js:**
```javascript
var response = http.get('https://api.example.com/data')
var data = JSON.parse(response.body)
output.myScript = {result: data.value}
```
