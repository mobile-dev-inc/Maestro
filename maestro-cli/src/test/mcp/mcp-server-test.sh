#!/bin/bash
set -e

CONFIG="maestro-cli/src/test/mcp/mcp-server-config.json"
SERVER="maestro-mcp"

failures=0

function inspector() {
  npx @modelcontextprotocol/inspector --cli --config "$CONFIG" --server "$SERVER" "$@"
}

# Helper function to run a test and track failures
function run_test() {
  local TEST_OUTPUT
  TEST_OUTPUT=$(./maestro-cli/src/test/mcp/test-mcp-tool.sh --quiet "$@" 2>&1)
  local STATUS=$?
  
  if [ $STATUS -eq 0 ]; then
    echo -e "\033[0;32mPASS\033[0m: $1"
  else
    echo -e "\033[0;31mFAIL\033[0m: $1"
    failures=$((failures+1))
  fi
}

# Helper function to run a test and get the response
function run_test_get_response() {
  ./maestro-cli/src/test/mcp/test-mcp-tool.sh "$@" 2>/dev/null
}

echo "Testing tools/list..."
LIST_RESPONSE=$(inspector --method tools/list)
if echo "$LIST_RESPONSE" | jq -e '.tools' > /dev/null; then
  echo -e "\033[0;32mPASS\033[0m: tools/list returned a tools array"
else
  echo -e "\033[0;31mFAIL\033[0m: tools/list did not return a tools array"
  failures=$((failures+1))
fi

run_test echo --arg "message=Hello from Inspector" --expect-contains "Hello from Inspector"

run_test list_devices --expect-type text

# Get device ID from start_device response
START_RESPONSE=$(run_test_get_response start_device --arg "platform=ios" --expect-contains "device_id")
DEVICE_ID=$(echo "$START_RESPONSE" | jq -r '.content[0].text | fromjson | .device_id')

if [ -n "$DEVICE_ID" ] && [ "$DEVICE_ID" != "null" ]; then
  run_test start_device --arg "platform=ios" --expect-contains "device_id"
  run_test take_screenshot --arg "device_id=$DEVICE_ID" --expect-type image
  run_test launch_app --arg "device_id=$DEVICE_ID" --arg "app_id=com.apple.Preferences" --expect-contains "Successfully launched app"
  
  # Test tap_on with Settings app UI element
  run_test tap_on --arg "device_id=$DEVICE_ID" --arg "selector=General" --expect-contains "Successfully tapped"
else
  echo -e "\033[0;31mFAIL\033[0m: Could not get device_id for device-dependent tests"
  failures=$((failures+1))
fi

if [[ $failures -eq 0 ]]; then
  echo -e "\n\033[0;32mAll tests passed!\033[0m"
else
  echo -e "\n\033[0;31m$failures test(s) failed.\033[0m"
fi

exit $failures
