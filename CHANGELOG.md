# Changelog

## 1.16.0 - 2022-11-29
* Feature: Javascript injection support
  * `runScript` and `evalScript` commands to run scripts
  * `assertTrue` command to assert based on Javascript
  * `runFlow` can be launched based on Javascript condition
  * `copyTextFrom` now also stores result in `maestro.copiedText` variable
  * Env parameters are now treated as Javascript variables 
* Feature: HTTP(s) requests
  * `http.request()` Javascript API that allows to make HTTP requests as part of Maestro flows
* Feature: Maestro Cloud `--android-api-level` parameter to select API version to be used
* Feature: `waitForAnimationToEnd` command to wait until animations/videos are finished
* Tweak: test reports can now be generated for single test runs (and not just folders)
* Tweak: `inputText` on Android was reworked to increase speed and input stability
* Tweak: `eraseText` is now much faster
* Tweak: `maestro cloud` will automatically retry upload up to 3 times
* Fix: running on Samsung devices was sometimes failing because of wrong user being used

## 1.15.0 - 2022-11-17
* Feature: run all tests in a folder as a suite
* Feature: XML test report in JUnit-compatible format
* Feature: `copyTextFrom` command for copying text from a view
* Feature: `maestro bugreport` command for capturing Maestro logs
* **Breaking change**: Removed `clipboardPaste` command in favour of new `pasteText` command 
* Fix: Java 8 compatibility issue for M1 users
* Fix: `_` character was mapped incorrectly on iOS
* Fix: first `tapOn` command was failing unless it was preceeded by `launchApp` or `openLink`
* Tweak: Maestro no longer kills running `idb_companion` processes
* Tweak: updated gRPC version to 1.52.0

## 1.14.0 - 2022-11-14
* Fix: passing env parameters to subflows and other env params
* Speeding up maestro flows
* Checking in maestro sample flows and adds sample updating guide
* Maestro is now compatible with java 8!
* Launching app without stopping the app
* Fixing launching app when resolving launcher activity throws `NullPointerException`

## 1.13.2 - 2022-11-10
* Fix: Fallback properly on monkey when start-activity command fails, when launching app.

## 1.13.1 - 2022-11-09
* Fix: Fix maestro hanging with message "Waiting for idb service to start.."
* Fix: Fix clearState operation not working on iOS 

## 1.13.0 - 2022-11-08

* Feature: Option to set direction and speed for swipe command
* Fix: Fix duplicate and unavailable iOS simulators in list
* Fix: Longer timeout for iOS simulator boot

## 1.12.0 - 2022-11-06

* Feature: `maestro cloud` command added

## 1.11.4 - 2022-11-02

* Fix: Use absolute path to prevent NullPointerException when .app folder is in the cwd
* Fix: Create parent directory if not exists when generating adb key pair, updates dadb to 1.2.6
* Fix: Opening of leak canary app
* Tweak: send agent: ci when known CI environment variables are set

## 1.11.3 - 2022-10-29

* Fix: updating to dadb 1.2.4

## 1.11.2 - 2022-10-29

* Fix: updating to dadb 1.2.3 to fix an occassional device connection issue
* Fix: injecting `env` parameters into conditions (i.e. in `runFlow`)

## 1.11.1 - 2022-10-27

* Fix: closing `idb_companion` after `maestro` completes

## 1.11.0 - 2022-10-26

* Feature: `maestro` will offer user to select a device if one is not running already
* Feature: `env` variables can be inlined in flow file or in `runFlow` command
* **Breaking change**: `--platform` option is deprecated. CLI now prompts user to pick a device.
* Tweak: auto-starting `idb_companion`. No need to start it manually anymore.
* Tweak: tripled Android Driver launch timeout
* Tweak: customisable error resolution in Orchestra
* Fix: `maestro upload` was not ignoring `-e` parameters

## 1.10.1 - 2022-10-12

* Fix: login command fails with java.lang.IllegalStateException: closed

## 1.10.0 - 2022-10-12

* Feature: `repeat` command that allows to create loops
* Feature: conditional `runFlow` execution that allows to create if-conditions
* Feature: `inputRandomText`, `inputRandomNumber`, `inputRandomEmail` and `inputRandomPersonName` commands (thanks @ttpho !)
* Feature: `clipboardPaste` command (thanks @depapp !)
* Feature: Added `enabled` property to element selector
* Feature: Added `download-samples` command to allow quickstart without having to build your own app
* Feature: Added `login` and `logout` commands for interacting with mobile.dev
* **Breaking change:** `upload` now takes 1 less argument. `uploadName` parameter was replaced with `--name` optional argument
* Tweak: `upload` command automatically zips iOS apps
* Tweak: sending `agent: cli` value alongside `upload` and `login` commands
* Fix: properly compare fields that contain regex special symbols
* Fix: input text on Android was sometimes missing characters

## 1.9.0 - 2022-09-30

* Feature: USB support for Android devices

## 1.8.3 - 2022-09-28

* Fix: occasional crash when an iOS layout has a view group with a 0 width
* Fix: properly mapping top-level syntax errors

## 1.8.2 - 2022-09-27

* Tweak: prioritise clickable elements over non-clickable ones
* Fix: close TCP forwarder if it is already in use
* Fix: hideKeyboard on Android did not always work

## 1.8.1 - 2022-09-27

* Fix: Timeout exception while opening port for tcp forwarding

## 1.8.0 - 2022-09-22

* Feature: `runFlow` command
* Tweak: support of Tab Bar on iOS
* Tweak: added `--mapping` option to `upload` CLI command
* Fix: open the main launcher screen on Android instead of Leak Canary
* Fix: input character-by-character on Android to counter adb issue where not the whole text gets transferred to the device 

## 1.7.2 - 2022-09-20

* Fix: `tapOn` command was failing due to a failure during screenshot capture

## 1.7.1 - 2022-09-19

* Feature: `clearState` command
* Feature: `clearKeychain` command
* Feature: `stopApp` command
* Tweak: Maestro now compares screenshots to decide whether screen has been updated
* Tweak: `launchApp` command now supports env parameters

## 1.7.0 - 2022-09-16

* Feature: `maestro upload` command for uploading your builds to mobile.dev
* Feature: `takeScreenshot` command
* Feature: `extendedWaitUntil` command
* Fix: waiting for Android gRPC server to properly start before interacting with it
* Fix: brought back multi-window support on Android
* Fix: `hideKeyboard` command did not always work
* Fix: make project buildable on Java 14
* Refactoring: make `MaestroCommand` serializable without custom adapters
* Refactoring: migrated to JUnit 5

## 1.6.0 - 2022-09-13

* Feature: hideKeyboard command
* Feature: add Android TV Remote navigation
* Tweak: allowing to skip package name when searching by `id`
* Fix: Android WebView contents were sometimes not reported as part of the view hierarchy
* Fix: iOS inputText race condition
* Fix: populate iOS accessibility value
* Refactoring: simplified `MaestroCommand` serialization 

## 1.5.0 - 2022-09-08

* Temporary fix: showing an error when unicode characters are passed to `inputText`
* Feature: `eraseText` command

## 1.4.2 - 2022-09-06

* Fix: Android devices were not discoverable in some cases

## 1.4.1 - 2022-09-05

* Fix: relative position selectors (i.e. `below`) were sometimes picking a wrong view
* Fix: await channel termination when closing a gRPC ManagedChannel
* Fix: Android `inputText` did not work properly when a string had whitespaces in it
* Fix: race condition in iOS `inputText`

## 1.4.0 - 2022-08-29

* Added `traits` selector.
* Relative selectors (such as `above`, `below`, etc.) are now picking the closest element.
* Fix: continuous mode did not work for paths without a parent directory
* Fix: workaround for UiAutomator content descriptor crash
* Fix: `tapOn: {int}` did not work

## 1.3.6 - 2022-08-25

* Added `longPressOn` command
* Decreased wait time in apps that have a screen overlay
* Fixed CLI issue where status updates would not propagate correctly

## 1.3.3 - 2022-08-23

* Fix: iOS accessibility was not propagated to Maestro

## 1.3.2 - 2022-08-22

* Fix: env parameters did not work with init flows when using `Maestro` programmatically

## 1.3.1 - 2022-08-19

* Added support for externally supplied parameters
* Added `openLink` command

## 1.2.6 - 2022-08-18

* Fail launching an iOS app if the app is already running

## 1.2.4 - 2022-08-17

* Add support for cli to specify what platform, host and port to connect to

## 1.2.3 - 2022-08-15

* Added support of iOS state restoration
* Exposing `appId` field as part of `MaestroConfig`

## 1.2.2 - 2022-08-08

* Update `Orchestra` to support state restoration

## 1.2.1 - 2022-08-04

* Update `YamlCommandReader` to accept Paths instead of Files to support zip Filesystems

## 1.2.0 - 2022-08-04

* Config is now defined via a document separator
* launchApp no longer requires and appId
* initFlow config implemented

## 1.1.0 - 2022-07-28

* `launchApp` command now can optionally clear app state
* `config` command to allow Orchestra consumers a higher degree of customization
* Fixed a bug where `ElementNotFound` hierarchy field was not declared as public

## 1.0.0 - 2022-07-20

* Initial Maestro release (formerly known as Conductor)
