# Maestro API Reference for YAML Flows

Maestro flows are defined in YAML. Below are the commands supported by the engine.

## Core Configuration
```yaml
appId: com.example.app  # The package name (Android) or Bundle ID (iOS)
env:
    USERNAME: "test_user"
---
```
## Commands

### Navigation & Interaction

-   **launchApp**: Launches the app defined in `appId`.
```yaml
- launchApp:
    clearState: true # Optional: Clears app data/permissions
    stopApp: true    # Optional: Restarts app if running
```
-   **tapOn**: Taps on an element.
```yaml
- tapOn: "Text on Screen"
# OR by ID
- tapOn:
    id: "com.example.app:id/submit_button"
# OR by Coordinates (Not recommended)
- tapOn:
    point: 50%,50%
```

-   **inputText**: Types text into the focused field.
```yaml
- inputText: "Hello World"
```

-   **back**: Presses the physical back button (Android) or swipe back gesture (iOS).
```yaml
- back
```

-   **scroll**: Scrolls vertically.
```yaml
- scroll
```

-   **swipe**: Performs a swipe gesture.
```yaml
- swipe:
    direction: LEFT # UP, DOWN, LEFT, RIGHT
    duration: 400
```

### Assertions

-   **assertVisible**: Fails flow if element is not visible.
```yaml
- assertVisible: "Success Message"
```

-   **assertNotVisible**: Fails flow if element IS visible.
```yaml
- assertNotVisible: "Loading..."
```

-   **assertCondition**: Use JS logic.
```yaml
- assertTrue: ${output.price > 10}
```
-   **takeScreenshot**: Saves a screenshot to the host machine.
```yaml
- takeScreenshot: /tmp/screenshot_1.png
```

### Searching Elements (Selectors)

Selectors can be simple strings (matching text) or objects:

-   `text`: Matches display text (regex supported).
    
-   `id`: Matches `resource-id` (Android) or `accessibility-id` (iOS).
    
-   `index`: 0-based index if multiple matches found.
    

Example:

```yaml
- tapOn:
    id: "submit_btn"
    index: 0
```

### Advanced

-   **runFlow**: Execute another YAML file.

```yaml
- runFlow:
    file: subflow-login.yaml
    env:
        USER: "admin"
```

-   **repeat**: Loop commands.

```yaml
- repeat:
    times: 5
    commands:
        - scroll
```

-   **openLink**: Deep linking.
```yaml
- openLink: "myapp://details/1"
```
