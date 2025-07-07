#!/bin/bash
set -e

CONFIG="maestro-cli/src/test/mcp/mcp-server-config.json"
SERVER="maestro-mcp"

failures=0
tests_run=0

function inspector() {
  npx @modelcontextprotocol/inspector --cli --config "$CONFIG" --server "$SERVER" "$@"
}

# Helper function to run a test and track failures
function run_test() {
  local TEST_OUTPUT
  TEST_OUTPUT=$(./maestro-cli/src/test/mcp/test-mcp-tool.sh --quiet "$@" 2>&1)
  local STATUS=$?
  tests_run=$((tests_run+1))
  
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

echo "=== MCP Server Integration Tests ==="

# Check if MAESTRO_API_KEY is set for API-dependent tools
if [ -z "$MAESTRO_API_KEY" ]; then
  echo -e "\033[0;33mWARNING\033[0m: MAESTRO_API_KEY not set. query_docs and cheat_sheet tools will be skipped."
  SKIP_API_TOOLS=true
else
  echo -e "\033[0;32mINFO\033[0m: MAESTRO_API_KEY is set. All tools will be tested."
  SKIP_API_TOOLS=false
fi

echo ""
echo "Testing tools/list..."
LIST_RESPONSE=$(inspector --method tools/list)
if echo "$LIST_RESPONSE" | jq -e '.tools' > /dev/null; then
  echo -e "\033[0;32mPASS\033[0m: tools/list returned a tools array"
  # Display available tools
  echo "Available tools:"
  echo "$LIST_RESPONSE" | jq -r '.tools[].name' | sed 's/^/  - /'
else
  echo -e "\033[0;31mFAIL\033[0m: tools/list did not return a tools array"
  failures=$((failures+1))
fi

echo ""
echo "=== Testing Device Management ==="
run_test list_devices --expect-type text

# Get device ID from start_device response
START_RESPONSE=$(run_test_get_response start_device --arg "platform=ios" --expect-contains "device_id")
DEVICE_ID=$(echo "$START_RESPONSE" | jq -r '.content[0].text | fromjson | .device_id')

if [ -n "$DEVICE_ID" ] && [ "$DEVICE_ID" != "null" ]; then
  echo -e "\033[0;32mINFO\033[0m: Using device_id: $DEVICE_ID"
  
  # Test device interaction tools
  echo ""
  echo "=== Testing Device Interaction ==="
  run_test start_device --arg "platform=ios" --expect-contains "device_id"
  run_test take_screenshot --arg "device_id=$DEVICE_ID" --expect-type image
  run_test inspect_view_hierarchy --arg "device_id=$DEVICE_ID" --expect-contains "elements"
  run_test inspect_view_hierarchy --arg "device_id=$DEVICE_ID" --arg "interactive_only=true" --expect-contains "elements"
  
  # Test app management
  echo ""
  echo "=== Testing App Management ==="
  run_test launch_app --arg "device_id=$DEVICE_ID" --arg "appId=com.apple.Preferences" --expect-contains "successfully"
  
  # Test UI interaction tools
  echo ""
  echo "=== Testing UI Interaction ==="
  run_test tap_on --arg "device_id=$DEVICE_ID" --arg "text=General" --expect-contains "success"
  run_test input_text --arg "device_id=$DEVICE_ID" --arg "text=test" --expect-contains "success"
  run_test back --arg "device_id=$DEVICE_ID" --expect-contains "success"
  run_test stop_app --arg "device_id=$DEVICE_ID" --arg "appId=com.apple.Preferences" --expect-contains "success"
  
  # Test flow tools
  echo ""
  echo "=== Testing Flow Tools ==="
  
  # Test syntax checking
  run_test check_flow_syntax --arg "flow_yaml=appId:test" --expect-contains "valid"
  run_test check_flow_syntax --arg "flow_yaml=invalid[" --expect-contains "valid"
  
  # Test flow execution with simple command (expect failure since it's just config)
  run_test run_flow --arg "device_id=$DEVICE_ID" --arg "flow_yaml=appId:com.apple.Preferences" --expect-contains "Failed"
  
  # Test flow files execution (using test files if they exist)
  if [ -f "maestro-cli/src/test/mcp/flow1.yaml" ] && [ -f "maestro-cli/src/test/mcp/flow2.yaml" ]; then
    run_test run_flow_files --arg "device_id=$DEVICE_ID" --arg "flow_files=maestro-cli/src/test/mcp/flow1.yaml,maestro-cli/src/test/mcp/flow2.yaml" --expect-contains "success"
  else
    echo -e "\033[0;33mSKIP\033[0m: run_flow_files (test files not found)"
  fi
  
else
  echo -e "\033[0;31mFAIL\033[0m: Could not get device_id for device-dependent tests"
  failures=$((failures+1))
fi

# Test API-dependent tools
echo ""
echo "=== Testing API Tools ==="
if [ "$SKIP_API_TOOLS" = true ]; then
  echo -e "\033[0;33mSKIP\033[0m: query_docs (MAESTRO_API_KEY not set)"
  echo -e "\033[0;33mSKIP\033[0m: cheat_sheet (MAESTRO_API_KEY not set)"
else
  run_test query_docs --arg "question=How do I tap?" --expect-type text
  run_test cheat_sheet --expect-type text
fi

echo ""
echo "=== Test Summary ==="
echo "Tests run: $tests_run"
if [[ $failures -eq 0 ]]; then
  echo -e "\033[0;32mAll tests passed!\033[0m"
else
  echo -e "\033[0;31m$failures test(s) failed.\033[0m"
fi

exit $failures
