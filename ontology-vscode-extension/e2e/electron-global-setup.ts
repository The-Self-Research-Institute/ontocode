

import fs from 'fs';
import path from 'path';
import type { FullConfig } from '@playwright/test';
import { bundledDesktopJarExists, ELECTRON_DIR, resolveElectronExecutable } from './helpers/electronLauncher';

export const STATE_PATH = path.join(__dirname, '.desktop-auth.json');

export default async function globalSetup(_config: FullConfig) {
  const mainJs = path.join(ELECTRON_DIR, 'main.js');
  if (!fs.existsSync(mainJs)) {
    throw new Error(`electron-app not found at ${ELECTRON_DIR}`);
  }

  const exe = resolveElectronExecutable();
  const isPackaged = exe.toLowerCase().endsWith('.exe');
  if (!isPackaged && !fs.existsSync(path.join(ELECTRON_DIR, 'node_modules', 'electron'))) {
    throw new Error(
      'Electron is not installed. Run:\n  cd electron-app && npm install',
    );
  }

  const fixturePath = path.resolve(__dirname, '../../test-data/consistent-ontology.owl');
  if (!fs.existsSync(fixturePath)) {
    throw new Error(`Missing test fixture: ${fixturePath}`);
  }

  if (process.env.ELECTRON_E2E_DEV === '1') {
    console.log('[electron-setup] Dev shell mode — Electron UI + Vite, backend must be reachable');
  } else if (isPackaged) {
    console.log(`[electron-setup] Using packaged app: ${exe}`);
  } else if (bundledDesktopJarExists()) {
    console.log('[electron-setup] Will launch Electron with bundled Mongo/Fuseki/JVM');
  } else {
    throw new Error(
      'No desktop.jar and no DESKTOP_EXE. Build desktop or set DESKTOP_EXE.\n' +
        'Close any running OntoCode window before tests (only one instance allowed).',
    );
  }

  console.log('[electron-setup] Pre-flight OK — Playwright will open the Electron window');
}
