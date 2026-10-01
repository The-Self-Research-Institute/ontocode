import React from 'react';
import { Edit3, Lightbulb, Save, Users } from 'lucide-react';
import { ChangeStats } from './types';

const StatCards: React.FC<{ stats: ChangeStats }> = ({ stats }) => (
  <div className="grid grid-cols-4 gap-2 mb-4">
    <div className="bg-yellow-50 p-2 rounded border border-yellow-200">
      <div className="text-xs text-yellow-600 flex items-center gap-1">
        <Edit3 className="w-3 h-3" />
        Drafts
      </div>
      <div className="text-xl font-bold text-yellow-700">{stats.draftChanges}</div>
    </div>
    <div className="bg-blue-50 p-2 rounded">
      <div className="text-xs text-blue-600 flex items-center gap-1">
        <Save className="w-3 h-3" />
        Saved
      </div>
      <div className="text-xl font-bold text-blue-700">{stats.totalChanges}</div>
    </div>
    <div className="bg-purple-50 p-2 rounded">
      <div className="text-xs text-purple-600 flex items-center gap-1">
        <Users className="w-3 h-3" />
        Authors
      </div>
      <div className="text-xl font-bold text-purple-700">{stats.activeAuthors}</div>
    </div>
    <div className="bg-orange-50 p-2 rounded">
      <div className="text-xs text-orange-600 flex items-center gap-1">
        <Lightbulb className="w-3 h-3" />
        Warnings
      </div>
      <div className="text-xl font-bold text-orange-700">{stats.warnings}</div>
    </div>
  </div>
);

export default StatCards;
