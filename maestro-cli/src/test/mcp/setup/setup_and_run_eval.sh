#!/bin/bash
set -e

# Run MCP LLM behavior evaluations
#
# These tests validate that LLMs can properly use MCP tools, including reasoning,
# safety, and interaction patterns. They test client/server interaction and LLM capabilities.
#
# Usage: ./run_mcp_evals.sh [--app mobilesafari|wikipedia|demo_app] <eval-file1.yaml> [eval-file2.yaml] [...]

# Parse arguments
app_setup="none"  # Default to clean home screen
eval_files=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --app)
            app_setup="$2"
            if [[ ! "$app_setup" =~ ^(none|mobilesafari|wikipedia|demo_app)$ ]]; then
                echo "Error: --app must be one of: none, mobilesafari, wikipedia, demo_app"
                exit 1
            fi
            shift 2
            ;;
        *.yaml)
            eval_files+=("$1")
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            echo "usage: $0 [--app mobilesafari|wikipedia|demo_app] <eval-file1.yaml> [eval-file2.yaml] [...]"
            exit 1
            ;;
    esac
done

if [ ${#eval_files[@]} -eq 0 ]; then
    echo "❌ Error: No eval files provided"
    echo "usage: $0 [--app mobilesafari|wikipedia|demo_app] <eval-file1.yaml> [eval-file2.yaml] [...]"
    echo "       Default app setup: none (clean home screen)"
    echo ""
    echo "Available eval files:"
    find evals/ -name "*.yaml" 2>/dev/null | sed 's/^/  /' || echo "  (none found)"
    exit 1
fi

# Get the script directory for relative paths
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG="$SCRIPT_DIR/mcp-server-config.json"

# Check if Maestro CLI is built
"$SCRIPT_DIR/setup/check-maestro-cli-built.sh"

# Ensure simulator is running (required for MCP evals that test device tools)
platform="ios"
"$SCRIPT_DIR/setup/launch-simulator.sh" "$platform"

# App setup based on chosen option
case "$app_setup" in
    "none")
        echo "📱 No app setup - using clean simulator home screen"
        ;;
    "mobilesafari")
        echo "📱 Launching Mobile Safari for evaluations..."
        cd "$(dirname "$SCRIPT_DIR")/../../.."
        maestro test "$SCRIPT_DIR/setup/flows/launch-safari-ios.yaml"
        echo "✅ Mobile Safari ready for evaluations"
        ;;
    "wikipedia")
        echo "📱 Setting up Wikipedia app environment for complex evaluations..."
        
        # Use setup utilities for app environment
        "$SCRIPT_DIR/setup/download-and-install-apps.sh" ios
        
        # Setup Wikipedia in a good state for hierarchy testing
        cd "$(dirname "$SCRIPT_DIR")/../../.."
        maestro test "$SCRIPT_DIR/setup/flows/setup-wikipedia-search-ios.yaml"
        maestro test "$SCRIPT_DIR/setup/flows/verify-ready-state.yaml"
        
        echo "✅ Wikipedia app environment ready for evaluations"
        ;;
    "demo_app")
        echo "📱 Launching Demo App for evaluations..."

                # Use setup utilities for app environment
        "$SCRIPT_DIR/setup/download-and-install-apps.sh" ios

        cd "$(dirname "$SCRIPT_DIR")/../../.."
        maestro test "$SCRIPT_DIR/setup/flows/launch-demo-app-ios.yaml"
        echo "✅ Demo App ready for evaluations"
        ;;
esac

# Run each eval file (from mcp directory so paths work correctly)
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
    
    # Run the evals using MCP inspector
    npx -y mcp-server-tester@1.3.1 evals "$eval_file" --server-config "$CONFIG"
done
