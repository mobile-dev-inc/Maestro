#!/usr/bin/env bash
set -euo pipefail

if [ "$(basename "$PWD")" != "maestro" ]; then
	echo "This script must be run from the maestro root directory"
	exit 1
fi

rm -rf ./build/Products

xcodebuild \
	ARCHS="x86_64 arm64" \
	ONLY_ACTIVE_ARCH=NO \
	-project ./maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj \
	-scheme maestro-driver-ios \
	-sdk iphonesimulator \
	-destination "generic/platform=iOS Simulator" \
	-IDEBuildLocationStyle=Custom \
	-IDECustomBuildLocationType=Absolute \
	-IDECustomBuildProductsPath="$PWD/build/Products" \
	build-for-testing

## Remove intermediates, output and copy runner in maestro-ios-driver
cp -r \
	./build/Products/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app \
	./maestro-ios-driver/src/main/resources/maestro-driver-iosUITests-Runner.app

cp -r \
	./build/Products/Debug-iphonesimulator/maestro-driver-ios.app \
	./maestro-ios-driver/src/main/resources/maestro-driver-ios.app

cp \
	./build/Products/*.xctestrun \
	./maestro-ios-driver/src/main/resources/maestro-driver-ios-config.xctestrun

(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-iosUITests-Runner.zip ./maestro-driver-iosUITests-Runner.app)
(cd ./maestro-ios-driver/src/main/resources && zip -r maestro-driver-ios.zip ./maestro-driver-ios.app)
rm -r ./maestro-ios-driver/src/main/resources/*.app
