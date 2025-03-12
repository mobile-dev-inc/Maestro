#!/usr/bin/env bash
set -euo pipefail

if [ "$(basename "$PWD")" != "Maestro" ]; then
	echo "This script must be run from the maestro root directory"
	exit 1
fi

rm -rf ./build/Products

xcodebuild clean build-for-testing \
  -project ./maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj \
  -derivedDataPath "$PWD/build/Products" \
  -scheme maestro-driver-ios \
  -destination "generic/platform=iOS Simulator" \
  CODE_SIGNING_ALLOWED=NO ARCHS="x86_64 arm64" COMPILER_INDEX_STORE_ENABLE=NO

## Remove intermediates, output and copy runner in maestro-ios-driver
cp -r \
	./build/Products/Build/Products/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app \
	./maestro-ios-driver/src/main/resources/maestro-driver-iosUITests-Runner.app

cp -r \
	./build/Products/Build/Products/Debug-iphonesimulator/maestro-driver-ios.app \
	./maestro-ios-driver/src/main/resources/maestro-driver-ios.app

cp \
	./build/Products/Build/Products/*.xctestrun \
	./maestro-ios-driver/src/main/resources/maestro-driver-ios-config.xctestrun

(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-iosUITests-Runner.zip ./maestro-driver-iosUITests-Runner.app)
(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-ios.zip ./maestro-driver-ios.app)
rm -r ./maestro-ios-driver/src/main/resources/*.app
