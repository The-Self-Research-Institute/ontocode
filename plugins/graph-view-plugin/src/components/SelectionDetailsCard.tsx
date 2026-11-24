import React, { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import { OntologyNode, OntologyEdge } from '../types';
import { useEntityDetails, entityKindOf } from '../hooks/useEntityDetails';
import { safeHref } from '../utils/safeUrl';

interface SelectionDetailsCardProps {
  node: OntologyNode;
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  projectId: string;
  draft?: boolean;
  isExternalNode?: (node: OntologyNode) => boolean;
  onSelectNode: (node: OntologyNode) => void;
}

interface Ref {
  iri: string;
  label: string;
}

interface AnnotationEntry {
  key: string;
  name: string;
  values: string[];
}

const RDFS_LABEL = 'http://www.w3.org/2000/01/rdf-schema#label';
const OWL_THING = 'http://www.w3.org/2002/07/owl#Thing';

const ANNOTATION_NAMES: Record<string, string> = {
  IAO_0000115: 'Definition',
  IAO_0000116: 'Editor note',
  IAO_0000117: 'Term editor',
  IAO_0000118: 'Alternative term',
  IAO_0000119: 'Definition source',
  IAO_0000112: 'Example of usage',
  IAO_0000114: 'Curation status',
  IAO_0000231: 'Obsolescence reason',
  comment: 'Comment',
  isDefinedBy: 'Defined by',
  seeAlso: 'See also',
  deprecated: 'Deprecated'
};

const PLACEHOLDER_LABELS = new Set(['Thing', 'owl:Thing', 'Literal', 'rdfs:Literal']);

function localName(iri: string): string {
  if (!iri) return '';
  const cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
  return cut >= 0 ? iri.slice(cut + 1) : iri;
}

function isIri(value?: string | null): value is string {
  return !!value && /^(https?|urn):/i.test(value);
}

const BUILT_IN_NAMESPACES = [
  'http://www.w3.org/2002/07/owl#',
  'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
  'http://www.w3.org/2000/01/rdf-schema#'
];

function isBuiltIn(iri: string): boolean {
  return BUILT_IN_NAMESPACES.some(ns => iri.startsWith(ns));
}

const VALUES_SHOWN_ELSEWHERE = new Set([
  'http://www.w3.org/2000/01/rdf-schema#label',
  'http://www.w3.org/2000/01/rdf-schema#isDefinedBy',
  'http://www.w3.org/2000/01/rdf-schema#comment',
  'http://www.w3.org/2000/01/rdf-schema#subClassOf',
  'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
  'http://www.w3.org/2002/07/owl#sameAs',
  'http://www.w3.org/2002/07/owl#differentFrom'
]);

function toStrings(value: unknown): string[] {
  if (value == null) return [];
  if (Array.isArray(value)) return value.flatMap(toStrings);
  if (typeof value === 'object') {
    const v = (value as any).value ?? (value as any).literal ?? (value as any).label;
    return v != null ? toStrings(v) : [];
  }
  const s = String(value).trim();
  return s ? [s] : [];
}

function readAnnotations(raw: unknown): AnnotationEntry[] {
  if (!raw) return [];
  const out: AnnotationEntry[] = [];
  const push = (key: string, value: unknown) => {
    const values = toStrings(value);
    if (!key || values.length === 0) return;
    const short = localName(key);
    out.push({ key, name: ANNOTATION_NAMES[short] || short, values });
  };
  if (Array.isArray(raw)) {
    raw.forEach((a: any) => push(a?.property || a?.propertyIri || a?.key || '', a?.value ?? a?.values));
  } else if (typeof raw === 'object') {
    Object.entries(raw as Record<string, unknown>).forEach(([k, v]) => push(k, v));
  }
  return out;
}

function takeAnnotation(list: AnnotationEntry[], test: (short: string) => boolean): string[] {
  const idx = list.findIndex(a => test(localName(a.key)));
  if (idx < 0) return [];
  const [found] = list.splice(idx, 1);
  return found.values;
}

function isRestrictionAxiom(ax: any): boolean {
  return ax?.isRestriction === true || ax?.isRestriction === 'true';
}

function namedRefs(axioms: unknown, labelFor: (iri: string) => string): { named: Ref[]; anonymous: string[] } {
  const named: Ref[] = [];
  const anonymous: string[] = [];
  const seen = new Set<string>();
  (Array.isArray(axioms) ? axioms : []).forEach((ax: any) => {
    const id = ax?.id;
    if (!isRestrictionAxiom(ax) && isIri(id)) {
      if (!seen.has(id)) {
        seen.add(id);
        named.push({ iri: id, label: labelFor(id) || ax?.definition || localName(id) });
      }
    } else if (ax?.definition) {
      anonymous.push(String(ax.definition));
    }
  });
  return { named, anonymous };
}

function iriRefs(list: unknown, labelFor: (iri: string) => string): Ref[] {
  const out: Ref[] = [];
  const seen = new Set<string>();
  (Array.isArray(list) ? list : []).forEach((item: any) => {
    const iri = typeof item === 'string' ? item : item?.iri || item?.id;
    if (!iri || seen.has(iri)) return;
    seen.add(iri);
    const given = typeof item === 'object' ? item?.label : undefined;
    out.push({ iri, label: given || labelFor(iri) || localName(iri) });
  });
  return out;
}

function formatCharacteristic(c: string): string {
  const s = localName(String(c)).replace(/Property$/, '').replace(/^owl:/, '');
  return s.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, ch => ch.toUpperCase());
}

export const SelectionDetailsCard: React.FC<SelectionDetailsCardProps> = ({
  node,
  nodes,
  edges,
  projectId,
  draft = false,
  isExternalNode,
  onSelectNode
}) => {
  const [collapsed, setCollapsed] = useState(false);
  const iri = (node as any).uri || (node as any).iri || node.id;
  const isThing = iri === OWL_THING || PLACEHOLDER_LABELS.has(node.label);
  const kind = isThing ? null : entityKindOf(node.type);
  const fetchIri = kind && isIri(iri) ? iri : null;
  const { data, loading, error, retry } = useEntityDetails(projectId, fetchIri, fetchIri ? kind : null, draft);

  const nodeByIri = useMemo(() => {
    const map = new Map<string, OntologyNode>();
    nodes.forEach(n => {
      map.set(n.id, n);
      const u = (n as any).uri;
      if (u && !map.has(u)) map.set(u, n);
    });
    return map;
  }, [nodes]);

  const labelFor = (target: string): string => {
    const n = nodeByIri.get(target);
    if (n?.label) return n.label;
    if (target.startsWith('http://www.w3.org/2001/XMLSchema#')) return `xsd:${localName(target)}`;
    return localName(target);
  };

  const isDatatype = node.type === 'datatype';
  const isPlaceholder = !kind && !isDatatype && (isThing || node.type === 'setOperator' || !isIri(iri));

  const view = useMemo(() => {
    const annotations = readAnnotations(data?.annotations).filter(a => a.key !== RDFS_LABEL && localName(a.key) !== 'label');
    const definition = takeAnnotation(annotations, s => s === 'IAO_0000115');
    const comment = takeAnnotation(annotations, s => s === 'comment');
    const definedBy = takeAnnotation(annotations, s => s === 'isDefinedBy');
    const moduleValues = takeAnnotation(annotations, s => s === 'module');
    const status = takeAnnotation(annotations, s => s === 'verificationStatus');
    const deprecated = takeAnnotation(annotations, s => s === 'deprecated');
    if (definition.length === 0 && data?.description) definition.push(String(data.description));
    if (definition.length === 0 && (node as any).description) definition.push(String((node as any).description));

    const characteristics: string[] = [];
    if (kind && isExternalNode?.(node)) characteristics.push('external');
    if (deprecated.some(v => v === 'true')) characteristics.push('deprecated');

    let superclasses: Ref[] = [];
    let subclasses: Ref[] = [];
    let equivalent = { named: [] as Ref[], anonymous: [] as string[] };
    let disjoint = { named: [] as Ref[], anonymous: [] as string[] };

    if (kind === 'class') {
      if (data) {
        superclasses = namedRefs(data.subClassOfAxioms, labelFor).named;
        equivalent = namedRefs(data.equivalentClassesAxioms, labelFor);
        disjoint = namedRefs(data.disjointClassesAxioms, labelFor);
        disjoint.named = disjoint.named.filter(r => r.iri !== iri);
        subclasses = iriRefs(data.directSubclasses ?? data.subClasses, labelFor);
      }
      if (superclasses.length === 0) {
        superclasses = iriRefs(edges.filter(e => e.type === 'subClassOf' && e.from === node.id).map(e => e.to), labelFor);
      }
      if (subclasses.length === 0) {
        subclasses = iriRefs(edges.filter(e => e.type === 'subClassOf' && e.to === node.id).map(e => e.from), labelFor);
      }
    }

    const propType = data?.type ? String(data.type) : node.type === 'dataProperty' ? 'DatatypeProperty' : (node.type as string) === 'annotationProperty' ? 'AnnotationProperty' : 'ObjectProperty';
    const propChars = kind === 'property' ? toStrings(data?.characteristics).map(formatCharacteristic) : [];

    return {
      typeLabel: kind === 'class' ? 'owl:Class' : kind === 'individual' ? 'owl:NamedIndividual' : kind === 'property' ? `owl:${propType}` : node.type,
      characteristics: [...characteristics, ...propChars],
      definition,
      comment,
      definedBy,
      moduleValues,
      status,
      superclasses,
      subclasses,
      equivalent,
      disjoint,
      domains: kind === 'property' ? iriRefs(data?.domains, labelFor) : [],
      ranges: kind === 'property' ? iriRefs(data?.ranges, labelFor) : [],
      superProperties: kind === 'property' ? iriRefs(data?.superProperties, labelFor) : [],
      inverse: kind === 'property' ? iriRefs(data?.inverseProperties, labelFor) : [],
      subProperties: kind === 'property' ? iriRefs(data?.subProperties, labelFor) : [],
      equivalentProperties: kind === 'property' ? iriRefs(data?.equivalentProperties, labelFor) : [],
      disjointProperties: kind === 'property' ? iriRefs(data?.disjointProperties, labelFor) : [],
      types: kind === 'individual' ? iriRefs(toStrings(data?.types).filter(t => isIri(t) && !isBuiltIn(t)), labelFor) : [],
      sameAs: kind === 'individual' ? iriRefs(data?.sameIndividualAs, labelFor) : [],
      differentFrom: kind === 'individual' ? iriRefs(data?.differentIndividualFrom, labelFor) : [],
      assertions: kind === 'individual' && Array.isArray(data?.propertyAssertions)
        ? (data.propertyAssertions as any[]).filter(a => a && isIri(a.propertyIri) && !VALUES_SHOWN_ELSEWHERE.has(String(a.propertyIri)))
            .filter(a => a.targetIri ? isIri(String(a.targetIri)) : a.targetLiteral != null)
            .map(a => ({
            propertyIri: String(a.propertyIri),
            propertyLabel: a.propertyLabel ? String(a.propertyLabel) : labelFor(String(a.propertyIri)),
            targetIri: a.targetIri ? String(a.targetIri) : null,
            targetLabel: a.targetIri ? (a.targetLabel ? String(a.targetLabel) : labelFor(String(a.targetIri))) : null,
            literal: a.targetLiteral != null ? String(a.targetLiteral) : null,
            datatype: a.datatype ? labelFor(String(a.datatype)) : null,
            inferred: a.isInferred === true
          }))
        : [] as { propertyIri: string; propertyLabel: string; targetIri: string | null; targetLabel: string | null; literal: string | null; datatype: string | null; inferred: boolean }[],
      others: annotations
    };
  }, [data, kind, node, edges, iri, nodeByIri, isExternalNode]);

  const renderRefs = (refs: Ref[]) => (
    <div style={styles.badges}>
      {refs.map(r => {
        const target = nodeByIri.get(r.iri);
        return target ? (
          <button
            key={r.iri}
            type="button"
            style={styles.badge}
            title={r.iri}
            onClick={() => onSelectNode(target)}
          >
            {r.label}
          </button>
        ) : (
          <span key={r.iri} style={{ ...styles.badge, ...styles.badgeStatic }} title={r.iri}>{r.label}</span>
        );
      })}
    </div>
  );

  const row = (label: string, content: React.ReactNode, show: boolean = true) =>
    show ? (
      <div style={styles.row}>
        <div style={styles.key}>{label}</div>
        <div style={styles.value}>{content}</div>
      </div>
    ) : null;

  const texts = (values: string[]) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {values.map((v, i) => <span key={i}>{v}</span>)}
    </div>
  );

  const links = (values: string[]) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {values.map((v, i) =>
        isIri(v) ? (
          <a key={i} href={safeHref(v)} target="_blank" rel="noopener noreferrer" style={styles.link}>{v}</a>
        ) : (
          <span key={i}>{v}</span>
        )
      )}
    </div>
  );

  const pills = (values: string[]) => (
    <div style={styles.badges}>
      {values.map(v => (
        <span key={v} style={{ ...styles.pill, ...styles.pillChar }}>{v}</span>
      ))}
    </div>
  );

  const anonymousList = (items: string[]) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
      {items.map((t, i) => <span key={i} style={styles.expr}>{t}</span>)}
    </div>
  );

  return (
    <div style={styles.card} data-testid="selection-details-card">
      <button type="button" style={styles.header} onClick={() => setCollapsed(c => !c)} aria-expanded={!collapsed}>
        {collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
        <span style={styles.title} title={node.label}>{node.label || localName(iri)}</span>
        <span style={styles.typeTag}>{kind === 'property' ? 'property' : node.type}</span>
      </button>

      {!collapsed && (
        <div style={styles.body}>
          {isIri(iri) && (
            <a href={safeHref(iri)} target="_blank" rel="noopener noreferrer" style={styles.iri} title={iri}>{iri}</a>
          )}

          {isPlaceholder && (
            <div style={styles.note}>Placeholder node — no ontology entity.</div>
          )}

          {isDatatype && (
            <>
              {row('Type', <span style={styles.mono}>rdfs:Datatype</span>)}
              {row('Name', <span style={styles.mono}>{isIri(iri) ? labelFor(iri) : (node.label || iri)}</span>)}
              {row('Built-in', <span>{isIri(iri) && iri.startsWith('http://www.w3.org/2001/XMLSchema#') ? 'Yes (XML Schema)' : 'No'}</span>)}
            </>
          )}

          {!isPlaceholder && kind && (
            <>
              {loading && (
                <div style={styles.note}>
                  <RefreshCw size={12} className="spinning" style={{ marginRight: 6, verticalAlign: -1 }} />
                  Loading details…
                </div>
              )}
              {error && (
                <div style={styles.error}>
                  <span>Details could not be loaded. {error}</span>
                  <button type="button" style={styles.retry} onClick={retry}>Retry</button>
                </div>
              )}

              {row('Type', <span style={styles.mono}>{view.typeLabel}</span>)}

              {kind === 'class' && (
                <>
                  {row('Charac.', pills(view.characteristics), view.characteristics.length > 0)}
                  {row('Definition', texts(view.definition), view.definition.length > 0)}
                  {row('Module', texts(view.moduleValues), view.moduleValues.length > 0)}
                  {row('Status', texts(view.status), view.status.length > 0)}
                  {row('Comment', texts(view.comment), view.comment.length > 0)}
                  {row('Defined by', links(view.definedBy), view.definedBy.length > 0)}
                  {row('Superclasses', renderRefs(view.superclasses), view.superclasses.length > 0)}
                  {row('Subclasses', renderRefs(view.subclasses), view.subclasses.length > 0)}
                  {row(
                    'Equivalent',
                    <>
                      {view.equivalent.named.length > 0 && renderRefs(view.equivalent.named)}
                      {view.equivalent.anonymous.length > 0 && anonymousList(view.equivalent.anonymous)}
                    </>,
                    view.equivalent.named.length + view.equivalent.anonymous.length > 0
                  )}
                  {row(
                    'Disjoint',
                    <>
                      {view.disjoint.named.length > 0 && renderRefs(view.disjoint.named)}
                      {view.disjoint.anonymous.length > 0 && anonymousList(view.disjoint.anonymous)}
                    </>,
                    view.disjoint.named.length + view.disjoint.anonymous.length > 0
                  )}
                </>
              )}

              {kind === 'property' && (
                <>
                  {row('Inverse', renderRefs(view.inverse), view.inverse.length > 0)}
                  {row('Domain', renderRefs(view.domains), view.domains.length > 0)}
                  {row('Range', renderRefs(view.ranges), view.ranges.length > 0)}
                  {row('Superprop.', renderRefs(view.superProperties), view.superProperties.length > 0)}
                  {row('Subprop.', renderRefs(view.subProperties), view.subProperties.length > 0)}
                  {row('Equivalent', renderRefs(view.equivalentProperties), view.equivalentProperties.length > 0)}
                  {row('Disjoint', renderRefs(view.disjointProperties), view.disjointProperties.length > 0)}
                  {row('Charac.', pills(view.characteristics), view.characteristics.length > 0)}
                  {row('Comment', texts(view.comment), view.comment.length > 0)}
                  {row('Defined by', links(view.definedBy), view.definedBy.length > 0)}
                  {row('Definition', texts(view.definition), view.definition.length > 0)}
                  {row('Module', texts(view.moduleValues), view.moduleValues.length > 0)}
                  {row('Status', texts(view.status), view.status.length > 0)}
                </>
              )}

              {kind === 'individual' && (
                <>
                  {row('Types', renderRefs(view.types), view.types.length > 0)}
                  {row('Definition', texts(view.definition), view.definition.length > 0)}
                  {row('Comment', texts(view.comment), view.comment.length > 0)}
                  {row('Same as', renderRefs(view.sameAs), view.sameAs.length > 0)}
                  {row('Different', renderRefs(view.differentFrom), view.differentFrom.length > 0)}
                  {row(
                    'Values',
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      {view.assertions.map((a, i) => (
                        <div key={`${a.propertyIri}-${i}`} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <span style={styles.annName} title={a.propertyIri}>
                            {a.propertyLabel}{a.inferred ? ' (inferred)' : ''}
                          </span>
                          {a.targetIri
                            ? renderRefs([{ iri: a.targetIri, label: a.targetLabel || localName(a.targetIri) }])
                            : <span>{a.literal}{a.datatype ? ` (${a.datatype})` : ''}</span>}
                        </div>
                      ))}
                    </div>,
                    view.assertions.length > 0
                  )}
                  {row('Module', texts(view.moduleValues), view.moduleValues.length > 0)}
                  {row('Status', texts(view.status), view.status.length > 0)}
                  {row('Defined by', links(view.definedBy), view.definedBy.length > 0)}
                </>
              )}

              {row(
                'Annotations',
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {view.others.map(a => (
                    <div key={a.key} style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                      <span style={styles.annName} title={a.key}>{a.name}</span>
                      {a.values.map((v, i) =>
                        isIri(v) ? (
                          <a key={i} href={safeHref(v)} target="_blank" rel="noopener noreferrer" style={styles.link}>{v}</a>
                        ) : (
                          <span key={i}>{v}</span>
                        )
                      )}
                    </div>
                  ))}
                </div>,
                view.others.length > 0
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

const styles: Record<string, React.CSSProperties> = {
  card: {
    display: 'flex',
    flexDirection: 'column',
    borderTop: '1px solid var(--border, #e5e7eb)',
    background: 'var(--surface-1, #ffffff)',
    minHeight: 0
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '6px 10px',
    background: 'var(--surface-2, #f3f4f6)',
    border: 'none',
    borderBottom: '1px solid var(--border, #e5e7eb)',
    color: 'var(--text-primary, #111827)',
    cursor: 'pointer',
    textAlign: 'left',
    width: '100%',
    flexShrink: 0
  },
  title: {
    flex: 1,
    minWidth: 0,
    fontWeight: 600,
    fontSize: 13,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap'
  },
  typeTag: {
    fontSize: 10,
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    color: 'var(--text-secondary, #6b7280)'
  },
  body: {
    display: 'flex',
    flexDirection: 'column',
    padding: '6px 10px 10px',
    gap: 2,
    overflow: 'auto',
    fontSize: 12,
    color: 'var(--text-primary, #111827)'
  },
  iri: {
    fontFamily: 'monospace',
    fontSize: 10,
    color: 'var(--text-secondary, #6b7280)',
    wordBreak: 'break-all',
    textDecoration: 'none',
    marginBottom: 4
  },
  row: {
    display: 'grid',
    gridTemplateColumns: '92px minmax(0, 1fr)',
    gap: 8,
    padding: '5px 0',
    borderTop: '1px dashed var(--border, #e5e7eb)'
  },
  key: {
    fontSize: 10.5,
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    color: 'var(--text-secondary, #6b7280)',
    paddingTop: 2
  },
  value: {
    minWidth: 0,
    overflowWrap: 'anywhere',
    lineHeight: 1.45
  },
  badges: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 4
  },
  badge: {
    fontSize: 11.5,
    padding: '1px 8px',
    borderRadius: 999,
    border: '1px solid var(--border, #d1d5db)',
    background: 'var(--surface-2, #f3f4f6)',
    color: 'var(--accent, #4f46e5)',
    cursor: 'pointer',
    maxWidth: '100%',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap'
  },
  badgeStatic: {
    cursor: 'default',
    color: 'var(--text-secondary, #6b7280)'
  },
  pill: {
    fontSize: 11,
    padding: '1px 8px',
    borderRadius: 999,
    fontWeight: 600
  },
  pillChar: {
    background: 'rgba(245, 158, 11, 0.15)',
    color: 'var(--warning, #b45309)'
  },
  mono: {
    fontFamily: 'monospace',
    fontSize: 11.5
  },
  expr: {
    fontFamily: 'monospace',
    fontSize: 11,
    color: 'var(--text-secondary, #6b7280)'
  },
  link: {
    color: 'var(--accent, #4f46e5)',
    wordBreak: 'break-all',
    textDecoration: 'none'
  },
  annName: {
    fontSize: 10.5,
    color: 'var(--text-secondary, #6b7280)'
  },
  note: {
    fontSize: 12,
    color: 'var(--text-secondary, #6b7280)',
    fontStyle: 'italic',
    padding: '4px 0'
  },
  error: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    fontSize: 12,
    color: 'var(--error, #b91c1c)',
    padding: '4px 0'
  },
  retry: {
    border: '1px solid var(--border, #d1d5db)',
    background: 'transparent',
    color: 'inherit',
    borderRadius: 4,
    padding: '1px 8px',
    cursor: 'pointer',
    fontSize: 11
  }
};
