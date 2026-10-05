import { useCallback, useEffect, useState } from 'react';

type Choices = Record<string, boolean>;
const MAX_STORED = 200;

function readChoices(storageKey: string): Choices {
  try {
    const parsed = JSON.parse(localStorage.getItem(storageKey) || '{}');
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeChoices(storageKey: string, choices: Choices): void {
  try {
    const keys = Object.keys(choices).slice(-MAX_STORED);
    const trimmed: Choices = {};
    keys.forEach(k => { trimmed[k] = choices[k]; });
    localStorage.setItem(storageKey, JSON.stringify(trimmed));
  } catch {
    return;
  }
}

export function useExpandedSets(projectId: string, newestKey: string | null, query: string, matchedKeys: Set<string>) {
  const storageKey = `changeAssistant.expandedSets.${projectId}`;
  const [choices, setChoices] = useState<Choices>(() => readChoices(storageKey));
  const [searchChoices, setSearchChoices] = useState<Choices>({});
  const searching = query.trim().length > 0;

  useEffect(() => setChoices(readChoices(storageKey)), [storageKey]);
  useEffect(() => setSearchChoices({}), [query]);

  const isExpanded = useCallback((key: string): boolean => {
    if (searching && matchedKeys.has(key)) return searchChoices[key] ?? true;
    return choices[key] ?? key === newestKey;
  }, [searching, matchedKeys, searchChoices, choices, newestKey]);

  const toggle = useCallback((key: string) => {
    const next = !isExpanded(key);
    if (searching && matchedKeys.has(key)) {
      setSearchChoices(prev => ({ ...prev, [key]: next }));
      return;
    }
    setChoices(prev => {
      const updated = { ...prev };
      delete updated[key];
      updated[key] = next;
      writeChoices(storageKey, updated);
      return updated;
    });
  }, [isExpanded, searching, matchedKeys, storageKey]);

  const expand = useCallback((key: string) => {
    if (isExpanded(key)) return;
    toggle(key);
  }, [isExpanded, toggle]);

  return { isExpanded, toggle, expand };
}
