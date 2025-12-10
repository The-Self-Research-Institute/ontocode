import React, { useState } from 'react';
import { AlertTriangle, CheckCircle, XCircle, ArrowRight } from 'lucide-react';

interface Conflict {
  id: string;
  type: 'concurrent_edit' | 'dependency' | 'constraint_violation';
  description: string;
  localChange: string;
  remoteChange: string;
  baseValue?: string;
}

interface ConflictResolverProps {
  conflict: Conflict;
  onResolve: (resolution: 'accept_local' | 'accept_remote' | 'merge') => void;
  onCancel: () => void;
}

const ConflictResolver: React.FC<ConflictResolverProps> = ({ conflict, onResolve, onCancel }) => {
  const [selectedResolution, setSelectedResolution] = useState<'accept_local' | 'accept_remote' | 'merge' | null>(null);
  const [mergedValue, setMergedValue] = useState('');

  const getConflictTypeLabel = (type: string) => {
    switch (type) {
      case 'concurrent_edit': return 'Concurrent Edit Conflict';
      case 'dependency': return 'Dependency Conflict';
      case 'constraint_violation': return 'Constraint Violation';
      default: return 'Conflict';
    }
  };

  const handleResolve = () => {
    if (selectedResolution) {
      onResolve(selectedResolution);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="border-b p-4 bg-orange-50">
          <div className="flex items-center gap-3">
            <AlertTriangle className="w-6 h-6 text-orange-600" />
            <div>
              <h2 className="text-lg font-semibold text-orange-900">
                {getConflictTypeLabel(conflict.type)}
              </h2>
              <p className="text-sm text-orange-700 mt-1">{conflict.description}</p>
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 space-y-6">
          {/* Base Value (if available) */}
          {conflict.baseValue && (
            <div className="bg-gray-50 rounded-lg p-4">
              <div className="text-sm font-semibold text-gray-700 mb-2">Original Value</div>
              <div className="font-mono text-sm text-gray-800 bg-white p-3 rounded border">
                {conflict.baseValue}
              </div>
            </div>
          )}

          {/* Conflict Options */}
          <div className="grid grid-cols-2 gap-4">
            {/* Local Change */}
            <div
              onClick={() => setSelectedResolution('accept_local')}
              className={`border-2 rounded-lg p-4 cursor-pointer transition-all ${
                selectedResolution === 'accept_local'
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 hover:border-blue-300'
              }`}
            >
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-full bg-blue-100 flex items-center justify-center">
                    <span className="text-blue-600 font-semibold">L</span>
                  </div>
                  <span className="font-semibold text-gray-900">Your Change</span>
                </div>
                {selectedResolution === 'accept_local' && (
                  <CheckCircle className="w-5 h-5 text-blue-600" />
                )}
              </div>
              <div className="font-mono text-sm text-gray-800 bg-white p-3 rounded border break-all">
                {conflict.localChange}
              </div>
            </div>

            {/* Remote Change */}
            <div
              onClick={() => setSelectedResolution('accept_remote')}
              className={`border-2 rounded-lg p-4 cursor-pointer transition-all ${
                selectedResolution === 'accept_remote'
                  ? 'border-green-500 bg-green-50'
                  : 'border-gray-200 hover:border-green-300'
              }`}
            >
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center">
                    <span className="text-green-600 font-semibold">R</span>
                  </div>
                  <span className="font-semibold text-gray-900">Their Change</span>
                </div>
                {selectedResolution === 'accept_remote' && (
                  <CheckCircle className="w-5 h-5 text-green-600" />
                )}
              </div>
              <div className="font-mono text-sm text-gray-800 bg-white p-3 rounded border break-all">
                {conflict.remoteChange}
              </div>
            </div>
          </div>

          {/* Manual Merge Option */}
          <div
            onClick={() => setSelectedResolution('merge')}
            className={`border-2 rounded-lg p-4 cursor-pointer transition-all ${
              selectedResolution === 'merge'
                ? 'border-purple-500 bg-purple-50'
                : 'border-gray-200 hover:border-purple-300'
            }`}
          >
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 rounded-full bg-purple-100 flex items-center justify-center">
                  <ArrowRight className="w-4 h-4 text-purple-600" />
                </div>
                <span className="font-semibold text-gray-900">Manual Merge</span>
              </div>
              {selectedResolution === 'merge' && (
                <CheckCircle className="w-5 h-5 text-purple-600" />
              )}
            </div>
            <textarea
              value={mergedValue}
              onChange={(e) => setMergedValue(e.target.value)}
              placeholder="Enter your merged value here..."
              className="w-full font-mono text-sm p-3 border rounded resize-none"
              rows={4}
              disabled={selectedResolution !== 'merge'}
            />
          </div>

          {/* Conflict Resolution Tips */}
          <div className="bg-blue-50 rounded-lg p-4">
            <h4 className="text-sm font-semibold text-blue-900 mb-2">Resolution Tips</h4>
            <ul className="text-sm text-blue-800 space-y-1 list-disc list-inside">
              <li>Review both changes carefully before deciding</li>
              <li>Consider the semantic meaning and impact on the ontology</li>
              <li>Use manual merge to combine both changes if appropriate</li>
              <li>Consult with team members if unsure</li>
            </ul>
          </div>
        </div>

        {/* Footer */}
        <div className="border-t p-4 bg-gray-50 flex justify-end gap-3">
          <button
            onClick={onCancel}
            className="px-4 py-2 border border-gray-300 rounded hover:bg-gray-100 flex items-center gap-2"
          >
            <XCircle className="w-4 h-4" />
            Cancel
          </button>
          <button
            onClick={handleResolve}
            disabled={!selectedResolution}
            className={`px-4 py-2 rounded flex items-center gap-2 ${
              selectedResolution
                ? 'bg-green-600 text-white hover:bg-green-700'
                : 'bg-gray-300 text-gray-500 cursor-not-allowed'
            }`}
          >
            <CheckCircle className="w-4 h-4" />
            Resolve Conflict
          </button>
        </div>
      </div>
    </div>
  );
};

export default ConflictResolver;
