#!/usr/bin/env bash

# iPhone
DESTINATION="generic/platform=iOS Simulator" DERIVED_DATA_DIR="driver-iPhoneSimulator" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh

# AppleTV
DESTINATION="generic/platform=tvOS Simulator" DERIVED_DATA_DIR="driver-appletvSimulator" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh

if [ -z "${DEVELOPMENT_TEAM:-}" ]; then
  echo "DEVELOPMENT_TEAM is not set, only building for iOS Simulator"
else
  # iPhone
  DESTINATION="generic/platform=iphoneos" DERIVED_DATA_DIR="driver-iphoneos" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
  
  # AppleTV
  DESTINATION="generic/platform=appletvOS" DERIVED_DATA_DIR="driver-appletvOS" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
fi
