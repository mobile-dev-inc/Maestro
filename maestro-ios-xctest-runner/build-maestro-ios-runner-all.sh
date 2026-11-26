#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

DESTINATION="generic/platform=iOS Simulator" DERIVED_DATA_DIR="driver-iPhoneSimulator" "$SCRIPT_DIR/build-maestro-ios-runner.sh"

if [ -z "${DEVELOPMENT_TEAM:-}" ]; then
  echo "DEVELOPMENT_TEAM is not set, only building for iOS Simulator"
else
  DESTINATION="generic/platform=iphoneos" DERIVED_DATA_DIR="driver-iphoneos" "$SCRIPT_DIR/build-maestro-ios-runner.sh"
fi
