#!/usr/bin/env sh
set -eu

# Install all apps from apps/ directory (that was previously created with
# download_apps).
#
# Matches .apk files to install on Android devices and .app files to install on
# iOS simulators.

[ "$(basename "$PWD")" = "e2e" ] || { echo "must be run from e2e directory" && exit 1; }

platform="${1:-}"
if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
	echo "usage: $0 <android|ios>"
	exit 1
fi

command -v adb >/dev/null 2>&1 || { echo "adb is required" && exit 1; }

for file in ./apps/*; do
	filename="$(basename "$file")"

	extension="${file##*.}"
	if [ "$platform" = android ] && [ "$extension" = "apk" ]; then
		echo "install $filename"
		adb install -r "$file" >/dev/null || echo "adb: could not install $filename"
	elif [ "$platform" = ios ] && [ "$extension" = "app" ] && [ "$(uname)" = "Darwin" ]; then
		echo "install $filename"
		xcrun simctl install booted "$file" || echo "xcrun: could not install $filename"
	fi
done
