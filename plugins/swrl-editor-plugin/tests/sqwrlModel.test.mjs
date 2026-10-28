import test from 'node:test';
import assert from 'node:assert/strict';
import {
  sqwrlMeta, filterRows, sortRows, nextSort, sortArrow, pageCount, clampPage, pageSlice, showingText,
  resultsLine, truncationNotice, csvCell, buildCsv, buildTsv, compareCells, SQWRL_PAGE_SIZES,
} from '../src/sqwrlModel.ts';

const COLS = ['p', 'age'];
const ROWS = [
  { p: 'Ann', age: '30' },
  { p: 'bob', age: '4' },
  { p: 'Cy', age: '100' },
  { p: 'Dee', age: '30' },
];

test('legacy response without the new fields still has sane meta', () => {
  const meta = sqwrlMeta({ rows: ROWS, rowCount: 4 });
  assert.deepEqual(meta, { returned: 4, total: 4, exact: true, truncated: false, bytesCapped: false, rowLimit: 4 });
  assert.equal(sqwrlMeta({}).returned, 0);
});

test('meta reads the new fields', () => {
  const meta = sqwrlMeta({ rows: ROWS, totalRows: 5000, totalRowsExact: false, truncated: true, rowLimit: 1000, bytesCapped: true });
  assert.equal(meta.total, 5000);
  assert.equal(meta.exact, false);
  assert.equal(meta.truncated, true);
  assert.equal(meta.bytesCapped, true);
  assert.equal(meta.rowLimit, 1000);
});

test('filter is case-insensitive over every column', () => {
  assert.equal(filterRows(ROWS, COLS, '').length, 4);
  assert.deepEqual(filterRows(ROWS, COLS, 'BOB').map((r) => r.p), ['bob']);
  assert.deepEqual(filterRows(ROWS, COLS, '30').map((r) => r.p), ['Ann', 'Dee']);
  assert.equal(filterRows(ROWS, COLS, 'zzz').length, 0);
  assert.equal(filterRows([{ p: null }], COLS, 'null').length, 0);
});

test('sort is numeric aware, case-insensitive and stable', () => {
  assert.deepEqual(sortRows(ROWS, { column: 'age', direction: 'asc' }).map((r) => r.p), ['bob', 'Ann', 'Dee', 'Cy']);
  assert.deepEqual(sortRows(ROWS, { column: 'age', direction: 'desc' }).map((r) => r.p), ['Cy', 'Ann', 'Dee', 'bob']);
  assert.deepEqual(sortRows(ROWS, { column: 'p', direction: 'asc' }).map((r) => r.p), ['Ann', 'bob', 'Cy', 'Dee']);
  assert.equal(sortRows(ROWS, null), ROWS);
  assert.ok(compareCells('9', '10') < 0);
  assert.ok(compareCells('abc', '5') > 0);
});

test('sort toggles asc, desc, off and shows an arrow', () => {
  let s = nextSort(null, 'age');
  assert.deepEqual(s, { column: 'age', direction: 'asc' });
  assert.equal(sortArrow(s, 'age'), '▲');
  s = nextSort(s, 'age');
  assert.equal(sortArrow(s, 'age'), '▼');
  assert.equal(sortArrow(s, 'p'), '');
  assert.equal(nextSort(s, 'age'), null);
  assert.deepEqual(nextSort(s, 'p'), { column: 'p', direction: 'asc' });
});

test('page math', () => {
  assert.deepEqual(SQWRL_PAGE_SIZES, [25, 50, 100, 250]);
  assert.equal(pageCount(312, 50), 7);
  assert.equal(pageCount(0, 50), 1);
  assert.equal(clampPage(99, 312, 50), 6);
  assert.equal(clampPage(-3, 312, 50), 0);
  const rows = Array.from({ length: 120 }, (_, i) => i);
  assert.equal(pageSlice(rows, 2, 50).length, 20);
  assert.equal(pageSlice(rows, 0, 50)[0], 0);
  assert.equal(pageSlice(rows, 9, 50)[0], 100);
});

test('showing text', () => {
  assert.equal(showingText(312, 1000, 1, 50), 'Showing 51-100 of 312 (filtered from 1,000)');
  assert.equal(showingText(120, 120, 2, 50), 'Showing 101-120 of 120');
  assert.equal(showingText(0, 40, 0, 50), 'Showing 0 of 0 (filtered from 40)');
});

test('results line and truncation notice', () => {
  const capped = sqwrlMeta({ rows: Array.from({ length: 1000 }, () => ({})), totalRows: 5000, truncated: true });
  assert.equal(resultsLine(capped, 12), '1,000 of 5,000 results in 12ms');
  assert.equal(truncationNotice(capped), 'Showing the first 1,000 of 5,000 results. Narrow the query, add a filter in the query, or use sqwrl:limit.');
  const open = sqwrlMeta({ rows: Array.from({ length: 1000 }, () => ({})), totalRows: 100000, totalRowsExact: false, truncated: true });
  assert.equal(truncationNotice(open), 'Showing the first 1,000 of more than 100,000 results. Narrow the query, add a filter in the query, or use sqwrl:limit.');
  assert.equal(resultsLine(open, 3), '1,000 of more than 100,000 results in 3ms');
  const full = sqwrlMeta({ rows: ROWS });
  assert.equal(resultsLine(full, 7), '4 results in 7ms');
  assert.equal(truncationNotice(full), null);
  assert.ok(truncationNotice(sqwrlMeta({ rows: ROWS, totalRows: 9, truncated: true, bytesCapped: true })).includes('cut'));
});

test('csv cells are quoted and formula safe', () => {
  assert.equal(csvCell('plain'), 'plain');
  assert.equal(csvCell('a,b'), '"a,b"');
  assert.equal(csvCell('say "hi"'), '"say ""hi"""');
  assert.equal(csvCell('=SUM(A1)'), "'=SUM(A1)");
  assert.equal(csvCell('+1'), "'+1");
  assert.equal(csvCell('-2'), "'-2");
  assert.equal(csvCell('@cmd'), "'@cmd");
  assert.equal(csvCell(null), '');
  assert.equal(csvCell('line\nbreak'), '"line\nbreak"');
});

test('csv and tsv bodies', () => {
  const csv = buildCsv([{ p: 'Ann', age: '=1+1' }], COLS);
  assert.equal(csv, "p,age\r\nAnn,'=1+1\r\n");
  const tsv = buildTsv([{ p: 'A\tB', age: 3 }], COLS);
  assert.equal(tsv, 'p\tage\nA B\t3\n');
});

import { sqwrlRowsPath, localPage, pageKey } from '../src/sqwrlModel.ts';

test('rows path carries offset, limit, filter, sort and draft', () => {
  assert.equal(sqwrlRowsPath('p 1', 'r1', { offset: 50, limit: 50, filter: ' ab ', sort: { column: 'age', direction: 'desc' } }, true),
    '/api/swrl/p%201/sqwrl/runs/r1/rows?offset=50&limit=50&filter=ab&sort=age&dir=desc&draft=true');
  assert.equal(sqwrlRowsPath('p', 'r', { offset: 0, limit: 1, filter: '', sort: null, exportAll: true }, false),
    '/api/swrl/p/sqwrl/runs/r/rows?export=true');
});

test('local page filters, sorts and slices', () => {
  const out = localPage(ROWS, COLS, '0', { column: 'age', direction: 'asc' }, 0, 1);
  assert.equal(out.filtered, 3);
  assert.equal(out.rows.length, 1);
  assert.equal(out.rows[0].p, 'Ann');
});

test('page key changes with every input', () => {
  const a = pageKey('r', '', null, 0, 50);
  assert.notEqual(a, pageKey('r', 'x', null, 0, 50));
  assert.notEqual(a, pageKey('r', '', { column: 'p', direction: 'asc' }, 0, 50));
  assert.notEqual(a, pageKey('r', '', null, 1, 50));
  assert.notEqual(a, pageKey('r', '', null, 0, 100));
});
