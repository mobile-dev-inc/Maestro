# iOS Device Config

A wrapper around `simctl` and XCTest to communicate with iOS devices.

## Prerequisites

### Xcode

Install the latest Xcode (Command Line Tools are not enough, install the full IDE).

### Physical devices

Running Maestro against a physical iOS device also requires:

- [go-ios](https://github.com/danielpaulus/go-ios): install it with `npm install -g go-ios`,
  or put a release binary named `ios` or `go-ios` on `PATH`.
- `iproxy` from [libusbmuxd 2.0.2 or newer](https://github.com/libimobiledevice/libusbmuxd/releases/tag/2.0.2):
  install it with `brew install libusbmuxd` (or upgrade with `brew upgrade libusbmuxd`). Version
  2.0.2 added source-address selection; Maestro requires its `--source` and `--local` options to
  keep XCTest forwarding USB-only and bound to `127.0.0.1`.

Maestro also discovers these binaries from `~/.maestro/deps/go-ios/` and
`~/.maestro/deps/iproxy`, respectively. Set `MAESTRO_GO_IOS_PATH` or `MAESTRO_IPROXY_PATH` to
use a binary elsewhere. Startup preflight checks the required `iproxy` options and reports an
upgrade instruction before configuring the device.

Connect the device over USB, trust the Mac, enable Developer Mode, keep the device unlocked,
and provide an Apple development team when Maestro first builds and signs its XCTest runner:

```bash
maestro test --device <DEVICE_UDID> --apple-team-id <APPLE_TEAM_ID> flow.yaml
```

Physical-device support is currently scoped to one USB-connected device per local CLI invocation.
Wi-Fi devices and physical-device sharding are not supported, and the MCP server currently accepts
iOS simulators only.

The physical-device backend does not support `clearState`, `addMedia`, screen recording, or proxy
configuration. `clearKeychain` logs a warning and continues without changing the device keychain.
On iOS 17.0 through 17.4, go-ios cannot create the userspace tunnel needed by location simulation
and some log/termination fallback paths; those capabilities remain unvalidated without a separately
managed privileged/CoreDevice tunnel.

### IntelliJ setup

If you are working with this subproject, update your IntelliJ config (Help -> Edit Custom Properties) by including the following lines:

```
# Needed for working with idb.proto definition
idea.max.intellisense.filesize=4000
```

Then restart the IDE.
