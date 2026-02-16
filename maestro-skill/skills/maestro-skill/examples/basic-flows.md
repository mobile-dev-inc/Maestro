# Maestro Flow Examples

This document contains common flow patterns that you can adapt for your apps.

## 1. Basic App Launch

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- takeScreenshot: /tmp/app-launched
```

## 2. Login Flow

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- assertVisible: "Login"
- tapOn: "Username"
- inputText: "test@example.com"
- tapOn: "Password"  
- inputText: "password123"
- tapOn: "Login"
- assertVisible: "Welcome"
- takeScreenshot: /tmp/login-success
```

## 3. Form Fill

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- tapOn: "First Name"
- inputText: "John"
- tapOn: "Last Name"
- inputText: "Doe"
- tapOn: "Email"
- inputText: "john.doe@example.com"
- tapOn: "Submit"
- assertVisible: "Success"
- takeScreenshot: /tmp/form-submitted
```

## 4. Scroll and Find

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
- assertVisible: "Notifications"
- takeScreenshot: /tmp/settings-found
```

## 5. Navigation Test

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- assertVisible: "Home"
- tapOn: "Profile"
- assertVisible: "User Info"
- back
- assertVisible: "Home"
- tapOn: "Settings"
- assertVisible: "Preferences"
- takeScreenshot: /tmp/navigation-complete
```

## 6. Conditional Actions

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- runFlow:
    when:
        visible: "Welcome Popup"
    commands:
        - tapOn: "Close"
- assertVisible: "Main Menu"
- takeScreenshot: /tmp/popup-handled
```

## 7. Retry Pattern

```yaml
appId: com.example.app
---
- launchApp
- waitForAnimationToEnd
- repeat:
    times: 3
    commands:
        - assertVisible: "Login Button"
        - tapOn: "Login Button"
        - stopOn:
            visible: "Login Screen"
- takeScreenshot: /tmp/retry-success
```

## 8. Fresh Start (Clear State)

```yaml
appId: com.example.app
---
- launchApp:
    clearState: true
- waitForAnimationToEnd
- assertVisible: "Welcome"
- takeScreenshot: /tmp/fresh-start
```

## 9. Element Selection Strategies

### By Text
```yaml
- tapOn: "Submit Button"
```

### By ID
```yaml
- tapOn:
    id: "com.example.app:id/submit_button"
```

### By Index (if multiple matches)
```yaml
- tapOn:
    text: "Continue"
    index: 1
```

### By Coordinates (avoid if possible)
```yaml
- tapOn:
    point: "50%,50%"
```

## 10. Advanced Wait Patterns

### Wait for Specific Element
```yaml
- launchApp
- assertVisible: "Dashboard"
- takeScreenshot: /tmp/dashboard-loaded
```

### Wait with Timeout
```yaml
- launchApp
- extendedWaitUntil:
    visible: "Loading Complete"
    timeout: 15000
- takeScreenshot: /tmp/loaded-with-timeout
```

### Wait for Animation
```yaml
- launchApp
- waitForAnimationToEnd
- takeScreenshot: /tmp/animation-complete
```

## Tips for Writing Flows

1. **Always wait after launchApp** - Use `waitForAnimationToEnd` or `assertVisible`
2. **Handle different states** - Use conditional flows for popups or loading states
3. **Take screenshots** - Add screenshots at key points for debugging
4. **Use descriptive names** - Name screenshot files clearly for easier debugging

## Common Issues and Solutions

### Element Not Found
- Add `waitForAnimationToEnd` before the tap
- Use `scrollUntilVisible` for off-screen elements
- Check element text/exact match with `maestro hierarchy`

### App Not Installed
- Install first: `xcrun simctl install booted app.ipa` (iOS)
- Install first: `adb install app.apk` (Android)

### Timing Issues
- Use `extendedWaitUntil` with timeout
- Add `repeat` blocks for flaky elements
- Use `assertVisible` to ensure elements are ready

### Device Issues
- Ensure device is unlocked
- Check app permissions
- Verify device is connected: `adb devices` or `xcrun simctl list`
