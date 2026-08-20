#!/usr/bin/env bash
# One-shot dev setup: syncs device-core into mavenLocal + devicecore.version, then
# builds the maestro CLI against it. See DEVICE_CORE_INTEGRATION.md.
#
# Usage:
#   ./scripts/dev-setup.sh [path-to-device-core-repo]
#   DEVICECORE_DIR=/path/to/maestro-device-core ./scripts/dev-setup.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAESTRO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

"$SCRIPT_DIR/devicecore-sync.sh" "$@"

echo "Building maestro CLI ..." >&2
( cd "$MAESTRO_ROOT" && ./gradlew :maestro-cli:installDist -x buildMcpViewer --refresh-dependencies )

BIN="$MAESTRO_ROOT/maestro-cli/build/install/maestro/bin/maestro"

cat <<EOF

Next steps
==========

Binary built at:
  $BIN

Find an Android emulator/device serial:
  adb devices

Find a booted iOS simulator udid:
  xcrun simctl list devices booted

Build + install the demo app:

  Android:
    cd e2e/demo_app
    flutter build apk --debug
    adb -s <serial> install -r build/app/outputs/flutter-apk/app-debug.apk

  iOS:
    cd e2e/demo_app
    flutter build ios --simulator --debug
    xcrun simctl install <udid> build/ios/iphonesimulator/Runner.app

Run the device-core smoke flow:

  $BIN test e2e/demo_app/.maestro/devicecore_smoke.yaml -p android --udid <serial>
  $BIN test e2e/demo_app/.maestro/devicecore_smoke.yaml -p ios --udid <udid>

Daily loop while iterating on device-core:
  1. edit device-core
  2. ./scripts/devicecore-sync.sh (or set \$DEVICECORE_DIR once and re-run it bare)
  3. rebuild maestro: ./gradlew :maestro-cli:installDist -x buildMcpViewer
EOF
