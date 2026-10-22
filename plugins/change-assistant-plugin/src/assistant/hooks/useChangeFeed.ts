import { useEffect, useRef, useState } from 'react';
import { currentActor } from '../../authFetch';
import { fetchDraftList, fetchDraftSessionActive, fetchRecentChanges } from '../assistantApi';
import { computeStats, parseChange, parseDraft } from '../changeParsing';
import { ChangeStats, EMPTY_STATS, OntologyChange } from '../types';

export function useChangeFeed(projectId: string, query = '') {
  const [changes, setChanges] = useState<OntologyChange[]>([]);
  const [draftChanges, setDraftChanges] = useState<OntologyChange[]>([]);
  const draftChangesRef = useRef<OntologyChange[]>(draftChanges);
  draftChangesRef.current = draftChanges;
  const [stats, setStats] = useState<ChangeStats>(EMPTY_STATS);
  const [isLoading, setIsLoading] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());
  const [isDraftActive, setIsDraftActive] = useState(false);
  const [olderChanges, setOlderChanges] = useState<OntologyChange[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const draftSeqRef = useRef(0);
  const changesSeqRef = useRef(0);
  const firstPageSizeRef = useRef(0);
  const queryRef = useRef(query);
  queryRef.current = query;

  const loadDraftChanges = async () => {
    const seq = ++draftSeqRef.current;
    try {
      const userId = currentActor().userId;
      const [drafts, draftActive] = await Promise.all([
        fetchDraftList(projectId, userId),
        fetchDraftSessionActive(projectId, userId),
      ]);
      if (seq !== draftSeqRef.current) return;
      if (drafts) setDraftChanges(drafts.map((draft: any) => parseDraft(draft)));
      setIsDraftActive(draftActive);
    } catch (error) {
      console.error('Failed to load draft changes:', error);
    }
  };

  const olderRef = useRef<OntologyChange[]>([]);
  olderRef.current = olderChanges;

  const loadOlder = async () => {
    if (isLoadingOlder || !hasMore) return;
    setIsLoadingOlder(true);
    try {
      const offset = firstPageSizeRef.current + olderRef.current.length;
      const data = await fetchRecentChanges(projectId, offset, currentActor().userId, queryRef.current);
      if (!data.success) return;
      const seen = new Set([...changes, ...olderRef.current].map(change => change.id));
      const fresh = data.changes.map((change: any) => parseChange(change)).filter((change: OntologyChange) => !seen.has(change.id));
      setOlderChanges(previous => [...previous, ...fresh]);
      setHasMore(!!data.hasMore);
    } catch (error) {
      console.error('Failed to load older changes:', error);
    } finally {
      setIsLoadingOlder(false);
    }
  };

  const loadChanges = async () => {
    const seq = ++changesSeqRef.current;
    setIsLoading(true);
    try {
      const data = await fetchRecentChanges(projectId, 0, currentActor().userId, queryRef.current);
      if (seq !== changesSeqRef.current) return;
      if (!data.success) {
        console.error('[ChangeAssistant] Failed to load changes:', data.error);
        return;
      }
      const parsedChanges = data.changes.map((change: any) => parseChange(change));
      firstPageSizeRef.current = parsedChanges.length;
      setChanges(parsedChanges);
      setHasMore(olderRef.current.length === 0 ? !!data.hasMore : true);
      setLastRefresh(new Date());
      if (!queryRef.current) setStats(computeStats([...parsedChanges, ...olderRef.current], draftChangesRef.current));
    } catch (error) {
      console.error('Failed to load changes:', error);
    } finally {
      if (seq === changesSeqRef.current) setIsLoading(false);
    }
  };

  useEffect(() => {
    setOlderChanges([]);
    setHasMore(false);
    loadChanges();
    loadDraftChanges();
    const interval = setInterval(() => {
      loadChanges();
      loadDraftChanges();
    }, 10000);
    const handleSaved = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!detail || detail.projectId === projectId) {
        loadChanges();
        loadDraftChanges();
      }
    };
    let committedTimer: ReturnType<typeof setTimeout> | undefined;
    const handleCommitted = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (detail && detail.projectId !== projectId) return;
      clearTimeout(committedTimer);
      committedTimer = setTimeout(() => {
        loadChanges();
        loadDraftChanges();
      }, 400);
    };
    window.addEventListener('ontologyChangesSaved', handleSaved);
    window.addEventListener('ontocode:committed-change', handleCommitted);
    return () => {
      clearInterval(interval);
      clearTimeout(committedTimer);
      window.removeEventListener('ontologyChangesSaved', handleSaved);
      window.removeEventListener('ontocode:committed-change', handleCommitted);
    };
  }, [projectId, query]);

  const refreshAll = () => {
    loadChanges();
    loadDraftChanges();
  };

  const refreshAfterRollback = () => {
    setTimeout(() => {
      loadChanges();
      loadDraftChanges();
    }, 1200);
  };

  const allChanges = olderChanges.length === 0 ? changes : [...changes, ...olderChanges.filter(older => !changes.some(c => c.id === older.id))];

  return {
    changes: allChanges, draftChanges, stats, isLoading, lastRefresh, isDraftActive, loadChanges, refreshAll,
    refreshAfterRollback, hasMore, isLoadingOlder, loadOlder
  };
}
