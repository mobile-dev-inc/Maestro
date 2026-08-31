# Brief: the `remote-differential-batch` skill

For the agent writing this skill with `writing-skills`. The skill wraps
`validation-harness/batch_differential.py` (build/partition/dispatch/collect,
plus `dispatch --smoke`). It carries the operator workflow and the five
invariants the script cannot enforce on its own.

## What the skill must carry

1. **The production-configurator invariant.** Devices boot ONLY through
   `maestro-device launch` (via `run_differential.py --device-bin`). The skill
   must forbid substituting the device-hosts research launchers
   (`launch-avd.sh` / `launch-sim.sh`) — they make `research_spike_*` devices
   with no golden bake and manufacture false divergence. State this as a
   red-line, with the why.

2. **Pool-claim + the human host-assignment gate.** Hosts are assigned by a
   human (the device-hosts pool protocol — assigned at the gate, the agent
   never self-selects). The human names the iOS (`arm-m4s-*`) and Android
   (`arm-m2m-*`) hosts allocated to them; the script validates whatever names
   are given against `ios_agents`/`android_agents` in the inventory (don't
   hardcode a count — the pool size varies, and today's `testing.yml` lists two
   of each). Before any run, each host is idle-checked (`adb devices` /
   `simctl list devices booted` empty, no stray `maestro`/`qemu` process). A
   busy host is skipped and reported — never swapped for another. Credentials
   are read from the inventory at run time and never written down.

3. **The smoke-test gate.** Always run `dispatch --smoke` first: one iOS host
   + one Android host, one folder each, then STOP. A green smoke (both hosts
   bake+boot a golden, both CLIs emit a trace, `diff.json` has the expected
   shape and pulls back intact, host env matches — openjdk@17 path, SDK path,
   `python3 >= 3.10`) is the go/no-go for the full fan-out. Spell out what
   "green" means and what each red mode implies.

4. **Tar-pull hygiene.** Always `collect` via the counted tar-stream pull;
   `scp -r` over `sshpass` silently truncates. A count mismatch fails the
   collect — never aggregate a partial set. Note the pull can exceed a single
   tool wall-clock; run it detached and wait on the count.

5. **The triage handoff.** After `collect`, hand ONLY the `diverge > 0`
   folders (in `triage-folders.txt`) to the `triage-3x-divergence` skill.
   Walls (OWED) and clean agreement are recorded but not triaged. Golden
   caches stay warm between runs (the "runs many times" win); only per-run
   scratch is cleaned.

## Operator flow the skill should script

build -> partition (with the human's named hosts) -> dispatch --smoke ->
inspect the two diffs -> dispatch (full) -> poll DONE -> collect -> triage.

## Copy-adapt provenance to record

The transport in `remote.py` is copied and adapted from the device-hosts
`run_remote.sh` (the 92-line verified-tar-pull version at
`~/codes/blind-hid/.claude/skills/run-spike-experiment/reference/run_remote.sh`),
per that skill's "copy and adapt, not run in place" rule. The skill should
point future maintainers there rather than to a vendored copy.
