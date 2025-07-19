#!/bin/bash
set -e

# Launch simulator/emulator for MCP testing
#
# Checks if a simulator is running and optionally launches one if not.
#
# Usage: ./launch-simulator.sh <android|ios> [--auto-launch]

platform="${1:-}"
auto_launch=false

if [ "${2:-}" = "--auto-launch" ]; then
    auto_launch=true
fi

if [ "$platform" != "android" ] && [ "$platform" != "ios" ]; then
    echo "usage: $0 <android|ios> [--auto-launch]"
    exit 1
fi

echo "📱 Checking $platform simulator/emulator status"

if [ "$platform" = "ios" ]; then
    if xcrun simctl list devices | grep -q "(Booted)"; then
        echo "✅ iOS simulator is already running"
        xcrun simctl list devices | grep "(Booted)" | head -1
    elif [ "$auto_launch" = true ]; then
        echo "🚀 Launching iOS simulator..."
        
        # Find the first available iPhone simulator
        available_sim=$(xcrun simctl list devices | grep "iPhone" | grep -v "unavailable" | head -1 | sed 's/.*iPhone \([^(]*\).*/iPhone \1/' | sed 's/ *$//')
        
        if [ -n "$available_sim" ]; then
            echo "📱 Booting: $available_sim"
            xcrun simctl boot "$available_sim"
            xcrun simctl bootstatus "$available_sim"
            echo "✅ iOS simulator launched successfully"
        else
            echo "❌ Error: No available iOS simulators found"
            exit 1
        fi
    else
        echo "❌ No iOS simulator is running"
        
        # Show available simulators
        available_sim=$(xcrun simctl list devices | grep "iPhone" | grep -v "unavailable" | head -1 | sed 's/.*iPhone \([^(]*\).*/iPhone \1/' | sed 's/ *$//')
        if [ -n "$available_sim" ]; then
            echo "   You can boot one with: xcrun simctl boot \"$available_sim\""
            echo "   Or use: $0 ios --auto-launch"
        fi
        exit 1
    fi
    
elif [ "$platform" = "android" ]; then
    if adb devices | grep -q "device$"; then
        echo "✅ Android emulator/device is connected"
        adb devices | grep "device$" | head -1
    elif [ "$auto_launch" = true ]; then
        echo "🚀 Auto-launching Android emulator not implemented yet"
        echo "   Please start an Android emulator manually"
        exit 1
    else
        echo "❌ No Android emulator/device is connected"
        echo "   Please start an Android emulator first"
        echo "   Or connect a physical device"
        exit 1
    fi
fi

echo "✅ $platform simulator/emulator ready for MCP testing"