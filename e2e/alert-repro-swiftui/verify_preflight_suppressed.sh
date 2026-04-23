#!/usr/bin/env bash
# Regression guard for the verifying iOS XCTest UI-interruptions are disabled.
#
# How it works:
#   1. Generates + builds the minimal AlertRepro SwiftUI app for iOS Simulator.
#   2. Installs it on the given simulator and runs the Maestro flow that
#      opens a real UIAlertController via SwiftUI `.alert(...)` and swipes.
#   3. Greps the latest xctest_runner log for Apple's preflight signature.
#      If any signature fires, the swizzle in
#      maestro-ios-xctest-runner/maestro-driver-iosUITests/Categories/XCUIApplication+Helper.m
#      (+load method) regressed.
#
# Prereqs:
#   - xcodegen, xcodebuild on PATH.
#   - iPhone simulator booted with the given UDID.
#   - `maestro` on PATH, or runnable from repo root via `./maestro`.
#
# Usage (local):
#   e2e/alert-repro-swiftui/verify_preflight_suppressed.sh <iPhone-sim-UDID>

set -euo pipefail

SIM_UDID="${1:-}"
if [[ -z "$SIM_UDID" ]]; then
    echo "usage: $0 <simulator-udid>" >&2
    exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
LOG_DIR="$HOME/Library/Logs/maestro/xctest_runner_logs"
FLOW="$HERE/flows/ui_interruption_preflight.yaml"

echo "[1/4] Generating + building AlertRepro for simulator $SIM_UDID"
(cd "$HERE" && xcodegen generate >/dev/null)
xcodebuild -project "$HERE/AlertRepro.xcodeproj" -scheme AlertRepro \
    -sdk iphonesimulator -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM_UDID" \
    -derivedDataPath "$HERE/build" build >/dev/null

APP="$HERE/build/Build/Products/Debug-iphonesimulator/AlertRepro.app"

echo "[2/4] Installing AlertRepro on $SIM_UDID"
xcrun simctl install "$SIM_UDID" "$APP"

echo "[3/4] Running regression flow via Maestro"
# Always use ./maestro from the repo root so we exercise the freshly-built
# runner checked in to maestro-ios-driver/src/main/resources/. A globally
# installed `maestro` CLI would carry its own stale runner zip.
if [[ -x "$REPO/maestro" ]]; then
    (cd "$REPO" && ./maestro test "$FLOW" >/dev/null)
elif command -v maestro >/dev/null 2>&1; then
    maestro test "$FLOW" >/dev/null
else
    echo "error: neither ./maestro in repo root nor 'maestro' on PATH is available" >&2
    exit 2
fi

LATEST_LOG="$(ls -t "$LOG_DIR"/*.log | head -1)"
echo "[4/4] Asserting preflight signature is absent in $(basename "$LATEST_LOG")"

needles=(
    'Check for interrupting elements affecting Window'
    'Found [0-9]+ interrupting element'
    'Invoking UI interruption monitors'
    'Alert from Application'
)

fail=0
for n in "${needles[@]}"; do
    hits=$(grep -cE "$n" "$LATEST_LOG" || true)
    if [[ "$hits" -gt 0 ]]; then
        echo "  FAIL: $hits hits for '$n'"
        fail=1
    else
        echo "  ok:   0 hits for '$n'"
    fi
done

if [[ "$fail" -ne 0 ]]; then
    echo
    echo "Apple's XCTest UI-interruption preflight fired during a Maestro gesture."
    echo "The swizzle in maestro-ios-xctest-runner/maestro-driver-iosUITests/"
    echo "Categories/XCUIApplication+Helper.m (+load method) regressed."
    exit 1
fi

echo
echo "[verify] preflight suppressed; swizzle is intact."
