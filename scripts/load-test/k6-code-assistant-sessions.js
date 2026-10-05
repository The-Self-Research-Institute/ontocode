import http from 'k6/http';
import { devJwt, RUN_ID, uploadAndWait } from './lib/assistant-k6.js';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const EMAIL_DOMAIN = __ENV.ASSISTANT_EMAIL_DOMAIN || 'loadtest.example.test';
const PLAN = __ENV.ASSISTANT_PLAN || 'PRO';
const TARGET_VUS = Number(__ENV.TARGET_VUS || 20);
const SESSIONS_PER_VU = Number(__ENV.SESSIONS_PER_VU || 20);
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0.2);
const SEED = open('./fixtures/small-20kb.owl');

function identityFor(vu) {
  return `k6-sessions-${RUN_ID}-vu${vu}@${EMAIL_DOMAIN}`;
}

function projectFor(vu) {
  return `k6-sessions-${RUN_ID}-${vu}`;
}

export const sessionCreateErrors = new Rate('assistant_session_create_errors');
export const sessionCreateDuration = new Trend('assistant_session_create_duration', true);

export const options = {
  setupTimeout: '600s',
  scenarios: {
    createSessionLoad: {
      executor: 'per-vu-iterations',
      exec: 'createSession',
      vus: TARGET_VUS,
      iterations: SESSIONS_PER_VU,
      maxDuration: '5m',
    },
  },
  thresholds: {
    'http_req_duration{name:CreateAssistantSession}': ['p(95)<800', 'p(99)<1500'],
    assistant_session_create_errors: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  for (let vu = 1; vu <= TARGET_VUS; vu++) {
    uploadAndWait(projectFor(vu), 'seed.owl', SEED, devJwt(identityFor(vu), PLAN));
  }
}

export function createSession() {
  const payload = JSON.stringify({
    projectId: projectFor(__VU),
    documentPath: '/doc.owl',
    actionType: 'ask',
    actionContext: 'k6 load test iteration',
  });

  const res = http.post(`${BASE_URL}/api/v1/code-assistant/sessions`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${devJwt(identityFor(__VU), PLAN)}`,
    },
    tags: { name: 'CreateAssistantSession' },
  });

  sessionCreateDuration.add(res.timings.duration);

  const parsed = (() => {
    try {
      return res.json();
    } catch (e) {
      return null;
    }
  })();

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'body has sessionId': () => !!(parsed && parsed.sessionId),
    'budget starts at max': () =>
      !!(parsed && parsed.budget && parsed.budget.retrievalCallsRemaining === parsed.budget.maxRetrievalCalls),
  });

  sessionCreateErrors.add(!ok);

  if (THINK_TIME_SECONDS > 0) {
    sleep(THINK_TIME_SECONDS);
  }
}
