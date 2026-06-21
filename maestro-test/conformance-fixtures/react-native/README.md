# React Native conformance fixture

Idiomatic React Native 0.86 (New Architecture) app used by Maestro's **driver conformance harness**
(`--framework react-native`). It is **not** a sample app — every screen exists to behavior-test one
`AndroidDriver` command against real RN UI, emitting an out-of-band `MAESTRO_FIXTURE` logcat event
the harness reads.

- Android-only (the harness provisions arm64-v8a emulators). The `ios/` scaffold was removed.
- The bundled APK (`maestro-test/src/main/resources/react-native-fixture.apk`) is committed and
  built by `./build-rn-fixture.sh` (needs Node ≥ 20.12). Re-run it whenever JS or native changes.

See `REACT-NATIVE-FIXTURE.md` at the repo root for the design, the oracle bridge, and findings.
