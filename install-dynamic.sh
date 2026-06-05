#!/bin/sh
# Installs the Maestro fork with --shard-split-dynamic support.
# Drop-in replacement for the official https://get.maestro.mobile.dev installer.
#
# Usage:
#   curl -Ls https://raw.githubusercontent.com/thamys-moraes/Maestro/feature/dynamic-sharding/install-dynamic.sh | bash

set -e

RELEASE_URL="https://github.com/thamys-moraes/Maestro/releases/download/v2.6.0-dynamic/maestro-dynamic-macos.zip"
INSTALL_DIR="$HOME/.maestro"

echo "Installing Maestro fork (v2.6.0-dynamic)..."

# Remove previous installation
rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"

# Download and extract
TMP=$(mktemp -d)
curl -Ls "$RELEASE_URL" -o "$TMP/maestro.zip"
unzip -q "$TMP/maestro.zip" -d "$TMP"
cp -r "$TMP/maestro/bin" "$INSTALL_DIR/bin"
cp -r "$TMP/maestro/lib" "$INSTALL_DIR/lib"
rm -rf "$TMP"

# Ensure PATH
if ! echo "$PATH" | grep -q "$INSTALL_DIR/bin"; then
  echo "export PATH=\"\$HOME/.maestro/bin:\$PATH\"" >> "$HOME/.zshrc" 2>/dev/null || true
  echo "export PATH=\"\$HOME/.maestro/bin:\$PATH\"" >> "$HOME/.bash_profile" 2>/dev/null || true
fi

export PATH="$INSTALL_DIR/bin:$PATH"
echo "Installed: $(maestro --version)"
