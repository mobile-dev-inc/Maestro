# Conductor

Conductor is a platform-agnostic UI automation library for Android and iOS.

## Quick Start

Get a CLI tool from homebrew

```
brew tap mobile-dev-inc/tap
brew install conductor
```

Write a simple test in a YAML file 

```
# flow.yaml

- launchApp: your.package.name
- tapOn:
    text: "Text on the screen"
```

Make sure an Android _emulator_ is running (support of testing via USB is work in progress).

Run it!

```
conductor test flow.yaml
```

## iOS Support

Only iOS Simulators are supported at the moment.

For Conductor to work with iOS you would need to do few extra steps. 

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

- [Learn more about Conductor features](https://conductor.mobile.dev/guides/using-conductor-cli)
- [Learn how to use Conductor programmatically](https://conductor.mobile.dev/guides/using-conductor-programmatically)

Documentation for Conductor can be found at [conductor.mobile.dev](https://conductor.mobile.dev)
