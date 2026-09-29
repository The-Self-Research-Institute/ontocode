import React from 'react';
import { OntologyChange } from './types';

function clip(value: string, limit: number): string {
  return value.length > limit ? value.substring(0, limit) + '...' : value;
}

const ModifiedValue: React.FC<{ draft: OntologyChange }> = ({ draft }) => (
  <div className="mt-2 p-2 bg-white rounded border border-yellow-200">
    <div className="text-xs text-gray-500 mb-1 font-medium">Value Change:</div>
    <div className="flex items-center gap-2 text-sm">
      {draft.oldValue && (
        <span className="px-2 py-1 bg-red-100 text-red-700 rounded line-through">
          {clip(draft.oldValue, 50)}
        </span>
      )}
      {draft.oldValue && draft.newValue && (
        <span className="text-gray-400">→</span>
      )}
      {draft.newValue && (
        <span className="px-2 py-1 bg-green-100 text-green-700 rounded font-medium">
          {clip(draft.newValue, 50)}
        </span>
      )}
    </div>
  </div>
);

const AddedValue: React.FC<{ value: string }> = ({ value }) => (
  <div className="mt-2 p-2 bg-white rounded border border-yellow-200">
    <div className="text-xs text-gray-500 mb-1 font-medium">New Value:</div>
    <span className="text-sm px-2 py-1 bg-green-100 text-green-700 rounded">
      {clip(value, 80)}
    </span>
  </div>
);

const DraftValues: React.FC<{ draft: OntologyChange }> = ({ draft }) => (
  <>
    {draft.action === 'modified' && (draft.oldValue || draft.newValue) && <ModifiedValue draft={draft} />}
    {draft.action === 'added' && draft.newValue && <AddedValue value={draft.newValue} />}
  </>
);

export default DraftValues;
