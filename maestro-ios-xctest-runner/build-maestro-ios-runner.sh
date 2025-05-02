#!/usr/bin/env bash
set -euo pipefail

if [ "$(basename "$PWD")" != "Maestro" ]; then
	echo "This script must be run from the maestro root directory"
	exit 1
fi

DERIVED_DATA_PATH="${DERIVED_DATA_DIR:-driver-iPhoneSimulator}"
DESTINATION="${DESTINATION:-generic/platform=iOS Simulator}"
ARCHS="${ARCHS:-arm64}"

# Determine build output directory
if [[ "$DESTINATION" == *"iOS Simulator"* ]]; then
	BUILD_OUTPUT_DIR="Debug-iphonesimulator"
else
	BUILD_OUTPUT_DIR="Debug-iphoneos"
fi

rm -rf "$PWD/$DERIVED_DATA_PATH"
rm -rf "./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH"

mkdir -p "$PWD/$DERIVED_DATA_PATH"
mkdir -p "./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH"
mkdir -p "./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/$BUILD_OUTPUT_DIR"

xcodebuild clean build-for-testing \
  -project ./maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj \
  -derivedDataPath "$PWD/$DERIVED_DATA_PATH" \
  -scheme maestro-driver-ios \
  -destination "$DESTINATION" \
  ARCHS="$ARCHS"

## Copy built apps and xctestrun file
cp -r \
	"./$DERIVED_DATA_PATH/Build/Products/$BUILD_OUTPUT_DIR/maestro-driver-iosUITests-Runner.app" \
	"./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/maestro-driver-iosUITests-Runner.app"

cp -r \
	"./$DERIVED_DATA_PATH/Build/Products/$BUILD_OUTPUT_DIR/maestro-driver-ios.app" \
	"./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/maestro-driver-ios.app"

# Find and copy the .xctestrun file
XCTESTRUN_FILE=$(find "$PWD/$DERIVED_DATA_PATH/Build/Products" -name "*.xctestrun" | head -n 1)
cp "$XCTESTRUN_FILE" "./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/maestro-driver-ios-config.xctestrun"

WORKING_DIR=$PWD

OUTPUT_DIR=./$DERIVED_DATA_PATH/Build/Products/$BUILD_OUTPUT_DIR
cd $OUTPUT_DIR
zip -r "$WORKING_DIR/maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/$BUILD_OUTPUT_DIR/maestro-driver-iosUITests-Runner.zip" "./maestro-driver-iosUITests-Runner.app"
zip -r "$WORKING_DIR/maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/$BUILD_OUTPUT_DIR/maestro-driver-ios.zip" "./maestro-driver-ios.app"

# Clean up
cd $WORKING_DIR
rm -rf "./maestro-ios-driver/src/main/resources/$DERIVED_DATA_PATH/"*.app
rm -rf "$PWD/$DERIVED_DATA_PATH"