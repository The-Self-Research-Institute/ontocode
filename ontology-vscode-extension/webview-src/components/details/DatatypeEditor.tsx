import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Search } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import { DatatypeDefinitionDialog } from '../dialogs';
import apiClient from '../../services/apiClient';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { Datatype } from '../../types';

interface UsageItem {
  type: string;
  subject: string;
  subjectLabel?: string;
  predicate?: string;
  object?: string;
  context?: string;
}

const UsageTab: React.FC<{
  datatypeIri: string;
  projectId: string;
  label: string;
}> = ({ datatypeIri, projectId, label }) => {
  const [loading, setLoading] = useState(true);
  const [usages, setUsages] = useState<UsageItem[]>([]);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    loadUsages();
  }, [datatypeIri, projectId]);

  const loadUsages = async () => {
    setLoading(true);
    try {
      const response = await apiClient.get<any>(`/api/ontology/datatypes/usage/${projectId}?datatypeIri=${encodeURIComponent(datatypeIri)}`);
      const usageData = response?.data || response || [];
      setUsages(usageData);
    } catch (error) {
      console.error('Failed to load usage data:', error);
      setUsages([]);
    } finally {
      setLoading(false);
    }
  };

  const filteredUsages = usages.filter(u =>
    (u.subjectLabel || u.subject || '').toLowerCase().includes(filter.toLowerCase())
  );

  if (loading) {
    return <div className="p-4 text-sm text-gray-500">Loading usage information...</div>;
  }

  return (
    <div className="flex flex-col h-full">
      <div className="p-2 border-b border-gray-200 space-y-2">
        <div className="text-xs text-gray-600">
          Found <span className="font-bold text-red-600">{usages.length}</span> uses of <span className="font-semibold">{label}</span>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex-1 relative">
            <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Filter usages..."
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="w-full pl-7 pr-2 py-1 text-xs rounded focus:outline-none theme-input"
            />
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {filteredUsages.length === 0 ? (
          <div className="text-xs text-gray-500 p-4 text-center">
            No usages found for this datatype
          </div>
        ) : (
          <div className="space-y-1">
            {filteredUsages.map((usage, idx) => (
              <div key={idx} className="bg-white border border-gray-200 rounded p-2 text-xs hover:bg-gray-50">
                <div className="font-semibold text-gray-800">{usage.subjectLabel || usage.subject}</div>
                <div className="text-gray-500 text-[11px] font-mono mt-1">{usage.type}</div>
                {usage.context && <div className="text-gray-600 text-[11px] mt-1">{usage.context}</div>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

interface DatatypeDefinition {
  id: string;
  expression: string;
  type: 'builtin' | 'restriction' | 'enumeration' | 'union' | 'intersection';
}

const DescriptionTab: React.FC<{
  item: Datatype;
  projectId: string;
  onUpdate: (item: Datatype) => void;
}> = ({ item, projectId, onUpdate }) => {
  const [definitions, setDefinitions] = useState<DatatypeDefinition[]>([]);
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false);

  const handleAddDefinition = () => {
    setIsAddDialogOpen(true);
  };

  const handleConfirmDefinition = async (expression: string, type: 'builtin' | 'expression') => {
    try {
      // TODO: Call backend API to add datatype definition
      const defType = type === 'builtin' ? 'builtin' :
                     expression.includes('[') ? 'restriction' :
                     expression.includes('{') ? 'enumeration' :
                     expression.includes('or') ? 'union' : 'intersection';

      const newDef: DatatypeDefinition = {
        id: Date.now().toString(),
        expression,
        type: defType as any
      };
      setDefinitions(prev => [...prev, newDef]);
    } catch (error) {
      console.error('Failed to add datatype definition:', error);
    }
  };

  const handleDeleteDefinition = async (id: string) => {
    try {
      // TODO: Call backend API to delete datatype definition
      setDefinitions(prev => prev.filter(d => d.id !== id));
    } catch (error) {
      console.error('Failed to delete datatype definition:', error);
    }
  };

  return (
    <div className="space-y-3">
      {/* Datatype Definitions Panel */}
      <Panel
        title="Datatype Definitions"
        defaultOpen={true}
        themeColor="bg-gradient-to-b from-blue-50 to-blue-100 text-blue-800 border-blue-200"
        actions={
          <button
            onClick={handleAddDefinition}
            className="p-1 rounded hover:bg-blue-200 transition-colors"
            title="Add datatype definition"
          >
            <Plus size={14} />
          </button>
        }
      >
        <div className="space-y-2">
          {definitions.length === 0 ? (
            <div className="text-xs text-gray-500 italic p-2 bg-gray-50 rounded border border-gray-200">
              No datatype definitions. Click + to add a restriction, enumeration, or range.
            </div>
          ) : (
            definitions.map(def => (
              <div key={def.id} className="flex items-center justify-between p-2 bg-white border border-gray-200 rounded text-xs hover:bg-gray-50">
                <div className="flex-1">
                  <div className="font-mono text-gray-800">{def.expression}</div>
                  <div className="text-[10px] text-gray-500 mt-1">{def.type}</div>
                </div>
                <button
                  onClick={() => handleDeleteDefinition(def.id)}
                  className="p-1 rounded hover:bg-red-100 text-red-600 transition-colors ml-2"
                  title="Delete definition"
                >
                  <Trash2 size={12} />
                </button>
              </div>
            ))
          )}
        </div>
      </Panel>

      {/* Add Definition Dialog */}
      <DatatypeDefinitionDialog
        isOpen={isAddDialogOpen}
        onClose={() => setIsAddDialogOpen(false)}
        onConfirm={handleConfirmDefinition}
      />
    </div>
  );
};

const DatatypeEditor: React.FC<{
  item: Datatype;
  onUpdate: (updatedItem: Datatype) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
}> = ({
    item,
    onUpdate,
    onAddAnnotation,
    onEditAnnotation,
    onDeleteAnnotation,
    activeTheme,
    projectId
}) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'description' | 'usage'>('description');
  const [loadingDetails, setLoadingDetails] = useState(false);

  const annotationCount = Object.keys(item.annotations || {}).length;

  return (
    <div className="flex flex-col h-full bg-white">
      {loadingDetails && (
        <div className="absolute top-0 left-0 right-0 bg-red-100 text-xs text-gray-700 px-3 py-1 z-10 flex items-center justify-center">
          <div className="animate-spin mr-2 h-3 w-3 border-2 border-red-600 border-t-transparent rounded-full"></div>
          Loading datatype details...
        </div>
      )}

      {/* Header with IRI */}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between flex-shrink-0">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="bg-red-200 text-red-800 p-1 rounded text-xs font-bold">D</div>
          <div className="flex flex-col min-w-0">
            <span className="font-bold text-sm truncate">{item.label}</span>
            <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-gray-200 bg-gray-50 flex-shrink-0">
        <button
          onClick={() => setActiveTab('annotations')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'annotations' ? 'border-red-600 text-red-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Annotations ({annotationCount})
        </button>
        <button
          onClick={() => setActiveTab('description')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'description' ? 'border-red-600 text-red-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Description
        </button>
        <button
          onClick={() => setActiveTab('usage')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'usage' ? 'border-red-600 text-red-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Usage
        </button>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
        {activeTab === 'usage' && (
          <UsageTab datatypeIri={item.id} projectId={projectId} label={item.label} />
        )}

        {activeTab === 'annotations' && (
          <Panel
            title="Annotations"
            defaultOpen={true}
            themeColor="bg-gradient-to-b from-gray-50 to-gray-100 text-gray-800 border-gray-200"
            actions={
              <button
                onClick={onAddAnnotation}
                className="p-1 rounded hover:bg-gray-200 transition-colors"
                title="Add annotation"
              >
                <Plus size={14} />
              </button>
            }
          >
            <AnnotationsDisplay annotations={item.annotations || {}} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} />
          </Panel>
        )}

        {activeTab === 'description' && (
          <DescriptionTab item={item} projectId={projectId} onUpdate={onUpdate} />
        )}
      </div>
    </div>
  );
};

export default DatatypeEditor;
