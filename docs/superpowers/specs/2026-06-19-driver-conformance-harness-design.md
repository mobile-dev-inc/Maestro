# Driver Conformance Harness — Design

**Status:** Draft for review
**Date:** 2026-06-19
**Scope:** Android-only (v1), abstraction-ready for iOS

---

## 1. Purpose & Positioning (why this exists, and why it is NOT E2E)

The **Driver Conformance Harness** proves that **each `AndroidDriver` primitive behaves
correctly across the rendering frameworks and API levels Maestro supports.**

It sits directly on top of `maestro-client`'s `AndroidDriver` (the `Driver` interface) and
exercises one command at a time against controlled fixture apps, verifying the *effect* of
the command via a channel independent of the driver.

### How it differs from E2E

| | E2E (Maestro flows) | Driver Conformance (this) |
|---|---|---|
| Unit under test | A user's app + a journey through it | A single driver command (`tap`, `swipe`, …) |
| Question answered | "Does *this app's* login flow work?" | "Does `swipe` actually swipe on Compose API 28? Flutter API 35?" |
| App role | The thing being tested | A controlled *instrument* (fixture) with known targets |
| Oracle | Visible UI assertions in the flow | Out-of-band event the fixture emits, independent of the command |
| Axis of variation | App features | framework × API level × command |
| Who it protects | The app author | **Maestro itself** — catches driver regressions before every user hits them |

**One-liner for the team:** *E2E tests apps with Maestro; the conformance harness tests
Maestro's driver against apps.* If `tap` silently breaks on Flutter API 35, no single E2E
flow is responsible for catching it — this harness is.

---

## 2. Key Decisions (locked)

1. **Platform scope:** Android-only for v1; design abstractions so `IOSDriver` (SwiftUI/UIKit)
   drops in later without reworking the harness core.
2. **Oracle:** Out-of-band app oracle. Fixtures emit structured events via a channel
   independent of the driver (logcat), so observation never depends on the command under test.
3. **Provisioning:** No dependency on `maestro-device`. Fresh AVDs created with stock Android
   SDK tooling, behind a swappable `DeviceProvider` interface. Explicit BYO-device allowed
   (with a loud banner); never silently adopt a running emulator for the matrix.
4. **Selection:** Cross product of `--api` × `--framework` → independent cells.
5. **Fixtures:** One shared *fixture contract*; one thin app per framework implementing it.
   Harness logic is framework-blind.
6. **Command scope:** Tier A (UI-interaction commands) for v1; structure so Tier B
   (device-state) and Tier C (meta) plug in later.
7. **Artifacts:** `command.json` (the verdict/evidence record) is the spine; media (video,
   stills, hierarchy, logcat) are evidence captured in tiers proportional to cost vs. use.
8. **No new module.** The harness lives **inside `maestro-client`** in a dedicated
   `conformance` source set with its own runnable entrypoint + Gradle task — it reuses
   `AndroidDriver` directly and avoids spinning up a new Gradle module.
9. **Isolated from unit tests.** The conformance task is **not** wired into `test` / `check`,
   so `./gradlew test` and the unit-test CI (`test.yaml`) never run it. It needs a live device
   and is far slower than a unit test.
10. **On-demand trigger (v1).** Runs via a manual `workflow_dispatch` GitHub Actions workflow,
    separate from unit-test CI. Future: target by file changes (driver / fixture paths).

---

## 3. Architecture

Lives **inside `maestro-client`** in a dedicated `conformance` source set (e.g.
`maestro-client/src/conformance/kotlin`), reusing `AndroidDriver` directly — no new Gradle
module. Built and run via a dedicated Gradle task that is excluded from `test` / `check`
(see §9). Five decoupled pieces:

```
┌─────────────────────────────────────────────────────────────┐
│  CLI / entrypoint  (--api, --framework, selection → cells)   │
└───────────────┬─────────────────────────────────────────────┘
                │ for each cell (api × framework):
        ┌───────▼────────┐   ┌──────────────┐   ┌────────────────┐
        │ DeviceProvider │   │ FixtureApp    │   │ CommandBehavior│
        │ (fresh AVD,    │──▶│ (per-framework│◀─│  registry      │
        │  swappable)    │   │  contract)    │   │ (Tier A cmds)  │
        └───────┬────────┘   └──────┬────────┘   └──────┬─────────┘
                │ DeviceHandle       │ installs          │ runs
                │ (serial+Driver)    ▼                   ▼
                │            ┌─────────────────────────────────┐
                │            │  LogcatEventReader (log I/O only)│
                │            └────────────────┬────────────────┘
                ▼                             ▼
        ┌──────────────────────────────────────────────────────┐
        │  Reporter → per-cell artifacts + JSON + HTML aggregate │
        └──────────────────────────────────────────────────────┘
```

- **DeviceProvider** — `FreshAvdProvider` (default), `AttachedDeviceProvider` (explicit BYO),
  future `GoldenSnapshotProvider` / `CloudDeviceProvider`. Hands back `DeviceHandle`
  (serial + booted `AndroidDriver`).
- **FixtureApp** — one per framework, all satisfying the same contract (§5).
- **CommandBehavior** — one small class per command encoding its before/after test (§4). Owns
  the **pass/fail decision** (it knows what it expected); the reader does not.
- **LogcatEventReader** — owns the adb logcat stream only: tails it, filters the
  `MAESTRO_FIXTURE` tag, parses each line into a typed `FixtureEvent(seq, type, payload)`,
  buffers them. Pure log I/O — no notion of "expected." (Renamed from `EventOracle`; "oracle"
  survives as a *concept*, the source of truth, not a class.)
- **Reporter** — per-cell artifact dir + machine-readable JSON + static HTML aggregator (§8).

The harness core only ever sees `DeviceHandle` — it does not know or care how the device was
born, which is what keeps it host-independent.

---

## 4. Per-test lifecycle & command model (red/green, adapted)

### Per-test lifecycle (one command, one cell)

Some steps run **once per cell** (device + fixture are shared by all the cell's commands); the
rest run **per command**.

```
PER CELL (once):
  C1  acquire device      DeviceProvider.acquire(spec) → DeviceHandle (serial + AndroidDriver)
  C2  install fixture     install the cell's framework APK (e.g. compose-fixture.apk)
  C3  start log reader     LogcatEventReader tails `adb logcat -s MAESTRO_FIXTURE`,
                           parsing each line → FixtureEvent(seq, type, payload)

PER COMMAND (e.g. tap):
  1  arrange      launchApp(appId, {"route":"TapScreen"})   ← deep link, NOT a tap
  2  baseline     ── APP class ──   sync barrier: fixture emits MARK seq=K → watermark W = K
                  ── RET class ──   read pre-value (pidof / isKeyboardVisible() / field text)
  3  pre-check    ── APP ──  assert NO event of expected type with seq > W   (red holds)
                  ── RET ──  assert pre-value is the "not yet" state
  4  act          driver.tap(centerOf("tap_target"))        ← (screen recording wraps this)
  5  observe      ── APP ──  poll buffer for event(type, seq > W) within timeout
                  ── RET ──  read post-value (return value / probe)
  6  verdict      ATTRIBUTION (seq > W, or value changed) AND PAYLOAD PREDICATE (fields match)
                  → PASS only if BOTH hold
  7  artifacts    always: command.json, events.log, after.png
                  on fail / --record all: recording.mp4, before.png, hierarchy.json, logcat-slice.txt
  8  reset        re-route / clearAppState as needed so the next command starts clean

PER CELL (teardown):
  C4  release device      DeviceProvider.release(handle)  (wipe/delete fresh AVD)
```

**The two checks in step 6 are the whole story.** They are orthogonal, and a pass needs both:

- **Attribution** (step 2's baseline marker) answers *"was this effect caused by* ***our*** *act,
  not a leftover?"* — for APP commands it's a `seq` watermark (a `MARK` barrier emitted by the
  fixture, so everything ≤ K is "before" and > K is "after"); for RET commands it's the
  before→after value delta. This is the red→green causation.
- **Payload predicate** (the structured fields) answers *"did it do the* ***right*** *thing?"* —
  the logcat line is not a "something happened" ping; it is a **structured report of what the
  app actually received** (raw `TOUCH` coords, echoed launch `args`, full `text`, swipe
  `dir`+`dx`/`dy`). The predicate asserts over those fields.

This is why presence-only checking is insufficient: a tap at the wrong coordinates still clears
attribution (an event appears past `W`) but **fails the payload predicate** (`TOUCH.x,y` ≠
commanded). Likewise a `launchApp` that ignores its arguments produces a `LAUNCHED` line but with
`args:{}` ≠ the map sent. The fields are where correctness lives.

### Command model (four steps)

maestro-device's red/green proves *causation*. For a command, causation = the observable
effect appears only because the command ran. Each `CommandBehavior` has four steps:

```
1. arrange   → driver.launchApp(appId, {"route":"<Screen>"})  — navigate via deep link /
               launch arg, NOT via tap, so arrange never depends on the command under test
2. pre-check → capture baseline (oracle shows NO event yet, OR snapshot return/probe value)
3. act       → driver.<command>(args)                   (the thing under test)
4. post-check→ assert the expected effect: an emitted event, OR a returned value / device probe
```

Example — `tap`:
1. open fixture `TapScreen` (target at known element id `tap_target`).
2. assert no `TAP` event in the oracle stream.
3. `driver.tap(centerOf("tap_target"))`.
4. assert oracle emitted `TAP target=tap_target x=… y=…` within tolerance.

An optional **negative control** per command (e.g. tap empty space → no event) strengthens the
proof where cheap.

### 4.1 Two oracle classes (not every command emits an event)

The four-step model above is the **APP-event** specialization. Tier A has two oracle classes,
and ~8 commands are NOT observable via a fixture event:

- **APP** — fixture emits a `MAESTRO_FIXTURE` logcat line; §4 applies literally (pre: no event,
  post: exactly the event). Commands: `tap`, `longPress`, all `swipe`, `inputText`, `eraseText`,
  `pressKey`, `backPress`, `scrollVertical`, `hideKeyboard`, `setOrientation`, `openLink`,
  `launchApp`, `clearAppState`.
- **RET / PROBE** — no fixture event is possible (the command reads state or controls the
  process); the verdict is the driver's **return value** or a **device probe** (`pidof`, decoded
  image bytes). Adapted model: arrange → snapshot baseline value → act → assert the returned/probed
  value. Commands: `contentDescriptor`, `queryOnDeviceElements`, `isKeyboardVisible`,
  `takeScreenshot`, `stopApp`, `killApp`, `waitUntilScreenIsStatic`, `waitForAppToSettle`.
- **DUAL** — `isKeyboardVisible`, `hideKeyboard`, `clearAppState` are cross-checked with both a
  probe and a fixture event for a stronger proof.

> **Lifecycle exception (important):** the out-of-band channel is the app's own logcat — a
> *killed/stopped* app cannot emit anything. `stopApp`/`killApp` therefore MUST use a `pidof`
> probe, never a `MAESTRO_FIXTURE` line.

### 4.2 Tier A command catalogue

The model, made concrete for every Tier A command. Driver calls are verbatim from `Driver.kt`.
Oracle column tags APP / RET / PROBE per §4.1.

| Command | What it proves | Screen | Element id(s) | Driver call | Expected oracle | Pass criteria | Negative control |
|---|---|---|---|---|---|---|---|
| `tap` | point tap dispatches a click to the element under it | `TapScreen` | `tap_target` | `tap(centerOf("tap_target"))` | APP `{"event":"TAP","target":"tap_target","x":..,"y":..}` | exactly one TAP; target matches; x/y in bounds | tap `(5,5)` empty → no TAP |
| `longPress` | long-press is distinguished from tap (timing) | `TapScreen` | `longpress_target` | `longPress(centerOf("longpress_target"))` | APP `{"event":"LONG_PRESS","target":"longpress_target"}` | one LONG_PRESS; no preceding TAP | normal `tap` → TAP, not LONG_PRESS |
| `swipe(start,end,dur)` | point-to-point drag moves in the commanded vector | `SwipeScreen` | `swipe_surface` | `swipe(Point(540,1600),Point(540,400),300)` | APP `{"event":"SWIPE","dir":"UP","dy":-1180,...}` | dir==UP; sign(dy)<0; \|dy\| in band (§5.3) | start==end → no SWIPE |
| `swipe(dir,dur)` | screen-level directional swipe resolves to correct axis/sign | `SwipeScreen` | (screen, no element) | `swipe(SwipeDirection.LEFT,300)` | APP `{"event":"SWIPE","dir":"LEFT","dx":<0}` | dir==LEFT; sign(dx)<0 | `RIGHT` → dir==RIGHT, sign(dx)>0 |
| `swipe(elem,dir,dur)` | swipe anchored at element+direction starts on element | `SwipeScreen` | `swipe_surface` | `swipe(centerOf("swipe_surface"),SwipeDirection.UP,300)` | APP `{"event":"SWIPE","dir":"UP","target":"swipe_surface"}` | dir==UP; target matches; start in bounds | anchor on non-scroll elem → distinct target |
| `inputText` | typed text delivered verbatim to focused field | `InputScreen` | `text_field` | `inputText("Maestro 42!")` (after focus via route) | APP `{"event":"TEXT_CHANGED","text":"Maestro 42!"}` | final field text == sent (exact; unicode note §5.3) | no focused field → no TEXT_CHANGED |
| `eraseText` | N chars removed from field tail | `InputScreen` | `text_field` (seeded "ABCDE") | `eraseText(2)` | APP `{"event":"TEXT_CHANGED","text":"ABC"}` | text == original minus last N | `eraseText(0)` → unchanged |
| `pressKey` | a key code is delivered | `KeyboardScreen` | `text_field` focused | `pressKey(KeyCode.ENTER)` | APP `{"event":"KEY","code":"ENTER"}` | KEY with matching code | unconsumed key → no spurious TEXT_CHANGED |
| `backPress` | system Back delivered to app | `AppLifecycleScreen` (pushed sub-screen) | n/a | `backPress()` | APP `{"event":"BACK"}` + screen pops | BACK observed AND navigation pop | from root w/ no handler → app backgrounds, no BACK consumed |
| `scrollVertical` | default vertical scroll moves a scrollable surface | `ScrollScreen` | `scroll_container` | `scrollVertical()` | APP `{"event":"SCROLL","axis":"Y","toOffset":>0}` | toOffset > fromOffset | non-scrollable screen → offset unchanged |
| `contentDescriptor` | driver reads on-device tree, resolves known elements | `TreeScreen` | `tree_root`,`tree_label_a`,`tree_button_b` | `contentDescriptor(false)` | RET `TreeNode` contains the known ids | all ids present; bounds non-empty | keyboard open + `excludeKeyboardElements=true` → IME nodes absent |
| `queryOnDeviceElements` | on-device query resolves known element | `TreeScreen` | `tree_label_a` | `queryOnDeviceElements(query)` | RET non-empty `List<TreeNode>` w/ match | ≥1 node; id matches | query nonexistent id → empty list |
| `isKeyboardVisible` | driver detects soft-keyboard state | `KeyboardScreen` | `text_field` | `isKeyboardVisible()` | RET `true` (x-check APP `IME SHOWN`) | true when IME shown | no focus → returns false |
| `hideKeyboard` | driver dismisses the keyboard | `KeyboardScreen` | `text_field` | `hideKeyboard()` | APP `{"event":"IME","state":"HIDDEN"}` + probe false | IME HIDDEN AND probe false | no IME → no error, stays hidden |
| `launchApp` | fixture starts cold and reaches entry screen | (app root) | n/a | `launchApp(appId, {})` | APP `{"event":"LIFECYCLE","state":"LAUNCHED","seq":1}` | LAUNCHED with seq reset | bogus appId → driver error → cell error, not silent pass |
| `stopApp` | app moved to stopped state | app root | n/a | `stopApp(appId)` | PROBE `pidof` empty; stream silent after | process gone; oracle silent post-stop | stop already-stopped → no error |
| `killApp` | app process force-killed | app root | n/a | `killApp(appId)` | PROBE `pidof` empty | pid absent after kill | relaunch succeeds → clean kill |
| `clearAppState` | app data wiped | `StateScreen` | `state_seed_button` | seed → `stopApp` → `clearAppState(appId)` → relaunch | APP after relaunch `{"event":"STATE","seeded":false}` | relaunched app reports empty state | clear without seed → still empty (idempotent) |
| `setOrientation` | driver rotates device; app observes it | `OrientationScreen` | n/a | `setOrientation(LANDSCAPE_LEFT)` | APP `{"event":"ORIENTATION","value":"LANDSCAPE"}` | reported orientation == LANDSCAPE | set `PORTRAIT` → round-trip |
| `takeScreenshot` | driver captures a non-empty image of the screen | `TapScreen` | n/a | `takeScreenshot(sink,false)` | RET bytes decode to valid image of expected dims | decodes; size matches; (opt) marker pixel | 0-byte/all-black → fail (guards API-29 case) |
| `openLink` | a URL/deep link is dispatched and resolved | (deep-link entry) | n/a | `openLink("maestrofixture://deeplink/ok",appId,false,false)` | APP `{"event":"DEEPLINK","data":"...ok"}` | fixture receives intent w/ exact URI | unhandled scheme → no DEEPLINK, no crash |
| `waitUntilScreenIsStatic` | driver blocks until animation settles, then returns | `AnimationScreen` | `animate_button` | `waitUntilScreenIsStatic(5000)` | RET `true` (x-check APP `ANIM SETTLED`) | true after SETTLED, before timeout | infinite animation → returns false at timeout (no hang) |
| `waitForAppToSettle` | driver waits for hierarchy to stabilize, returns settled tree | `AnimationScreen` | n/a | `waitForAppToSettle(null,appId,5000)` | RET non-null stable `ViewHierarchy` | stable tree; two `contentDescriptor` calls equal after | never-settling screen → returns within timeout |

### 4.3 Minimal verification table (payload → attributes asserted)

What each command emits and the **specific attributes** the payload predicate checks (attribution
via `seq` watermark / value-delta is implied for every row). This is the load-bearing detail: the
attributes — not event presence — are what catch wrong coordinates, ignored arguments, and dropped
characters.

| Command | Oracle | Event / return | Attributes verified |
|---|---|---|---|
| `tap` | APP | `TOUCH` + `TAP` | `x,y == commanded`; `TAP.target == tap_target` |
| `longPress` | APP | `LONG_PRESS` | `target` matches; is long-press, not `TAP` |
| `swipe(start,end,dur)` | APP | `TOUCH` + `SWIPE` | start `x,y == commanded`; `dir`; `sign(dy)`; `\|dy\|` in band |
| `swipe(dir,dur)` | APP | `SWIPE` | `dir == requested`; `sign(dx/dy)` matches axis |
| `swipe(elem,dir,dur)` | APP | `SWIPE` | `target == swipe_surface`; start in bounds; `dir` |
| `inputText` | APP | `TEXT_CHANGED` | `text == sent` (exact; ASCII-only if `!isUnicodeInputSupported`) |
| `eraseText` | APP | `TEXT_CHANGED` | `text == original minus last N` |
| `pressKey` | APP | `KEY` | `code == requested KeyCode` |
| `backPress` | APP | `BACK` (+ nav) | `BACK` received; screen popped to parent |
| `scrollVertical` | APP | `SCROLL` | `axis == Y`; `toOffset > fromOffset` |
| `contentDescriptor` | RET | `TreeNode` | returned tree contains known ids; bounds non-empty |
| `queryOnDeviceElements` | RET | `List<TreeNode>` | non-empty; queried id present |
| `isKeyboardVisible` | RET+APP | `Boolean` (+`IME`) | `true` when IME shown, `false` otherwise |
| `hideKeyboard` | APP+probe | `IME` | `state == HIDDEN`; probe `isKeyboardVisible()==false` |
| `launchApp` | APP | `LIFECYCLE` | `state == LAUNCHED`; **`args == sent map`**; `seq` reset to 1 |
| `stopApp` | PROBE | `pidof` | pid present before → empty after; stream silent after |
| `killApp` | PROBE | `pidof` | pid empty after kill |
| `clearAppState` | APP | `STATE` (post-relaunch) | `seeded == false` after seed→clear→relaunch |
| `setOrientation` | APP | `ORIENTATION` | `value == requested` (LANDSCAPE/PORTRAIT); round-trips |
| `takeScreenshot` | RET | image bytes | decodes; `width,height == device`; not 0-byte/blank |
| `openLink` | APP | `DEEPLINK` | `data == url passed` |
| `waitUntilScreenIsStatic` | RET+APP | `Boolean` (+`ANIM`) | returns `true`; `ANIM SETTLED` before return; `false` at timeout if never settles |
| `waitForAppToSettle` | RET | `ViewHierarchy?` | non-null; two `contentDescriptor` calls equal afterward |

### Deferred (designed-for, not built in v1)
- **Tier B (device-state, system-probe oracles):** `setLocation`, `setPermissions`,
  `addMedia`, `setAirplaneMode`/`isAirplaneModeEnabled`, `setProxy`/`resetProxy`,
  `clearKeychain`, `setAndroidChromeDevToolsEnabled`.
- **Tier C (meta/hard-to-assert):** `deviceInfo`, `name`, `capabilities`, `open`/`close`,
  `isShutdown`, `isUnicodeInputSupported`, `startScreenRecording`.

---

## 5. Fixture Contract

A single spec every framework app implements, so the harness is framework-blind.

- **Screens:** one per command-group (`TapScreen`, `SwipeScreen`, `InputScreen`,
  `KeyboardScreen`, `ScrollScreen`, `OrientationScreen`, `AppLifecycleScreen`, …), reachable
  by a stable route.
- **Element IDs:** identical, stable identifiers (`tap_target`, `swipe_surface`,
  `text_field`) exposed via each framework's accessibility/testID mechanism so
  `contentDescriptor` / `queryOnDeviceElements` see them consistently. Because the tree-reading
  commands are the highest-risk (§5.2), the contract pins *how* each framework exposes the id:
  native `resource-id`/`contentDescription`; Compose `Modifier.testTag` + `semantics`; RN
  `testID`/`accessibilityLabel`; Flutter `Semantics(identifier/label)`; WebView DOM
  `id`/`aria-label`.
- **Report what was *received*, not just that something happened.** Each fixture must emit enough
  of the input it actually got to validate *correctness*, not mere occurrence (§4.3): raw `TOUCH`
  coordinates for gestures (a top-level pointer listener, independent of which widget handled the
  hit), echoed launch `args` for `launchApp`, the full resulting `text` for input, and
  `dir`+`dx`/`dy` for swipes. Presence-only events would pass a wrong-coordinate tap or an
  arguments-dropping launch; the received-payload fields are what fail them.
- **Event protocol (out-of-band channel):** every app logs one structured line per observed
  interaction to logcat under a fixed tag, with a monotonic `seq` to defeat races:
  ```
  MAESTRO_FIXTURE {"seq":12,"event":"SWIPE","dir":"UP","dx":2,"dy":-540,"target":"swipe_surface"}
  ```
  Channel per framework: Native/Compose → `Log.d`; Flutter → `debugPrint`; RN →
  `console.log`; WebView → `console.log` (surfaces in chromium logcat). The oracle filters by
  tag + seq.
- **Conformance self-test:** each fixture emits a distinct `{"event":"SELFTEST"}` line at
  startup, so a *broken fixture* fails loudly rather than masquerading as a driver bug. To keep
  this from polluting a command's red baseline, the per-command **pre-check filters by event
  type AND a `seq` watermark captured at `arrange`** — only events with `seq` greater than the
  watermark and matching the expected type count. Startup/`SELFTEST`/`LAUNCHED` lines are below
  the watermark and ignored.

Adding a 7th framework (or iOS later) = implement this contract. Nothing else changes.

### Frameworks (v1, Android)
Native Android, Jetpack Compose, React Native, Flutter, WebView-based.
(iOS SwiftUI / UIKit deferred behind the same contract + `IOSDriver`.)

### 5.1 Screen inventory (every framework implements every row, identically)

| Screen | Serves commands | Key element ids | Position/state oracle |
|---|---|---|---|
| `TapScreen` | `tap`, `longPress`, `takeScreenshot` | `tap_target`, `longpress_target` | `TAP` / `LONG_PRESS` |
| `SwipeScreen` | all `swipe` | `swipe_surface` | `SWIPE` (dir/dx/dy) |
| `ScrollScreen` | `scrollVertical` | `scroll_container` | `SCROLL` (from/toOffset) |
| `InputScreen` | `inputText`, `eraseText` | `text_field` | `TEXT_CHANGED` (full text) |
| `KeyboardScreen` | `isKeyboardVisible`, `hideKeyboard`, `pressKey` | `text_field` | `IME` SHOWN/HIDDEN, `KEY` |
| `TreeScreen` | `contentDescriptor`, `queryOnDeviceElements` | `tree_root`, `tree_label_a`, `tree_button_b` | static tree (RET oracle, no event) |
| `OrientationScreen` | `setOrientation` | — | `ORIENTATION` PORTRAIT/LANDSCAPE |
| `AnimationScreen` | `waitUntilScreenIsStatic`, `waitForAppToSettle` | `animate_button` | `ANIM` RUNNING/SETTLED |
| `AppLifecycleScreen` | `backPress`, `launchApp`, `stopApp`, `killApp`, `clearAppState`, `openLink` | `state_seed_button` | `LIFECYCLE`, `STATE`, `BACK`, `DEEPLINK` |

> `scrollVertical()` and `swipe(SwipeDirection,…)` take **no element** — their screen exposes a
> **scroll-offset oracle** (`fromOffset`/`toOffset`), not a tap target.

### 5.2 Applicability: which commands actually vary by framework

**Write-once premise.** A `CommandBehavior` is written **once**, framework-agnostically: it routes
to a screen name, resolves an element by its contract id, calls one `Driver` method, and asserts a
`MAESTRO_FIXTURE` event (or a return/probe). It contains **no framework branches**. The only thing
that differs between Compose, Flutter, RN, WebView, and native is **which fixture APK/bundle the
cell installed**. `SwipeBehavior` is the same class whether the cell is `api34-flutter` or
`api30-webview`; it never imports anything framework-specific.

But not every command is *informative* on every framework, so the matrix spends device-time where
regressions hide:

- **Framework-sensitive (must run on ALL frameworks):** behavior depends on the framework's
  view/accessibility tree, focus model, or gesture physics — `tap`, `longPress`, all `swipe`,
  `inputText`, `eraseText`, `scrollVertical`, **`contentDescriptor`** and `queryOnDeviceElements`
  (highest risk — the a11y/semantics tree is *entirely* framework-defined), `waitUntilScreenIsStatic`,
  `waitForAppToSettle`. **This is the whole point of the harness.**
- **Mixed (a few frameworks):** `isKeyboardVisible`, `hideKeyboard`, `pressKey`, `backPress`,
  `openLink` — mostly OS-level, but focus/routing/back-handling differ enough to spot-check
  (include WebView + a back-handler framework like Flutter/RN).
- **Device-level (does NOT vary — running on all 6 is waste):** `setOrientation`, `takeScreenshot`,
  `stopApp`, `killApp`, `clearAppState` — act below the rendering layer; framework only affects how
  the fixture *reports*. Run on native + one spot-check.

Each `CommandBehavior` declares a `coverage: framework-sensitive | mixed | device-level` field so
the runner auto-skips redundant cells. This **amends §7's flat cross-product**: framework-sensitive
commands run in every cell; device-level commands run in a reduced framework set (default: native +
1), overridable with `--full-matrix`.

### 5.3 The same test across frameworks: identical vs. legitimately different

Worked example — `swipe` across all 6 frameworks.

**Identical everywhere:** the screen (`SwipeScreen`) + element (`swipe_surface`); the call
`driver.swipe(Point(540,1600),Point(540,400),300)`; the assertion *shape* — one `SWIPE` with
`dir==UP`, `sign(dy)<0`, `target=="swipe_surface"`.

**Legitimately different (NOT failures):**
- **Reported `dy` magnitude.** The gesture commands ~1200px, but the fixture reports scroll
  *offset*, which includes fling/overscroll. Compose/Flutter/RN/WebView have different ballistics —
  `dy=-1180` on one and `dy=-1600` on another are **both PASS**.
- **Coordinate mapping.** Density/inset handling differs; the *start point* must land inside the
  resolved element bounds, but exact pixels differ per framework.
- **Focus side-effects (input commands).** WebView/RN may need an explicit focus step native does
  not; the behavior handles this generically, IME timing differs.

**Tolerance policy.** Direction/sign assertions are **framework-invariant** (a wrong-direction swipe
is always a bug). Magnitude assertions use a **band, not equality**, and the band MAY be
parameterized per framework physics class (`clamped | momentum`) declared on the
screen/behavior — not hidden in `command.json`. Both the band and the framework class are recorded
in `command.json` so a reviewer can see *why* `dy=-1600` passed on Flutter but would fail on native.
**Unicode:** `inputText` branches its expected string on `isUnicodeInputSupported()` — ASCII-only
payload when false — to avoid false reds where unicode injection isn't supported.

---

## 6. Provisioning & "ensure env" (host-independent)

Hard split between *what runs the tests* and *what gives me a device*, connected only by an
adb serial. Harness core asks the world for one thing: a serial reachable over adb.

- **Preflight (fail fast, actionable):** adb present, JDK ok, SDK + required system images
  installed (offer `sdkmanager` install), hardware accel available (KVM/HVF), disk space.
- **`FreshAvdProvider`:**
  - `avdmanager create avd -n maestro-conformance-api{N} -k "system-images;android-{N};google_apis;<abi>" --device pixel_6 --force`
  - boot: `emulator -avd … -no-snapshot-save -no-window -no-boot-anim -no-audio -no-metrics`
  - wait-for-ready: `adb wait-for-device` → `getprop sys.boot_completed == 1` →
    `settings list global` and `pm get-max-users` exit 0
  - on release: wipe/delete the AVD.
  - Clean state comes from a *freshly created* AVD (pristine userdata) — no golden snapshot
    needed. This is the deliberate trade vs. maestro-device: we give up warm-boot speed to
    avoid the device-side configurator/snapshot dependency.
- **Explicit BYO:** `--device <serial>` / `ANDROID_SERIAL` runs there and prints a loud
  banner in console + report header:
  `⚠ user-supplied device <serial> — state not managed by harness`.
  The matrix never auto-adopts a random running emulator.
- **Host-independence:** stock Android SDK only → identical on laptop, CI, anyone's machine.
  Same single entrypoint everywhere.

### DeviceProvider interface

```kotlin
interface DeviceProvider {
    fun acquire(spec: DeviceSpec): DeviceHandle   // serial + booted AndroidDriver, ready
    fun release(handle: DeviceHandle)             // teardown / wipe / return to pool
}
```

---

## 7. Matrix selection & CLI

Invoked via a Gradle task on the `conformance` source set (not a standalone module binary):

```
./gradlew :maestro-client:driverConformance \
  --api 25,26,27,28,29,30,31 --framework flutter,react-native \
  [--command tap,swipe]          # default: all Tier A
  [--device <serial>]            # BYO override, skips provisioning
  [--record all|on-failure|never]
  [--out ./report]
```

- The task wraps a runnable entrypoint (Clikt-style arg parsing) — **not** JUnit, so the
  suite is never swept up by `./gradlew test`.
- `--api` accepts lists and ranges (`24..36`); `--framework all`; cross product → **cells**.
- Each cell = (api, framework). **Framework-sensitive** commands run in every selected cell;
  **device-level** commands run in a reduced framework set (default: native + 1) per their
  `coverage` class (§5.2). `--full-matrix` forces every command into every cell.
- Cells are independent → parallelizable later; v1 may run sequentially per device.
- Supported API range: **24–36**.

---

## 8. Reporting & Artifacts

Mirrors maestro-device's shape (per-run dirs + JSON + HTML aggregator + `file://`-openable
`.js` mirror), with **command** in place of red/green side and **cell (api×framework)** in
place of (step×api).

### Artifact tree

```
report/
├── index.html                 # aggregator viewer: grid rows=command, cols=api×framework
├── summary.json               # machine-readable roll-up: totals, per-cell status, env banner, durations
├── summary.js                 # report.js-style mirror so index.html opens over file://
└── cells/
    └── api34-compose/                    # one dir per cell (api × framework)
        ├── cell.json                     # per-command pass/fail rollup for the cell
        ├── env.log                       # serial, api, abi, device profile, accel, fixture build id, BYO banner
        ├── fixture-install.log           # apk/bundle install output
        ├── maestro.log                   # driver-level log for the whole cell
        └── tap/                          # one dir per command (the "unit")
            ├── command.json              # THE evidence record (see below)
            ├── events.log                # raw out-of-band oracle stream (always)
            ├── after.png                 # end-state still (always)
            ├── recording.mp4             # gesture replay (failure bundle / --record all)
            ├── before.png                # paired diff still (failure bundle / --record all)
            ├── hierarchy.json            # contentDescriptor dump (failure bundle / --record all)
            └── logcat-slice.txt          # raw unfiltered logcat for the test window (failure bundle)
```

### `command.json` — the spine

The one artifact that turns raw evidence into a machine-readable verdict. Drives the HTML grid
and the CI exit code; enables cross-matrix diffing and reproduction. The `oracle` block is a
**tagged union** (`APP_EVENT` | `RETURN_VALUE` | `DEVICE_PROBE`) so the same spine covers both
oracle classes from §4.1 — event-emitting and return/probe commands alike.

```json
{
  "command": "swipe",
  "coverage": "framework-sensitive",
  "args": { "start": [540,1600], "end": [540,400], "durationMs": 300 },
  "target": { "id": "swipe_surface", "resolvedBounds": [40,300,1040,1600] },
  "oracle": {
    "kind": "APP_EVENT",
    "expected": { "event": "SWIPE", "dir": "UP", "dyBand": [-1600, -1000], "physics": "momentum" },
    "actual":   { "event": "SWIPE", "dir": "UP", "dy": -1180 }
  },
  "verdict": "PASS",
  "failureReason": null,
  "timings": { "actMs": 312, "totalMs": 940 },
  "artifacts": ["events.log", "after.png"]
}
```

A RET command looks like:
```json
{ "command": "contentDescriptor",
  "oracle": { "kind": "RETURN_VALUE",
              "expected": { "containsIds": ["tree_root","tree_label_a"] },
              "actual":   { "foundIds": ["tree_root","tree_label_a","tree_button_b"] } },
  "verdict": "PASS" }
```

### Artifact policy (tiered by cost vs. use)

- **Always (cheap, every run):** `command.json`, `events.log`, `after.png`.
- **Failure bundle (on fail, or promoted by `--record all`):** `recording.mp4`, `before.png`,
  `hierarchy.json`, `logcat-slice.txt`.
- **`--record on-failure`** is the lean CI default; **`--record all`** keeps the full bundle
  for green runs too (full green+red parity); **`--record never`** disables media.
- Rationale: never capture video + hierarchy for every passing command — it triples disk and
  wall-clock on the 95% case nobody opens, and would make a 13-API × 6-framework matrix crawl.

### Screen recording specifics

- Per-command short clips via `adb shell screenrecord` around the `act` step (start → before
  → act → after → SIGINT to flush → pull). Per-command (not one-per-cell) because
  `screenrecord` has a **180s cap on API 29–32**; a full cell could exceed it, and the only
  way maestro-device dodges this is by patching the device-side binary — a dependency we
  reject for host-independence.
- **Recording never fails a test** — it is a diagnostic artifact; the verdict rests on the
  out-of-band oracle. On recording error the artifact is marked unavailable.
- **Known caveats handled gracefully:** API 29 on Apple-Silicon emulators can emit
  0-byte/broken output (detected → marked unavailable); high-DPI AVDs (pixel_6 long edge
  2400 > 1920 AVC cap) get `--size` capped ≤1920 long-edge so clips aren't silently squashed
  (recording arg only, no binary patch).

---

## 9. Execution model & CI

### Where it lives & how it's wired
- Code: `maestro-client/src/conformance/kotlin` (dedicated `conformance` source set), reusing
  `maestro-client`'s `main` (so `AndroidDriver` is on the classpath without a new module).
- Gradle: a `driverConformance` task with `JavaExec`-style execution of the runnable
  entrypoint. **It is deliberately NOT a dependency of `test`, `check`, or `build`** — running
  unit tests must never trigger a device-backed conformance run.
- A separate `conformanceCompile`/source-set check may compile the code, but execution is
  always explicit via the task.

### Isolation from unit-test CI
- The existing unit-test workflow (`test.yaml`) runs `./gradlew test` and **must not** pick up
  conformance. Because conformance is its own source set + task outside `check`, this holds by
  construction.
- Conformance has its own GitHub Actions workflow, e.g. `.github/workflows/driver-conformance.yaml`.

### Trigger (v1 = on-demand)
- `workflow_dispatch` only, mirroring `test-e2e.yaml`. Manual inputs map to the CLI flags:
  `api` (e.g. `34` or `24..36`), `framework` (e.g. `flutter,compose` or `all`),
  `command` (optional), `record` (`all|on-failure|never`).
- Runs on a runner with the Android SDK + emulator acceleration; provisions fresh AVDs via the
  `FreshAvdProvider` (§6).
- **Future (not v1):** add `pull_request` / `push` path filters so changes under the driver or
  fixture paths (e.g. `maestro-client/src/.../drivers/AndroidDriver.kt`,
  `maestro-android/**`, fixture dirs) auto-trigger a targeted subset.

### Artifacts in CI
- The `report/` tree (§8) is uploaded as a workflow artifact; `index.html` is the entry point
  for "what passed / what didn't," each red cell drilling into per-command evidence.

## 10. Phasing

1. **Skeleton + 1 framework + 3 commands** (native Android; `tap`, `inputText`,
   `swipe(start,end,durationMs)` — the most primitive overload) on one API → proves
   DeviceProvider + LogcatEventReader + Reporter end-to-end, across both oracle classes
   (`tap`/`inputText` = APP, plus one RET command such as `contentDescriptor` recommended early).
2. **All Tier A commands** on native Android, single API.
3. **Add fixtures**: Compose → React Native → Flutter → WebView, each satisfying the contract.
4. **Matrix out** to API 24–36; HTML aggregator.
5. **Design-in hooks** for Tier B (system-probe oracles) and iOS (`IOSDriver` behind the same
   `DeviceProvider` / contract).

---

## 11. Resolved & open questions

### Resolved
- **Placement:** inside `maestro-client` (`conformance` source set), no new module.
- **Entrypoint:** runnable Clikt-style CLI via a Gradle task — not JUnit — so it stays out of
  `./gradlew test`.
- **CI trigger:** on-demand `workflow_dispatch` in its own workflow, isolated from unit-test CI.

### Still open
- **Parallelism in v1:** sequential per device first, or build cell-level parallelism in from
  the start?
- **File-change targeting (future):** exact path globs that should auto-trigger a targeted run.
