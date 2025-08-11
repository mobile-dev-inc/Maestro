#!/bin/bash
set -e

# Download and install apps for MCP testing
#
# Uses the existing e2e infrastructure to download and install test apps
# on the specified platform.
#
# Usage: ./download-and-install-apps.sh <android|ios>

platform="${1:-}"

if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
    echo "usage: $0 <android|ios>"
    exit 1
fi

echo "📥 Setting up apps for MCP testing on $platform"

# Get the script directory and find e2e directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAESTRO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
E2E_DIR="$MAESTRO_ROOT/e2e"

# Check if we can find the e2e directory
if [ ! -d "$E2E_DIR" ]; then
    echo "❌ Error: Could not find e2e directory at $E2E_DIR"
    exit 1
fi

echo "📂 Using e2e directory: $E2E_DIR"

# Step 1: Download apps using e2e infrastructure
echo "📥 Downloading test apps..."
cd "$E2E_DIR"
./download_apps

# Step 2: Install apps for the specified platform
echo "📱 Installing apps on $platform..."
./install_apps "$platform"

echo "✅ Apps ready for MCP testing on $platform"