import React, { useEffect, useState } from 'react';
import apiClient from './apiClient';
import type { InferredAxiom } from './types';

interface Premise {
  kind: string;
  text: string;
}

interface Justification {
  ruleName: string;
  ruleText: string;
  bindings?: Record<string, string>;
  premises?: Premise[];
}

interface ExplainResult {
  conclusion?: string;
  status: string;
  justifications?: Justification[];
  notes?: string[];
}

interface AtomReport {
  text: string;
  status: string;
  detail?: string;
}

interface WhyNotResult {
  ruleName?: string;
  individual?: string;
  result: string;
  summary?: string;
  bindings?: Record<string, string>;
  atoms?: AtomReport[];
  scope?: string;
  notes?: string[];
}

const plainError = (e: unknown, what: string): string => {
  const raw = e instanceof Error ? e.message : typeof e === 'string' ? e : '';
  if (!raw || raw.trim().startsWith('{') || raw.trim().startsWith('[') || raw.includes('\n    at ') || raw.length > 200) {
    return `${what} failed. Please try again.`;
  }
  return `${what} failed: ${raw}`;
};

const formatBindings = (bindings?: Record<string, string>): string => {
  if (!bindings) return '';
  return Object.entries(bindings)
    .map(([k, v]) => `${k.replace(/^\?/, '')} = ${v}`)
    .join(', ');
};

const hasAssertedNote = (notes?: string[]): boolean =>
  !!notes && notes.some(n => /asserted|already in the ontology|already present/i.test(n));

const statusText = (r: ExplainResult): string => {
  if (r.status === 'explained') return 'Explained by a rule';
  if (r.status === 'origin_only') {
    if (hasAssertedNote(r.notes)) return 'Already in the ontology';
    return 'Produced by OWL reasoning or something this explanation cannot evaluate';
  }
  if (r.status === 'not_found') return 'No explanation was found for this conclusion';
  return 'The explanation returned an unrecognised status';
};

const COLLAPSE_AFTER = 3;

const JustificationView: React.FC<{ j: Justification; index: number }> = ({ j, index }) => {
  const bindings = formatBindings(j.bindings);
  return (
    <div className="border border-gray-200 rounded-lg p-2 bg-white">
      <div className="text-xs text-gray-500 mb-1">Reason {index + 1}</div>
      <div className="text-sm text-gray-900">
        <span className="font-semibold">Rule {j.ruleName}</span>
        {j.ruleText ? <span className="font-mono text-xs text-purple-800">: {j.ruleText}</span> : null}
      </div>
      {bindings && (
        <div className="text-xs text-gray-700 mt-1">
          With <span className="font-mono">{bindings}</span>
        </div>
      )}
      {j.premises && j.premises.length > 0 && (
        <ul className="list-disc pl-5 mt-1 text-xs text-gray-700 space-y-0.5">
          {j.premises.map((p, i) => (
            <li key={i}>{p.text}</li>
          ))}
        </ul>
      )}
    </div>
  );
};

export const ExplainPanel: React.FC<{ projectId: string; axiom: InferredAxiom; onClose: () => void }> = ({ projectId, axiom, onClose }) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ExplainResult | null>(null);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setResult(null);
    const body: Record<string, string> = {
      subject: axiom.subjectIri || '',
      predicate: axiom.predicateIri || '',
    };
    if (axiom.objectIri) body.object = axiom.objectIri;
    if (axiom.objectLiteral != null && axiom.objectLiteral !== '') body.objectLiteral = axiom.objectLiteral;
    if (!body.subject || !body.predicate) {
      setError('This conclusion has no subject or predicate to explain.');
      setLoading(false);
      return;
    }
    apiClient
      .post<ExplainResult>(`/api/swrl/${projectId}/explain`, body)
      .then(res => {
        if (!cancelled) setResult(res);
      })
      .catch(e => {
        if (!cancelled) setError(plainError(e, 'Explaining this conclusion'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, axiom]);

  const justifications = result?.justifications || [];
  const visible = showAll ? justifications : justifications.slice(0, COLLAPSE_AFTER);
  const hidden = justifications.length - visible.length;

  return (
    <div className="border border-purple-200 bg-purple-50/50 rounded-lg p-3 text-sm space-y-2">
      <div className="flex items-start justify-between gap-2">
        <div className="font-semibold text-gray-800">Why was this concluded?</div>
        <button onClick={onClose} className="text-xs text-gray-500 hover:text-gray-800">Close</button>
      </div>
      {loading && <div className="text-xs text-gray-600">Looking for the reasons...</div>}
      {error && <div className="text-xs text-red-700 bg-red-50 border border-red-200 rounded p-2">{error}</div>}
      {result && (
        <>
          <div className="text-xs font-medium text-gray-800">{statusText(result)}</div>
          {result.status === 'explained' && justifications.length > 1 && (
            <div className="text-xs text-gray-600">{justifications.length} different rules or bindings each produce this conclusion.</div>
          )}
          {result.status === 'origin_only' && !hasAssertedNote(result.notes) && (
            <div className="text-xs text-gray-600">This does not mean the conclusion is wrong, only that no rule here could be shown to produce it.</div>
          )}
          {visible.map((j, i) => (
            <JustificationView key={i} j={j} index={i} />
          ))}
          {hidden > 0 && (
            <button onClick={() => setShowAll(true)} className="text-xs text-purple-700 hover:underline">
              Show {hidden} more
            </button>
          )}
          {showAll && justifications.length > COLLAPSE_AFTER && (
            <button onClick={() => setShowAll(false)} className="text-xs text-purple-700 hover:underline">
              Show fewer
            </button>
          )}
          {result.notes && result.notes.length > 0 && (
            <ul className="list-disc pl-5 text-xs text-gray-700 space-y-0.5">
              {result.notes.map((n, i) => (
                <li key={i}>{n}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
};

const resultHeadline = (r: WhyNotResult, individual: string, firstDetail: string): string => {
  switch (r.result) {
    case 'no_supporting_fact':
      return `No supporting fact found: ${firstDetail}`;
    case 'comparison_failed':
      return `A comparison failed: ${firstDetail}`;
    case 'unsupported':
      return `This explanation cannot evaluate: ${firstDetail}`;
    case 'incomplete':
      return 'The check stopped early';
    case 'fires':
      return `This rule can fire for ${individual}`;
    default:
      return 'The check returned an unrecognised result';
  }
};

const resultColor = (result: string): string => {
  if (result === 'fires') return 'bg-green-50 border-green-200 text-green-900';
  if (result === 'no_supporting_fact' || result === 'comparison_failed') return 'bg-amber-50 border-amber-200 text-amber-900';
  return 'bg-gray-50 border-gray-300 text-gray-900';
};

const atomStatusText = (s: string): string => {
  switch (s) {
    case 'satisfied':
      return 'satisfied';
    case 'failed':
      return 'failed';
    case 'unsupported':
      return 'cannot evaluate';
    case 'not_evaluated':
      return 'not checked';
    default:
      return s.replace(/_/g, ' ');
  }
};

const isFullIri = (s: string): boolean => /^[A-Za-z][A-Za-z0-9+.-]*:\/\/\S+$/.test(s) || /^urn:\S+$/.test(s);

export const WhyNotPanel: React.FC<{ projectId: string; ruleId: string; individuals?: string[] }> = ({ projectId, ruleId, individuals }) => {
  const [open, setOpen] = useState(false);
  const [individual, setIndividual] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<WhyNotResult | null>(null);
  const [asked, setAsked] = useState('');

  useEffect(() => {
    setResult(null);
    setError(null);
    setAsked('');
  }, [ruleId]);

  const run = async () => {
    const value = individual.trim().replace(/^<|>$/g, '');
    setResult(null);
    if (!isFullIri(value)) {
      setError('Enter the full IRI of the individual, for example http://example.org/ontology#cara. Short names are not resolved here.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const res = await apiClient.post<WhyNotResult>(`/api/swrl/${projectId}/rules/${ruleId}/why-not`, { individual: value });
      setResult(res);
      setAsked(value);
    } catch (e) {
      setError(plainError(e, "Checking why this rule didn't fire"));
    } finally {
      setLoading(false);
    }
  };

  if (!open) {
    return (
      <button onClick={() => setOpen(true)} className="text-sm text-purple-700 hover:underline self-start">
        Why didn't this fire?
      </button>
    );
  }

  const atoms = result?.atoms || [];
  const problem = atoms.find(a => (a.status === 'failed' || a.status === 'unsupported') && a.detail);
  const firstDetail = problem?.detail || result?.summary || '';
  const listId = `whynot-individuals-${ruleId}`;

  return (
    <div className="border border-gray-200 rounded-lg p-3 space-y-2 bg-white">
      <div className="flex items-center justify-between">
        <div className="text-sm font-semibold text-gray-800">Why didn't this fire?</div>
        <button onClick={() => setOpen(false)} className="text-xs text-gray-500 hover:text-gray-800">Close</button>
      </div>
      <div className="flex gap-2">
        <input
          list={listId}
          value={individual}
          onChange={e => setIndividual(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !loading) run(); }}
          placeholder="Full IRI of an individual, e.g. http://example.org/ontology#cara"
          className="flex-1 px-2 py-1.5 text-sm border border-gray-300 rounded font-mono"
        />
        {individuals && individuals.length > 0 && (
          <datalist id={listId}>
            {individuals.map(i => (
              <option key={i} value={i} />
            ))}
          </datalist>
        )}
        <button
          onClick={run}
          disabled={loading || !individual.trim()}
          className="px-3 py-1.5 text-sm font-medium text-white bg-purple-600 rounded hover:bg-purple-700 disabled:opacity-50"
        >
          {loading ? 'Checking...' : 'Check'}
        </button>
      </div>
      {error && <div className="text-xs text-red-700 bg-red-50 border border-red-200 rounded p-2">{error}</div>}
      {result && (
        <div className="space-y-2">
          <div className={`text-sm font-medium border rounded p-2 ${resultColor(result.result)}`}>
            {resultHeadline(result, asked, firstDetail)}
          </div>
          {atoms.length > 0 && (
            <ul className="space-y-1 text-xs text-gray-800">
              {atoms.map((a, i) => (
                <li key={i} className="border border-gray-100 rounded p-1.5">
                  <span className="font-mono">{a.text}</span>
                  <span className="ml-2 text-gray-600">[{atomStatusText(a.status)}]</span>
                  {a.detail ? <div className="text-gray-600 mt-0.5">{a.detail}</div> : null}
                </li>
              ))}
            </ul>
          )}
          {result.notes && result.notes.length > 0 && (
            <ul className="list-disc pl-5 text-xs text-gray-700 space-y-0.5">
              {result.notes.map((n, i) => (
                <li key={i}>{n}</li>
              ))}
            </ul>
          )}
          {result.scope && <div className="text-xs text-gray-600 italic">{result.scope}</div>}
        </div>
      )}
    </div>
  );
};
