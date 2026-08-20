# OntoCode Launcher - Cross-Platform

This folder contains launchers for all platforms:

## Windows
- **OntoCodeLauncher.exe** - Windows executable
- Build: `.\build-exe.bat`
- Run directly by double-clicking

## macOS
- **OntoCode.app** - macOS application bundle
- Build: `chmod +x build-macos-app.sh && ./build-macos-app.sh`
- Install to /Applications or run directly
- Distributable as .zip

## Linux
- **OntoCodeLauncher.sh** - Bash script
- Run: `chmod +x OntoCodeLauncher.sh && ./OntoCodeLauncher.sh`

### Setup Instructions:

#### Windows:
1. Run `build-exe.bat`
2. Share `OntoCodeLauncher.exe`
3. Double-click to run

#### macOS:
1. Run on Mac:
   ```bash
   chmod +x build-macos-app.sh
   ./build-macos-app.sh
   ```
2. This creates `OntoCode.app`
3. To share:
   ```bash
   zip -r OntoCode.zip OntoCode.app
   ```
4. Users extract and copy to /Applications

#### Linux:
1. Make executable:
   ```bash
   chmod +x OntoCodeLauncher.sh
   ```
2. Run:
   ```bash
   ./OntoCodeLauncher.sh
   ```
3. Share the .sh file

### Desktop Shortcuts:

**macOS:** 
- `.app` file can be in /Applications or Desktop
- Launch from Spotlight or Launchpad

**Linux:** 
- Creates `OntoCode.desktop` on Desktop

**Windows:** 
- Creates `OntoCode.lnk` on Desktop

## Features (All Platforms):

✓ Checks Docker status
✓ Pulls Docker images (first time only)
✓ Starts all OntoCode services
✓ Creates workspace/shortcuts automatically
✓ Opens http://localhost:3000 in browser
✓ Fast restart on second run
✓ All configuration embedded - no external files needed!

## Distribution:

- **Windows:** Share `OntoCodeLauncher.exe` (single file)
- **macOS:** Share `OntoCode.zip` (contains .app bundle)
- **Linux:** Share `OntoCodeLauncher.sh` (single file)
