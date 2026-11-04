import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextResultsHeight, MIN_RESULTS_HEIGHT } from '../src/resultsHeight.ts';

test('dragging down makes the panel taller', () => {
  assert.equal(nextResultsHeight(500, 100, 800), 400);
  assert.equal(nextResultsHeight(600, 100, 800), 500);
});

test('dragging up makes the panel shorter', () => {
  assert.equal(nextResultsHeight(400, 100, 800), 300);
});

test('clamps at the minimum', () => {
  assert.equal(nextResultsHeight(150, 100, 800), MIN_RESULTS_HEIGHT);
  assert.equal(nextResultsHeight(0, 100, 800), MIN_RESULTS_HEIGHT);
  assert.equal(nextResultsHeight(150, 100, 800, 120), 120);
});

test('clamps at the container height', () => {
  assert.equal(nextResultsHeight(5000, 100, 800), 800);
});

test('container smaller than the minimum yields the minimum', () => {
  assert.equal(nextResultsHeight(500, 100, 150), MIN_RESULTS_HEIGHT);
});

test('non-finite input fills the container', () => {
  assert.equal(nextResultsHeight(Number.NaN, 100, 800), 800);
});
