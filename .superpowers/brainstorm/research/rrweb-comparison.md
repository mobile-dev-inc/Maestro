# rrweb-style UI capture vs. Maestro's span/event trace — an honest verdict

> Evaluating the idea: rearchitect Maestro around an *rrweb-style* capture of the
> full, mutating UI hierarchy during an interaction sequence, as either a
> complement to — or a replacement for — the planned span/event trace
> (Dimension 4 of `worked-example-core-contract.md`).

**TL;DR verdict:** **(b) with a strong (c) caveat.** rrweb-style capture is *not*
redundant with the trace and *not* a good architectural foundation for mobile. It
is a **valuable OPTIONAL session-recording layer** that attaches as artifacts when
explicitly enabled — but only on platforms/modes where continuous tree capture is
cheap (mainly Android via the accessibility event stream, or any gray-box build).
On black-box iOS it degrades to expensive polling and should default to
**screenshot/video + the existing snapshot-at-command-boundary**, not a faithful
mutation stream. Making rrweb-style capture the *default* or the *core* would
violate the "low runtime overhead, black-box default" goal. Slotting it in as an
opt-in layer over the trace satisfies every goal without bloating the default path.

---

## 1. How rrweb actually works (and why it's clever)

rrweb ("**r**ecord and **r**eplay the **web**") does **not** record video or
pixels. It records a *reconstructable description of the DOM over time* and
**replays** it by rebuilding a live DOM in an iframe. Three pillars:

1. **One initial full snapshot.** On `record()` it serializes the entire DOM into a
   plain-JSON tree where every node gets a stable integer `id`
   (`FullSnapshot` / `EventType.FullSnapshot`). This is rrweb's `snapshot()`
   primitive (the `rrweb-snapshot` package): element/text/comment/document nodes
   with attributes, captured CSS, etc. The `id` is the linchpin — every later event
   refers to nodes *by id*, never by re-serializing them.

2. **A stream of incremental events** (`EventType.IncrementalSnapshot`), each tagged
   with an `IncrementalSource`. The important ones:
   - `Mutation` — DOM adds/removes/attribute/text changes, captured via a
     **`MutationObserver`**. This is the heart of rrweb and is essentially *free*
     because the browser hands rrweb a batched diff of exactly what changed.
   - `MouseMove` / `TouchMove` — pointer paths, **sampled/throttled** (default
     ~50ms / "mousemove sampling") to keep volume down.
   - `MouseInteraction` (click, focus, blur, etc.), `Scroll`, `ViewportResize`,
     `Input` (value changes), `MediaInteraction` (play/pause/seek),
     `StyleSheetRule` (dynamic CSSOM edits), `Font`, and `CanvasMutation`
     (canvas is special — see below).
   - Each event carries a `timestamp`, so the stream is **seekable**: a player can
     fast-forward/rewind to any moment and the DOM is reconstructed exactly.

3. **Optional periodic re-snapshot** — `checkoutEveryNms` / `checkoutEveryNth`
   inserts a fresh full snapshot occasionally so a player can start mid-stream
   without replaying from t=0 (the "checkout" concept). This is the *only* place
   rrweb pays the full-snapshot cost again.

**Replay is DOM rebuild, not playback.** `rrweb-player` takes the snapshot,
rebuilds the tree (`rebuild()` in `rrweb-snapshot`), then applies the incremental
events in time order. Because the result is a *real DOM*, you can pause and
**inspect any element at any point in time** with devtools-like fidelity — this is
the single biggest advantage over video.

Confirmed event schema (from `@rrweb/types`, `packages/types/src/index.ts`):
- `EventType` = `DomContentLoaded`, `Load`, `FullSnapshot`, `IncrementalSnapshot`,
  `Meta`, `Custom` (a TypeScript numeric enum).
- `IncrementalSource` = `Mutation`, `MouseMove`, `MouseInteraction`, `Scroll`,
  `ViewportResize`, `Input`, `TouchMove`, `MediaInteraction`, `StyleSheetRule`,
  `CanvasMutation`, `Font`, `Log`, `Drag`, `StyleDeclaration`, `Selection`,
  `AdoptedStyleSheet`.
- A `FullSnapshot` event carries a serialized node tree + `initialOffset`; an
  `IncrementalSnapshot` carries `incrementalData` tagged by `IncrementalSource`.

A subtle but important detail confirmed in rrweb's `observer.md`: because
`MutationObserver` is **batched and asynchronous** (one callback per burst of
changes), rrweb *coalesces* — it records only the **final value** of a node's
attribute within a single callback (e.g. a textarea drag-resize that fires hundreds
of intermediate width/height records collapses to the last value). That's a
deliberate fidelity-for-size tradeoff, and it's only possible *because* the browser
hands you a precise, batched node-level diff for free. (Mobile has no such diff —
Section 3.)

**Why it's powerful:**
- **Inspectable at any point**: you get the live tree, computed structure, text,
  and attributes at any timestamp — not just pixels.
- **Tiny vs. video, and it compresses well**: a session is text/JSON deltas. rrweb
  ships a **`pack`/`unpack`** option (fflate-based) on individual events, and the
  docs note events "compress extremely well with gzip or brotli" (recommend
  whole-session backend compression for best ratio). Storage-optimization levers:
  block high-event subtrees (long lists, SVGs, animations, canvas), **sample**
  (disable mousemove, throttle scroll e.g. 150ms min interval, record only final
  input values), and `slimDOMOptions`.
- **Privacy without pixels**: from the guide — `.rr-block` ("replay as a
  placeholder"), `.rr-ignore` (don't record input events), `.rr-mask` (mask text),
  `input[type='password']` masked by default, plus `maskAllInputs`,
  `maskInputOptions`, `maskTextFn`. Redaction happens *at capture time* before bytes
  leave the page — you can ship a faithful structural replay with all PII stripped.

**Costs / limits — confirmed, including real numbers:**
- **Canvas/WebGL** is *not* recorded by default; enabling it
  (`recordCanvas: true`, `sampling: { canvas: 15 }` FPS,
  `dataURLOptions:{type:'image/webp',quality}`) captures periodic image frames —
  the one place rrweb goes "video-like" and gets expensive; `preserveDrawingBuffer`
  (default on) "has some negative performance implications." A WebRTC canvas plugin
  exists precisely because frame-by-frame serialization of WebGL is costly.
- **Cross-origin iframes**, some media, shadow-DOM edge cases can't be perfectly
  reconstructed. Setting input/textarea/select values *programmatically* doesn't
  trigger `MutationObserver`, so rrweb needs extra event listeners as a workaround.
- **High-churn DOMs are the documented Achilles' heel.** "When there is a large
  number of mutations (10k+), the page gets stuck"; benchmark write-ups cite a real
  PostHog customer whose **page processing time went from 2.5s to 35s** with replay
  on, and animation-heavy / rapidly-mutating DOMs are explicitly called out as worse
  cases. The cost is in **observation + serialization**, not transmission.
- **The whole model assumes a cheap, push-based mutation feed** — the
  `MutationObserver`, described in benchmarks as "a high-performance web API…the
  more efficient successor to DOM Mutation Events." *This assumption does not hold
  on mobile.* (Section 3.) Industry confirmation: "**rrweb for mobile doesn't
  exist**… web session replay largely relies on a single open-source library
  (rrweb)" — vendors had to build entirely separate native implementations.

**Sources (verified this session):** rrweb repo `https://github.com/rrweb-io/rrweb`
(README confirms snapshot→serialize-with-unique-id, record-all-DOM-mutations,
rebuild-not-video); `EventType`/`IncrementalSource` from
`packages/types/src/index.ts`; coalescing/observer design in `docs/observer.md`;
masking from `guide.md`; canvas options from `docs/recipes/canvas.md`; storage levers
from `docs/recipes/optimize-storage`; performance numbers from the highlight.io and
PostHog session-recording benchmarks and rrweb issues #744/#1447/#1820.

---

## 2. What our trace already gives us (Dimension 4 recap)

From `worked-example-core-contract.md`:

- An **append-only NDJSON stream of span events** nesting
  `run → flow → command → retry → deviceAction/wait`, identical on the wire and on
  disk (one serialization path, no drift).
- `SpanEvent` point-in-time markers (poll ticks, `device.idle` pushes,
  actionability transitions) — i.e. a **semantic timeline of *why* the runner did
  what it did**.
- `ArtifactRef`s to **observation** and **screenshot** artifacts captured at
  command boundaries (e.g. `obs_4f1c`, `shot_4f1c` on the failed assertion).
- A typed `Element` model and `observe()`/`snapshot()` primitives — observation is
  **cheap, targeted, on-demand**, explicitly *"not a full view-hierarchy dump
  diffed on a timer."* (That line is, in effect, a deliberate rejection of the
  rrweb default.)

So today the trace is **command-granular and semantic**: it tells you the runner's
intent, sync logic, retries, and the state *at decision points*, with screenshots
for pixels. What it does **not** give you is a **continuous, inspectable
between-commands timeline of the UI tree** — e.g. the exact frame-by-frame mutations
while a spinner spun, an animation played, or a banner faded in over 400ms.

---

## 3. "rrweb for mobile": the feasibility crux

To get faithful rrweb-style replay on mobile you need, continuously:
**(a) an initial full tree snapshot, (b) every subsequent mutation, (c) every input
event, time-stamped and seekable.** (a) and (c) are tractable. **(b) is the
problem**, and it's a different problem on each platform.

### The web's unfair advantage
`MutationObserver` is a **browser-native, push-based, batched diff** of exactly
what changed in the DOM — near-zero cost, no polling. rrweb's entire economy of
scale rests on it. **Neither mobile platform has an equivalent for the full UI
tree.**

### Android — *partially* feasible (push-ish, lossy)
Android exposes an `AccessibilityService` with an **`AccessibilityEvent` stream**,
notably `TYPE_WINDOW_CONTENT_CHANGED` (Android docs: "change in the content of a
window… adding/removing view, changing a view size, etc."), plus
`WINDOW_STATE_CHANGED`, `VIEW_SCROLLED`, focus/click events. This is the closest
thing to a `MutationObserver`. But it is a **weak substitute**:
- The event tells you *that* a window's content changed (with a coarse
  `getContentChangeTypes()` scope), **not a precise node-level diff** like
  `MutationObserver`. To learn *what* changed you must re-walk the relevant subtree
  via `AccessibilityNodeInfo` / `getRootInActiveWindow()`. Worse, the Android docs
  note that if a service hasn't requested window content the event **won't even carry
  a source reference** — so you're forced into the snapshot path.
- **Walking the tree is expensive.** Android's own docs flag accessibility node
  retrieval as "**generally expensive to retrieve and should only be requested when
  needed**," and that "creating a complete snapshot of a view tree can be
  resource-intensive, especially with complex layouts" — node access is IPC across
  the app→system boundary; the standard guidance is to **cache** rather than
  repeatedly call `getRootInActiveWindow()`. It also reflects a *settled* tree, not
  every transient animation frame. Doing a re-walk on every `CONTENT_CHANGED` during
  an animation is a CPU/IPC hotspot that will perturb timing on the device under
  test.
- Events are **throttled/coalesced by the framework** and can be **dropped** under
  load; rapidly-animating or `WebView`/Compose surfaces emit floods or, worse,
  under-report. So the "mutation stream" is **lossy and coarse**, not faithful.

**Net:** On Android you *can* build an approximate rrweb-style layer (snapshot +
on-`CONTENT_CHANGED` re-walk + input events) — but it's **snapshot-diffed, not
true-mutation**, has real overhead, and is best **sampled/debounced**, which means
it's "rrweb-ish," not rrweb-faithful.

### iOS — essentially infeasible black-box (poll-only)
Black-box iOS has **no continuous UI-mutation feed at all**. Options:
- **XCUITest accessibility snapshots** (the element-tree query that Maestro's iOS
  driver, like Appium/WebDriverAgent, already uses) are **notoriously slow and a
  documented bottleneck**. Per the Appium XCUITest "WDA slowness" docs: "to retrieve
  the page source, WDA needs to take a snapshot of the whole accessibility hierarchy
  with all element attributes resolved, which is a **time-expensive operation**,"
  and it "**makes the test process pretty much slow**." Real reports show
  `getPageSource` / "Requesting snapshot of accessibility hierarchy" **getting stuck
  for long times** and big slowness on swiping. Mitigations are all about *doing it
  less*: `snapshotMaxDepth`, `snapshotMaxChildren`, excluding expensive attributes.
  Polling that at any rate approaching "faithful mutation capture" is a non-starter:
  it would dominate runtime and badly distort the app's timing — the exact opposite
  of rrweb's near-free `MutationObserver`.
- `UIAccessibility`/`AX` notifications exist (e.g. `LayoutChanged`,
  `ScreenChanged`) but are **sparse, app-emitted, and screen-reader-oriented** —
  nowhere near a per-node mutation diff, and you'd be at the app's mercy to emit
  them.
- The only way to get a cheap, faithful iOS mutation feed is **gray-box / in-process
  hooks** (a linked helper that observes `UIView`/`CALayer`/SwiftUI updates from
  inside the app) — which **breaks the black-box default**, requires instrumenting
  the build, and is a large, fragile surface (UIKit + SwiftUI + RN + Flutter each
  need their own hooks).

**Net:** Faithful rrweb-style capture on **black-box iOS is not feasible**; it
degrades to expensive polling (worse than video) or requires gray-box
instrumentation (violates a core goal). This is the strongest single argument
against making rrweb-style capture a *core/default* mechanism.

### What the industry actually does (strong corroborating evidence)
Mobile session-replay vendors **deliberately did not port rrweb's mutation-stream
model to native mobile** — confirmed directly: *"rrweb for mobile doesn't exist…
mobile session replay solutions require separate implementations for different
platforms."* PostHog's own engineering write-up ("How we built mobile replay (and
why it took so long)") describes the actual native approach:
- **Grab the view-hierarchy state when the screen is drawn**, transform it to JSON,
  and later render it as an **HTML wireframe** — *wireframe mode is the default*; an
  optional **screenshot mode** captures real pixels instead.
- It is **snapshot-per-frame, not mutation-stream**: there is a **throttle delay
  (default 1000ms)** between captures, screenshots are taken **only on interaction**,
  compressed hard (JPEGs at ~30% quality, ~20KB each). Android uses `PixelCopy`/
  `Canvas` to grab the screen + view hierarchy *in the same frame*, then uses the
  hierarchy only to **locate and mask** controls.
- They explicitly had to build **separate SDKs** for iOS, Android, RN, and Flutter,
  and call out that **Jetpack Compose vs. view-based** and **SwiftUI vs. UIKit**
  each need different replay approaches — i.e. the gray-box surface is large and
  fragmented.

That a sophisticated vendor, *which itself sponsors and uses rrweb on web*,
converged on **throttled snapshots/wireframes** rather than a mutation stream for
native mobile is the single strongest external confirmation that the rrweb default
does not transplant. rrweb-on-mobile survives in practice only inside **WebViews /
RN web contexts**, where a real DOM (and thus `MutationObserver`) exists.

**Sources:** Android `AccessibilityEvent`/`TYPE_WINDOW_CONTENT_CHANGED`/
`AccessibilityNodeInfo`/`AccessibilityService`/`getRootInActiveWindow()` — Android
developer docs (`https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent`,
`.../accessibilityservice/AccessibilityService`); Apple `UIAccessibility`
notifications + XCUITest snapshot latency — Appium XCUITest driver "WDA slowness"
docs (`https://appium.github.io/appium-xcuitest-driver/.../troubleshooting/wda-slowness/`)
and appium/appium #16199; PostHog mobile replay — blog
`https://posthog.com/blog/mobile-session-replay` and docs
`https://posthog.com/docs/session-replay/mobile`.

---

## 4. What rrweb-style adds over our trace — and what it costs

| Dimension | Our trace + snapshots + screenshots/video | rrweb-style mutation capture |
|---|---|---|
| **Granularity** | Command boundaries + semantic SpanEvents | Continuous, every mutation between commands |
| **Inspectability** | Structured Element snapshot *at decision points* | Inspectable tree at **any** timestamp |
| **Semantics (why)** | Rich — spans encode intent, sync, retries, error category | None — it's *what changed*, not *why the runner acted* |
| **Replay** | Screenshots/video + snapshot scrub | DOM-rebuild scrub (web) / wireframe scrub (mobile, best case) |
| **Capture cost (web)** | n/a | ~free (`MutationObserver`) |
| **Capture cost (Android)** | cheap (on-demand) | moderate–high (event-driven re-walk, lossy) |
| **Capture cost (iOS black-box)** | cheap (on-demand) | **prohibitive** (polling) or gray-box only |
| **Size** | tiny NDJSON + a few artifacts | small vs video, but >> our trace; grows with churn |

**What it genuinely adds:** a *continuous inspectable structural timeline between
command boundaries.* That is real value for **post-hoc eval/debugging** of flaky
or animation-sensitive flows ("show me the tree at the exact 380ms the banner
appeared, before the next command ran"). Our current trace gives you the tree
*at* the failed assert, plus poll ticks — but not the in-between frames as a
walkable tree.

**What it costs:** runtime overhead on-device (the thing we're explicitly trying to
minimize), a *second* capture/serialization path that must stay consistent with the
span stream, larger artifacts, and — critically — **it does not subsume the trace**.
The span tree carries *semantics* (intent, sync source, retry, error category)
that a pure UI-mutation stream fundamentally cannot represent. So it can only ever
**augment**, never replace.

---

## 5. Verdict against the goals

Goals: *agent self-correction, AI eval scoring, cloud run replay, low runtime
overhead, black-box default.*

- **Agent self-correction:** No benefit. Self-correction is driven by *structured
  errors + `candidates` at the decision point* (Dimension 3), which the trace
  already delivers. A continuous mutation stream is too low-level and too late for
  the in-loop agent. **Trace wins.**
- **AI eval scoring:** *Some* benefit for nuanced post-hoc scoring (timing of
  transitions, did-the-spinner-resolve, animation correctness) — but a screenshot/
  video + the snapshot-at-boundary already covers most eval needs. Marginal.
- **Cloud run replay:** **This is the real win.** A seekable, inspectable timeline
  is excellent for cloud post-mortem. But on mobile the *faithful* version is only
  achievable on Android/gray-box; the portable version is throttled screenshots/
  wireframes — which is closer to "richer artifacts" than "rrweb."
- **Low runtime overhead:** rrweb-style capture **directly conflicts** with this on
  mobile (re-walks on Android, polling on iOS). Must be **off by default.**
- **Black-box default:** Faithful capture on iOS needs gray-box hooks → **conflicts**
  unless restricted to opt-in/gray-box/Android.

**Conclusion: not redundant, not a core/default mechanism, but a valuable *optional*
session-recording layer.** Specifically:
- **(a) redundant?** No — it captures something the trace doesn't (between-command
  structural frames). But it also can't replace the trace's semantics.
- **(b) valuable optional layer?** **Yes — this is the answer**, scoped to when it's
  cheap (Android, gray-box, or WebView contexts) and otherwise degrading to
  throttled screenshots/video.
- **(c) bad idea on mobile?** **As a *default/core* mechanism, yes** — especially on
  black-box iOS. The provocative "rearchitect around rrweb" framing should be
  **rejected**; the watered-down "optional artifact layer" framing should be
  **accepted.**

---

## 6. How it slots in without bloating the default path

Keep the span/event trace as the **single source of truth and the only thing on
the default path.** Add rrweb-style capture as an **opt-in artifact producer that
hangs off the existing artifact/`ArtifactRef` mechanism** — never as a new
first-class dimension.

1. **Opt-in capability, off by default.**
   `session.capabilities()` already negotiates features. Add
   `uiTimeline: false` by default; enable via
   `connect({ capture: { uiTimeline: "auto" | "off" | "snapshots" } })`.
   `"auto"` = faithful where cheap (Android event-driven / WebView / gray-box),
   throttled-screenshots fallback elsewhere; never poll the iOS AX tree at high
   rate.

2. **Reuse the artifact channel, not a new stream.** The capture writes its own
   self-contained recording artifact (rrweb-format JSON where a real DOM exists in
   WebViews; a Maestro "tree-delta" NDJSON elsewhere). The **span stream just
   references it** with the existing `ArtifactRef` event:
   `{ type: "ArtifactRef", spanId, ref, kind: "uiTimeline" }` (extend the `kind`
   union with `"uiTimeline"`). No second control-plane serialization path; the
   trace stays the spine and the recording is a leaf artifact, exactly like
   screenshots today.

3. **Anchor the recording to spans by timestamp + spanId.** Because both the recording
   events and `SpanEvent`s carry epoch-ms timestamps, the player can scrub the
   UI timeline *and* highlight which command/span was active — turning the optional
   layer into a visual overlay on the existing trace tree (great for cloud replay /
   Studio) without coupling the formats.

4. **Adopt rrweb's cost-control playbook verbatim** (it's the part that *does*
   transplant): full snapshot + deltas, **sampling/debounce** of high-churn
   surfaces, periodic `checkout` snapshots for mid-stream seeking, privacy
   **masking at capture time** (mask text/inputs before bytes leave the device),
   and `pack`-style compression. On Android, debounce `CONTENT_CHANGED`-driven
   re-walks (e.g. coalesce within an animation frame budget) and cap tree depth.

5. **Platform-honest defaults.**
   - **WebView / RN-web content:** run *actual rrweb* in-context — you get the real
     thing for free.
   - **Android native:** event-driven snapshot+delta layer, sampled, opt-in.
   - **iOS black-box:** **do not** offer faithful mutation capture; offer throttled
     screenshots/video as the "timeline," labeled as such. Faithful capture only
     in gray-box builds.

This gives cloud replay a rich, seekable, inspectable timeline **when enabled and
when cheap**, keeps the default path lean and black-box, and never forces the
mutation-stream cost onto a runtime that can't afford it (iOS).

---

## 7. One-line answer

rrweb is brilliant *because the web gives it a free `MutationObserver`*; mobile
doesn't, so port rrweb's **value** (seekable, inspectable, masked, tiny structural
replay) as an **optional artifact layer over the trace** — not its **default
mechanism**, and never as the **core** of a rearchitecture. The span/event trace
stays the spine; rrweb-style capture is a leaf artifact you switch on for cloud
replay where capture is cheap.

---

### Sources (all consulted this session)
- rrweb — repository & README (snapshot serialize-with-unique-id, record DOM mutations, rebuild-not-video): `https://github.com/rrweb-io/rrweb`
- rrweb guide (design, masking: `.rr-block`/`.rr-ignore`/`.rr-mask`, `maskAllInputs`, password masked by default): `https://github.com/rrweb-io/rrweb/blob/master/guide.md` (live site redirects to `https://rrweb.com/guide.html`)
- rrweb serialization & rebuild: `rrweb-snapshot` package (`https://github.com/rrweb-io/rrweb/tree/master/packages/rrweb-snapshot`)
- rrweb event schema (`EventType` / `IncrementalSource`): `packages/types/src/index.ts` (`https://github.com/rrweb-io/rrweb/blob/master/packages/types/src/index.ts`); "Dive Into Events" recipe
- rrweb observer/coalescing design (batched async `MutationObserver`, records final attr value): `https://github.com/rrweb-io/rrweb/blob/master/docs/observer.md`; "How does session replay work Part2: Observer" (dev.to)
- rrweb canvas cost (`recordCanvas`, `sampling.canvas`, `preserveDrawingBuffer`, WebRTC plugin): `https://github.com/rrweb-io/rrweb/blob/master/docs/recipes/canvas.md`
- rrweb storage optimization (block subtrees, sampling, `pack`/fflate, gzip/brotli): `https://rrweb.com/docs/recipes/optimize-storage`
- rrweb performance (10k+ mutations stalls; 2.5s→35s customer case; mousemove/animation cost): highlight.io session-replay benchmark (`https://www.highlight.io/blog/session-replay-performance`); PostHog `https://posthog.com/blog/session-recording-performance`; rrweb issues #744, #1447, #1820
- Android accessibility (`AccessibilityEvent`/`TYPE_WINDOW_CONTENT_CHANGED`, node retrieval "expensive…only when needed", cache root): Android developer docs `https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent`, `.../accessibilityservice/AccessibilityService`, `.../accessibility/AccessibilityNodeInfo`
- iOS XCUITest snapshot latency (whole-hierarchy snapshot "time-expensive," `snapshotMaxDepth`/`snapshotMaxChildren`, getPageSource stalls): Appium XCUITest driver "WDA slowness" `https://appium.github.io/appium-xcuitest-driver/11.0/troubleshooting/wda-slowness/`; appium/appium #16199
- Mobile session-replay precedent ("rrweb for mobile doesn't exist"; view-hierarchy→JSON→HTML wireframe; throttle default 1000ms; screenshot-on-interaction; per-platform SDKs; Compose/SwiftUI splits): PostHog `https://posthog.com/blog/mobile-session-replay`, `https://posthog.com/docs/session-replay/mobile`
- Maestro internal: `/Users/stevieclifton/codes/Maestro/.superpowers/brainstorm/worked-example-core-contract.md` (Dimension 2 Observation, Dimension 4 Trace)
