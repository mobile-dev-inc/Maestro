#!/usr/bin/env bash
# Prints the android-<level> entries test-start-device should boot, one per line.
#
#   select_api_levels.sh <min> <max>               -> the whole declared range
#   select_api_levels.sh <min> <max> <bmin> <bmax> -> max, plus any level this range
#                                                      adds outside the base's bmin..bmax
#
# <max> may carry a minor platform version (e.g. 37.1); the top entry keeps it, every
# other entry is a bare integer level, and all comparisons use the integer part. Empty
# base bounds (an unparseable base) fall back to booting the whole range.
#
# Pure function of its arguments (no file or git access) so it can be unit-tested;
# see select_api_levels_test.sh. The range job in test-e2e.yaml does the file reads
# and git diffing, then calls this to expand and select.
set -euo pipefail

min=$1
max=$2
max_api="${max%%.*}"
bmin="${3:-}"
bmax_api="${4:-}"; bmax_api="${bmax_api%%.*}"

api=$min
while [ "$api" -le "$max_api" ]; do
  # Diff mode (a base min given) keeps only max and the levels outside the base range;
  # without it, keep every level.
  if [ -z "$bmin" ] || [ "$api" -eq "$max_api" ] || [ "$api" -lt "$bmin" ] || [ "$api" -gt "${bmax_api:-0}" ]; then
    # The top entry keeps max's minor version, if any.
    [ "$api" -eq "$max_api" ] && echo "android-$max" || echo "android-$api"
  fi
  api=$((api + 1))
done
