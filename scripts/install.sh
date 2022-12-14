#!/bin/bash
# Inspired by Sdkman setup script

which_maestro=$(which maestro)
if [[ "$which_maestro" == "/usr/local"* || $which_maestro == "/opt/homebrew"* || $which_maestro == "/home/linuxbrew"* ]]; then
  echo "Your maestro installation is already managed by a homebrew"
  echo ""
  echo "Update to the latest version with:"
  echo ""
  echo "    brew upgrade maestro"
  echo ""
  echo "Or delete brew installation with:"
  echo ""
  echo "    brew uninstall maestro"
  echo ""
  echo "Then re-run this script."
  exit 1
fi

if ! command -v java > /dev/null; then
	echo "java not found."
	echo "======================================================================================================"
	echo " Please install java on your system using your favourite package manager."
	echo ""
	echo " Restart after installing java."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

if ! command -v unzip > /dev/null; then
	echo "unzip not found."
	echo "======================================================================================================"
	echo " Please install unzip on your system using your favourite package manager."
	echo ""
	echo " Restart after installing unzip."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

if ! command -v curl > /dev/null; then
	echo "curl not found."
	echo ""
	echo "======================================================================================================"
	echo " Please install curl on your system using your favourite package manager."
	echo ""
	echo " Restart after installing curl."
	echo "======================================================================================================"
	echo ""
	exit 1
fi

if [ -z "$MAESTRO_DIR" ]; then
    MAESTRO_DIR="$HOME/.maestro"
    MAESTRO_BIN_DIR_RAW='$HOME/.maestro/bin'
else
    MAESTRO_BIN_DIR_RAW="$MAESTRO_DIR/bin"
fi
export MAESTRO_DIR

# Local variables
maestro_tmp_folder="${MAESTRO_DIR}/tmp"
maestro_bash_profile="${HOME}/.bash_profile"
maestro_profile="${HOME}/.profile"
maestro_bashrc="${HOME}/.bashrc"
maestro_zshrc="${ZDOTDIR:-${HOME}}/.zshrc"

# OS specific support (must be 'true' or 'false').
cygwin=false;
darwin=false;
solaris=false;
freebsd=false;
case "$(uname)" in
    CYGWIN*)
        cygwin=true
        ;;
    Darwin*)
        darwin=true
        ;;
    SunOS*)
        solaris=true
        ;;
    FreeBSD*)
        freebsd=true
esac

echo "* Create distribution directories..."
mkdir -p "$maestro_tmp_folder"


if [ -z "$MAESTRO_VERSION" ]; then
    download_url="https://github.com/mobile-dev-inc/maestro/releases/latest/download/maestro.zip"
else
    download_url="https://github.com/mobile-dev-inc/maestro/releases/download/cli-$MAESTRO_VERSION/maestro.zip"
fi

maestro_zip_file="${maestro_tmp_folder}/maestro.zip"
echo "* Downloading..."
curl --fail --location --progress-bar "$download_url" > "$maestro_zip_file"

echo "* Checking archive integrity..."
ARCHIVE_OK=$(unzip -qt "$maestro_zip_file" | grep 'No errors detected in compressed data')
if [[ -z "$ARCHIVE_OK" ]]; then
	echo "Downloaded zip archive is corrupt. Are you connected to the internet?"
	exit
fi

# Extract archive
echo "* Extracting archive..."
if [[ "$cygwin" == 'true' ]]; then
	maestro_tmp_folder=$(cygpath -w "$maestro_tmp_folder")
	maestro_zip_file=$(cygpath -w "$maestro_zip_file")
fi
unzip -qo "$maestro_zip_file" -d "$maestro_tmp_folder"

# Copy in place
echo "* Copying archive contents..."
cp -rf "${maestro_tmp_folder}"/maestro/* "$MAESTRO_DIR"

# Clean up
echo "* Cleaning up..."
rm -rf "$maestro_tmp_folder"/maestro
rm -rf "$maestro_zip_file"

echo ""

# Installing
if [[ $darwin == true ]]; then
  touch "$maestro_bash_profile"
  if ! command -v maestro > /dev/null; then
    echo "Adding maestro to your PATH in $maestro_bash_profile"
    echo 'export PATH=$PATH:'"$MAESTRO_BIN_DIR_RAW" >> "$maestro_bash_profile"
  fi
else
  echo "Attempt update of interactive bash profile on regular UNIX..."
  touch "${maestro_bashrc}"
  if ! command -v maestro > /dev/null; then
    echo "Adding maestro to your PATH in $maestro_bashrc"
    echo 'export PATH=$PATH:'"$MAESTRO_BIN_DIR_RAW" >> "$maestro_bashrc"
  fi
fi

touch "$maestro_zshrc"
if ! command -v maestro > /dev/null; then
  echo "Adding maestro to your PATH in $maestro_zshrc"
  echo 'export PATH=$PATH:'"$MAESTRO_BIN_DIR_RAW" >> "$maestro_zshrc"
fi

echo ""
echo "Installation was successful!"
echo "Please open a new terminal OR run the following in the existing one:"
echo ""
echo "    export PATH=\"\$PATH\":\"$MAESTRO_BIN_DIR_RAW\""
echo ""
echo "Then run the following command:"
echo ""
echo "    maestro"
echo ""
echo "Welcome to Maestro!"