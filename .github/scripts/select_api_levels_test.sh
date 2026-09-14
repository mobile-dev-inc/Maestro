#!/usr/bin/env bash
# Unit tests for select_api_levels.sh. Run: bash .github/scripts/select_api_levels_test.sh
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
subject="$here/select_api_levels.sh"
fails=0

# check <name> <expected-newline-list> -- <args...>
check() {
  name=$1; expected=$2; shift 2
  [ "$1" = "--" ] && shift
  actual=$(bash "$subject" "$@")
  if [ "$actual" = "$expected" ]; then
    echo "ok   - $name"
  else
    echo "FAIL - $name"
    echo "        args:     $*"
    echo "        expected: $(printf '%s' "$expected" | paste -sd, -)"
    echo "        actual:   $(printf '%s' "$actual" | paste -sd, -)"
    fails=$((fails + 1))
  fi
}

# Whole range.
check "whole range 29..36" \
  "$(printf 'android-29\nandroid-30\nandroid-31\nandroid-32\nandroid-33\nandroid-34\nandroid-35\nandroid-36')" \
  -- 29 36

# Whole range where max carries a minor version.
check "whole range keeps max minor" \
  "$(printf 'android-29\nandroid-30\nandroid-31\nandroid-32\nandroid-33\nandroid-34\nandroid-35\nandroid-36\nandroid-37.1')" \
  -- 29 37.1

# Single level (min == max).
check "single level" \
  "android-36" \
  -- 36 36

# PR that only bumps max: base 29..36, now 29..37 -> just the new max.
check "pr adds max only" \
  "android-37" \
  -- 29 37 29 36

# PR that widens both ends: base 30..35, now 28..37 -> new low, new high, and max.
check "pr adds low and high ends" \
  "$(printf 'android-28\nandroid-29\nandroid-36\nandroid-37')" \
  -- 28 37 30 35

# PR that adds max carrying a minor version.
check "pr adds max with minor" \
  "android-37.1" \
  -- 29 37.1 29 36

# PR touched the file but changed nothing in the range -> only max reboots.
check "pr no range change" \
  "android-36" \
  -- 29 36 29 36

# Empty base bounds (unparseable base) default to 0, so everything counts as added.
check "empty base bounds select all" \
  "$(printf 'android-29\nandroid-30\nandroid-31')" \
  -- 29 31 "" ""

echo
if [ "$fails" -eq 0 ]; then
  echo "All tests passed."
else
  echo "$fails test(s) failed."
  exit 1
fi
