# Developing

## Running against a local device-core build

Maestro's orchestra layer drives devices through [maestro-device-core](https://github.com/mobile-dev-inc/maestro-device-core) (`dev.mobile.devicecore:implementation` / `:drivers-core`), consumed from `mavenLocal`. The version is not hand-edited: it's read from a gitignored `devicecore.version` file at the repo root, written by a sync script.

Clone both repos next to each other, then from the maestro repo root:

```sh
DEVICECORE_DIR=/path/to/maestro-device-core ./scripts/dev-setup.sh
```

This publishes device-core's `implementation` and `drivers-core` artifacts to `mavenLocal`, writes the resolved version into `devicecore.version`, and builds the maestro CLI (`:maestro-cli:installDist`). It prints the binary path and copy-pasteable run commands for both platforms when it finishes.

`-x buildMcpViewer` is baked into the scripts because the vite/MCP-viewer build step is currently broken in local dev environments — omit it once that's fixed.

### Daily loop

After editing device-core:

```sh
./scripts/devicecore-sync.sh /path/to/maestro-device-core   # or set $DEVICECORE_DIR once and run it bare
./gradlew :maestro-cli:installDist -x buildMcpViewer --refresh-dependencies
```

`devicecore-sync.sh` alone is enough if you're only re-publishing device-core; rerun the CLI build to pick up the new version. Keep `--refresh-dependencies`: while iterating with uncommitted device-core changes the version string stays `0.1.0-<sha>-dirty`, and Gradle caches that fixed version — without it you'd rebuild against a stale jar. (A committed device-core change gets a new sha, so the version changes and the flag is moot.)
