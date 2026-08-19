# MaestroWorker migration to the device-core execution seam

The device-core converge work deleted the legacy `maestro.Maestro` facade and the
`maestro/drivers/*` drivers, and repointed flow execution onto a single seam:
`maestro.orchestra.Orchestra` now takes a `DeviceCoreDriver` and drives every device verb through
it. MaestroWorker (`copilot/maestro-worker`) still constructs the deleted facade and drivers, so it
can't build against this maestro once the two repos share a composite build. This doc is what the
worker has to change, and the exact maestro-side entry points it imports.

The worker builds green in the coordinated **copilot-repo** PR — not here. This task only made the
seam consumable from `maestro-orchestra` and wrote this down.

## What the maestro side now looks like

Everything the worker needs is public and lives in `maestro-orchestra`, package
`maestro.orchestra.devicecore` — the module the worker already substitutes. It does **not** need
`maestro-cli`.

- `DeviceCoreDriver` — the interface `Orchestra` drives. Public.
- `RealDeviceCoreDriver(providerFactory = ::defaultProviderFor)` — the real driver over device-core.
  Public; the no-arg constructor wires the real Android/iOS adaptors, so `RealDeviceCoreDriver()`
  is all a caller needs.
- `DeviceCoreTarget(platform: Platform, serial: String? = null)` — names which device a `connect`
  reaches. Public data class.
- `DeviceCoreProvisioning` — **new in this task**: a CLI-free provisioning entry point (below).

`SelectorTranslator`, `DeviceCoreErrorMapper`, and the trace types stay internal to the seam — the
worker never touches them.

The device-selection logic (pick the right booted emulator/simulator, adb/simctl discovery,
interactive prompts) stays in `maestro-cli`'s `MaestroSessionManager` and was deliberately NOT moved
into orchestra. The worker boots its own devices via its device-management layer, so it already has
the `(platform, serial)` pair `DeviceCoreTarget` wants — it doesn't need any of that CLI code.

## The entry point the worker imports

```
maestro.orchestra.devicecore.DeviceCoreProvisioning
```

Two functions, both taking an already-resolved device (the worker knows platform + serial because it
booted the device):

```kotlin
// Build + connect; caller owns close().
val driver = DeviceCoreProvisioning.connect(
    platform = Platform.ANDROID,      // maestro.device.Platform
    serial = "emulator-5554",         // null = let device-core pick the single attached device
    appId = "com.example.app",        // carries the iOS bundle id through connect; ignored on Android
)
try {
    Orchestra(driver = driver, platform = platform).runFlow(commands)
} finally {
    driver.close()
}

// Or the scoped form, which closes for you (even if the block or connect throws):
DeviceCoreProvisioning.withSession(platform, serial, appId) { driver ->
    Orchestra(driver = driver, platform = platform).runFlow(commands)
}
```

Both take a `driverFactory: () -> DeviceCoreDriver = { RealDeviceCoreDriver() }` for tests to inject
a fake. This is the same build → connect → close lifecycle the CLI's `MaestroSessionManager` and the
MCP session manager already run; the worker gets it as a named public helper instead of reimplementing
it. If you'd rather not take the helper at all, `RealDeviceCoreDriver().connect(DeviceCoreTarget(...),
appId)` directly is equivalent and just as public — the helper only saves the try/finally.

`Orchestra` itself is unchanged as a consumer contract: `Orchestra(driver = <connected>, platform =
<resolved Platform>)`, in `maestro-orchestra`, package `maestro.orchestra`.

## What the worker PR must change (`copilot/maestro-worker`)

1. **Drop the substitutions for the deleted modules.** These maestro modules no longer exist
   (deleted in W1–W3): `maestro-ios-driver`, `maestro-ios`, `maestro-web`. Every
   `substitute(module("dev.mobile:maestro-ios-driver"))` / `"maestro-ios"` / `"maestro-web"` line
   pointing at the maestro composite build must go — otherwise dependency substitution fails to
   resolve the instant `../maestro` updates. Same for any substitution of a maestro-repo
   `maestro-device` module: there is no `maestro-device` module in the maestro repo (device
   provisioning lives in `maestro-client`, see below), so a substitution targeting one won't resolve.
   Keep the `maestro-orchestra`, `maestro-client`, and `maestro-cli` substitutions.

2. **Drop the matching `implementation(...)` deps** on those three deleted artifacts.

3. **Stop constructing `maestro.Maestro` + `maestro/drivers/*`.** `IOSDevice` / `AndroidDevice` /
   `WebDevice` build a driver and call `getMaestro(): Maestro` today; that facade and those drivers
   are gone. Rewrite them to hand back a connected `DeviceCoreDriver` obtained via
   `DeviceCoreProvisioning` — the worker already has the booted device's platform and serial, so it
   passes those straight into `DeviceCoreProvisioning.connect(platform, serial, appId)`.

4. **Rewrite `MaestroTestRunner`** to run through `Orchestra(driver = <the connected
   DeviceCoreDriver>, platform = <Platform>)` instead of building an `Orchestra` around a `Maestro`.
   It already runs via `maestro.orchestra.Orchestra`, so this is swapping how the driver is supplied.

5. **Web has no device-core provider.** `DeviceCoreTarget(Platform.WEB, ...)` connect throws
   `MaestroException.NotImplemented` at the seam. If the worker still exercises `WebDevice`, that path
   is not yet supported by device-core and the worker PR has to decide whether to drop web coverage or
   gate it — this is a real gap, not something this task can paper over.

## 4th-repo blast radius: `copilot/maestro-device` → `maestro-client`

`copilot/maestro-device` does `api("dev.mobile:maestro-client")`. The converge work slimmed the
maestro side but the shared value types it relies on all still live in `maestro-client` (many under
package `maestro.device`), so that `api(...)` still resolves. The ones the seam's own public surface
exposes, and that a device layer is likely to use:

- `maestro.device.Platform` — the `enum class Platform` (`ANDROID` / `IOS` / `WEB`).
- `maestro.device.Device` — the sealed device model (`Connected`, etc.).
- `maestro.device.DeviceService` — connected-device discovery.
- `maestro.device.DeviceOrientation`, `maestro.device.CapturedDeviceArtifact`.
- `maestro.DeviceInfo`, `maestro.KeyCode`, `maestro.Point`, `maestro.SwipeDirection`,
  `maestro.TapRepeat`, `maestro.ScreenRecording` — value types on the `DeviceCoreDriver` surface.
- `maestro.MaestroException` (in `maestro/Errors.kt`) — the exception hierarchy the seam throws.

If `copilot/maestro-device` imported anything from the now-deleted driver modules (`maestro-ios`,
`maestro-ios-driver`, `maestro-web`) rather than from `maestro-client`, those imports break too and
need the same treatment as the worker's.

## Merge coordination

The composite build (`includeBuild("../maestro")` + `dependencySubstitution`) means the worker builds
against the local maestro checkout, not a published artifact. So the moment `../maestro` picks up this
converge work, the worker's build breaks unless its PR lands in the same window. **The maestro PR and
the copilot PR (worker + `copilot/maestro-device`, if it needs changes) have to merge together.**

"MaestroWorker builds green" is validated in the copilot PR against the merged maestro — it is not
and cannot be validated in the maestro repo, which has no worker sources.
