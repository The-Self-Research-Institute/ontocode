import { useEffect } from 'react';
import type { AssistantTab } from '../types';

const TAB_EVENT = 'change-assistant:show-tab';
const PENDING_TAB = '__ontocodeChangeAssistantTab';
const TABS: readonly AssistantTab[] = ['live', 'drafts', 'changes', 'conflicts', 'history', 'stats'];

function asTab(value: unknown): AssistantTab | undefined {
  return TABS.find((tab) => tab === value);
}

export function useRequestedTab(select: (tab: AssistantTab) => void): void {
  useEffect(() => {
    const holder = window as unknown as Record<string, unknown>;
    const pending = asTab(holder[PENDING_TAB]);
    holder[PENDING_TAB] = undefined;
    if (pending) {select(pending);}

    const onRequest = (event: Event) => {
      const tab = asTab((event as CustomEvent).detail?.tab);
      holder[PENDING_TAB] = undefined;
      if (tab) {select(tab);}
    };
    window.addEventListener(TAB_EVENT, onRequest);
    return () => window.removeEventListener(TAB_EVENT, onRequest);
  }, [select]);
}
