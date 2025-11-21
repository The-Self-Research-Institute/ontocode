# How to Restart the Extension and Clear Cache

## Quick Steps:

1. **Close ALL VSCode windows** running the extension

2. **Press F5 in VSCode** (or click "Run and Debug" → "Run Extension")
   - This will start a new Extension Development Host with fresh cache

## If Still Seeing Old UI:

### Option A: Hard Reload the Webview
In the Extension Development Host window:
- Press `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (Mac)
- Type "Developer: Reload Window"
- Press Enter

### Option B: Clear VSCode Cache Completely
1. Close all VSCode windows
2. Delete cache directory:
   - **Windows**: `%APPDATA%\Code\User\workspaceStorage\*`
   - **Mac/Linux**: `~/.config/Code/User/workspaceStorage/*`
3. Restart VSCode

## What Was Fixed:

✅ **React Error #310 (Infinite Re-renders)** - Fixed circular dependencies
✅ **Cache Busting** - Added timestamp parameter to JS/CSS files
✅ **Webview Bundle** - Rebuilt with all latest changes
✅ **Extension Code** - Compiled with cache-busting mechanism

## Files Updated:
- `webview-src/dist/assets/index-ByRl-tjJ.js` (488 KB - fresh build)
- `webview-src/dist/assets/index-7QMjx9U2.css` (1.9 KB - fresh build)
- `out/extension.js` (compiled with cache buster)

## Verify It's Working:

The new UI should NOT show Protégé-style dialogs. Instead you should see:
- OntoCode custom dialogs
- Purple-themed UI
- Entity renaming with F2 and double-click
- Settings gear icon in entity hierarchy
- No infinite re-render errors in console
