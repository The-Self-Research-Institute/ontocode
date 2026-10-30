import type { InferredAxiom } from './types';

export interface AxiomParts {
  type: string;
  subject: string;
  predicate?: string;
  object?: string;
  formatted: string;
}

export interface AxiomCells {
  subject: string;
  relation: string;
  object: string;
}

const RELATIONS: Record<string, string> = {
  ClassAssertion: 'is a',
  SubClassOf: 'is a kind of',
  EquivalentClasses: 'is equivalent to',
  EquivalentObjectProperties: 'is equivalent to',
  EquivalentDataProperties: 'is equivalent to',
  SubObjectPropertyOf: 'is a kind of',
  SubDataPropertyOf: 'is a kind of',
  SameIndividual: 'is the same as',
  DifferentIndividuals: 'is different from',
  DisjointClasses: 'is disjoint with',
  InverseObjectProperties: 'is the inverse of',
  ObjectPropertyDomain: 'has domain',
  ObjectPropertyRange: 'has range',
  DataPropertyDomain: 'has domain',
  DataPropertyRange: 'has range',
  Declaration: 'is declared as',
};

const localName = (iri?: string | null): string => {
  if (!iri) return '';
  const bare = iri.replace(/^<|>$/g, '');
  return bare.split(/[#/]/).pop() || bare;
};

const humanize = (type: string): string => type.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();

const cleanLiteral = (raw: string): string => raw.replace(/\^\^.*$/, '').replace(/@[A-Za-z-]+$/, '').replace(/^"|"$/g, '');

const ANNOTATION = /AnnotationAssertion\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s+(.+)\)\s*$/i;

export function axiomCells(axiom: InferredAxiom, parsed: AxiomParts): AxiomCells {
  const type = axiom.axiomType || parsed.type;
  const subjectIri = localName(axiom.subjectIri);
  const predicateIri = localName(axiom.predicateIri);
  const objectIri = axiom.objectLiteral ?? localName(axiom.objectIri);

  if (type === 'AnnotationAssertion') {
    const match = (axiom.description || '').match(ANNOTATION);
    return {
      subject: subjectIri || (match ? localName(match[2]) : ''),
      relation: predicateIri || (match ? localName(match[1]) : 'has note'),
      object: objectIri || (match ? cleanLiteral(match[3]) : ''),
    };
  }

  const unmatched = parsed.object === undefined && parsed.predicate === undefined && parsed.formatted === parsed.subject;
  if (unmatched && !subjectIri && !objectIri) {
    return { subject: '', relation: humanize(type), object: parsed.subject };
  }

  const propertyRelation = type === 'ObjectPropertyAssertion' || type === 'DataPropertyAssertion';
  return {
    subject: subjectIri || parsed.subject,
    relation: propertyRelation ? predicateIri || parsed.predicate || 'is related to' : RELATIONS[type] || parsed.predicate || humanize(type),
    object: objectIri || parsed.object || '',
  };
}
