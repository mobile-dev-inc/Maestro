# demo_app

A simple cross-platform Android & iOS app, built with Flutter, for testing
[Maestro framework](https://github.com/mobile-dev-inc/maestro).

### How does it work?

1. Binaries of this app are built
2. Binaries are uploaded to our GCS bucket
3. Link to the binaries in GCS bucket are in [e2e/manifest.txt][manifest]

Whenever E2E pipeline of Maestro runs, it downloads the binaries from the GCS
and runs flows in the [e2e/workspaces/demo_app][workspace] workspace (among others).

[workspace]: https://github.com/mobile-dev-inc/maestro/tree/main/e2e/workspaces/demo_app
[manifest]: https://github.com/mobile-dev-inc/maestro/blob/main/e2e/manifest.txt
