#!/bin/bash

echo "Looking for the first available and installed iOS simulator..."

# Fetch the first available simulator from the list, regardless of device type or runtime
SIMULATOR_ID=$(xcrun simctl list --json devices | jq -r '
    .devices | to_entries[] | .value[] |
    select(.isAvailable == true) | .udid' | head -n 1)

# Check if a simulator is found
if [ -z "$SIMULATOR_ID" ]; then
    echo "Error: No available simulator found."
    exit 1
fi

echo "Found simulator with ID: $SIMULATOR_ID"

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