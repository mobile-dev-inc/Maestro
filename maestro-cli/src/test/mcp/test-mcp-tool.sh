#!/bin/bash
set -e

CONFIG="maestro-cli/src/test/mcp/mcp-server-config.json"
SERVER="maestro-mcp"
QUIET=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

function inspector() {
  npx @modelcontextprotocol/inspector --cli --config "$CONFIG" --server "$SERVER" "$@"
}

function print_usage() {
  echo "Usage: $0 <tool-name> [--expect-type text|image] [--expect-contains text] [--arg key=value ...]"
  echo
  echo "Options:"
  echo "  --arg key=value       Tool argument in key=value format (required if tool has arguments)"
  echo "  --expect-type type    [Optional] Expected response type (text or image, default: text)"
  echo "  --expect-contains     [Optional] Validate that response contains specific text (only for text type)"
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

# Run the tool
echo "Testing $TOOL..." >&2
RESPONSE=$(npx @modelcontextprotocol/inspector --cli --config "$CONFIG" --server "$SERVER" --method tools/call --tool-name "$TOOL" "${ARGS[@]}")
STATUS=$?

if [ $STATUS -ne 0 ]; then
  echo -e "${RED}FAIL${NC}: Tool execution failed" >&2
  exit 1
fi

# Always output the response to stdout for parsing
echo "$RESPONSE"

# Validate response type if specified
if [ -n "$EXPECT_TYPE" ]; then
  CONTENT_TYPE=$(echo "$RESPONSE" | jq -r '.content[0].type // "text"')
  if [ "$CONTENT_TYPE" != "$EXPECT_TYPE" ]; then
    echo -e "${RED}FAIL${NC}: Expected content type $EXPECT_TYPE but got $CONTENT_TYPE" >&2
    exit 1
  fi
fi

# Validate response content if specified
if [ -n "$EXPECT_CONTAINS" ]; then
  CONTENT=$(echo "$RESPONSE" | jq -r '.content[0].text')
  if ! echo "$CONTENT" | grep -q "$EXPECT_CONTAINS"; then
    echo -e "${RED}FAIL${NC}: Response does not contain expected text: $EXPECT_CONTAINS" >&2
    exit 1
  fi
fi

echo -e "${GREEN}PASS${NC}: $TOOL test completed successfully" >&2
exit 0 