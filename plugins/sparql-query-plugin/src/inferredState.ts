export type InferenceMode = 'asserted' | 'inferred';

export interface InferenceStatus {
  available?: boolean;
  stale?: boolean;
  computedAt?: string | number;
  reasoner?: string;
  profile?: string;
  layers?: string[];
  tooLarge?: boolean;
  message?: string;
}

export interface EqualityInfo {
  groups?: number;
  largestGroup?: number;
  triplesAdded?: number;
  incomplete?: boolean;
}

export interface InferredInfo {
  used?: boolean;
  reasoner?: string;
  computedAt?: string | number;
  snapshotId?: string;
  scope?: string;
  profile?: string;
  layers?: string[];
  assertedTripleCount?: number;
  inferredTripleCount?: number;
  partial?: boolean;
  partialReasons?: string[];
  equality?: EqualityInfo;
}

export interface SwitchState {
  disabled: boolean;
  tooltip: string;
}

export type ConflictKind =
  | { kind: 'needsReasoning'; reason: string; message: string; computedAt?: string | number }
  | { kind: 'inconsistent'; message: string; explanation: string };

export const REQUIRED_INFO_PREFIX = 'Includes the documented, tested equality consequences';

export const INCLUDED_ITEMS = [
  'Class hierarchy',
  'Types of individuals',
  'Facts between individuals',
  'Facts with values',
  'Rule conclusions',
  'Same-as and different-from facts'
];

export const NOT_INCLUDED_ITEMS = [
  'Full OWL 2 DL entailment',
  'Rule results that conclude same-as'
];

const DEFAULT_TOOLTIP = 'Also search facts worked out by the reasoner';

export function storageKey(projectId: string): string {
  return `sparql.includeInferred.${projectId}`;
}

export function loadMode(projectId: string, storage?: Pick<Storage, 'getItem'> | null): InferenceMode {
  try {
    const store = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
    return store?.getItem(storageKey(projectId)) === 'inferred' ? 'inferred' : 'asserted';
  } catch {
    return 'asserted';
  }
}

export function saveMode(projectId: string, mode: InferenceMode, storage?: Pick<Storage, 'setItem'> | null): void {
  try {
    const store = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
    store?.setItem(storageKey(projectId), mode);
  } catch {
    return;
  }
}

export const COMPUTING_TEXT = 'Computing the inferred facts. This can take up to a minute.';

export function needsCompute(status: InferenceStatus | null | undefined): boolean {
  return !!status && status.available === false && !status.tooLarge;
}

export function switchState(status: InferenceStatus | null | undefined): SwitchState {
  if (!status) return { disabled: false, tooltip: DEFAULT_TOOLTIP };
  if (status.tooLarge) return { disabled: true, tooltip: 'Too large to include inferred facts' };
  if (status.available === false) {
    return {
      disabled: false,
      tooltip: status.stale
        ? 'The ontology changed since the inferred facts were computed. Running the query computes them again.'
        : 'Also search facts worked out by the reasoner. The first use computes them and can take up to a minute.'
    };
  }
  return { disabled: false, tooltip: DEFAULT_TOOLTIP };
}

export function effectiveMode(mode: InferenceMode, status: InferenceStatus | null | undefined): InferenceMode {
  return mode === 'inferred' && status?.tooLarge ? 'asserted' : mode;
}

export function toMillis(value: string | number | undefined | null): number | null {
  if (value === undefined || value === null || value === '') return null;
  const ms = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(ms) ? ms : null;
}

export function relativeTime(value: string | number | undefined | null, now: number = Date.now()): string {
  const ms = toMillis(value);
  if (ms === null) return 'at an unknown time';
  const seconds = Math.max(0, Math.round((now - ms) / 1000));
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? '1 day ago' : `${days} days ago`;
}

const LAYER_LABELS: Record<string, string> = {
  owl: 'class and instance reasoning',
  rules: 'rule conclusions',
  equality: 'same-as groups'
};

export function layersText(layers: string[] | undefined | null): string {
  if (!layers || layers.length === 0) return 'no extra layers listed';
  return layers.map(l => LAYER_LABELS[l] ?? l).join(', ');
}

export function infoLine(info: InferredInfo, now: number = Date.now()): string {
  return `${REQUIRED_INFO_PREFIX}. HermiT, computed ${relativeTime(info.computedAt, now)}, ${layersText(info.layers)}`;
}

const EQUALITY_INCOMPLETE_TEXT = 'Some equivalent-name facts were left out because a limit was reached';

const REASON_TEXT: Record<string, string> = {
  timeout: 'The reasoner stopped at its time limit, so some facts may be missing.',
  too_large: 'The ontology was too large to include every fact.',
  size_limit: 'A size limit was reached, so some facts may be missing.',
  triple_limit: 'A size limit was reached, so some facts may be missing.',
  rules_timeout: 'Rules ran out of time, so some rule conclusions may be missing.',
  rule_limit: 'A rule limit was reached, so some rule conclusions may be missing.',
  equality_limit: EQUALITY_INCOMPLETE_TEXT
};

export function partialReasonText(reason: string): string {
  const known = REASON_TEXT[reason];
  if (known) return known;
  const plain = reason.replace(/[_-]+/g, ' ').trim();
  return plain ? `Some facts may be missing: ${plain}.` : 'Some facts may be missing.';
}

export function partialMessages(info: InferredInfo | null | undefined): string[] {
  if (!info) return [];
  const out: string[] = [];
  for (const reason of info.partialReasons ?? []) {
    const text = partialReasonText(String(reason));
    if (!out.includes(text)) out.push(text);
  }
  if (info.equality?.incomplete && !out.includes(EQUALITY_INCOMPLETE_TEXT)) {
    out.push(EQUALITY_INCOMPLETE_TEXT);
  }
  if (info.partial && out.length === 0) out.push('Some facts may be missing from the inferred results.');
  return out;
}

export function isPartial(info: InferredInfo | null | undefined): boolean {
  return partialMessages(info).length > 0;
}

export function countsText(info: InferredInfo): string | null {
  const a = info.assertedTripleCount;
  const i = info.inferredTripleCount;
  if (typeof a !== 'number' && typeof i !== 'number') return null;
  const parts: string[] = [];
  if (typeof a === 'number') parts.push(`${a.toLocaleString('en-US')} stated`);
  if (typeof i === 'number') parts.push(`${i.toLocaleString('en-US')} inferred`);
  return `${parts.join(', ')} triples searched`;
}

const NEEDS_REASONING_TEXT: Record<string, string> = {
  none: 'The inferred facts have not been computed yet. Choose Run again with refresh to compute them.',
  stale: 'The ontology changed since the reasoner last ran, so inferred facts may be out of date.',
  too_large: 'This ontology is too large to include inferred facts. Query the asserted facts only.',
  timeout: 'The reasoner took too long and was stopped. Try again, or query the asserted facts only.',
  changed_during_capture: 'The ontology changed while the inferred facts were being collected. Run again.'
};

export function needsReasoningMessage(reason: string | undefined, message: string | undefined): string {
  if (message && message.trim()) return message;
  return NEEDS_REASONING_TEXT[reason ?? 'none'] ?? NEEDS_REASONING_TEXT.none;
}

export function canRefresh(reason: string): boolean {
  return reason !== 'too_large';
}

function asObject(value: unknown): Record<string, any> | null {
  if (value && typeof value === 'object') return value as Record<string, any>;
  if (typeof value === 'string') {
    const start = value.indexOf('{');
    if (start >= 0) {
      try {
        const parsed = JSON.parse(value.slice(start));
        return parsed && typeof parsed === 'object' ? parsed : null;
      } catch {
        return null;
      }
    }
  }
  return null;
}

export function mapConflict(source: unknown): ConflictKind | null {
  const direct = asObject(source);
  if (!direct) return null;
  const candidates = [
    direct,
    asObject(direct.body),
    asObject(direct.data),
    asObject(direct.response?.data),
    asObject(direct.response?.body),
    asObject(direct.details),
    asObject(direct.message)
  ];
  for (const body of candidates) {
    if (!body) continue;
    if (body.status === 'INCONSISTENT') {
      const message = typeof body.message === 'string' && body.message
        ? body.message
        : 'The ontology is inconsistent, so no inferred results can be given.';
      return { kind: 'inconsistent', message, explanation: typeof body.explanation === 'string' ? body.explanation : '' };
    }
    if (body.needsReasoning === true) {
      const reason = typeof body.reason === 'string' ? body.reason : 'none';
      return {
        kind: 'needsReasoning',
        reason,
        message: needsReasoningMessage(reason, typeof body.message === 'string' ? body.message : undefined),
        computedAt: body.computedAt
      };
    }
  }
  return null;
}

export function buildRequestBody(
  base: { query: string; format?: string },
  mode: InferenceMode,
  refresh: boolean
): Record<string, unknown> {
  if (mode !== 'inferred') return { ...base };
  const body: Record<string, unknown> = { ...base, includeInferred: true };
  if (refresh) body.refresh = true;
  return body;
}

export function statusUrl(projectId: string, draftMode: boolean): string {
  return `/api/sparql/inference/${projectId}/status${draftMode ? '?draft=true' : ''}`;
}

export function usedInferred(response: unknown): InferredInfo | null {
  const info = (response as any)?.inferred;
  if (!info || typeof info !== 'object' || info.used === false) return null;
  return info as InferredInfo;
}
