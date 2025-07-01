#!/bin/bash
set -e

# Run MCP LLM behavior evaluations
#
# These tests validate that LLMs can properly use MCP tools, including reasoning,
# safety, and interaction patterns. They test client/server interaction and LLM capabilities.
#
# Usage: ./run_mcp_evals.sh [--with-apps] <eval-file1.yaml> [eval-file2.yaml] [...]

# Parse arguments
with_apps=false
eval_files=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --with-apps)
            with_apps=true
            shift
            ;;
        *.yaml)
            eval_files+=("$1")
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            echo "usage: $0 [--with-apps] <eval-file1.yaml> [eval-file2.yaml] [...]"
            exit 1
            ;;
    esac
done

if [ ${#eval_files[@]} -eq 0 ]; then
    echo "❌ Error: No eval files provided"
    echo "usage: $0 [--with-apps] <eval-file1.yaml> [eval-file2.yaml] [...]"
    echo ""
    echo "Available eval files:"
    find evals/ -name "*.yaml" 2>/dev/null | sed 's/^/  /' || echo "  (none found)"
    exit 1
fi

echo "🧠 Running MCP LLM behavior evaluations"
echo "📋 Eval files: ${eval_files[*]}"
echo "📱 With app setup: $with_apps"

# Get the script directory for relative paths
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG="$SCRIPT_DIR/mcp-server-config.json"

# Check if Maestro CLI is built
"$SCRIPT_DIR/setup/check-maestro-cli-built.sh"

# Ensure simulator is running (required for MCP evals that test device tools)
platform="ios"
"$SCRIPT_DIR/setup/launch-simulator.sh" "$platform"

# Optional app setup for complex evals
if [ "$with_apps" = true ]; then
    echo "📱 Setting up app environment for complex evaluations..."
    
    # Use setup utilities for app environment
    "$SCRIPT_DIR/setup/download-and-install-apps.sh" ios
    
    # Setup Wikipedia in a good state for hierarchy testing
    cd "$(dirname "$SCRIPT_DIR")/../../.."
    maestro test "$SCRIPT_DIR/setup/flows/setup-wikipedia-search-ios.yaml"
    maestro test "$SCRIPT_DIR/setup/flows/verify-ready-state.yaml"
    
    echo "✅ App environment ready for evaluations"
fi

# Run each eval file (from mcp directory so paths work correctly)
echo "🧪 Executing LLM behavior evaluations..."
cd "$SCRIPT_DIR"

eval_count=0
for eval_file in "${eval_files[@]}"; do
    eval_count=$((eval_count + 1))
    echo "📋 Running eval $eval_count: $eval_file"
    
    # Check if file exists, try relative to evals/ if not absolute
    if [ ! -f "$eval_file" ]; then
        if [ -f "evals/$eval_file" ]; then
            eval_file="evals/$eval_file"
        else
            echo "❌ Error: Eval file not found: $eval_file"
            exit 1
        fi
    fi
    
    echo "📋 Using config: $CONFIG"
    echo "📋 Using evals: $eval_file"
    
    # Run the evals using MCP inspector
    npx -y ./modelcontextprotocol-inspector-0.15.0.tgz --cli --evals "$eval_file" --config "$CONFIG" --server maestro-mcp
    
    echo "✅ Eval $eval_count completed: $eval_file"
done

echo "🎉 All LLM behavior evaluations completed successfully!"
echo "📊 Ran $eval_count eval file(s)"