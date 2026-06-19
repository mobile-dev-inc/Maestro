# Conformance Harness — Known Quirks & Platform Notes

## Image selection (arm64-v8a / Apple Silicon)

`FreshAvdProvider.detectHostAbi()` returns `"arm64-v8a"` on `aarch64` hosts (Apple Silicon Macs).
No x86_64 fallback is provided; the harness targets Apple Silicon only.

## API 36 system-image variant

Google renamed the API 36 system-image package to use a `ps16k` (4 KB → 16 KB page-size) suffix.
`FreshAvdProvider.variantFor(api)` returns `"google_apis_ps16k"` for api >= 36, and `"google_apis"` for
api <= 35. This affects the `sdkmanager` install path and the `avdmanager` `-k` argument.

Installed images on the reference Apple Silicon host:
- android-29..35 / google_apis / arm64-v8a — present
- android-36 / google_apis / arm64-v8a — present (legacy alias)
- android-36 / google_apis_ps16k / arm64-v8a — present (canonical name used by harness)
- APIs 24-28, 30, 31 — NOT installed on this host; sdkmanager will download on first use

## GBoard IME availability

The `google_apis` images do not ship GBoard pre-pinned. `FreshAvdProvider.pinGboardIme()` enables and
sets `com.google.android.inputmethod.latin/...LatinIME` if the package is present. When absent
(observed on some API 29 images), a warning is printed and keyboard-dependent commands
(`inputText`, `eraseText`, `isKeyboardVisible`, `hideKeyboard`, `pressKey`) may return verdicts
that reflect the missing IME rather than a driver bug. These are legitimate environment gaps, not
driver regressions.

## API 29 screenshot / screen-record on Apple Silicon

Some API 29 `google_apis` arm64-v8a images have intermittent failures in `screencap` / `screenrecord`
under hardware acceleration on Apple Silicon. If `takeScreenshot` is red on API 29 only, treat the
artifact as unavailable rather than a driver regression.

## Per-API quirks observed (provisioned runs to date)

| API | Commands run | All PASS? | Notes |
|-----|-------------|-----------|-------|
| 33  | all 22      | Yes       | No quirks observed. GBoard present and pinned. |
| 34  | all 22      | Yes       | No quirks observed. GBoard present and pinned. |

## Full 24–36 sweep

The complete matrix (APIs 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36) requires
downloading ~13 system-image packages totalling several GB for the APIs not pre-installed
(24-28, 30, 31). Sequentially provisioning 13 AVDs on a single machine exceeds a reasonable
interactive session (~2-4 h). Per the spec, **scaling is a CI concern**: the intended deployment
is a sharded GitHub Actions matrix where each API runs on its own runner. A single runner per API
(parallel shards) reduces wall time to roughly the cost of one provisioned AVD boot (~5 min).
