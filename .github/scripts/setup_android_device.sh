#!/bin/bash
set -euo pipefail

# Wait for the connected Android emulator to finish booting, then apply CI-specific
# pre-configuration that prevents Maestro flows from being blocked by:
#   - Chrome's first-run "Welcome / Make Chrome your own" UI
#   - GMS's "Google Location Accuracy" consent dialog (Android 14+ google_apis images)
#
# Both pre-configs are emulator/userdebug-only — real users hit these dialogs on their
# own devices and need driver-level fixes for them. This script is purely a CI bootstrap.
#
# Idempotent. Run after starting the emulator and before installing apps / running tests.

adb wait-for-device && echo 'Emulator device online'
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done;' && echo 'Emulator booted'

# Wait for the package manager service to be available
while true; do
    adb shell service list | grep 'package' && echo 'service "package" is active!' && break
    echo 'waiting for service "package" to start'
    sleep 1
done

# Skip Chrome first-run experience (Welcome / "Make Chrome your own" sign-in card
# surfaced on Android 35+). This is Chrome's own UI on launch, not a Maestro behaviour
# — Maestro just dispatches the VIEW intent in openLink. The setprop trick is userdebug-
# only, so it lives here in the AVD setup rather than in the maestro-client driver.
# Ref: https://stackoverflow.com/questions/33408138
adb shell 'echo "chrome --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
adb shell setprop debug.chrome.command_line /data/local/tmp/chrome-command-line
adb shell am force-stop com.android.chrome || true

# Pre-grant GMS "Google Location Accuracy" consent so FusedLocationProviderClient.setMockMode(true)
# in maestro-server doesn't trigger a system overlay during setLocation flows on the
# Android 14+ google_apis image. The flag lives in GMS's private content provider
# (content://com.google.settings/partner) which only `system` uid can write to —
# `adb root` (userdebug emulator only) gives us that. Real users on production-build
# phones can't root and will hit the dialog separately; that's a driver-side fix.
adb root
adb wait-for-device
adb shell content insert \
    --uri content://com.google.settings/partner \
    --bind name:s:network_location_opt_in --bind value:s:1 || true
adb shell content insert \
    --uri content://com.google.settings/partner \
    --bind name:s:use_location_for_services --bind value:s:1 || true

# Drop back to shell uid so subsequent steps (install_apps, run_tests, maestro driver
# via dadb) exercise the same code path real users hit on production-build devices.
adb unroot
adb wait-for-device
