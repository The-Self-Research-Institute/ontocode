import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const EMAIL = __ENV.ASSISTANT_EMAIL || 'k6-loadtest@example.com';
const PLAN = __ENV.ASSISTANT_PLAN || 'PRO';
const PROJECT_PREFIX = __ENV.PROJECT_PREFIX || 'k6-loadtest-project';
const TARGET_VUS = Number(__ENV.TARGET_VUS || 20);
const RAMP_DURATION = __ENV.RAMP_DURATION || '15s';
const SUSTAIN_DURATION = __ENV.SUSTAIN_DURATION || '30s';
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0.2);

function buildUnsignedJwt(email, plan) {
  const header = encoding.b64encode(JSON.stringify({ alg: 'none', typ: 'JWT' }), 'rawurl');
  const payload = encoding.b64encode(JSON.stringify({ email, plan }), 'rawurl');
  return `${header}.${payload}.unsigned`;
}

const AUTH_TOKEN = buildUnsignedJwt(EMAIL, PLAN);

export const sessionCreateErrors = new Rate('assistant_session_create_errors');
export const sessionCreateDuration = new Trend('assistant_session_create_duration', true);

export const options = {
  scenarios: {
    createSessionLoad: {
      executor: 'ramping-vus',
      exec: 'createSession',
      startVUs: 0,
      stages: [
        { duration: RAMP_DURATION, target: TARGET_VUS },
        { duration: SUSTAIN_DURATION, target: TARGET_VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    assistant_session_create_errors: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function createSession() {
  const projectId = `${PROJECT_PREFIX}-${__VU}-${__ITER}`;
  const payload = JSON.stringify({
    projectId,
    documentPath: '/doc.owl',
    actionType: 'ask',
    actionContext: 'k6 load test iteration',
  });

  const res = http.post(`${BASE_URL}/api/v1/code-assistant/sessions`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${AUTH_TOKEN}`,
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
