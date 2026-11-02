import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  clampPage, compareCells, exportNote, filterRows, formatNumber, localPage, nextSort, pageCount, pageKey,
  resultsLine, resultsMeta, rowsPath, showingText, sortArrow, sortRows, truncationNotice
} from '../src/resultsPaging.ts';

const row = (name: string, n: string) => ({ name: { type: 'literal', value: name }, n: { type: 'literal', value: n } });

test('formats numbers with thousands separators', () => {
  assert.equal(formatNumber(1000), '1,000');
  assert.equal(formatNumber(-5), '0');
});

test('reads the paging fields and falls back for an older server', () => {
  const run = resultsMeta({ runId: 'r1', totalRows: 5000, totalRowsExact: false, truncated: true, rowLimit: 1000, bytesCapped: true, storedRows: 1000 }, 100);
  assert.deepEqual(run, { runId: 'r1', totalRows: 5000, totalRowsExact: false, truncated: true, rowLimit: 1000, bytesCapped: true, storedRows: 1000 });
  const legacy = resultsMeta({ truncated: true }, 42);
  assert.equal(legacy.runId, null);
  assert.equal(legacy.storedRows, 42);
  assert.equal(legacy.totalRows, 42);
  assert.equal(resultsMeta({}, 3).truncated, false);
});

test('writes the truncation notice in plain words', () => {
  const base = { runId: 'r', totalRows: 5000, totalRowsExact: false, truncated: true, rowLimit: 1000, bytesCapped: false, storedRows: 1000 };
  assert.equal(truncationNotice(base), 'Showing the first 1,000 of at least 5,000 rows. Narrow the query or add LIMIT.');
  assert.equal(truncationNotice({ ...base, totalRowsExact: true }), 'Showing the first 1,000 of 5,000 rows. Narrow the query or add LIMIT.');
  assert.match(truncationNotice({ ...base, bytesCapped: true })!, /also cut to keep its size manageable/);
  assert.equal(truncationNotice({ ...base, totalRows: 1000 }), 'Showing the first 1,000 rows; the rest were cut off. Narrow the query or add LIMIT.');
  assert.equal(truncationNotice({ ...base, truncated: false }), null);
});

test('writes the results line', () => {
  const base = { runId: 'r', totalRows: 312, totalRowsExact: true, truncated: false, rowLimit: 1000, bytesCapped: false, storedRows: 312 };
  assert.equal(resultsLine(base), '312 rows');
  assert.equal(resultsLine({ ...base, storedRows: 1, totalRows: 1 }), '1 row');
  assert.equal(resultsLine({ ...base, truncated: true, storedRows: 1000, totalRows: 5000, totalRowsExact: false }), '1,000 of at least 5,000 rows');
});

test('shows which rows are on the page and what the filter hid', () => {
  assert.equal(showingText(312, 312, 0, 50), 'Showing 1-50 of 312');
  assert.equal(showingText(312, 1000, 1, 50), 'Showing 51-100 of 312 (filtered from 1,000)');
  assert.equal(showingText(312, 312, 6, 50), 'Showing 301-312 of 312');
  assert.equal(showingText(0, 1000, 0, 50), 'Showing 0 of 0 (filtered from 1,000)');
  assert.equal(showingText(0, 0, 0, 50), 'Showing 0 of 0');
});

test('counts and clamps pages', () => {
  assert.equal(pageCount(312, 50), 7);
  assert.equal(pageCount(0, 50), 1);
  assert.equal(clampPage(99, 312, 50), 6);
  assert.equal(clampPage(-3, 312, 50), 0);
});

test('compares numbers as numbers and text without case', () => {
  assert.ok(compareCells('9', '10') < 0);
  assert.ok(compareCells('100', 'abc') < 0);
  assert.ok(compareCells('apple', 'Banana') < 0);
});

test('filters across all columns and sorts numerically and stably', () => {
  const rows = [row('Banana', '10'), row('apple', '9'), row('Cherry', '100'), row('date', '2')];
  assert.deepEqual(filterRows(rows, ['name', 'n'], ' AN ').map((r) => r.name.value), ['Banana']);
  assert.deepEqual(filterRows(rows, ['name', 'n'], '').length, 4);
  assert.deepEqual(sortRows(rows, { column: 'n', direction: 'asc' }).map((r) => r.n.value), ['2', '9', '10', '100']);
  assert.deepEqual(sortRows(rows, { column: 'n', direction: 'desc' }).map((r) => r.n.value), ['100', '10', '9', '2']);
  assert.equal(sortRows(rows, null), rows);
});

test('pages the rows on the client when the server sent no run', () => {
  const rows = Array.from({ length: 120 }, (_, i) => row(`item${i}`, String(i)));
  const second = localPage(rows, ['name', 'n'], '', null, 1, 50);
  assert.equal(second.rows.length, 50);
  assert.equal(second.rows[0].name.value, 'item50');
  assert.equal(second.filtered, 120);
  const filtered = localPage(rows, ['name', 'n'], 'item11', null, 0, 50);
  assert.equal(filtered.filtered, 11);
  const past = localPage(rows, ['name', 'n'], '', null, 99, 50);
  assert.equal(past.rows[0].name.value, 'item100');
});

test('cycles the sort and draws the arrow', () => {
  assert.deepEqual(nextSort(null, 'a'), { column: 'a', direction: 'asc' });
  assert.deepEqual(nextSort({ column: 'a', direction: 'asc' }, 'a'), { column: 'a', direction: 'desc' });
  assert.equal(nextSort({ column: 'a', direction: 'desc' }, 'a'), null);
  assert.deepEqual(nextSort({ column: 'a', direction: 'asc' }, 'b'), { column: 'b', direction: 'asc' });
  assert.equal(sortArrow({ column: 'a', direction: 'asc' }, 'a'), '▲');
  assert.equal(sortArrow({ column: 'a', direction: 'desc' }, 'a'), '▼');
  assert.equal(sortArrow(null, 'a'), '');
});

test('builds the rows request', () => {
  assert.equal(
    rowsPath('proj 1', 'run/1', { offset: 100, limit: 50, filter: ' term ', sort: { column: 'n', direction: 'desc' } }),
    '/api/sparql/proj%201/runs/run%2F1/rows?offset=100&limit=50&filter=term&sort=n&dir=desc'
  );
  assert.equal(
    rowsPath('p', 'r', { offset: 0, limit: 1, filter: '', sort: null, exportAll: true }),
    '/api/sparql/p/runs/r/rows?export=true'
  );
});

test('keys a page by run, filter, sort, page and size', () => {
  assert.equal(pageKey('r', ' x ', { column: 'n', direction: 'asc' }, 2, 50), 'r|x|n:asc|2|50');
  assert.notEqual(pageKey('r', '', null, 0, 50), pageKey('r', '', null, 0, 100));
});

test('says when an export was cut', () => {
  assert.equal(exportNote(25000, 25000), null);
  assert.equal(exportNote(25000, 90000), 'Downloaded the first 25,000 of 90,000 matching rows. Add a filter to get the rest.');
});
