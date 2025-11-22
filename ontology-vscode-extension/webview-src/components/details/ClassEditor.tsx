import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Search, ExternalLink, AlertCircle, Edit3 } from 'lucide-react';
import { Panel, AnnotationsDisplay, AxiomSubsection } from './common';
import { ClassExpressionDialog, MultiClassSelectorDialog, IRIEditorDialog } from '../dialogs';
import apiClient from '../../services/apiClient';
import ontologyMutationService from '../../services/ontologyMutationService';
import type { TreeNode, Axiom, ClassUsage, AxiomUsage } from '../../types';

type AxiomType = 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';

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
    annotation: true,
    annotation_on_class: true
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
    annotation: filteredUsages.filter(u => u.type === 'annotation'),
    annotation_on_class: filteredUsages.filter(u => u.type === 'annotation_on_class')
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
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.annotation_on_class} onChange={(e) => setShowTypes({...showTypes, annotation_on_class: e.target.checked})} className="w-3 h-3" />
            <span>class annotations ({usagesByType.annotation_on_class.length})</span>
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
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  classHierarchy?: TreeNode[];
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  expandedNodes?: string[];
}> = ({ item, projectId, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, classHierarchy = [], onToggleNode, expandedNodes }) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'usage' | 'description'>('annotations');
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [classDetails, setClassDetails] = useState<any>(null);

  // Manchester Syntax Editor State
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorType, setEditorType] = useState<AxiomType | null>(null);
  const [editorTitle, setEditorTitle] = useState("");

  // Properties for restriction creators
  const [properties, setProperties] = useState<any[]>([]);
  const [dataProperties, setDataProperties] = useState<any[]>([]);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<TreeNode[]>([]);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<TreeNode[]>([]);

  // Disjoint Union State
  const [isDisjointUnionOpen, setIsDisjointUnionOpen] = useState(false);

  // IRI Editor State
  const [isIRIEditorOpen, setIsIRIEditorOpen] = useState(false);

  const handleSaveIRI = async (newIRI: string, newLabel: string) => {
    try {
      // Note: Changing IRI is a complex operation that may require backend support
      // For now, we'll just update the label if it changed
      if (newLabel !== item.label) {
        await ontologyMutationService.updateClassLabel(projectId, item.id, newLabel);
        const updatedItem = { ...item, label: newLabel };
        onUpdate(updatedItem as TreeNode);
      }

      // TODO: Add backend support for IRI renaming
      if (newIRI !== item.id) {
        console.warn('IRI renaming requires backend support - not yet implemented');
        alert('IRI renaming is not yet supported. Only label changes are saved.');
      }
    } catch (error) {
      console.error('Failed to update entity:', error);
      alert('Failed to update entity. See console for details.');
    }
  };

  // Load class details including annotations when component mounts
  useEffect(() => {
    if (item.id && projectId) {
      loadClassDetails();
      loadProperties();
    }
  }, [item.id, projectId]);

  const loadProperties = async () => {
    try {
      // Load all properties (both object and data)
      const allPropsResponse = await apiClient.get(`/api/ontology/properties/${projectId}`);
      const allProps = allPropsResponse?.data?.properties || [];

      // Separate object and data properties
      const objProps = allProps.filter((p: any) => p.type === 'ObjectProperty');
      const dataProps = allProps.filter((p: any) => p.type === 'DatatypeProperty');

      setProperties(objProps);
      setDataProperties(dataProps);

      // Build Object Property Hierarchy (same logic as Dashboard)
      const opMap = new Map<string, any>();
      objProps.forEach((p: any) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topObjectProperty = {
        id: 'http://www.w3.org/2002/07/owl#topObjectProperty',
        label: 'owl:topObjectProperty',
        children: [],
        hasChildren: false
      };

      objProps.forEach((p: any) => {
        const node = opMap.get(p.id);
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach((superId: string) => {
            if (superId === topObjectProperty.id) {
              topObjectProperty.children.push(node);
              topObjectProperty.hasChildren = true;
              added = true;
            } else if (opMap.has(superId)) {
              const parent = opMap.get(superId);
              parent.children.push(node);
              parent.hasChildren = true;
              added = true;
            }
          });
          if (!added) {
            topObjectProperty.children.push(node);
            topObjectProperty.hasChildren = true;
          }
        } else {
          topObjectProperty.children.push(node);
          topObjectProperty.hasChildren = true;
        }
      });

      setObjectPropertyHierarchy([topObjectProperty]);

      // Build Data Property Hierarchy
      const dpMap = new Map<string, any>();
      dataProps.forEach((p: any) => {
        dpMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topDataProperty = {
        id: 'http://www.w3.org/2002/07/owl#topDataProperty',
        label: 'owl:topDataProperty',
        children: [],
        hasChildren: false
      };

      dataProps.forEach((p: any) => {
        const node = dpMap.get(p.id);
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach((superId: string) => {
            if (superId === topDataProperty.id) {
              topDataProperty.children.push(node);
              topDataProperty.hasChildren = true;
              added = true;
            } else if (dpMap.has(superId)) {
              const parent = dpMap.get(superId);
              parent.children.push(node);
              parent.hasChildren = true;
              added = true;
            }
          });
          if (!added) {
            topDataProperty.children.push(node);
            topDataProperty.hasChildren = true;
          }
        } else {
          topDataProperty.children.push(node);
          topDataProperty.hasChildren = true;
        }
      });

      setDataPropertyHierarchy([topDataProperty]);
    } catch (error) {
      console.error('Failed to load properties:', error);
    }
  };

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

  const openEditor = (type: AxiomType, title: string) => {
    setEditorType(type);
    setEditorTitle(title);
    setIsEditorOpen(true);
  };

  const handleEditorConfirm = (expression: string) => {
    if (editorType) {
      handleAddAxiom(editorType, expression);
    }
    setIsEditorOpen(false);
    setEditorType(null);
  };

  const handleAddAxiom = async (type: AxiomType, definition: string) => {
    try {
      await ontologyMutationService.addAxiom(projectId, item.id, type, definition);
      // Reload details to get the updated axioms (assuming backend processed it)
      await loadClassDetails();
      // Also notify parent to update tree if needed (though axioms usually don't change tree structure unless it's subclassof)
      // onUpdate(item); // We might not need this if we reload details
    } catch (error) {
      console.error('Failed to add axiom:', error);
      // You might want to show a notification here
    }
  };

  const handleDeleteAxiom = async (type: AxiomType, id: string) => {
    // For deletion, we need the axiom ID or the definition. 
    // The current backend API for delete might need the definition if IDs are not persistent/consistent.
    // For now, let's assume we can't easily delete complex axioms without more backend support.
    console.warn("Delete axiom not fully implemented for complex expressions");
    
    // Optimistic update for UI
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

  const handleDisjointUnionConfirm = async (nodes: TreeNode[]) => {
    try {
      const expression = nodes.map(n => n.label || n.id).join(", ");
      // We treat Disjoint Union as a special axiom type or just use addAxiom with a specific flag if backend supported it.
      // For now, let's assume we send it as a "DisjointUnion" axiom type (which we need to add to AxiomType or handle loosely)
      // But AxiomType is strict. Let's cast or extend it.
      // Actually, DisjointUnion is usually a set of classes.
      // We can format it as "DisjointUnionOf(A, B, C)" or just "A, B, C" and let the backend handle it.
      
      // Since we don't have a specific "DisjointUnion" type in AxiomType, we might need to extend it or use a custom call.
      // Let's use addAxiom with a custom type string if possible, or just log it for now as backend support is partial.
      
      await ontologyMutationService.addAxiom(projectId, item.id, 'DisjointUnion' as any, expression);
      await loadClassDetails();
    } catch (error) {
      console.error('Failed to add disjoint union:', error);
    }
    setIsDisjointUnionOpen(false);
  };

  const annotationCount = Object.keys(item.annotations || {}).length;
  const displayAnnotations = loadingDetails ? {} : (item.annotations || {});

  return (
    <div className="flex flex-col h-full bg-white">
      {loadingDetails && (
        <div className="absolute top-0 left-0 right-0 bg-yellow-100 text-xs text-gray-700 px-3 py-1 z-10 flex items-center justify-center">
          <div className="animate-spin mr-2 h-3 w-3 border-2 border-yellow-600 border-t-transparent rounded-full"></div>
          Loading class details...
        </div>
      )}
      
      {/* Header with IRI */}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="bg-yellow-200 text-yellow-800 p-1 rounded text-xs font-bold">C</div>
          <div className="flex flex-col min-w-0">
            <span className="font-bold text-sm truncate">{item.label}</span>
            <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
          </div>
        </div>
        <button
          onClick={() => setIsIRIEditorOpen(true)}
          className="p-1.5 hover:bg-gray-200 rounded text-gray-600 hover:text-purple-600 flex-shrink-0"
          title="Edit IRI and Label"
        >
          <Edit3 size={16} />
        </button>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-gray-200 bg-gray-50">
        <button 
          onClick={() => setActiveTab('annotations')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'annotations' ? 'border-purple-600 text-purple-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Annotations ({annotationCount})
        </button>
        <button 
          onClick={() => setActiveTab('description')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'description' ? 'border-purple-600 text-purple-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Description
        </button>
        <button 
          onClick={() => setActiveTab('usage')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === 'usage' ? 'border-purple-600 text-purple-700 bg-white' : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'}`}
        >
          Usage
        </button>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
        {activeTab === 'usage' && (
          <UsageTab classIri={item.id} projectId={projectId} label={item.label} />
        )}
        
        {activeTab === 'annotations' && (
            <Panel title="Annotations" defaultOpen={true} themeColor="bg-gradient-to-b from-gray-50 to-gray-100 text-gray-800 border-gray-200" 
              actions={
                <button onClick={onAddAnnotation} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600" title="Add annotation">
                  <Plus size={14} />
                </button>
              }
            >
              <div className="p-2">
                <AnnotationsDisplay annotations={displayAnnotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} />
              </div>
            </Panel>
        )}

        {activeTab === 'description' && (
            <Panel title="Description" defaultOpen={true} themeColor="bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]">
              <div className="p-3 space-y-4">
                <AxiomSubsection
                  title="Equivalent To"
                  axioms={item.equivalentClassesAxioms}
                  onAdd={(def) => handleAddAxiom('EquivalentTo', def)}
                  onDelete={(id) => handleDeleteAxiom('EquivalentTo', id)}
                  onAddClick={() => openEditor('EquivalentTo', 'Edit Equivalent Class Expression')}
                />
                
                <AxiomSubsection
                  title="SubClass Of"
                  axioms={item.subClassOfAxioms}
                  onAdd={(def) => handleAddAxiom('SubClassOf', def)}
                  onDelete={(id) => handleDeleteAxiom('SubClassOf', id)}
                  onAddClick={() => openEditor('SubClassOf', 'Edit SubClass Expression')}
                />
                
                <AxiomSubsection
                  title="Disjoint With"
                  axioms={item.disjointClassesAxioms}
                  onAdd={(def) => handleAddAxiom('DisjointWith', def)}
                  onDelete={(id) => handleDeleteAxiom('DisjointWith', id)}
                  onAddClick={() => openEditor('DisjointWith', 'Edit Disjoint Class Expression')}
                />
                
                <AxiomSubsection
                  title="Disjoint Union Of"
                  axioms={item.disjointUnionAxioms}
                  onAdd={() => {}}
                  onDelete={(id) => handleDeleteAxiom('DisjointUnion' as any, id)}
                  onAddClick={() => setIsDisjointUnionOpen(true)}
                  emptyMessage="No disjoint unions defined"
                />

                <AxiomSubsection
                  title="Has Key"
                  axioms={item.hasKeyAxioms}
                  onAdd={(def) => handleAddAxiom('HasKey' as any, def)}
                  onDelete={(id) => handleDeleteAxiom('HasKey' as any, id)}
                  onAddClick={() => openEditor('HasKey' as any, 'Edit Has Key (Properties)')}
                  emptyMessage="No keys defined"
                />
                
                {/* Members Section (Placeholder for now, could fetch instances) */}
                <div className="mt-4 pt-4 border-t border-gray-200">
                   <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-2">Members</h4>
                   <div className="p-2 text-xs text-gray-500 italic bg-gray-50 border border-gray-200 rounded">
                     Instances of this class are listed in the "Individuals by class" tab.
                   </div>
                </div>
              </div>
            </Panel>
        )}
      </div>

      {/* Class Expression Editor Dialog (Better UI) */}
      <ClassExpressionDialog
        isOpen={isEditorOpen}
        onClose={() => setIsEditorOpen(false)}
        onConfirm={handleEditorConfirm}
        title={editorTitle}
        classHierarchy={classHierarchy}
        objectProperties={properties}
        dataProperties={dataProperties}
        objectPropertiesTree={objectPropertyHierarchy}
        dataPropertiesTree={dataPropertyHierarchy}
        expandedNodes={expandedNodes}
        onToggleNode={onToggleNode}
      />

      {/* Disjoint Union Selector */}
      <MultiClassSelectorDialog
        isOpen={isDisjointUnionOpen}
        onClose={() => setIsDisjointUnionOpen(false)}
        onConfirm={handleDisjointUnionConfirm}
        classHierarchy={classHierarchy}
        projectId={projectId}
        onToggleNode={onToggleNode}
        externalExpandedNodes={expandedNodes}
        title="Select Classes for Disjoint Union"
      />

      {/* IRI Editor Dialog */}
      <IRIEditorDialog
        isOpen={isIRIEditorOpen}
        onClose={() => setIsIRIEditorOpen(false)}
        currentIRI={item.id}
        currentLabel={item.label}
        entityType="Class"
        onSave={handleSaveIRI}
      />
    </div>
  );
};

export default ClassEditor;
