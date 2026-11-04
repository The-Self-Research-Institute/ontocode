import { describe, expect, it } from 'vitest';
import { EXPLAIN_TEXT, describeFailure, describeJob, justificationItem, sentenceFor, toExplainFact } from '../src/explainText';

const NS = 'http://example.org/p#';
const TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';

const ax = (over: Record<string, unknown>) => ({
  axiomType: 'ClassAssertion',
  role: 'ASSERTED',
  text: '',
  subjectIri: null,
  predicateIri: null,
  objectIri: null,
  objectLiteral: null,
  literalDatatypeIri: null,
  objectIris: null,
  objectOperator: null,
  entities: [],
  ...over,
}) as any;

describe('building the explain request from an inferred fact', () => {
  const base = { description: '', readable: '' };

  it('maps a class assertion and fills the type predicate', () => {
    const fact = toExplainFact({ ...base, axiomType: 'ClassAssertion', subjectIri: `${NS}ann`, objectIri: `${NS}Person` });
    expect(fact).toMatchObject({ axiomType: 'ClassAssertion', subjectIri: `${NS}ann`, objectIri: `${NS}Person`, predicateIri: TYPE });
  });

  it('maps a sub class fact', () => {
    const fact = toExplainFact({ ...base, axiomType: 'SubClassOf', subjectIri: `${NS}A`, objectIri: `${NS}B`, predicateIri: 'http://www.w3.org/2000/01/rdf-schema#subClassOf' });
    expect(fact).toMatchObject({ axiomType: 'SubClassOf', subjectIri: `${NS}A`, objectIri: `${NS}B` });
  });

  it('maps an object property assertion and needs the property', () => {
    const ok = toExplainFact({ ...base, axiomType: 'ObjectPropertyAssertion', subjectIri: `${NS}a`, predicateIri: `${NS}knows`, objectIri: `${NS}b` });
    expect(ok).toMatchObject({ predicateIri: `${NS}knows`, objectIri: `${NS}b` });
    expect(toExplainFact({ ...base, axiomType: 'ObjectPropertyAssertion', subjectIri: `${NS}a`, objectIri: `${NS}b` })).toBeNull();
  });

  it('maps a data property assertion with literal and datatype', () => {
    const fact = toExplainFact({ ...base, axiomType: 'DataPropertyAssertion', subjectIri: `${NS}a`, predicateIri: `${NS}age`, objectLiteral: '42', literalDatatypeIri: 'http://www.w3.org/2001/XMLSchema#integer' });
    expect(fact).toMatchObject({ objectLiteral: '42', literalDatatypeIri: 'http://www.w3.org/2001/XMLSchema#integer' });
    expect(toExplainFact({ ...base, axiomType: 'DataPropertyAssertion', subjectIri: `${NS}a`, predicateIri: `${NS}age` })).toBeNull();
  });

  it.each(['ObjectPropertyDomain', 'ObjectPropertyRange', 'SubObjectPropertyOf', 'InverseObjectProperties', 'EquivalentClasses', 'SameIndividual'])('maps %s with a subject and an object', type => {
    expect(toExplainFact({ ...base, axiomType: type, subjectIri: `${NS}x`, objectIri: `${NS}y` })).toMatchObject({ axiomType: type, subjectIri: `${NS}x`, objectIri: `${NS}y` });
    expect(toExplainFact({ ...base, axiomType: type, subjectIri: `${NS}x` })).toBeNull();
  });

  it('refuses kinds the server does not support and unusable IRIs', () => {
    expect(toExplainFact({ ...base, axiomType: 'DataPropertyDomain', subjectIri: `${NS}x`, objectIri: `${NS}y` })).toBeNull();
    expect(toExplainFact({ ...base, axiomType: 'ClassAssertion', subjectIri: 'ann', objectIri: `${NS}Person` })).toBeNull();
    expect(toExplainFact({ ...base, axiomType: 'ClassAssertion', subjectIri: 'urn:ontocode:blank:1', objectIri: `${NS}Person` })).toBeNull();
    expect(toExplainFact({ ...base, axiomType: 'ClassAssertion', objectIri: `${NS}Person` })).toBeNull();
  });
});

describe('friendly sentences', () => {
  it('writes each axiom type in plain words', () => {
    expect(sentenceFor(ax({ axiomType: 'ClassAssertion', subjectIri: `${NS}ann`, objectIri: `${NS}Customer` }))).toBe('ann is a Customer');
    expect(sentenceFor(ax({ axiomType: 'SubClassOf', subjectIri: `${NS}Customer`, objectIri: `${NS}Person` }))).toBe('Customer is a kind of Person');
    expect(sentenceFor(ax({ axiomType: 'ObjectPropertyAssertion', subjectIri: `${NS}p1`, predicateIri: `${NS}hasTopping`, objectIri: `${NS}t1` }))).toBe('p1 hasTopping t1');
    expect(sentenceFor(ax({ axiomType: 'DataPropertyAssertion', subjectIri: `${NS}ann`, predicateIri: `${NS}age`, objectLiteral: '42' }))).toBe('ann age 42');
    expect(sentenceFor(ax({ axiomType: 'ObjectPropertyDomain', subjectIri: `${NS}hasTopping`, objectIri: `${NS}Pizza` }))).toBe('hasTopping applies to Pizza');
    expect(sentenceFor(ax({ axiomType: 'ObjectPropertyRange', subjectIri: `${NS}hasTopping`, objectIri: `${NS}Topping` }))).toBe('hasTopping leads to Topping');
    expect(sentenceFor(ax({ axiomType: 'SubObjectPropertyOf', subjectIri: `${NS}hasSon`, objectIri: `${NS}hasChild` }))).toBe('hasSon is a more specific form of hasChild');
    expect(sentenceFor(ax({ axiomType: 'InverseObjectProperties', subjectIri: `${NS}hasChild`, objectIri: `${NS}hasParent` }))).toBe('hasChild is the reverse of hasParent');
    expect(sentenceFor(ax({ axiomType: 'EquivalentClasses', subjectIri: `${NS}Parent`, objectIri: `${NS}Mother`, text: 'EquivalentClasses: Parent, Person and hasChild some Person' }))).toBe('Parent is defined as: EquivalentClasses: Parent, Person and hasChild some Person');
    expect(sentenceFor(ax({ axiomType: 'SameIndividual', subjectIri: `${NS}a`, objectIri: `${NS}b` }))).toBe('a and b are the same individual');
  });

  it('prefers entity labels over local names', () => {
    const text = sentenceFor(ax({
      axiomType: 'ClassAssertion',
      subjectIri: `${NS}c2`,
      objectIri: `${NS}Cust`,
      entities: [{ iri: `${NS}c2`, label: 'Customer Two', kind: 'individual' }, { iri: `${NS}Cust`, label: 'Customer', kind: 'class' }],
    }));
    expect(text).toBe('Customer Two is a Customer');
  });

  it('joins union and intersection operands', () => {
    expect(sentenceFor(ax({ axiomType: 'SubClassOf', subjectIri: `${NS}A`, objectIris: [`${NS}B`, `${NS}C`], objectOperator: 'UNION' }))).toBe('A is a kind of B or C');
    expect(sentenceFor(ax({ axiomType: 'ClassAssertion', subjectIri: `${NS}a`, objectIris: [`${NS}B`, `${NS}C`], objectOperator: 'INTERSECTION' }))).toBe('a is a B and C');
  });

  it('falls back to the server text with full IRIs shortened', () => {
    expect(sentenceFor(ax({ axiomType: 'DisjointClasses', text: `DisjointClasses: <${NS}Cat>, <${NS}Dog>` }))).toBe('DisjointClasses: Cat, Dog');
    expect(sentenceFor(ax({ axiomType: 'ClassAssertion', text: 'ann Type Customer' }))).toBe('ann Type Customer');
  });

  it('marks rules', () => {
    expect(justificationItem(ax({ axiomType: 'Rule', role: 'RULE', text: `Patient(?p) -> Senior(?p)` }))).toBe('rule: Patient(?p) -> Senior(?p)');
  });
});

describe('outcome wording', () => {
  const job = (over: Record<string, unknown>) => ({ jobId: 'j', projectId: 'p', status: 'COMPLETED', ...over }) as any;

  it('lists the reasons and notes a partial answer', () => {
    const shown = describeJob(job({
      explainStatus: 'EXPLAINED',
      partial: true,
      justification: [ax({ subjectIri: `${NS}a`, objectIri: `${NS}B` }), ax({ axiomType: 'SubClassOf', subjectIri: `${NS}B`, objectIri: `${NS}C` })],
    }));
    expect(shown.headline).toBe('This is true because:');
    expect(shown.items).toEqual(['a is a B', 'B is a kind of C']);
    expect(shown.note).toBe(EXPLAIN_TEXT.partial);
  });

  it('has no note for a complete answer', () => {
    expect(describeJob(job({ explainStatus: 'EXPLAINED', justification: [ax({ subjectIri: `${NS}a`, objectIri: `${NS}B` })] })).note).toBeUndefined();
  });

  it('says a stated fact is stated', () => {
    expect(describeJob(job({ explainStatus: 'ASSERTED' })).headline).toBe('This fact is stated directly in the ontology.');
  });

  it('uses the message when the fact is not entailed', () => {
    expect(describeJob(job({ explainStatus: 'NOT_ENTAILED', message: 'The ontology does not entail this.' })).headline).toBe('The ontology does not entail this.');
    expect(describeJob(job({ explainStatus: 'NOT_ENTAILED' })).headline).toBe(EXPLAIN_TEXT.notEntailed);
  });

  it('gives one plain sentence for every other status', () => {
    expect(describeJob(job({ explainStatus: 'TOO_LARGE' })).headline).toBe(EXPLAIN_TEXT.tooLarge);
    expect(describeJob(job({ explainStatus: 'TIMEOUT' })).headline).toBe(EXPLAIN_TEXT.timeout);
    expect(describeJob(job({ explainStatus: 'UNSUPPORTED' })).headline).toBe(EXPLAIN_TEXT.unsupported);
    expect(describeJob(job({ explainStatus: 'CANCELLED' })).headline).toBe(EXPLAIN_TEXT.cancelled);
    expect(describeJob(job({ explainStatus: 'FAILED' })).headline).toBe(EXPLAIN_TEXT.failed);
    expect(describeJob(job({ status: 'FAILED', error: 'boom' })).headline).toBe(EXPLAIN_TEXT.failed);
    expect(describeJob(job({ explainStatus: 'EXPLAINED', justification: [] })).headline).toBe(EXPLAIN_TEXT.failed);
  });

  it('never shows status codes', () => {
    const all = ['TOO_LARGE', 'TIMEOUT', 'UNSUPPORTED', 'CANCELLED', 'FAILED'].map(s => describeJob(job({ explainStatus: s })).headline).join(' ');
    expect(all).not.toMatch(/[A-Z]+_[A-Z]+|\b[A-Z]{6,}\b/);
  });

  it('describes request failures', () => {
    expect(describeFailure({ status: 429, data: { busy: true, retryAfterSeconds: 12 } }).text).toBe('The explanation service is busy right now. Try again in 12 seconds.');
    expect(describeFailure({ status: 429, data: { busy: true, retryAfterSeconds: 1 } }).text).toContain('Try again in 1 second.');
    expect(describeFailure({ status: 429 }).text).toBe(EXPLAIN_TEXT.busy);
    expect(describeFailure({ status: 503, data: { explainStatus: 'UNAVAILABLE' } }).kind).toBe('unavailable');
    expect(describeFailure({ status: 400, data: { explainStatus: 'UNSUPPORTED' } }).text).toBe(EXPLAIN_TEXT.unsupported);
    expect(describeFailure(new Error('x')).text).toBe(EXPLAIN_TEXT.failed);
    expect(describeFailure(null).kind).toBe('failed');
  });
});
