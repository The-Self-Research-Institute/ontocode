import React from 'react';
import { AlertTriangle, CheckCircle, GitMerge } from 'lucide-react';
import { OntologyChange } from './types';

interface ConflictActions {
  onResolveClick: (change: OntologyChange) => void;
  onAutoResolve: (changeId: string, resolution: string) => void;
}

const ConflictCard: React.FC<ConflictActions & { change: OntologyChange }> = ({ change, onResolveClick, onAutoResolve }) => (
  <div className="border border-orange-300 rounded-lg p-3 bg-orange-50">
    <div className="flex items-center gap-2 mb-2">
      <AlertTriangle className="w-5 h-5 text-orange-600" />
      <h3 className="font-medium text-orange-900">{change.entityLabel}</h3>
    </div>
    <p className="text-sm text-orange-700 mb-2">{change.description}</p>
    {change.conflicts?.map((conflict, idx) => (
      <div key={idx} className="bg-white p-2 rounded border border-orange-200 mb-2">
        <div className="text-sm font-medium text-orange-800 mb-1">
          {conflict.conflictType.replace(/_/g, ' ').toUpperCase()}
        </div>
        <p className="text-sm text-gray-700 mb-2">{conflict.description}</p>
        <div className="flex gap-2">
          <button
            onClick={() => onResolveClick(change)}
            className="px-3 py-1 text-sm bg-orange-600 text-white rounded hover:bg-orange-700 flex items-center gap-1"
          >
            <GitMerge className="w-3 h-3" />
            Resolve Conflict
          </button>
          {conflict.suggestedResolution && (
            <button
              onClick={() => onAutoResolve(change.id, conflict.suggestedResolution!)}
              className="px-3 py-1 text-sm border border-orange-600 text-orange-600 rounded hover:bg-orange-50"
            >
              Auto-Resolve
            </button>
          )}
        </div>
      </div>
    ))}
  </div>
);

const ConflictsTab: React.FC<ConflictActions & { changes: OntologyChange[] }> = ({ changes, onResolveClick, onAutoResolve }) => (
  <div className="space-y-2">
    {changes.filter(c => c.status === 'conflicted').length === 0 ? (
      <div className="text-center py-12 text-gray-500">
        <CheckCircle className="w-12 h-12 mx-auto mb-3 text-green-500 opacity-50" />
        <p className="font-medium">No conflicts detected</p>
        <p className="text-sm mt-1">All changes are synchronized</p>
      </div>
    ) : (
      changes.filter(c => c.status === 'conflicted').map(change => (
        <ConflictCard key={change.id} change={change} onResolveClick={onResolveClick} onAutoResolve={onAutoResolve} />
      ))
    )}
  </div>
);

export default ConflictsTab;
