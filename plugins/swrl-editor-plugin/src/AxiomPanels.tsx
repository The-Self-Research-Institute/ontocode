import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';
import apiClient from './apiClient';
import { rt, btn } from './resultsTheme';
import {
  ASSERTED_PAGE_SIZE,
  ASSERTED_TYPES,
  OWL2RL_PAGE_SIZE,
  OWL2RL_UNSUPPORTED_NOTE,
  OWL2RL_EXPIRED_MESSAGE,
  assertedAxiomsPath,
  axiomListErrorMessage,
  canGoNextAssertedPage,
  formatCount,
  isExpiredRunError,
  owl2rlErrorMessage,
  owl2rlRunPath,
  owl2rlStartPath,
  owl2rlTruncationNote,
} from './ruleModel';

interface AssertedItem {
  axiomType: string;
  readable?: string;
  description?: string;
  category?: string;
}

interface AssertedResponse {
  total: number;
  page: number;
  pageSize: number;
  truncated?: boolean;
  scanned?: number;
  maxScan?: number;
  items: AssertedItem[];
}

interface Owl2RlAxiom {
  axiomType: string;
  category?: string;
  readable?: string;
  description?: string;
}

interface Owl2RlResponse {
  success: boolean;
  completion?: 'completed' | 'timeout' | 'failed';
  errorMessage?: string | null;
  executionTimeMs?: number;
  derivedCount?: number;
  returnedCount?: number;
  truncated?: boolean;
  maxAxioms?: number;
  omittedTrivialCount?: number;
  typeCounts?: Record<string, number>;
  unsupportedRules?: string[];
  runId?: string | null;
  page?: number;
  pageSize?: number;
  filteredCount?: number;
  totalPages?: number;
  axioms?: Owl2RlAxiom[];
}

const badge = (label: string) => (
  <span className="px-1.5 py-0.5 text-[10px] rounded-full whitespace-nowrap" style={rt.chip}>{label}</span>
);

export function AssertedAxiomsPanel({ projectId, draftMode }: { projectId: string; draftMode: boolean }) {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AssertedResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const requestRef = useRef(0);

  useEffect(() => {
    const mine = ++requestRef.current;
    setLoading(true);
    setError(null);
    apiClient
      .get<AssertedResponse>(assertedAxiomsPath(projectId, { page, pageSize: ASSERTED_PAGE_SIZE, search, type }, draftMode))
      .then(res => {
        if (requestRef.current === mine) setData(res);
      })
      .catch(e => {
        if (requestRef.current === mine) setError(axiomListErrorMessage(e));
      })
      .finally(() => {
        if (requestRef.current === mine) setLoading(false);
      });
  }, [projectId, draftMode, page, search, type, attempt]);

  const submitSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSearch(searchInput);
  };

  const total = data?.total ?? 0;
  const pageSize = data?.pageSize ?? ASSERTED_PAGE_SIZE;
  const from = total === 0 ? 0 : page * pageSize + 1;
  const to = Math.min(total, (page + 1) * pageSize);
  const hasNext = canGoNextAssertedPage(page, pageSize, total);

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-3" data-testid="asserted-panel">
      <p className="text-sm" style={rt.label}>
        Asserted axioms are the facts and rules typed into the ontology, not the ones worked out by a reasoner.
      </p>
      <form onSubmit={submitSearch} className="flex flex-wrap items-center gap-2">
        <input
          value={searchInput}
          onChange={e => setSearchInput(e.target.value)}
          placeholder="Search axioms"
          aria-label="Search asserted axioms"
          className="flex-1 min-w-[12rem] px-3 py-1.5 text-sm rounded-lg"
          style={rt.input}
        />
        <select
          value={type}
          onChange={e => { setPage(0); setType(e.target.value); }}
          aria-label="Axiom type"
          className="px-2 py-1.5 text-sm rounded-lg"
          style={rt.input}
        >
          <option value="">All types</option>
          {ASSERTED_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
        </select>
        <button type="submit" className="px-3 py-1.5 text-sm rounded-lg" style={rt.button}>Search</button>
      </form>

      {error && (
        <div className="p-3 text-sm rounded-lg flex items-center gap-3" style={rt.bannerError} role="alert" data-testid="asserted-error">
          <span className="flex-1">{error}</span>
          <button onClick={() => setAttempt(a => a + 1)} className="px-3 py-1 text-xs rounded-lg" style={rt.button}>Try again</button>
        </div>
      )}

      {data?.truncated && (
        <div className="p-2 text-sm rounded-lg" style={rt.bannerWarning} data-testid="asserted-truncated">
          This ontology is large, so only part of it was searched ({formatCount(data.scanned ?? 0)} axioms scanned). Narrow the search or type to see more.
        </div>
      )}

      {loading && !data && (
        <div className="flex items-center gap-2 text-sm" style={rt.muted}><Loader2 size={14} className="animate-spin" /> Loading axioms…</div>
      )}

      {data && (
        <>
          <div className="flex items-center justify-between text-xs" style={rt.label}>
            <span data-testid="asserted-range">
              {total === 0 ? 'No matching axioms' : `Showing ${formatCount(from)}-${formatCount(to)} of ${formatCount(total)}`}
              {loading && ' …'}
            </span>
            <span className="flex items-center gap-2">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0 || loading}
                className="px-2 py-1 rounded-lg"
                style={btn(page === 0 || loading)}
              >
                Previous
              </button>
              <button
                onClick={() => setPage(p => p + 1)}
                disabled={!hasNext || loading}
                className="px-2 py-1 rounded-lg"
                style={btn(!hasNext || loading)}
              >
                Next
              </button>
            </span>
          </div>
          <ul className="rounded-lg overflow-hidden" style={rt.panel}>
            {data.items.map((it, i) => (
              <li key={`${page}-${i}`} className="flex items-start gap-2 px-3 py-2 text-sm" style={rt.rowBorder}>
                {badge(it.axiomType)}
                <span className="flex-1 break-words" style={rt.value}>{it.readable || it.description}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

export function Owl2RlPanel({ projectId, draftMode }: { projectId: string; draftMode: boolean }) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<Owl2RlResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [typeFilter, setTypeFilter] = useState('');
  const [filterInput, setFilterInput] = useState('');
  const [pageLoading, setPageLoading] = useState(false);
  const inFlightRef = useRef(false);

  const compute = useCallback(async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    setRunning(true);
    setError(null);
    setExpired(false);
    setResult(null);
    setTypeFilter('');
    setFilterInput('');
    try {
      const res = await apiClient.post<Owl2RlResponse>(
        owl2rlStartPath(projectId, { pageSize: OWL2RL_PAGE_SIZE, type: '', filter: '' }, draftMode)
      );
      setResult(res);
    } catch (e: any) {
      setError(owl2rlErrorMessage(e));
    } finally {
      inFlightRef.current = false;
      setRunning(false);
    }
  }, [projectId, draftMode]);

  const loadPage = async (page: number, type: string, filter: string) => {
    const runId = result?.runId;
    if (!runId || pageLoading) return;
    setPageLoading(true);
    setError(null);
    try {
      const res = await apiClient.get<Owl2RlResponse>(
        owl2rlRunPath(projectId, runId, { page, pageSize: result?.pageSize ?? OWL2RL_PAGE_SIZE, type, filter }, draftMode)
      );
      setResult(prev => ({ ...(prev as Owl2RlResponse), ...res, runId: res.runId ?? runId }));
    } catch (e: any) {
      if (isExpiredRunError(e) || e?.data?.expired) {
        setExpired(true);
        setResult(null);
      } else {
        setError(owl2rlErrorMessage(e));
      }
    } finally {
      setPageLoading(false);
    }
  };

  const page = result?.page ?? 0;
  const totalPages = result?.totalPages ?? 1;
  const typeNames = Object.keys(result?.typeCounts ?? {});
  const unsupported = result?.unsupportedRules ?? [];
  const note = result ? owl2rlTruncationNote(result) : null;
  const axioms = result?.axioms ?? [];
  const sorted = [...axioms.filter(a => a.category !== 'structure'), ...axioms.filter(a => a.category === 'structure')];
  const failed = !!result && (!result.success || result.completion === 'timeout' || result.completion === 'failed');

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-3" data-testid="owl2rl-panel">
      <p className="text-sm" style={rt.label}>
        OWL 2 RL is a standard set of reasoning rules. Computing the closure works out every fact that follows from the ontology (inferred facts), which can take a while on large ontologies.
      </p>
      <div>
        <button
          onClick={compute}
          disabled={running}
          className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2"
          style={btn(running, true)}
        >
          {running && <Loader2 size={14} className="animate-spin" />}
          {running ? 'Computing…' : result || expired ? 'Compute again' : 'Compute OWL 2 RL closure'}
        </button>
      </div>

      {expired && (
        <div className="p-3 text-sm rounded-lg" style={rt.bannerWarning} role="status" data-testid="owl2rl-expired">{OWL2RL_EXPIRED_MESSAGE}</div>
      )}

      {error && (
        <div className="p-3 text-sm rounded-lg" style={rt.bannerError} role="alert" data-testid="owl2rl-error">{error}</div>
      )}

      {result && failed && (
        <div className="p-3 text-sm rounded-lg" style={rt.bannerError} role="alert" data-testid="owl2rl-failed">
          {result.errorMessage || (result.completion === 'timeout' ? 'The computation took too long and was stopped.' : 'The computation failed.')}
        </div>
      )}

      {result && !failed && (
        <>
          <div className="p-3 text-sm rounded-lg" style={rt.bannerSuccess} data-testid="owl2rl-summary">
            Worked out {formatCount(result.derivedCount ?? 0)} new fact{result.derivedCount === 1 ? '' : 's'} in {formatCount(result.executionTimeMs ?? 0)} ms.
            {(result.omittedTrivialCount ?? 0) > 0 && ` ${formatCount(result.omittedTrivialCount ?? 0)} trivial ones are left out.`}
          </div>
          {unsupported.length > 0 && (
            <div className="p-2 text-sm rounded-lg" style={rt.bannerWarning} data-testid="owl2rl-unsupported">
              {OWL2RL_UNSUPPORTED_NOTE} Not applied: {unsupported.join(', ')}.
            </div>
          )}
          {note && (
            <div className="p-2 text-sm rounded-lg" style={rt.bannerNote} data-testid="owl2rl-truncated">{note}</div>
          )}
          {result.runId && (
            <form
              onSubmit={e => { e.preventDefault(); loadPage(0, typeFilter, filterInput); }}
              className="flex flex-wrap items-center gap-2"
            >
              <input
                value={filterInput}
                onChange={e => setFilterInput(e.target.value)}
                placeholder="Filter results"
                aria-label="Filter closure results"
                className="flex-1 min-w-[12rem] px-3 py-1.5 text-sm rounded-lg"
                style={rt.input}
              />
              <select
                value={typeFilter}
                onChange={e => { setTypeFilter(e.target.value); loadPage(0, e.target.value, filterInput); }}
                aria-label="Closure axiom type"
                className="px-2 py-1.5 text-sm rounded-lg"
                style={rt.input}
              >
                <option value="">All types</option>
                {typeNames.map(t => <option key={t} value={t}>{t} ({formatCount(result.typeCounts?.[t] ?? 0)})</option>)}
              </select>
              <button type="submit" className="px-3 py-1.5 text-sm rounded-lg" style={rt.button}>Filter</button>
            </form>
          )}
          <ul className="rounded-lg overflow-hidden" style={rt.panel}>
            {sorted.map((a, i) => (
              <li key={`${page}-${i}`} className="flex items-start gap-2 px-3 py-2 text-sm" style={rt.rowBorder}>
                {badge(a.category === 'structure' ? 'structure' : 'conclusion')}
                {badge(a.axiomType)}
                <span className="flex-1 break-words" style={rt.value}>{a.readable || a.description}</span>
              </li>
            ))}
          </ul>
          {result.runId && totalPages > 1 && (
            <div className="flex items-center justify-between text-xs" style={rt.label}>
              <span data-testid="owl2rl-page">Page {page + 1} of {totalPages}</span>
              <span className="flex items-center gap-2">
                <button onClick={() => loadPage(page - 1, typeFilter, filterInput)} disabled={page === 0 || pageLoading} className="px-2 py-1 rounded-lg" style={btn(page === 0 || pageLoading)}>Previous</button>
                <button onClick={() => loadPage(page + 1, typeFilter, filterInput)} disabled={page + 1 >= totalPages || pageLoading} className="px-2 py-1 rounded-lg" style={btn(page + 1 >= totalPages || pageLoading)}>Next</button>
              </span>
            </div>
          )}
        </>
      )}
    </div>
  );
}
