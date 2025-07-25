#!/usr/bin/env sh
set -eu

# Download apps from URLs listed in manifest.txt.
#
# We assume that if the downloaded file is a zip file, it's an iOS app and must
# be unzipped.

[ "$(basename "$PWD")" = "e2e" ] || { echo "must be run from e2e directory" && exit 1; }

command -v curl >/dev/null 2>&1 || { echo "curl is required" && exit 1; }

mkdir -p ./apps
while read -r url; do
	echo "download $url"
	app_file="$(curl -fsSL --output-dir ./apps --write-out "%{filename_effective}" -OJ "$url")"
	extension="${app_file##*.}"
	if [ "$extension" = "zip" ]; then
		unzip -qq -o -d ./apps "$app_file" -x "__MACOSX/*"
	fi
done <manifest.txt
