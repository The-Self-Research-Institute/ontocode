import React from 'react';
import { CheckCircle, Edit3, Info } from 'lucide-react';
import { getActionColor, getChangeIcon, getRelativeTime } from './display';
import DraftValues from './DraftValues';
import DraftWarningList from './DraftWarningList';
import { OntologyChange } from './types';

const DraftCard: React.FC<{ draft: OntologyChange }> = ({ draft }) => (
  <div className="border border-yellow-200 rounded-lg p-3 bg-yellow-50">
    <div className="flex items-start gap-2">
      <span className="text-2xl">{getChangeIcon(draft.type)}</span>
      <div className="flex-1">
        <div className="flex items-center gap-2">
          <span className="font-medium">{draft.entityLabel}</span>
          <span className={`text-sm ${getActionColor(draft.action)}`}>
            {draft.action}
          </span>
          <span className="px-1.5 py-0.5 text-xs bg-yellow-200 text-yellow-700 rounded">
            draft
          </span>
        </div>
        <p className="text-sm text-gray-600 mt-1">{draft.description}</p>
        <DraftValues draft={draft} />
        <div className="text-xs text-gray-500 mt-2">
          {getRelativeTime(draft.timestamp)}
        </div>
      </div>
    </div>
    {draft.warnings && draft.warnings.length > 0 && <DraftWarningList warnings={draft.warnings} />}
  </div>
);

const DraftList: React.FC<{ drafts: OntologyChange[] }> = ({ drafts }) => (
  <div className="space-y-2">
    {drafts.map(draft => <DraftCard key={draft.id} draft={draft} />)}
    <div className="mt-4 p-3 bg-gray-50 rounded-lg border border-gray-200">
      <div className="flex items-center gap-2 text-sm text-gray-600">
        <Info className="w-4 h-4" />
        <span>Click <strong>Save</strong> in the editor to commit these changes to the database</span>
      </div>
    </div>
  </div>
);

const DraftsTab: React.FC<{ drafts: OntologyChange[] }> = ({ drafts }) => (
  <div className="space-y-3">
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-2">
        <Edit3 className="w-5 h-5 text-yellow-500" />
        <h3 className="font-medium">Pending Drafts</h3>
        <span className="text-xs text-gray-500">Changes not yet saved to database</span>
      </div>
      {drafts.length > 0 && (
        <span className="px-2 py-1 bg-yellow-100 text-yellow-700 text-xs rounded-full">
          {drafts.length} unsaved
        </span>
      )}
    </div>
    {drafts.length === 0 ? (
      <div className="text-center py-12 text-gray-500">
        <CheckCircle className="w-12 h-12 mx-auto mb-3 text-green-500 opacity-50" />
        <p className="font-medium">No pending drafts</p>
        <p className="text-sm mt-1">All changes have been saved</p>
      </div>
    ) : (
      <DraftList drafts={drafts} />
    )}
  </div>
);

export default DraftsTab;
