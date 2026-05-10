#!/usr/bin/env bash
set -euo pipefail
export MAESTRO_CLI_NO_ANALYTICS=1
PASS=0; FAIL=0

check() {          # check <desc> <cmd> <assertion> <expected>
  local desc="$1" cmd="$2" assertion="$3" expected="$4"
  local actual ok=0
  actual=$(eval "$cmd" 2>&1 | tr -d '\r')
  case "$assertion" in
    equals)   [ "$actual" = "$expected" ]                  && ok=1 ;;
    includes) printf '%s' "$actual" | grep -qF "$expected" && ok=1 ;;
    excludes) ! printf '%s' "$actual" | grep -qF "$expected" && ok=1 ;;
  esac
  if [ $ok -eq 1 ]; then
    echo "PASS: $desc"
    (( ++PASS ))
  else
    echo "FAIL: $desc"
    echo "      expected ($assertion): $expected"
    echo "      got:                   $actual"
    (( ++FAIL ))
  fi
}

VERSION=$(grep '^CLI_VERSION=' maestro-cli/gradle.properties | cut -d= -f2)

check "maestro -v equals gradle.properties version" \
  "maestro -v" equals "$VERSION"

echo ""
echo "$PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
