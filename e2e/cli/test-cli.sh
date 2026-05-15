#!/usr/bin/env bash
set -euo pipefail
export MAESTRO_CLI_NO_ANALYTICS=1
PASS=0; FAIL=0

trap 'echo "ERROR: script failed at line $LINENO" >&2' ERR

check() {          # check <desc> <cmd> <assertion> <expected>
  local desc="$1" cmd="$2" assertion="$3" expected="$4"
  local actual actual_plain ok=0
  actual=$(eval "$cmd" 2>&1 | tr -d '\r') || true
  actual_plain=$(printf '%s' "$actual" | sed 's/\x1b\[[0-9;]*[mGKHF]//g') # strip ansi
  case "$assertion" in
    equals)   [ "$actual_plain" = "$expected" ]                        && ok=1 ;;
    includes) printf '%s' "$actual_plain" | grep -qF "$expected"       && ok=1 ;;
    excludes) ! printf '%s' "$actual_plain" | grep -qF "$expected"     && ok=1 ;;
    *)        echo "ERROR: unknown assertion '$assertion' (use: equals, includes, excludes)" >&2; exit 1 ;;
  esac
  if [ $ok -eq 1 ]; then
    echo "PASS: $desc"
    (( ++PASS ))
  else
    printf "FAIL: %s\n"        "$desc"
    printf "      %-11s %s\n" "cmd:"       "$cmd"
    printf "      %-11s %s\n" "assertion:" "$assertion"
    printf "      %-11s %s\n" "expected:"  "$expected"
    printf "      %-11s %s\n" "got:"       "$actual_plain"
    (( ++FAIL ))
  fi
}

VERSION=$(grep '^CLI_VERSION=' maestro-cli/gradle.properties | cut -d= -f2)

# TESTS START HERE:

check "maestro -v equals gradle.properties version" \
  "maestro -v" equals "$VERSION"

check "maestro gives usage instructions when called without parameters" \
  "maestro" includes "Usage: maestro"

check "maestro gives usage instructions when called with --help" \
  "maestro --help" includes "Usage: maestro"

check "maestro test subcommand gives usage instructions when called with --help" \
  "maestro test --help" includes "Usage: maestro test"

check "maestro bugreport gives instruction" \
  "maestro bugreport" includes "https://github.com/mobile-dev-inc/Maestro/issues"

echo ""
echo "$PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
