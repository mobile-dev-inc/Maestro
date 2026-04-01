# Unified Web Driver Design

## Problem

Maestro has two web driver implementations:

- **`CdpWebDriver`** — used by CLI/Studio. Creates a local `ChromeDriver`, extracts the debugger address, and uses CDP for JS evaluation, screenshots, navigation, and `clearState`. Uses Selenium for interactions (tap, swipe, input).
- **`WebDriver`** — used by Cloud (maestro-worker via Browserbase). Takes a `SeleniumFactory`, uses Selenium for everything. Has features CdpWebDriver lacks: `executeAsyncJS`, Flutter web support, page-load waits.

This split caused the `clearState` bug: PR #2996 implemented it in `CdpWebDriver` but `WebDriver` was a no-op. Any future web feature risks the same gap. CLI and Cloud diverge silently.

## Goal

One `WebDriver` class. One code path. Every consumer (CLI, Studio, Cloud) uses it via `Maestro.web()`. `CdpWebDriver` is the source of truth — it already has CDP for JS eval, screenshots, navigation, and `clearState`. We enhance it with the missing features from the current `WebDriver`, rename it to `WebDriver`, and delete the old Selenium-only `WebDriver`.

## Approach

**`CdpWebDriver` stays as the foundation.** It gets:
1. `SeleniumFactory` param (replace hardcoded `ChromeDriver` creation)
2. `CdpClientFactory` interface (replace hardcoded local CDP client creation)
3. `executeAsyncJS` (from old `WebDriver`, needed for Flutter web)
4. Flutter-aware scroll/swipe methods (from old `WebDriver`)
5. Page-load wait in `launchApp` (from old `WebDriver`)
6. Cached Flutter detection in `open()` (eliminate repeated `isFlutterApp()` JS calls)

Then rename `CdpWebDriver` → `WebDriver`, delete old `WebDriver.kt`.

## Architecture

### Constructor

```kotlin
class WebDriver(
    val isStudio: Boolean,
    private val seleniumFactory: SeleniumFactory = ChromeSeleniumFactory(isHeadless = false, screenSize = null),
    private val cdpClientFactory: CdpClientFactory = LocalCdpClientFactory(),
    private val screenSize: String? = null
) : Driver
```

### `SeleniumFactory` (unchanged)

Already exists at `maestro.web.selenium.SeleniumFactory`. Two implementations:

- `ChromeSeleniumFactory` — local Chrome for CLI/Studio (already exists)
- `BrowserbaseSeleniumFactory` — remote Browserbase for Cloud (already exists in maestro-worker)

### `CdpClientFactory` (new)

```kotlin
interface CdpClientFactory {
    fun create(seleniumDriver: org.openqa.selenium.WebDriver): CdpClient
}
```

Takes the Selenium driver created by `SeleniumFactory` and returns a connected `CdpClient`. This is needed because the CDP connection details depend on how the browser was created:

- **Local Chrome**: extract `debuggerAddress` from ChromeDriver capabilities → `CdpClient(host, port)` connecting to `http://localhost:{port}/json` for target discovery and `ws://localhost:{port}/devtools/page/{id}` for commands.
- **Browserbase**: use `wss://connect.browserbase.com?apiKey={key}&sessionId={id}` for commands. Target discovery via Browserbase's API or a single-target assumption.

Two implementations:

```kotlin
// For CLI/Studio — extracts debugger address from local ChromeDriver
class LocalCdpClientFactory : CdpClientFactory {
    override fun create(seleniumDriver: WebDriver): CdpClient {
        val options = (seleniumDriver as ChromeDriver)
            .capabilities
            .getCapability("goog:chromeOptions") as Map<String, Any>
        val debuggerAddress = options["debuggerAddress"] as String
        val parts = debuggerAddress.split(":")
        return CdpClient(host = parts[0], port = parts[1].toInt())
    }
}

// For Cloud — connects to Browserbase's CDP endpoint
class BrowserbaseCdpClientFactory(
    private val apiKey: String,
    private val sessionId: String
) : CdpClientFactory {
    override fun create(seleniumDriver: WebDriver): CdpClient {
        return CdpClient(
            webSocketUrl = "wss://connect.browserbase.com?apiKey=$apiKey&sessionId=$sessionId"
        )
    }
}
```

### `CdpClient` changes

`CdpClient` currently hardcodes `http://{host}:{port}/json` for target listing and opens a new WebSocket per command using `target.webSocketDebuggerUrl`. For Browserbase, we need to support a direct WebSocket URL where target listing isn't needed (single session = single target).

Add a second constructor to `CdpClient`:

```kotlin
class CdpClient private constructor(
    private val httpClient: HttpClient,
    private val targetProvider: TargetProvider
) {
    // Local Chrome — discovers targets via HTTP
    constructor(host: String, port: Int) : this(
        httpClient = ...,
        targetProvider = HttpTargetProvider(host, port)
    )

    // Browserbase — single WebSocket URL, no target discovery
    constructor(webSocketUrl: String) : this(
        httpClient = ...,
        targetProvider = DirectTargetProvider(webSocketUrl)
    )
}
```

`TargetProvider` is internal to `CdpClient` — not a public interface. It abstracts how the client gets a `CdpTarget` (via HTTP target list vs. a known WebSocket URL).

### Flutter Detection — Cached in `open()`

Currently every `scrollVertical`, `swipe(direction)`, and `swipe(start,end)` calls `executeJS("window.maestro.isFlutterApp()")` independently. The app type doesn't change mid-session.

Detect once in `open()` after the Selenium driver and CDP client are created:

```kotlin
private var isFlutterApp: Boolean = false

override fun open() {
    seleniumDriver = seleniumFactory.create()
    cdpClient = cdpClientFactory.create(seleniumDriver!!)
    // ... DevTools setup ...
    isFlutterApp = executeJS("window.maestro.isFlutterApp()") as? Boolean ?: false
}
```

All scroll/swipe methods use the cached `isFlutterApp` field directly.

## Features Added from Old `WebDriver`

### `executeAsyncJS`

Needed for Flutter web scrolling. Uses Selenium's `executeAsyncScript` which supports callback-based async execution. CDP's `Runtime.evaluate` has `awaitPromise: true` but `CdpClient` doesn't wire it up, and adding async support to CDP eval requires significant protocol work.

```kotlin
private fun executeAsyncJS(js: String, timeoutMs: Long): Any? {
    val executor = seleniumDriver as JavascriptExecutor
    // ... inject maestro-web.js and arguments ...
    seleniumDriver?.manage()?.timeouts()?.scriptTimeout(Duration.ofMillis(timeoutMs))
    val wrapped = """
        const callback = arguments[arguments.length - 1];
        Promise.resolve((function() { return $js; })())
            .then((result) => callback(result))
            .catch(() => callback(null));
    """.trimIndent()
    return executor.executeAsyncScript(wrapped)
}
```

### Flutter-Aware Scrolling

**`scrollVertical`** — detect Flutter, use `executeAsyncJS` for `smoothScrollFlutter`:

```kotlin
override fun scrollVertical() {
    if (isFlutterApp) {
        executeAsyncJS("window.maestro.smoothScrollFlutter('UP', 500)", 1500L)
    } else {
        scroll("window.scrollY + Math.round(window.innerHeight / 2)", "window.scrollX")
    }
}
```

**`swipe(start, end, durationMs)`** — Flutter: wheel events via `smoothScrollFlutterByDelta`. Standard: touch pointer drag.

**`swipe(swipeDirection, durationMs)`** — Flutter: `smoothScrollFlutter`. Standard: window scroll.

### Page-Load Wait in `launchApp`

After CDP `Page.navigate`, wait for `document.readyState == "complete"`:

```kotlin
override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
    injectedArguments = injectedArguments + launchArguments

    runBlocking {
        val target = cdpClient.listTargets().first()
        cdpClient.openUrl(appId, target)
    }

    // Wait for page to be ready
    val driver = ensureOpen()
    WebDriverWait(driver, Duration.ofSeconds(30L))
        .until { (it as JavascriptExecutor).executeScript("return document.readyState") == "complete" }
}
```

Note: Old `WebDriver.launchApp` also calls `open()` which re-creates the Selenium driver on every launch. This is unnecessary and won't be carried over. The driver is opened once in `open()`.

## Features Kept from `CdpWebDriver` (unchanged)

These already work correctly and are the reason `CdpWebDriver` is the source of truth:

- **`executeJS`** — CDP `Runtime.evaluate` with JSON.stringify serialization
- **`takeScreenshot`** — CDP `Page.captureScreenshot`, returns raw PNG bytes
- **`clearAppState`** — CDP `Storage.clearDataForOrigin`, origin-aware clearing
- **`launchApp` navigation** — CDP `Page.navigate`
- **`deviceInfo`** — CDP `executeJS` for window dimensions (returns `Int`)
- **`contentDescriptor`** — CDP `executeJS` for DOM hierarchy

## Consumer Changes

### CLI (`Maestro.web()`)

```kotlin
fun web(
    isStudio: Boolean,
    isHeadless: Boolean,
    screenSize: String?,
    seleniumFactory: SeleniumFactory = ChromeSeleniumFactory(isHeadless, screenSize),
    cdpClientFactory: CdpClientFactory = LocalCdpClientFactory(),
): Maestro {
    // Check that JRE is at least 11
    // ...

    val driver = WebDriver(
        isStudio = isStudio,
        seleniumFactory = seleniumFactory,
        cdpClientFactory = cdpClientFactory,
        screenSize = screenSize
    )
    driver.open()
    return Maestro(driver)
}
```

No change needed for existing CLI callers — defaults handle it.

### `DeviceService.kt`

Update the `Platform.WEB` branch to use `WebDriver` with new constructor (same behavior, just using the factory pattern now).

### Cloud (`WebDevice.kt` in maestro-worker)

```kotlin
private fun createDefaultMaestro(): Maestro {
    return Maestro.web(
        isStudio = false,
        isHeadless = false,
        screenSize = "1920x1080",
        seleniumFactory = BrowserbaseSeleniumFactory(
            sessionId = sessionId,
            apiKey = browserbaseClient.apiKey,
        ),
        cdpClientFactory = BrowserbaseCdpClientFactory(
            apiKey = browserbaseClient.apiKey,
            sessionId = sessionId,
        ),
    )
}
```

## Files to Delete

- `maestro-client/src/main/java/maestro/drivers/WebDriver.kt` — replaced entirely by the enhanced+renamed `CdpWebDriver`.

## Migration Risks

### Browserbase CDP compatibility

The biggest unknown is whether Browserbase's CDP WebSocket endpoint supports all the methods we use:

- `Runtime.evaluate` — standard CDP method, expected to work
- `Page.captureScreenshot` — standard CDP method, expected to work
- `Page.navigate` — standard CDP method, expected to work
- `Storage.clearDataForOrigin` — standard CDP method, expected to work

If any of these fail on Browserbase, we'll discover it immediately in testing. The fix would be in `CdpClient`'s Browserbase mode, not in the driver.

### Target discovery on Browserbase

`CdpClient.listTargets()` uses `http://{host}:{port}/json` which won't work on Browserbase. The `DirectTargetProvider` (Browserbase mode) skips HTTP target listing and provides a `CdpTarget` with the known WebSocket URL directly. This is safe because a Browserbase session is always a single target.

### Type casting changes for Cloud

Cloud's old `WebDriver` currently returns `Long` from Selenium JS execution. The unified driver returns `Int` from CDP. All usages are screen dimensions and scroll offsets that fit in Int. No risk.

## Testing Plan

### Existing test flows (`demo_app/.maestro/web_flows/`)

| Flow | What it covers |
|---|---|
| `clear_state.yaml` | `clearState` command clears cookies/storage, login screen reappears |
| `clear_state_on_launch.yaml` | `launchApp(clearState: true)` clears state before navigation |
| `retain_state_default.yaml` | Default `launchApp` preserves session state across navigations |
| `iframe.yaml` | Asserting visibility of content inside iframes |
| `simple.yaml` | Login, tap, inputText, navigation, regex assertion |

### New test flows needed

| Flow | What it covers |
|---|---|
| `scroll.yaml` | Page with scrollable content, scroll down/up |
| `back_press.yaml` | Navigate forward then back |
| `erase_text.yaml` | Type in field, erase, retype |
| Flutter web scroll | Verify `smoothScrollFlutter` works on Flutter web build of demo app |

### Execution

1. **Build demo app for Flutter web** — ensure it can run as a web app.
2. **Local CLI**: Run all flows in `web_flows/` via `maestro test` — verify no regressions.
3. **E2E workflow**: Push Maestro branch, trigger `test-e2e.yaml` using demo app PR as reference.
4. **Cloud**: Update `WebDevice.kt` with `BrowserbaseCdpClientFactory`, test on Maestro Cloud.
