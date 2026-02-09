#!/bin/bash
# ========================================
# Build OntoCode Launcher for Linux/Mac
# ========================================

echo "========================================"
echo "  OntoCode Launcher Setup"
echo "========================================"
echo ""

LAUNCHER_SCRIPT="OntoCodeLauncher.sh"

if [ ! -f "$LAUNCHER_SCRIPT" ]; then
    echo "[ERROR] ${LAUNCHER_SCRIPT} not found!"
    echo "Please make sure ${LAUNCHER_SCRIPT} is in the current directory."
    exit 1
fi

# Make the script executable
chmod +x "$LAUNCHER_SCRIPT"

echo "[OK] Launcher script is now executable"
echo ""
echo "========================================"
echo "  Setup Complete!"
echo "========================================"
echo ""
echo "You can now:"
echo "  1. Run: ./${LAUNCHER_SCRIPT}"
echo "  2. It will create a desktop shortcut automatically"
echo "  3. Share ${LAUNCHER_SCRIPT} with others"
echo ""
echo "The script will automatically:"
echo "  - Check Docker status"
echo "  - Pull/start services"
echo "  - Create desktop shortcut"
echo "  - Open http://localhost:3000"
echo ""

# Detect OS and provide specific instructions
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "macOS detected:"
    echo "  Desktop shortcut will be: OntoCode.command"
    echo "  Double-click to run"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "Linux detected:"
    echo "  Desktop shortcut will be: OntoCode.desktop"
    echo "  You may need to mark it as trusted in file manager"
fi

echo ""
read -p "Press Enter to run the launcher now, or Ctrl+C to exit..."

# Run the launcher
./"$LAUNCHER_SCRIPT"
