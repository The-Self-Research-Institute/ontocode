import { test } from 'node:test';
import assert from 'node:assert/strict';
import { detectQueryForm } from '../src/queryForm.ts';
import { formatTerm, normalizeRows, shortenIri, shortDatatype } from '../src/bindingFormat.ts';
import { buildExportTable, toCsv, toTsv, buildJsonBindings } from '../src/exportColumns.ts';

test('detects the four read forms', () => {
  assert.equal(detectQueryForm('SELECT * WHERE { ?s ?p ?o }'), 'select');
  assert.equal(detectQueryForm('ask { ?s ?p ?o }'), 'ask');
  assert.equal(detectQueryForm('CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }'), 'construct');
  assert.equal(detectQueryForm('DESCRIBE <http://ex.org/a>'), 'describe');
});

test('ignores comments, prefixes and base', () => {
  const q = '# a comment with SELECT\nBASE <http://ex.org/#>\nPREFIX ex: <http://ex.org/#>\nPREFIX : <http://x/>\n# DELETE\nASK { ?s a ex:A }';
  assert.equal(detectQueryForm(q), 'ask');
  assert.equal(detectQueryForm('PREFIX ex: <http://ex.org/#> # c\nCONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}'), 'construct');
});

test('detects update forms and unknown', () => {
  assert.equal(detectQueryForm('INSERT DATA { <a> <b> <c> }'), 'update');
  assert.equal(detectQueryForm('PREFIX ex: <http://ex.org/>\nDELETE WHERE { ?s ?p ?o }'), 'update');
  assert.equal(detectQueryForm('WITH <g> DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }'), 'update');
  assert.equal(detectQueryForm('CLEAR ALL'), 'update');
  assert.equal(detectQueryForm(''), 'unknown');
  assert.equal(detectQueryForm('hello'), 'unknown');
});

test('shortens only IRIs', () => {
  const prefixes = [{ prefix: 'ex', namespace: 'http://ex.org/' }];
  assert.equal(formatTerm({ type: 'uri', value: 'http://ex.org/Thing' }, prefixes).text, 'ex:Thing');
  assert.equal(formatTerm({ type: 'uri', value: 'http://other.org/a/b#c' }).text, 'c');
  assert.equal(formatTerm({ type: 'uri', value: 'http://other.org/a/b' }).text, 'b');
  assert.equal(shortenIri('http://ex.org/', prefixes), 'http://ex.org/');
  assert.equal(formatTerm({ type: 'literal', value: 'a/b/c' }).text, '"a/b/c"');
  assert.equal(formatTerm({ type: 'literal', value: 'http://x/y' }).text, '"http://x/y"');
});

test('formats literals, languages and bnodes', () => {
  assert.equal(formatTerm({ type: 'literal', value: 'hi', 'xml:lang': 'en' }).text, '"hi"@en');
  const typed = formatTerm({ type: 'literal', value: '1', datatype: 'http://www.w3.org/2001/XMLSchema#int' });
  assert.equal(typed.text, '"1"^^xsd:int');
  const custom = formatTerm({ type: 'literal', value: 'x', datatype: 'http://ex.org/types#Code' });
  assert.equal(custom.text, '"x"^^Code');
  assert.equal(custom.title, 'http://ex.org/types#Code');
  assert.equal(formatTerm({ type: 'literal', value: 'plain', datatype: 'http://www.w3.org/2001/XMLSchema#string' }).text, '"plain"');
  assert.equal(formatTerm({ type: 'literal', value: '' }).text, '""');
  assert.equal(formatTerm({ type: 'bnode', value: 'b0' }).text, '_:b0');
  assert.equal(formatTerm(undefined).text, '-');
  assert.equal(shortDatatype('http://www.w3.org/2001/XMLSchema#date'), 'xsd:date');
});

test('normalizes new and old response shapes', () => {
  const fresh = normalizeRows({
    results: [{ a: 'x' }],
    bindings: [{ a: { type: 'literal', value: 'x', 'xml:lang': 'de' } }]
  });
  assert.deepEqual(fresh, [{ a: { type: 'literal', value: 'x', 'xml:lang': 'de' } }]);
  const old = normalizeRows({ results: [{ a: 'http://ex.org/A', b: 'a/b', c: '' }] });
  assert.equal(old[0].a.type, 'uri');
  assert.equal(old[0].b.type, 'literal');
  assert.equal(old[0].c.value, '');
  assert.deepEqual(normalizeRows({}), []);
  assert.deepEqual(normalizeRows({ results: '[{"a":"1"}]' }), [{ a: { type: 'literal', value: '1' } }]);
});

test('export columns default to the existing ones and gain type columns on request', () => {
  const rows = normalizeRows({
    bindings: [
      { n: { type: 'literal', value: 'a,b', datatype: 'http://www.w3.org/2001/XMLSchema#int' }, l: { type: 'literal', value: 'hi', 'xml:lang': 'en' } },
      { n: { type: 'uri', value: 'http://ex.org/A' } }
    ]
  });
  const plain = buildExportTable(['n', 'l'], rows, false);
  assert.deepEqual(plain.header, ['n', 'l']);
  assert.equal(toCsv(plain), 'n,l\n"a,b",hi\nhttp://ex.org/A,');
  const typed = buildExportTable(['n', 'l'], rows, true);
  assert.deepEqual(typed.header, ['n', 'n_datatype', 'n_lang', 'l', 'l_datatype', 'l_lang']);
  assert.deepEqual(typed.rows[0], ['a,b', 'http://www.w3.org/2001/XMLSchema#int', '', 'hi', '', 'en']);
  assert.equal(toTsv(plain), 'a,b\thi\nhttp://ex.org/A\t');
  const json = buildJsonBindings(['n'], rows, false);
  assert.equal(json[0].n.datatype, undefined);
  assert.equal(buildJsonBindings(['n'], rows, true)[0].n.datatype, 'http://www.w3.org/2001/XMLSchema#int');
});
