import { Direction, OperationItem, OperationOutcome } from './types';
import { predicateLabel } from './predicateLabels';
import { plural } from './timeFormat';

export interface PreviewSummary {
  appliedCount: number;
  headline: string;
  skippedTitle: string | null;
  skippedLines: string[];
  canApply: boolean;
}

function verb(direction: Direction): string {
  return direction === 'REDO' ? 'redone' : 'undone';
}

function lowerFirst(text: string): string {
  return text ? text.charAt(0).toLowerCase() + text.slice(1) : text;
}

export function skippedLine(item: OperationItem): string {
  const reason = item.reason ? lowerFirst(item.reason) : 'it could not be changed';
  const entity = item.entityLabel;
  const subject = item.predicate ? `${predicateLabel(item.predicate)}${entity ? ` of ${entity}` : ''}` : entity;
  return subject ? `${subject}: ${reason}` : reason.charAt(0).toUpperCase() + reason.slice(1);
}

export function summarizePreview(outcome: OperationOutcome, direction: Direction): PreviewSummary {
  const applied = outcome.data?.applied || [];
  const skipped = outcome.data?.skipped || [];
  const nothing = direction === 'REDO' ? 'Nothing can be redone' : 'Nothing can be undone';
  const already = direction === 'REDO' ? 'This is already applied' : 'This was already undone';
  const headline = applied.length > 0
    ? `${plural(applied.length, 'change', 'changes')} will be ${verb(direction)}`
    : outcome.data?.alreadyReverted ? already : nothing;
  return {
    appliedCount: applied.length,
    headline,
    skippedTitle: skipped.length > 0 ? `${skipped.length} skipped` : null,
    skippedLines: skipped.map(skippedLine),
    canApply: applied.length > 0
  };
}

export function confirmLabel(direction: Direction, count: number): string {
  return `${direction === 'REDO' ? 'Redo' : 'Undo'} ${plural(count, 'change', 'changes')}`;
}

export function errorMessage(outcome: OperationOutcome, direction: Direction): string | null {
  const { status, data } = outcome;
  if (status === 0) return "Couldn't reach the server. Check your connection and try again.";
  if (status === 403) return "You don't have permission to change this.";
  if (status === 404) return 'This change no longer exists. Refresh the list and try again.';
  if (status === 409) return direction === 'REDO' ? 'Nothing could be redone.' : 'Nothing could be undone.';
  if (status >= 400 || !data) return 'Something went wrong. Try again.';
  return null;
}
