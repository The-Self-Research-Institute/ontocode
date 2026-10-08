import { AiInfo, ChangeSource, SubChange } from '../changeSets/types';

export type ChangeType = 'class' | 'property' | 'individual' | 'axiom' | 'annotation' | 'import';
export type ChangeAction = 'added' | 'deleted' | 'modified';
export type ChangeStatus = 'saved' | 'draft' | 'reverted' | 'conflicted';
export type AssistantTab = 'live' | 'drafts' | 'changes' | 'conflicts' | 'history' | 'stats';

export interface OntologyChange {
  id: string;
  timestamp: Date;
  author: string;
  authorEmail: string;
  type: ChangeType;
  action: ChangeAction;
  status: ChangeStatus;
  entityUri: string;
  entityLabel: string;
  oldValue?: string;
  newValue?: string;
  description: string;
  commitId?: string;
  branch?: string;
  comments: ChangeComment[];
  conflicts?: ConflictInfo[];
  warnings?: ChangeWarning[];
  commentCount?: number;
  operationType?: string;
  subChanges?: SubChange[];
  reverted?: boolean;
  revertedBy?: string;
  revertedAt?: string;
  changeSetId?: string | null;
  source?: ChangeSource | null;
  ai?: AiInfo | null;
  revertsChangeSetId?: string | null;
  revertedAuditId?: string;
  revertedWithSet?: boolean;
  rollbackAuditId?: string | null;
}

export interface ChangeComment {
  id: string;
  author: string;
  timestamp: Date;
  text: string;
  resolved: boolean;
}

export interface ConflictInfo {
  conflictType: 'concurrent_edit' | 'dependency' | 'constraint_violation';
  description: string;
  conflictingChangeId?: string;
  suggestedResolution?: string;
}

export interface ChangeWarning {
  type: 'best_practice' | 'consistency' | 'naming' | 'structure';
  severity: 'info' | 'warning' | 'error';
  message: string;
  suggestion?: string;
}

export interface ChangeStats {
  totalChanges: number;
  draftChanges: number;
  conflicts: number;
  activeAuthors: number;
  warnings: number;
}

export interface LiveActivity {
  id: string;
  userId: string;
  username: string;
  action: string;
  entityLabel: string;
  timestamp: Date;
  isCurrentUser: boolean;
}

export interface AssistantNotification {
  show: boolean;
  type: 'success' | 'error' | 'info';
  message: string;
}

export const EMPTY_STATS: ChangeStats = {
  totalChanges: 0,
  draftChanges: 0,
  conflicts: 0,
  activeAuthors: 0,
  warnings: 0
};
