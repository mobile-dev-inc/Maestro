#!/bin/bash
set -e

# Run MCP evaluation tests
#
# These tests validate MCP server behavior using LLM-based evaluations.
# They test actual task completion and response quality.
#
# Usage: ./run_mcp_evals.sh [ios|android]

platform="${1:-ios}"

if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
    echo "usage: $0 [ios|android]"
    exit 1
fi

echo "🔧 Running MCP evaluation tests for $platform"

# Get the script directory for relative paths
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Check if Maestro CLI is built
"$SCRIPT_DIR/setup/check-maestro-cli-built.sh"

# Run the evaluation tests (from mcp directory so paths work correctly)
echo "🧪 Executing MCP evaluation tests..."
cd "$SCRIPT_DIR"
npx -y mcp-server-tester@1.3.1 evals full-evals.yaml --server-config maestro-mcp.json || true

echo "✅ MCP evaluation tests completed!"