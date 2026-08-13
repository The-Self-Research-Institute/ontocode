import fs from 'fs';
import path from 'path';
import { expect } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import { STATE_PATH } from '../electron-global-setup';

export interface DesktopAuthState {
  apiBase: string;
  token: string;
  user: string;
  fixturePath: string;
}

export function loadDesktopAuth(): DesktopAuthState {
  const raw = fs.readFileSync(STATE_PATH, 'utf8');
  return JSON.parse(raw) as DesktopAuthState;
}

export function makeDesktopProjectId(prefix = 'pw-desktop'): string {
  return `${prefix}-${Date.now()}`;
}

function authHeaders(token: string) {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

export async function uploadOntologyFixture(
  request: APIRequestContext,
  projectId: string,
  owlPath: string,
  token: string,
  apiBase: string,
): Promise<{ uploadFileName: string }> {
  const uploadFileName = `desktop-e2e-${Date.now()}.owl`;
  const buffer = fs.readFileSync(owlPath);
  const q = new URLSearchParams({
    action: 'replace',
    importMode: 'FULL',
    partition: 'NONE',
  });
  const url = `${apiBase}/api/ontology/upload/${encodeURIComponent(projectId)}?${q}`;

  const res = await request.post(url, {
    headers: authHeaders(token),
    multipart: {
      file: {
        name: uploadFileName,
        mimeType: 'application/rdf+xml',
        buffer,
      },
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`Upload failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  return { uploadFileName };
}

export async function waitForImportComplete(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  timeoutMs = 180_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  const url = `${apiBase}/api/ontology/status/${encodeURIComponent(projectId)}`;

  while (Date.now() < deadline) {
    const res = await request.get(url, { headers: authHeaders(token) });
    const json = await res.json().catch(() => ({}));
    const data = (json as { data?: Record<string, unknown> }).data || json;
    const status = String((data as { status?: string }).status || (data as { state?: string }).state || '');
    const owlapiReady = Boolean((data as { owlapiReady?: boolean }).owlapiReady);

    if (status === 'COMPLETED' || status === 'UPDATED' || status === 'ALREADY_LOADED') {
      return;
    }

    if (owlapiReady) {
      return;
    }
    if (status === 'ERROR' || status === 'FAILED') {
      throw new Error(`Import failed: ${JSON.stringify(data).slice(0, 300)}`);
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
  throw new Error(`Import not complete after ${timeoutMs}ms for project ${projectId}`);
}

export async function waitForClassesReady(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  timeoutMs = 120_000,
): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  const url = `${apiBase}/api/ontology/metadata/${encodeURIComponent(projectId)}`;

  while (Date.now() < deadline) {
    const res = await request.get(url, { headers: authHeaders(token) });
    const json = await res.json().catch(() => ({}));
    const data = (json as { data?: Record<string, unknown> }).data || json;
    const classCount = Number((data as { classCount?: number }).classCount ?? 0);
    if (classCount > 0) {
      return classCount;
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
  throw new Error(`No classes in metadata after ${timeoutMs}ms`);
}

export async function prepareIsolatedDesktopProject(
  request: APIRequestContext,
  prefix?: string,
): Promise<{ projectId: string; token: string; apiBase: string }> {
  const auth = loadDesktopAuth();
  const projectId = makeDesktopProjectId(prefix);
  await uploadOntologyFixture(request, projectId, auth.fixturePath, auth.token, auth.apiBase);
  await waitForImportComplete(request, projectId, auth.token, auth.apiBase);
  await waitForTopLevelClasses(request, projectId, auth.token, auth.apiBase, 1, 120_000);
  return { projectId, token: auth.token, apiBase: auth.apiBase };
}

export interface WarmResult {
  ready: boolean;
  owlapiReady?: boolean;
  sparqlFallback?: boolean;
  pending?: boolean;
  topLevelClasses?: number;
  elapsedMs?: number;
  message?: string;
}

export interface CacheStatusResult {
  owlapiReady: boolean;
  hierarchyReady?: boolean;
  sparqlFallback?: boolean;
  topLevelClasses?: number;
  hierarchyEngine?: string;
}

export interface TopLevelResult {
  classes: Array<{ iri?: string; label?: string }>;
  topLevelTotal: number;
  hierarchyEngine?: string;
}

export async function warmOntology(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  timeoutMs = 120_000,
): Promise<WarmResult> {
  const url = `${apiBase}/api/ontology/warm/${encodeURIComponent(projectId)}?timeoutMs=${timeoutMs}`;
  const res = await request.post(url, { headers: authHeaders(token) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`warm failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const data = (body as { data?: WarmResult }).data || body;
  return data as WarmResult;
}

export async function getCacheStatus(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
): Promise<CacheStatusResult> {
  const url = `${apiBase}/api/ontology/cache-status/${encodeURIComponent(projectId)}`;
  const res = await request.get(url, { headers: authHeaders(token) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`cache-status failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const data = (body as { data?: CacheStatusResult }).data || body;
  return data as CacheStatusResult;
}

export async function waitForOwlApiReady(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  timeoutMs = 120_000,
): Promise<CacheStatusResult> {
  const deadline = Date.now() + timeoutMs;
  let last: CacheStatusResult | undefined;

  while (Date.now() < deadline) {
    last = await getCacheStatus(request, projectId, token, apiBase);
    if (last.owlapiReady) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 1_500));
  }
  throw new Error(
    `OWLAPI not ready after ${timeoutMs}ms for ${projectId}: ${JSON.stringify(last).slice(0, 300)}`,
  );
}

export async function getTopLevelClasses(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
): Promise<TopLevelResult> {
  const url = `${apiBase}/api/ontology/classes/top-level/${encodeURIComponent(projectId)}?limit=500`;
  const res = await request.get(url, { headers: authHeaders(token) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`top-level failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const data = (body as { data?: TopLevelResult }).data || body;
  const classes = (data as TopLevelResult).classes || [];
  const topLevelTotal = Number(
    (data as TopLevelResult).topLevelTotal ?? (data as { topLevelClasses?: number }).topLevelClasses ?? classes.length,
  );
  return {
    classes,
    topLevelTotal,
    hierarchyEngine: (data as TopLevelResult).hierarchyEngine,
  };
}

export async function waitForTopLevelClasses(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  minCount = 1,
  timeoutMs = 120_000,
): Promise<TopLevelResult> {
  const deadline = Date.now() + timeoutMs;
  let last: TopLevelResult = { classes: [], topLevelTotal: 0 };

  while (Date.now() < deadline) {
    last = await getTopLevelClasses(request, projectId, token, apiBase);
    if (last.topLevelTotal >= minCount || last.classes.length >= minCount) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 1_500));
  }
  throw new Error(
    `Expected >=${minCount} top-level classes after ${timeoutMs}ms for ${projectId}: ${JSON.stringify(last).slice(0, 300)}`,
  );
}

export async function scheduleFusekiSync(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
): Promise<{ status: number; body: Record<string, unknown> }> {
  const url = `${apiBase}/api/desktop/schedule-fuseki-sync/${encodeURIComponent(projectId)}`;
  const res = await request.post(url, { headers: authHeaders(token) });
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { status: res.status(), body };
}

const DESKTOP_WORKSPACE_ID = 'desktop-workspace-local';

export async function createDesktopLibraryProject(
  request: APIRequestContext,
  token: string,
  apiBase: string,
  name?: string,
): Promise<{ parentProjectId: string }> {
  const projectName = name || `pw-owlapi-${Date.now()}`;

  const listRes = await request.get(`${apiBase}/api/projects`, { headers: authHeaders('') });
  if (listRes.ok()) {
    const listBody = await listRes.json().catch(() => ({}));
    const myFiles =
      (listBody as { myFiles?: Array<{ projectId?: string }> }).myFiles || [];
    const existing = myFiles.find((p) => p.projectId)?.projectId;
    if (existing) {
      return { parentProjectId: existing };
    }
  }

  const wsRes = await request.get(
    `${apiBase}/api/projects/workspace/${DESKTOP_WORKSPACE_ID}`,
    { headers: authHeaders('') },
  );
  if (wsRes.ok()) {
    const wsBody = await wsRes.json().catch(() => ({}));
    const projects =
      (wsBody as { projects?: Array<{ projectId?: string }> }).projects ||
      (wsBody as { data?: Array<{ projectId?: string }> }).data ||
      [];
    const wsProject = projects.find((p) => p.projectId)?.projectId;
    if (wsProject) {
      return { parentProjectId: wsProject };
    }
  }

  const res = await request.post(`${apiBase}/api/projects`, {
    headers: { ...authHeaders(''), 'Content-Type': 'application/json' },
    data: {
      workspaceId: DESKTOP_WORKSPACE_ID,
      name: projectName,
      description: 'Playwright OWLAPI fast-open test project',
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`create project failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const parentProjectId =
    (body as { project?: { projectId?: string } }).project?.projectId ||
    (body as { projectId?: string }).projectId ||
    (body as { data?: { projectId?: string } }).data?.projectId ||
    (body as { id?: string }).id;
  if (!parentProjectId) {
    throw new Error(`create project missing projectId: ${JSON.stringify(body).slice(0, 400)}`);
  }
  return { parentProjectId };
}

export async function uploadFileToLibraryProject(
  request: APIRequestContext,
  parentProjectId: string,
  owlPath: string,
  token: string,
  apiBase: string,
): Promise<{ fileId: string; fileName: string }> {
  const fileName = path.basename(owlPath);
  const buffer = fs.readFileSync(owlPath);
  const res = await request.post(`${apiBase}/api/projects/${encodeURIComponent(parentProjectId)}/files`, {
    headers: authHeaders(''),
    multipart: {
      file: {
        name: fileName,
        mimeType: 'application/rdf+xml',
        buffer,
      },
      fileName,
      fileType: 'application/rdf+xml',
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`library file upload failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const fileId = (body as { fileId?: string }).fileId;
  if (!fileId) {
    throw new Error(`library upload missing fileId: ${JSON.stringify(body).slice(0, 400)}`);
  }
  return { fileId, fileName };
}

export function ontologyProjectId(parentProjectId: string, fileId: string): string {
  return `${parentProjectId}--${fileId}`;
}

export async function openOntologyByFileRef(
  request: APIRequestContext,
  parentProjectId: string,
  fileId: string,
  token: string,
  apiBase: string,
): Promise<{ ontologyProjectId: string; status: string }> {
  const ontologyId = ontologyProjectId(parentProjectId, fileId);
  const q = new URLSearchParams({
    fileId,
    parentProjectId,
    action: 'replace',
    importMode: 'FULL',
    partition: 'NONE',
    workspaceId: DESKTOP_WORKSPACE_ID,
  });
  const url = `${apiBase}/api/ontology/upload-by-file-ref/${encodeURIComponent(ontologyId)}?${q}`;
  const res = await request.post(url, { headers: authHeaders(token) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok()) {
    throw new Error(`upload-by-file-ref failed (${res.status()}): ${JSON.stringify(body).slice(0, 400)}`);
  }
  const data = (body as { data?: { status?: string } }).data || body;
  const status = String((data as { status?: string }).status || '');
  return { ontologyProjectId: ontologyId, status };
}

export async function prepareFileRefDesktopProject(
  request: APIRequestContext,
  prefix = 'pw-owlapi-ref',
): Promise<{
  parentProjectId: string;
  fileId: string;
  ontologyProjectId: string;
  token: string;
  apiBase: string;
}> {
  const auth = loadDesktopAuth();
  const { parentProjectId } = await createDesktopLibraryProject(
    request,
    auth.token,
    auth.apiBase,
    `${prefix}-${Date.now()}`,
  );
  const { fileId } = await uploadFileToLibraryProject(
    request,
    parentProjectId,
    auth.fixturePath,
    auth.token,
    auth.apiBase,
  );
  const ontologyId = ontologyProjectId(parentProjectId, fileId);
  const { status } = await openOntologyByFileRef(
    request,
    parentProjectId,
    fileId,
    auth.token,
    auth.apiBase,
  );
  if (status !== 'ALREADY_LOADED' && status !== 'ALREADY_LOADING') {
    await waitForImportComplete(request, ontologyId, auth.token, auth.apiBase);
  }
  return {
    parentProjectId,
    fileId,
    ontologyProjectId: ontologyId,
    token: auth.token,
    apiBase: auth.apiBase,
  };
}

export async function assertOwlApiFastOpen(
  request: APIRequestContext,
  projectId: string,
  token: string,
  apiBase: string,
  timeoutMs = 90_000,
): Promise<{ warm: WarmResult; topLevel: TopLevelResult }> {
  const warm = await warmOntology(request, projectId, token, apiBase, timeoutMs);
  expect(warm.sparqlFallback).not.toBe(true);

  await waitForOwlApiReady(request, projectId, token, apiBase, timeoutMs);
  const cache = await getCacheStatus(request, projectId, token, apiBase);
  expect(cache.owlapiReady).toBe(true);
  expect(cache.sparqlFallback).not.toBe(true);

  const topLevel = await waitForTopLevelClasses(request, projectId, token, apiBase, 1, timeoutMs);
  expect(topLevel.hierarchyEngine).toBe('owlapi');
  expect(topLevel.topLevelTotal).toBeGreaterThan(0);

  return { warm, topLevel };
}
