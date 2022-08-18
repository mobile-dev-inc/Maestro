# Maestro 🎹

Maestro is a declarative UI automation library for Android and iOS.

Documentation for Maestro can be found at [maestro.mobile.dev](https://maestro.mobile.dev)

## Why Maestro?

Maestro is built on learnings from its predessessors (Appium, Espresso, UIAutomator, XCTest)

- Built-in tolerance to flakiness. UI elements will not always be where you expect them, screen tap will not always go through, etc. Maestro embrases the instability of mobile applications and devices and tries to counter it.
- Built-in tolerance to delays. No need to pepper your tests with `sleep()` calls. Maestro knows that it might take time to load the content (i.e. over the network) and automaitcally waits for it (but no longer than required).
- Blazingly fast iteration. Tests are interpreted, no need to compile anything. Maestro is able to continuously monitor your test files and rerun them as they change.
- Declarative yet powerful syntax. Define your tests in a `yaml` file.
- Simple setup. Maestro is a single binary that works anywhere.

## Quick Start

Get a CLI tool from homebrew

```
brew tap mobile-dev-inc/tap
brew install maestro
```

Write a simple test in a YAML file 

```
# flow.yaml

appId: your.package.name
---
- launchApp
- tapOn: "Text on the screen"
```

Make sure an Android _emulator_ is running (support of testing via USB is work in progress).

Run it!

```
maestro test flow.yaml
```

## iOS Support

Only iOS Simulators are supported at the moment.

For Maestro to work with iOS you would need to do few extra steps. 

Install [Facebook IDB](https://fbidb.io/) tool

```
brew tap facebook/fb
brew install idb-companion
```

And launch it:

```
idb_companion --udid {id of the iOS device}
```

# Next steps

- [Learn more about Maestro features](https://maestro.mobile.dev/guides/using-maestro-cli)
- [Learn how to use Maestro programmatically](https://maestro.mobile.dev/guides/using-maestro-programmatically)

