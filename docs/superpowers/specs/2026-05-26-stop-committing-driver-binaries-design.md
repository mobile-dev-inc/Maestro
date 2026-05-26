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
- Centralizing building to one toolchain preventing regressions (`macos-26` + `Xcode 26.2`)
- One mechanism that works for:
    - PR e2e
    - CLI release,
    - submodule consumers: studio and worker

## Proposal

- Move the driver binaries out of the source tree into Github Releases
- Commit a tiny pointer (manifest) that names the current binaries.
- Every build pulls the manifest versioned from release.
- We just manage updating driver manifest

### Overall change

1. **Introduce `drivers-manifest.toml`** at repo root — a 200-byte committed pointer to the current driver binaries.
2. **Move the binaries to GitHub Releases of this repo** under prerelease tags `drivers-<sha>`.
3. **Add a `fetchDrivers` Gradle task** that reads the manifest and downloads the binaries into the existing `src/main/resources/` paths.
4. **Gitignore the binary paths** and remove them in a one-time migration commit.
5. **Rewire `update-drivers.yaml`** to publish to the store + update the manifest instead of committing zips back to `main`.

### How each actor works

- **PR (Kotlin-only change)** — `fetchDrivers` runs as part of `processResources`, downloads from the manifest URL, e2e runs against pinned bytes. ~10s overhead.
- **PR (driver source change)** — a `build-drivers` job on `macos-14 + Xcode 26.2` builds fresh zips and uploads them as a GHA workflow artifact; the `e2e` job downloads that artifact into the resource paths; `fetchDrivers` sees files on disk and no-ops; e2e runs against the PR's bytes.
- **Post-merge to `main`** — `update-drivers.yaml` builds on `macos-14`, creates `gh release drivers-<newsha> --prerelease` with the 4 artifacts, then commits the manifest update with `[skip ci]`.
- **CLI release** — Linux publish runner runs `:maestro-cli:shadowJar` → `processResources` → `fetchDrivers` → bytes baked into the fat JAR → `jreleaser` uploads `maestro.tar` to the `v<version>` Release and the Homebrew tap.
- **Maven publish** — same flow as CLI release; `maestro-client.jar` and `maestro-ios-driver.jar` go to Maven Central with bytes already inside.
- **Cloud** (Maven consumer) — pulls `maestro-client:X.Y.Z` from Maven Central; JAR already contains the bytes; never touches the store.
- **Worker / Studio** (submodule consumer) — their CI builds the submodule, transitively triggers `fetchDrivers`, which reads the manifest at the pinned submodule commit and downloads the matching `drivers-<sha>` release; no Xcode required.
- **End user (`brew upgrade maestro`)** — Homebrew downloads `maestro.tar` from the `v<version>` Release; bytes are already inside; `fetchDrivers` never runs on the user's machine.

### Manifest format

```toml
release = "drivers-def456"
artifacts = [
  "maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/maestro-driver-ios.zip",
  "maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.zip",
  "maestro-client/src/main/resources/maestro-app.apk",
  "maestro-client/src/main/resources/maestro-server.apk",
]
```

- URL is derived: `https://github.com/mobile-dev-inc/maestro/releases/download/<release>/<basename(path)>`.
- The release tag is content-addressed (`drivers-<sha>` = artifacts built from source hash `<sha>`); bytes can't drift within a tag.
- Old `drivers-<sha>` releases are never deleted so historical Maestro versions remain reproducible.

### `fetchDrivers` task

- Wired as a dependency of `processResources` in `maestro-client` and `maestro-ios-driver`.
- Declares `drivers-manifest.toml` as `@InputFile` and the four binary paths as `@OutputFiles`.
- Gradle's up-to-date check handles "manifest changed → re-download" and "files missing → re-download" automatically.
- Existing `buildIosDriver` wiring stays in place so Mac devs editing Swift get a local rebuild.

```kotlin
abstract class FetchDrivers : DefaultTask() {
    @get:InputFile  abstract val manifest: RegularFileProperty
    @get:Internal   abstract val repoRoot: DirectoryProperty

    @TaskAction fun run() {
        val toml = Toml().read(manifest.get().asFile)
        val release = toml.getString("release")
        toml.getList<String>("artifacts").forEach { path ->
            val target = repoRoot.file(path).get().asFile
            if (target.exists()) return@forEach
            val url = "https://github.com/mobile-dev-inc/maestro/releases/download/$release/${target.name}"
            target.parentFile.mkdirs()
            URL(url).openStream().use { it.copyTo(target.outputStream()) }
        }
    }
}
```

## Risks

- Old `drivers-<sha>` releases must never be deleted.
- Every build now needs network access to `github.com/.../releases/`; a GitHub outage breaks all source builds.
- Manifest auto-update must use a workflow concurrency group to avoid races on parallel merges.
- Submodule consumers without internet on CI will break (unlikely today).

## Changes (concrete diff)

1. Add `drivers-manifest.toml` at repo root.
2. Add `FetchDrivers` task in `build-logic/` and wire it into `processResources` in `maestro-client` and `maestro-ios-driver`.
3. `.gitignore` the four binary paths; `git rm` the existing files in a single migration commit.
4. Rewrite Dan's `update-drivers.yaml`: pin Xcode 26.2, swap "commit zips" for "release + manifest update".
5. Update `test-e2e.yaml`: add `build-drivers` job on `macos-14` (conditional on driver source diff), make `e2e` consume its workflow artifact.
6. Delete `check-drivers.yaml`, `checkAndroidApksFresh`, `maestro-android-source.sha256`.