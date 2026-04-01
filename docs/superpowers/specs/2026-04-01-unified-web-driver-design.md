# Unified Web Driver Design

## Problem

Maestro has two web driver implementations:

- **`CdpWebDriver`** — used by CLI/Studio. Creates a local `ChromeDriver`, extracts the debugger address, and uses CDP for JS evaluation, screenshots, navigation, and `clearState`. Uses Selenium for interactions (tap, swipe, input).
- **`WebDriver`** — used by Cloud (maestro-worker via Browserbase). Takes a `SeleniumFactory`, uses Selenium for everything. Has features CdpWebDriver lacks: `executeAsyncJS`, Flutter web support, page-load waits.

This split caused the `clearState` bug: PR #2996 implemented it in `CdpWebDriver` but `WebDriver` was a no-op. Any future web feature risks the same gap. CLI and Cloud diverge silently.

## Goal

One `WebDriver` class. One code path. Every consumer (CLI, Studio, Cloud) uses it. `CdpClient` is a required dependency — no optional fallbacks.

## Architecture

### Constructor

```kotlin
class WebDriver(
    val isStudio: Boolean,
    private val seleniumFactory: SeleniumFactory,
    private val cdpClientFactory: CdpClientFactory,
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

Change `CdpClient` to support two modes:

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

## Method-by-Method Decisions

The unified driver takes the best implementation from each existing driver. The selection criteria: use CDP where it provides better reliability or correctness, use Selenium where CDP lacks capability.

### JS Execution

**`executeJS`** — use CDP `Runtime.evaluate` (from `CdpWebDriver`).

CDP wraps expressions in `JSON.stringify` and returns strings parsed via Jackson. This gives consistent serialization across local and remote. The current `WebDriver` uses Selenium's `JavascriptExecutor` which returns Java objects with inconsistent types (Long vs Int depending on driver implementation).

Callers that currently cast to `Long` (in `WebDriver`) will cast to `Int` (as in `CdpWebDriver`). Since all values are screen dimensions that fit in Int, this is safe.

**`executeAsyncJS`** — use Selenium `executeAsyncScript` (from `WebDriver`).

CDP's `Runtime.evaluate` has `awaitPromise: true` but `CdpClient` doesn't wire it up, and adding async support to CDP eval requires significant protocol work. Selenium's async script execution works today and is only needed for Flutter web support. Keep it as-is.

### Navigation

**`launchApp`** — CDP `Page.navigate` + Selenium page-load wait.

`CdpWebDriver` fires CDP `Page.navigate` but doesn't wait for the page to load. `WebDriver` uses Selenium `driver.get()` + `WebDriverWait` until `document.readyState == "complete"`. The unified driver uses CDP for navigation and adds the readyState wait:

```kotlin
override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
    injectedArguments = injectedArguments + launchArguments

    runBlocking {
        val target = cdpClient.listTargets().first()
        cdpClient.openUrl(appId, target)
    }

    // Wait for page to be ready (from WebDriver)
    val driver = ensureOpen()
    WebDriverWait(driver, Duration.ofSeconds(30L))
        .until { (it as JavascriptExecutor).executeScript("return document.readyState") == "complete" }
}
```

Note: `WebDriver.launchApp` also calls `open()` which re-creates the Selenium driver on every launch. This is unnecessary and won't be carried over. The driver is opened once in `open()`.

### Screenshots

**`takeScreenshot`** — use CDP `Page.captureScreenshot` (from `CdpWebDriver`).

Returns raw PNG bytes directly from the browser. More reliable than Selenium's `TakesScreenshot` which writes to a temp file. No behavioral difference for consumers.

### State Management

**`clearAppState`** — use CDP `Storage.clearDataForOrigin` (from `CdpWebDriver`).

Origin-aware clearing of all storage types (cookies, localStorage, sessionStorage, IndexedDB, cache, service workers). This is the whole reason we're here — `WebDriver` had this as a no-op.

### Scrolling and Swiping

**`scrollVertical`** — use `WebDriver`'s implementation with Flutter detection.

`CdpWebDriver` has no Flutter awareness. `WebDriver` detects Flutter apps and uses `executeAsyncJS` for smooth scrolling. Keep this.

**`swipe(start, end, durationMs)`** — use `WebDriver`'s implementation with Flutter detection.

Same reasoning. `CdpWebDriver` only has basic pointer input. `WebDriver` adds Flutter-specific wheel event scrolling via `smoothScrollFlutterByDelta`.

**`swipe(swipeDirection, durationMs)`** — use `WebDriver`'s implementation.

Flutter-aware direction-based scrolling.

### Interactions

**`tap`**, **`longPress`**, **`pressKey`**, **`inputText`**, **`eraseText`**, **`backPress`** — both drivers use identical Selenium-based implementations. No change needed. The unified driver uses these as-is from `WebDriver`.

### Other Methods

- **`deviceInfo`** — use CDP `executeJS` instead of Selenium. Returns `Int` (same as `CdpWebDriver`).
- **`contentDescriptor`** — use CDP `executeJS`. Both drivers have identical logic otherwise.
- **`openLink`** — use Selenium `driver.get()` (both are identical).
- **`setLocation`** — use Selenium DevTools API (both are identical).
- **`startScreenRecording`** — both are identical (Selenium-based).
- **`queryOnDeviceElements`** — both are identical.

## Consumer Changes

### CLI (`Maestro.kt`)

```kotlin
fun web(isStudio: Boolean, isHeadless: Boolean, screenSize: String?): Maestro {
    val driver = WebDriver(
        isStudio = isStudio,
        seleniumFactory = ChromeSeleniumFactory(isHeadless, screenSize),
        cdpClientFactory = LocalCdpClientFactory(),
        screenSize = screenSize
    )
    driver.open()
    return Maestro(driver)
}
```

### Cloud (`WebDevice.kt` in maestro-worker)

```kotlin
private fun createDefaultMaestro(): Maestro {
    val driver = WebDriver(
        isStudio = false,
        seleniumFactory = BrowserbaseSeleniumFactory(
            sessionId = sessionId,
            apiKey = browserbaseClient.apiKey,
        ),
        cdpClientFactory = BrowserbaseCdpClientFactory(
            apiKey = browserbaseClient.apiKey,
            sessionId = sessionId,
        ),
        screenSize = "1920x1080"
    )
    driver.open()
    return Maestro(driver)
}
```

## Files to Delete

- `maestro-client/src/main/java/maestro/drivers/CdpWebDriver.kt` — replaced entirely by unified `WebDriver`.

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

Cloud's `WebDriver` currently returns `Long` from Selenium JS execution. The unified driver returns `Int` from CDP. All usages are screen dimensions and scroll offsets that fit in Int. No risk.

## Testing Plan

### Existing test flows (`demo_app/.maestro/web_flows/`)

| Flow | What it covers |
|---|---|
| `clear_state.yaml` | `clearState` command clears cookies/storage, login screen reappears |
| `clear_state_on_launch.yaml` | `launchApp(clearState: true)` clears state before navigation |
| `retain_state_default.yaml` | Default `launchApp` preserves session state across navigations |
| `iframe.yaml` | Asserting visibility of content inside iframes |
| `simple.yaml` | Login, tap, inputText, navigation, regex assertion |

### Coverage mapping to unified driver capabilities

| Unified Driver Capability | Existing Coverage | Needs New Flow |
|---|---|---|
| CDP `executeJS` (hierarchy/assertions) | All flows | No |
| CDP `Page.navigate` (launchApp) | All flows | No |
| CDP `Storage.clearDataForOrigin` (clearState) | `clear_state.yaml`, `clear_state_on_launch.yaml` | No |
| CDP `Page.captureScreenshot` | Implicit in all flows | No |
| Selenium page-load wait (new for CDP nav) | All flows | No |
| Selenium tap | `simple.yaml`, login flows | No |
| Selenium inputText | All login flows | No |
| Selenium `executeAsyncJS` (Flutter scroll) | **MISSING** | Yes — Flutter web app with scroll |
| Selenium backPress | **MISSING** | Yes — navigate forward then back |
| scrollVertical / swipe | **MISSING** | Yes — page with scrollable content |
| eraseText | **MISSING** | Yes — type in field, erase, retype |
| State retention (no clearState) | `retain_state_default.yaml` | No |
| iframe content detection | `iframe.yaml` | No |

### Execution

1. **Local CLI**: Run all flows in `web_flows/` via `maestro test` — verify no regressions.
2. **Cloud**: Upload and run all flows on Maestro Cloud — verify parity with CLI.
3. **Browserbase CDP**: Specifically verify `Storage.clearDataForOrigin`, `Runtime.evaluate`, `Page.captureScreenshot`, and `Page.navigate` work over Browserbase's CDP WebSocket.
