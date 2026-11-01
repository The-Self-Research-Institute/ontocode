import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { SWRL_BUILTINS, SWRL_BUILTINS_QUICK, BUILT_IN_PREFIXES, allBuiltIns } from '../src/builtinCatalog.ts';

const engine = JSON.parse(fs.readFileSync(new URL('./engine-builtins.json', import.meta.url), 'utf8'));
const listed = allBuiltIns();

test('every function the rule engine registers is in the help list', () => {
  const missing = [];
  for (const [prefix, names] of Object.entries(engine)) {
    for (const name of names) {
      if (!listed.some(item => item.fullName === `${prefix}:${name}`)) missing.push(`${prefix}:${name}`);
    }
  }
  assert.deepEqual(missing, []);
});

test('the help list has no function the rule engine does not register', () => {
  const extra = listed.filter(item => {
    const [prefix, name] = item.fullName.split(':');
    return !(engine[prefix] || []).includes(name);
  }).map(item => item.fullName);
  assert.deepEqual(extra, []);
});

test('every entry has a unique full name, a matching signature, a description and a known prefix', () => {
  const seen = new Set();
  for (const item of listed) {
    assert.ok(!seen.has(item.fullName), `duplicate ${item.fullName}`);
    seen.add(item.fullName);
    assert.ok(item.signature.startsWith(item.fullName + '('), item.signature);
    assert.ok(item.description.length > 5, item.fullName);
    assert.ok(BUILT_IN_PREFIXES.has(item.fullName.split(':')[0]), item.fullName);
  }
});

test('functions marked unavailable or limited always carry a note saying why', () => {
  for (const item of listed) {
    if (item.status === 'unavailable' || item.status === 'caution') {
      assert.ok(item.note && item.note.length > 5, `${item.fullName} has no note`);
    }
  }
});

test('the prefixes the editor treats as built-in are exactly the libraries the engine registers', () => {
  assert.deepEqual([...BUILT_IN_PREFIXES].sort(), Object.keys(engine).sort());
  assert.deepEqual([...new Set(SWRL_BUILTINS.map(category => category.prefix))].sort(), Object.keys(engine).sort());
});

test('the quick buttons only offer functions that work', () => {
  for (const group of SWRL_BUILTINS_QUICK) {
    for (const full of group.items) {
      const found = listed.find(item => item.fullName === full);
      assert.ok(found, full);
      assert.notEqual(found.status, 'unavailable', full);
    }
  }
});

test('functions the old list described wrongly are no longer offered', () => {
  for (const gone of ['swrlm:exp', 'swrlm:log10', 'swrlm:sin', 'swrlm:cos', 'swrlm:tan', 'swrlm:asin', 'swrlm:acos', 'swrlm:atan', 'swrlm:toDegrees', 'swrlm:toRadians']) {
    assert.ok(!listed.some(item => item.fullName === gone), gone);
  }
});

test('functions that were once refused or wrong are listed as working', () => {
  for (const full of [
    'swrlb:subtractDateTimesYieldingDayTimeDuration', 'sqwrl:countDistinct', 'swrlb:date', 'swrlb:time', 'swrlb:dateTime', 'swrlb:integerDivide', 'swrlb:round',
    'swrlb:divide', 'swrlb:subtractDates', 'swrlb:subtractTimes', 'swrlb:addYearMonthDurations', 'swrlb:subtractYearMonthDurations', 'swrlb:subtractDayTimeDurations',
    'swrlb:multiplyYearMonthDuration', 'swrlb:divideYearMonthDuration', 'swrlb:multiplyDayTimeDuration', 'swrlb:divideDayTimeDuration',
    'swrlb:addDayTimeDurationToTime', 'swrlb:subtractDayTimeDurationFromTime', 'swrlb:addYearMonthDurationToDateTime', 'swrlb:subtractYearMonthDurationFromDateTime',
    'swrlb:substring', 'swrlb:substringBefore', 'swrlb:translate', 'swrlb:matches', 'swrlb:anyURI', 'swrlb:resolveURI', 'swrlm:eval', 'swrlm:log',
    'temporal:durationEqualTo', 'temporal:durationGreaterThan', 'temporal:durationLessThan', 'temporal:notDurationEqualTo', 'sqwrl:min', 'sqwrl:max',
    'sqwrl:contains', 'sqwrl:notContains',
  ]) {
    const found = listed.find(item => item.fullName === full);
    assert.ok(found, full);
    assert.equal(found.status, 'ok', full);
  }
});

test('only the list functions are still marked unavailable, each with a note saying why', () => {
  const unavailable = listed.filter(item => item.status === 'unavailable').map(item => item.fullName).sort();
  assert.deepEqual(unavailable, ['length', 'first', 'rest', 'empty', 'member', 'sublist', 'listConcat', 'listIntersection', 'listSubtraction'].map(name => `swrlb:${name}`).sort());
  for (const item of listed.filter(entry => entry.status === 'unavailable')) {
    assert.ok(item.note && item.note.includes('does not implement'), item.fullName);
  }
});

test('functions whose corrected behaviour has a real limit keep a note', () => {
  for (const full of ['swrlb:divide', 'swrlb:round', 'swrlb:addYearMonthDurationToDateTime', 'swrlb:addDayTimeDurationToTime', 'swrlb:date']) {
    const found = listed.find(item => item.fullName === full);
    assert.ok(found.note && found.note.length > 10, full);
  }
});
