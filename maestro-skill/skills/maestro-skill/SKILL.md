---
name: maestro-skill
description: Mobile automation for Android and iOS using Maestro. Auto-detects devices, writes YAML flows to /tmp, and executes them. Capable of launching apps, tapping elements, inputting text, scrolling, and asserting UI states. Use when user wants to test mobile apps, automate Android/iOS interactions, or validate mobile UI.
---

**IMPORTANT - Path Resolution:**
Use the `$SKILL_DIR` variable to refer to the current directory where this skill is installed.

# Maestro Mobile Automation

General-purpose mobile automation skill using the Maestro CLI framework. I will write declarative YAML flows for any task and execute them.

**CRITICAL WORKFLOW - Follow these steps in order:**

1.  **Check Prerequisites** - Ensure Maestro CLI is installed and devices are detected.
```bash
cd $SKILL_DIR && node -e "require('./lib/helpers').checkEnvironment().then(status => console.log(JSON.stringify(status, null, 2)))"
```
- If `maestro` binary is missing, ask user to run `npm run setup`.
- If no devices found, ask user to launch an Android Emulator or iOS Simulator.

1.  **Inspect Hierarchy (Optional but Recommended)** - If you are unsure about View IDs or text:
```bash
maestro hierarchy
```

1.  **Write Flows to /tmp** - NEVER write test files to the skill directory; always use `/tmp/maestro-flow-*.yaml`.

2.  **Execute Flow** - Run the flow using the wrapper:
```bash
cd $SKILL_DIR && node run.js /tmp/maestro-flow-task.yaml
```

## Syntax Quick Reference (YAML)

Maestro flows are YAML files consisting of a list of commands.

```yaml
appId: com.example.app
---
- launchApp
- tapOn: "Login"
- inputText: "user@example.com"
- tapOn:
    id: "password_field"
- inputText: "secret123"
- tapOn: "Submit"
- assertVisible: "Welcome back"
- takeScreenshot: /tmp/result.png
```

## Common Patterns

### Launch and Reset

```yaml
appId: ${APP_ID}
---
- launchApp:
    clearState: true
```
### Scroll to Element

```yaml
- scrollUntilVisible:
    element:
        text: "Submit Order"
    direction: DOWN
```

### Conditionals

```yaml
- runFlow:
    when:
        visible: "Popup"
    commands:
        - tapOn: "Close"
```

## Timing Best Practices

### launchApp Behavior
- `launchApp` completes when app process starts (not when UI is ready)
- Always add waits after launchApp for reliable automation

### Wait Strategies

**Option 1: Generic wait (good for splash screens)**
```yaml
- launchApp
- waitForAnimationToEnd
- takeScreenshot: /tmp/result
```

**Option 2: Wait for specific element (recommended)**
```yaml
- launchApp  
- assertVisible: "Studies"  # or any expected UI element
- takeScreenshot: /tmp/result
```

**Option 3: Explicit timeout with condition**
```yaml
- launchApp
- extendedWaitUntil:
    visible: "Loading..."
    timeout: 10000
```

**Option 4: Retry pattern for flaky elements**
```yaml
- repeat:
    times: 3
    commands:
      - assertVisible: "Login Button"
      - tapOn: "Login Button"
```

## Troubleshooting

**Maestro not found?** Run `npm run setup` in the skill directory.

**Device not found?** Ensure `adb devices` (Android) or `xcrun simctl list` (iOS) shows running devices.

**App not installed?** Install first:
```bash
# iOS
xcrun simctl install booted /path/to/app.ipa

# Android  
adb install /path/to/app.apk
```

**Hierarchy Inspector** If `tapOn` fails, use `maestro hierarchy` to see the actual view structure and attributes (text, resource-id) available to Maestro.

**Screenshot file names** Maestro adds .png automatically. Use `/tmp/screenshot` (without extension) to avoid `/tmp/screenshot.png.png`.
