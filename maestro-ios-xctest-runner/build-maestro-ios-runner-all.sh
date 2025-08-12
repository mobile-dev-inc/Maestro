#!/usr/bin/env bash

DESTINATION="generic/platform=iOS Simulator" DERIVED_DATA_DIR="driver-iPhoneSimulator" ARCHS="x86_64 arm64" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
DESTINATION="generic/platform=iphoneos" DERIVED_DATA_DIR="driver-iphoneos" ARCHS="arm64" $PWD/maestro-ios-xctest-runner/build-maestro-ios-runner.sh
