# Launching a new Android version

How supporting a new Android API level works today across Maestro and its four consumers, what it
costs in PRs, and a recommendation that reduces it.

Worked example throughout: **Android 17 / API 37 / `android-37`**.

The four consumers of Maestro's driver layer:

| Consumer | Runs on | Provisions devices via |
|---|---|---|
| **CLI** (`maestro test`, `maestro start-device`) | user's machine | `maestro-client/device/DeviceService` |
| **MCP** (`maestro mcp`) | user's machine | same `DeviceService` |
| **Studio** | user's machine (+ cloud devices) | `studio-server/core/device/DeviceManager`, over the `maestro` submodule |
| **Worker** (cloud) | macStadium fleet Mac | copilot's own `dev.mobile:maestro-device` (`AvdManager` / `SimManager`) |

---

## 1. The current way

### 1.1 What we actually do

The loop is documented in `.claude/skills/bump-android-version/SKILL.md` and is driven by the
`test-e2e.yaml` GHA workflow.

1. **Extend the workflow enum.** `android-37` must be added to the `android_version` `choice`
   options in `.github/workflows/test-e2e.yaml`, or `workflow_dispatch` rejects the input. This has
   to land *before* the first dispatch.
2. **Bump the SDK.** `maestro-android/build.gradle.kts` — `compileSdk` and `targetSdk` (currently
   `36`; `minSdk` stays `24`).
3. **Rebuild the on-device driver.** `./gradlew :maestro-android:assemble :maestro-android:assembleAndroidTest`,
   which regenerates three checked-in binaries: `maestro-app.apk`, `maestro-server.apk`, and the
   `maestro-android-source.sha256` sentinel that enforces the rebuild.
4. **Discover what broke, by running the whole e2e suite.** Dispatch `test-e2e.yaml` with
   `-f android_version=android-37`, wait ~40 minutes, download `maestro-root-dir-android`, read
   screenshots and `commands-*.json` to work out which `passing/` flows regressed and why.
5. **Patch the driver, rebuild the APKs, re-dispatch.** Repeat 4–5 until green. The skill carries an
   explicit "three iterations without progress, stop and ask" clause — which is the tell that this
   loop is where the calendar time goes.
6. **Fleet.** Add the system images to copilot's catalog and run the prewarm playbook across the
   worker hosts.
7. **Consumers.** Bump the `maestro` submodule twice in copilot (once for the worker, once for
   studio-server) and fix whatever broke.
8. **Sell it.** Add the OS version and the device/OS pairings customers are allowed to select.

### 1.2 The PRs

| # | Type | Repo / path | Change |
|---|---|---|---|
| 1 | PR | Maestro · `.github/workflows/test-e2e.yaml` | add `android-37` to the `android_version` choice `options:` |
| 2 | PR | Maestro · `maestro-android/build.gradle.kts` + `maestro-client/src/main/resources/*.apk` | `compileSdk`/`targetSdk` → 37, rebuilt driver APKs + sha256 sentinel, **plus N driver fixes found by the e2e loop** |
| 3 | PR | copilot · `shared/src/main/resources/supported-devices.yml` | 2 `system_images` entries (`google_apis`, `google_apis_playstore`), 1 `os_versions` entry, N `devices.*.os_version_ids` |
| 4 | PR | copilot · `maestro-device/.../avd/` | `ConfigStep.supportedApis` ranges, `AvdManager.featureFlagsFor(37)`, `GmsBandTable` band |
| 5 | run | copilot · `didb/.../playbooks/android/prewarm-system-images.yml` | download the images onto every `android_worker` (`throttle: 5`) |
| 6 | PR | copilot · `maestro/` submodule | bump for maestro-worker (opened by the `bump-maestro` skill) |
| 7 | PR | copilot · `studio-server/maestro/` submodule | bump for studio-server (same skill, separate PR) |

**5 human PRs + 2 generated PRs + 1 fleet run, across 2 repos.**

Once the device-core cutover lands, add one more:

| 8 | PR | maestro-device-core | `shared/drivers/uiautomation/authority/pin.yaml` — AOSP `revision` + `compile_sdk`, regenerate `api.txt`, re-ledger `map/excluded.yaml` `above-compile-sdk`, plus `compileSdk`/`targetSdk` in 4 fixture-app `build.gradle.kts` |

### 1.3 What makes it expensive

- **Discovery is a 40-minute round trip.** The only signal that Android 37 changed a driver
  behaviour is a customer-shaped e2e flow failing. Getting from "flow X failed at step 13" back to
  "`hideKeyboard` regressed" is manual triage of screenshots and logs, every iteration.
- **The same fact is written in five places.** "What Android version can we drive" appears as
  `compileSdk`, `targetSdk`, the workflow enum, `supported-devices.yml`, and (post-cutover)
  `pin.yaml`'s `compile_sdk` — with no link between them and no check that they agree.
- **Two independent device-provisioning implementations.** `maestro-client/device/DeviceService`
  (CLI, MCP, Studio) and copilot's `maestro-device` (worker) both shell out to `sdkmanager` and
  both create AVDs, with no shared code. Only one of them can install an image.
- **Duplicated lists that are known to drift.** `SimManager.SUPPORTED_RUNTIMES` carries its own
  comment: *"Mirrors the `ios.os_versions` section of `supported-devices.yml` — keep both in sync
  when adding or removing a runtime (no automated cross-check)."*
- **Stale defaults nobody bumps.** `DeviceSpec.Android.DEFAULT` is `pixel_6` / **`android-33`** and
  `DeviceSpec.Ios.DEFAULT` is `iPhone-11` / **`iOS-17-5`**. Every CLI, MCP and Studio user gets
  those unless they pass an explicit `--os`.
- **No consumer can try the new version early.** There is no supported path to "boot API 37 and see
  what happens" that doesn't start with a repo change.

### 1.4 iOS, for contrast

The same shape, worse: upload an Xcode `.xip` to `gs://mobile.dev/xcodes/`, bump `xcode_version` in
the didb inventory, possibly bump `osx-version`'s `target_os_version` and run a full macOS upgrade
per Mac, run `ios/xcode.yml` and `ios/simulator.yml`, add the runtime to `supported-devices.yml`
(**repeated across ~20 device rows**) plus its `xcode_version_required`, mirror it into
`SimManager.SUPPORTED_RUNTIMES`, and update the `Xcode_26.2.app` path in two Maestro workflows.
Roughly **7 human PRs + 3 fleet runs + 1 manual upload**.

---

## 2. Recommendation — the Host layer

**The concept that is missing is the _host_: the machine, as a capability.** It is the only layer
that can answer a question before any device exists. device-core already uses the word this way
(`conformance/rig/Host.kt` — *"The host, as facts"*, the `HOST-ABSENT` skip verdict, the
`device-hosts` skill). Maestro spells it `AndroidEnvUtils` / `SystemInfo` — names that describe
*reading*, which is why nothing in Maestro owns *acquiring*.

### 2.1 The three layers, separated

- **Host** — the machine: SDK root, cmdline-tools, system images, emulator binary, Xcode, simulator
  runtimes, macOS, licenses, disk. *Test: it can answer with no device in existence.*
- **Device** — turns a host capability into a booted, configured device: AVD create → config steps
  → boot. *Test: it produces one.* (`DeviceService`, `AvdManager`, `SimManager`.)
- **Driver** — speaks to a device that is already up. *Test: it requires one to already exist.*
  (`maestro-android` today; device-core tomorrow.)

### 2.2 The recommendation, part by part

- **Name the layer `Host` and give it three verbs** — `describe` (what can this machine boot),
  `plan` (what would it take to run X here), `ensure` (do the acquirable part, report the rest).
- **Put it in `maestro-client/device/`, beside `AndroidEnvUtils`** — the one module every consumer
  already reaches, and the one that survives the device-core cutover (`maestro-android` does not).
- **Move, don't build: `ensure` already exists.** copilot's `AndroidSdk.ensureSystemImage` →
  `sdkmanager --install` already installs on demand; it just lives in the consumer that needed it
  instead of the layer all four share.
- **Add a lock around acquisition.** There is no locking around `ensureSystemImage` today; prewarm
  has been hiding that by making the install a no-op at run time. Removing prewarm exposes it.
- **`plan` is the only genuinely new code** — and it is the part that answers the iOS pain:
  *"needs Xcode 27.0, host has 26.2; Xcode 27.0 needs macOS ≥ 27"*, per machine, before anything
  starts.
- **Delete the prewarm path for Android.** The first run that asks for `android-37` installs it.
  This removes the `android-system-images` role, `prewarm-system-images.yml`,
  `bump-cmdline-tools.yml`, and the 14 hardcoded strings in `supported-devices.yml`'s
  `system_images:` block.
- **Keep one-time host bootstrap, which is not per-version** — SDK root, cmdline-tools, Java, and
  **SDK license acceptance** (`ensureSystemImage` does not pipe `yes`, so licenses must be
  pre-accepted). Run once when a Mac joins the fleet.
- **Report `host describe` up to the scheduler.** Replaces "we prewarmed it" as the gate that stops
  a run being routed to a host that cannot produce the device — and it is live truth rather than
  "a playbook ran three weeks ago".
- **Land the driver conformance harness and put it in front of e2e.** It already exists, built and
  validated across API 24–36, on the unmerged `spec/driver-conformance-harness` branch. Running
  `:maestro-test:driverConformance --api 37` gives a per-verb red/green matrix in ~20 minutes on one
  device. **e2e stops being the discovery mechanism and becomes the final gate.**
- **Generate the `test-e2e.yaml` enum from the host probe** — removes an entire PR and the
  "forgot to extend the enum" failure the bump skill warns about.
- **Derive `DeviceSpec.DEFAULT` from `host describe`** — the stale `android-33` / `iOS-17-5`
  defaults stop being constants.
- **Shrink `supported-devices.yml` to policy only.** Capability is discovered; what remains is the
  commercial decision — `hidden`, and deliberate withholdings like MA-4041 (35/36 kept off the
  pixel_9 family because their screens cascade failures in existing customer flows). That part
  deserves to stay hand-written.
- **Delete the mirrored lists** — `SimManager.SUPPORTED_RUNTIMES` and iOS's `xcode_version_required`
  both become computed.
- **iOS keeps a real rollout.** `xcodes runtimes install` is multi-GB and Xcode/macOS upgrades need
  reboots, so they cannot happen inside a customer's run. `plan` makes the cost visible and
  planned; it does not make it smaller.

### 2.3 What the launch looks like afterwards

| Step | Cost |
|---|---|
| `host plan/ensure --os android-37`, then `start-device` — on any laptop | ~10 min, 0 PRs |
| `:maestro-test:driverConformance --api 37` → per-verb red/green | ~20 min, 0 PRs |
| **`compileSdk`/authority pin + APK rebuild + fix what the matrix flagged** | **the real work — 1 PR** |
| `test-e2e.yaml` dispatched **once**, as a gate | 0 PRs (enum generated) |
| ~~fleet prewarm~~ | **gone** — first run installs the image |
| `ConfigStep.supportedApis` / `featureFlagsFor(37)` review + policy row | 1 PR |
| submodule bumps ×2 | generated by `bump-maestro` |

### 2.4 What this does *not* fix

- The driver work itself — `compileSdk`, the APK rebuild, the authority re-pin, and fixing what
  Android actually changed — is unchanged, and is still most of the calendar time.
- Xcode downloads and macOS upgrades are unchanged.
- `ConfigStep.supportedApis` / `featureFlagsFor` still need per-API human judgment about emulator
  behaviour. That is genuine engineering, not bookkeeping.

---

## 3. PR comparison

### Android (`android-37`)

| | Today | Recommended |
|---|---|---|
| Workflow enum | 1 PR | 0 — generated |
| SDK bump + driver APKs + fixes | 1 PR | 1 PR |
| copilot catalog (`supported-devices.yml`) | 1 PR | folded into the config-step PR (policy row only) |
| copilot config steps (`maestro-device`) | 1 PR | 1 PR |
| Fleet prewarm | 1 run | 0 |
| Submodule bumps | 2 generated PRs | 2 generated PRs |
| device-core authority pin (post-cutover) | 1 PR | 1 PR |
| **Human PRs** | **5** | **2** |
| **Generated PRs** | 2 | 2 |
| **Fleet runs** | 1 | **0** |
| **Repos touched** | 3 | 2 |
| **Time to "what broke?"** | ~40 min + manual triage | ~20 min, per-verb |

### iOS (`iOS-27-0` / Xcode 27)

| | Today | Recommended |
|---|---|---|
| Xcode `.xip` upload to GCS | 1 manual | 1 manual (unchanged) |
| didb inventory `xcode_version` | 1 PR | 1 PR |
| `osx-version` target + macOS rollout | 1 PR + 1 run | 1 PR + 1 run (unchanged) |
| `ios/xcode.yml` rollout | 1 run | 1 run (unchanged) |
| `ios/simulator.yml` runtime install | 1 run | 0 — `host ensure` |
| `supported-devices.yml` (+ ~20 device rows, `xcode_version_required`) | 1 PR | policy row only |
| `SimManager.SUPPORTED_RUNTIMES` mirror | 1 PR | 0 — deleted |
| Maestro workflows' `Xcode_26.2.app` path + xctest runner target | 1 PR | 1 PR |
| device-core `uikit`/`swiftui`/`xctest` pins | 1 PR | 1 PR |
| Submodule bumps | 2 generated PRs | 2 generated PRs |
| **Human PRs** | **7** | **4** |
| **Fleet runs** | 3 | 2 |

### Build cost of the recommendation

- **One move** — `ensureSystemImage` and its siblings down into `maestro-client/device/`.
- **One lock** — around SDK acquisition.
- **One new verb** — `plan`.
- **One merge** — the conformance harness, already written, sitting on `spec/driver-conformance-harness`.

### Related, and worth fixing alongside

`devicecore.version` is gitignored (`.gitignore:28`), so which device-core build a given Maestro
shipped against is not answerable from the repo. Any "bump once, consumers inherit" story needs that
pin committed.
