import React from 'react';
import { Activity, Zap } from 'lucide-react';
import { getRelativeTime } from './display';
import { LiveActivity } from './types';

const ActivityRow: React.FC<{ activity: LiveActivity }> = ({ activity }) => (
  <div
    className={`flex items-start gap-3 p-3 rounded-lg border ${
      activity.isCurrentUser ? 'bg-purple-50 border-purple-200' : 'bg-gray-50'
    }`}
  >
    <div className={`w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-medium ${
      activity.isCurrentUser ? 'bg-purple-500' : 'bg-blue-500'
    }`}>
      {activity.username[0].toUpperCase()}
    </div>
    <div className="flex-1">
      <div className="flex items-center gap-2">
        <span className="font-medium text-sm">{activity.username}</span>
        <span className="text-xs text-gray-500">{getRelativeTime(activity.timestamp)}</span>
      </div>
      <p className="text-sm text-gray-700">
        <span>{activity.action}</span>
        {activity.entityLabel && <>{' '}<span className="font-medium">{activity.entityLabel}</span></>}
      </p>
    </div>
    <Zap className="w-4 h-4 text-yellow-500" />
  </div>
);

const LiveActivityTab: React.FC<{ activities: LiveActivity[] }> = ({ activities }) => (
  <div className="space-y-3">
    <div className="flex items-center gap-2 mb-4">
      <Activity className="w-5 h-5 text-green-500" />
      <h3 className="font-medium">Activity</h3>
      <span className="text-xs text-gray-500">Real-time updates from you and collaborators</span>
    </div>
    {activities.length === 0 ? (
      <div className="text-center py-12 text-gray-500">
        <Activity className="w-12 h-12 mx-auto mb-3 opacity-30" />
        <p className="font-medium">No recent activity</p>
        <p className="text-sm mt-1">Your changes and your collaborators' changes appear here as they happen</p>
      </div>
    ) : (
      <div className="space-y-2">
        {activities.map(activity => <ActivityRow key={activity.id} activity={activity} />)}
      </div>
    )}
  </div>
);

export default LiveActivityTab;
