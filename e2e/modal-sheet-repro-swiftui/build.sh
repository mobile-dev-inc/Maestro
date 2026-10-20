#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
e2e_root="$(cd "$here/.." && pwd)"
xcodebuild -project "$here/ModalSheetRepro.xcodeproj" -scheme ModalSheetRepro \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$here/build" \
  ARCHS='arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO \
  SWIFT_OPTIMIZATION_LEVEL=-O DEBUG_INFORMATION_FORMAT=dwarf-with-dsym \
  SWIFT_SERIALIZE_DEBUGGING_OPTIONS=NO COPY_PHASE_STRIP=YES build
mkdir -p "$e2e_root/apps"
rm -rf "$e2e_root/apps/ModalSheetRepro.app"
ditto "$here/build/Build/Products/Debug-iphonesimulator/ModalSheetRepro.app" \
  "$e2e_root/apps/ModalSheetRepro.app"
