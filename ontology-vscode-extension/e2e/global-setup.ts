/**
 * Playwright Global Setup
 *
 * Runs ONCE before all tests. Ensures the `playwright_test` user exists and has
 * at least one project with a sample OWL file for editor tests.
 */

import { readFileSync } from 'fs';
import { resolve } from 'path';
import type { FullConfig } from '@playwright/test';

const BASE = 'http://localhost:3001'; // Vite dev server (proxies /api → localhost:80)
const USER = { username: 'playwright_test', email: 'playwright_test@localhost.test', password: 'PlwTest123!' };
const PROJECT_NAME = 'Playwright Test Project';
const OWL_NAME = 'class-hierarchy-test.owl';
const OWL_CONTENT = readFileSync(
  resolve(__dirname, '../../scripts/load-test/fixtures/class-hierarchy-test.owl'),
  'utf8',
);

async function login(): Promise<string> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER.username, password: USER.password }),
  });
  if (!res.ok) {
    throw new Error(`[setup] Login failed: ${res.status} ${await res.text()}`);
  }
  const data = await res.json();
  const token = data?.jwt ?? data?.token ?? data?.accessToken;
  if (!token) throw new Error('[setup] Login response missing token');
  return token as string;
}

async function ensureUser(): Promise<string> {
  const existing = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER.username, password: USER.password }),
  }).catch(() => null);

  if (existing?.ok) {
    console.log('[setup] playwright_test user already exists ✓');
    return login();
  }

  const signupRes = await fetch(`${BASE}/api/auth/signup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(USER),
  }).catch(() => null);

  if (!signupRes) {
    throw new Error('[setup] Could not reach the dev server. Is the local backend running? (gateway on :80)');
  }

  const body = await signupRes.json().catch(() => ({}));
  if (!signupRes.ok && !JSON.stringify(body).toLowerCase().includes('exist')) {
    throw new Error(`[setup] Signup failed: ${JSON.stringify(body)}`);
  }

  console.log('[setup] playwright_test user created and verified ✓');
  return login();
}

async function authFetch(token: string, path: string, init?: RequestInit) {
  const headers = new Headers(init?.headers);
  headers.set('Authorization', `Bearer ${token}`);
  return fetch(`${BASE}${path}`, { ...init, headers });
}

async function ensureProjectAndFile(token: string) {
  const projRes = await authFetch(token, '/api/projects/my');
  if (!projRes.ok) {
    throw new Error(`[setup] Could not list projects: ${projRes.status}`);
  }
  const projData = await projRes.json();
  let project = (projData.projects ?? []).find((p: { name?: string }) => p.name === PROJECT_NAME)
    ?? (projData.projects ?? [])[0];

  if (!project) {
    const wsRes = await authFetch(token, '/api/workspaces');
    if (!wsRes.ok) throw new Error(`[setup] Could not list workspaces: ${wsRes.status}`);
    const wsData = await wsRes.json();
    let workspace = (wsData.workspaces ?? [])[0];

    if (!workspace?.workspaceId) {
      const createWsRes = await authFetch(token, '/api/workspaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Playwright Workspace',
          description: 'Seeded by Playwright global setup',
        }),
      });
      if (!createWsRes.ok) {
        throw new Error(`[setup] Could not create workspace: ${createWsRes.status} ${await createWsRes.text()}`);
      }
      const createdWs = await createWsRes.json();
      workspace = createdWs.workspace ?? createdWs;
      console.log('[setup] Created test workspace ✓');
    }

    const createRes = await authFetch(token, '/api/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: PROJECT_NAME,
        description: 'Seeded by Playwright global setup',
        workspaceId: workspace.workspaceId,
        shareWith: 'none',
      }),
    });
    if (!createRes.ok) {
      throw new Error(`[setup] Could not create project: ${createRes.status} ${await createRes.text()}`);
    }
    const created = await createRes.json();
    project = created.project ?? created;
    console.log('[setup] Created test project ✓');
  }

  const projectId = project.projectId ?? project.id;
  const filesRes = await authFetch(token, `/api/projects/${projectId}/files`);
  if (!filesRes.ok) {
    throw new Error(`[setup] Could not list project files: ${filesRes.status}`);
  }
  const filesData = await filesRes.json();
  const files = filesData.files ?? [];
  const hasFixture = files.some((f: { name?: string; fileName?: string }) =>
    (f.name ?? f.fileName) === OWL_NAME,
  );
  if (hasFixture) {
    console.log(`[setup] Test ontology ${OWL_NAME} already present ✓`);
    return;
  }

  const form = new FormData();
  form.append('fileName', OWL_NAME);
  form.append('file', new Blob([OWL_CONTENT], { type: 'application/rdf+xml' }), OWL_NAME);

  const uploadRes = await authFetch(token, `/api/projects/${projectId}/files`, {
    method: 'POST',
    body: form,
  });
  if (!uploadRes.ok) {
    throw new Error(`[setup] Could not upload test OWL: ${uploadRes.status} ${await uploadRes.text()}`);
  }
  console.log(`[setup] Uploaded ${OWL_NAME} ✓`);
}

async function globalSetup(_config: FullConfig) {
  const token = await ensureUser();
  await ensureProjectAndFile(token);
}

export default globalSetup;
