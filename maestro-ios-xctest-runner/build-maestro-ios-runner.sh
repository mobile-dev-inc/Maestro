#!/usr/bin/env sh

set -eu

if [ "$(basename "$PWD")" != "maestro" ]; then
  echo "This script must be run from the maestro root directory"
  exit 1
fi

## Build the UI test

rm -rf ./build/Products || exit 1
xcodebuild ARCHS="x86_64 arm64" \
  ONLY_ACTIVE_ARCH=NO \
  -project ./maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj \
  -scheme maestro-driver-ios \
  -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" \
  -IDEBuildLocationStyle=Custom \
  -IDECustomBuildLocationType=Absolute \
  -IDECustomBuildProductsPath="$PWD/build/Products" \
  build-for-testing || exit 1

## Remove intermediates, output and copy runner in maestro-ios-driver
mv "$PWD"/build/Products/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app ./maestro-ios-driver/src/main/resources/maestro-driver-iosUITests-Runner.app || exit 1

mv "$PWD"/build/Products/Debug-iphonesimulator/maestro-driver-ios.app ./maestro-ios-driver/src/main/resources/maestro-driver-ios.app || exit 1

mv "$PWD"/build/Products/*.xctestrun ./maestro-ios-driver/src/main/resources/maestro-driver-ios-config.xctestrun || exit 1

(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-iosUITests-Runner.zip ./maestro-driver-iosUITests-Runner.app) || exit 1
(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-ios.zip ./maestro-driver-ios.app) || exit 1
rm -r ./maestro-ios-driver/src/main/resources/*.app || exit 1
