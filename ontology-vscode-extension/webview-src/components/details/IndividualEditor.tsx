import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Loader2, Search } from 'lucide-react';
import { Panel, AnnotationsDisplay, MultiSelectSection, CollaboratorPresenceBar } from './common';
import type { Individual, PropertyAssertion, TreeNode } from '../../types';
import { ManchesterSyntaxEditor, IndividualSelectorDialog, PropertyAssertionDialog, ClassExpressionDialog } from '../dialogs';
import ontologyMutationService from '../../services/ontologyMutationService';
import { notificationService } from '../../services/notificationService';
import apiClient from '../../services/apiClient';

interface UsageItem {
  type: string;
  subject: string;
  subjectLabel?: string;
  predicate?: string;
  object?: string;
  context?: string;
}

const IndividualUsageTab: React.FC<{ 
  individualIri: string; 
  projectId: string; 
  label: string;
}> = ({ individualIri, projectId, label }) => {
  const [loading, setLoading] = useState(true);
  const [usages, setUsages] = useState<UsageItem[]>([]);
  const [filter, setFilter] = useState('');
  const [showTypes, setShowTypes] = useState({
    assertion: true,
    same: true,
    different: true,
    annotation: true
  });

  useEffect(() => {
    loadUsages();
  }, [individualIri, projectId]);

  const loadUsages = async () => {
    setLoading(true);
    try {
      const response = await apiClient.get<any>(`/api/ontology/individuals/usage/${projectId}?individualIri=${encodeURIComponent(individualIri)}`);
      const usageData = response?.data?.data || response?.data || response || [];
      setUsages(Array.isArray(usageData) ? usageData : []);
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
    assertion: filteredUsages.filter(u => u.type === 'assertion'),
    same: filteredUsages.filter(u => u.type === 'same'),
    different: filteredUsages.filter(u => u.type === 'different'),
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
              className="w-full pl-7 pr-2 py-1 text-xs rounded focus:outline-none theme-input"
            />
          </div>
        </div>

        <div className="flex flex-wrap gap-2 text-xs">
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.assertion} onChange={(e) => setShowTypes({...showTypes, assertion: e.target.checked})} className="w-3 h-3" />
            <span>assertions ({usagesByType.assertion.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.same} onChange={(e) => setShowTypes({...showTypes, same: e.target.checked})} className="w-3 h-3" />
            <span>same as ({usagesByType.same.length})</span>
          </label>
          <label className="flex items-center gap-1 cursor-pointer">
            <input type="checkbox" checked={showTypes.different} onChange={(e) => setShowTypes({...showTypes, different: e.target.checked})} className="w-3 h-3" />
            <span>different from ({usagesByType.different.length})</span>
          </label>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {filteredUsages.length === 0 ? (
          <div className="text-xs text-gray-400 italic text-center py-4">No usages found</div>
        ) : (
          <div className="space-y-1">
            {filteredUsages.map((u, idx) => (
              <div key={idx} className="p-2 bg-gray-50 rounded border border-gray-200 hover:bg-gray-100">
                <div className="flex items-start gap-2">
                  <span className="text-[10px] font-semibold text-purple-600 uppercase min-w-[80px] mt-0.5">{u.type}</span>
                  <div className="flex-1 text-xs">
                    <div className="font-mono text-gray-700 break-all">{u.subjectLabel || u.subject}</div>
                    {u.context && <div className="text-gray-500 mt-1 italic">{u.context}</div>}
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

const IndividualEditor: React.FC<{
  item: Individual;
  onUpdate: (updatedItem: Individual) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  projectId: string;
  userId?: string;
  username?: string;
  objectPropertyHierarchy?: TreeNode[];
  dataPropertyHierarchy?: TreeNode[];
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
  onNavigate?: (iri: string, type: string) => void;
}> = ({ item, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, projectId, userId, username, objectPropertyHierarchy, dataPropertyHierarchy, isViewOnly = false, onViewOnlyAction, onNavigate }) => {
  const [isAddingAssertion, setIsAddingAssertion] = useState(false);
  const [isNegativeAssertion, setIsNegativeAssertion] = useState(false);
  const [newAssertion, setNewAssertion] = useState({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
  const [isLoading, setIsLoading] = useState(false);
  const [detailsFetched, setDetailsFetched] = useState<string | null>(null);
  const [propertySuggestions, setPropertySuggestions] = useState<{ label: string; value: string }[]>([]);
  const [individualSuggestions, setIndividualSuggestions] = useState<{ label: string; value: string }[]>([]);
  const [sameDiffDialog, setSameDiffDialog] = useState<null | { mode: 'same' | 'different' }>(null);
  const [allIndividuals, setAllIndividuals] = useState<Individual[]>([]);
  const [typeDialogOpen, setTypeDialogOpen] = useState(false);
  const [typeClassHierarchy, setTypeClassHierarchy] = useState<TreeNode[]>([]);

  const loadIndividualDetails = async () => {
    if (!projectId || !item.id) return;
    setIsLoading(true);
    try {
      const response = await apiClient.get<any>(`/api/ontology/individual-details/${projectId}?individualIri=${encodeURIComponent(item.id)}`);
      const details = response?.data || response;
      if (details) {
        onUpdate({
          ...item,
          types: details.types || item.types,
          annotations: details.annotations || item.annotations,
          propertyAssertions: details.propertyAssertions || [],
          sameIndividualAs: details.sameIndividualAs || item.sameIndividualAs,
          differentIndividualFrom: details.differentIndividualFrom || item.differentIndividualFrom,
        });
        setDetailsFetched(item.id);
      }
    } catch (error) {
      console.error('[IndividualEditor] Failed to fetch individual details:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // Fetch individual details when component mounts or item changes.
  // Uses an "alive" flag so stale responses from a previously selected
  // individual are discarded (prevents showing the previous individual's data).
  useEffect(() => {
    if (!projectId || !item.id) return;

    let alive = true;
    const currentId = item.id;
    setIsLoading(true);

    // Watchdog: avoid the spinner getting stuck if backend hangs.
    const watchdog = setTimeout(() => {
      if (alive) setIsLoading(false);
    }, 30000);

    (async () => {
      try {
        // Use query parameter endpoint to avoid URL encoding issues with IRI containing #
        const response = await apiClient.get<any>(`/api/ontology/individual-details/${projectId}?individualIri=${encodeURIComponent(currentId)}`);
        if (!alive || currentId !== item.id) return;

        const details = response?.data || response;

        if (details) {
          const updatedItem: Individual = {
            ...item,
            types: details.types || item.types,
            annotations: details.annotations || item.annotations,
            propertyAssertions: details.propertyAssertions || [],
            sameIndividualAs: details.sameIndividualAs || item.sameIndividualAs,
            differentIndividualFrom: details.differentIndividualFrom || item.differentIndividualFrom,
          };
          onUpdate(updatedItem);
          setDetailsFetched(currentId);
        }
      } catch (error) {
        if (alive && currentId === item.id) {
          console.error('[IndividualEditor] Failed to fetch individual details:', error);
        }
      } finally {
        if (alive && currentId === item.id) setIsLoading(false);
      }
    })();

    return () => { alive = false; clearTimeout(watchdog); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id, projectId]);

  // Auto-reload when a collaborator modifies this individual
  useEffect(() => {
    const handleRemoteEdit = (e: Event) => {
      const edit = (e as CustomEvent).detail;
      if (!edit || edit.nodeId !== item.id) return;
      const INDIVIDUAL_CHANGE_TYPES = new Set([
        "INDIVIDUAL_MODIFIED", "INDIVIDUAL_ADDED",
        "ANNOTATION_ADDED", "ANNOTATION_MODIFIED", "ANNOTATION_DELETED",
      ]);
      if (INDIVIDUAL_CHANGE_TYPES.has(edit.type)) {
        loadIndividualDetails();
      }
    };
    window.addEventListener("remoteEditReceived", handleRemoteEdit);
    return () => window.removeEventListener("remoteEditReceived", handleRemoteEdit);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  // Separate positive/negative property assertions
  const positiveObjectPropertyAssertions = item.propertyAssertions?.filter(a => a.isObjectProperty && !a.isNegative) || [];
  const positiveDataPropertyAssertions = item.propertyAssertions?.filter(a => !a.isObjectProperty && !a.isNegative) || [];
  const negativeObjectPropertyAssertions = item.propertyAssertions?.filter(a => a.isObjectProperty && a.isNegative) || [];
  const negativeDataPropertyAssertions = item.propertyAssertions?.filter(a => !a.isObjectProperty && a.isNegative) || [];
  
  const [activeTab, setActiveTab] = useState<'types' | 'usage'>('types');
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorAction, setEditorAction] = useState<((val: string) => void) | null>(null);

  const handleAddAssertion = async (data?: { 
    propertyLabel: string; 
    targetLabel: string; 
    isObjectProperty: boolean;
    language?: string;
    datatype?: string;
  }) => {
    const propLabel = data?.propertyLabel || newAssertion.propertyLabel;
    const targetLabel = data?.targetLabel || newAssertion.targetLabel;
    const isObjProp = data ? data.isObjectProperty : newAssertion.isObjectProperty;
    // Note: language and datatype are available in 'data' but backend support might be pending.
    // We will pass them if the service supports it.

    if (!propLabel || !targetLabel) {
        notificationService.warning("Validation Error", "Property and value cannot be empty.");
        return;
    }
    
    // Build proper IRIs from the input
    const baseIri = item.id.substring(0, item.id.lastIndexOf('#') + 1) || 'http://example.com/onto#';
    const propertyIri = propLabel.startsWith('http') 
      ? propLabel 
      : `${baseIri}${propLabel.replace(/\s+/g, '_')}`;
    const targetIri = isObjProp 
      ? (targetLabel.startsWith('http') 
        ? targetLabel 
        : `${baseIri}${targetLabel.replace(/\s+/g, '_')}`)
      : targetLabel;
    
    try {
      // Call mutation service to persist
      if (isNegativeAssertion) {
        if (isObjProp) {
          await ontologyMutationService.addNegativeObjectPropertyAssertion(
            projectId, item.id, propertyIri, targetIri, userId, username
          );
        } else {
          await ontologyMutationService.addNegativeDataPropertyAssertion(
            projectId, item.id, propertyIri, targetLabel, userId, username
          );
        }
      } else {
        if (isObjProp) {
          await ontologyMutationService.addObjectPropertyAssertion(
            projectId, item.id, propertyIri, targetIri, userId, username
          );
        } else {
          await ontologyMutationService.addDataPropertyAssertion(
            projectId, item.id, propertyIri, targetLabel, userId, username
          );
        }
      }
      
      // Update local state
      const newAssertionObject: PropertyAssertion = {
          id: `assertion-${Date.now()}`,
          propertyIri: propertyIri,
          propertyLabel: newAssertion.propertyLabel,
          [newAssertion.isObjectProperty ? 'targetIri' : 'targetLiteral']: newAssertion.isObjectProperty ? targetIri : `"${newAssertion.targetLabel}"`,
          [newAssertion.isObjectProperty ? 'targetLabel' : '']: newAssertion.targetLabel,
          isObjectProperty: newAssertion.isObjectProperty,
          isNegative: isNegativeAssertion,
      };
      onUpdate({ ...item, propertyAssertions: [...(item.propertyAssertions || []), newAssertionObject] });
      setNewAssertion({ propertyLabel: '', targetLabel: '', isObjectProperty: true });
      setIsAddingAssertion(false);
      setIsNegativeAssertion(false);
    } catch (error) {
      console.error('Failed to add property assertion:', error);
      notificationService.error("Add Failed", "Failed to add property assertion. See console for details.");
    }
  };

  const openPropertyAssertionDialog = async (isObjectProperty: boolean, negative: boolean = false) => {
    setNewAssertion(p => ({ ...p, isObjectProperty }));
    setIsAddingAssertion(true);
    setIsNegativeAssertion(negative);

    try {
      if (!projectId) return;
      const [propsRes, indsRes] = await Promise.all([
        apiClient.get<any>(`/api/ontology/${isObjectProperty ? 'object-properties' : 'data-properties'}/${projectId}`),
        isObjectProperty ? apiClient.get<any>(`/api/ontology/individuals/${projectId}`) : Promise.resolve({ data: [] })
      ]);

      const props = Array.isArray(propsRes?.data) ? propsRes.data : (propsRes?.properties || propsRes?.data?.properties || []);
      const propSuggestions = (props || []).map((p: any) => ({
        label: p.label || p.name || (typeof p === 'string' ? p.split('#').pop() : String(p)),
        value: p.id || p.iri || p.value || p.label || String(p)
      }));
      setPropertySuggestions(propSuggestions);

      if (isObjectProperty) {
        const inds = Array.isArray(indsRes?.data) ? indsRes.data : indsRes?.data?.individuals || [];
        const indSuggestions = (inds || []).map((i: any) => ({
          label: i.label || (typeof i === 'string' ? i.split('#').pop() : String(i)),
          value: i.id || i.iri || i.value || i.label || String(i)
        }));
        setIndividualSuggestions(indSuggestions);
      } else {
        setIndividualSuggestions([]);
      }
    } catch (e) {
      console.error('[IndividualEditor] Failed to load suggestions for assertion dialog:', e);
      setPropertySuggestions([]);
      setIndividualSuggestions([]);
    }
  };

  const openTypeDialog = async () => {
    setTypeDialogOpen(true);
    try {
      if (!projectId) return;
      const res = await apiClient.get<any>(`/api/ontology/classes/top-level/${projectId}`);
      const classes = Array.isArray(res?.data) ? res.data : res?.data?.classes || res?.classes || [];
      setTypeClassHierarchy(classes);
    } catch (e) {
      console.error('[IndividualEditor] Failed to load top-level classes for type dialog:', e);
      setTypeClassHierarchy([]);
    }
  };

  const handleAddType = async (expression: string) => {
      try {
          await ontologyMutationService.addClassAssertion(projectId, item.id, expression);
          onUpdate({ ...item, types: [...(item.types || []), expression] });
      } catch (e) { console.error(e); }
  };

  const openSameDifferentDialog = async (mode: 'same' | 'different') => {
    setSameDiffDialog({ mode });
    try {
      const res = await apiClient.get<any>(`/api/ontology/individuals/${projectId}`);
      const inds = Array.isArray(res?.data) ? res.data : res?.data?.individuals || [];
      setAllIndividuals(inds);
    } catch (e) {
      console.error('[IndividualEditor] Failed to load individuals:', e);
      setAllIndividuals([]);
    }
  };

  const handleDeleteAssertion = async (assertion: PropertyAssertion) => {
    try {
      // Call mutation service to persist deletion
      if (assertion.isNegative) {
        if (assertion.isObjectProperty && assertion.targetIri) {
          await ontologyMutationService.deleteNegativeObjectPropertyAssertion(
            projectId, item.id, assertion.propertyIri, assertion.targetIri, userId, username
          );
        } else if (assertion.targetLiteral) {
          const literalValue = assertion.targetLiteral.replace(/^"|"$/g, '');
          await ontologyMutationService.deleteNegativeDataPropertyAssertion(
            projectId, item.id, assertion.propertyIri, literalValue, userId, username
          );
        }
      } else {
        if (assertion.isObjectProperty && assertion.targetIri) {
          await ontologyMutationService.deleteObjectPropertyAssertion(
            projectId, item.id, assertion.propertyIri, assertion.targetIri, userId, username
          );
        } else if (assertion.targetLiteral) {
          // Remove quotes from literal value if present
          const literalValue = assertion.targetLiteral.replace(/^"|"$/g, '');
          await ontologyMutationService.deleteDataPropertyAssertion(
            projectId, item.id, assertion.propertyIri, literalValue, userId, username
          );
        }
      }
      
      // Update local state
      onUpdate({ ...item, propertyAssertions: item.propertyAssertions?.filter(a => a.id !== assertion.id) });
    } catch (error) {
      console.error('Failed to delete property assertion:', error);
      notificationService.error("Delete Failed", "Failed to delete property assertion. See console for details.");
    }
  };

  const handleAddSameAs = async (iri: string) => {
      try {
          await ontologyMutationService.addSameIndividual(projectId, item.id, iri, userId, username);
          // Optimistic update
          onUpdate({ ...item, sameIndividualAs: [...(item.sameIndividualAs || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleAddDifferentFrom = async (iri: string) => {
      try {
          await ontologyMutationService.addDifferentIndividual(projectId, item.id, iri, userId, username);
          // Optimistic update
          onUpdate({ ...item, differentIndividualFrom: [...(item.differentIndividualFrom || []), iri] });
      } catch (e) { console.error(e); }
  };

  const handleDeleteSameAs = async (iri: string) => {
      try {
        await ontologyMutationService.deleteSameIndividual(projectId, item.id, iri, userId, username);
        onUpdate({ ...item, sameIndividualAs: item.sameIndividualAs?.filter(i => i !== iri) });
      } catch (e) { console.error(e); }
  };

  const handleDeleteDifferentFrom = async (iri: string) => {
      try {
        await ontologyMutationService.deleteDifferentIndividual(projectId, item.id, iri, userId, username);
        onUpdate({ ...item, differentIndividualFrom: item.differentIndividualFrom?.filter(i => i !== iri) });
      } catch (e) { console.error(e); }
  };

  const openEditor = (title: string, action: (val: string) => void) => {
      setEditorTitle(title);
      setEditorAction(() => action);
      setIsEditorOpen(true);
  };
  
  return (
    <div className="flex flex-col h-full bg-white">
      {/* Header with IRI */}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="bg-purple-200 text-purple-800 p-1 rounded text-xs font-bold">I</div>
          <div className="flex flex-col min-w-0">
            <span className="font-bold text-sm truncate">{item.label}</span>
            <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
          </div>
        </div>
      </div>
      <CollaboratorPresenceBar entityId={item.id} />

      {/* Tabs */}
      <div className="flex border-b border-gray-200 bg-gray-50">
        <button 
          onClick={() => setActiveTab('types')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            activeTab === 'types' 
              ? 'border-purple-600 text-purple-700 bg-white' 
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
          }`}
        >
          Types & Assertions
        </button>
        <button 
          onClick={() => setActiveTab('usage')}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${
            activeTab === 'usage' 
              ? 'border-purple-600 text-purple-700 bg-white' 
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
          }`}
        >
          Usage
        </button>
      </div>

      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 space-y-4">
        {activeTab === 'types' && (
          <>
        {/* Annotations Section */}
        <Panel title="Annotations" defaultOpen={true} themeColor="bg-gradient-to-b from-gray-50 to-gray-100 text-gray-800 border-gray-200"
          actions={
            <button onClick={isViewOnly ? () => onViewOnlyAction?.() : onAddAnnotation} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600" title={isViewOnly ? "View-only: upgrade to edit" : "Add annotation"}>
              <Plus size={14} />
            </button>
          }
        >
          <div className="p-2">
            <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} isViewOnly={isViewOnly} onViewOnlyAction={onViewOnlyAction} />
          </div>
        </Panel>

        {/* Description Section */}
        <Panel title="Description" defaultOpen={true} themeColor="bg-gradient-to-b from-purple-50 to-purple-100 text-purple-900 border-purple-200">
          <div className="p-3 space-y-4">
            {/* Types */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Types</h4>
                <button onClick={isViewOnly ? () => onViewOnlyAction?.() : openTypeDialog} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title={isViewOnly ? "View-only: upgrade to edit" : "Add type"}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm p-1.5 space-y-1">
                {item.types?.map(type => (
                    <div key={type} className="text-xs p-1 bg-gray-50 rounded border border-gray-100 flex items-center gap-2">
                        <div className="w-2 h-2 rounded-full bg-yellow-500 flex-shrink-0"></div>
                        <span>{type.split('#').pop()}</span>
                    </div>
                ))}
                {(!item.types || item.types.length === 0) && (
                    <div className="text-xs text-gray-400 italic p-1">No types defined</div>
                )}
              </div>
            </div>

            {/* Object Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Object property assertions</h4>
                <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => openPropertyAssertionDialog(true)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-purple-600 transition-colors" title={isViewOnly ? "View-only: upgrade to edit" : "Add object property assertion"}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                    {isLoading ? (
                      <div className="flex items-center justify-center p-3 text-gray-500">
                        <Loader2 size={16} className="animate-spin mr-2" />
                        <span className="text-xs">Loading...</span>
                      </div>
                    ) : (
                      <>
                        {positiveObjectPropertyAssertions.map(assertion => (
                          <div key={assertion.id} className="group flex items-center justify-between text-xs bg-blue-50 p-1.5 rounded-sm border border-blue-100">
                              <div>
                                  <span className="font-semibold text-blue-700">{assertion.propertyLabel}</span>
                                  <span className="mx-1.5 text-gray-400">→</span>
                                  <span className="text-blue-600">{assertion.targetLabel || assertion.targetIri?.split('#').pop()}</span>
                              </div>
                              <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                                  <Trash2 size={12} className="text-red-600"/>
                              </button>
                          </div>
                        ))}
                        {positiveObjectPropertyAssertions.length === 0 && (
                          <div className="text-xs text-gray-400 italic p-1">No object property assertions</div>
                        )}
                      </>
                    )}
                </div>
              </div>
            </div>

            {/* Data Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Data property assertions</h4>
                <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => openPropertyAssertionDialog(false)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-green-600 transition-colors" title={isViewOnly ? "View-only: upgrade to edit" : "Add data property assertion"}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                    {isLoading ? (
                      <div className="flex items-center justify-center p-3 text-gray-500">
                        <Loader2 size={16} className="animate-spin mr-2" />
                        <span className="text-xs">Loading...</span>
                      </div>
                    ) : (
                      <>
                        {positiveDataPropertyAssertions.map(assertion => (
                          <div key={assertion.id} className="group flex items-center justify-between text-xs bg-green-50 p-1.5 rounded-sm border border-green-100">
                              <div>
                                  <span className="font-semibold text-green-700">{assertion.propertyLabel}</span>
                                  <span className="mx-1.5 text-gray-400">=</span>
                                  <span className="text-green-600">{assertion.targetLiteral}</span>
                              </div>
                              <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                                  <Trash2 size={12} className="text-red-600"/>
                              </button>
                          </div>
                        ))}
                        {positiveDataPropertyAssertions.length === 0 && (
                          <div className="text-xs text-gray-400 italic p-1">No data property assertions</div>
                        )}
                      </>
                    )}
                </div>
              </div>
            </div>

            {/* Negative Object Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Negative object property assertions</h4>
                <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => openPropertyAssertionDialog(true, true)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-red-600 transition-colors" title={isViewOnly ? "View-only: upgrade to edit" : "Add negative object property assertion"}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                  {negativeObjectPropertyAssertions.map(assertion => (
                    <div key={assertion.id} className="group flex items-center justify-between text-xs bg-red-50 p-1.5 rounded-sm border border-red-100">
                      <div>
                        <span className="font-semibold text-red-700">NOT</span>
                        <span className="mx-2 text-gray-400" />
                        <span className="font-semibold text-red-700">{assertion.propertyLabel}</span>
                        <span className="mx-1.5 text-gray-400">→</span>
                        <span className="text-red-600">{assertion.targetLabel || assertion.targetIri?.split('#').pop()}</span>
                      </div>
                      <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                        <Trash2 size={12} className="text-red-600" />
                      </button>
                    </div>
                  ))}
                  {negativeObjectPropertyAssertions.length === 0 && (
                    <div className="text-xs text-gray-400 italic p-1">No negative object property assertions</div>
                  )}
                </div>
              </div>
            </div>

            {/* Negative Data Property Assertions */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Negative data property assertions</h4>
                <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => openPropertyAssertionDialog(false, true)} className="p-1 hover:bg-gray-200 rounded text-gray-500 hover:text-red-600 transition-colors" title={isViewOnly ? "View-only: upgrade to edit" : "Add negative data property assertion"}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
                <div className="p-1.5 space-y-1">
                  {negativeDataPropertyAssertions.map(assertion => (
                    <div key={assertion.id} className="group flex items-center justify-between text-xs bg-red-50 p-1.5 rounded-sm border border-red-100">
                      <div>
                        <span className="font-semibold text-red-700">NOT</span>
                        <span className="mx-2 text-gray-400" />
                        <span className="font-semibold text-red-700">{assertion.propertyLabel}</span>
                        <span className="mx-1.5 text-gray-400">=</span>
                        <span className="text-red-600">{assertion.targetLiteral}</span>
                      </div>
                      <button onClick={isViewOnly ? () => onViewOnlyAction?.() : () => handleDeleteAssertion(assertion)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200">
                        <Trash2 size={12} className="text-red-600" />
                      </button>
                    </div>
                  ))}
                  {negativeDataPropertyAssertions.length === 0 && (
                    <div className="text-xs text-gray-400 italic p-1">No negative data property assertions</div>
                  )}
                </div>
              </div>
            </div>

            {/* Add Assertion Dialog (Protégé-style) */}
            <PropertyAssertionDialog
              isOpen={isAddingAssertion}
              title={
                isNegativeAssertion
                  ? `${newAssertion.isObjectProperty ? 'Negative object property assertions' : 'Negative data property assertions'}: ${item.label}`
                  : `Property assertions: ${item.label}`
              }
              isObjectProperty={newAssertion.isObjectProperty}
              objectPropertiesTree={objectPropertyHierarchy}
              dataPropertiesTree={dataPropertyHierarchy}
              propertySuggestions={propertySuggestions}
              targetSuggestions={individualSuggestions}
              initialPropertyLabel={newAssertion.propertyLabel}
              initialTargetLabel={newAssertion.targetLabel}
              onCancel={() => {
                setIsAddingAssertion(false);
                setNewAssertion(p => ({ ...p, propertyLabel: '', targetLabel: '' }));
                setIsNegativeAssertion(false);
              }}
              onConfirm={async (data) => {
                await handleAddAssertion(data);
                setIsAddingAssertion(false);
                setNewAssertion(p => ({ ...p, propertyLabel: '', targetLabel: '' }));
                setIsNegativeAssertion(false);
              }}
            />

            {/* Same Individual As / Different Individual From Section */}
            <div className="mb-4 last:mb-0">
              <div className="flex justify-between items-center mb-1">
                <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Same Individual As / Different Individual From</h4>
              </div>
              <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm p-1.5">
                {/* Same Individual As */}
                <MultiSelectSection
                    title="Same Individual As"
                    items={item.sameIndividualAs}
                    onAddClick={() => openSameDifferentDialog('same')}
                    onDelete={handleDeleteSameAs}
                    themeColor="purple"
                    itemEntityType="individual"
                    isViewOnly={isViewOnly}
                    onViewOnlyAction={onViewOnlyAction}
                    onNavigate={onNavigate}
                    projectId={projectId}
                    parentEntityIri={item.id}
                />

                {/* Different Individual From */}
                <MultiSelectSection
                    title="Different Individual From"
                    items={item.differentIndividualFrom}
                    onAddClick={() => openSameDifferentDialog('different')}
                    onDelete={handleDeleteDifferentFrom}
                    themeColor="purple"
                    itemEntityType="individual"
                    isViewOnly={isViewOnly}
                    onViewOnlyAction={onViewOnlyAction}
                    onNavigate={onNavigate}
                    projectId={projectId}
                    parentEntityIri={item.id}
                />
              </div>
            </div>
          </div>
        </Panel>
        </>
        )}

        {activeTab === 'usage' && (
          <IndividualUsageTab 
            individualIri={item.id} 
            projectId={projectId} 
            label={item.label || item.id.split(/[#/]/).pop() || ''} 
          />
        )}
      </div>

      {/* Manchester Syntax Editor Dialog */}
      {isEditorOpen && (
        <ManchesterSyntaxEditor
          isOpen={isEditorOpen}
          onClose={() => setIsEditorOpen(false)}
          title={editorTitle}
          onConfirm={val => {
              if (editorAction) editorAction(val);
              setIsEditorOpen(false);
          }}
          projectId={projectId}
        />
      )}

      {/* Protégé-style selector for same/different individuals */}
      {/* Protégé-style type selector */}
      {typeDialogOpen && (
        <ClassExpressionDialog
          isOpen={true}
          onClose={() => setTypeDialogOpen(false)}
          title={`Types: ${item.label}`}
          classHierarchy={typeClassHierarchy}
          projectId={projectId}
          onConfirm={(expression) => {
            handleAddType(expression);
            setTypeDialogOpen(false);
          }}
          objectProperties={[]} // We can pass these if needed for restrictions
          dataProperties={[]}
          allowedTabs={['hierarchy', 'classExpression', 'objectRestriction', 'dataRestriction']}
        />
      )}

      {sameDiffDialog && (
        <IndividualSelectorDialog
          isOpen={true}
          onClose={() => setSameDiffDialog(null)}
          title={sameDiffDialog.mode === 'same' ? `Same Individual As: ${item.label}` : `Different Individuals: ${item.label}`}
          individuals={allIndividuals}
          projectId={projectId}
          excludeIndividualIds={[
            item.id,
            ...(sameDiffDialog.mode === 'same' ? (item.sameIndividualAs || []) : (item.differentIndividualFrom || []))
          ]}
          minSelection={1}
          onConfirm={async (inds) => {
            if (sameDiffDialog.mode === 'same') {
              for (const ind of inds) await handleAddSameAs(ind.id);
            } else {
              for (const ind of inds) await handleAddDifferentFrom(ind.id);
            }
            setSameDiffDialog(null);
          }}
        />
      )}
    </div>
  );
};

export default IndividualEditor;
