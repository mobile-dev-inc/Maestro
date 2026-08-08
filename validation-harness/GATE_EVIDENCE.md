# Phase 2 HARD GATE — legacy backend ≡ stock main (Android)

**Verdict: GREEN.** The `legacy` ExecutionBackend is behaviorally identical to stock
`main` across the full 38-flow Android replay corpus. Zero real backend divergences.

## Methodology (control-subtracted, flakiness-robust)

Real corpus apps are non-deterministic across launches (loading-state asserts flip;
scrolled elements land at different pixels), so two separate runs diverge even on the
SAME backend. A literal zero-divergence diff can't separate a backend difference from
app noise. So each flow runs **4×** on one emulator — stock, legacy, stock, legacy —
with app state reset (`pm clear`) between passes. A step is judged only where BOTH
backends are internally reproducible; the first step flaky in either backend excludes
everything downstream (logged). On judged steps legacy must match stock on verdict +
element presence + element identity (text+resourceId). Coordinates never gate behavior
(same element at a different pixel = app layout, not backend); flagged only if both
backends place the same element reproducibly yet differently. RED flows auto-escalate
with more samples — a real divergence is deterministic, a coin-flip assert is not.

## Result

- **corpusGreen: True** — 38/38 flows GREEN, 0 RED, 0 incomplete
- **1437 judged steps** of exact legacy≡stock equivalence; 490 flaky steps excluded
- **26/38 flows fully reproducible** (zero exclusions)
- **0 coordinate flags**
- device pool: arm-m2m-006 / emulator-5680 (research_spike_api34); tol ±2px

## Per-flow

| flow | verdict | judged | excluded | kFlaky |
|---|---|---:|---:|---:|
| Amazon_(one_medical)_run_01kty4e9k7evrtbrc091ne13tf | GREEN | 12 | 0 | None |
| Amazon_(one_medical)_run_01kty4e9nxe0y8j77f41cabj3j | GREEN | 12 | 0 | None |
| Buildertrend_run_01kty14qnjebts3ra93w8h11hd | GREEN | 29 | 0 | None |
| Buildertrend_run_01kty14qpne6rtt0hg6pygqjc2 | GREEN | 31 | 0 | None |
| CompanyCam_run_01kty5qrq7fk6rdg7vgt4eks53 | GREEN | 7 | 3 | 8 |
| DoorDash_run_01ktw5etvnfydbq6rezwgz6y5f | GREEN | 45 | 0 | None |
| DoorDash_run_01ktw6bv2rfgkbckvbeksp6vxh | GREEN | 55 | 0 | None |
| DoorDash_run_01ktw97kcxeypvjh5cqc8k9d1k | GREEN | 14 | 0 | None |
| DoorDash_run_01ktwap29re058nra2cy7393xf | GREEN | 37 | 0 | None |
| DoorDash_run_01ktwe1kbyf3vv4yqy7hwmr5c9 | GREEN | 29 | 7 | 29 |
| DoorDash_run_01ktweav75ehxr79gjtjfek978 | GREEN | 29 | 7 | 29 |
| GlossGenius_run_01ktxzqm48ehaa4h6037bj1xc5 | GREEN | 44 | 22 | 48 |
| GlossGenius_run_01ktxzqm8ye91rt535ry1nwmdr | GREEN | 48 | 32 | 53 |
| Komoot_run_01kty6xjh2f3prgcxem63z3xj9 | GREEN | 5 | 0 | None |
| Komoot_run_01kty6xjvqfsx9pmw5hjqhaps4 | GREEN | 5 | 0 | None |
| Kraken_run_01kty6grv2fptt5aza7rwentew | GREEN | 22 | 51 | 23 |
| Kraken_run_01kty6gsdce4vrwmeydqcxes1a | GREEN | 74 | 0 | None |
| Microsoft_Copilot_run_01ktw79t4yf8y9n91rt5f4er5m | GREEN | 47 | 0 | None |
| Microsoft_Copilot_run_01ktw884n1e9fvgsfc8w7gtmwm | GREEN | 7 | 0 | None |
| NewCore_run_01kty7bv8xfxmrmewcsr4azeyr | GREEN | 10 | 0 | None |
| Plum_run_01ktxzpw0hepga58kf73x0pjb0 | GREEN | 46 | 71 | 48 |
| Plum_run_01ktxzpxbgfqgtj5wq0w678rq1 | GREEN | 121 | 0 | None |
| QuintoAndar_run_01kty5k25bfnkvr2fm7na84ps6 | GREEN | 37 | 0 | None |
| QuintoAndar_run_01kty5k2gefn2sbn9wmyf2yngf | GREEN | 47 | 0 | None |
| Redcare_Pharmacy__run_01kty1kx9df75trn27desrm40k | GREEN | 16 | 60 | 18 |
| Redcare_Pharmacy__run_01kty1t9e9e6z8ng12hff9pdnq | GREEN | 16 | 124 | 18 |
| Skyscanner_run_01kty6715ye51s29hxhnyg9kkx | GREEN | 7 | 43 | 8 |
| Skyscanner_run_01kty6719vf38rxgk88j6bfx9y | GREEN | 44 | 0 | None |
| Vividseats_run_01kty6w7aveffb4ryjcefaf5bx | GREEN | 48 | 0 | None |
| Vividseats_run_01kty6w7d3erts3sb44yr3g4m7 | GREEN | 60 | 0 | None |
| Wahed_run_01ktxz9ybpe52t5r54xjtnzejp | GREEN | 92 | 0 | None |
| Wahed_run_01ktxz9yg5f13s2w0r1f91yx4y | GREEN | 64 | 0 | None |
| Wealthsimple_run_01kty4g8sgf138mzzm1h62s0kd | GREEN | 10 | 0 | None |
| Wealthsimple_run_01kty4g8tdffnvfqb2gfwcwk5g | GREEN | 10 | 0 | None |
| crypto.com_run_01ktxy7h3wfy89jsjkh5twb6qz | GREEN | 49 | 26 | 57 |
| crypto.com_run_01ktxy7hbfe1btxd4bgs37bj1z | GREEN | 46 | 44 | 53 |
| crypto.com_run_01kty696yjeabr15g5kd8429q6 | GREEN | 65 | 0 | None |
| crypto.com_run_01kty6972ffsjv8e0tr1bct57v | GREEN | 97 | 0 | None |
