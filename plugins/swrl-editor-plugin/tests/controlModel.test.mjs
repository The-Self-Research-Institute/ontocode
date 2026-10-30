import test from 'node:test';
import assert from 'node:assert/strict';
import { runButtonLabel, scopeLine, factsFoundText, addButtonLabel, checkSummaryText, durationText, runTailText, runTone, addDisabledReason } from '../src/controlModel.ts';

test('the run button and the scope line say what will happen', () => {
  assert.equal(runButtonLabel(0), 'Run rules');
  assert.equal(runButtonLabel(3), 'Run 3 selected rules');
  assert.equal(runButtonLabel(1), 'Run 1 selected rule');
  assert.deepEqual(scopeLine(7, 0), { head: '7 rules will run', note: '(all enabled rules)' });
  assert.deepEqual(scopeLine(7, 2), { head: '2 rules will run', note: '(selected rules)' });
  assert.deepEqual(scopeLine(0, 0), { head: 'No rules to run', note: '' });
});

test('the results line and the add button use plain numbers', () => {
  assert.equal(factsFoundText(118), '118 new facts found');
  assert.equal(factsFoundText(1), '1 new fact found');
  assert.equal(addButtonLabel(118), 'Add 118 facts to the ontology…');
  assert.equal(addButtonLabel(1), 'Add 1 fact to the ontology…');
});

test('check summary lists the counts that will be sent', () => {
  assert.equal(
    checkSummaryText({ rulesExported: 8, classDeclarations: 57, individualDeclarations: 45, objectPropertyDeclarations: 1, dataPropertyDeclarations: 1, totalAxioms: 378, elapsedMs: 1 }),
    'Sends 8 rules, 57 classes, 45 individuals, 378 axioms'
  );
});

test('timing reads in milliseconds or seconds', () => {
  assert.equal(durationText(242), '242 ms');
  assert.equal(durationText(4800), '4.8 s');
  assert.equal(runTailText(7, 4800, 'just now'), 'in 4.8 s, from 7 rules, just now');
  assert.equal(runTailText(1, 10, ''), 'in 10 ms, from 1 rule');
});

test('run tone and the reason Add is off', () => {
  assert.equal(runTone(true, 0), 'ok');
  assert.equal(runTone(true, 2), 'warn');
  assert.equal(runTone(false, 0), 'fail');
  assert.equal(addDisabledReason({ hasResult: true, success: true, test: false, total: 0, alreadyAdded: false }), 'This run found no new facts to add.');
  assert.equal(addDisabledReason({ hasResult: true, success: true, test: false, total: 4, alreadyAdded: true }), 'These facts are already in the ontology.');
  assert.equal(addDisabledReason({ hasResult: true, success: true, test: false, total: 4, alreadyAdded: false }), '');
});
