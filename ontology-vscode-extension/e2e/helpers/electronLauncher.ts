import fs from 'fs';
import path from 'path';
import type { ElectronApplication, Page } from '@playwright/test';
import { _electron as electron } from '@playwright/test';

const REPO_ROOT = path.resolve(__dirname, '../../..');
export const ELECTRON_DIR = path.join(REPO_ROOT, 'electron-app');
const DEFAULT_EXE = path.join(ELECTRON_DIR, 'dist-electron', 'win-unpacked', 'OntoCode.exe');

export function resolveElectronExecutable(): string {
  if (process.env.DESKTOP_EXE && fs.existsSync(process.env.DESKTOP_EXE)) {
    return process.env.DESKTOP_EXE;
  }
  if (fs.existsSync(DEFAULT_EXE)) {
    return DEFAULT_EXE;
  }
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require(path.join(ELECTRON_DIR, 'node_modules', 'electron')) as string;
}

export function bundledDesktopJarExists(): boolean {
  return fs.existsSync(path.join(ELECTRON_DIR, 'resources', 'backend', 'jars', 'desktop.jar'));
}

export async function launchOntocodeElectron(): Promise<ElectronApplication> {
  const startupTimeout = Number(process.env.ELECTRON_STARTUP_TIMEOUT_MS || 300_000);
  const exe = resolveElectronExecutable();
  const isPackagedExe = exe.toLowerCase().endsWith('.exe') || exe.endsWith('OntoCode');

  if (isPackagedExe) {
    return electron.launch({
      executablePath: exe,
      timeout: startupTimeout,
    });
  }

  const mainJs = path.join(ELECTRON_DIR, 'main.js');
  if (!fs.existsSync(mainJs)) {
    throw new Error(`Electron main.js not found: ${mainJs}`);
  }

  const useDevShell =
    process.env.ELECTRON_E2E_DEV === '1' ||
    (!bundledDesktopJarExists() && process.env.ELECTRON_E2E_DEV !== '0');

  if (useDevShell && process.env.ELECTRON_E2E_DEV !== '1') {
    throw new Error(
      'desktop.jar is missing. Either:\n' +
        '  • Build the desktop app (build-desktop.bat win pack), or\n' +
        '  • Set DESKTOP_EXE to your installed OntoCode.exe, or\n' +
        '  • Set ELECTRON_E2E_DEV=1 and start Vite on :3002 (webview-src npm run dev:desktop-e2e)\n' +
        'Close any running OntoCode window before tests (single-instance).',
    );
  }

  const env: NodeJS.ProcessEnv = { ...process.env };
  if (useDevShell) {
    env.ELECTRON_IS_DEV = '1';
    env.ELECTRON_DEV_API_URL = (process.env.DESKTOP_API_BASE || 'http://127.0.0.1:18085').replace(/\/$/, '');
    env.ELECTRON_VITE_URL = (process.env.ELECTRON_VITE_URL || 'http://localhost:3002').replace(/\/$/, '');
  } else {
    env.ELECTRON_IS_DEV = '0';
    env.ONTOCODE_E2E = '1';
  }

  return electron.launch({
    executablePath: exe,
    args: isPackagedExe ? [] : [mainJs],
    cwd: isPackagedExe ? path.dirname(exe) : ELECTRON_DIR,
    env,
    timeout: startupTimeout,
  });
}

export async function findElectronMainPage(app: ElectronApplication): Promise<Page> {
  const deadline = Date.now() + Number(process.env.ELECTRON_WINDOW_TIMEOUT_MS || 240_000);
  while (Date.now() < deadline) {
    for (const win of app.windows()) {
      const hasApi = await win.evaluate(() => !!(window as unknown as { electronAPI?: unknown }).electronAPI).catch(() => false);
      if (hasApi) {
        return win;
      }
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
  return app.firstWindow();
}

export async function readDesktopApiBase(page: Page): Promise<string> {
  const apiBase = await page.evaluate(() => {
    const w = window as unknown as { __DESKTOP_API_URL__?: string };
    return w.__DESKTOP_API_URL__ || 'http://127.0.0.1:18085';
  });
  return apiBase.replace(/\/$/, '');
}

export async function waitForBackendHealth(apiBase: string, timeoutMs = 300_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastErr = 'unknown';
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${apiBase}/actuator/health`, { signal: AbortSignal.timeout(5_000) });
      if (res.ok) {
        return;
      }
      lastErr = `http ${res.status}`;
    } catch (e) {
      lastErr = e instanceof Error ? e.message : String(e);
    }
    await new Promise((r) => setTimeout(r, 3_000));
  }
  throw new Error(`Desktop backend not healthy at ${apiBase} (${lastErr})`);
}

export async function waitForDesktopShell(page: Page): Promise<void> {
  await Promise.race([
    page.getByText(/My projects|Projects/i).first().waitFor({ state: 'visible', timeout: 240_000 }),
    page.getByText(/Classes|Entities/i).first().waitFor({ state: 'visible', timeout: 240_000 }),
    page.locator('[data-class-id]').first().waitFor({ state: 'visible', timeout: 240_000 }),
  ]).catch(() => {
    /* home or editor — either is fine after splash */
  });
}
