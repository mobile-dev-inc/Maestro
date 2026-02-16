# Maestro Skill for Claude Code

Mobile automation skill for Claude Code using the Maestro CLI framework. Created by **ThalesMMS**. Supports Android and iOS automation with intelligent error handling, templates, and best practices.

## Features

- Auto-detection of connected devices and Maestro installation
- Cross-platform support for Android emulators and iOS simulators  
- Smart error handling with helpful suggestions
- Flow templates for common automation patterns
- Timing best practices built-in
- App installation detection
- Screenshot management (handles extension issues automatically)

## Quick Start

### 1. Installation

The skill should already be installed in `~/.claude/skills/maestro-skill/`.

### 2. Setup Maestro CLI

```bash
cd ~/.claude/skills/maestro-skill
npm run setup
```

### 3. Verify Environment

```bash
npm run check-env
```

This will show:
- Maestro installation status
- Connected devices (Android/iOS)
- Sample installed apps

## Usage in Claude Code

Simply ask Claude to use the maestro-skill:

```
"Use maestro-skill to test the calculator app"
"Automate login flow with maestro-skill"  
"Check if the settings screen is accessible"
```

## Available Commands

### Environment Commands
- `npm run check-env` - Check Maestro installation and devices
- `npm run check-app` - Check if specific app is installed

### Skill Commands (used via Claude)
- Flow execution with automatic error handling
- Device detection and app validation
- Screenshot capture with proper naming

## Flow Examples

### Basic App Launch
```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- takeScreenshot: /tmp/app-launched
```

### Login Flow
```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- assertVisible: "Login"
- tapOn: "Username"
- inputText: "user@example.com"
- tapOn: "Password"
- inputText: "password123"
- tapOn: "Login"
- assertVisible: "Welcome"
- takeScreenshot: /tmp/login-success
```

### Scroll and Find
```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- scrollUntilVisible:
    element:
        text: "Settings"
    direction: DOWN
- tapOn: "Settings"
- takeScreenshot: /tmp/settings-found
```

## Timing Best Practices

**Important**: `launchApp` completes when the app process starts, not when UI is ready. Always add waits:

```yaml
# Option 1: Generic wait
- launchApp
- waitForAnimationToEnd
- takeScreenshot: /tmp/result

# Option 2: Wait for specific element (recommended)
- launchApp  
- assertVisible: "Main Screen"
- takeScreenshot: /tmp/result
```

## Error Handling

The skill provides intelligent error recovery:

- **App not installed** → Shows installation commands
- **Device not found** → Shows device startup commands  
- **Element not found** → Suggests hierarchy inspection and wait strategies
- **Permission denied** → Checks app and file permissions

## File Structure

```
maestro-skill/
├── lib/
│   ├── helpers.js       # Environment checking and app detection
│   └── templates.js     # Common flow templates
├── examples/
│   └── basic-flows.md  # Example flows and patterns
├── run.js              # Main executor with error handling
├── SKILL.md            # Skill documentation
├── API_REFERENCE.md    # Maestro command reference
└── package.json        # NPM configuration
```

## Advanced Features

### Using Templates

```javascript
const templates = require('./lib/templates');

// Create a login flow
const loginFlow = templates.loginFlow(
  'com.example.app',
  'user@example.com',
  'password123',
  'Dashboard'
);
```

### App Detection

```javascript
const { checkAppInstalled } = require('./lib/helpers');

// Check if app is installed
const isInstalled = await checkAppInstalled('ios', 'com.example.app');
```

## Troubleshooting

### Common Issues

1. **Maestro not found**
   ```bash
   npm run setup
   ```

2. **No devices detected**
   ```bash
   # iOS
   xcrun simctl boot "iPhone 15"
   
   # Android  
   emulator @your_emulator_name
   ```

3. **App not installed**
   ```bash
   # iOS
   xcrun simctl install booted /path/to/app.ipa
   
   # Android
   adb install /path/to/app.apk
   ```

4. **Element not found**
   - Use `maestro hierarchy` to inspect UI
   - Add `waitForAnimationToEnd` before interactions
   - Use `scrollUntilVisible` for off-screen elements

### Debug Mode

For detailed debugging, add more screenshots:

```yaml
- launchApp
- takeScreenshot: /tmp/1-after-launch
- waitForAnimationToEnd
- takeScreenshot: /tmp/2-after-wait
- tapOn: "Button"
- takeScreenshot: /tmp/3-after-tap
```

## Version History

- **v1.1.0** - Enhanced error handling, templates, app detection
- **v1.0.0** - Basic Maestro CLI wrapper

## Contributing

Created by **ThalesMMS**. Feel free to submit issues and enhancement requests!

## License

MIT License - see LICENSE file for details.
