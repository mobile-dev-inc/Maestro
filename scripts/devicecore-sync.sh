#!/usr/bin/env bash
# Builds+publishes maestro-device-core to mavenLocal and writes the exact published
# version into <maestro-repo-root>/devicecore.version, which maestro-orchestra's
# build.gradle.kts reads to resolve the dev.mobile.devicecore:* dependencies.
#
# Usage:
#   ./scripts/devicecore-sync.sh [path-to-device-core-repo]
#   DEVICECORE_DIR=/path/to/maestro-device-core ./scripts/devicecore-sync.sh
set -euo pipefail

DC="${1:-${DEVICECORE_DIR:-}}"
if [ -z "$DC" ]; then
    echo "Usage: $0 <path-to-maestro-device-core> (or set \$DEVICECORE_DIR)" >&2
    exit 1
fi
if [ ! -d "$DC" ]; then
    echo "error: device-core dir not found: $DC" >&2
    exit 1
fi

# Resolve maestro repo root as this script's parent-of-parent dir, regardless of CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAESTRO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Publishing device-core (implementation, drivers-core) to mavenLocal from $DC ..." >&2
( cd "$DC" && ./gradlew :implementation:publishToMavenLocal :drivers-core:publishToMavenLocal )

# Determine the exact published version. Prefer querying device-core's own gradle
# (do NOT guess from mavenLocal timestamps).
VER="$(cd "$DC" && ./gradlew -q :implementation:properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' | tr -d '[:space:]')"

M2_DIR="$HOME/.m2/repository/dev/mobile/devicecore/implementation"

if [ -z "$VER" ] || [ ! -d "$M2_DIR/$VER" ]; then
    # Fall back to reconstructing the version from device-core's git state.
    SHA="$(git -C "$DC" rev-parse --short=12 HEAD)"
    VER="0.1.0-$SHA"
    if [ -n "$(git -C "$DC" status --porcelain)" ]; then
        VER="${VER}-dirty"
    fi
fi

if [ ! -d "$M2_DIR/$VER" ]; then
    echo "error: could not verify published version — no directory at $M2_DIR/$VER" >&2
    exit 1
fi

echo -n "$VER" > "$MAESTRO_ROOT/devicecore.version"
echo "$VER"
