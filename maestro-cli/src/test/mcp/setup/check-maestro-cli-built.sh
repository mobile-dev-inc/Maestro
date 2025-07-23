#!/bin/bash
set -e

# Check if Maestro CLI is built for MCP testing
#
# This script verifies that the Maestro CLI has been built and is available
# at the expected location for MCP testing.
#
# Usage: ./check-maestro-cli-built.sh

# Get the script directory and find the repo root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAESTRO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"

# Check if Maestro CLI is built
MAESTRO_CLI_PATH="$MAESTRO_ROOT/maestro-cli/build/install/maestro/bin/maestro"
if [ ! -f "$MAESTRO_CLI_PATH" ]; then
    echo "❌ Error: Maestro CLI not found at expected location."
    echo "   MCP tests require the Maestro CLI to be built first."
    echo "   Please run: ./gradlew :maestro-cli:installDist"
    echo "   From the repository root: $MAESTRO_ROOT"
    exit 1
fi
