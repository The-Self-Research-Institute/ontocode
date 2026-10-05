import { ChangeAction, ChangeType } from './types';

export function getRelativeTime(date: Date): string {
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHour = Math.floor(diffMin / 60);
  const diffDay = Math.floor(diffHour / 24);
  if (diffSec < 60) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  if (diffHour < 24) return `${diffHour}h ago`;
  if (diffDay < 7) return `${diffDay}d ago`;
  return date.toLocaleDateString();
}

export function getChangeIcon(type: ChangeType) {
  switch (type) {
    case 'class': return '🔷';
    case 'property': return '🔗';
    case 'individual': return '👤';
    case 'axiom': return '📐';
    case 'annotation': return '📝';
    case 'import': return '📦';
    default: return '📄';
  }
}

export function getActionColor(action: ChangeAction) {
  switch (action) {
    case 'added': return 'text-green-600';
    case 'deleted': return 'text-red-600';
    case 'modified': return 'text-blue-600';
  }
}

export function humanizeAction(raw: string): string {
  const words = /[a-z]/.test(raw) ? raw.replace(/([a-z])([A-Z])/g, '$1 $2') : raw.replace(/_/g, ' ');
  const text = words.toLowerCase().trim();
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : 'Modified';
}

export function describeLive(detail: any): { action: string; entityLabel: string } {
  if (detail.type === 'CHANGE_SET_APPLIED') {
    return { action: detail.description || 'Saved changes', entityLabel: '' };
  }
  if (detail.type === 'ROLLBACK') {
    const fallback = detail.direction === 'REDO' ? 'Redid a change' : 'Undid a change';
    return { action: detail.description || fallback, entityLabel: detail.description ? '' : detail.entityLabel || '' };
  }
  return { action: humanizeAction(detail.type || 'modified'), entityLabel: detail.entityLabel || detail.iri || 'Unknown entity' };
}
