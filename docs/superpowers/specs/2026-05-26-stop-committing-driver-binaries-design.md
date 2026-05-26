# Stop Committing Driver Binaries to Git

## Current

- Driver binaries live in `maestro-client/src/main/resources/` and `maestro-ios-driver/src/main/resources/`.
- Four files: `maestro-app.apk`, `maestro-server.apk`, `maestro-driver-ios.zip`, `maestro-driver-iosUITests-Runner.zip`.
- Every driver source change rewrites ~7 MB into git history.
- `check-drivers.yaml` blocks PRs that change driver source without committed bytes.
- Linux publish works because committed bytes satisfy Gradle's up-to-date check on `buildIosDriver`.
- Cloud consumes Maestro via Maven JARs.
- Worker and Studio consume Maestro as a git submodule.

## Objective

- Stop bloating git history with binary blobs (closes #1822).
- Keep published JARs and CLI fat-JAR byte-identical for consumers.
- Pin the driver build toolchain (`macos-14` + `Xcode 26.2`) for reproducibility.
- One mechanism that works for PR e2e, CLI release, Maven publish, and submodule consumers.

## Proposal

### Storage

- Built drivers live as GitHub Release assets on this same repo.
- Tag scheme: `drivers-<sha>`, marked `--prerelease` so they hide from the default Releases UI.
- One release per driver-source content hash.
- Old `drivers-<sha>` releases are never deleted (preserves reproducibility for old Maestro versions).

### Manifest

- New file `drivers-manifest.toml` at repo root, ~200 bytes, committed.
- Names the current `drivers-<sha>` release and lists the four artifact paths + sha256s.

```toml
release = "drivers-def456"

[[artifacts]]
path   = "maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/maestro-driver-ios.zip"
sha256 = "8f3a1c..."

[[artifacts]]
path   = "maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.zip"
sha256 = "2b9e07..."

[[artifacts]]
path   = "maestro-client/src/main/resources/maestro-app.apk"
sha256 = "c4d2f8..."

[[artifacts]]
path   = "maestro-client/src/main/resources/maestro-server.apk"
sha256 = "a1e5b3..."
```

- URL is derived: `https://github.com/mobile-dev-inc/maestro/releases/download/<release>/<basename(path)>`.

### `fetchDrivers` Gradle task

- New task wired as a dependency of `processResources` in `maestro-client` and `maestro-ios-driver`.
- For each manifest entry: if file exists on disk, trust it; else download and verify sha256.
- No flags. No source-sha hashing. Disk state wins.

```kotlin
abstract class FetchDrivers : DefaultTask() {
    @get:InputFile  abstract val manifest: RegularFileProperty
    @get:Internal   abstract val repoRoot: DirectoryProperty

    @TaskAction fun run() {
        val toml = Toml().read(manifest.get().asFile)
        val release = toml.getString("release")
        toml.getTables("artifacts").forEach { a ->
            val target = repoRoot.file(a.getString("path")).get().asFile
            if (target.exists()) return@forEach
            val url = "https://github.com/mobile-dev-inc/maestro/releases/download/$release/${target.name}"
            target.parentFile.mkdirs()
            URL(url).openStream().use { it.copyTo(target.outputStream()) }
            check(sha256(target) == a.getString("sha256")) { "sha mismatch: ${target.name}" }
        }
    }
}
```

### Publish trigger (when `drivers-<sha>` and manifest are written)

- Single trigger: push to `main` that touches driver source paths.
- `update-drivers.yaml` runs on `macos-14` with `DEVELOPER_DIR=/Applications/Xcode_26.2.app/...`.
- Steps: build the four artifacts → `gh release create drivers-<newsha> --prerelease <files>` → rewrite `drivers-manifest.toml` → commit with `[skip ci]`.
- Concurrency group prevents race between two parallel main merges.

### PR flow — Kotlin-only change

- `processResources` triggers `fetchDrivers`.
- `fetchDrivers` downloads four files from the current `drivers-<sha>` release.
- E2E runs against pinned bytes.
- ~10s overhead (cached after first PR run on a runner).

### PR flow — driver source change

- `test-e2e.yaml` gains a `build-drivers` job on `macos-14`, conditional on driver source diff.
- `build-drivers` builds the four artifacts and uploads them as a workflow artifact (`actions/upload-artifact`).
- The `e2e` job downloads that artifact into the same paths `fetchDrivers` would write.
- `fetchDrivers` sees files on disk, does nothing.
- E2E runs against freshly built bytes.
- The store is not written; workflow artifact is discarded after the run.

### Release flow

- `publish-release.yaml` and `publish-cli.yaml` unchanged in shape.
- Gradle triggers `fetchDrivers` before `processResources` on the Linux runner.
- Bytes appear in `src/main/resources/` exactly where today's committed bytes live.
- Maven JARs and CLI fat-JAR are byte-identical to today's output.

### Cloud (Maven consumer)

- Pulls `maestro-client:X.Y.Z` and `maestro-ios-driver:X.Y.Z` from Maven Central.
- Those JARs already contain the bytes (release CI baked them in).
- Zero change. No knowledge of `drivers-<sha>`.

### Worker and Studio (submodule consumers)

- Their submodule pin = their version pin.
- When they build, `fetchDrivers` runs in their CI on the submodule's paths.
- It reads the manifest at the pinned submodule commit and downloads from the corresponding `drivers-<sha>` release.
- No Xcode on their runners; pure HTTP.
- To bump driver bytes, they bump the submodule pointer.

## Changes (concrete diff)

1. Add `drivers-manifest.toml` at repo root.
2. Add `FetchDrivers` task in `build-logic/` and wire it into `processResources` in `maestro-client` and `maestro-ios-driver`.
3. `.gitignore` the four binary paths; `git rm` the existing files in a single migration commit.
4. Rewrite Dan's `update-drivers.yaml`: pin Xcode 26.2, swap "commit zips" for "release + manifest update".
5. Update `test-e2e.yaml`: add `build-drivers` job on `macos-14` (conditional), make `e2e` consume its workflow artifact.
6. Delete `check-drivers.yaml`, `checkAndroidApksFresh`, `maestro-android-source.sha256`.

## Risks

- Old `drivers-<sha>` releases must never be deleted.
- Every build now needs network access to `github.com/.../releases/`; GitHub outage breaks builds.
- Manifest auto-update must use a workflow concurrency group to avoid stomp on parallel merges.
- Submodule consumers without internet access on CI will break (unlikely today).

## Out of scope

- Rewriting past git history (Bartek already ruled this out).
- A separate companion repo for binaries.
- A separate Maven artifact for binaries.

## Open questions

- Do we attach the four artifacts to `v<version>` releases too (mirror, for archival)? Optional polish.
- Do we ever GC old `drivers-<sha>` releases? Default: never. Revisit if storage cost becomes real.
