export type ChangeSource = 'AI' | 'MANUAL' | 'ROLLBACK';
export type SourceFilter = 'all' | 'AI' | 'MANUAL';
export type Direction = 'UNDO' | 'REDO';

export interface AiInfo {
  provider?: string;
  model?: string;
  sessionId?: string;
  groupId?: string;
  summary?: string;
}

export interface SubChange {
  id: string;
  predicate?: string;
  oldValue?: string;
  newValue?: string;
  annotationProperty?: string;
  addition: boolean;
  reverted?: boolean;
  revertedBy?: string;
  revertedAt?: string;
  revertedAuditId?: string;
  revertedWithSet?: boolean;
}

export interface ChangeEntry {
  id: string;
  timestamp: Date;
  author: string;
  type: string;
  action: string;
  entityUri: string;
  entityLabel: string;
  description: string;
  operationType?: string;
  oldValue?: string;
  newValue?: string;
  subChanges?: SubChange[];
  reverted?: boolean;
  revertedBy?: string;
  revertedAt?: string;
  commentCount?: number;
  conflicts?: unknown[];
  changeSetId?: string | null;
  source?: ChangeSource | null;
  ai?: AiInfo | null;
  revertsChangeSetId?: string | null;
  revertedAuditId?: string;
  revertedWithSet?: boolean;
  rollbackAuditId?: string | null;
}

export interface PropertyRow {
  key: string;
  changeId: string;
  subChangeId: string | null;
  label: string;
  value: string;
  rawValue: string;
  addition: boolean;
  undone: boolean;
  undoneBy?: string;
  undoneAt?: Date;
  redoAuditId?: string;
}

export interface EntityRow {
  entry: ChangeEntry;
  undoable: boolean;
  label: string;
  badge: string;
  properties: PropertyRow[];
  undone: boolean;
  undoneBy?: string;
  undoneAt?: Date;
  redoAuditId?: string;
  remaining: number;
}

export interface RollbackEvent {
  id: string;
  direction: Direction;
  author: string;
  timestamp: Date;
  description: string;
  targetChangeSetId: string | null;
  auditId: string | null;
  targetTitle?: string;
  entityUri?: string;
}

export interface SetHeading {
  verb: string;
  name: string;
  detail?: string;
}

export interface ChangeSet {
  key: string;
  changeSetId: string | null;
  source: 'AI' | 'MANUAL';
  ai: AiInfo | null;
  entries: ChangeEntry[];
  entities: EntityRow[];
  timestamp: Date;
  author: string;
  title: string;
  heading?: SetHeading;
  entityCount: number;
  changeCount: number;
  remaining: number;
  fullyUndone: boolean;
  undoneBy?: string;
  undoneAt?: Date;
  history: RollbackEvent[];
}

export type ListItem =
  | { kind: 'set'; key: string; timestamp: Date; set: ChangeSet }
  | { kind: 'rollback'; key: string; timestamp: Date; event: RollbackEvent };

export interface DaySection {
  label: string;
  items: ListItem[];
}

export interface EventContext {
  entityIRI?: string;
  entityLabel?: string;
  entityType?: string;
  action?: string;
  originalAuthor?: string;
  oldValue?: string;
  newValue?: string;
}

export type OperationTarget =
  | { kind: 'set'; direction: Direction; changeSetId: string; label: string; context: EventContext }
  | { kind: 'entry'; direction: 'UNDO'; changeId: string; label: string; context: EventContext }
  | { kind: 'property'; direction: 'UNDO'; changeId: string; subChangeId: string; label: string; context: EventContext }
  | { kind: 'undo'; direction: 'REDO'; auditId: string; label: string; context: EventContext };

export interface OperationItem {
  changeId?: string;
  subChangeId?: string;
  entityIRI?: string;
  entityLabel?: string;
  predicate?: string;
  reason?: string;
}

export interface OperationResponse {
  success: boolean;
  alreadyReverted?: boolean;
  dryRun?: boolean;
  direction?: Direction;
  applied: OperationItem[];
  skipped: OperationItem[];
  auditId?: string;
  message?: string;
  error?: string;
}

export interface OperationOutcome {
  status: number;
  data: OperationResponse | null;
}
