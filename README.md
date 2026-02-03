> [!TIP]
> Great things happen when testers connect — [Join the Maestro Community](https://maestrodev.typeform.com/to/FelIEe8A)


<p align="center">
  <a href="https://www.maestro.dev">
    <img width="1200" alt="Maestro logo" src="https://github.com/mobile-dev-inc/Maestro/blob/main/assets/banne_logo.png" />
  </a>
</p>


<p align="center">
  <strong>Maestro</strong> is an open-source framework that makes UI and end-to-end testing for Android, iOS, and web apps simple and fast.<br/>
  Write your first test in under five minutes using YAML flows and run them on any emulator, simulator, or browser.
</p>

<img src="https://user-images.githubusercontent.com/847683/187275009-ddbdf963-ce1d-4e07-ac08-b10f145e8894.gif" />

---

## Table of Contents

- [Why Maestro?](#why-maestro)
- [Getting Started](#getting-started)
- [Resources & Community](#resources--community)
- [Contributing](#contributing)
- [Maestro Studio – Test IDE](#maestro-studio--test-ide)
- [Maestro Cloud – Parallel Execution & Scalability](#maestro-cloud--parallel-execution--scalability)
- [Fork Changelog](#fork-changelog)


---

## Why Maestro?

Maestro is built on learnings from its predecessors (Appium, Espresso, UIAutomator, XCTest, Selenium, Playwright) and allows you to easily define and test your Flows.

By combining a human-readable YAML syntax with an interpreted execution engine, it lets you write, run, and scale cross-platform end-to-end tests for mobile and web with ease.

- **Cross-platform coverage** – test Android, iOS, and web apps (React Native, Flutter, hybrid) on emulators, simulators, or real devices.  
- **Human-readable YAML flows** – express interactions as commands like `launchApp`, `tapOn`, and `assertVisible`.  
- **Resilience & smart waiting** – built-in flakiness tolerance and automatic waiting handle dynamic UIs without manual `sleep()` calls.  
- **Fast iteration & simple install** – flows are interpreted (no compilation) and installation is a single script.

**Simple Example:**
```
# flow_contacts_android.yaml

appId: com.android.contacts
---
- launchApp
- tapOn: "Create new contact"
- tapOn: "First Name"
- inputText: "John"
- tapOn: "Last Name"
- inputText: "Snow"
- tapOn: "Save"
```

---
## Getting Started

Maestro requires Java 17 or higher to be installed on your system. You can verify your Java version by running:

```
java -version
```

Installing the CLI:

Run the following command to install Maestro on macOS, Linux or Windows (WSL):

```
curl -fsSL "https://get.maestro.mobile.dev" | bash
```

The links below will guide you through the next steps.

- [Installing Maestro](https://docs.maestro.dev/getting-started/installing-maestro) (includes regular Windows installation)
- [Build and install your app](https://docs.maestro.dev/getting-started/build-and-install-your-app)
- [Run a sample flow](https://docs.maestro.dev/getting-started/run-a-sample-flow)
- [Writing your first flow](https://docs.maestro.dev/getting-started/writing-your-first-flow)


---

## Resources & Community

- 💬 [Join the Slack Community](https://maestrodev.typeform.com/to/FelIEe8A)
- 📘 [Documentation](https://docs.maestro.dev)  
- 📰 [Blog](https://maestro.dev/blog?utm_source=github-readme) 
- 🐦 [Follow us on X](https://twitter.com/maestro__dev)

---

## Contributing

Maestro is open-source under the Apache 2.0 license — contributions are welcome!

- Check [good first issues](https://github.com/mobile-dev-inc/maestro/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)
- Read the [Contribution Guide](https://github.com/mobile-dev-inc/Maestro/blob/main/CONTRIBUTING.md) 
- Fork, create a branch, and open a Pull Request.

If you find Maestro useful, ⭐ star the repository to support the project.

---

## Maestro Studio – Test IDE

**Maestro Studio Desktop** is a lightweight IDE that lets you design and execute tests visually — no terminal needed. 
It is also free, even though Studio is not an open-source project. So you won't find the Maestro Studio code here.

- **Simple setup** – just download the native app for macOS, Windows, or Linux.  
- **Visual flow builder & inspector** – record interactions, inspect elements, and build flows visually.  
- **AI assistance** – use MaestroGPT to generate commands and answer questions while authoring tests.

[Download Maestro Studio](https://maestro.dev/?utm_source=github-readme#maestro-studio)

---

## Maestro Cloud – Parallel Execution & Scalability

When your test suite grows, run hundreds of tests in parallel on dedicated infrastructure, cutting execution times by up to 90%. Includes built-in notifications, deterministic environments, and complete debugging tools.

Pricing for Maestro Cloud is completely transparent and can be found on the [pricing page](https://maestro.dev/pricing?utm_source=github-readme).

👉 [Start your free 7-day trial](https://maestro.dev/cloud?utm_source=github-readme)



---

## Fork Changelog

This fork contains modifications from the upstream [mobile-dev-inc/Maestro](https://github.com/mobile-dev-inc/Maestro) repository.

### Added

#### Video Recording & GCS Upload

Screen recording during test execution with automatic upload to Google Cloud Storage for failed tests.

- Records only on the **last retry attempt** (no point recording tests that will be retried)
- Uploads only **failed test** recordings (passed tests don't need recordings)
- Uses `gcloud` CLI for upload (simpler auth than Java SDK)
- File naming convention: `{buildName}-{buildNumber}-{deviceName}-{flowName}.mp4`
- Requires `BUILD_NAME`, `BUILD_NUMBER`, `DEVICE_NAME` env vars to be set (prevents accidental local recording)
- Local recordings are deleted after upload
- New CLI flags: `--no-record`, `--gcs-bucket`, `--attempt-number`, `--max-retries`

**Commits:**
- `770ee79a` feat: add video recording functionality and upload to cloud
- `cd8196e8` new recording implementation
- `c9784dc8` add log value and temp output directory
- `477edc06` change naming of file
- `5f92d63c` remove dump recordings
- `fe778970` using gcloud cli to upload instead of java sdk
- `fc38ebcc` use env variables for some parameters instead of system variables
- `196d536f` read max retry and attemp number from -e flags instead of system.env()
- `8557b850` disable recording by default on local when env variables are not explicitly provided

#### AI-Powered Commands

**`assertVisual` command** - Visual regression testing against baseline images:
- Uses `image-comparison` library for pixel comparison
- Stores baselines in `.maestro/visual_regression/`
- Creates baseline automatically if missing
- Configurable threshold (default 95%)
- Failed comparisons saved to `.maestro/failed_visual_regression/`

**`extractPointWithAI` command** - AI extracts UI element coordinates from text description:
- Returns percentage-based coordinates (e.g., "10%,20%")
- Uses OpenAI for image analysis

**Commits:**
- `b8f7bf73` feat: assert visual
- `a93d637c` feat: add extractPoint command
- `83648615` Create OpenAIClient.kt
- `39fc1cc1` Update OpenAIClient.kt
- `ecfae1c3` Update OpenAIClient.kt
- `875474f3` Update OpenAIClient.kt
- `4967ee66` Update Prediction.kt
- `90174dd0` Update ApiClient.kt
- `6f651983` Update DemoApp.kt
- `7d67716d` Update Orchestra.kt
- `7099f2db` fix: ai assertion

#### CLI Enhancements

- Flow execution logging with start time and duration
- `--driver-port` flag for specifying driver port
- Device resolver adjustments for test restructuring

**Commits:**
- `515ab64e` print when flow starts execution and duration of execution
- `d1b67af4` feat: add driver port
- `68967a3f` feat: adjustments to the resolvers to help restructuring of maestro tests

#### Command Interface

- Added `yamlString()` method to `Command` interface (all commands must implement)

**Commits:**
- `f7de5fbb` add missing yamlString function
- `e8e97883` add method needed for implementing the interface

---

### Changed

#### Build & Infrastructure

- Java target version: **1.8 → 17** (all modules)
- Gradle wrapper: **7.6.4 → 8.9**

**Commits:**
- `ef093253` feat: bump java from 1.8 to 17
- `a451de2b` update gradle version

#### Cloud API

- Removed Maestro Cloud API key requirement for AI predictions
- `Prediction.kt` now only uses OpenAI client (removed cloud API fallback)
- `CloudAIPredictionEngine` no longer requires `apiKey` parameter

**Commits:**
- `2e565d8e` feat: remove cloud key requirements
- `b42f389e` fix: removing maestro api-key requirment after syncing with upstream
- `3e5e2d05` remove api key from cloud api prediction
- `f2f5c1bc` remove duplicate declaration

#### Sharding

- Port selection now starts from provided `driverPort` and picks from a range
- Prevents port conflicts when running multiple sharded tests

**Commits:**
- `3b84c0ca` fix: moving shards to pick from different ports after defining a starting port

#### SwipeCommand

- Parameters changed from `startPoint: Point?`/`endPoint: Point?` to `start: String?`/`end: String?`

---

### Fixed

#### View Hierarchy Failures (iOS)

Added retry logic for transient `kAXErrorInvalidUIElement` errors that occur when UI elements are deallocated during traversal.

- 5 retry attempts with 300ms delay between retries
- Retries on all errors (not just specific ones)

**Commits:**
- `5a976b67` fix: view hierarchy failure
- `2e374e48` fix: added more fixes to cover more instances of view heirarchy request failures

#### CI Job Stability

Limited XCTest runner restart attempts to prevent infinite loops that hang CI jobs.

- `MAX_RESTART_ATTEMPTS = 2`

**Commits:**
- `4493e1cf` fix: ci jobs hang up in CI jobs
- `bcd340b3` fix: ci jobs hang up in CI jobs

#### Shutdown Error Handling (iOS)

Graceful handling of "Unable to lookup in current state: Shutdown" and "No devices are booted" errors in `terminate()` and `uninstall()` methods.

**Commits:**
- `96c4d0a5` ignore unable to shutdown error since we already handle our own shutdown and no need to throw the error
- `737110dd` handle already shutdown error gracefully in unistall method
- `a1f1bf60` Revert "ignore unable to shutdown error..."
- `024f1fe0` Revert "Revert "ignore unable to shutdown error...""

#### Report Exception Handling

Wrapped `DebugLogStore.finalizeRun()` in try-catch to prevent exceptions during log cleanup from breaking test execution.

**Commits:**
- `262ccb86` fix: disable the exception firing on failure to handle reports

#### Import/Dependency Fixes

**Commits:**
- `91218ac5` fix imports
- `6b3be0ca` fix: imports
- `627d2cda` fix: import
- `99a1a974` fix: import
- `d6988ace` fix: dependency
- `deb2382b` fix: loadCommand deps
- `7182e9ee` fix: export
- `a43c2a38` fix: flow loading
- `f572e214` fix: lib versions
- `e56c7246` fix: lib versions
- `6eef246f` fix: implement member
- `2cf93337` fix: undo env changes
- `9ced3b06` fix: undo env changes
- `d534fd8c` fix: undo env changes
- `c882f346` fix: undo env changes

---

### Removed

- `assertScreenshot` command (replaced by `assertVisual`)
- `YamlAssertScreenshot.kt` YAML parser
- Cloud API fallback in `Prediction.kt`
- Maestro Cloud API key requirements
- PostHog analytics tracking (commented out)
- `maestro-studio/web/src/api/mocks.ts`

**Commits:**
- `769dec19` Delete maestro-studio/web/src/api/mocks.ts
- `d29cfba1` comment out unwanted changes

---

### Sync with Upstream

**Commits:**
- `c4f6750e` Merge pull request #27 from dincozdemir/UP-4194/sync_fork_from_main
- `871ac0b2` merge upstream with fork
- `0dfd69ac` merge upstream with fork
- `7c42924b` Merge pull request #25 from dincozdemir/UP-4127/sync_for_from_upstream
- `da786191` Merge remote-tracking branch 'upstream/main' into UP-4127/sync_for_from_upstream
- `ba7c4613` Merge pull request #24
- `f8150294` Merge pull request #23 from dincozdemir/UP-4077/maestro_recordings
- `3bac885e` Merge pull request #22 from dincozdemir/UP-4077/maestro_recordings
- `65791264` Merge pull request #21 from dincozdemir/UP-4077/maestro_recordings
- `bee22507` Merge pull request #20 from dincozdemir/UP-4077/maestro_recordings
- `10bf0502` Merge pull request #19 from dincozdemir/UP-4077/maestro_recordings
- `d47b9024` Merge pull request #18 from dincozdemir/UP-4094/sync_maestor_repo
- `66afa132` sync fork from main
- `3cdad3d7` Merge pull request #17 from dincozdemir/update_fork
- `fbfec54a` sync for from base repo
- `8da6906c` Merge pull request #16 from dincozdemir/disable-excetion-file-handle-close
- `18646478` Merge pull request #15 from dincozdemir/UP-4047/add_maestro_video_recordings
- `6f5949a6` Merge branch 'main' into UP-4047/add_maestro_video_recordings
- `e8164046` Merge pull request #14 from dincozdemir/UP-4046/print_flow_start_execution
- `8e1eb4d7` Merge pull request #13 from dincozdemir/UP-4043/ci_nightly_jobs_fail
- `5bf3c282` Merge pull request #12 from dincozdemir/UP-4043/ci_nightly_jobs_fail
- `3deb48c1` Merge pull request #11 from dincozdemir/fix-view-heirarchy-issues
- `f1edd5de` Merge pull request #10 from dincozdemir/UP-4011/view_heirarhy_failure
- `b800d21c` Merge pull request #8 from dincozdemir/UP-3870/ignore_shutdown_error
- `2d214620` Merge pull request #9 from dincozdemir/revert-7-revert-6-UP-3870/ignore_shutdown_error
- `3fa60d71` Merge pull request #7 from dincozdemir/revert-6-UP-3870/ignore_shutdown_error
- `8ca8b5cb` Merge pull request #6 from dincozdemir/UP-3870/ignore_shutdown_error
- `26bb8730` sync for from repo
- `5316eb28` Merge pull request #5 from dincozdemir/distributing-shards-on-different-ports-from-give-port
- `1d76d83f` Merge pull request #4 from dincozdemir/fix-maestro-api-key-removal-local
- `357f81de` Merge remote-tracking branch 'origin/main'
- `6b7de5e1` sync fork with main repo
- `b814f465` Merge pull request #2 from dincozdemir/restructure-tests-directories
- `e58ea084` Merge pull request #1 from dincozdemir/driver-port
- `c0553208` Merge branch 'mobile-dev-inc:main' into main
- `7403d39f` Merge branch 'mobile-dev-inc:main' into main
- `3a34522b` fix: merge upstream
- `84aca266` feat: merge upstream
- `315a6787` Merge branch 'mobile-dev-inc:main' into main
- `af99e25f` Merge branch 'mobile-dev-inc:main' into main
- `3be6cc11` Merge branch 'mobile-dev-inc:main' into main
- `bbfd2405` Merge remote-tracking branch 'origin/main'
- `0f9492e3` Merge branch 'mobile-dev-inc:main' into main
- `5ccd9d98` Merge branch 'mobile-dev-inc:main' into main
- `23ddfa93` Merge branch 'mobile-dev-inc:main' into main
- `0737dfd1` Merge branch 'mobile-dev-inc:main' into main
- `c03f113e` Merge branch 'mobile-dev-inc:main' into main
- `625fd447` feat: merger
- `36693ac5` Merge branch 'mobile-dev-inc:main' into main
- `a9d873ea` Merge pull request #26 from dincozdemir/UP-4157/disable_local_recording

---

```
  Built with ❤️ by Maestro.dev
```


