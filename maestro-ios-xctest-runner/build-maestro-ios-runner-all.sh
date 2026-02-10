#!/usr/bin/env bash

DESTINATION="generic/platform=iOS Simulator" DERIVED_DATA_DIR="driver-iPhoneSimulator" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh

if [ -z "${DEVELOPMENT_TEAM:-}" ]; then
  echo "DEVELOPMENT_TEAM is not set, only building for iOS Simulator"
else
  DESTINATION="generic/platform=iphoneos" DERIVED_DATA_DIR="driver-iphoneos" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
fi
