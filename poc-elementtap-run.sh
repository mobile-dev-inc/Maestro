#!/usr/bin/env bash
set -euo pipefail

# POC: validate XCUIElement.tap() against the cross-process HealthKit auth sheet.
#
# This bypasses Maestro's HTTP/Kotlin pipeline entirely. It drives the demo app's
# HealthKit flow with raw XCUIElement.tap() calls. If the test passes, Apple's tap
# correctly resolves cross-process screen coordinates and option B (tap-by-element
# handle) is validated. If it fails the same way Maestro does today, only the
# offset-math approach in PR #3250 will work.
#
# Usage:
#   1. Boot a simulator (iOS 17+):  xcrun simctl boot "iPhone 17 Pro"
#   2. From repo root:              ./poc-elementtap-run.sh
#
# To force a specific simulator, set DEVICE_ID=<udid> before running.

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
BUNDLE_ID="com.example.example"

# Make sure flutter is reachable (non-interactive shells often lack the user's PATH).
if ! command -v flutter >/dev/null 2>&1; then
    for candidate in "$HOME/Downloads/flutter/bin" "$HOME/flutter/bin" "$HOME/development/flutter/bin"; do
        if [ -x "$candidate/flutter" ]; then
            export PATH="$candidate:$PATH"
            break
        fi
    done
fi
if ! command -v flutter >/dev/null 2>&1; then
    echo "ERROR: 'flutter' not found on PATH. Set PATH to include flutter/bin and rerun."
    exit 1
fi

# Resolve booted simulator
DEVICE_ID="${DEVICE_ID:-$(xcrun simctl list devices booted | grep -Eo '[A-F0-9-]{36}' | head -1 || true)}"
if [ -z "$DEVICE_ID" ]; then
    echo "ERROR: no booted simulator."
    echo "Boot one first, e.g.: xcrun simctl boot 'iPhone 17 Pro'"
    exit 1
fi
echo "[POC] simulator: $DEVICE_ID"

echo "[POC] 1/4 — uninstall any prior demo build (resets HealthKit auth state)"
xcrun simctl uninstall "$DEVICE_ID" "$BUNDLE_ID" 2>/dev/null || true

echo "[POC] 2/4 — build demo app for simulator (codesign on, so LD embeds entitlements section)"
APP_PATH="$REPO_ROOT/e2e/demo_app/build/ios/iphonesimulator/Runner.app"
pushd "$REPO_ROOT/e2e/demo_app" >/dev/null
# NOTE: NOT --no-codesign — that path skips the LD entitlements section, which makes
# installcoordinationd report "had no entitlements" and HKHealthStore.isHealthDataAvailable
# returns false, so the auth sheet never appears.
flutter build ios --simulator
popd >/dev/null

echo "[POC] 3/4 — verify entitlements landed in the binary, then install"
codesign -d --entitlements - "$APP_PATH" 2>&1 | sed 's/^/        /' | head -20
xcrun simctl install "$DEVICE_ID" "$APP_PATH"

echo "[POC] 4/4 — run the XCUIElement.tap() POC test"
cd "$REPO_ROOT"
xcodebuild test \
  -project maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj \
  -scheme maestro-driver-ios \
  -destination "id=$DEVICE_ID" \
  -only-testing:maestro-driver-iosUITests/maestro_driver_iosUITests/testHealthKitTapPOC \
  | tee "$REPO_ROOT/poc-elementtap-run.log"

echo
echo "[POC] Done. Result is in poc-elementtap-run.log."
echo "      Look for '** TEST SUCCEEDED **' (validates option B) or"
echo "      '** TEST FAILED **'              (option B not viable, stick with offset math)."
