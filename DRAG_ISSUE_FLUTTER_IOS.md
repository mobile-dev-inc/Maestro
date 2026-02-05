# Drag Command Implementation - Handoff Document

## Summary

Implementing a new `drag` command for Maestro (GitHub issue #1203) that enables drag-and-drop gestures and item reordering in mobile apps.

## Current Status

- **Android**: ✅ Working perfectly (Flutter, React Native)
- **iOS + React Native**: ✅ Working perfectly (tested with react-native-drax)
- **iOS + Flutter**: ❌ Broken - element coordinates become garbage after first reorder

## The Core Problem

### This is bizarre for a tool whose entire purpose is finding elements on screen

Maestro's view hierarchy returns **incorrect (relative) bounds** for elements after the first drag operation reorders items in a Flutter `ReorderableListView`. This is a fundamental breakdown - the element is visible on screen, Maestro can see it exists, but the coordinates are garbage.

**Example:**
- First drag: Item 3 bounds = `[134, 306][268, 434]`, center = `(201, 370)` ✅ Correct
- Second drag: Item 2 bounds = `[0, 0][134, 21]`, center = `(67, 10)` ❌ WRONG

The Y=10 coordinate is at the very top of the device, which locks the screen when touched. The element is clearly visible in the middle of the list, not at the top.

### Why Android Works and iOS Doesn't

**Android:** Uses UIAutomator which consistently returns correct absolute screen coordinates for elements, even after reorder operations.

**iOS:** After Flutter's `ReorderableListView` reorders items, the iOS accessibility hierarchy returns **relative coordinates within a container** instead of absolute screen coordinates. This appears to be a bug in how Flutter reports accessibility frames to iOS after animations complete.

### What We've Investigated

The problem is NOT in our code - it's in what iOS returns to us:

1. **Maestro's view hierarchy fetch** - Correctly queries iOS and returns what iOS gives us
2. **XCUIElement queries** - Also return the same bad frames (tried `staticTexts`, `descendants`, `firstMatch`, iterating through `allElementsBoundByIndex`)
3. **XCUIElement.frame** - Returns `(0.0, 0.0, 134.0, 21.33)` for the affected element (incorrect bounds)
4. **XCUICoordinate.screenPoint** - Also wrong (derived from bad frame)
5. **app.snapshot()** - Forcing accessibility refresh doesn't help
6. **Re-querying after delay** - Same bad coordinates
7. **XCUIElement native drag API** - `press(forDuration:thenDragTo:)` also uses the bad frame internally

### The Pattern

- Element exists: ✅ `element.exists` returns true
- Element is visible: ✅ You can see it on screen
- Element label matches: ✅ Predicate matches correctly
- Element frame is valid: ❌ Returns relative/garbage coordinates

This suggests Flutter's accessibility layer on iOS doesn't properly update element frames after reorder animations complete. The accessibility tree knows the element exists and its label, but its coordinate reporting is broken.

### Critical Data Point: Manual Drag Works

**You can manually drag Item 2 to below Item 5 after the first reorder completes.** The UI is fully functional - touch handling works perfectly. This confirms:

1. The actual view hierarchy is correct
2. Touch/gesture handling works
3. Only the accessibility coordinate reporting is broken

This is purely an accessibility metadata problem, not a UI problem.

## Attempted Solutions

| Approach | Result |
|----------|--------|
| Use coordinate-based drag from Maestro's view hierarchy | ❌ Bad coords after first reorder |
| Use XCUIElement.frame directly on iOS | ❌ Same bad coords |
| Force accessibility refresh with `app.snapshot()` | ❌ Still bad coords |
| Re-query elements after delay | ❌ Still bad coords (also a code smell) |
| Iterate through all matching elements to find one with valid frame | ❌ ALL matching elements have bad frames |
| Use XCUIElement native drag API (`press(forDuration:thenDragTo:)`) | ❌ Uses bad coords internally |
| Query parent containers instead of text labels | ❌ Caused timeout/hang |

## Current Implementation State

### What's Working

- **Android drag**: Fully functional using `input draganddrop` shell command
- **iOS first drag**: Works because the accessibility tree still has correct element bounds
- **YAML parsing**: All drag command variants parse correctly
- **Driver layer**: All plumbing in place for both platforms

### Files Modified

**Command Layer:**
- `Commands.kt` - `DragCommand` data class
- `MaestroCommand.kt` - Added `dragCommand` field
- `YamlDrag.kt` - YAML deserializer (new file)
- `YamlFluentCommand.kt` - Drag handling

**Driver Layer:**
- `Driver.kt` - `drag()` and `dragByText()` interface methods
- `Maestro.kt` - Client-side drag methods
- `AndroidDriver.kt` - Working implementation
- `IOSDriver.kt` - Implementation (broken by iOS bug)
- `WebDriver.kt`, `CdpWebDriver.kt` - Stubs

**iOS XCTest Runner (Swift):**
- `DragRouteHandler.swift` - HTTP handler with `dragByText` implementation
- `DragRequest.swift` - Request model
- `EventRecord.swift` - Touch event synthesis
- `XCTestHTTPServer.swift`, `RouteHandlerFactory.swift` - Routing

**Orchestra:**
- `Orchestra.kt` - `dragCommand()` handler with debug logging

### Test Files

- `e2e/workspaces/demo_app/commands/drag_test.yaml`
- Demo app: `~/dev/maestro_demo_app/lib/drag_screen.dart` (Flutter ReorderableListView)

## Latest Debug Session Findings (2026-02-04)

### Debug Output Added

Added `println` debug statements to both `Orchestra.dragCommand()` and `Maestro.tap()` to trace coordinate resolution.

### Key Finding: TAP ALSO GETS BAD COORDINATES

```
Tap on "Drag Test"...[TAP DEBUG] element ORIGINAL bounds=Bounds(x=144, y=262, width=113, height=48)
[TAP DEBUG] FINAL center=Point(x=200, y=286)    <-- CORRECT

Drag from ".*Item 3.*" to ".*Item 1.*"...
[DRAG DEBUG] fromElement '.*Item 3.*' ORIGINAL bounds=Bounds(x=0, y=338, width=402, height=64)
[DRAG DEBUG] FINAL bounds=Bounds(x=0, y=338, width=402, height=64) center=Point(x=201, y=370)  <-- CORRECT

Tap on "Item 2"...[TAP DEBUG] element ORIGINAL bounds=Bounds(x=0, y=0, width=134, height=21)
[TAP DEBUG] refreshedElement bounds=Bounds(x=0, y=0, width=134, height=21)
[TAP DEBUG] FINAL center=Point(x=67, y=10)      <-- BAD COORDS! But tap still "works"?

Drag from ".*Item 2.*"...
[DRAG DEBUG] fromElement '.*Item 2.*' ORIGINAL bounds=Bounds(x=0, y=0, width=134, height=21)
[DRAG DEBUG] FINAL bounds=Bounds(x=0, y=0, width=134, height=21) center=Point(x=67, y=10)  <-- SAME BAD COORDS
```

### Critical Observation

- **Tap on "Item 2" reports COMPLETED** in the test output
- **BUT tap is using coordinates (67, 10)** - same garbage coordinates as drag!
- Y=10 is at the very top of the screen (notification area)
- **Question: Is tap actually working, or just not failing?**

Tapping at Y=10 might pull down the iOS notification shade slightly but not cause an error. The test shows ✅ because the tap command executed without throwing, not because it actually interacted with Item 2.

### Hierarchy Inspection

Examined the saved hierarchy JSON from test output:
```
"accessibilityText" : "Item 2",
"bounds" : "[0,0][134,21]",   <-- Only ONE Item 2 in hierarchy, with garbage bounds
```

There is no shadow/duplicate element. The hierarchy itself contains only bad data for Item 2 after reorder.

### What This Means

1. **Both tap AND drag get the same bad coordinates** from `findElement()`
2. **The hierarchy returned by iOS contains incorrect/stale element bounds** after a reorder operation
3. **`refreshElement()` doesn't help** because it refreshes against the same bad hierarchy
4. **`waitForAppToSettle()` returns NULL** (screen settles quickly), so we fallback to the initial bad hierarchy
5. **Tap "succeeds" only because tapping the wrong location doesn't throw an error**

### Code Path Analysis

Both tap and drag follow the same coordinate resolution:
1. `findElement()` → returns `FindElementResult(element, hierarchy)` with BAD bounds
2. `waitForAppToSettle(hierarchy)` → returns NULL (screen already settled)
3. `refreshElement(element.treeNode)` on fallback hierarchy → finds same element with same bad bounds
4. Extract center → (67, 10) garbage coordinates

The problem is **upstream of our code** - the iOS accessibility layer is returning bad bounds data.

### Conclusion: TAP IS ALSO BROKEN

**Tap is definitely also broken** - it uses the exact same code path and gets the exact same garbage coordinates as drag. The difference is:
- **Drag at Y=10**: Visibly pulls down iOS lock screen liquid - obviously wrong
- **Tap at Y=10**: Taps the top of screen, nothing visible happens, test doesn't fail

The demo app's list items have no `onTap` handler, so there's no visual feedback to confirm where the tap actually lands. But since tap uses identical coordinates to drag, and drag visibly starts at Y=10, tap is also hitting Y=10.

**This is a Flutter/iOS accessibility bug**, not a Maestro bug. After `ReorderableListView` reorders items, iOS returns garbage bounds `[0,0][134,21]` for moved elements instead of their actual screen positions.

### Confirmed: React Native + iOS Works Fine

Tested the same drag implementation against a React Native app (Spicy Golf) using `react-native-drax` for drag-and-drop:

- **First drag**: ✅ Works
- **Second drag**: ✅ Works  
- **Multiple consecutive drags**: ✅ All work correctly

This confirms the bug is **specifically Flutter + iOS**, not a Maestro or iOS issue. The Maestro drag implementation is correct.

## Possible Next Steps

### 1. Use XCUICoordinate Without Element Resolution (Appium's Approach)

A comment on GitHub issue #1203 pointed to how Appium's WebDriverAgent handles this - using `XCUICoordinate` APIs that operate purely on coordinates without resolving `XCUIElement` objects:

```objc
// From XCUICoordinate.h (Xcode 12+)
- (void)pressForDuration:(double)duration
    thenDragToCoordinate:(XCUICoordinate *)otherCoordinate
    withVelocity:(CGFloat)velocity
    thenHoldForDuration:(double)holdDuration;
```

Reference: https://github.com/appium/WebDriverAgent/blob/67c8d73a08cb7fdf04a7db64f62ec2bd258eed64/PrivateHeaders/XCTest/XCUICoordinate.h#L38

**We already have this infrastructure** in `DragRouteHandler.swift`:

```swift
// dragWithXCUICoordinate() creates coordinates from app, not elements:
let startCoord = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
    .withOffset(CGVector(dx: start.x, dy: start.y))
```

**The problem**: This approach works if you have correct screen coordinates. But Maestro's Kotlin layer resolves coordinates by querying the accessibility hierarchy *before* calling Swift — and after a reorder, that hierarchy returns incorrect/stale element bounds. The Swift-side element queries also get the same bad data.

**To make this work**, we'd need to calculate expected element positions mathematically (based on list structure, item heights, reorder history) rather than querying them. This is a significant design change - essentially tracking list state instead of querying it.

### 2. Check if This is a Known Flutter/iOS Issue

Search for Flutter issues related to:
- ReorderableListView accessibility
- iOS accessibility frames after animation
- Flutter semantic bounds incorrect after reorder

### 3. Try a Completely Different Approach

Instead of finding element coordinates, could we:
- Use relative drag (swipe from center of list up/down by N items)?
- Calculate expected positions based on list structure rather than querying elements?
- Use a different Flutter widget that doesn't corrupt accessibility?

### 4. File a Flutter Bug

If this is confirmed to be a Flutter/iOS accessibility bug, document and file it upstream.

## Build Commands

```bash
# Build XCTest runner
cd /Users/brad/dev/Maestro/maestro-ios-xctest-runner
xcodebuild -project maestro-driver-ios.xcodeproj -scheme maestro-driver-ios \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,id=8F7B2E3B-2D1D-4F13-BC85-7442A88DAD6F' \
  build-for-testing

# Package and copy to resources
cd /Users/brad/Library/Developer/Xcode/DerivedData/maestro-driver-ios-fzhtqcmioilcjtctxlrxlfwsfhmc/Build/Products/Debug-iphonesimulator
zip -rq /tmp/maestro-driver-iosUITests-Runner.zip maestro-driver-iosUITests-Runner.app
cp /tmp/maestro-driver-iosUITests-Runner.zip \
  /Users/brad/dev/Maestro/maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/

# Rebuild Maestro CLI
cd /Users/brad/dev/Maestro
./gradlew :maestro-cli:installDist

# Run iOS test
./maestro-cli/build/install/maestro/bin/maestro test --device "8F7B2E3B-2D1D-4F13-BC85-7442A88DAD6F" \
  e2e/workspaces/demo_app/commands/drag_test.yaml

# Run Android test
./maestro-cli/build/install/maestro/bin/maestro test --device emulator-5554 \
  e2e/workspaces/demo_app/commands/drag_test.yaml
```

## Environment

- macOS Darwin 25.2.0
- Java 17 (via jenv)
- Gradle 8.13
- Flutter 3.38.9
- Xcode 16.4.0
- iOS Simulator: iPhone 17 Pro - iOS 26.2 (UUID: 8F7B2E3B-2D1D-4F13-BC85-7442A88DAD6F)
- Android Emulator: Pixel 8 Pro (emulator-5554)
