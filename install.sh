#!/bin/bash
# Linux Installer for Android Mic Bridge (miccpy)

set -e

# Configuration
INSTALL_DIR="$HOME/.local/share/miccpy"
BIN_DIR="$HOME/.local/bin"
REPO_URL="https://raw.githubusercontent.com/typedbywill/amb/main"
RELEASES_PAGE="https://github.com/typedbywill/amb/releases"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=========================================${NC}"
echo -e "${CYAN}   Installing Android Mic Bridge (miccpy)  ${NC}"
echo -e "${CYAN}=========================================${NC}"

# 1. Check Python 3
echo -e "${YELLOW}[1/6] Checking for Python 3...${NC}"
if ! command -v python3 &> /dev/null; then
    echo -e "${RED}Error: Python 3 is not installed.${NC}"
    echo -e "Please install it using your package manager (e.g., 'sudo apt install python3' or 'sudo pacman -S python')."
    exit 1
fi
python3_version=$(python3 --version)
echo -e "${GREEN}Found $python3_version${NC}"

# 2. Check Pip
echo -e "${YELLOW}[2/6] Checking for pip...${NC}"
if ! python3 -m pip --version &> /dev/null; then
    echo -e "${RED}Error: pip for Python 3 is not installed.${NC}"
    echo -e "Please install it using your package manager (e.g., 'sudo apt install python3-pip' or 'sudo dnf install python3-pip')."
    exit 1
fi
echo -e "${GREEN}Found pip${NC}"

# 3. Create installation directories
echo -e "${YELLOW}[3/6] Creating installation directory at $INSTALL_DIR...${NC}"
mkdir -p "$INSTALL_DIR/desktop"
mkdir -p "$BIN_DIR"

# 4. Download Client Script
echo -e "${YELLOW}[4/6] Downloading miccpy client scripts...${NC}"
if command -v curl &> /dev/null; then
    curl -sSL "$REPO_URL/desktop/miccpy.py" -o "$INSTALL_DIR/desktop/miccpy.py"
else
    wget -qO "$INSTALL_DIR/desktop/miccpy.py" "$REPO_URL/desktop/miccpy.py"
fi
echo -e "${GREEN}Downloaded client scripts.${NC}"

# 5. Install Python dependencies
echo -e "${YELLOW}[5/6] Installing Python dependencies (sounddevice, pyogg, numpy)...${NC}"
if ! python3 -m pip install --user sounddevice pyogg numpy; then
    echo -e "${YELLOW}Standard pip install failed (possibly due to PEP 668 externally-managed environment).${NC}"
    echo -e "${YELLOW}Retrying pip install with --break-system-packages...${NC}"
    if ! python3 -m pip install --user --break-system-packages sounddevice pyogg numpy; then
        echo -e "${RED}Error: Failed to install Python dependencies. Please run: pip install sounddevice pyogg numpy${NC}"
    fi
fi

# Try to download the APK from the latest releases
echo -e "${YELLOW}Checking for Android APK release...${NC}"
apk_url="https://github.com/typedbywill/amb/releases/latest/download/app-debug.apk"
apk_dest="$INSTALL_DIR/miccpy.apk"
download_success=false

if command -v curl &> /dev/null; then
    if curl -sSL -o "$apk_dest" -f "$apk_url"; then
        download_success=true
    fi
else
    if wget -qO "$apk_dest" "$apk_url"; then
        download_success=true
    fi
fi

if [ "$download_success" = true ]; then
    echo -e "${GREEN}Android APK downloaded to: $apk_dest${NC}"
    echo -e "${GREEN}Install it using: adb install $apk_dest${NC}"
else
    echo -e "${YELLOW}Could not automatically download the APK (no release published yet or network error).${NC}"
    echo -e "${CYAN}To install the Android app, please build it via './gradlew installDebug' in the android/ folder, or check: $RELEASES_PAGE${NC}"
fi

# Check for ADB
if ! command -v adb &> /dev/null; then
    echo -e "${YELLOW}Warning: 'adb' command not found in your PATH.${NC}"
    echo -e "You will need ADB to connect over USB. Please install it:"
    echo -e "  - Debian/Ubuntu: sudo apt install android-tools-adb"
    echo -e "  - Fedora: sudo dnf install android-tools"
    echo -e "  - Arch Linux: sudo pacman -S android-tools"
fi

# 6. Create binary wrapper script
echo -e "${YELLOW}[6/6] Generating binary wrapper at $BIN_DIR/miccpy...${NC}"
cat << EOF > "$BIN_DIR/miccpy"
#!/bin/bash
python3 "$INSTALL_DIR/desktop/miccpy.py" "\$@"
EOF
chmod +x "$BIN_DIR/miccpy"
echo -e "${GREEN}Wrapper script generated and made executable.${NC}"

# Check PATH configuration
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo -e "${YELLOW}Warning: $BIN_DIR is not in your PATH.${NC}"
    echo -e "To run 'miccpy' from anywhere, please add it to your PATH by adding the following line to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
    echo -e "${CYAN}  export PATH=\"\$HOME/.local/bin:\$PATH\"${NC}"
    echo -e "Then run: source ~/.bashrc (or reopen your terminal)"
fi

echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}  Android Mic Bridge installed successfully!${NC}"
echo -e "${GREEN}  Run: miccpy${NC}"
echo -e "${GREEN}=========================================${NC}"
