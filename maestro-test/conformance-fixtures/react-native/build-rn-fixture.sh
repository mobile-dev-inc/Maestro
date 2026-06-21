#!/usr/bin/env bash
# Builds the React Native conformance fixture into the bundled APK resource consumed by the harness.
#
# Unlike the native/compose fixtures (on-demand Gradle modules), the RN fixture is a standalone app
# with its own toolchain (Metro/Hermes, RN Gradle plugin). To keep the conformance harness free of
# the RN dependency tree at run time, we build a release APK with the JS bundle embedded ONCE and
# commit it. Re-run this script whenever the RN fixture (JS or native) changes.
#
# Requires: node + npm, Android SDK (ANDROID_HOME), NDK + CMake (RN New Architecture compiles a
# small C++ layer). Output APK is signed with the RN debug keystore — fine for a test fixture.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# Metro (RN 0.86) calls util.styleText, which needs Node >= 20.12. If the active node is older,
# fall back to a Homebrew node. Build with --no-daemon so the chosen node (PATH) is actually used
# by the React Native Gradle plugin (a reused daemon would spawn whatever node it started with).
node_major_minor() { node -e 'const [a,b]=process.versions.node.split(".");process.stdout.write(a+"."+b)'; }
if [ "$(printf '%s\n20.12\n' "$(node_major_minor)" | sort -V | head -1)" != "20.12" ]; then
  for cand in /opt/homebrew/opt/node/bin /opt/homebrew/bin /usr/local/bin; do
    if [ -x "$cand/node" ]; then PATH="$cand:$PATH"; break; fi
  done
fi
echo "Building RN fixture with node $(node -v)"

[ -d node_modules ] || npm install

(cd android && ./gradlew assembleRelease --no-daemon)

DEST="$HERE/../../src/main/resources/react-native-fixture.apk"
cp android/app/build/outputs/apk/release/app-release.apk "$DEST"
echo "Copied RN fixture APK -> $DEST"
