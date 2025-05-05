# Changelog

## Unreleased

## 1.40.1
Fix
- iOS apps going on background while using maestro commands

Feature
- Flag to skip interactive device selection by picking a --device-index

## 1.40.0

Fix:
- JavaScript fails when running maestro test in continuos mode. Affected Commands: `maestro test`, `maestro record` ([#2311](https://github.com/mobile-dev-inc/Maestro/pull/2311))
- Ignore notifications in analyse command for CI ([#2306](https://github.com/mobile-dev-inc/Maestro/pull/2306))
- `config.yaml` not resolving on Windows ([#2327](https://github.com/mobile-dev-inc/Maestro/pull/2327))
- Fix swipe command failure on iOS after upgrading to Xcode 16.2 [issue #2422](https://github.com/mobile-dev-inc/maestro/issues/2422) ([#2332](https://github.com/mobile-dev-inc/maestro/pull/2332))
- Fix `app-binary-id` option on maestro cloud upload ([#2361](https://github.com/mobile-dev-inc/Maestro/pull/2361))
- Ensure commands with missing elements fail as expected in Studio ([#2140](https://github.com/mobile-dev-inc/Maestro/pull/2140))
- Prevent flows from getting stuck on the cloud by properly setting driver closing state ([#2364](https://github.com/mobile-dev-inc/Maestro/pull/2364))
- Fix `maestro cloud` & `maestro start-device` on windows ([#2371](https://github.com/mobile-dev-inc/Maestro/pull/2371))
- Improved `maestro cloud` to only process valid flow `.yaml`/`.yml` files and skip unrelated files like `config.yaml`, preventing parsing errors when uploading folders with mixed content ([#2359](https://github.com/mobile-dev-inc/Maestro/pull/2359))
- Improved `maestro cloud` to skip validating non-flow files (e.g., .js, README, config.yaml) in folders, preventing parsing errors and upload failures
- Fix setting up iOS Driver when not on bash environment ([#2412](https://github.com/mobile-dev-inc/Maestro/pull/2412))
- Speed up view hierarchy generation by reducing SpringBoard queries and avoiding redundant app list calls on iOS. ([#2419](https://github.com/mobile-dev-inc/Maestro/pull/2419))

Features:
- Added support for `androidWebViewHierarchy: devtools` option to build Android WebView hierarchy using Chrome DevTools ([#2350](https://github.com/mobile-dev-inc/Maestro/pull/2350))
- Added Chrome to available devices for web automation ([#2360](https://github.com/mobile-dev-inc/Maestro/pull/2360))
- Introduced pre-built mode for setting up iOS driver on simulators without relying on `xcodebuild` ([#2325](https://github.com/mobile-dev-inc/Maestro/pull/2325))
- Added command-line chat mode to Maestro CLI accessable by `maestro chat --ask=` and `maestro chat` ([#2378](https://github.com/mobile-dev-inc/Maestro/pull/2378))
- Introduced `maestro check-syntax` command for validating flow syntax ([#2387](https://github.com/mobile-dev-inc/Maestro/pull/2387))
- Added `--reinstall-driver` flag that reinstalls xctestrunner driver before running the test. Set to false if the driver shouldn't be reinstalled ([#2413](https://github.com/mobile-dev-inc/Maestro/pull/2413))
- Added `--compact` flag that remove empty values to make the output hierarchy json smaller ([#2413](https://github.com/mobile-dev-inc/Maestro/pull/2413))
- Added `--device-os` and `--device-model` options to target specific iOS minor versions and devices ([Docs](https://docs.maestro.dev/cloud/reference/configuring-os-version#using-a-specific-ios-minor-version-and-device-recommended)) ([#2413](https://github.com/mobile-dev-inc/Maestro/pull/2413))
- Added support for ios 18 on cloud and local
- Bumped default iOS version to 16 for `maestro start-device`
- Enabled AI command usage on `mobile.dev` ([#2425](https://github.com/mobile-dev-inc/Maestro/pull/2425))

Chore: 
- Update Flying Fox HTTP server on iOS driver ([#2415](https://github.com/mobile-dev-inc/Maestro/pull/2415))
- Migrated app termination from `simctl` to `xctest` for improved stability` ([#2418](https://github.com/mobile-dev-inc/Maestro/pull/2418))

## 1.39.13

- Fix : Adding upload route back again
- Feature: Removing Analyze logs from CI uploads

## 1.39.12

- Fix: Upload route on Robin was not working on maestro cloud command

## 1.39.11

- Feature: Starting trial from CLI
- Feature: Better logs to improve visibility
- Feature: Prebuilt iOS driver without xcodebuild
- Feature: Analyze option to test command

## 1.39.10

- Update install script to tidy up old installation binaries

## 1.39.9

- Revert: Error in showing keyboard during input and erase commands on iOS
- Fix: applesimutils affecting granting location permission
- Fix: Setting host and port from the optional arguments
- Feature: New `maestro login` command for logging in Robin.
- Feature: Improved `maestro record` video to scroll and follow the currently executing commands
- Fix: Enable running Maestro on Windows without WSL
- Feature: Add console.log messages directly to the maestro log file.

## 1.39.8

- Fix: Debug message not showing up when we execute commands on maestro cli anymore

## 1.39.7

- Feature: Improved web support. 
  - Fix: Maestro can test web pages again (it was broken)
  - Fix: WebDriver was reporting invalid screen size 
  - Web: support cases where a new tab is opened from the page 
  - Web: screen recording support (via JCodec for now, but we could add ffmpeg later)
  - Web: fake geolocation support 
  - Studio: better layout for wide aspect-ratio screens (i.e. web pages or tablets)
- Feature: Introduces extractTextWithAI command

- Fix: Retry should throw exception when max retries reaches
- Fix: Studio getting unresponsive due to exceptions in streaming device

## 1.39.5

Released on 2024-12-16


Fixes:
- Fix: Failure on how the assertConditionCommand was being handled on Robin([#2171](https://github.com/mobile-dev-inc/maestro/pull/2171))

## 1.39.4

Features:
- Add `waitToSettleTimeoutMs` to other swipe related commands ([#2153](https://github.com/mobile-dev-inc/maestro/pull/2153))
- Add retry command for flaky conditions ([#2168](https://github.com/mobile-dev-inc/maestro/pull/2168))
- Add support for recording maestro flows locally instead of using remote servers ([#2173](https://github.com/mobile-dev-inc/maestro/pull/2173))

Fixes:
- Fix: multiple xcodebuild process and leading to IOSDriverTimeoutException ([#2097](https://github.com/mobile-dev-inc/maestro/pull/2097))
- Fix: NullPointerException during view hierarchy operations for android ([#2172](https://github.com/mobile-dev-inc/maestro/pull/2172))
- Fix: Debug level logs in maestro.log file leading to large debug files ([#2170](https://github.com/mobile-dev-inc/maestro/pull/2170))
- Fix: Environment variable not being set for test suite ([#2163](https://github.com/mobile-dev-inc/maestro/pull/2163))
- Fix: Failures on clearKeychain operations on iOS due to missing directories ([#2178](https://github.com/mobile-dev-inc/maestro/pull/2178))

## 1.39.2

Released on 2024-11-19

Fixes:
- Fix: Insights object causing ConcurrentModificationException ([#2131](https://github.com/mobile-dev-inc/maestro/pull/2131))
- Fix: Timeout unit in scrollUntilVisible command ([#2112](https://github.com/mobile-dev-inc/maestro/pull/2112))
- Feat: Add new status for robin flows: PREPARING and INSTALLING. ([#2145](https://github.com/mobile-dev-inc/maestro/pull/2145))

## 1.39.1

Released on 2024-11-04

Fixes:
- Fix: clearState now automatically reinstall the App ([#2118](https://github.com/mobile-dev-inc/maestro/pull/2118))

## 1.39.0

Released on 2024-10-15

Features:
- Feature: add `--shard-split` and `--shard-all` options to `maestro test` ([#1955](https://github.com/mobile-dev-inc/maestro/pull/1955) by [Tarek Belkahia](https://github.com/tokou))

  The `--shard` is now deprecated and superseded by `--shard-split`.

- Feature: allow for passing multiple flow files to `maestro test` ([#1995](https://github.com/mobile-dev-inc/maestro/pull/1995) by [Tarek Belkahia](https://github.com/tokou))
- Feature: add the `optional` argument to all commands ([#1946](https://github.com/mobile-dev-inc/maestro/pull/1946) by [Tarek Belkahia](https://github.com/tokou))

  This new command-level `optional` argument supersededs the (now removed) selector-level `optional` argument. No behavior changes are expected.

  When command with `optional: true` fails, its status is now "warned ⚠️" instead of "skipped ⚪️"

- Feature: add changelog to the update prompt when new Maestro version is available ([#1950](https://github.com/mobile-dev-inc/maestro/pull/1950) by [Tarek Belkahia](https://github.com/tokou))
- Feature: add back the `--platform` option ([#1954](https://github.com/mobile-dev-inc/maestro/pull/1954) by [Tarek Belkahia](https://github.com/tokou))
- Feature: expose current flow name as `MAESTRO_FILENAME` env var ([#1945](https://github.com/mobile-dev-inc/maestro/pull/1945) by [Tarek Belkahia](https://github.com/tokou))

Fixes:
- Fix: Warnings generated by AI-powered commands aren't formatted nicely ([#2043](https://github.com/mobile-dev-inc/maestro/pull/2043)) ([#2044](https://github.com/mobile-dev-inc/maestro/pull/2044))
- Fix: not working when iOS simulator is in landscape orientation ([caveats apply](https://github.com/mobile-dev-inc/maestro/pull/1974#issuecomment-2346074593)) ([#1974](https://github.com/mobile-dev-inc/maestro/pull/1974))
- Fix: confusing error message "BlockingCoroutine is cancelling" ([#2036](https://github.com/mobile-dev-inc/maestro/pull/2036))
- Fix: AI-powered commands crashing when Anthropic is used ([#2033](https://github.com/mobile-dev-inc/maestro/pull/2033))
- Fix: display warnings generated by AI-powered commands in CLI output when `optional: true` ([#2026](https://github.com/mobile-dev-inc/maestro/pull/2026)) 
- Fix: visual bug with emojis having slightly different length in `maestro test`'s interactive CLI output ([#2016](https://github.com/mobile-dev-inc/maestro/pull/2016)) 
- Fix: no tests being run when flowsOrder specified all tests in the workspace ([#2003](https://github.com/mobile-dev-inc/maestro/pull/2003))
- Fix: using integers from JavaScript outputs causing a deserialization error ([#1788](https://github.com/mobile-dev-inc/maestro/pull/1788) by [Muhammed Furkan Boran](https://github.com/boranfrkn)) 
- Fix: delete temporary APKs after using them ([#1947](https://github.com/mobile-dev-inc/maestro/pull/1947) by [Tarek Belkahia](https://github.com/tokou))
- Fix: allow env vars in `setLocation` and `travel` commands ([#1988](https://github.com/mobile-dev-inc/maestro/pull/1988) by [Prasanta Biswas](https://github.com/prasanta-biswas))
- Fix: error message when specifying `--format` together with `--continuous` #1948 ([#1948](https://github.com/mobile-dev-inc/maestro/pull/1948) by [Tarek Belkahia](https://github.com/tokou))

Chores:
- Chore: clean up logging, make log format configurable with 2 new env vars ([#2041](https://github.com/mobile-dev-inc/maestro/pull/2041))
- Chore: make Maestro build & compile on Java 17 ([#2008](https://github.com/mobile-dev-inc/maestro/pull/2008))
- Chore: Migrate all Gradle buildscripts to Gradle Kotlin DSL ([#1994](https://github.com/mobile-dev-inc/maestro/pull/1994))

## 1.38.1

Released on 2024-08-30

- New experimental AI-powered commands for screenshot testing: [assertWithAI](https://maestro.mobile.dev/api-reference/commands/assertwithai) and [assertNoDefectsWithAI](https://maestro.mobile.dev/api-reference/commands/assertnodefectswithai) ([#1906](https://github.com/mobile-dev-inc/maestro/pull/1906))
- Enable basic support for Maestro uploads while keeping Maestro Cloud functioning ([#1970](https://github.com/mobile-dev-inc/maestro/pull/1970))

## 1.37.9

Released on 2024-08-15

- Revert iOS landscape mode fix ([#1916](https://github.com/mobile-dev-inc/maestro/pull/1916))

## 1.37.8

Released on 2024-08-14

- Fix sharding on Android failing on all but one devices (quick hotfix) ([#1867](https://github.com/mobile-dev-inc/maestro/pull/1867))
- Fix CLI crash when flow is canceled on Maestro Cloud ([#1912](https://github.com/mobile-dev-inc/maestro/pull/1912))
- Fix iOS landscape mode ([caveats apply](https://github.com/mobile-dev-inc/maestro/pull/1809#issuecomment-2249917209)) ([#1809](https://github.com/mobile-dev-inc/maestro/pull/1809))
- Skip search engine selection when running on the web ([#1869](https://github.com/mobile-dev-inc/maestro/pull/1869))

## 1.37.7

Released on 2024-08-03

- Fix cryptic "Socket Exception" when `CI` env var is set, once and for all ([#1882](https://github.com/mobile-dev-inc/maestro/pull/1882))

## 1.37.6

Released on 2024-08-02

- Print stack trace on 3rd retry ([#1877](https://github.com/mobile-dev-inc/maestro/pull/1877))

## 1.37.5

Released on 2024-08-02

- Fix cryptic "SocketException" when API token is invalid ([#1871](https://github.com/mobile-dev-inc/maestro/pull/1871))

## 1.37.4

Released on 2024-07-30

- Don't ask for analytics permission on CI + add `MAESTRO_CLI_NO_ANALYTICS` env var ([#1848](https://github.com/mobile-dev-inc/maestro/pull/1848))

## 1.37.3

Released on 2024-07-29

### Bug fixes

- Fix `FileNotFoundException: ~.maestro/sessions` ([#1843](https://github.com/mobile-dev-inc/maestro/pull/1843)) 

## 1.37.2 - 2024-07-29

### Bug fixes

- Fix `UnsupportedOperationException: Empty collection can't be reduced` ([#1840](https://github.com/mobile-dev-inc/maestro/pull/1840))

## 1.37.1 - 2024-07-29

### Bug fixes

- Fix crash when `flutter` or `xcodebuild` is not installed ([#1839](https://github.com/mobile-dev-inc/maestro/pull/1839))

## 1.37.0 - 2024-07-29

### New features

- **Sharding tests for parallel execution on many devices 🎉** ([#1732](https://github.com/mobile-dev-inc/maestro/pull/1732) by [Kaan](https://github.com/sdfgsdfgd))

  You can now pass `--shards` argument to `maestro test` to split up your test suite into chunks that run in parallel. If you have feedback or suggestions about this huge new feature, please share them with us in [issue #1818](https://github.com/mobile-dev-inc/maestro/issues/1818).

- **Reports in HTML** ([#1750](https://github.com/mobile-dev-inc/maestro/pull/1750) by [Depa Panjie Purnama](https://github.com/depapp))

  To see it, run `maestro test --format HTML <your-flow.yaml>`

- **Homebrew is back!**

  If you prefer to switch your installation of Maestro to use Homebrew:
    1. `rm -rf ~/.maestro`
    2. `brew tap mobile-dev-inc/tap && brew install maestro` 🎉

    Script install method is still supported.

- **Current platform exposed in JavaScript** ([#1747](https://github.com/mobile-dev-inc/maestro/pull/1747) by [Dan Caseley](https://github.com/Fishbowler))

  In JavaScript, you can now access `maestro.platform` to express logic that depends on whether the test runs on iOS or Android.
- **Control airplane mode** ([#1672](https://github.com/mobile-dev-inc/maestro/pull/1672) by [NyCodeGHG](https://github.com/NyCodeGHG))

  New commands: `setAirplaneMode` and `toggleAirplaneMode`. Android-only because of iOS simulator restrictions.
- **New `killApp` command** ([#1727](https://github.com/mobile-dev-inc/maestro/pull/1727) by [Alexandre Favre](https://github.com/alexandrefavre4))

  To trigger a System-Initiated Process Death on Android. On iOS, works the same as `stopApp`.

### Bug fixes

- Fix cleaning up retries in iOS driver ([#1669](https://github.com/mobile-dev-inc/maestro/pull/1669))
- Fix some commands not respecting custom labels ([#1762](https://github.com/mobile-dev-inc/maestro/pull/1762) by [Dan Caseley](https://github.com/Fishbowler))
- Fix “Protocol family unavailable” when rerunning iOS tests ([#1671](https://github.com/mobile-dev-inc/maestro/pull/1671) by [Stanisław Chmiela](https://github.com/sjchmiela))

## 1.36.0 - 2024-02-15

- Feature: Add support for extra keys to Android TV
- Feature: Add support for pressing tab key on Android
- Feature: Add status and time to report.xml
- Fix: Extend retry to handle 404 in upload status call
- Fix: Crashes caused by toasts on Android API < 30

## 1.35.0 - 2024-01-08

- Change: Adds view class to Android hierarchy output
- Change: Improves description of maestro start-device command to include device locale as well
- Change: Adds scrollable attribute to Android view hierarchy output
- Feature: Adds childOf attribute to selector to select from children of a container
- Feature: Adds label attribute to customize the CLI output of maestro commands
- Fix: Fixing “Unsupported architecture UNKNOWN” on linux environment when calling maestro attempts to create devices
- Fix: Allow maestro to work below API level 25 for Android
- Fix: IllegalArgumentException on swipe operation for iOS if the coordinates beyond device width and height are selected

## 1.34.5 - 2024-01-04

- Feature: Adds a parameter to exclude all the keyboard elements from hierarchy

## 1.34.4 - 2023-12-27

- Fix: Failures due to swipe ranges going beyond screen dimensions
- Change: Adding escape key in `pressKey` API
- Tweak: Avoid returning `Result` in IOSDriver install and clearAppState

## 1.34.3 - 2023-11-21

- Tweak: Include scrollable attribute in view hierarchy from Android Driver
- Feature: Custom labels for readability of maestro commands
- Feature: Adding childOf selector
- Tweak: Message of start-device command to show locale as well

## 1.34.2 - 2023-11-13

- Tweak: Include view class in view hierarchy attributes from the Android driver

## 1.34.1 - 2023-11-9

- Feature: add support `--device-locale` parameter for `maestro cloud` command
- Feature: add support iOS17 for `maestro start-device` command
- Feature: add support Android API level 34 for `maestro start-device` command

## 1.34.0 - 2023-10-24

- Feature: support `--device-locale` parameter for `maestro start-device`
- Feature: add `centerElement` parameter for `scrollUntilVisible`. Center element will attempt to stop scrolling when the element is near the center of the screen.
- Feature: add `power` button support for `pressKey` on Android
- Change: add `tapOn` parameter `waitToSettleTimeoutMs` to control how long it waits to move on to the next command. Helpful for animation heavy apps.
- Change: improve executionOrder planning
- Change: improve retry mechanism to ensure openness of XCUITest Server
- Fix: improve `TimeoutException` for driver startup

## 1.33.1 - 2023-10-03

- Feature: support for multipart form data file upload in Javascript, thanks @maciejkrolik
- Fix: setPermissions produces error on Xcode 15
- Fix: Maestro studio - include enter key in command editor on initial paste

## 1.33.0 - 2023-09-21

- Feature: Adds MAESTRO_DRIVER_STARTUP_TIMEOUT to iOS driver to configure timeout to start iOS driver, used in CI/CD environment with performance limitations. Thanks, Jesse Farsong for contributing.
- Feature: Introducing the "addMedia" command that enables adding images and videos directly to the devices.
- Change: Improved Studio's user interface:
  - Updated fonts to align with company branding.
  - Introduced a distinct loading animation for better clarity when AI is processing commands.
- Fix: Crash resulting in Error: No matches found for first query match sequence: `Children matching type Other` due to resolving root element for a snapshot operation on iOS
- Fix: Android driver getting stuck when the device was disconnected
- Fix: XCTestUnreachable exceptions due to missing IPv6 config on /etc/hosts
- Fix: Handling app crash errors from XCUITest drivers gracefully
- Fix: Timeouts can be separated with `_`. For example 10_000 for 10000

## 1.32.0 - 2023-09-06

Studio

- Feature: Support writing Flows using AI (more info to come 🚀)
- Feature: Maestro Studio can now run in multiple tabs simultaneously
- Feature: Added element id and copy option for it
- Tweak: Hide action buttons till command is hovered
- Tweak: Hide Unnecessary Scrollbars
- Tweak: Repl view scroll improvements
- Tweak: Improve Maestro Studio performance
- Fix: Selected element size
- Fix: Performance issues with maestro studio device refresh
- Fix: Fixed dark mode for element id

CLI

- Feature: New command to start or create a Maestro recommended device (docs)
- Feature: Support id selection for testID with react-native-web (community contribution)
- Feature: Control if browser automatically opens when running Maestro Studio via --no-window (community contribution)
- Tweak: Show cancellation reason when available (Maestro Cloud)
- Tweak: Update selenium-java and remove webdrivermanager to support Chrome 116+
- Tweak: Show device type when running on Maestro Cloud
- Tweak: Added better messaging and recovery options for Maestro Cloud uploads (useful for CI)
- Tweak: Added better error messages for missing workspace and yaml validation errors
- Tweak: Added file name and line number in yaml parsing error messages
- Fix: Input text and erase text stability improvements for iOS
- Fix: Leaking response body on iOS & better error handling for iOS Driver
- Fix: Fixed Maestro Cloud wrong exit code when flow failed
- Fix: Debug commands parsing would crash maestro
- Fix: Cleaning up debug logs

## 1.31.0 - 2023-08-10

- Fix: Warning shown from OkHttp for leaking response bodies on CLI
  - Closing response bodies for retries done on the XCUITest driver
  - Closing response bodies for permissions
  - Removing different thread execution done on hideKeyboard
- Fix: Scroll for React native apps on screens with large view hierarchies on iOS
- Fix: Showing more descriptive errors on flow file not found during maestro cloud command.
- Fix: Input text characters being skipped or being appended later in the test on iOS
- Fix: Crash in debug output generation when maestro flow contains "/"’
- Fix: Resolved issue where tapping on the device in maestro studio produced inaccurate click locations due to incorrect coordinates. Now fixed for accurate device interaction
- Fix: In Maestro Studio, the issue of window resizing causing devices to overflow off the screen has been resolved.
- Feature: Add headers to HTTP response for API calls done with Maestro. Thanks, Jesse Willoughby! for this contribution.
- Feature: Now it is possible to configure the path with the –debug-output option for debugging information that maestro dumps in the user directory by default.
- Feature: Enhanced Maestro Studio with keyboard accessibility, streamlining navigation and facilitating the copy, run, and edit commands using the keyboard.
- Change: Fail the test if any of the onFlowStart or onFlowComplete hooks fail
- Change: Removed IDB on iOS. This may impact the performance of maestro commands like tapOn and assertVisible on iOS screens with large view hierarchies.
  - Studio and CLI will now provide insights and warnings in case the hierarchy of these screens becomes extensive.
- Change: In Maestro Studio, we've integrated screenshots of selected elements alongside their corresponding commands.
- Change: In Maestro Studio, double-clicking will now execute the command.

## 1.30.4 - 2023-07-19

- Fix: correctly resolve external parameters for onStart/Complete hooks
- Fix: reuse JSEngine for all executeCommands (hooks, main commands, subflows) actions

## 1.30.3 - 2023-07-17

- Update: Maestro Studio revamp improvements
  - wrapped element names in sidebar
  - sidebar text always visible
  - add "hintText" and "accesbilityText" in sidebar
  - improve sidebar search
  - fixed highlight issues in search
  - various other small improvements

## 1.30.2 - 2023-07-14

- Revert connection improvements (from 1.30.1)

## 1.30.1 - 2023-07-14

- Fix: Allow running `maestro studio` and `maestro test` simultaneusly
- Fix: Connection improvements

## 1.30.0 - 2023-07-13

- Feature: onFlowStart / onFlowComplete hooks
- Feature: Maestro Studio revamp
  - improved design
  - search components panel
  - improved drag-and-drop
- Feature: Introduce `--app-binary-id` parameter for Maestro Cloud upload action to be able to re-use a previously uploaded app for different flows
- Feature: Implement Experimental GraalJsEngine (ECMAScript 2022 compliant)
- Fix: Save xctest xcodebuild logs output to system temp dir
- Fix: Close existing screen recording if it was left open.
  - Thanks, @carlosmuvi, for the contribution!
- Fix: Execute sequential Flows even if no other Flows are present
- Fix: Various XCTestClient connection improvements
- Deprecate: `assertOutgoingRequestsCommand`
- Deprecate: Network Mocking feature
- Deprecate: Maestro Mock Server feature

## 1.29.0 - 2023-06-19

- Feature: Add test duration measurement and display
- Feature: New screen recording commands
  - Thanks, @tokou, for the contribution!
- Feature: Add support for sequential execution
- Feature: Add support for double taps + multiple taps in tapOn
- Feature: Add support for custom Android driver startup timeout
  - Thanks, @arildojr7, for the contribution!
- Fix: Validate workspace prior to upload to Maestro Cloud
- Fix: Resolve Android scrollUntilVisible flakiness
- Fix: Resolve inputText flakiness
- Fix: iOS url arguments
  - Thanks, @tokou, for the contribution!

## 1.28.0 - 2023-05-18

- Feature: runScript command now support conditional execution
- Feature: Improved debug output:
  - Shows failure reason when command fails
  - Generates screenshot when command fails
  - Unified most logs under ~/.maestro/tests/<date>/maestro.log
- Change: Launch arguments support for long values
- Tweak: JUnit report naming changes. Local and Cloud should now have the same naming convention.
- Tweak: Added deprecation notice for experimental features
- Fix: maestro record command was not working on iOS
- Fix: WebDriver, only scroll to elements outside of the window before tapping
- Fix: close request leaking body
- Fix: maestro cloud now will fail on timeout if configured as such

## 1.27.0 - 2023-05-02

- Feature: Adds assertOutgoingRequests to assert the network requests from the app
- Feature: Add platform condition in runFlow command to do platform-specific orchestration. Thanks, Larry Ng for your contribution!
- Feature: Adds a new selector containsDescendants. Thanks, Larry Ng for your contribution!
- Feature: iOS and Android launch arguments
- Change: Include the update command instead of update instructions in the update message. Thanks @bobpozun for your contribution!
- Fix: Fixes swipe flakiness caused due to waiting for animations to complete on XCTest
- Fix: Correctly resolving `maestro.copiedText`
- Fix: Using deviceId instead of booted, potentially resolving XCTestUnreachable exceptions.
- Fix: Improving waitForAppToSettle for Android by accounting window updates. Resolves maestro command interaction in Android 13.
- Fix: Notification permissions not getting granted
- Fix: Use correct documentation URLs in Studio

## 1.26.1 - 2023-04-13

- Fix: hideKeyboard crashing on react native apps because swipe fails on some screens

## 1.26.0 - 2023-04-13

- Feature: Adds Travel command to mock motion for app
- Feature: Adds a capability to match the toast messages
- Feature: Add support for console.log in javascript
- Feature: Allow writing inline flows with runFlow command
- Change: Adds sms permission to permission names which can be used to allow/deny: android.permission.READ_SMS, android.permission.RECIEVE_SMS, android.permission.SEND_SMS. Thanks, @depapp for the contribution.
- Change: Maestro can now also match hint text and values of text field.
- Change: Maestro can now also match elements with their accessibility text.
- Commands moved away from IDB:
  - Long press is now done with XCTest instead of idb
  - Installation of app is now done with simctl commands
  - Hide keyboard with help of XCTest. We now scroll up and down from the middle of the screen to close the keyboard.
  - Press key now is done with XCTest.
  - Note that with this change pressKey: Enter now only wraps on new line - earlier it also closed the keyboard
  - Erase text is now done with XCTest.
  - Use simctl to record screen
- Fix: Web driver no longer crashes when using latest Chrome
- Fix: Fixes hideKeyboard on android by appropriately dispatching proper event. Thanks, @nhaarman for contribution
- Fix: Properly shutting down studio by listening to SIGTSP signal
- Fix: Update granting of notifications and health permissions causing simulator restarts and XCTestUnreachableExceptions.

## 1.25.0 - 2023-03-13

- Fix: Shell environment variables can no longer crash the javascript runtime
- Fix: XCTestRunner and IDB are restarted on connection error
- Feature: Add support for setLocation

## 1.24.0 - 2023-03-07

- Change: LaunchApp command sets all app permissions to allow ([documentation](https://maestro.mobile.dev/reference/app-lifecycle))
- Feature: LaunchApp supports specifying app permission state
- Feature: On Android it is now possible to force links to be opened in the browser
- Fix: Autocorrect is no longer applied to inputText on iOS
- Fix: iOS apps with big view hierarchies (common with ReactNative and Flutter) caused an error in XCTest.framework
- Fix: Studio UI fixes for Firefox and Safari
- Fix: Element selection behavior in Maestro Studio

## 1.23.0 - 2023-02-15

- Feature: Maestro Studio - Action Modal
- Feature: Maestro Studio - Dark Mode
- Feature: assertion on `enabled`, `selected`, `checked`, `focused` properties ([documentation](https://maestro.mobile.dev/reference/assertions#assertvisible))
- Feature: running tests in a deterministic order ([documentation](https://maestro.mobile.dev/cli/test-suites-and-reports#deterministic-ordering))
- Feature: default global tags can now be set in `config.yaml` ([documentation](https://maestro.mobile.dev/cli/tags#global-tags))
- Feature: allow to configure what flows should be included into a run at `config.yaml` level ([documentation](https://maestro.mobile.dev/cli/test-suites-and-reports#controlling-what-tests-to-include))
- Tweak: considerable speed-up of iOS tests due to removal of unnecessary hierarchy polling
- Tweak: wait for app to settle before proceeding with iOS test
- Tweak: UX improvements in "delete command" confirmation dialog
- Tweak: using `xcrun` for uninstall command on iOS
- Tweak: using `xcrun` for clearKeychain command on iOS
- Tweak: using `.maestro` directory by default for mockserver deploy command
- Fix: errors were clipped in Maestro Studio
- Fix: use element title as id in Web driver
- Fix: Repeat-while-true did not work properly with JavaScript conditions
- Fix: Repeat-times did not work properly with JavaScript input
- Fix: added artificial delay after key presses (i.e. "back" key) on Android

## 1.22.1 - 2023-02-09

- Early Access Feature: Maestro Mock Server and Maestro SDK (Android preview)
- Tweak: added visibility threshold and scroll speed to `scrollUntilVisible` command
- Tweak: speed up `tapOn` command on iOS
- Fix: removing view hierarchy elements that are out of screen bounds
- Fix: `inputText` command skipping characters on iOS
- Fix: Reworked `clearAppState` behaviour on iOS, solving issue that caused crashes after clearing the state
- Fix: crash when running multiple Maestro sessions in parallel while using iOS device
- Fix: a rare crash in React Native apps when trying to input a long string on iOS
- Fix: properly handling linebreaks in Maestro Studio

## 1.21.3 - 2023-01-30

- Fix: `scrollUntilVisible` was not always working on iOS
- Tweak: speed up tests by skipping an unnecessary hierarchy poll
- Tweak: iOS screenshot no longer depends on IDB and is faster

## 1.21.2 - 2023-01-26

- Hotfix: Move iOS tap() implementation back to IDB to resolve problems with React Native apps
- Fix: running multiple Maestro instances would sometimes result in Connection exception
- Fix: support JS injection in `scrollUntilVisible` command

## 1.21.1 - 2023-01-25

- Fix: Increase typing speed for iOS text input

## 1.21.0 - 2023-01-25

- Feature: Next evolution of Maestro Studio
- Fix: More robust implementation of inputText on iOS
- Fix: More robust implementation of tap on iOS
- Experimental: Added web driver

## 1.20.0 - 2023-01-24

- Feature: Maestro Studio - use percentage-based swiping
- Feature: Scroll until view element is visible
- Feature: Relatively swipe with percentage based start and end coordinates
- Fix: Android tap was not always working
- Fix: Bottom of Android hierarchy was cut off
- Fix: idb_companion fails to start due to gRPC timeout exception
- Tweak: Improve Android Screenshot Internal Logic
- Tweak: Change the end coordinates for swipe element
- Tweak: Update sample flows

## 1.19.5 - 2023-01-19

- Fix: inputText was not working on iOS React Native apps
- Fix: Maestro fails to launch on iOS if --device parameter is present
- Fix: Evaluate JS scripts with element selector in swipe command
- Tweak: added tags to sample flows
- Tweak: indicating whether build is running on CI in analytics

## 1.19.2 - 2023-01-17

- Hotfix: Maestro Studio was not working

## 1.19.1 - 2023-01-17

- Feature: generating test report from `maestro cloud` output
- Fix: in rare cases, maestro cloud was computing progress bar as negative value
- Fix: local test suite included non-flow files
- Fix: some special characters were not allowed in env variables (i.e. `&`)
- Fix: vertical scrolling was sometimes not working on iOS
- Fix: if a text string is an invalid regex, treat it as a regular string instead
- Fix: scroll and swipe commands on iOS were throwing an error when running in parallel with Maestro Studio
- Tweak: print out valid inputs for `--format` parameter in `maestro test` and `maestro upload`
- Tweak: removed Maestro Studio warning related to parallel execution
- Refactor: making XCTestDriver configurable

## 1.19.0 - 2023-01-13

- Feature: iOS unicode input support + non-English keyboards
- Feature: `swipe` command now supports `from` argument to swipe from a given view
- Feature: `repeat` command now supports `while` condition
- Feature: Allowing `extendedWaitUntil` command to use env values in `timeout` property
- Tweak: assert commands now respect `optional` flag
- Tweak: error analytics
- Fix: scroll not working reliably on iOS
- Fix: `openLink` was opening Google Maps on Android
- Fix: sub-flows are now included regardless of their tags
- Fix: Maestro Studio was not always computing `index` field correctly
- Fix: `maestro upload` was ignoring JS files
- Fix: `openLink` command now supports query parameters

## 1.18.5 - 2023-01-10

- Feature: tags
- Tweak: allow running other maestro commands alongside Maestro Studio
- Tweak: improved matching for strings with linebreaks
- Fix: creating maestro logs directory was not always working properly
- Fix: maestro studio was not working properly on Kubuntu

## 1.18.3 - 2022-12-27

- XCUITest driver improvements and fixes:
  - Close the response when validating server up
  - Add logs to uninstall of runner
  - Remove redundant import and library from maestro-ios
  - Kills the process before we uninstall it
  - Redirect runner logs in xctest_runner_logs directory

## 1.18.2 - 2022-12-27

- Fix: Wait for XCUITest server to start before proceeding

## 1.18.1 - 2022-12-27

- Fix: Create XCUITest driver HTTP server on loopback address
- Fix: Create parity with idb for `text` attribute with following priority:
  - Title
  - Label
  - Value

## 1.18.0 - 2022-12-26

- Feature: Adds new XcUITest driver to capture view hierarchy on iOS.
  - Fixes stability issues on iOS 16
  - Fixes not identified bottom navigation tabs
  - Gets view hierarchy natively from XCUITest
- Fix: Missing letter j and y in inputRandomText command
- Tweak: Un-deprecate the hierarchy command, inform about Studio
- Tweak: Match negative bounds as well in maestro studio
- Feature: Adds replay functionality in maestro studio
- Feature: Adding device interaction to interact page in Maestro Studio

## 1.17.4 - 2022-12-15

- Fix: Maestro commands were failing if Android SDK wasn't installed

## 1.17.3 - 2022-12-15

- Feature: no-ansi version for terminals that do not ANSI
- Feature: Android Maven artifact for setting up network mocking
- Fix: Android emulator was not discovered properly if it wasn't on PATH
- Fix: missing favicon

## 1.17.2 - 2022-12-13

- Tweak: Deprecate hierachy and query CLI commands

## 1.17.1 - 2022-12-12

- Tweak: Remove Maestro Studio icon from Mac dock
- Tweak: Prefer port 9999 for Maestro Studio app
- Fix: Fix Maestro Studio conditional code snippet

## 1.17.0 - 2022-12-12

- Feature: Maestro Studio
- Feature: Print a message when an update is available
- Feature: Support percentages for tapOn
- Fix: Maestro commands execute faster now
- Fix: Fix environment variable substitution in certain cases
- Fix: Use actual android device screen size (including nav bar)

## 1.16.4 - 2022-12-02

- Fix: Add error message for when an Android screen recording fails

## 1.16.3 - 2022-12-02

- Fix: Fix iOS `clearState` not working in certain cases
- Fix: Fix `maestro record` not capturing full launch screen recording

## 1.16.2 - 2022-12-02

- Fix: older version of Maestro Driver on Android was not always updated

## 1.16.1 - 2022-11-30

- Feature: `maestro record` command
- Fix: `z` character was not inputted correctly on Android

## 1.16.0 - 2022-11-29

- Feature: Javascript injection support
  - `runScript` and `evalScript` commands to run scripts
  - `assertTrue` command to assert based on Javascript
  - `runFlow` can be launched based on Javascript condition
  - `copyTextFrom` now also stores result in `maestro.copiedText` variable
  - Env parameters are now treated as Javascript variables
- Feature: HTTP(s) requests
  - `http.request()` Javascript API that allows to make HTTP requests as part of Maestro flows
- Feature: Maestro Cloud `--android-api-level` parameter to select API version to be used
- Feature: `waitForAnimationToEnd` command to wait until animations/videos are finished
- Tweak: test reports can now be generated for single test runs (and not just folders)
- Tweak: `inputText` on Android was reworked to increase speed and input stability
- Tweak: `eraseText` is now much faster
- Tweak: `maestro cloud` will automatically retry upload up to 3 times
- Fix: running on Samsung devices was sometimes failing because of wrong user being used

## 1.15.0 - 2022-11-17

- Feature: run all tests in a folder as a suite
- Feature: XML test report in JUnit-compatible format
- Feature: `copyTextFrom` command for copying text from a view
- Feature: `maestro bugreport` command for capturing Maestro logs
- **Breaking change**: Removed `clipboardPaste` command in favour of new `pasteText` command
- Fix: Java 8 compatibility issue for M1 users
- Fix: `_` character was mapped incorrectly on iOS
- Fix: first `tapOn` command was failing unless it was preceeded by `launchApp` or `openLink`
- Tweak: Maestro no longer kills running `idb_companion` processes
- Tweak: updated gRPC version to 1.52.0

## 1.14.0 - 2022-11-14

- Fix: passing env parameters to subflows and other env params
- Speeding up maestro flows
- Checking in maestro sample flows and adds sample updating guide
- Maestro is now compatible with java 8!
- Launching app without stopping the app
- Fixing launching app when resolving launcher activity throws `NullPointerException`

## 1.13.2 - 2022-11-10

- Fix: Fallback properly on monkey when start-activity command fails, when launching app.

## 1.13.1 - 2022-11-09

- Fix: Fix maestro hanging with message "Waiting for idb service to start.."
- Fix: Fix clearState operation not working on iOS

## 1.13.0 - 2022-11-08

- Feature: Option to set direction and speed for swipe command
- Fix: Fix duplicate and unavailable iOS simulators in list
- Fix: Longer timeout for iOS simulator boot

## 1.12.0 - 2022-11-06

- Feature: `maestro cloud` command added

## 1.11.4 - 2022-11-02

- Fix: Use absolute path to prevent NullPointerException when .app folder is in the cwd
- Fix: Create parent directory if not exists when generating adb key pair, updates dadb to 1.2.6
- Fix: Opening of leak canary app
- Tweak: send agent: ci when known CI environment variables are set

## 1.11.3 - 2022-10-29

- Fix: updating to dadb 1.2.4

## 1.11.2 - 2022-10-29

- Fix: updating to dadb 1.2.3 to fix an occassional device connection issue
- Fix: injecting `env` parameters into conditions (i.e. in `runFlow`)

## 1.11.1 - 2022-10-27

- Fix: closing `idb_companion` after `maestro` completes

## 1.11.0 - 2022-10-26

- Feature: `maestro` will offer user to select a device if one is not running already
- Feature: `env` variables can be inlined in flow file or in `runFlow` command
- **Breaking change**: `--platform` option is deprecated. CLI now prompts user to pick a device.
- Tweak: auto-starting `idb_companion`. No need to start it manually anymore.
- Tweak: tripled Android Driver launch timeout
- Tweak: customisable error resolution in Orchestra
- Fix: `maestro upload` was not ignoring `-e` parameters

## 1.10.1 - 2022-10-12

- Fix: login command fails with java.lang.IllegalStateException: closed

## 1.10.0 - 2022-10-12

- Feature: `repeat` command that allows to create loops
- Feature: conditional `runFlow` execution that allows to create if-conditions
- Feature: `inputRandomText`, `inputRandomNumber`, `inputRandomEmail` and `inputRandomPersonName` commands (thanks @ttpho !)
- Feature: `clipboardPaste` command (thanks @depapp !)
- Feature: Added `enabled` property to element selector
- Feature: Added `download-samples` command to allow quickstart without having to build your own app
- Feature: Added `login` and `logout` commands for interacting with mobile.dev
- **Breaking change:** `upload` now takes 1 less argument. `uploadName` parameter was replaced with `--name` optional argument
- Tweak: `upload` command automatically zips iOS apps
- Tweak: sending `agent: cli` value alongside `upload` and `login` commands
- Fix: properly compare fields that contain regex special symbols
- Fix: input text on Android was sometimes missing characters

## 1.9.0 - 2022-09-30

- Feature: USB support for Android devices

## 1.8.3 - 2022-09-28

- Fix: occasional crash when an iOS layout has a view group with a 0 width
- Fix: properly mapping top-level syntax errors

## 1.8.2 - 2022-09-27

- Tweak: prioritise clickable elements over non-clickable ones
- Fix: close TCP forwarder if it is already in use
- Fix: hideKeyboard on Android did not always work

## 1.8.1 - 2022-09-27

- Fix: Timeout exception while opening port for tcp forwarding

## 1.8.0 - 2022-09-22

- Feature: `runFlow` command
- Tweak: support of Tab Bar on iOS
- Tweak: added `--mapping` option to `upload` CLI command
- Fix: open the main launcher screen on Android instead of Leak Canary
- Fix: input character-by-character on Android to counter adb issue where not the whole text gets transferred to the device

## 1.7.2 - 2022-09-20

- Fix: `tapOn` command was failing due to a failure during screenshot capture

## 1.7.1 - 2022-09-19

- Feature: `clearState` command
- Feature: `clearKeychain` command
- Feature: `stopApp` command
- Tweak: Maestro now compares screenshots to decide whether screen has been updated
- Tweak: `launchApp` command now supports env parameters

## 1.7.0 - 2022-09-16

- Feature: `maestro upload` command for uploading your builds to mobile.dev
- Feature: `takeScreenshot` command
- Feature: `extendedWaitUntil` command
- Fix: waiting for Android gRPC server to properly start before interacting with it
- Fix: brought back multi-window support on Android
- Fix: `hideKeyboard` command did not always work
- Fix: make project buildable on Java 14
- Refactoring: make `MaestroCommand` serializable without custom adapters
- Refactoring: migrated to JUnit 5

## 1.6.0 - 2022-09-13

- Feature: hideKeyboard command
- Feature: add Android TV Remote navigation
- Tweak: allowing to skip package name when searching by `id`
- Fix: Android WebView contents were sometimes not reported as part of the view hierarchy
- Fix: iOS inputText race condition
- Fix: populate iOS accessibility value
- Refactoring: simplified `MaestroCommand` serialization

## 1.5.0 - 2022-09-08

- Temporary fix: showing an error when unicode characters are passed to `inputText`
- Feature: `eraseText` command

## 1.4.2 - 2022-09-06

- Fix: Android devices were not discoverable in some cases

## 1.4.1 - 2022-09-05

- Fix: relative position selectors (i.e. `below`) were sometimes picking a wrong view
- Fix: await channel termination when closing a gRPC ManagedChannel
- Fix: Android `inputText` did not work properly when a string had whitespaces in it
- Fix: race condition in iOS `inputText`

## 1.4.0 - 2022-08-29

- Added `traits` selector.
- Relative selectors (such as `above`, `below`, etc.) are now picking the closest element.
- Fix: continuous mode did not work for paths without a parent directory
- Fix: workaround for UiAutomator content descriptor crash
- Fix: `tapOn: {int}` did not work

## 1.3.6 - 2022-08-25

- Added `longPressOn` command
- Decreased wait time in apps that have a screen overlay
- Fixed CLI issue where status updates would not propagate correctly

## 1.3.3 - 2022-08-23

- Fix: iOS accessibility was not propagated to Maestro

## 1.3.2 - 2022-08-22

- Fix: env parameters did not work with init flows when using `Maestro` programmatically

## 1.3.1 - 2022-08-19

- Added support for externally supplied parameters
- Added `openLink` command

## 1.2.6 - 2022-08-18

- Fail launching an iOS app if the app is already running

## 1.2.4 - 2022-08-17

- Add support for cli to specify what platform, host and port to connect to

## 1.2.3 - 2022-08-15

- Added support of iOS state restoration
- Exposing `appId` field as part of `MaestroConfig`

## 1.2.2 - 2022-08-08

- Update `Orchestra` to support state restoration

## 1.2.1 - 2022-08-04

- Update `YamlCommandReader` to accept Paths instead of Files to support zip Filesystems

## 1.2.0 - 2022-08-04

- Config is now defined via a document separator
- launchApp no longer requires and appId
- initFlow config implemented

## 1.1.0 - 2022-07-28

- `launchApp` command now can optionally clear app state
- `config` command to allow Orchestra consumers a higher degree of customization
- Fixed a bug where `ElementNotFound` hierarchy field was not declared as public

## 1.0.0 - 2022-07-20

- Initial Maestro release (formerly known as Conductor)
