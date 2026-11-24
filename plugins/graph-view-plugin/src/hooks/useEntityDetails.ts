import { useEffect, useState } from 'react';
import { authHeaders } from '../utils/authHeaders';

export type EntityKind = 'class' | 'property' | 'individual';

export interface EntityDetailsState {
  data: any | null;
  loading: boolean;
  error: string | null;
  retry: () => void;
}

const detailsCache = new Map<string, any>();
const MAX_CACHE_ENTRIES = 300;
const cacheListeners = new Set<() => void>();

function apiBaseUrl(): string {
  return (window as any).__DESKTOP_API_URL__ || (window as any).API_BASE_URL || '';
}

export function entityKindOf(type?: string): EntityKind | null {
  if (type === 'class') return 'class';
  if (type === 'individual') return 'individual';
  if (type === 'objectProperty' || type === 'dataProperty' || type === 'annotationProperty') return 'property';
  return null;
}

export function clearEntityDetailsCache(): void {
  detailsCache.clear();
  cacheListeners.forEach(listener => listener());
}

export function useEntityDetails(
  projectId: string,
  iri: string | null,
  kind: EntityKind | null,
  draft: boolean
): EntityDetailsState {
  const [state, setState] = useState<{ data: any | null; loading: boolean; error: string | null }>({
    data: null,
    loading: false,
    error: null
  });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const listener = () => setAttempt(a => a + 1);
    cacheListeners.add(listener);
    return () => {
      cacheListeners.delete(listener);
    };
  }, []);

  useEffect(() => {
    if (!projectId || !iri || !kind) {
      setState({ data: null, loading: false, error: null });
      return;
    }

    const key = `${projectId}|${draft ? 'draft' : 'main'}|${kind}|${iri}`;
    const cached = detailsCache.get(key);
    if (cached) {
      setState({ data: cached, loading: false, error: null });
      return;
    }

    const controller = new AbortController();
    setState({ data: null, loading: true, error: null });

    const pid = encodeURIComponent(projectId);
    let url = kind === 'class'
      ? `${apiBaseUrl()}/api/ontology/classes/details/${pid}?classIri=${encodeURIComponent(iri)}`
      : kind === 'individual'
        ? `${apiBaseUrl()}/api/ontology/individual-details/${pid}?individualIri=${encodeURIComponent(iri)}`
        : `${apiBaseUrl()}/api/ontology/properties/detail/${pid}?iri=${encodeURIComponent(iri)}`;
    if (draft) url += '&draft=true';

    fetch(url, { headers: authHeaders(), signal: controller.signal })
      .then(async res => {
        if (!res.ok) throw new Error(`The server answered ${res.status}.`);
        return res.json();
      })
      .then(body => {
        if (body && body.success === false) throw new Error(body.message || body.error || 'No details were returned.');
        const data = body && typeof body === 'object' && 'data' in body ? body.data : body;
        if (!data || typeof data !== 'object') throw new Error('No details were returned.');
        if (detailsCache.size >= MAX_CACHE_ENTRIES) {
          const oldest = detailsCache.keys().next().value;
          if (oldest !== undefined) detailsCache.delete(oldest);
        }
        detailsCache.set(key, data);
        setState({ data, loading: false, error: null });
      })
      .catch(err => {
        if (controller.signal.aborted) return;
        setState({ data: null, loading: false, error: err?.message || 'Could not load details.' });
      });

    return () => controller.abort();
  }, [projectId, iri, kind, draft, attempt]);

  return { ...state, retry: () => setAttempt(a => a + 1) };
}
