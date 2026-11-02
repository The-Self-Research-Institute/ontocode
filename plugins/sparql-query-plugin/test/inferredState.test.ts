import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  switchState, effectiveMode, relativeTime, layersText, infoLine, partialMessages, countsText,
  mapConflict, needsCompute, buildRequestBody, statusUrl, usedInferred, loadMode, saveMode, canRefresh, storageKey
} from '../src/inferredState.ts';

test('switch is disabled only when the ontology is too large', () => {
  assert.equal(switchState(null).disabled, false);
  assert.equal(switchState({ available: true }).disabled, false);
  assert.deepEqual(switchState({ available: false }), {
    disabled: false,
    tooltip: 'Also search facts worked out by the reasoner. The first use computes them and can take up to a minute.'
  });
  assert.deepEqual(switchState({ available: false, stale: true }), {
    disabled: false,
    tooltip: 'The ontology changed since the inferred facts were computed. Running the query computes them again.'
  });
  assert.deepEqual(switchState({ available: false, tooLarge: true }), { disabled: true, tooltip: 'Too large to include inferred facts' });
  assert.deepEqual(switchState({ available: true, tooLarge: true }), { disabled: true, tooltip: 'Too large to include inferred facts' });
  assert.equal(effectiveMode('inferred', { available: false }), 'inferred');
  assert.equal(effectiveMode('inferred', { available: false, stale: true }), 'inferred');
  assert.equal(effectiveMode('inferred', { available: true, tooLarge: true }), 'asserted');
  assert.equal(effectiveMode('inferred', { available: true }), 'inferred');
  assert.equal(effectiveMode('asserted', { available: true }), 'asserted');
});

test('a first compute is needed only when no snapshot exists and the ontology is not too large', () => {
  assert.equal(needsCompute({ available: false }), true);
  assert.equal(needsCompute({ available: false, stale: true }), true);
  assert.equal(needsCompute({ available: false, tooLarge: true }), false);
  assert.equal(needsCompute({ available: true }), false);
  assert.equal(needsCompute(null), false);
});

test('relative time in plain words', () => {
  const now = Date.parse('2026-10-28T12:00:00Z');
  assert.equal(relativeTime('2026-10-28T11:59:50Z', now), 'just now');
  assert.equal(relativeTime('2026-10-28T11:55:00Z', now), '5 minutes ago');
  assert.equal(relativeTime('2026-10-28T09:00:00Z', now), '3 hours ago');
  assert.equal(relativeTime('2026-10-26T12:00:00Z', now), '2 days ago');
  assert.equal(relativeTime(undefined, now), 'at an unknown time');
  assert.equal(relativeTime('garbage', now), 'at an unknown time');
});

test('info line has the required wording, names HermiT and never claims full reasoning', () => {
  const now = Date.parse('2026-10-28T12:00:00Z');
  const line = infoLine({ computedAt: '2026-10-28T11:55:00Z', layers: ['owl', 'rules'] }, now);
  assert.ok(line.startsWith('Includes the documented, tested equality consequences'));
  assert.ok(line.includes('HermiT, computed 5 minutes ago'));
  assert.ok(line.includes('class and instance reasoning, rule conclusions'));
  assert.ok(!/full owl reasoning|openllet|pellet/i.test(line));
  assert.equal(layersText([]), 'no extra layers listed');
});

test('partial reasons and incomplete equality are listed in plain words', () => {
  assert.deepEqual(partialMessages(undefined), []);
  assert.deepEqual(partialMessages({ partial: false }), []);
  const msgs = partialMessages({ partial: true, partialReasons: ['timeout', 'weird_new_reason'], equality: { incomplete: true } });
  assert.equal(msgs.length, 3);
  assert.ok(msgs[0].includes('time limit'));
  assert.ok(msgs[1].includes('weird new reason'));
  assert.equal(msgs[2], 'Some equivalent-name facts were left out because a limit was reached');
  assert.equal(partialMessages({ partial: true }).length, 1);
  assert.equal(partialMessages({ equality: { incomplete: true }, partialReasons: ['equality_limit'] }).length, 1);
});

test('counts text', () => {
  assert.equal(countsText({}), null);
  assert.equal(countsText({ assertedTripleCount: 1200, inferredTripleCount: 34 }), '1,200 stated, 34 inferred triples searched');
});

test('maps 409 bodies from several client error shapes', () => {
  const body = { needsReasoning: true, reason: 'stale', message: 'Out of date', computedAt: '2026-10-28T11:00:00Z' };
  for (const source of [body, { body }, { data: body }, { response: { data: body } }, { message: 'HTTP 409: ' + JSON.stringify(body) }]) {
    const mapped = mapConflict(source);
    assert.equal(mapped?.kind, 'needsReasoning');
    assert.equal((mapped as any).reason, 'stale');
    assert.equal((mapped as any).message, 'Out of date');
  }
  const inconsistent = mapConflict({ data: { status: 'INCONSISTENT', message: 'No model', explanation: 'A and B clash' } });
  assert.deepEqual(inconsistent, { kind: 'inconsistent', message: 'No model', explanation: 'A and B clash' });
  assert.equal(mapConflict({ error: 'boom' }), null);
  assert.equal(mapConflict(null), null);
  assert.equal(mapConflict(new Error('plain failure')), null);
});

test('needsReasoning without a message uses plain wording per reason', () => {
  const none = mapConflict({ needsReasoning: true, reason: 'none' }) as any;
  assert.ok(none.message.includes('have not been computed yet'));
  const timeout = mapConflict({ needsReasoning: true, reason: 'timeout' }) as any;
  assert.ok(timeout.message.includes('took too long'));
  assert.equal(canRefresh('too_large'), false);
  assert.equal(canRefresh('stale'), true);
});

test('request body only changes for inferred runs', () => {
  assert.deepEqual(buildRequestBody({ query: 'q' }, 'asserted', true), { query: 'q' });
  assert.deepEqual(buildRequestBody({ query: 'q', format: 'turtle' }, 'inferred', false), { query: 'q', format: 'turtle', includeInferred: true });
  assert.deepEqual(buildRequestBody({ query: 'q' }, 'inferred', true), { query: 'q', includeInferred: true, refresh: true });
});

test('status url and used flag', () => {
  assert.equal(statusUrl('p1', false), '/api/sparql/inference/p1/status');
  assert.equal(statusUrl('p1', true), '/api/sparql/inference/p1/status?draft=true');
  assert.equal(usedInferred({}), null);
  assert.equal(usedInferred({ inferred: { used: false } }), null);
  assert.deepEqual(usedInferred({ inferred: { used: true, layers: ['owl'] } }), { used: true, layers: ['owl'] });
});

test('choice is remembered per project and storage failures are ignored', () => {
  const data = new Map<string, string>();
  const store = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) };
  assert.equal(loadMode('a', store), 'asserted');
  saveMode('a', 'inferred', store);
  assert.equal(loadMode('a', store), 'inferred');
  assert.equal(loadMode('b', store), 'asserted');
  assert.notEqual(storageKey('a'), storageKey('b'));
  const broken = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
  assert.equal(loadMode('a', broken), 'asserted');
  assert.doesNotThrow(() => saveMode('a', 'inferred', broken));
});
