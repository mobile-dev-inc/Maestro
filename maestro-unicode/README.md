# Maestro Unicode Support

This module provides comprehensive Unicode text input support for the Maestro mobile testing framework on Android devices.

## Overview

The `maestro-unicode` module addresses the limitation where Maestro could only input ASCII characters on Android devices. This implementation provides:

- **Full Unicode Support**: Input text in any language (Arabic, Chinese, Japanese, Korean, Hebrew, Thai, Hindi, etc.)
- **Emoji Support**: Complete emoji input including complex sequences
- **RTL Language Support**: Proper handling of right-to-left languages
- **Zero Configuration**: Works out of the box with sensible defaults
- **Fallback Mechanisms**: Multiple fallback strategies for maximum compatibility

## How It Works

1. **Detects Unicode text** in your test flows
2. **Downloads ADB Keyboard APK** at runtime from [senzhk/ADBKeyBoard](https://github.com/senzhk/ADBKeyBoard)
   - Downloads to temporary file on host machine
   - Installs via ADB to Android device/emulator
   - Cleans up temporary file after installation
3. **Switches** to the Unicode keyboard temporarily
4. **Inputs text** reliably using broadcast messages
5. **Restores** original keyboard after input
6. **Falls back** to clipboard/character methods if needed

## Usage

Unicode input works automatically in your test flows:

```yaml
- inputText: "Hello 世界 🌍"  # Works seamlessly
- inputText: "مرحبا بك"      # Arabic text
- inputText: "こんにちは"      # Japanese text
```

## Configuration

Optional YAML configuration:

```yaml
unicode:
  enabled: true
  autoInstallKeyboard: true
  fallbackToCharInput: true
```

## Dependencies

- **ADB Keyboard**: Downloaded at runtime from GitHub (GPL-2.0 licensed)
  - No binary files stored in repository
  - Automatic download and installation
  - Temporary files cleaned up after use
- **Android SDK**: ADB for device communication
- **Network access**: Required for initial APK download

## Testing

```bash
# Run Unicode tests
./gradlew :maestro-unicode:test

# Test with real device
maestro test unicode-comprehensive-test.yaml
```