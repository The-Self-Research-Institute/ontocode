import React, { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import { fetchImportedItems } from './assistantApi';

const IMPORT_IRI_PREFIX = 'urn:ontocode:spreadsheet-import:';
const PAGE_SIZE = 50;

const GROUPS: { key: string; label: string; withAssertions?: boolean }[] = [
  { key: 'classes', label: 'Classes' },
  { key: 'individuals', label: 'Individuals', withAssertions: true },
  { key: 'objectProperties', label: 'Object properties' },
  { key: 'dataProperties', label: 'Data properties' },
  { key: 'annotationProperties', label: 'Annotation properties' },
  { key: 'datatypes', label: 'Datatypes' },
  { key: 'updatedIndividuals', label: 'Existing individuals updated', withAssertions: true },
];

interface Link { property: string; propertyIri: string; target: string; targetIri: string | null }
interface Value { property: string; propertyIri: string; value: string }
interface Assertions {
  classes: string[];
  objects: Link[];
  data: Value[];
  annotations: Value[];
  other: Link[];
}
interface Item { iri: string; name: string; assertions?: Assertions | null }
interface Group { total: number; offset: number; items: Item[] }

export function isSpreadsheetImport(details: any): boolean {
  return String(details?.entityIRI || '').startsWith(IMPORT_IRI_PREFIX);
}

const SECTION = 'text-[11px] font-semibold uppercase tracking-wide text-gray-500 mt-2 mb-1';

const AssertionList: React.FC<{ assertions?: Assertions | null }> = ({ assertions }) => {
  if (!assertions) {
    return <p className="text-xs text-gray-500 pl-6 py-1">No assertions were added for this individual.</p>;
  }
  return (
    <div className="pl-6 pb-2">
      {assertions.classes.length > 0 && (
        <>
          <p className={SECTION}>Class assertions</p>
          <div className="flex flex-wrap gap-1">
            {assertions.classes.map(c => (
              <span key={c} className="px-2 py-0.5 rounded border border-gray-200 bg-white text-gray-700 text-xs">{c}</span>
            ))}
          </div>
        </>
      )}
      {assertions.objects.length > 0 && (
        <>
          <p className={SECTION}>Object property assertions</p>
          {assertions.objects.map((l, i) => (
            <div key={`${l.propertyIri}-${i}`} className="px-2 py-1 mb-1 rounded border border-gray-200 bg-white text-xs">
              <span className="font-medium text-gray-800" title={l.propertyIri}>{l.property}</span>
              <span className="text-gray-400"> → </span>
              <span className="text-gray-700" title={l.targetIri || undefined}>{l.target}</span>
            </div>
          ))}
        </>
      )}
      {assertions.data.length > 0 && (
        <>
          <p className={SECTION}>Data property assertions</p>
          {assertions.data.map((v, i) => (
            <div key={`${v.propertyIri}-${i}`} className="px-2 py-1 mb-1 rounded border border-gray-200 bg-white text-xs">
              <span className="font-medium text-gray-800" title={v.propertyIri}>{v.property}</span>
              <span className="text-gray-400"> = </span>
              <span className="text-gray-700 break-words">{v.value}</span>
            </div>
          ))}
        </>
      )}
      {assertions.annotations.length > 0 && (
        <>
          <p className={SECTION}>Annotation assertions</p>
          {assertions.annotations.map((v, i) => (
            <div key={`${v.propertyIri}-${i}`} className="px-2 py-1 mb-1 rounded border border-gray-200 bg-white text-xs">
              <span className="font-medium text-gray-700" title={v.propertyIri}>{v.property}</span>
              <span className="text-gray-400"> = </span>
              <span className="text-gray-700 break-words">{v.value}</span>
            </div>
          ))}
        </>
      )}
      {assertions.other.length > 0 && (
        <>
          <p className={SECTION}>Other</p>
          {assertions.other.map((l, i) => (
            <div key={`${l.propertyIri}-${i}`} className="px-2 py-1 mb-1 rounded border border-gray-200 bg-white text-xs">
              <span className="font-medium text-gray-700">{l.property}</span>
              <span className="text-gray-400"> → </span>
              <span className="text-gray-700">{l.target}</span>
            </div>
          ))}
        </>
      )}
    </div>
  );
};

const ImportedItems: React.FC<{ projectId: string; details: any }> = ({ projectId, details }) => {
  const importId = String(details.entityIRI).slice(IMPORT_IRI_PREFIX.length);
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<{ rolledBack: boolean; groups: Record<string, Group> } | null>(null);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [loadingMore, setLoadingMore] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setOpen({});
    fetchImportedItems(projectId, importId)
      .then(result => { if (!cancelled) setData(result); })
      .catch(() => { if (!cancelled) setData(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId, importId]);

  const loadMore = async (key: string) => {
    const current = data?.groups?.[key];
    if (!data || !current) return;
    setLoadingMore(key);
    setMoreError(null);
    try {
      const page: Group | null = await fetchImportedItems(projectId, importId,
        { group: key, offset: current.items.length, limit: PAGE_SIZE });
      if (!page) throw new Error('not loaded');
      setData(prev => prev && {
        ...prev,
        groups: {
          ...prev.groups,
          [key]: { ...current, total: page.total, items: [...current.items, ...page.items] },
        },
      });
    } catch {
      setMoreError(key);
    } finally {
      setLoadingMore(null);
    }
  };

  const toggle = (iri: string) => setOpen(prev => ({ ...prev, [iri]: !prev[iri] }));

  const groups = GROUPS.filter(g => (data?.groups?.[g.key]?.total ?? 0) > 0);

  return (
    <div className="bg-gray-50 rounded-lg p-3">
      <h4 className="font-medium text-sm mb-2">Imported items</h4>
      {loading ? (
        <p className="text-sm text-gray-500 flex items-center gap-2">
          <RefreshCw className="w-4 h-4 animate-spin" /> Loading…
        </p>
      ) : !data ? (
        <p className="text-sm text-gray-500">The list of imported items is not available.</p>
      ) : groups.length === 0 ? (
        <p className="text-sm text-gray-500">This import did not add any classes, individuals, properties or assertions.</p>
      ) : (
        <div className="space-y-3">
          {data.rolledBack && (
            <p className="text-xs text-amber-700">This import was undone; these items were removed again.</p>
          )}
          {groups.map(g => {
            const group = data.groups[g.key];
            const left = group.total - group.items.length;
            return (
              <div key={g.key}>
                <p className="text-xs font-medium text-gray-700">{g.label} ({group.total})</p>
                {g.withAssertions ? (
                  <div className="mt-1">
                    {group.items.map(item => (
                      <div key={item.iri} className="border-b border-gray-200 last:border-b-0">
                        <button type="button" onClick={() => toggle(item.iri)} title={item.iri}
                          className="w-full flex items-center gap-1 py-1 text-left text-sm text-gray-700 hover:text-purple-700"
                          aria-expanded={!!open[item.iri]}>
                          {open[item.iri] ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                          {item.name}
                        </button>
                        {open[item.iri] && <AssertionList assertions={item.assertions} />}
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-gray-600 break-words">
                    {group.items.map((item, i) => (
                      <React.Fragment key={item.iri}>
                        {i > 0 && ', '}
                        <span title={item.iri}>{item.name}</span>
                      </React.Fragment>
                    ))}
                  </p>
                )}
                {left > 0 && (
                  <button type="button" onClick={() => loadMore(g.key)} disabled={loadingMore === g.key}
                    className="mt-1 text-xs text-purple-700 hover:underline disabled:text-gray-400">
                    {loadingMore === g.key ? 'Loading…' : `Load more (${left} left)`}
                  </button>
                )}
                {moreError === g.key && (
                  <p className="text-xs text-red-600">Could not load more. Try again.</p>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default ImportedItems;
