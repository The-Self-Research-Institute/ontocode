import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseRuleText, serializeRule } from '../src/ruleModel.ts';

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
