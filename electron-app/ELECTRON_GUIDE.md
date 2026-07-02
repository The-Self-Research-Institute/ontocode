# OntoCode Electron — Developer Guide

Covers three topics:
1. [Dev hot-reload workflow](#1-dev-hot-reload-workflow) — iterate fast without rebuilding JARs
2. [Converting the web app to Electron](#2-converting-the-web-app-to-electron) — what changed and why
3. [Decentralized sync and share](#3-decentralized-sync-and-share) — P2P sync via Syncthing

---

## 1. Dev Hot-Reload Workflow

### How it works

In dev mode (`ELECTRON_IS_DEV=1`), Electron skips the bundled JARs / MongoDB / Fuseki startup and instead:
- Loads the React UI from the **Vite dev server** at `http://localhost:5173` (HMR enabled)
- Points the API at your **Docker backend** at `http://localhost:8083`

This means every React change is visible in Electron within ~100 ms, the same as browser dev.

### One-command startup (Windows)

```bat
electron-app\scripts\start-electron-dev.bat
```

This single script:
1. Runs `docker compose -f docker-compose.dev.yml up -d` (starts backend)
2. Opens a new terminal running `npm run dev` in `webview-src/` (Vite HMR)
3. Launches Electron with `ELECTRON_IS_DEV=1` (no JAR startup, DevTools open)

### Manual startup (if you prefer)

Open three terminals:

**Terminal 1 — Docker backend**
```bat
docker compose -f docker-compose.dev.yml up -d
```

**Terminal 2 — Vite dev server**
```bat
cd ontology-vscode-extension\webview-src
npm run dev
```

**Terminal 3 — Electron**
```bat
cd electron-app
set ELECTRON_IS_DEV=1
npx electron .
```

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `ELECTRON_IS_DEV` | `0` | `1` = dev mode (skip JARs, use Docker) |
| `ELECTRON_DEV_API_URL` | `http://localhost:8083` | Override backend URL |
| `ELECTRON_VITE_URL` | `http://localhost:5173` | Override Vite URL |

### What you see in dev mode

- DevTools open automatically
- `window.__IS_DEV__ = true` is injected
- Splash screen shows "Dev mode — connecting to Docker" instead of the service startup sequence
- Hot Module Replacement works exactly as in the browser

### Changing Java / Spring Boot code

Java changes require a rebuild and Docker restart. Fastest path:

```bat
REM Rebuild only the editor service
docker compose -f docker-compose.dev.yml up -d --build ontology-editor
```

This takes ~30–60 s. Electron stays open; refresh the window with `Ctrl+R` when the service is up.

---

## 2. Converting the Web App to Electron

### Architecture overview

```
┌─────────────────────────────────────────────────────┐
│  Electron process (main.js)                         │
│                                                     │
│  ┌─────────────────┐   IPC    ┌──────────────────┐  │
│  │ BrowserWindow   │ ◄──────► │ ipcMain handlers │  │
│  │ (Chromium)      │          │  file:open       │  │
│  │                 │          │  sync:*          │  │
│  │  React UI       │          │  config:get      │  │
│  │  (webview-src/) │          │  auth:*          │  │
│  └─────────────────┘          └──────────────────┘  │
│                                       │             │
│                              ServiceManager         │
│                              ┌────────────────┐     │
│                              │ MongoDB        │     │
│                              │ Fuseki         │     │
│                              │ Spring Boot    │     │
│                              │ Syncthing      │     │
│                              └────────────────┘     │
└─────────────────────────────────────────────────────┘
```

### Key changes made to the web app

#### 1. API URL injection (`main.js`)

The React app needs to know where the backend is. In the web app, this is handled by the gateway. In Electron, `main.js` injects it on page load:

```js
window.__DESKTOP_API_URL__ = 'http://127.0.0.1:18083';
window.__DESKTOP_MODE__    = true;
```

The `apiClient.ts` in the React app checks `window.__DESKTOP_API_URL__` and uses it when present.

#### 2. File system access via IPC

The web app can't open local files. Electron bridges this via `preload.js`:

```js
// preload.js exposes:
window.electronAPI = {
  openFile:    ()      => ipcRenderer.invoke('file:open'),
  saveAsFile:  (data)  => ipcRenderer.invoke('file:saveAs', data),
  syncStatus:  ()      => ipcRenderer.invoke('sync:status'),
  shareFolder: (opts)  => ipcRenderer.invoke('sync:shareWorkspace', opts),
  // ...
};
```

The React UI calls `window.electronAPI?.openFile()` and falls back to the `<input type="file">` picker when not in Electron.

#### 3. Auth token storage

In the web app, auth tokens are in `localStorage`. In Electron they are stored in the OS keychain via `electron-store` (encrypted). The `auth:get` / `auth:save` / `auth:clear` IPC handlers are used by the React auth context when `window.__DESKTOP_MODE__` is true.

#### 4. ServiceManager (`services/ServiceManager.js`)

The three backend services that run as Docker containers in the web app run as child processes in the desktop app:

| Web (Docker) | Desktop (child process) |
|---|---|
| `ontology-editor` container (port 8083) | `java -jar owl-editor.jar` (port 18083) |
| GraphDB / Fuseki container (port 7200/3030) | `java -jar fuseki-server.jar` (port 13030) |
| MongoDB container (port 27017) | `mongod` process (port 27117) |

Ports are offset from the defaults so a running Docker stack doesn't conflict with the desktop app.

#### 5. Bundled resources layout

```
electron-app/
  resources/
    backend/
      jars/
        owl-editor.jar       ← Spring Boot fat JAR (built by Maven)
        fuseki-server.jar    ← Apache Jena Fuseki
      mongodb/
        win32/mongod.exe     ← MongoDB Community binary
        darwin/mongod
        linux/mongod
      syncthing/
        win32/syncthing.exe  ← Syncthing binary
        darwin/syncthing
        linux/syncthing
      jre/                   ← Optional bundled JRE 17
```

Run `npm run prepare-resources` to copy these into place from your local installs.

### Build for production (fully standalone — no Docker, no system Java)

The desktop app bundles its own JRE so end-users never need to install Java.

**Step 1 — build the Spring Boot JAR**
```bat
mvn -pl ontology-editor package -DskipTests
```

**Step 2 — prepare all resources (JARs + JRE + MongoDB)**
```bat
cd electron-app
node scripts/prepare-resources.js
```

This script:
1. Copies `owl-editor.jar` from `ontology-editor/target/`
2. Copies `fuseki-server.jar` from `fuseki-docker/`
3. Copies `mongod` binaries from `data/mongodb/bin/<platform>/`
4. **Bundles a JRE** using `jlink` if a JDK is on your build machine (creates a ~65 MB minimal runtime), or downloads Temurin 17 JRE automatically as fallback

The resulting `resources/backend/jre/bin/java` is what `ServiceManager.js` uses — it is checked before `JAVA_HOME` or system `java`.

**Step 3 — package the installer**
```bat
npm run dist:win    # → dist-electron/*.exe  (NSIS installer)
npm run dist:mac    # → dist-electron/*.dmg
npm run dist:linux  # → dist-electron/*.AppImage + *.deb
```

The packaged app contains:
```
resources/
  backend/
    jre/bin/java.exe    ← bundled JRE — end user needs nothing installed
    jars/owl-editor.jar
    jars/fuseki-server.jar
    mongodb/win32/mongod.exe
    syncthing/win32/syncthing.exe
```

**On startup**, the app launches `jre/bin/java -jar jars/owl-editor.jar` and everything runs locally — no internet, no Docker, no account.

### CI / CD pipeline sketch

```yaml
# GitHub Actions example
jobs:
  build-electron:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build Spring Boot JAR
        run: mvn -pl ontology-editor package -DskipTests
      - name: Copy JAR to resources
        run: copy ontology-editor\target\*.jar electron-app\resources\backend\jars\owl-editor.jar
      - name: Install electron deps
        run: npm install
        working-directory: electron-app
      - name: Build and package
        run: npm run dist:win
        working-directory: electron-app
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 3. Decentralized Sync and Share

### Overview

OntoCode Desktop uses [Syncthing](https://syncthing.net/) for peer-to-peer sync. Syncthing:
- Is open source (MPLv2), mature, and battle-tested
- Syncs directly device-to-device (or via community relay servers as fallback)
- Requires no central server or OntoCode account
- Handles conflicts, versioning, and deletions automatically

Syncthing is bundled as a binary inside the app (`resources/backend/syncthing/`). It starts automatically alongside the other backend services.

### How sync works

```
Device A (Alice)                    Device B (Bob)
┌─────────────────────┐             ┌─────────────────────┐
│ Workspace folder    │             │ Workspace folder    │
│  ontology.owl       │ ◄─ sync ──► │  ontology.owl       │
│  queries/           │   (direct   │  queries/           │
│  rules/             │  or relay)  │  rules/             │
│  history/           │             │  history/           │
└─────────────────────┘             └─────────────────────┘
       ▲                                     ▲
       │ manages via REST API                │ manages via REST API
       │ (port 18384)                        │ (port 18384)
  SyncManager.js                        SyncManager.js
       ▲                                     ▲
       │ IPC                                 │ IPC
  React UI                             React UI
```

### Pairing two devices

**On Device A (sharing):**

1. Open the Sync panel in OntoCode
2. Click "Share Workspace"
3. Select the workspace folder
4. Copy the generated share link (looks like `ontocode-sync://eyJkZXZpY2VJZCI6Ii...`)
5. Send the link to Device B (email, WhatsApp, anything)

**On Device B (receiving):**

1. Open the Sync panel
2. Click "Import Share Link"
3. Paste the link
4. OntoCode adds Device A as a peer and requests the shared folder
5. Device A must accept the incoming folder request (one-time)

After pairing, sync is automatic whenever both devices are online. If both are on the same LAN they sync directly; otherwise they use Syncthing's public relay servers.

### Share link format

```
ontocode-sync://<base64url(JSON)>

JSON payload:
{
  "v": 1,
  "deviceId": "ABCDE12-FGHIJ34-...",   // Syncthing device ID of sender
  "folderIds": ["ontocode-a1b2c3d4"]   // Syncthing folder IDs to share
}
```

The link is purely informational. The receiving device uses it to know:
- Which device to add as a peer
- Which folders to request

No credentials or tokens are embedded. Security is handled by Syncthing's TLS device certificate model — each device has a unique certificate, and you explicitly accept each peer.

### IPC API (from React → Electron)

```ts
// Check if Syncthing is running
const status = await window.electronAPI.ipc('sync:status');
// → { running: true }

// Get this device's shareable ID
const deviceId = await window.electronAPI.ipc('sync:deviceId');
// → "ABCDE12-FGHIJ34-..."

// List synced folders
const folders = await window.electronAPI.ipc('sync:folders');
// → [{ id, label, path, paused }]

// Share a workspace (opens folder picker, returns link)
const result = await window.electronAPI.ipc('sync:shareWorkspace', { label: 'MyOntology' });
// → { folderPath, folderId, link: "ontocode-sync://..." }

// Import a share link (add peer + request folder)
const parsed = await window.electronAPI.ipc('sync:parseLink', { link });
await window.electronAPI.ipc('sync:addPeer', {
  deviceId: parsed.deviceId,
  folderId: parsed.folderIds[0],
  peerName: 'Alice',
});

// Check sync progress (0-100)
const pct = await window.electronAPI.ipc('sync:completion', {
  folderId: 'ontocode-a1b2c3d4',
  deviceId: 'ABCDE12-...',
});
```

### Bundling Syncthing

Download the correct Syncthing binary for each platform from https://syncthing.net/downloads/ and place it at:

```
electron-app/resources/backend/syncthing/
  win32/syncthing.exe
  darwin/syncthing
  linux/syncthing
```

Then run `chmod +x` on the Linux/macOS binaries.

The `electron-builder.yml` `extraResources` block already includes `resources/backend/**` so these will be bundled automatically.

### Conflict handling

When two devices edit the same `.owl` file simultaneously while offline, Syncthing will:
1. Keep the most recently modified version as the live file
2. Rename the other to `ontology.sync-conflict-<date>-<devid>.owl`

OntoCode detects `.sync-conflict-*` files in workspace folders and shows a yellow banner: "Sync conflict detected — click to resolve". The conflict resolver opens both files side-by-side and lets the user pick axioms from each.

This is a future UI feature; the backend detection happens automatically via the file watcher.

### Why Syncthing vs alternatives

| Option | Pros | Cons |
|---|---|---|
| **Syncthing** (chosen) | No server, P2P, mature, handles conflicts | Binary adds ~15 MB; needs firewall permission |
| OntoCode Cloud sync | Works without pairing; supports teams | Requires account; infrastructure cost |
| Git | Standard for code; branching and history | Complex for non-developers; no real-time |
| Dropbox / iCloud | Zero setup for users who already have it | Not bundled; vendor lock-in |

---

## Quick Reference

| Task | Command |
|---|---|
| **Start dev mode (Windows)** | `electron-app\scripts\start-electron-dev.bat` |
| Start Electron only (dev) | `cd electron-app && set ELECTRON_IS_DEV=1 && npx electron .` |
| Build React SPA | `cd ontology-vscode-extension\webview-src && npm run build` |
| Build Spring Boot JAR | `mvn -pl ontology-editor package -DskipTests` |
| **Bundle JRE + resources** | `cd electron-app && node scripts/prepare-resources.js` |
| **Package Windows installer** | `cd electron-app && npm run dist:win` |
| Package all platforms | `cd electron-app && npm run dist` |
| Rebuild Docker on Java change | `docker compose -f docker-compose.dev.yml up -d --build ontology-editor` |

### JRE size after jlink

| JRE type | Approximate size |
|---|---|
| Full Temurin 17 JRE | ~200 MB |
| `jlink` minimal (modules above) | ~65 MB |
| After electron-builder compression | ~35 MB in installer |
