/**
 * Electron main process – OntoCode Desktop
 *
 * Responsibilities:
 *  - Creates the BrowserWindow and loads the built React app.
 *  - Handles IPC messages from the preload bridge:
 *      auth:get        → read token from electron-store
 *      auth:save       → write token to electron-store
 *      auth:clear      → delete token from electron-store
 *      file:open       → native open-file dialog → returns { fileName, fileContent, fileSize }
 *      notification    → native OS notification
 */

const { app, BrowserWindow, ipcMain, dialog, Notification, shell } = require('electron');
const path = require('path');
const Store = require('electron-store');

// Secure storage for auth token (encrypted at rest by electron-store)
const store = new Store({ encryptionKey: 'ontocode-desktop-secret' });

let mainWindow = null;

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 900,
        minHeight: 600,
        title: 'OntoCode',
        webPreferences: {
            // Preload exposes the safe window.vscode bridge
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            // Allow loading local files from the dist folder
            webSecurity: false,
        },
    });

    // Load the production build of the React app
    const indexPath = path.join(
        __dirname,
        '../ontology-vscode-extension/webview-src/dist/index.html'
    );
    mainWindow.loadFile(indexPath);

    // Open external links in the default browser
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: 'deny' };
    });

    mainWindow.on('closed', () => { mainWindow = null; });
}

// ── IPC handlers ────────────────────────────────────────────────────────────

/** Return stored auth token (or null) */
ipcMain.handle('auth:get', () => {
    return store.get('authToken', null);
});

/** Persist auth token */
ipcMain.handle('auth:save', (_event, token) => {
    store.set('authToken', token);
});

/** Remove auth token (logout) */
ipcMain.handle('auth:clear', () => {
    store.delete('authToken');
});

/**
 * Open a native file-open dialog and return the selected ontology file's
 * name, text content, and size (in bytes).
 * Returns null if the user cancels.
 */
ipcMain.handle('file:open', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: 'Open Ontology File',
        filters: [
            { name: 'Ontology Files', extensions: ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld'] },
            { name: 'All Files', extensions: ['*'] },
        ],
        properties: ['openFile'],
    });

    if (result.canceled || result.filePaths.length === 0) return null;

    const fs = require('fs');
    const filePath = result.filePaths[0];
    const fileName = path.basename(filePath);
    const fileContent = fs.readFileSync(filePath, 'utf8');
    const fileSize = Buffer.byteLength(fileContent, 'utf8');

    return { fileName, fileContent, fileSize };
});

/** Show a native OS notification */
ipcMain.handle('notification:show', (_event, { title, message, type }) => {
    if (Notification.isSupported()) {
        new Notification({ title, body: message }).show();
    } else {
        console.log(`[Notification][${type}] ${title}: ${message}`);
    }
});

// ── App lifecycle ────────────────────────────────────────────────────────────

app.whenReady().then(() => {
    createWindow();
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
