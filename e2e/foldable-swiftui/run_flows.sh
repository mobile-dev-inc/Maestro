#!/usr/bin/env bash
# Runs the foldable fixture's flows on a foldable simulator, in whatever pose it is in.
#
# The iPhone Duo (Xcode 27.1+, iOS 27.1 runtime) has two screens: the cover screen while closed, the
# inner screen while open. The flows must pass in every pose and orientation. Nothing in Xcode changes
# the pose from the command line, so set it with Simulator's Device Hub (hinge and orientation
# controls) between runs:
#
#   closed, flat, book (half open, portrait), tabletop (half open, landscape),
#   each in the orientations the pose allows.
#
# Not part of the CI suite: the GitHub runners have no foldable simulator.
#
# Prereqs: xcodegen and xcodebuild on PATH; the foldable simulator booted.
#
# Usage: e2e/foldable-swiftui/run_flows.sh <simulator-udid>

set -euo pipefail

SIM_UDID="${1:-}"
if [[ -z "$SIM_UDID" ]]; then
    echo "usage: $0 <simulator-udid>" >&2
    exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

echo "[1/3] Generating + building FoldableFixture for simulator $SIM_UDID"
(cd "$HERE" && xcodegen generate >/dev/null)
# SYMROOT pins the app under build/, whatever per-user build locations Xcode is set to.
xcodebuild -project "$HERE/FoldableFixture.xcodeproj" -scheme FoldableFixture \
    -sdk iphonesimulator -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM_UDID" \
    -derivedDataPath "$HERE/build" SYMROOT="$HERE/build/Build/Products" build >/dev/null

echo "[2/3] Installing FoldableFixture on $SIM_UDID"
xcrun simctl install "$SIM_UDID" "$HERE/build/Build/Products/Debug-iphonesimulator/FoldableFixture.app"

echo "[3/3] Running the flows"
# ./maestro rebuilds the CLI with the runner from this checkout; a globally installed maestro would
# carry its own runner.
if [[ -x "$REPO/maestro" ]]; then
    (cd "$REPO" && ./maestro --device "$SIM_UDID" test "$HERE/flows")
else
    maestro --device "$SIM_UDID" test "$HERE/flows"
fi
