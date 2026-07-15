import fs from 'fs';
import path from 'path';
import { test as base, expect } from '@playwright/test';
import type { ElectronApplication, Page } from '@playwright/test';
import {
  findElectronMainPage,
  launchOntocodeElectron,
  readDesktopApiBase,
  waitForBackendHealth,
  waitForDesktopShell,
} from '../helpers/electronLauncher';
import { STATE_PATH } from '../electron-global-setup';

const DESKTOP_USER = process.env.DESKTOP_E2E_USER || 'admin@ontocode.local';
const DESKTOP_PASS = process.env.DESKTOP_E2E_PASSWORD || 'ontocode-desktop';

/** Login and return JWT for editor API calls (desktop seeds admin@ontocode.local). */
async function loginDesktopApi(apiBase: string): Promise<string> {
  const res = await fetch(`${apiBase}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ username: DESKTOP_USER, password: DESKTOP_PASS }),
  });
  const body = await res.json().catch(() => ({}));
  if (res.ok) {
    const token =
      (body as { jwt?: string }).jwt ||
      (body as { token?: string }).token ||
      (body as { data?: { jwt?: string; token?: string } }).data?.jwt ||
      (body as { data?: { token?: string } }).data?.token;
    if (token) {
      return token;
    }
  }
  // Desktop permit-all: omit Bearer on API calls (matches Electron shell).
  return '';
}

function writeAuthState(apiBase: string, token: string) {
  const fixturePath = path.resolve(__dirname, '../../../test-data/consistent-ontology.owl');
  fs.writeFileSync(
    STATE_PATH,
    JSON.stringify({
      apiBase,
      token,
      user: DESKTOP_USER,
      fixturePath,
    }),
  );
}

type ElectronFixtures = {
  electronApp: ElectronApplication;
  apiBase: string;
};

export const test = base.extend<ElectronFixtures>({
  electronApp: [
    async ({}, use) => {
      console.log('[electron] Launching OntoCode Desktop…');
      const app = await launchOntocodeElectron();
      try {
        const apiBaseDefault = (process.env.DESKTOP_API_BASE || 'http://127.0.0.1:18085').replace(/\/$/, '');
        const [page] = await Promise.all([
          findElectronMainPage(app),
          waitForBackendHealth(apiBaseDefault, 480_000),
        ]);
        await page.waitForLoadState('domcontentloaded', { timeout: 120_000 }).catch(() => {});
        const apiBase = await readDesktopApiBase(page).catch(() => apiBaseDefault);
        const token = await loginDesktopApi(apiBase);
        writeAuthState(apiBase, token);
        await waitForDesktopShell(page);
        console.log('[electron] Desktop ready at', apiBase);
        await use(app);
      } finally {
        await app.close().catch(() => {});
      }
    },
    { scope: 'worker', timeout: 600_000 },
  ],

  apiBase: [
    async ({ electronApp }, use) => {
      const page = await findElectronMainPage(electronApp);
      await use(await readDesktopApiBase(page));
    },
    { scope: 'worker' },
  ],

  page: async ({ electronApp }, use) => {
    const page = await findElectronMainPage(electronApp);
    await use(page);
  },
});

export { expect };

export async function assertElectronWindow(page: Page) {
  const hasApi = await page.evaluate(() => !!(window as unknown as { electronAPI?: unknown }).electronAPI);
  expect(hasApi, 'Tests must run inside the Electron window (window.electronAPI)').toBeTruthy();
}
