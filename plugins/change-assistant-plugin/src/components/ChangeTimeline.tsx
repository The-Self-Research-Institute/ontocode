import React from 'react';
import { Clock, User, GitCommit, AlertTriangle } from 'lucide-react';

interface TimelineChange {
  id: string;
  timestamp: Date;
  author: string;
  type: string;
  action: string;
  entityLabel: string;
  description: string;
  status: string;
  hasConflict: boolean;
}

interface ChangeTimelineProps {
  changes: TimelineChange[];
  onSelectChange: (changeId: string) => void;
}

const ChangeTimeline: React.FC<ChangeTimelineProps> = ({ changes, onSelectChange }) => {
  const groupedByDate = changes.reduce((acc, change) => {
    const date = change.timestamp.toLocaleDateString();
    if (!acc[date]) acc[date] = [];
    acc[date].push(change);
    return acc;
  }, {} as Record<string, TimelineChange[]>);

  const getActionColor = (action: string) => {
    switch (action) {
      case 'added': return 'bg-green-500';
      case 'deleted': return 'bg-red-500';
      case 'modified': return 'bg-blue-500';
      default: return 'bg-gray-500';
    }
  };

  return (
    <div className="relative">
      {Object.entries(groupedByDate).map(([date, dateChanges]) => (
        <div key={date} className="mb-6">
          <div className="flex items-center gap-2 mb-3 sticky top-0 bg-white py-2 z-10">
            <Clock className="w-4 h-4 text-gray-500" />
            <span className="text-sm font-semibold text-gray-700">{date}</span>
            <div className="flex-1 h-px bg-gray-200"></div>
          </div>
          
          <div className="relative pl-8">
            <div className="absolute left-3 top-0 bottom-0 w-0.5 bg-gray-200"></div>
            
            {dateChanges.map((change, idx) => (
              <div
                key={change.id}
                onClick={() => onSelectChange(change.id)}
                className="relative mb-4 cursor-pointer hover:bg-gray-50 rounded-lg p-3 transition-colors"
              >
                <div className={`absolute left-[-1.4rem] w-4 h-4 rounded-full border-2 border-white ${getActionColor(change.action)}`}></div>
                
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-medium text-sm">{change.entityLabel}</span>
                      <span className="text-xs text-gray-500">•</span>
                      <span className="text-xs text-gray-500 capitalize">{change.action}</span>
                      {change.hasConflict && (
                        <AlertTriangle className="w-3 h-3 text-orange-500" />
                      )}
                    </div>
                    <p className="text-xs text-gray-600 mb-2">{change.description}</p>
                    <div className="flex items-center gap-3 text-xs text-gray-500">
                      <span className="flex items-center gap-1">
                        <User className="w-3 h-3" />
                        {change.author}
                      </span>
                      <span>{change.timestamp.toLocaleTimeString()}</span>
                    </div>
                  </div>
                  <GitCommit className="w-4 h-4 text-gray-400 flex-shrink-0" />
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
};

export default ChangeTimeline;
