# Changelog

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
