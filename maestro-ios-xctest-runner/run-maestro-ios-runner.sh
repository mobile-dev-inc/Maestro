#!/usr/bin/env bash
set -euo pipefail

if [ "$(basename "$PWD")" != "maestro" ]; then
	echo "This script must be run from the maestro root directory"
	exit 1
fi

DEVICE="${1:-}"
if [ -z "$DEVICE" ]; then
	DEVICE="iPhone 15"
	echo "No device passed, will default to $DEVICE"
fi

xctestrun_file="$(find ./build/Products -maxdepth 1 -name '*.xctestrun' -print)"
file_count="$(echo "$xctestrun_file" | wc -l | tr -d '[:blank:]')"
if [ "$file_count" = 1 ]; then
	echo "xctestrun file found: $xctestrun_file"
elif [ "$file_count" = 0 ]; then
	echo "xctestrun file not found in ./build/Products. Did you build the runner?"
	exit 1
else
	echo "Multiple ($file_count) xctestrun files found in ./build/Products. Only 1 can be present."
	exit 1
fi

xcodebuild test-without-building \
	-xctestrun "$xctestrun_file" \
	-destination "platform=iOS Simulator,name=$DEVICE" \
	-destination-timeout 1
