#!/usr/bin/env bash

set -e

if [ -t 0 ]; then
  input=""
else
  input=$(cat -)
fi

echo "Hello $input"