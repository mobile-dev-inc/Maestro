#!/usr/bin/env bash

DESTINATION="generic/platform=iOS Simulator" DERIVED_DATA_DIR="driver-iPhoneSimulator" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
DESTINATION="generic/platform=iphoneos" DERIVED_DATA_DIR="driver-iphoneos" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
