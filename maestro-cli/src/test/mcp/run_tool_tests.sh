#!/bin/bash
set -e

# Run MCP tool functionality tests
#
# These tests validate that tools execute without errors and return expected data types.
# They test the API functionality, not LLM behavior.
#
# Usage: ./run_tool_tests.sh [ios|android]

platform="${1:-ios}"

if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
    echo "usage: $0 [ios|android]"
    exit 1
fi

echo "🔧 Running MCP tool functionality tests for $platform"

# Get the script directory for relative paths
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Check if Maestro CLI is built
"$SCRIPT_DIR/setup/check-maestro-cli-built.sh"

# Ensure simulator/emulator is running (required for tool tests)
"$SCRIPT_DIR/setup/launch-simulator.sh" "$platform"

# Run the tool tests (from mcp directory so paths work correctly)
echo "🧪 Executing tool functionality tests..."
cd "$SCRIPT_DIR"
./tool-tests/test-all-mcp-tools.sh

echo "✅ Tool functionality tests completed!"