

import fs from 'fs';
import path from 'path';
import type { FullConfig } from '@playwright/test';

const API_BASE = (process.env.DESKTOP_API_BASE || 'http://127.0.0.1:18085').replace(/\/$/, '');
const DESKTOP_USER = process.env.DESKTOP_E2E_USER || 'admin@ontocode.local';
const DESKTOP_PASS = process.env.DESKTOP_E2E_PASSWORD || 'ontocode-desktop';

const STATE_PATH = path.join(__dirname, '.desktop-auth.json');

async function waitForHealth(deadlineMs: number): Promise<void> {
  const deadline = Date.now() + deadlineMs;
  let lastErr = 'unknown';

  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${API_BASE}/actuator/health`, {
        signal: AbortSignal.timeout(5_000),
      });
      if (res.ok) {
        const body = await res.json().catch(() => ({}));
        const status = (body as { status?: string }).status;
        if (!status || status === 'UP') {
          console.log(`[desktop-setup] Backend healthy at ${API_BASE}`);
          return;
        }
        lastErr = `health status=${status}`;
      } else {
        lastErr = `health http ${res.status}`;
      }
    } catch (e) {
      lastErr = e instanceof Error ? e.message : String(e);
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }

  throw new Error(
    `[desktop-setup] Desktop backend not reachable at ${API_BASE} (${lastErr}).\n` +
      'Start the OntoCode Desktop app first (it bundles Mongo + Fuseki + Java on :18085). No Docker needed.',
  );
}

async function login(): Promise<string> {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ username: DESKTOP_USER, password: DESKTOP_PASS }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(
      `[desktop-setup] Login failed for ${DESKTOP_USER}: ${JSON.stringify(body).slice(0, 300)}`,
    );
  }
  const token =
    (body as { token?: string }).token ||
    (body as { data?: { token?: string } }).data?.token;
  if (!token) {
    throw new Error('[desktop-setup] Login response missing token');
  }
  console.log('[desktop-setup] Logged in as desktop admin ✓');
  return token;
}

export default async function globalSetup(_config: FullConfig) {
  await waitForHealth(60_000);
  const token = await login();

  fs.writeFileSync(
    STATE_PATH,
    JSON.stringify({
      apiBase: API_BASE,
      token,
      user: DESKTOP_USER,
      fixturePath: path.resolve(__dirname, '../../test-data/consistent-ontology.owl'),
    }),
  );
}

export { STATE_PATH };
