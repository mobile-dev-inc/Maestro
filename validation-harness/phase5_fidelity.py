#!/usr/bin/env python3
"""phase5_fidelity.py — historical note (Phase 5 device-core fidelity framework).

This file used to house both `fidelity_report()` (the legacy-vs-device-core
step classifier: AGREE/DIVERGE/OWED/INFRA/MISSING) AND a single-flow,
built-in-app (`com.android.settings`) demo runner that drove it over its own
private ssh/scp transport.

Both jobs have since moved:

  - `fidelity_report()` lives in `diff_traces.py` now, next to the
    `diff_flow` engine it's built on — see `diff_traces.fidelity_report`.
  - The demo-runner half (`main`, `build_remote_script`, `run_side`) was a
    single-flow subset of `run_differential.py` (which replays ANY number of
    replay-harness folders, local or remote, through the shared executor
    seam) and has been retired as redundant. Use `run_differential.py`
    instead — see `RUN_DIFFERENTIAL.md`. The `flows/settings-*.yaml` files
    this demo used are still valid input flows for it.

Kept as a pointer (not deleted) so anyone who remembers "phase5_fidelity"
lands somewhere useful.
"""
