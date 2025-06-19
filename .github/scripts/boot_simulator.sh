#!/bin/bash

# Specify the device type and runtime as per your requirements
DEVICE_TYPE="iPhone 15 Pro"
RUNTIME="iOS17.5"

# Create a unique identifier for the new simulator to avoid naming conflicts
SIMULATOR_NAME="Simulator_$(uuidgen)"

echo "Creating a new iOS simulator: $SIMULATOR_NAME"

# Create the simulator
simulator_id=$(xcrun simctl create "$SIMULATOR_NAME" "$DEVICE_TYPE" $RUNTIME)
echo "Simulator ID: $simulator_id created."

# Boot the simulator
echo "Booting the simulator..."
xcrun simctl boot "$simulator_id"

# Wait for the simulator to be fully booted
while true; do
    # Check the current state of the simulator
    state=$(xcrun simctl list | grep "$simulator_id" | grep -o "Booted" || true)

    if [ "$state" == "Booted" ]; then
        echo "Simulator $SIMULATOR_NAME is now ready."
        break
    else
        echo "Waiting for the simulator to be ready..."
        sleep 5 # sleep for 5 seconds before checking again to avoid spamming
    fi
done