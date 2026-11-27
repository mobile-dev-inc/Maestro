#!/bin/bash
#
# Collects what an iOS simulator and its host were doing, for a CI job that failed.
#
# ~/.maestro holds only Maestro's side of a run: when the driver does not start in time,
# maestro.log shows nothing but connection retries. The cause is on the simulator or in
# xcodebuild, so this gathers their logs.
#
# Usage: collect_simulator_diagnostics.sh <simulator-udid> <output-dir>
#
# Best effort: every step tolerates failure, so one missing log never hides the rest,
# and the script always exits 0.

set -u

SIM_UDID="${1:?usage: $0 <simulator-udid> <output-dir>}"
OUT="${2:?usage: $0 <simulator-udid> <output-dir>}"
# How far back the simulator's unified log is read. Covers the longest iOS job.
LOG_WINDOW="${LOG_WINDOW:-60m}"

mkdir -p "$OUT"

step() {
    echo "[collect_simulator_diagnostics] $1"
}

step "Simulator and runtime state"
{
    echo "## simctl list devices"
    xcrun simctl list devices
    echo
    echo "## simctl bootstatus (non-blocking)"
    # Without -b this reports the current boot phase and returns: is first-boot setup
    # (data migration) still running?
    xcrun simctl bootstatus "$SIM_UDID"
    echo
    echo "## simctl list runtimes"
    xcrun simctl list runtimes
} > "$OUT/simctl-state.txt" 2>&1

step "Host processes involved in starting the driver"
# How long each has been running shows whether an xcodebuild from an earlier step is
# still alive and holding the simulator. (pgrep cannot print elapsed time.)
# shellcheck disable=SC2009
ps -axo pid,ppid,etime,%cpu,rss,command \
    | grep -E "xcodebuild|xctest|XCTRunner|Simulator|simctl|testmanagerd|maestro" \
    | grep -v grep > "$OUT/host-processes.txt" 2>&1

step "Host load"
{
    uptime
    echo
    vm_stat
} > "$OUT/host-load.txt" 2>&1

step "Simulator screenshot"
xcrun simctl io "$SIM_UDID" screenshot "$OUT/simulator-screenshot.png" > /dev/null 2>&1 \
    || echo "screenshot failed" > "$OUT/simulator-screenshot-failed.txt"

step "Simulator unified log (last $LOG_WINDOW)"
# Hundreds of MB uncompressed even without debug-level messages; a few MB gzipped.
xcrun simctl spawn "$SIM_UDID" log show --last "$LOG_WINDOW" --style compact --info 2>&1 \
    | gzip > "$OUT/simulator-unified.log.gz"

step "CoreSimulator logs"
if [ -d "$HOME/Library/Logs/CoreSimulator" ]; then
    mkdir -p "$OUT/CoreSimulator"
    cp "$HOME/Library/Logs/CoreSimulator/"*.log "$OUT/CoreSimulator/" 2>/dev/null
    cp -R "$HOME/Library/Logs/CoreSimulator/$SIM_UDID" "$OUT/CoreSimulator/" 2>/dev/null
fi

step "Crash reports"
# Crashes of the runner, xcodebuild or CoreSimulator services land here, on the host.
if [ -d "$HOME/Library/Logs/DiagnosticReports" ]; then
    mkdir -p "$OUT/DiagnosticReports"
    find "$HOME/Library/Logs/DiagnosticReports" -type f -mmin -90 \
        -exec cp {} "$OUT/DiagnosticReports/" \; 2>/dev/null
fi

step "Done: $(find "$OUT" -type f | wc -l | tr -d ' ') files in $OUT"
exit 0
