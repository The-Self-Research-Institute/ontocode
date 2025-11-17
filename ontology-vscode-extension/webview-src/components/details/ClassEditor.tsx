import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Search } from 'lucide-react';
import { Panel, AnnotationsDisplay } from './common';
import ManchesterSyntaxEditor from './ManchesterSyntaxEditor';
import apiClient from '../../services/apiClient';
import type { TreeNode, Axiom, ClassUsage, AxiomUsage } from '../../types';

type AxiomType = 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';

const AxiomSection: React.FC<{
  title: string;
  axioms: Axiom[] | undefined;
  onAdd: (definition: string) => void;
  onDelete: (id: string) => void;
}> = ({ title, axioms, onAdd, onDelete }) => {
  const [isAdding, setIsAdding] = useState(false);

  const handleSave = (definition: string) => {
    onAdd(definition);
    setIsAdding(false);
  };

  return (
    <div className="border border-gray-200 rounded-sm">
      <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center">
        <span className="font-semibold">{title}</span>
        <button onClick={() => setIsAdding(true)} className="p-0.5 hover:bg-gray-300 rounded" title={`Add ${title}`}>
          <Plus size={14} />
        </button>
      </div>
      <div className="p-1.5 space-y-1">
        {axioms?.map(axiom => (
          <div key={axiom.id} className="group flex justify-between items-center bg-gray-50 p-1.5 rounded-sm text-xs font-mono hover:bg-gray-100">
            <span className="text-purple-700">{axiom.definition}</span>
            <button onClick={() => onDelete(axiom.id)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200" title="Delete axiom">
                <Trash2 size={12} className="text-red-600"/>
            </button>
          </div>
        ))}
        {isAdding && <ManchesterSyntaxEditor onSave={handleSave} onCancel={() => setIsAdding(false)} />}
        {!isAdding && (!axioms || axioms.length === 0) && (
             <button onClick={() => setIsAdding(true)} className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                Add...
             </button>
        )}
      </div>
    </div>
  );
};

interface UsageItem {
  type: string;
  subject: string;
  subjectLabel?: string;
  predicate?: string;
  object?: string;
  context?: string;
}

const UsageTab: React.FC<{ 
  classIri: string; 
  projectId: string;
  label: string;
}> = ({ classIri, projectId, label }) => {
  const [loading, setLoading] = useState(true);
  const [usages, setUsages] = useState<UsageItem[]>([]);
  const [filter, setFilter] = useState('');
  const [showTypes, setShowTypes] = useState({
    instance: true,
    subclass: true,
    superclass: true,
    disjoint: true,
    domain: true,
    range: true,
    restriction: true,
    equivalent: true,
    union: true,
    intersection: true,
    annotation: true
  });

  useEffect(() => {
    loadUsages();
  }, [classIri, projectId]);

  const loadUsages = async () => {
    setLoading(true);
    try {
      // Query for all usages of this class
      const response = await apiClient.get<any>(`/api/ontology/classes/usage/${projectId}?classIri=${encodeURIComponent(classIri)}`);
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
    (u.subjectLabel || u.subject || '').toLowerCase().includes(filter.toLowerCase()) &&
    showTypes[u.type as keyof typeof showTypes] !== false
  );

  const usagesByType = {
    instance: filteredUsages.filter(u => u.type === 'instance'),
    subclass: filteredUsages.filter(u => u.type === 'subclass'),
    superclass: filteredUsages.filter(u => u.type === 'superclass'),
    disjoint: filteredUsages.filter(u => u.type === 'disjoint'),
    domain: filteredUsages.filter(u => u.type === 'domain'),
    range: filteredUsages.filter(u => u.type === 'range'),
    restriction: filteredUsages.filter(u => u.type === 'restriction'),
    equivalent: filteredUsages.filter(u => u.type === 'equivalent'),
    union: filteredUsages.filter(u => u.type === 'union'),
    intersection: filteredUsages.filter(u => u.type === 'intersection'),
    annotation: filteredUsages.filter(u => u.type === 'annotation')
  };

  if (loading) {
    return <div className="p-4 text-sm text-gray-500">Loading usage information...</div>;
  }

  return (
    <div className="flex flex-col h-full">
      <div className="p-2 border-b border-gray-200 space-y-2">
        <div className="text-xs text-gray-600">
          Found <span className="font-bold text-purple-600">{usages.length}</span> uses of <span className="font-semibold">{label}</span>
        </div>
        
        <div className="flex items-center gap-2">
          <div className="flex-1 relative">
            <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Filter usages..."
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="w-full pl-7 pr-2 py-1 text-xs border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-purple-500"
            />
          </div>
        </div>

        <div className="flex flex-wrap gap-2 text-xs">
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.instance} onChange={(e) => setShowTypes({...showTypes, instance: e.target.checked})} className="w-3 h-3" />
            <span>instances ({usagesByType.instance.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.subclass} onChange={(e) => setShowTypes({...showTypes, subclass: e.target.checked})} className="w-3 h-3" />
            <span>subclasses ({usagesByType.subclass.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.superclass} onChange={(e) => setShowTypes({...showTypes, superclass: e.target.checked})} className="w-3 h-3" />
            <span>superclasses ({usagesByType.superclass.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.disjoint} onChange={(e) => setShowTypes({...showTypes, disjoint: e.target.checked})} className="w-3 h-3" />
            <span>disjoints ({usagesByType.disjoint.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.domain} onChange={(e) => setShowTypes({...showTypes, domain: e.target.checked})} className="w-3 h-3" />
            <span>domains ({usagesByType.domain.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.range} onChange={(e) => setShowTypes({...showTypes, range: e.target.checked})} className="w-3 h-3" />
            <span>ranges ({usagesByType.range.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.restriction} onChange={(e) => setShowTypes({...showTypes, restriction: e.target.checked})} className="w-3 h-3" />
            <span>restrictions ({usagesByType.restriction.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.equivalent} onChange={(e) => setShowTypes({...showTypes, equivalent: e.target.checked})} className="w-3 h-3" />
            <span>equivalent ({usagesByType.equivalent.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.union} onChange={(e) => setShowTypes({...showTypes, union: e.target.checked})} className="w-3 h-3" />
            <span>unions ({usagesByType.union.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.intersection} onChange={(e) => setShowTypes({...showTypes, intersection: e.target.checked})} className="w-3 h-3" />
            <span>intersections ({usagesByType.intersection.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.annotation} onChange={(e) => setShowTypes({...showTypes, annotation: e.target.checked})} className="w-3 h-3" />
            <span>annotations ({usagesByType.annotation.length})</span>
          </label>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {filteredUsages.length === 0 ? (
          <div className="text-xs text-gray-400 italic text-center py-4">No usages found</div>
        ) : (
          <div className="space-y-1">
            {filteredUsages.map((usage, idx) => (
              <div key={idx} className="p-2 bg-gray-50 rounded border border-gray-200 hover:bg-gray-100">
                <div className="flex items-start gap-2">
                  <span className="text-xs font-semibold text-orange-600 uppercase min-w-[80px]">{usage.type}</span>
                  <div className="flex-1 text-xs">
                    <div className="font-mono text-purple-700">{usage.subjectLabel || usage.subject}</div>
                    {usage.context && <div className="text-gray-600 mt-1">{usage.context}</div>}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};


const ClassEditor: React.FC<{
  item: TreeNode;
  projectId: string;
  onUpdate: (updatedItem: TreeNode) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
}> = ({ item, projectId, onUpdate, onAddAnnotation, onDeleteAnnotation, activeTheme }) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'usage' | 'description'>('annotations');
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [classDetails, setClassDetails] = useState<any>(null);

  // Load class details including annotations when component mounts
  useEffect(() => {
    if (item.id && projectId) {
      loadClassDetails();
    }
  }, [item.id, projectId]);

  const loadClassDetails = async () => {
    setLoadingDetails(true);
    try {
      const response = await apiClient.get<any>(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(item.id)}`);
      const details = response?.data || response;
      console.log('Class details loaded:', details);
      setClassDetails(details);
      
      // Update the item with loaded annotations
      if (details.annotations) {
        onUpdate({ ...item, annotations: details.annotations });
      }
    } catch (error) {
      console.error('Failed to load class details:', error);
    } finally {
      setLoadingDetails(false);
    }
  };

  const handleAddAxiom = (type: AxiomType, definition: string) => {
    const newAxiom: Axiom = { id: `axiom-${Date.now()}`, type, definition };
    let updatedAxioms;
    
    switch (type) {
        case 'EquivalentTo':
            updatedAxioms = [...(item.equivalentClassesAxioms || []), newAxiom];
            onUpdate({ ...item, equivalentClassesAxioms: updatedAxioms });
            break;
        case 'SubClassOf':
            updatedAxioms = [...(item.subClassOfAxioms || []), newAxiom];
            onUpdate({ ...item, subClassOfAxioms: updatedAxioms });
            break;
        case 'DisjointWith':
            updatedAxioms = [...(item.disjointClassesAxioms || []), newAxiom];
            onUpdate({ ...item, disjointClassesAxioms: updatedAxioms });
            break;
    }
  };

  const handleDeleteAxiom = (type: AxiomType, id: string) => {
    switch (type) {
        case 'EquivalentTo':
            onUpdate({ ...item, equivalentClassesAxioms: item.equivalentClassesAxioms?.filter(a => a.id !== id) });
            break;
        case 'SubClassOf':
            onUpdate({ ...item, subClassOfAxioms: item.subClassOfAxioms?.filter(a => a.id !== id) });
            break;
        case 'DisjointWith':
            onUpdate({ ...item, disjointClassesAxioms: item.disjointClassesAxioms?.filter(a => a.id !== id) });
            break;
    }
  };

  const annotationCount = Object.keys(item.annotations || {}).length;
  const displayAnnotations = loadingDetails ? {} : (item.annotations || {});

  return (
    <div className="flex flex-col h-full bg-white">
      {loadingDetails && (
        <div className="absolute top-0 left-0 right-0 bg-yellow-100 text-xs text-gray-700 px-3 py-1 z-10">
          Loading class details...
        </div>
      )}
      {/* Tab Navigation */}
      <div className="flex border-b border-gray-300">
        <button
          onClick={() => setActiveTab('annotations')}
          className={`flex-1 px-4 py-2 text-xs font-medium transition-colors ${
            activeTab === 'annotations'
              ? 'bg-yellow-100 text-gray-900 border-b-2 border-yellow-600'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          Annotations {annotationCount > 0 && `(${annotationCount})`}
        </button>
        <button
          onClick={() => setActiveTab('usage')}
          className={`flex-1 px-4 py-2 text-xs font-medium transition-colors ${
            activeTab === 'usage'
              ? 'bg-yellow-100 text-gray-900 border-b-2 border-yellow-600'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          Usage
        </button>
        <button
          onClick={() => setActiveTab('description')}
          className={`flex-1 px-4 py-2 text-xs font-medium transition-colors ${
            activeTab === 'description'
              ? 'bg-yellow-100 text-gray-900 border-b-2 border-yellow-600'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          Description
        </button>
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-hidden">
        {activeTab === 'annotations' && (
          <div className="h-full overflow-y-auto p-3">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold text-gray-700">Annotations: {item.label}</h3>
              <button onClick={onAddAnnotation} className="p-1 hover:bg-gray-200 rounded" title="Add annotation">
                <Plus size={16} />
              </button>
            </div>
            {loadingDetails ? (
              <div className="text-sm text-gray-500 italic">Loading annotations...</div>
            ) : (
              <AnnotationsDisplay annotations={displayAnnotations} onDelete={onDeleteAnnotation} />
            )}
          </div>
        )}

        {activeTab === 'usage' && (
          <UsageTab classIri={item.id} projectId={projectId} label={item.label} />
        )}

        {activeTab === 'description' && (
          <div className="h-full overflow-y-auto p-3">
            <h3 className="text-sm font-semibold text-gray-700 mb-2">Description: {item.label}</h3>
            <div className="space-y-2">
              <AxiomSection
                title="Equivalent To"
                axioms={item.equivalentClassesAxioms}
                onAdd={(def) => handleAddAxiom('EquivalentTo', def)}
                onDelete={(id) => handleDeleteAxiom('EquivalentTo', id)}
              />
              <AxiomSection
                title="SubClass Of"
                axioms={item.subClassOfAxioms}
                onAdd={(def) => handleAddAxiom('SubClassOf', def)}
                onDelete={(id) => handleDeleteAxiom('SubClassOf', id)}
              />
              <AxiomSection
                title="Disjoint With"
                axioms={item.disjointClassesAxioms}
                onAdd={(def) => handleAddAxiom('DisjointWith', def)}
                onDelete={(id) => handleDeleteAxiom('DisjointWith', id)}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ClassEditor;