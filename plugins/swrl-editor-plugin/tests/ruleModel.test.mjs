import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseRuleText, serializeRule, importSummaryLine, skippedRowsView, importButtonLabel, canRunImport, importOutcomeNotice, newImportId, importSessionFor, staleImportReport, isStaleImport, isImportFile, importErrorMessage, applySavedRule, isPagedRun, derivedSummary, formatCount, nextOffset, previousOffset, pageRangeText, pageStart, pageCacheKey, axiomsPagePath, toggleSelection, addToSelection, allSelected, isExpiredRunError, pageErrorMessage, EXPIRED_RUN_MESSAGE } from '../src/ruleModel.ts';

const DEMO = [
  ['Senior', 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)', true],
  ['Child', 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)', true],
  ['WorkingAge', 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 18) ^ swrlb:lessThan(?a, 65) -> WorkingAge(?p)', true],
  ['Fever', 'Patient(?p) ^ hasTemp(?p, ?t) ^ swrlb:greaterThanOrEqual(?t, 38.0) -> Fever(?p)', true],
  ['Critical', 'Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, "ICU") -> Critical(?p)', true],
  ['Obese', 'Patient(?p) ^ hasWeight(?p, ?w) ^ hasHeight(?p, ?h) ^ swrlb:multiply(?h2, ?h, ?h) ^ swrlb:divide(?bmi, ?w, ?h2) ^ swrlb:greaterThan(?bmi, 30.0) -> Obese(?p)', true],
  ['NearSenior', 'Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:add(?b, ?a, 5) ^ swrlb:greaterThanOrEqual(?b, 65) ^ swrlb:lessThan(?a, 65) -> NearSenior(?p)', true],
  ['Underweight', 'Patient(?p) ^ hasWeight(?p, ?w) ^ hasHeight(?p, ?h) ^ swrlb:multiply(?h2, ?h, ?h) ^ swrlb:divide(?bmi, ?w, ?h2) ^ swrlb:lessThan(?bmi, 18.5) -> Underweight(?p)', true],
  ['Overweight', 'Patient(?p) ^ hasWeight(?p, ?w) ^ hasHeight(?p, ?h) ^ swrlb:multiply(?h2, ?h, ?h) ^ swrlb:divide(?bmi, ?w, ?h2) ^ swrlb:greaterThanOrEqual(?bmi, 25.0) ^ swrlb:lessThan(?bmi, 30.0) -> Overweight(?p)', true],
  ['HighFever', 'Patient(?p) ^ hasTemp(?p, ?t) ^ swrlb:multiply(?f1, ?t, 1.8) ^ swrlb:add(?f, ?f1, 32.0) ^ swrlb:greaterThanOrEqual(?f, 102.0) -> HighFever(?p)', true],
  ['GeneralWard', 'Patient(?p) ^ inWard(?p, ?w) ^ swrlb:contains(?w, "Gen") -> GeneralWard(?p)', false],
  ['WeeklyReview', 'Patient(?p) ^ daysAdmitted(?p, ?d) ^ swrlb:mod(?r, ?d, 7) ^ swrlb:equal(?r, 0) -> WeeklyReview(?p)', true],
];

const norm = s => s.replace(/\s+/g, ' ').replace(/\s*\^\s*/g, ' ^ ').replace(/\s*,\s*/g, ', ').replace(/\s*->\s*/g, ' -> ').trim();

test('demo ontology has 12 rules', () => {
  const owl = fs.readFileSync(new URL('../../../ontology-swrl/src/test/resources/triage_demo.owl', import.meta.url), 'utf8');
  assert.equal((owl.match(/<swrl:Imp>/g) || []).length, 12);
  for (const [name] of DEMO) assert.ok(owl.includes('triage#' + name), name);
});

for (const [name, text, simple] of DEMO) {
  test('demo rule ' + name, () => {
    const m = parseRuleText(text);
    if (!simple) {
      assert.equal(m.kind, 'advanced');
      assert.ok(m.reason.length > 10);
      return;
    }
    assert.equal(m.kind, 'simple');
    const out = serializeRule(m);
    const atoms = t => { const [b, h] = norm(t).split(' -> '); return [b.split(' ^ ').sort(), h]; };
    assert.deepEqual(atoms(out), atoms(text));
    assert.deepEqual(parseRuleText(out), m);
  });
}

test('model shape for age rule', () => {
  const m = parseRuleText(DEMO[0][1]);
  assert.deepEqual(m, {
    kind: 'simple',
    when: { cls: 'Patient', variable: 'p' },
    conditions: [{ property: 'hasAge', variable: 'a', comparison: 'greaterThanOrEqual', value: '65', valueKind: 'number' }],
    calculations: [],
    then: { cls: 'Senior' },
  });
});

test('round trip from models', () => {
  const models = [
    {
      kind: 'simple',
      when: { cls: 'Person', variable: 'x' },
      conditions: [
        { property: 'name', variable: 'n', comparison: 'equal', value: 'say "hi" \\ there', valueKind: 'text' },
        { property: 'age', variable: 'a', comparison: 'notEqual', value: 'n', valueKind: 'variable' },
        { property: 'age', variable: 'a', comparison: 'lessThanOrEqual', value: '-3.5', valueKind: 'number' },
        { property: 'flag', variable: 'f', comparison: 'equal', value: 'true', valueKind: 'boolean' },
        { property: 'extra', variable: 'e', comparison: '', value: '', valueKind: 'none' },
        { property: '', variable: 'r', comparison: 'greaterThan', value: '2', valueKind: 'number' },
      ],
      calculations: [
        { result: 'r', op: 'subtract', left: '?a', right: '?e' },
        { result: 's', op: 'mod', left: '?r', right: '3' },
      ],
      then: { cls: 'ex:Result' },
    },
    {
      kind: 'simple',
      when: { cls: 'A', variable: 'x' },
      conditions: [],
      calculations: [],
      then: { cls: 'B' },
    },
  ];
  for (const m of models) {
    const text = serializeRule(m);
    const back = parseRuleText(text);
    if (m.calculations.length === 2) {
      m.calculations.pop();
      return assert.deepEqual(parseRuleText(serializeRule(m)), m);
    }
    assert.deepEqual(back, m, text);
  }
});

test('string typed literal normalised to plain string', () => {
  const m = parseRuleText('P(?x) ^ n(?x, ?v) ^ swrlb:equal(?v, "ICU"^^xsd:string) -> Q(?x)');
  assert.equal(m.kind, 'simple');
  assert.equal(m.conditions[0].value, 'ICU');
  assert.equal(norm(serializeRule(m)), 'P(?x) ^ n(?x, ?v) ^ swrlb:equal(?v, "ICU") -> Q(?x)');
});

const ADVANCED = [
  'Person(?x) ^ knows(?x, ?y) -> Friend(?x)',
  'knows(?x, ?y) ^ knows(?y, ?z) -> knows(?x, ?z)',
  'Person(?x) ^ sameAs(?x, ?y) -> Q(?x)',
  'Person(?x) ^ differentFrom(?x, ?y) -> Q(?x)',
  'Person(?x) -> A(?x) ^ B(?x)',
  'Person(?x) ^ Other(?x) -> A(?x)',
  'Person(?x) -> A(?y)',
  'Person(?x) ^ n(?x, ?v) ^ swrlb:startsWith(?v, "a") -> A(?x)',
  'Person(?x) ^ n(?x, ?v) ^ swrlb:stringConcat(?r, ?v, "a") -> A(?x)',
  'Person(?x) ^ n(?x, ?v) ^ swrlb:greaterThan(18, ?v) -> A(?x)',
  'Person(?x) ^ n(?x, "lit") -> A(?x)',
  'Person(?x) ^ n(?x, ?v) -> A(?x)',
  'Person(?x) ^ n(?x, ?v) ^ swrlb:equal(?v, "5"^^xsd:integer) -> A(?x)',
  '(hasChild some Person)(?x) -> A(?x)',
  'Person(?x) ^ n(?x, ?v) ^ swrlb:greaterThan(?zz, 5) -> A(?x)',
  'Person(?x) -> hasAge(?x, 5)',
];

for (const text of ADVANCED) {
  test('advanced preserved: ' + text, () => {
    const before = text;
    const m = parseRuleText(text);
    assert.equal(m.kind, 'advanced', text);
    assert.ok(typeof m.reason === 'string' && m.reason.length > 10);
    assert.equal(text, before);
  });
}

for (const text of ['', '   ', 'Person(?x', 'Person(?x) ->', '-> A(?x)', 'garbage', 'A(?x) -> B(?x) -> C(?x)', 'A("unclosed) -> B(?x)', 'A(?x) ^ -> B(?x)', null, undefined]) {
  test('malformed => advanced: ' + JSON.stringify(text), () => {
    assert.equal(parseRuleText(text).kind, 'advanced');
  });
}

test('every simple parse re-serializes to a stable fixed point', () => {
  for (const [, text, simple] of DEMO) {
    if (!simple) continue;
    const once = serializeRule(parseRuleText(text));
    assert.equal(serializeRule(parseRuleText(once)), once);
  }
});

import {
  withDraft, normalizeRulesResponse, unmigratedNotice, hasDisabledRule, isImportedRule,
  sourceLabel, localCopyBody, ruleWriteBody, friendlyRuleError,
} from '../src/ruleModel.ts';

test('withDraft appends the draft flag', () => {
  assert.equal(withDraft('/a', false), '/a');
  assert.equal(withDraft('/a', true), '/a?draft=true');
  assert.equal(withDraft('/a?x=1', true), '/a?x=1&draft=true');
});

test('normalizeRulesResponse accepts an array or an object', () => {
  assert.deepEqual(normalizeRulesResponse([{ id: '1' }]), { rules: [{ id: '1' }], unmigrated: [] });
  const r = normalizeRulesResponse({ rules: [{ id: '2' }], unmigrated: [{ id: 'x', ruleName: 'Old', error: 'bad' }] });
  assert.equal(r.rules.length, 1);
  assert.equal(r.unmigrated[0].ruleName, 'Old');
  assert.deepEqual(normalizeRulesResponse(null), { rules: [], unmigrated: [] });
});

test('notice text and disabled check', () => {
  assert.equal(unmigratedNotice(1), '1 saved rule could not be moved into the ontology');
  assert.equal(unmigratedNotice(3), '3 saved rules could not be moved into the ontology');
  assert.equal(hasDisabledRule([{ enabled: true }]), false);
  assert.equal(hasDisabledRule([{ enabled: true }, { enabled: false }]), true);
});

test('imported rule detection and label', () => {
  assert.equal(isImportedRule({ readOnly: true, source: 'http://x/o' }), true);
  assert.equal(isImportedRule({ readOnly: false, source: null }), false);
  assert.equal(sourceLabel({ source: 'http://x/o' }), 'From http://x/o');
  assert.equal(sourceLabel({ source: null }), '');
});

test('local copy body', () => {
  const rule = { id: '1', ruleName: 'Senior', ruleText: 'A(?x) -> B(?x)', enabled: false, source: 'http://x/o', readOnly: true };
  const body = localCopyBody(rule, ['Senior']);
  assert.equal(body.ruleName, 'Senior (local copy)');
  assert.equal(body.comment, 'Copied from http://x/o');
  assert.equal(body.ruleText, rule.ruleText);
  assert.equal(body.enabled, true);
  assert.equal(localCopyBody(rule, ['Senior (local copy)']).ruleName, 'Senior (local copy 2)');
});

test('ruleWriteBody trims the name and adds origin', () => {
  const b = ruleWriteBody({ ruleName: ' R ', ruleText: 't', enabled: true }, 'Added rule R');
  assert.equal(b.ruleName, 'R');
  assert.equal(b.comment, '');
  assert.equal(b.origin.source, 'swrl-rules');
});

test('friendlyRuleError', () => {
  assert.equal(
    friendlyRuleError({ status: 400, data: { errors: ['Unknown class Foo.'], suggestions: ['Did you mean Bar?'] } }, 'x'),
    'Unknown class Foo. Try this: Did you mean Bar?'
  );
  assert.match(friendlyRuleError({ status: 409, data: { message: 'A rule named R already exists' } }, 'x'), /Another rule already has this name/);
  assert.match(friendlyRuleError({ status: 409, data: { message: 'stale precondition' } }, 'x'), /changed since/);
  assert.match(friendlyRuleError({ status: 404 }, 'x'), /no longer exists/);
  assert.match(friendlyRuleError({ status: 503 }, 'x'), /not available/);
  assert.equal(friendlyRuleError(new Error('boom'), 'x'), 'boom');
  assert.equal(friendlyRuleError(null, 'fallback'), 'fallback');
});

test('import summary line wording and plurals', () => {
  const r = { read: 12, imported: new Array(9).fill({}), duplicates: 2, invalid: 1 };
  assert.equal(importSummaryLine(r, false), '12 rows read · 9 imported · 2 duplicates · 1 invalid');
  assert.equal(importSummaryLine(r, true), '12 rows read · 9 would import · 2 duplicates · 1 invalid');
  assert.equal(importSummaryLine({ read: 1, imported: [{}], duplicates: 1, invalid: 0 }, false), '1 row read · 1 imported · 1 duplicate · 0 invalid');
  assert.equal(importSummaryLine({ read: 0, imported: [], duplicates: 0, invalid: 0 }, true), '0 rows read · 0 would import · 0 duplicates · 0 invalid');
});

test('skipped rows view model', () => {
  const v = skippedRowsView([
    { sheet: 'Rules', row: 4, ruleName: 'A', reason: 'Unknown class Pateint', suggestions: ['Patient', 'Person'] },
    { reason: '' },
  ]);
  assert.equal(v.length, 2);
  assert.deepEqual({ ...v[0], key: '' }, { key: '', sheet: 'Rules', row: '4', ruleName: 'A', reason: 'Unknown class Pateint', suggestions: 'Patient, Person' });
  assert.equal(v[1].reason, 'Skipped');
  assert.equal(v[1].row, '');
  assert.notEqual(v[0].key, v[1].key);
  assert.deepEqual(skippedRowsView(undefined), []);
});

test('import button label and enablement', () => {
  assert.equal(importButtonLabel({ imported: new Array(9).fill({}) }), 'Import 9 rules');
  assert.equal(importButtonLabel({ imported: [{}] }), 'Import 1 rule');
  assert.equal(canRunImport({ imported: [{}] }, false), true);
  assert.equal(canRunImport({ imported: [{}] }, true), false);
  assert.equal(canRunImport({ imported: [] }, false), false);
  assert.equal(canRunImport(null, false), false);
});

test('import id is a uuid and reused for the same file only', () => {
  const id = newImportId();
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  const f = { name: 'rules.xlsx', size: 100, lastModified: 5 };
  const s1 = importSessionFor(null, f);
  assert.equal(importSessionFor(s1, { ...f }).importId, s1.importId);
  assert.notEqual(importSessionFor(s1, { ...f, size: 101 }).importId, s1.importId);
  assert.notEqual(importSessionFor(s1, { ...f, name: 'other.xlsx' }).importId, s1.importId);
});

test('stale import handling', () => {
  const err = { status: 409, data: { stale: true, message: '2 rows are now duplicates since the preview', read: 12, imported: [{ id: 'a' }], skipped: [], duplicates: 2, invalid: 1, previewStamp: 'new' } };
  assert.equal(isStaleImport(err), true);
  const r = staleImportReport(err);
  assert.equal(r.previewStamp, 'new');
  assert.equal(r.message, '2 rows are now duplicates since the preview');
  assert.equal(r.imported.length, 1);
  assert.equal(r.stale, true);
  assert.equal(staleImportReport({ status: 409, data: { message: 'A rule named X already exists' } }), null);
  assert.equal(staleImportReport({ status: 500, data: { stale: true } }), null);
});

test('import outcome and error messages', () => {
  assert.equal(importOutcomeNotice({ read: 3, imported: [{}], skipped: [], duplicates: 0, invalid: 0, replayed: true }), 'This import was already done');
  assert.equal(importOutcomeNotice({ read: 3, imported: [{}, {}, {}], skipped: [], duplicates: 0, invalid: 0 }), '3 rows read · 3 imported · 0 duplicates · 0 invalid');
  assert.equal(importErrorMessage({ status: 413, message: 'x' }), 'This file is too large to import.');
  assert.equal(importErrorMessage({ status: 400, data: { message: 'This is not a valid .xlsx file' } }), 'This is not a valid .xlsx file');
  assert.equal(isImportFile('a.XLSX'), true);
  assert.equal(isImportFile('a.json'), true);
  assert.equal(isImportFile('a.xlsm'), false);
});

test('rename result replaces the rule in the list and selection', () => {
  const rules = [{ id: '1', ruleName: 'A', ruleText: 't', enabled: true }, { id: '2', ruleName: 'B', ruleText: 'u', enabled: true }];
  const form = { ruleName: ' A2 ', ruleText: 't', comment: '', category: '', enabled: true };
  const a = applySavedRule(rules, '1', { id: '1', ruleName: 'A2', ruleText: 't', enabled: true }, form);
  assert.deepEqual(a.rules.map(r => r.ruleName), ['A2', 'B']);
  assert.equal(a.saved.ruleName, 'A2');
  const b = applySavedRule(rules, '1', { ruleText: 't' }, form);
  assert.deepEqual(b.rules.map(r => r.ruleName), ['A2', 'B']);
  const c = applySavedRule(rules, '1', { id: '1', ruleName: 'A2' }, { ...form, ruleText: 'new', enabled: false });
  assert.equal(c.saved.ruleText, 'new');
  assert.equal(c.saved.enabled, false);
});

test('isPagedRun needs a run id and counts', () => {
  assert.equal(isPagedRun(null), false);
  assert.equal(isPagedRun({ runId: 'r1' }), false);
  assert.equal(isPagedRun({ counts: { conclusions: 1, structure: 2 } }), false);
  assert.equal(isPagedRun({ runId: 'r1', counts: { conclusions: 1, structure: 2 } }), true);
});

test('derivedSummary uses separators and singular or plural', () => {
  assert.equal(derivedSummary({ conclusions: 4, structure: 7599 }), '7,603 derived: 4 rule conclusions, 7,599 structure');
  assert.equal(derivedSummary({ conclusions: 1, structure: 0 }), '1 derived: 1 rule conclusion, 0 structure');
  assert.equal(derivedSummary({ conclusions: 2, structure: 3, omittedTrivial: 1 }), '5 derived: 2 rule conclusions, 3 structure (1 trivial same-individual fact hidden)');
  assert.equal(derivedSummary({ conclusions: 2, structure: 3, omittedTrivial: 12 }).endsWith('(12 trivial same-individual facts hidden)'), true);
  assert.equal(formatCount(1234567), '1,234,567');
});

test('page math and range text', () => {
  assert.equal(nextOffset(0, 120), 50);
  assert.equal(nextOffset(100, 120), null);
  assert.equal(nextOffset(50, 100), null);
  assert.equal(previousOffset(0), null);
  assert.equal(previousOffset(50), 0);
  assert.equal(previousOffset(30), 0);
  assert.equal(pageStart(999, 120), 100);
  assert.equal(pageStart(10, 0), 0);
  assert.equal(pageRangeText(50, 50, 7599), 'Showing 51-100 of 7,599');
  assert.equal(pageRangeText(7550, 49, 7599), 'Showing 7,551-7,599 of 7,599');
  assert.equal(pageRangeText(0, 0, 0), 'No results');
});

test('page request path carries paging, kind, query and draft', () => {
  assert.equal(
    axiomsPagePath('p 1', 'run/1', { offset: 50, limit: 50, kind: 'structure', query: '  ' }, false),
    '/api/ontology/p%201/rules/execute/run%2F1/axioms?offset=50&limit=50&kind=structure'
  );
  assert.equal(
    axiomsPagePath('p1', 'r1', { offset: 0, limit: 50, kind: 'conclusions', query: 'Adult x' }, true),
    '/api/ontology/p1/rules/execute/r1/axioms?offset=0&limit=50&kind=conclusions&q=Adult+x&draft=true'
  );
  assert.equal(pageCacheKey('r1', 'conclusions', 'a', 50), 'r1|conclusions|a|50');
});

test('selection survives across pages and toggles', () => {
  const a = { description: 'A' };
  const b = { description: 'B' };
  const c = { description: 'C' };
  let sel = new Map();
  sel = toggleSelection(sel, a);
  sel = addToSelection(sel, [b, c]);
  assert.deepEqual([...sel.keys()], ['A', 'B', 'C']);
  assert.equal(allSelected(sel, [a, b]), true);
  assert.equal(allSelected(sel, [a, { description: 'D' }]), false);
  assert.equal(allSelected(sel, []), false);
  const next = toggleSelection(sel, a);
  assert.equal(next.has('A'), false);
  assert.equal(sel.has('A'), true);
});

test('a 410 maps to the expired message', () => {
  assert.equal(isExpiredRunError({ status: 410 }), true);
  assert.equal(isExpiredRunError({ status: 500 }), false);
  assert.equal(pageErrorMessage({ status: 410 }), EXPIRED_RUN_MESSAGE);
  assert.equal(pageErrorMessage({ message: 'boom' }), 'Could not load this page: boom');
  assert.equal(pageErrorMessage({}), 'Could not load this page. Try again.');
});
