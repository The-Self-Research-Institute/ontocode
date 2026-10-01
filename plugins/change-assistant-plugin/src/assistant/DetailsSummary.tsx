import React from 'react';
import { Clock, GitBranch } from 'lucide-react';
import { getChangeIcon } from './display';

function actionClass(details: any): string {
  const action = (details.operationType || details.action || '').toLowerCase();
  return action.includes('add') ? 'text-green-600' :
    action.includes('delete') ? 'text-red-600' :
    'text-blue-600';
}

function statusClass(status: string): string {
  return status === 'APPROVED' ? 'bg-green-100 text-green-700' :
    status === 'REJECTED' ? 'bg-red-100 text-red-700' :
    status === 'PENDING' ? 'bg-yellow-100 text-yellow-700' :
    'bg-gray-100 text-gray-700';
}

const InfoGrid: React.FC<{ details: any }> = ({ details }) => (
  <div className="grid grid-cols-2 gap-2 text-sm">
    <div>
      <span className="text-gray-500">Action:</span>
      <span className={`ml-2 capitalize ${actionClass(details)}`}>
        {details.operationType || details.action || 'Unknown'}
      </span>
    </div>
    <div>
      <span className="text-gray-500">Type:</span>
      <span className="ml-2 capitalize">{details.entityType || details.type || 'Unknown'}</span>
    </div>
    <div>
      <span className="text-gray-500">Author:</span>
      <span className="ml-2">{details.username || details.author || 'Unknown'}</span>
    </div>
    <div>
      <span className="text-gray-500">Status:</span>
      <span className={`ml-2 px-1.5 py-0.5 text-xs rounded ${statusClass(details.status)}`}>
        {details.status || 'approved'}
      </span>
    </div>
  </div>
);

export const ChangeInfo: React.FC<{ details: any }> = ({ details }) => (
  <div className="bg-gray-50 rounded-lg p-3 space-y-2">
    <div className="flex items-center gap-2">
      <span className="text-2xl">{getChangeIcon(details.entityType?.toLowerCase() || details.type || 'axiom')}</span>
      <div>
        <p className="font-medium">{details.entityLabel || 'Unknown Entity'}</p>
        <p className="text-xs text-gray-500 font-mono truncate max-w-[350px]" title={details.entityIRI}>
          {details.entityIRI}
        </p>
      </div>
    </div>
    <InfoGrid details={details} />
    <div className="text-xs text-gray-500 pt-1 border-t">
      <Clock className="w-3 h-3 inline mr-1" />
      {details.timestamp ? new Date(details.timestamp).toLocaleString() : 'Unknown time'}
    </div>
  </div>
);

export const ValueChanges: React.FC<{ details: any }> = ({ details }) => (
  <div className="bg-gray-50 rounded-lg p-3">
    <h4 className="font-medium text-sm mb-2 flex items-center gap-1">
      <GitBranch className="w-4 h-4" />
      Value Changes
    </h4>
    <div className="space-y-2">
      {details.oldValue && (
        <div>
          <span className="text-xs text-red-500 font-medium">Before:</span>
          <div className="mt-1 px-2 py-1 bg-red-50 text-red-700 rounded text-sm font-mono border border-red-200 break-all">
            {details.oldValue}
          </div>
        </div>
      )}
      {details.newValue && (
        <div>
          <span className="text-xs text-green-500 font-medium">After:</span>
          <div className="mt-1 px-2 py-1 bg-green-50 text-green-700 rounded text-sm font-mono border border-green-200 break-all">
            {details.newValue}
          </div>
        </div>
      )}
    </div>
  </div>
);
