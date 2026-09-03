---
name: bump-cli-device-support
description: Use when a new OS version or a system-image naming change breaks how the Maestro CLI + core libs provision a device locally — `maestro start-device` failing to resolve or launch a new image, `osVersion` returning 0 on a minor-versioned platform (`android-37.1`), variant image dirs (`google_apis_ps16k`), new/renamed tags, or `avdmanager` getting the wrong or absent package. NOT the on-device driver/APK bump (bump-android-version) and NOT the cloud worker/backend pipeline (copilot bump-device-support).
---

# Bump CLI Device Support

## Overview

An OS bump is more than a driver/e2e change: the CLI's own device-creation path (`maestro start-device` → `DeviceSpec` → `avdmanager`) has to resolve and launch the new system image. The driver `test-e2e` workflow builds its **own** `system-images;…` string, so it never exercises this path — a broken CLI resolution only surfaces for real users and the `maestro-device` worker.

This skill is the **repeatable loop** for that leg: reproduce the provisioning break on the new target, fix it in the CLI + core libraries, and prove it against real workloads — looping until green.

**Design-neutral.** Do not prescribe *how* resolution should work — the contract flips between releases (fail-fast on a missing image vs. resolve-the-package-on-the-host, `systemImageOverride` string vs. semantic tag). This skill's job is to make `start-device` provision the new image with **whatever contract the code currently uses**. Read the current code first (Pre-flight); match it, don't redesign it.

## When to Use

- A new API level / OS version, or a system-image naming change: minor-versioned platform (`android-37.1`), variant image dir (`google_apis_ps16k`), new/renamed tag.
- `maestro start-device` can't resolve or launch the new image; `avdmanager` gets the wrong or absent package.
- `DeviceSpec.osVersion` returns 0 (or throws) on a minor-versioned `os`.

**Not for** — driver APKs / `compileSdk` (that's `bump-android-version`), or the cloud worker/backend catalog, prewarm, bake (that's copilot `bump-device-support`).

| Skill | Repo | Owns |
|---|---|---|
| `bump-android-version` | Maestro | driver APKs + `test-e2e` (→ device-core) |
| **`bump-cli-device-support`** (this) | Maestro | CLI + core libs: `start-device` image resolution |
| `bump-device-support` | copilot | maestro-device, worker/backend, smoke (downstream) |

Run this alongside `bump-android-version` — both Maestro-side. This CLI/core leg stays in Maestro when the driver leg moves to device-core.

## Pre-flight

- Clean tree; `main` up to date.
- Branch: `git checkout -b bump-cli-device-<target> origin/main`.
- **Locate the layer — files and the resolution contract move release to release, so find, don't assume:**
  ```bash
  rg -n "osVersion|systemImage|selectSystemImage|avdmanager|start-device" maestro-client maestro-cli
  ```
  Usual homes: image resolution + `osVersion` parsing in `maestro-client/.../device/DeviceSpec.kt`; the `avdmanager create avd` invocation in `maestro-client/.../device/DeviceService.kt`; orchestration (install/prompt, calls `DeviceService`) in `maestro-cli/.../device/DeviceCreateUtil.kt`. Read how resolution works **today** before changing it.

## The loop

### 1. Reproduce

```bash
maestro start-device --platform android --device-os <target>
```

`<target>` is the new `android-<n>` (or a full `system-images;…` override — `--device-os` accepts both). Capture exactly how it breaks: wrong/absent package, `osVersion` parse, `avdmanager` args.

### 2. Diagnose

Trace the failure to the resolution/invocation code (homes above). Traps seen on past bumps — a checklist of *what tends to break*, not a fixed fix:

- `osVersion` parsed as an int that dies on a minor-versioned platform (`android-37.1` → 0).
- The package family gains a variant dir (`google_apis_ps16k`) the derivation doesn't know.
- `avdmanager` handed `--tag`/`--abi` that diverge for variant images — it takes a fully-qualified `--package` instead.

### 3. Fix — with consent, in the CLI + core libs

Keep the fix in the CLI/core layer, not the driver or a CI workaround. Prefer the general path (`--device-os` accepting a full `system-images;…` override) over hardcoding a specific image; hardcode a default only with explicit maintainer sign-off.

Present each proposed diff (file + one-line summary + side effects) and **ask before editing**:

> Proposed fix for `<symptom>`: `<one-liner>`. Apply?

One commit per logical concern (e.g. `osVersion`/image-path parsing separate from the `avdmanager` change) so each is revertible.

### 4. Validate — loop until green

1. **Fast** — assert resolution with a unit test, adding a case for the new target:
   ```bash
   ./gradlew :maestro-client:test --tests 'maestro.device.DeviceSpecTest'
   ```
   (add `DeviceServiceTest` if selection policy changed.)
2. **Boot** — build the CLI from the branch, run step 1 again: it resolves a valid image, creates the AVD, and boots. Tear the emulator down.
3. **Real workloads** — replay a set of real cloud runs locally against an AVD on the new image (copilot **`replay-cloud-run`** skill; if it isn't available in this repo, re-run a representative cloud flow manually against the AVD).

Any leg red → back to step 3 and loop. Don't stop at the first green unit test.

### 5. Commit

```bash
git commit -m "feat(device): support <target> in start-device"
```

Separate commits per concern (step 3).

## "Supported" = three legs, not one

Starts locally via the CLI (this skill) **and** the driver e2e is green (`bump-android-version`) **and** the cloud pipeline accepts it (copilot `bump-device-support`). A green e2e alone is not a supported bump — e2e never exercises CLI resolution.

## Anti-patterns

- **Prescribing a specific resolution architecture** — read and match whatever the code does today; this skill fixes provisioning, it doesn't redesign the contract.
- **Calling the bump done on green e2e alone** — `test-e2e` builds its own image string and never runs `start-device`.
- **Masking a CLI gap in the driver or a CI workflow** — fix the CLI/core layer so real users inherit it.
- **Auto-applying Kotlin edits without consent** — every source patch needs explicit approval first.
- **Bundling unrelated concerns into one commit.**
- **Hardcoding a specific image** instead of the general `--device-os` override path (needs sign-off).
- **Passing `--tag`/`--abi` to `avdmanager` for variant images** — pass a fully-qualified `--package`.
- **Committing on `main`** — always work on the bump branch.
