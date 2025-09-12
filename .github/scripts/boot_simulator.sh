#!/bin/bash

echo "Looking for the first available and installed iOS simulator..."

# Fetch the first available iPhone simulator's ID and name
SIMULATOR_INFO=$(xcrun simctl list --json devices | jq -r '
    .devices | to_entries[] | .value[] |
    select(.isAvailable == true and .state != "Booted" and (.name | type == "string" and test("iPhone"))) |
    "\(.udid) \(.name)"' | head -n 1)

# Extract the simulator's ID and name
SIMULATOR_ID=$(echo "$SIMULATOR_INFO" | awk '{print $1}')
SIMULATOR_NAME=$(echo "$SIMULATOR_INFO" | cut -d' ' -f2-)

# Check if a simulator is found
if [ -z "$SIMULATOR_ID" ]; then
    echo "Error: No available simulator found."
    exit 1
fi

echo "Found simulator: $SIMULATOR_NAME (ID: $SIMULATOR_ID)"

# Boot the simulator
echo "Booting simulator..."
xcrun simctl boot "$SIMULATOR_ID"

# Wait for the simulator to fully boot
echo "Waiting for the simulator to boot..."
while true; do
    # Check the current state of the simulator
    state=$(xcrun simctl list --json devices | jq -r --arg SIMULATOR_ID "$SIMULATOR_ID" '
        .devices | to_entries[] | .value[] |
        select(.udid == $SIMULATOR_ID) | .state')

    if [ "$state" == "Booted" ]; then
        echo "Simulator with ID $SIMULATOR_ID is now ready."
        break
    else
        echo "Current state: $state. Waiting..."
        sleep 5
    fi
done