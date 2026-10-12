import { useEffect, useRef, useState } from 'react';
import { currentActor } from '../../authFetch';
import { fetchDraftList, fetchDraftSessionActive, fetchRecentChanges } from '../assistantApi';
import { computeStats, parseChange, parseDraft } from '../changeParsing';
import { ChangeStats, EMPTY_STATS, OntologyChange } from '../types';

export function useChangeFeed(projectId: string) {
  const [changes, setChanges] = useState<OntologyChange[]>([]);
  const [draftChanges, setDraftChanges] = useState<OntologyChange[]>([]);
  const draftChangesRef = useRef<OntologyChange[]>(draftChanges);
  draftChangesRef.current = draftChanges;
  const [stats, setStats] = useState<ChangeStats>(EMPTY_STATS);
  const [isLoading, setIsLoading] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());
  const [isDraftActive, setIsDraftActive] = useState(false);
  const draftSeqRef = useRef(0);
  const changesSeqRef = useRef(0);

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

  const loadChanges = async () => {
    const seq = ++changesSeqRef.current;
    setIsLoading(true);
    try {
      const data = await fetchRecentChanges(projectId);
      if (seq !== changesSeqRef.current) return;
      if (!data.success) {
        console.error('[ChangeAssistant] Failed to load changes:', data.error);
        return;
      }
      const parsedChanges = data.changes.map((change: any) => parseChange(change));
      setChanges(parsedChanges);
      setLastRefresh(new Date());
      setStats(computeStats(parsedChanges, draftChangesRef.current));
    } catch (error) {
      console.error('Failed to load changes:', error);
    } finally {
      if (seq === changesSeqRef.current) setIsLoading(false);
    }
  };

  useEffect(() => {
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
    window.addEventListener('ontologyChangesSaved', handleSaved);
    return () => {
      clearInterval(interval);
      window.removeEventListener('ontologyChangesSaved', handleSaved);
    };
  }, [projectId]);

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

  return { changes, draftChanges, stats, isLoading, lastRefresh, isDraftActive, loadChanges, refreshAll, refreshAfterRollback };
}
