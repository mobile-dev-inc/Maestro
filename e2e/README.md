# e2e

This directory contains glue code for testing Maestro itself.

Typical workflow is:

1. Start Android emulator and iOS simulator
2. `download_apps`
3. `install_apps`
4. `run_tests`

We try to keep scripts in files, so we don't get too tightly coupled to GitHub Action.
