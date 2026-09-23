import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const EMAIL = __ENV.ASSISTANT_EMAIL || 'k6-loadtest@example.com';
const PLAN = __ENV.ASSISTANT_PLAN || 'PRO';
const PROJECT_PREFIX = __ENV.PROJECT_PREFIX || 'k6-groupapply';
const TARGET_FORMAT = __ENV.TARGET_FORMAT || 'turtle';
const DISJOINT_GROUP_COUNT = Number(__ENV.DISJOINT_GROUP_COUNT || 5);
const PARALLEL_PROJECT_COUNT = Number(__ENV.PARALLEL_PROJECT_COUNT || 5);
const IMPORT_POLL_TIMEOUT_SECONDS = Number(__ENV.IMPORT_POLL_TIMEOUT_SECONDS || 60);
const IMPORT_POLL_INTERVAL_SECONDS = Number(__ENV.IMPORT_POLL_INTERVAL_SECONDS || 1);

function buildUnsignedJwt(email, plan) {
  const header = encoding.b64encode(JSON.stringify({ alg: 'none', typ: 'JWT' }), 'rawurl');
  const payload = encoding.b64encode(JSON.stringify({ email, plan }), 'rawurl');
  return `${header}.${payload}.unsigned`;
}

const AUTH_TOKEN = buildUnsignedJwt(EMAIL, PLAN);

export const applyDisjointErrors = new Rate('assistant_apply_disjoint_errors');
export const applyDisjointDuration = new Trend('assistant_apply_disjoint_duration', true);
export const applyOverlapUnexpectedResult = new Rate('assistant_apply_overlap_unexpected_result');
export const applyOverlapDuration = new Trend('assistant_apply_overlap_duration', true);
export const applyParallelErrors = new Rate('assistant_apply_parallel_errors');
export const applyParallelDuration = new Trend('assistant_apply_parallel_duration', true);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    disjointGroupsOneProject: {
      executor: 'shared-iterations',
      exec: 'scenarioA',
      vus: 1,
      iterations: 1,
      startTime: '0s',
      maxDuration: '30s',
    },
    overlappingGroupsOneCluster: {
      executor: 'shared-iterations',
      exec: 'scenarioB',
      vus: 1,
      iterations: 1,
      startTime: '5s',
      maxDuration: '30s',
    },
    disjointProjectsInParallel: {
      executor: 'per-vu-iterations',
      exec: 'scenarioC',
      vus: PARALLEL_PROJECT_COUNT,
      iterations: 1,
      startTime: '10s',
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<4000'],
    checks: ['rate>0.99'],
    assistant_apply_disjoint_errors: ['rate<0.01'],
    assistant_apply_overlap_unexpected_result: ['rate<0.01'],
    assistant_apply_parallel_errors: ['rate<0.01'],
  },
};

function jsonHeaders() {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${AUTH_TOKEN}` };
}

function authHeaders() {
  return { Authorization: `Bearer ${AUTH_TOKEN}` };
}

function safeJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function applyUrl(sessionId, serverGroupId) {
  return `${BASE_URL}/api/v1/code-assistant/sessions/${sessionId}/groups/${serverGroupId}/apply`;
}

function buildSeedTurtle(numSlots, runId) {
  const lines = [
    '@prefix : <http://example.org/k6-loadtest/> .',
    '@prefix owl: <http://www.w3.org/2002/07/owl#> .',
    '@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .',
    '',
  ];
  for (let i = 0; i < numSlots; i++) {
    lines.push(`:Slot${i}_${runId} a owl:Class ;`);
    lines.push(`    rdfs:label "k6-marker-${runId}-slot${i}" .`);
    lines.push('');
  }
  return lines.join('\n');
}

function uploadSeedProject(projectId, turtleContent) {
  const res = http.post(
    `${BASE_URL}/api/ontology/upload/${projectId}`,
    { file: http.file(turtleContent, `${projectId}.ttl`, 'text/turtle') },
    { headers: authHeaders(), tags: { name: 'SeedUploadProject' } },
  );
  if (res.status !== 200) {
    throw new Error(`setup: upload failed for project ${projectId}: HTTP ${res.status} ${res.body}`);
  }
  const body = safeJson(res);
  if (!body || body.success !== true) {
    throw new Error(`setup: upload did not report success for project ${projectId}: ${res.body}`);
  }
}

function waitForImportComplete(projectId) {
  const deadline = Date.now() + IMPORT_POLL_TIMEOUT_SECONDS * 1000;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE_URL}/api/ontology/status/${projectId}`, {
      tags: { name: 'SeedImportStatus' },
    });
    const body = safeJson(res);
    const status = body && body.data ? body.data : null;
    if (status && status.status === 'COMPLETED') {
      return;
    }
    if (status && status.status === 'ERROR') {
      throw new Error(`setup: import failed for project ${projectId}: ${status.statusMessage || 'unknown error'}`);
    }
    sleep(IMPORT_POLL_INTERVAL_SECONDS);
  }
  throw new Error(`setup: import did not complete for project ${projectId} within ${IMPORT_POLL_TIMEOUT_SECONDS}s`);
}

function seedAndImport(projectId, numSlots, runId) {
  uploadSeedProject(projectId, buildSeedTurtle(numSlots, runId));
  waitForImportComplete(projectId);
}

function fetchAllLines(projectId) {
  const res = http.get(
    `${BASE_URL}/api/ontology/${projectId}/content-page?format=${TARGET_FORMAT}&startLine=0&lineCount=5000`,
    { tags: { name: 'SeedContentPage' } },
  );
  if (res.status !== 200) {
    throw new Error(`setup: content-page failed for project ${projectId}: HTTP ${res.status}`);
  }
  const body = safeJson(res);
  if (!body || typeof body.content !== 'string') {
    throw new Error(`setup: content-page returned no content for project ${projectId}`);
  }
  return body.content.split('\n');
}

function createAssistantSession(projectId) {
  const payload = JSON.stringify({
    projectId,
    documentPath: `/${projectId}.ttl`,
    actionType: 'ask',
    actionContext: 'k6 group-apply load test setup',
  });
  const res = http.post(`${BASE_URL}/api/v1/code-assistant/sessions`, payload, {
    headers: jsonHeaders(),
    tags: { name: 'CreateAssistantSessionForGroupApply' },
  });
  if (res.status !== 200) {
    throw new Error(`setup: session create failed for project ${projectId}: HTTP ${res.status} ${res.body}`);
  }
  const body = safeJson(res);
  if (!body || !body.sessionId) {
    throw new Error(`setup: session create returned no sessionId for project ${projectId}: ${res.body}`);
  }
  return body;
}

function proposeGroups(sessionId, groups) {
  const payload = JSON.stringify({ groups });
  const res = http.post(`${BASE_URL}/api/v1/code-assistant/sessions/${sessionId}/propose`, payload, {
    headers: jsonHeaders(),
    tags: { name: 'ProposeGroupApplySetup' },
  });
  if (res.status !== 200) {
    throw new Error(`setup: propose failed for session ${sessionId}: HTTP ${res.status} ${res.body}`);
  }
  const body = safeJson(res);
  if (!body || body.ok !== true) {
    throw new Error(`setup: propose returned ok=false for session ${sessionId}: ${res.body}`);
  }
  body.groups.forEach((group) => {
    if (!group.validation || group.validation.passed !== true) {
      throw new Error(
        `setup: proposed group ${group.clientGroupId} failed validation for session ${sessionId}: ${JSON.stringify(group.validation)}`,
      );
    }
  });
  return body;
}

function findMarkerLine(lines, marker) {
  const index = lines.findIndex((line) => line.includes(marker));
  if (index === -1) {
    throw new Error(`setup: marker "${marker}" not found in live document content`);
  }
  return { index, text: lines[index] };
}

function singleLineEdit(lines, index, marker, suffix) {
  const original = lines[index];
  return {
    targetPath: TARGET_FORMAT,
    range: { startLine: index, lineCount: 1 },
    originalText: original,
    newText: original.replace(marker, `${marker}-${suffix}`),
  };
}

function twoLineEdit(lines, index, marker, suffix) {
  if (index - 1 < 0) {
    throw new Error(`setup: cannot build a two-line overlapping edit at line ${index}, no preceding line exists`);
  }
  const original = `${lines[index - 1]}\n${lines[index]}`;
  return {
    targetPath: TARGET_FORMAT,
    range: { startLine: index - 1, lineCount: 2 },
    originalText: original,
    newText: original.replace(marker, `${marker}-${suffix}`),
  };
}

function setUpDisjointGroupsScenario(runId) {
  const projectId = `${PROJECT_PREFIX}-a-${runId}`;
  seedAndImport(projectId, DISJOINT_GROUP_COUNT, runId);
  const session = createAssistantSession(projectId);
  const lines = fetchAllLines(projectId);

  const groupInputs = [];
  for (let i = 0; i < DISJOINT_GROUP_COUNT; i++) {
    const marker = `k6-marker-${runId}-slot${i}`;
    const found = findMarkerLine(lines, marker);
    groupInputs.push({ clientGroupId: `a-${i}`, edits: [singleLineEdit(lines, found.index, marker, 'applied')] });
  }

  const proposed = proposeGroups(session.sessionId, groupInputs);
  return {
    projectId,
    sessionId: session.sessionId,
    serverGroupIds: proposed.groups.map((group) => group.serverGroupId),
  };
}

function setUpOverlappingGroupsScenario(runId) {
  const projectId = `${PROJECT_PREFIX}-b-${runId}`;
  seedAndImport(projectId, 3, runId);
  const session = createAssistantSession(projectId);
  const lines = fetchAllLines(projectId);

  const marker = `k6-marker-${runId}-slot0`;
  const found = findMarkerLine(lines, marker);
  const groupInputs = [
    { clientGroupId: 'b-same-range-0', edits: [singleLineEdit(lines, found.index, marker, 'winner0')] },
    { clientGroupId: 'b-same-range-1', edits: [singleLineEdit(lines, found.index, marker, 'winner1')] },
    { clientGroupId: 'b-overlapping-range-2', edits: [twoLineEdit(lines, found.index, marker, 'winner2')] },
  ];

  const proposed = proposeGroups(session.sessionId, groupInputs);
  return {
    projectId,
    sessionId: session.sessionId,
    serverGroupIds: proposed.groups.map((group) => group.serverGroupId),
  };
}

function setUpParallelProjectsScenario(runId) {
  const projects = [];
  for (let p = 0; p < PARALLEL_PROJECT_COUNT; p++) {
    const projectId = `${PROJECT_PREFIX}-c${p}-${runId}`;
    seedAndImport(projectId, 1, runId);
    const session = createAssistantSession(projectId);
    const lines = fetchAllLines(projectId);
    const marker = `k6-marker-${runId}-slot0`;
    const found = findMarkerLine(lines, marker);
    const proposed = proposeGroups(session.sessionId, [
      { clientGroupId: `c-${p}`, edits: [singleLineEdit(lines, found.index, marker, 'applied')] },
    ]);
    projects.push({
      projectId,
      sessionId: session.sessionId,
      serverGroupId: proposed.groups[0].serverGroupId,
    });
  }
  return { projects };
}

export function setup() {
  const runId = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  return {
    scenarioA: setUpDisjointGroupsScenario(runId),
    scenarioB: setUpOverlappingGroupsScenario(runId),
    scenarioC: setUpParallelProjectsScenario(runId),
  };
}

export function scenarioA(data) {
  const { sessionId, serverGroupIds } = data.scenarioA;
  const requests = serverGroupIds.map((serverGroupId) => ({
    method: 'POST',
    url: applyUrl(sessionId, serverGroupId),
    body: null,
    params: { headers: authHeaders(), tags: { name: 'ApplyDisjointGroup' } },
  }));

  const responses = http.batch(requests);
  const newRevisions = [];

  responses.forEach((res) => {
    applyDisjointDuration.add(res.timings.duration);
    const body = safeJson(res);
    const ok = check(res, {
      'disjoint apply: status is 200': (r) => r.status === 200,
      'disjoint apply: ok is true': () => !!(body && body.ok === true),
      'disjoint apply: applied is true': () => !!(body && body.applied === true),
      'disjoint apply: newRevision is a number': () => !!(body && typeof body.newRevision === 'number'),
    });
    applyDisjointErrors.add(!ok);
    if (body && typeof body.newRevision === 'number') {
      newRevisions.push(body.newRevision);
    }
  });

  check(null, {
    'disjoint apply: every concurrent apply succeeded': () => newRevisions.length === serverGroupIds.length,
    'disjoint apply: every newRevision is distinct': () => new Set(newRevisions).size === newRevisions.length,
  });
}

export function scenarioB(data) {
  const { sessionId, serverGroupIds } = data.scenarioB;
  const requests = serverGroupIds.map((serverGroupId) => ({
    method: 'POST',
    url: applyUrl(sessionId, serverGroupId),
    body: null,
    params: { headers: authHeaders(), tags: { name: 'ApplyOverlappingGroup' } },
  }));

  const responses = http.batch(requests);
  let winnerCount = 0;
  let expectedLoserCount = 0;

  responses.forEach((res) => {
    applyOverlapDuration.add(res.timings.duration);
    const body = safeJson(res);
    const isWinner = !!(body && body.ok === true && body.applied === true && typeof body.newRevision === 'number');
    const isExpectedLoser =
      !!(body && body.ok === false && ['STALE_GROUP', 'CONFLICT'].includes(body.errorCode));
    const matchesContract = isWinner || isExpectedLoser;

    check(res, {
      'overlap apply: status is 200': (r) => r.status === 200,
      'overlap apply: result is a winner or an expected loser': () => matchesContract,
    });
    applyOverlapUnexpectedResult.add(!matchesContract);

    if (isWinner) {
      winnerCount++;
    }
    if (isExpectedLoser) {
      expectedLoserCount++;
    }
  });

  check(null, {
    'overlap apply: exactly one winner in the cluster': () => winnerCount === 1,
    'overlap apply: every other group lost with a recognized error code': () =>
      expectedLoserCount === serverGroupIds.length - 1,
  });
}

export function scenarioC(data) {
  const projects = data.scenarioC.projects;
  const target = projects[(__VU - 1) % projects.length];

  const res = http.post(applyUrl(target.sessionId, target.serverGroupId), null, {
    headers: authHeaders(),
    tags: { name: 'ApplyParallelProjectGroup' },
  });

  applyParallelDuration.add(res.timings.duration);
  const body = safeJson(res);
  const ok = check(res, {
    'parallel apply: status is 200': (r) => r.status === 200,
    'parallel apply: ok is true': () => !!(body && body.ok === true),
    'parallel apply: newRevision is a number': () => !!(body && typeof body.newRevision === 'number'),
  });
  applyParallelErrors.add(!ok);
}
