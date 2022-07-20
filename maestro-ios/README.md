# iOS Device Config

A wrapper around idb to communicate with iOS devices.

## Prerequisites

### idb
To test on iOS devices you need to install [Facebook's idb tool](https://fbidb.io/).

### Xcode
Install Xcode 13 (command line tools are not enough, install the full IDE).

### IntelliJ setup
If you are working with this module, update your IntelliJ config (Help -> Edit Custom Properties) by including the following lines:

```
# Needed for working with idb.proto definition
idea.max.intellisense.filesize=4000
```

Restart the IDE

## Testing on iOS devices

1. List the devices with `idb_companion --list 1`
2. Pick a simulator from the list and note its UDID
3. Launch the simulator with `idb_companion --boot {udid}`
4. Connect to the simulator with `idb_companion --udid {udid}`

You are good to go!
