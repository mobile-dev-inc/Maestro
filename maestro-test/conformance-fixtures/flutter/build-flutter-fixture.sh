#!/usr/bin/env bash
# Builds the Flutter conformance fixture into the bundled APK resource consumed by the harness.
#
# Like the React Native fixture, this is a standalone app with its own toolchain (Flutter SDK), so
# we build a release APK once and commit it — the conformance harness never needs Flutter at run
# time. Re-run whenever the Dart or native side changes.
#
# Requires the Flutter SDK on PATH (falls back to ~/Downloads/flutter/bin). arm64-v8a only, since the
# harness provisions arm64 emulators — keeps the committed APK small. Release uses the debug
# keystore (fine for a test fixture).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

if ! command -v flutter >/dev/null 2>&1; then
  if [ -x "$HOME/Downloads/flutter/bin/flutter" ]; then
    PATH="$HOME/Downloads/flutter/bin:$PATH"
  else
    echo "flutter not found on PATH (and ~/Downloads/flutter/bin missing)" >&2
    exit 1
  fi
fi
echo "Building Flutter fixture with $(flutter --version 2>/dev/null | head -1)"

flutter build apk --release --target-platform android-arm64

DEST="$HERE/../../src/main/resources/flutter-fixture.apk"
cp build/app/outputs/flutter-apk/app-release.apk "$DEST"
echo "Copied Flutter fixture APK -> $DEST"
