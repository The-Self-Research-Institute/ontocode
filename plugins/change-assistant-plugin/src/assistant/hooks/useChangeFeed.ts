import { useEffect, useState } from 'react';
import { fetchDraftList, fetchRecentChanges } from '../assistantApi';
import { computeStats, parseChange, parseDraft } from '../changeParsing';
import { ChangeStats, EMPTY_STATS, OntologyChange } from '../types';

export function useChangeFeed(projectId: string) {
  const [changes, setChanges] = useState<OntologyChange[]>([]);
  const [draftChanges, setDraftChanges] = useState<OntologyChange[]>([]);
  const [stats, setStats] = useState<ChangeStats>(EMPTY_STATS);
  const [isLoading, setIsLoading] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());

  const loadDraftChanges = async () => {
    try {
      const drafts = await fetchDraftList(projectId);
      if (drafts) setDraftChanges(drafts.map((draft: any) => parseDraft(draft)));
    } catch (error) {
      console.error('Failed to load draft changes:', error);
    }
  };

  const loadChanges = async () => {
    setIsLoading(true);
    try {
      const data = await fetchRecentChanges(projectId);
      if (!data.success) {
        console.error('[ChangeAssistant] Failed to load changes:', data.error);
        setIsLoading(false);
        return;
      }
      const parsedChanges = data.changes.map((change: any) => parseChange(change));
      setChanges(parsedChanges);
      setLastRefresh(new Date());
      setStats(computeStats(parsedChanges, draftChanges));
    } catch (error) {
      console.error('Failed to load changes:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadChanges();
    loadDraftChanges();
    const interval = setInterval(() => {
      loadChanges();
      loadDraftChanges();
    }, 10000);
    return () => clearInterval(interval);
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

  return { changes, draftChanges, stats, isLoading, lastRefresh, loadChanges, refreshAll, refreshAfterRollback };
}
