#!/bin/bash
set -e

CONFIG="maestro-cli/src/test/mcp/mcp-server-config.json"
SERVER="maestro-mcp"
QUIET=false
DEBUG=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

function debug() {
  if [ "$DEBUG" = true ]; then
    echo -e "${YELLOW}[DEBUG]${NC} $*" >&2
  fi
}

function inspector() {
  npx @modelcontextprotocol/inspector --cli --config "$CONFIG" --server "$SERVER" "$@"
}

function print_usage() {
  echo "Usage: $0 <tool-name> [--expect-type text|image] [--expect-contains text] [--arg key=value ...] [--debug]"
  echo
  echo "Options:"
  echo "  --arg key=value       Tool argument in key=value format (required if tool has arguments)"
  echo "  --expect-type type    [Optional] Expected response type (text or image, default: text)"
  echo "  --expect-contains     [Optional] Validate that response contains specific text (only for text type)"
  echo "  --debug              [Optional] Enable debug output"
  echo
  echo "Example:"
  echo "  $0 list_devices"
  echo "  $0 start_device --arg device_id=5E7F44E1"
  echo "  $0 launch_app --arg device_id=5E7F44E1 --arg app_id=com.apple.mobilesafari"
  echo "  $0 take_screenshot --arg device_id=5E7F44E1 --expect-type image"
  exit 1
}

# Parse arguments
ARGS=()
TOOL=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --quiet)
      QUIET=true
      shift
      ;;
    --debug)
      DEBUG=true
      shift
      ;;
    --arg)
      KEY="${2%%=*}"
      VALUE="${2#*=}"
      ARGS+=("--tool-arg" "$KEY=$VALUE")
      shift 2
      ;;
    --expect-contains)
      EXPECT_CONTAINS="$2"
      shift 2
      ;;
    --expect-type)
      EXPECT_TYPE="$2"
      shift 2
      ;;
    -*)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
    *)
      if [ -z "$TOOL" ]; then
        TOOL="$1"
      else
        echo "Tool name already specified: $TOOL" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [ -z "$TOOL" ]; then
  print_usage
fi

# Construct the full command for debugging
FULL_CMD="npx @modelcontextprotocol/inspector --cli --config \"$CONFIG\" --server \"$SERVER\" --method tools/call --tool-name \"$TOOL\" ${ARGS[*]}"
debug "Executing command: $FULL_CMD"

# Run the tool
echo "Testing $TOOL..." >&2

# Capture both stdout and stderr
debug "Command output:"
OUTPUT=$(eval "$FULL_CMD" 2>&1)
STATUS=$?

# Show output only once
echo "$OUTPUT"

if [ $STATUS -ne 0 ]; then
  echo -e "${RED}FAIL${NC}: Tool execution failed with status $STATUS" >&2
  debug "Failed command: $FULL_CMD"
  exit 1
fi

echo -e "${GREEN}PASS${NC}: $TOOL test completed successfully" >&2
exit 0 