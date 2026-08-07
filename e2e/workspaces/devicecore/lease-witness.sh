#!/usr/bin/env bash
# lease-witness.sh — prints the current UiAutomation/accessibility slot owner from
# system_server's own vantage point (dumpsys accessibility), independent of any
# on-device LEASE_TIMING log line.
#
# Run this on a SEPARATE cycle from a timed acceptance run so sampling it never
# inflates the latency number being measured. It only reads state; it never
# acquires or releases anything itself.
#
# Usage: ./lease-witness.sh [adb serial]
#   If a serial is given, it's passed to `adb -s <serial>`. Otherwise this uses
#   whatever device `adb` picks by default (fine when exactly one is attached,
#   e.g. the single booted emulator-5554 this prototype targets).

set -euo pipefail

serial="${1:-}"
adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd=(adb -s "$serial")
fi

"${adb_cmd[@]}" shell dumpsys accessibility \
  | grep -iE "uiautomation|Ui Automation|registered|serviceInfo" \
  || true
