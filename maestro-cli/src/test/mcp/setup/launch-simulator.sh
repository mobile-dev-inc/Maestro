#!/bin/bash
set -e

# Launch simulator/emulator for MCP testing
#
# Checks if a simulator is running and launches if it's not running
#
# Usage: ./launch-simulator.sh <android|ios>

platform="${1:-}"

if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
    echo "usage: $0 <android|ios> [--auto-launch]"
    exit 1
fi

if [ "$platform" = "ios" ]; then
    if xcrun simctl list devices | grep -q "(Booted)"; then
        echo "✅ iOS simulator is already running" >&2
        device_id=$(xcrun simctl list devices | grep "(Booted)" | head -1 | grep -o '[A-F0-9-]\{36\}')
        echo "$device_id"
        exit 0
    fi
    
    # Find the first available iPhone simulator
    available_sim=$(xcrun simctl list devices | grep "iPhone" | grep -v "unavailable" | head -1 | sed 's/.*iPhone \([^(]*\).*/iPhone \1/' | sed 's/ *$//')
    
    if [ -n "$available_sim" ]; then
        echo "📱 Booting: $available_sim" >&2
        device_id=$(xcrun simctl list devices | grep "iPhone" | grep -v "unavailable" | head -1 | grep -o '[A-F0-9-]\{36\}')
        xcrun simctl boot "$device_id"
        xcrun simctl bootstatus "$device_id" > /dev/null
        echo "✅ iOS simulator launched successfully" >&2
    else
        echo "❌ Error: No available iOS simulators found" >&2
        exit 1
    fi
    
elif [ "$platform" = "android" ]; then
    if adb devices | grep -q "device$"; then
        echo "✅ Android emulator/device is connected" >&2
        device_id=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
    elif [ "$auto_launch" = true ]; then
        echo "🚀 Auto-launching Android emulator not implemented yet" >&2
        echo "   Please start an Android emulator manually" >&2
        exit 1
    else
        echo "❌ No Android emulator/device is connected" >&2
        echo "   Please start an Android emulator first" >&2
        echo "   Or connect a physical device" >&2
        exit 1
    fi
fi

echo "$device_id"