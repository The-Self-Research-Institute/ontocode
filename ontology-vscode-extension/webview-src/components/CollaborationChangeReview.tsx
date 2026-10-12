import React from 'react';
import { Undo2, MessageSquare, ExternalLink, Loader2, X } from 'lucide-react';
import { OntologyChange } from '../services/changeTrackingService';

interface CollaborationChangeReviewProps {
  selectedChange: OntologyChange;
  actionLoading: string | null;
  subChangeLoading: string | null;
  modificationNote: string;
  setModificationNote: (note: string) => void;
  onClose: () => void;
  onNavigate: (change: OntologyChange) => void;
  onRollback: (change: OntologyChange) => void;
  onSubChangeRollback: (change: OntologyChange, subChangeId: string) => void;
  onRequestModification: (change: OntologyChange) => void;
}

export function formatTime(timestamp: string | number) {
  const date = new Date(timestamp);
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function isRollbackRecord(change: OntologyChange): boolean {
  return !!change.operationType && change.operationType.startsWith('ROLLBACK_');
}

export function changeActionIcon(changeType: string) {
  const t = changeType.toLowerCase();
  if (t.includes('add') || t.includes('create')) return '+ ';
  if (t.includes('delete') || t.includes('remove')) return '− ';
  if (t.includes('update') || t.includes('edit') || t.includes('rename') || t.includes('modify')) return '✎ ';
  return '• ';
}

export function changeActionColor(changeType: string) {
  const t = changeType.toLowerCase();
  if (t.includes('add') || t.includes('create')) return 'text-green-600';
  if (t.includes('delete') || t.includes('remove')) return 'text-red-600';
  if (t.includes('update') || t.includes('edit') || t.includes('rename') || t.includes('modify')) return 'text-blue-600';
  return 'text-amber-600';
}

export const CollaborationChangeReview: React.FC<CollaborationChangeReviewProps> = ({
  selectedChange,
  actionLoading,
  subChangeLoading,
  modificationNote,
  setModificationNote,
  onClose,
  onNavigate,
  onRollback,
  onSubChangeRollback,
  onRequestModification,
}) => (
  <div className="border-t border-gray-200 bg-gray-50 p-3 space-y-2">
    <div className="flex items-start justify-between gap-2">
      <div className="min-w-0">
        <div className="text-xs font-semibold text-gray-800 truncate">
          {selectedChange.entityLabel || 'Unknown entity'}
        </div>
        <div className="text-[10px] text-gray-500 font-mono truncate" title={selectedChange.entityIRI}>
          {selectedChange.entityIRI || 'No IRI'}
        </div>
        <div className="mt-1 flex items-center gap-1 flex-wrap">
          <span className="text-[10px] text-gray-500">
            by {selectedChange.username} · {formatTime(selectedChange.timestamp)}
          </span>
        </div>
      </div>
      <button
        onClick={() => onClose()}
        className="text-gray-400 hover:text-gray-600 p-0.5"
        title="Close"
      >
        <X size={14} />
      </button>
    </div>

    <div className="text-xs text-gray-700 bg-white rounded border p-2">
      <span className="font-medium">{selectedChange.changeType}</span>
      {selectedChange.oldValue && selectedChange.newValue && (
        <div className="mt-1 text-[10px]">
          <span className="text-red-600 line-through">{selectedChange.oldValue}</span>
          {' → '}
          <span className="text-green-600">{selectedChange.newValue}</span>
        </div>
      )}
    </div>

    <div className="flex flex-wrap gap-1">
      {selectedChange.entityIRI && (
        <button
          onClick={() => onNavigate(selectedChange)}
          className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium bg-purple-600 text-white rounded hover:bg-purple-700"
        >
          <ExternalLink size={10} /> Go to entity
        </button>
      )}
      <button
        onClick={() => onRollback(selectedChange)}
        disabled={!!actionLoading || !selectedChange.entityIRI || selectedChange.reverted || isRollbackRecord(selectedChange)}
        title={isRollbackRecord(selectedChange) ? "Rollback records can't be rolled back" : (selectedChange.reverted ? 'Already reverted' : undefined)}
        className="flex items-center gap-1 px-2 py-1 text-[10px] font-medium border border-orange-500 text-orange-600 rounded hover:bg-orange-50 disabled:opacity-50"
      >
        {actionLoading === 'rollback' ? <Loader2 size={10} className="animate-spin" /> : <Undo2 size={10} />}
        Rollback
      </button>
    </div>

    {selectedChange.reverted && (
      <div className="text-[10px] text-gray-500">
        Reverted{selectedChange.revertedBy ? ` by ${selectedChange.revertedBy}` : ''}
        {selectedChange.revertedAt ? ` · ${formatTime(selectedChange.revertedAt)}` : ''}
      </div>
    )}

    {selectedChange.subChanges && selectedChange.subChanges.length > 0 && (
      <div className="space-y-1 border-t border-gray-200 pt-2">
        <label className="text-[10px] font-medium text-gray-600">
          Sub-changes ({selectedChange.subChanges.length})
        </label>
        {selectedChange.subChanges.map((sc) => (
          <div key={sc.id} className="flex items-center justify-between gap-2 bg-white rounded border p-1.5">
            <div className="min-w-0 text-[10px] text-gray-700 truncate">
              {sc.predicate || sc.annotationProperty || 'sub-change'}
              <span className={sc.addition ? 'text-green-600' : 'text-red-600'}> · {sc.addition ? 'added' : 'removed'}</span>
              {sc.reverted && (
                <span className="text-gray-400">
                  {' '}· reverted{sc.revertedBy ? ` by ${sc.revertedBy}` : ''}
                </span>
              )}
            </div>
            <button
              onClick={() => onSubChangeRollback(selectedChange, sc.id)}
              disabled={subChangeLoading === sc.id || sc.reverted || selectedChange.reverted}
              title={(sc.reverted || selectedChange.reverted) ? 'Already reverted' : 'Rollback this sub-change'}
              className="flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-medium border border-orange-500 text-orange-600 rounded hover:bg-orange-50 disabled:opacity-50 flex-shrink-0"
            >
              {subChangeLoading === sc.id ? <Loader2 size={9} className="animate-spin" /> : <Undo2 size={9} />}
              Rollback
            </button>
          </div>
        ))}
      </div>
    )}

    <div className="space-y-1">
      <label className="text-[10px] font-medium text-gray-600 flex items-center gap-1">
        <MessageSquare size={10} /> Request modification
      </label>
      <textarea
        value={modificationNote}
        onChange={(e) => setModificationNote(e.target.value)}
        placeholder="Describe what should be changed..."
        className="w-full text-xs border rounded p-1.5 resize-none h-14 bg-white"
      />
      <button
        onClick={() => onRequestModification(selectedChange)}
        disabled={!!actionLoading || !modificationNote.trim()}
        className="w-full px-2 py-1 text-[10px] font-medium bg-amber-100 text-amber-800 rounded hover:bg-amber-200 disabled:opacity-50"
      >
        Send modification request
      </button>
    </div>
  </div>
);
