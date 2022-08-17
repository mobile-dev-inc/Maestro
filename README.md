# Maestro 🎹

Maestro is a platform-agnostic UI automation library for Android and iOS.

Documentation for Maestro can be found at [maestro.mobile.dev](https://maestro.mobile.dev)

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

