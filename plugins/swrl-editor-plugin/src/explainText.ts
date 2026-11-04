import type { ExplainAxiom, ExplainFact, ExplainJobResponse, InferredAxiom } from './types';

export const EXPLAIN_TEXT = {
  button: 'Explain in detail',
  working: 'Working it out… (up to 30 seconds)',
  cancel: 'Cancel',
  cancelled: 'The explanation was cancelled.',
  explainedHeading: 'This is true because:',
  partial: 'This may not be the shortest explanation (time ran out).',
  asserted: 'This fact is stated directly in the ontology.',
  notEntailed: 'The reasoner does not draw this conclusion from the ontology as it is now. Run the rules again to refresh the results.',
  tooLarge: 'This ontology is too large to explain this fact in detail.',
  timeout: 'Working this out took too long, so it was stopped. Try again, or try a simpler fact.',
  unsupported: 'Explaining this kind of fact in detail is not supported yet.',
  failed: 'The explanation could not be worked out. Please try again.',
  busy: 'The explanation service is busy right now.',
  ruleLabel: 'rule',
} as const;

export type ExplainOutcomeKind = 'explained' | 'asserted' | 'info' | 'warn';

export interface ExplainDisplay {
  kind: ExplainOutcomeKind;
  headline: string;
  items: string[];
  note?: string;
}

export interface ExplainFailure {
  kind: 'busy' | 'unavailable' | 'unsupported' | 'failed';
  text: string;
}

const RDF_TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';

const SUPPORTED_TYPES = new Set([
  'ClassAssertion',
  'SubClassOf',
  'ObjectPropertyAssertion',
  'DataPropertyAssertion',
  'ObjectPropertyDomain',
  'ObjectPropertyRange',
  'SubObjectPropertyOf',
  'InverseObjectProperties',
  'EquivalentClasses',
  'SameIndividual',
]);

const NEEDS_OBJECT = new Set([
  'ClassAssertion',
  'SubClassOf',
  'ObjectPropertyDomain',
  'ObjectPropertyRange',
  'SubObjectPropertyOf',
  'InverseObjectProperties',
  'EquivalentClasses',
  'SameIndividual',
]);

const usable = (iri?: string | null): iri is string => !!iri && /^[A-Za-z][A-Za-z0-9+.-]*:\S+$/.test(iri) && !iri.startsWith('urn:ontocode:blank:');

export const localName = (raw: string | null | undefined): string => {
  if (!raw) return '';
  const trimmed = raw.replace(/^<|>$/g, '');
  const cut = Math.max(trimmed.lastIndexOf('#'), trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
  return cut >= 0 && cut < trimmed.length - 1 ? trimmed.slice(cut + 1) : trimmed;
};

export const toExplainFact = (axiom: InferredAxiom): ExplainFact | null => {
  const type = axiom.axiomType;
  if (!SUPPORTED_TYPES.has(type) || !usable(axiom.subjectIri)) return null;
  const fact: ExplainFact = { axiomType: type as ExplainFact['axiomType'], subjectIri: axiom.subjectIri };
  if (NEEDS_OBJECT.has(type)) {
    if (!usable(axiom.objectIri)) return null;
    fact.objectIri = axiom.objectIri;
    fact.predicateIri = type === 'ClassAssertion' ? axiom.predicateIri || RDF_TYPE : axiom.predicateIri || null;
    return fact;
  }
  if (!usable(axiom.predicateIri)) return null;
  fact.predicateIri = axiom.predicateIri;
  if (type === 'ObjectPropertyAssertion') {
    if (!usable(axiom.objectIri)) return null;
    fact.objectIri = axiom.objectIri;
    return fact;
  }
  if (axiom.objectLiteral == null || axiom.objectLiteral === '') return null;
  fact.objectLiteral = axiom.objectLiteral;
  fact.literalDatatypeIri = axiom.literalDatatypeIri || null;
  return fact;
};

const shortenText = (text: string): string => text.replace(/<([^<>\s]+)>/g, (_m, iri: string) => localName(iri));

const nameFor = (iri: string | null | undefined, labels: Map<string, string>): string => {
  if (!iri) return '';
  return labels.get(iri) || localName(iri);
};

export const sentenceFor = (axiom: ExplainAxiom): string => {
  const labels = new Map<string, string>();
  (axiom.entities || []).forEach(e => {
    if (e.iri && e.label) labels.set(e.iri, e.label);
  });
  const s = nameFor(axiom.subjectIri, labels);
  const p = nameFor(axiom.predicateIri, labels);
  const o = nameFor(axiom.objectIri, labels);
  const operands = (axiom.objectIris || []).map(iri => nameFor(iri, labels));
  const target = operands.length > 0 ? operands.join(axiom.objectOperator === 'UNION' ? ' or ' : ' and ') : o;
  const fallback = shortenText(axiom.text || '');
  switch (axiom.axiomType) {
    case 'ClassAssertion':
      return s && target ? `${s} is a ${target}` : fallback;
    case 'SubClassOf':
      return s && target ? `${s} is a kind of ${target}` : fallback;
    case 'ObjectPropertyAssertion':
      return s && p && o ? `${s} ${p} ${o}` : fallback;
    case 'DataPropertyAssertion':
      return s && p && axiom.objectLiteral != null ? `${s} ${p} ${axiom.objectLiteral}` : fallback;
    case 'ObjectPropertyDomain':
      return s && o ? `${s} applies to ${o}` : fallback;
    case 'ObjectPropertyRange':
      return s && o ? `${s} leads to ${o}` : fallback;
    case 'SubObjectPropertyOf':
      return s && o ? `${s} is a more specific form of ${o}` : fallback;
    case 'InverseObjectProperties':
      return s && o ? `${s} is the reverse of ${o}` : fallback;
    case 'EquivalentClasses':
      return s && fallback ? `${s} is defined as: ${fallback}` : fallback;
    case 'SameIndividual':
      return s && o ? `${s} and ${o} are the same individual` : fallback;
    default:
      return fallback;
  }
};

export const justificationItem = (axiom: ExplainAxiom): string => {
  if (axiom.role === 'RULE') return `${EXPLAIN_TEXT.ruleLabel}: ${shortenText(axiom.text || '') || sentenceFor(axiom)}`;
  return sentenceFor(axiom);
};

export const describeJob = (job: ExplainJobResponse): ExplainDisplay => {
  const status = job.explainStatus || (job.status === 'FAILED' ? 'FAILED' : undefined);
  switch (status) {
    case 'EXPLAINED': {
      const items = (job.justification || []).map(justificationItem).filter(Boolean);
      if (items.length === 0) return { kind: 'warn', headline: EXPLAIN_TEXT.failed, items: [] };
      return { kind: 'explained', headline: EXPLAIN_TEXT.explainedHeading, items, note: job.partial ? EXPLAIN_TEXT.partial : undefined };
    }
    case 'ASSERTED':
      return { kind: 'asserted', headline: EXPLAIN_TEXT.asserted, items: [] };
    case 'NOT_ENTAILED':
      return { kind: 'info', headline: job.message || EXPLAIN_TEXT.notEntailed, items: [] };
    case 'TOO_LARGE':
      return { kind: 'warn', headline: EXPLAIN_TEXT.tooLarge, items: [] };
    case 'TIMEOUT':
      return { kind: 'warn', headline: EXPLAIN_TEXT.timeout, items: [] };
    case 'UNSUPPORTED':
      return { kind: 'info', headline: EXPLAIN_TEXT.unsupported, items: [] };
    case 'CANCELLED':
      return { kind: 'info', headline: EXPLAIN_TEXT.cancelled, items: [] };
    default:
      return { kind: 'warn', headline: EXPLAIN_TEXT.failed, items: [] };
  }
};

export const describeFailure = (e: unknown): ExplainFailure => {
  const err = e as { status?: number; data?: { busy?: boolean; retryAfterSeconds?: number; explainStatus?: string } } | null;
  const status = err?.status;
  const data = err?.data;
  if (status === 429 || data?.busy) {
    const seconds = Math.ceil(Number(data?.retryAfterSeconds));
    const wait = Number.isFinite(seconds) && seconds > 0 ? ` Try again in ${seconds} ${seconds === 1 ? 'second' : 'seconds'}.` : '';
    return { kind: 'busy', text: `${EXPLAIN_TEXT.busy}${wait}` };
  }
  if (status === 503 || data?.explainStatus === 'UNAVAILABLE') return { kind: 'unavailable', text: '' };
  if (status === 400 || data?.explainStatus === 'UNSUPPORTED') return { kind: 'unsupported', text: EXPLAIN_TEXT.unsupported };
  return { kind: 'failed', text: EXPLAIN_TEXT.failed };
};
