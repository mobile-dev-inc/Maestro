# killApp vs stopApp — Deprecation Decision & Schema-Change Story

Staff-eng decision report. Code research + judgment only; no emulator run, no command was deprecated. Verification = grounding in source + the conformance harness finding.

## Issue

Maestro exposes two near-identical YAML commands that both "stop an app":

- `stopApp` → `am force-stop <appId>` on Android.
- `killApp` → `am kill <appId>` on Android.

Their user-facing models are **byte-for-byte identical** apart from the class name (`YamlStopApp` vs `YamlKillApp`, both `appId?/label?/optional`), and on iOS/Web they are already the same operation. The overlap confuses users ("which one stops my app?") and multiplies maintenance across four consuming surfaces.

The new driver conformance harness (`./gradlew :maestro-test:driverConformance`) found that **`killApp` is ineffective on Android API 24–27** — it reports `process still running after killApp: pid=…`. It begins working at API 28. `stopApp` (force-stop) works across all API levels. This is because `am kill` only reaps **cached/background** processes; it cannot terminate a foreground app on older Android.

## Root cause / analysis

### What each command actually does

| | Android | iOS | Web |
|---|---|---|---|
| `stopApp` | `am force-stop $appId` — `AndroidDriver.kt:240` | `iosDevice.stop(appId)` — `IOSDriver.kt:111` | stop (WebDriver) |
| `killApp` | `am kill $appId` — `AndroidDriver.kt:247` | **alias → `stopApp(appId)`** — `IOSDriver.kt:118` | alias → stop |

- `Driver` interface declares both: `Driver.kt:41` (`stopApp`), `Driver.kt:43` (`killApp`).
- Android `killApp` carries the comment *"Kill is the adb command needed to trigger System-initiated Process Death"* (`AndroidDriver.kt:246`). This is the **only real distinction**: `am kill` is meant to simulate the OS reclaiming a backgrounded process so a test can assert correct state restoration — it is a *niche test affordance*, not a general "stop the app" command.
- iOS makes the distinction meaningless: `IOSDriver.kt:117` — *"On iOS there is no Process Death like on Android so this command will be a synonym to the stop command."*

### The conformance finding (the smoking gun)

- `KillAppBehavior.kt:25` already hard-codes a workaround: *"am kill only works on cached/background processes; press Home first."* It presses HOME before calling `killApp` (`KillAppBehavior.kt:26-29`) and still fails on API 24–27 with *"process still running after killApp"* (`KillAppBehavior.kt:44`).
- `StopAppBehavior.kt:24` just calls `stopApp` directly and passes on every API level.

So `killApp` is (a) ineffective on a range of supported APIs, (b) only meaningfully different from `stopApp` on Android, and (c) requires a HOME-press dance to have any chance of working — none of which the average user knows.

### How it surfaces as a command, and the cross-surface coupling

A single command's shape is currently re-declared in **six+ places**, none generated from a single source:

1. **Models (de-facto source of truth):** `StopAppCommand` (`Commands.kt:692`), `KillAppCommand` (`Commands.kt:709`) — identical bodies. Plus `MaestroCommand.kt:54-55,104-105,151-152` (one nullable field + dispatch per command).
2. **YAML parse map:** `MaestroFlowParser.kt:149-150` (`"stopApp" → …`, `"killApp" → …`) and `YamlFluentCommand.kt:124-125,330-345`.
3. **Orchestra dispatch:** `Orchestra.kt:362-363,679-687` — both route to `maestro.stopApp` / `maestro.killApp`.
4. **CLI / MCP:** the MCP server does **not** expose per-command tools; it gives the LLM a `cheat_sheet` tool that **fetches command docs from a remote URL** — `CheatSheetTool.kt:30` → `https://api.copilot.mobile.dev/v2/bot/maestro-cheat-sheet`. The LLM then writes YAML run via `RunTool`. So MCP's command knowledge is *another* hand-maintained copy, served from copilot.
5. **copilot — Studio UI:** `studio/src/mocks/maestro/commands-index.ts` (`killApp` ~line 351, `stopApp` ~511-516, menu ~625) — the full ~70-command list hand-coded in TypeScript.
6. **copilot — Schema + cheat sheet (what MCP & worker rely on):** `python/api/maestro-schema.json:1161-1179` (already documents `killApp` as *"On iOS and Web, it is an alias for stopApp"*) and `python/api/maestro-cheat-sheet.yaml:345-349`. The worker executes via the embedded Maestro submodule (`copilot/maestro/…`), so it inherits the Kotlin models, but validation/docs/UI are independent copies.

**Coupling summary:** rename/deprecate one command and you must edit, in lockstep: `Commands.kt`, `MaestroCommand.kt`, `MaestroFlowParser.kt`, `YamlFluentCommand.kt`, `Orchestra.kt`, copilot `commands-index.ts`, `maestro-schema.json`, `maestro-cheat-sheet.yaml` — across two repos, with no compiler or test that fails if any copy drifts.

## Recommendation (the opinion)

**Deprecate `killApp`. Keep `stopApp` as the one canonical "stop the app" command, and keep a behavior-preserving `killApp` only as a thin, documented alias on the deprecation path.**

Reasons, in priority order:

1. **`killApp` is broken on supported APIs; `stopApp` is universally correct.** The conformance harness proves `am kill` fails on API 24–27 (`KillAppBehavior.kt:44`), while `am force-stop` passes everywhere (`StopAppBehavior.kt`). Keeping a command that silently no-ops on a quarter of the Android matrix is a footgun.
2. **It only differs on one platform.** iOS/Web already alias `killApp → stopApp` (`IOSDriver.kt:117-118`). Two commands that are identical on 2 of 3 platforms and unreliable on the third do not earn their keep.
3. **Real user intent is "stop the app," which is exactly `stopApp` / force-stop.** `am kill` exists to simulate System-initiated Process Death — a test-engineering edge case, not the 99% need. Users reach for "kill" expecting it to *definitely* stop the app; force-stop is what actually does that.

### Don't simply delete the Process-Death capability

`killApp`'s legitimate niche (forcing process death to test state restoration) should not vanish — it should be **renamed to intent**, not killed silently. Migration path:

1. **Now (non-breaking):**
   - Mark `KillAppCommand` `@Deprecated` and have `YamlFluentCommand`/`MaestroFlowParser` map `killApp` to a **`stopApp`** with a one-time deprecation warning logged at parse time. (`killApp` continues to parse, so no flow breaks.)
   - Update copilot `maestro-schema.json` / `maestro-cheat-sheet.yaml` / `commands-index.ts` to label `killApp` as deprecated → "use `stopApp`".
2. **If the process-death affordance is worth preserving as a first-class feature:** introduce `simulateProcessDeath` (Android-only, explicitly documented as no-op-able pre-API-28, or implemented via a reliable mechanism), and route the old `killApp` Android `am kill` path there. Otherwise, folding `killApp` into `stopApp` is sufficient for the vast majority of flows.
3. **Later (major version):** remove `killApp` from docs/schema/UI; keep the YAML alias indefinitely for backward-compat (cheap — one entry in the parse map).

Net effect: users get one reliable command; the broken `am kill` foreground path stops being the default behavior behind a friendly name.

## Schema-change story (worker / Studio / CLI / MCP)

**One-liner:** Make the Kotlin command models the single source of truth, generate every downstream artifact (JSON schema, MCP/cheat-sheet docs, Studio command index) from them in CI, and adopt an explicit `@Deprecated`/alias policy so no command name is ever hand-maintained in more than one place.

### Current pain
A command's identity and shape live in 6+ independently-edited locations across two repos (enumerated above). There is no compile error or failing test when a copy drifts — the JSON schema, the remote cheat sheet that *MCP* depends on, and the Studio TS list can each silently fall out of sync with the Kotlin models. Deprecating `killApp` today literally means a coordinated 8-file, 2-repo edit.

### Proposed approach

1. **Single source of truth = `maestro-orchestra-models`.** The Kotlin `Command` data classes already are the runtime authority. Enrich them with the metadata the other surfaces re-invent by hand: add lightweight annotations (e.g. `@CommandMeta(name = "stopApp", category = "app-state", since = "x", deprecatedBy = "…")`) and use the existing fields as the schema (appId/label/optional). Keep this in the existing module — do not spin up a new Gradle module (extend orchestra/models).

2. **Generate, don't duplicate.** Add a Gradle task (sibling to `driverConformance`) that reflects/serializes the annotated command models into:
   - **JSON Schema** → replaces hand-edited `copilot/python/api/maestro-schema.json`.
   - **Cheat sheet** → the artifact served at `api.copilot.mobile.dev/.../maestro-cheat-sheet` that **MCP's `CheatSheetTool` fetches** (`CheatSheetTool.kt:30`). Generating this makes MCP correct-by-construction.
   - **Studio command index** → `copilot/studio/src/mocks/maestro/commands-index.ts` becomes generated, not hand-coded.
   Check the generated files into the repos and **fail CI** if regeneration produces a diff (a "schema is stale" check). This converts silent drift into a red build.

3. **Worker stays correct for free** because it executes via the embedded Maestro submodule (Kotlin models) — but its *validation/allowlist* (if any) should also be generated from the same metadata so it accepts exactly the command set the models define.

4. **Versioning & deprecation policy.** Standardize on:
   - `deprecatedBy`/`since`/`removedIn` metadata on each command → flows through to schema + docs + a parse-time warning automatically.
   - **Aliases are a first-class concept** in the parse map (`MaestroFlowParser.kt`): `killApp → stopApp` is one declarative entry, generated into every surface's "this is deprecated, use X" hint.
   - Never remove a YAML name in a minor version; keep aliases indefinitely, drop only docs/UI visibility.

5. **MCP specifically:** since MCP teaches the LLM via the generated cheat sheet rather than per-command tool schemas, keeping the cheat sheet generated means MCP never recommends a removed/broken command (e.g. it would stop suggesting `killApp`). No hand-edited MCP tool defs to maintain for commands.

**Outcome:** changing a command (add field / rename / deprecate) becomes: edit the Kotlin model + annotation in one place, run the generator, commit the regenerated artifacts. CI guarantees worker, Studio, CLI, and MCP all move in lockstep instead of drifting.

## Verification

Grounded entirely in source + the conformance harness; no emulator used.

- **Behavioral difference:** `AndroidDriver.kt:240` (`am force-stop`) vs `AndroidDriver.kt:247` (`am kill`) with intent comment `AndroidDriver.kt:246`. iOS alias `IOSDriver.kt:115-119`. Interface `Driver.kt:41,43`.
- **Conformance finding (killApp ineffective 24–27):** `KillAppBehavior.kt:25-29,44` (HOME-press workaround + "process still running after killApp" fail path) vs `StopAppBehavior.kt:24` (direct, passes). Harness entrypoint `maestro-test/build.gradle.kts:49`; usage `maestro-test/README.md:42-51`.
- **Identical user-facing shape:** `YamlStopApp.kt` and `YamlKillApp.kt` are identical but for the class name; models `Commands.kt:692,709`.
- **Cross-surface coupling:** `MaestroCommand.kt:54-55`, `MaestroFlowParser.kt:149-150`, `YamlFluentCommand.kt:124-125,330-345`, `Orchestra.kt:362-363,679-687`, `CheatSheetTool.kt:30` (remote cheat-sheet dependency), and copilot `commands-index.ts`, `python/api/maestro-schema.json:1161-1179` (already documents killApp as a stopApp alias), `python/api/maestro-cheat-sheet.yaml:345-349`.
