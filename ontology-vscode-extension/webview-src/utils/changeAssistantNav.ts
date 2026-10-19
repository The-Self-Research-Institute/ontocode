export const CHANGE_ASSISTANT_TAB_EVENT = 'change-assistant:show-tab';
export const CHANGE_ASSISTANT_PENDING_TAB = '__ontocodeChangeAssistantTab';

export type ChangeAssistantTabName = 'live' | 'drafts' | 'changes' | 'conflicts' | 'history' | 'stats';

export function requestChangeAssistantTab(tab: ChangeAssistantTabName): void {
  (window as unknown as Record<string, unknown>)[CHANGE_ASSISTANT_PENDING_TAB] = tab;
  window.dispatchEvent(new CustomEvent(CHANGE_ASSISTANT_TAB_EVENT, { detail: { tab } }));
}
