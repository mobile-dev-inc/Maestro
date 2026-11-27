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
# Best effort: every step tolerates failure, and every simctl call has a time limit, since
# the simulator being diagnosed may be stuck. One missing log never hides the rest, and the
# script always exits 0.

set -u

SIM_UDID="${1:?usage: $0 <simulator-udid> <output-dir>}"
OUT="${2:?usage: $0 <simulator-udid> <output-dir>}"
# How far back the simulator's unified log is read. Covers the longest iOS job.
LOG_WINDOW="${LOG_WINDOW:-60m}"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

step() {
    echo "[collect_simulator_diagnostics] $1"
}

# Runs a command for at most $1 seconds (macOS has no `timeout`). Exit status 142 means it
# was stopped at the limit.
run_bounded() {
    local secs=$1
    shift
    perl -e 'alarm shift; exec @ARGV' "$secs" "$@"
}

step "Simulator and runtime state"
{
    echo "## simctl list devices"
    run_bounded 30 xcrun simctl list devices
    echo
    echo "## simctl bootstatus"
    # Reports the current boot phase, such as data migration on a first boot. It then waits
    # for the boot to finish, which a stuck simulator never does, hence the short limit.
    run_bounded 10 xcrun simctl bootstatus "$SIM_UDID"
    echo "(exit status $?; 142 means still waiting when stopped after 10s)"
    echo
    echo "## simctl list runtimes"
    run_bounded 30 xcrun simctl list runtimes
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
run_bounded 30 xcrun simctl io "$SIM_UDID" screenshot "$OUT/simulator-screenshot.png" > /dev/null 2>&1 \
    || echo "screenshot failed" > "$OUT/simulator-screenshot-failed.txt"

step "Simulator unified log (last $LOG_WINDOW)"
# Hundreds of MB uncompressed even without debug-level messages; a few MB gzipped. Reading
# it takes a minute or two on a healthy simulator.
run_bounded 300 xcrun simctl spawn "$SIM_UDID" log show --last "$LOG_WINDOW" --style compact --info 2>&1 \
    | gzip > "$OUT/simulator-unified.log.gz"

step "CoreSimulator logs"
if [ -d "$HOME/Library/Logs/CoreSimulator" ]; then
    mkdir -p "$OUT/CoreSimulator"
    cp "$HOME/Library/Logs/CoreSimulator/"*.log "$OUT/CoreSimulator/" 2>/dev/null
    cp -R "$HOME/Library/Logs/CoreSimulator/$SIM_UDID" "$OUT/CoreSimulator/" 2>/dev/null
fi

step "Crash reports"
# Crashes of the runner, xcodebuild or CoreSimulator services land here, on the host.
# Relative paths are kept, so reports in subfolders (Retired/) cannot overwrite each other.
if [ -d "$HOME/Library/Logs/DiagnosticReports" ]; then
    mkdir -p "$OUT/DiagnosticReports"
    (cd "$HOME/Library/Logs/DiagnosticReports" \
        && find . -type f -mmin -90 -exec rsync -R {} "$OUT/DiagnosticReports/" \;) 2>/dev/null
fi

step "Done: $(find "$OUT" -type f | wc -l | tr -d ' ') files in $OUT"
exit 0
