import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Search, ExternalLink, AlertCircle, Edit3 } from 'lucide-react';
import { Panel, AnnotationsDisplay, AxiomSubsection } from './common';
import { ClassExpressionDialog, MultiClassSelectorDialog, MultiPropertySelectorDialog, IRIEditorDialog, RestrictionData } from '../dialogs';
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
      // Backend returns {success: true, data: [...]}
      // apiClient might wrap it in {data: {...}}
      const usageData = response?.data?.data || response?.data || response || [];
      console.log('[UsageTab] Loaded usages:', usageData);
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
  // Callbacks for creating entities inside dialogs
  onAddClass?: (type: 'subclass' | 'sibling') => void;
  onDeleteClass?: () => void;
  onRefreshClasses?: () => void;
  onAddObjectProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onAddDataProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteProperty?: () => void;
  metadata?: { ontologyIRI?: string };
  objectPropertyHierarchy?: TreeNode[];
  dataPropertyHierarchy?: TreeNode[];
  objectProperties?: any[];
  dataProperties?: any[];
}> = ({ item, projectId, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, classHierarchy = [], onToggleNode, expandedNodes = [], onAddClass, onDeleteClass, onRefreshClasses, onAddObjectProperty, onAddDataProperty, onDeleteProperty, metadata, objectPropertyHierarchy: propObjectPropertyHierarchy, dataPropertyHierarchy: propDataPropertyHierarchy, objectProperties: propObjectProperties, dataProperties: propDataProperties }) => {
  const [activeTab, setActiveTab] = useState<'annotations' | 'usage' | 'description'>('annotations');
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [classDetails, setClassDetails] = useState<any>(null);

  // Manchester Syntax Editor State
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorType, setEditorType] = useState<AxiomType | null>(null);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorExistingValue, setEditorExistingValue] = useState<string | undefined>();
  const [editorExistingId, setEditorExistingId] = useState<string | undefined>();
  const [editorInitialTab, setEditorInitialTab] = useState<'hierarchy' | 'objectRestriction' | 'dataRestriction' | 'classExpression' | undefined>();
  const [editorInitialRestrictionData, setEditorInitialRestrictionData] = useState<any>();

  // Properties for restriction creators - use props if available, otherwise local state
  const [properties, setProperties] = useState<any[]>(propObjectProperties || []);
  const [dataProperties, setDataProperties] = useState<any[]>(propDataProperties || []);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<TreeNode[]>(propObjectPropertyHierarchy || []);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<TreeNode[]>(propDataPropertyHierarchy || []);

  // Update local state when props change
  useEffect(() => {
    if (propObjectProperties) {
      console.log('[ClassEditor] Updating object properties from props:', propObjectProperties.length);
      setProperties(propObjectProperties);
    }
  }, [propObjectProperties]);

  useEffect(() => {
    if (propDataProperties) {
      console.log('[ClassEditor] Updating data properties from props:', propDataProperties.length);
      setDataProperties(propDataProperties);
    }
  }, [propDataProperties]);

  useEffect(() => {
    if (propObjectPropertyHierarchy) {
      console.log('[ClassEditor] Updating object property hierarchy from props, nodes:', propObjectPropertyHierarchy.length);
      setObjectPropertyHierarchy(propObjectPropertyHierarchy);
    }
  }, [propObjectPropertyHierarchy]);

  useEffect(() => {
    if (propDataPropertyHierarchy) {
      console.log('[ClassEditor] Updating data property hierarchy from props, nodes:', propDataPropertyHierarchy.length);
      setDataPropertyHierarchy(propDataPropertyHierarchy);
    }
  }, [propDataPropertyHierarchy]);

  // Disjoint With State (multi-class selector like Protégé)
  const [isDisjointWithOpen, setIsDisjointWithOpen] = useState(false);
  const [editingDisjointWithId, setEditingDisjointWithId] = useState<string | undefined>();
  const [editingDisjointWithTarget, setEditingDisjointWithTarget] = useState<string | undefined>();

  // Disjoint Union State
  const [isDisjointUnionOpen, setIsDisjointUnionOpen] = useState(false);
  const [editingDisjointUnionId, setEditingDisjointUnionId] = useState<string | undefined>();
  const [editingDisjointUnionMembers, setEditingDisjointUnionMembers] = useState<string[]>([]);

  // Has Key State
  const [isHasKeyOpen, setIsHasKeyOpen] = useState(false);
  const [editingHasKeyId, setEditingHasKeyId] = useState<string | undefined>();
  const [editingHasKeyProperties, setEditingHasKeyProperties] = useState<string[]>([]);

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
      // Backend returns { success: true, data: [...] }
      // apiClient might wrap it in { data: {...} }
      const allProps = allPropsResponse?.data?.data || allPropsResponse?.data || [];

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
      // Backend returns {success: true, data: {...}}
      const details = response?.data?.data || response?.data || response;
      console.log('[ClassEditor] Class details loaded:', details);
      setClassDetails(details);
      
      // Update the item with all loaded details (annotations, axioms, etc.)
      // Debug logging for axioms
      console.log('[ClassEditor] Axioms from backend:', {
        subClassOf: details.subClassOfAxioms?.length || 0,
        equivalentTo: details.equivalentClassesAxioms?.length || 0,
        disjointWith: details.disjointClassesAxioms?.length || 0,
        disjointUnion: details.disjointUnionAxioms?.length || 0,
        hasKey: details.hasKeyAxioms?.length || 0
      });
      
      const updatedItem: TreeNode = {
        ...item,
        annotations: details.annotations || item.annotations,
        subClassOfAxioms: details.subClassOfAxioms || item.subClassOfAxioms,
        equivalentClassesAxioms: details.equivalentClassesAxioms || item.equivalentClassesAxioms,
        disjointClassesAxioms: details.disjointClassesAxioms || item.disjointClassesAxioms,
        disjointUnionAxioms: details.disjointUnionAxioms || item.disjointUnionAxioms,
        hasKeyAxioms: details.hasKeyAxioms || item.hasKeyAxioms
      };
      console.log('[ClassEditor] Updated item:', updatedItem);
      onUpdate(updatedItem);
    } catch (error) {
      console.error('Failed to load class details:', error);
    } finally {
      setLoadingDetails(false);
    }
  };

  const openEditor = (type: AxiomType, title: string, existingValue?: string, existingId?: string, initialTab?: 'hierarchy' | 'objectRestriction' | 'dataRestriction' | 'classExpression', restrictionData?: any) => {
    setEditorType(type);
    // Update title to indicate edit mode
    if (existingValue && existingId) {
      setEditorTitle(`Edit ${title}`);
      // For hierarchy tab (simple class axioms), pass the IRI as initialValue so it can be pre-selected
      // For other tabs (restrictions, expressions), pass the definition/label
      setEditorExistingValue(initialTab === 'hierarchy' ? existingId : existingValue);
      setEditorExistingId(existingId);
    } else {
      setEditorTitle(`Add ${title}`);
      setEditorExistingValue(undefined);
      setEditorExistingId(undefined);
    }
    setEditorInitialTab(initialTab);
    setEditorInitialRestrictionData(restrictionData);
    setIsEditorOpen(true);
  };

  const handleEditorConfirm = async (expression: string, restrictionData?: RestrictionData) => {
    if (editorType) {
      // If we have an existing axiom ID, this is an edit operation
      if (editorExistingId) {
        console.log('[ClassEditor] Edit operation - deleting old axiom:', { 
          editorExistingId, 
          editorType, 
          newExpression: expression,
          classIri: item.id,
          editorInitialRestrictionData,
          restrictionData 
        });
        
        // For edit, we need to delete the old one first, then add the new one
        // Check if the old axiom was a restriction that needs special handling
        if (editorInitialRestrictionData) {
          // Delete the old restriction
          const axiomType = editorType === 'EquivalentTo' ? 'EquivalentTo' : 'SubClassOf';
          
          console.log('[ClassEditor] Deleting old restriction:', editorInitialRestrictionData);
          if (editorInitialRestrictionData.isDataProperty) {
            await ontologyMutationService.deleteDataRestriction(
              projectId,
              item.id,
              axiomType,
              editorInitialRestrictionData.propertyIri!,
              editorInitialRestrictionData.restrictionType!,
              editorInitialRestrictionData.fillerIri!
            );
          } else {
            await ontologyMutationService.deleteObjectRestriction(
              projectId,
              item.id,
              axiomType,
              editorInitialRestrictionData.propertyIri!,
              editorInitialRestrictionData.restrictionType!,
              editorInitialRestrictionData.fillerIri!
            );
          }
          // Wait for GraphDB to process the deletion - increased delay for restrictions
          await new Promise(resolve => setTimeout(resolve, 1000));
          console.log('[ClassEditor] Waited 1000ms after restriction deletion');
        } else {
          // Delete old simple class axiom (not a restriction)
          // The editorExistingId should be the IRI of the target class
          console.log('[ClassEditor] Editing simple class axiom - using UPDATE instead of DELETE+ADD:', { 
            editorExistingId, 
            editorType,
            classIri: item.id,
            oldTarget: editorExistingId,
            newTarget: expression
          });
          
          // Use UPDATE operations to replace in a single transaction
          // This prevents creating duplicates
          const isNewSimpleIRI = expression.startsWith('http://') || expression.startsWith('https://') || expression.startsWith('urn:');
          
          if (isNewSimpleIRI) {
            switch (editorType) {
              case 'EquivalentTo':
                console.log('[ClassEditor] Calling updateEquivalentClass with:', { projectId, classIri: item.id, oldTarget: editorExistingId, newTarget: expression });
                await ontologyMutationService.updateEquivalentClass(projectId, item.id, editorExistingId, expression);
                console.log('[ClassEditor] updateEquivalentClass completed');
                break;
              case 'SubClassOf':
                console.log('[ClassEditor] Calling updateSubClassOf with:', { projectId, classIri: item.id, oldTarget: editorExistingId, newTarget: expression });
                await ontologyMutationService.updateSubClassOf(projectId, item.id, editorExistingId, expression);
                console.log('[ClassEditor] updateSubClassOf completed');
                break;
              case 'DisjointWith':
                console.log('[ClassEditor] Calling updateDisjointWith with:', { projectId, classIri: item.id, oldTarget: editorExistingId, newTarget: expression });
                await ontologyMutationService.updateDisjointWith(projectId, item.id, editorExistingId, expression);
                console.log('[ClassEditor] updateDisjointWith completed');
                break;
            }
            // Wait for GraphDB to process the update
            await new Promise(resolve => setTimeout(resolve, 500));
            console.log('[ClassEditor] Waited 500ms after update operation');
            
            // Reload details to reflect the changes
            console.log('[ClassEditor] Reloading class details after edit');
            await loadClassDetails();
            console.log('[ClassEditor] Class details reloaded');
            
            // Close the dialog
            // Close the dialog
            setIsEditorOpen(false);
            setEditorExistingId(undefined);
            return;
          } else {
            // For complex expressions, still use delete+add
            console.log('[ClassEditor] Complex expression detected, using delete+add approach');
            switch (editorType) {
              case 'EquivalentTo':
                await ontologyMutationService.deleteEquivalentClass(projectId, item.id, editorExistingId);
                break;
              case 'SubClassOf':
                await ontologyMutationService.deleteSubClassOf(projectId, item.id, editorExistingId);
                break;
              case 'DisjointWith':
                await ontologyMutationService.deleteDisjointWith(projectId, item.id, editorExistingId);
                break;
            }
            // Wait for GraphDB to process the deletion
            await new Promise(resolve => setTimeout(resolve, 500));
            
            // Add the new complex expression axiom
            await ontologyMutationService.addAxiom(projectId, item.id, editorType, expression);
            
            // Reload details to reflect the changes
            console.log('[ClassEditor] Reloading class details after complex expression edit');
            await loadClassDetails();
            console.log('[ClassEditor] Class details reloaded');
            
            // Close the dialog
            setIsEditorOpen(false);
            setEditorExistingId(undefined);
            return;
          }
        }
        
        // For restriction edits, continue with the delete+add flow
        console.log('[ClassEditor] Adding new restriction axiom after deletion:', { expression, restrictionData, editorType });
        
        // SAFETY CHECK: If this is a restriction edit, verify that we're not adding a duplicate
        // Check if an axiom with the same property and filler already exists
        if (restrictionData) {
          // Get the correct axiom array based on axiom type
          let existingAxioms: any[] = [];
          if (editorType === 'SubClassOf') {
            existingAxioms = classDetails?.subClassOfAxioms || item.subClassOfAxioms || [];
          } else if (editorType === 'EquivalentTo') {
            existingAxioms = classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms || [];
          } else if (editorType === 'DisjointWith') {
            existingAxioms = classDetails?.disjointWithAxioms || item.disjointWithAxioms || [];
          }
          
          const isDuplicate = existingAxioms.some((axiom: any) => {
            // Check if axiom has the same property and filler
            return axiom.propertyIri === restrictionData.propertyIri && 
                   axiom.fillerIri === restrictionData.fillerIri &&
                   axiom.restrictionType === restrictionData.restrictionType;
          });
          
          if (isDuplicate) {
            console.warn('[ClassEditor] ⚠️  DUPLICATE DETECTED - Not adding axiom that already exists with same restriction:', {
              editorType,
              propertyIri: restrictionData.propertyIri,
              fillerIri: restrictionData.fillerIri,
              restrictionType: restrictionData.restrictionType,
              existingAxiomsCount: existingAxioms.length
            });
            // Skip the add operation - the axiom already exists
            setIsEditorOpen(false);
            setEditorExistingId(undefined);
            return;
          }
        }
        
        // Add the new restriction axiom
        await handleAddAxiom(editorType, expression, restrictionData);
      } else {
        // Otherwise it's an add operation
        console.log('[ClassEditor] Add operation:', { expression, restrictionData });
        await handleAddAxiom(editorType, expression, restrictionData);
      }
    }
    setIsEditorOpen(false);
    setEditorType(null);
    setEditorExistingValue(undefined);
    setEditorExistingId(undefined);
    setEditorInitialTab(undefined);
    setEditorInitialRestrictionData(undefined);
  };

  const handleAddAxiom = async (type: AxiomType, definition: string, restrictionData?: RestrictionData) => {
    try {
      // If we have structured restriction data, use the specific restriction methods
      // NOTE: DisjointWith does NOT support restrictions - it's only for class-to-class disjointness
      if (restrictionData && type !== 'DisjointWith') {
        // Set the axiom type from the editor type
        restrictionData.axiomType = type;
        
        if (restrictionData.type === 'objectRestriction') {
          await ontologyMutationService.addObjectRestriction(
            projectId,
            item.id,
            restrictionData.axiomType,
            restrictionData.propertyIri,
            restrictionData.restrictionType,
            restrictionData.fillerIri,
            restrictionData.cardinality
          );
        } else if (restrictionData.type === 'dataRestriction') {
          // Only allow valid restrictionType values for data restrictions
          const validDataRestrictionTypes = ['some', 'only', 'min', 'max', 'exactly'];
          if (validDataRestrictionTypes.includes(restrictionData.restrictionType)) {
            await ontologyMutationService.addDataRestriction(
              projectId,
              item.id,
              restrictionData.axiomType,
              restrictionData.propertyIri,
              restrictionData.restrictionType as 'some' | 'only' | 'min' | 'max' | 'exactly',
              restrictionData.fillerIri,
              restrictionData.cardinality
            );
          } else {
            console.warn('Invalid restrictionType for data restriction:', restrictionData.restrictionType);
          }
        }
        // Reload details to get the updated axioms
        await loadClassDetails();
        return;
      }

      // Check if definition is a simple class IRI (starts with http:// or urn:)
      // For simple IRIs, use the specific mutation methods that work with the backend
      const isSimpleIRI = definition.startsWith('http://') || definition.startsWith('https://') || definition.startsWith('urn:');
      
      if (isSimpleIRI) {
        // Use specific mutation methods for simple class relationships
        switch (type) {
          case 'EquivalentTo':
            await ontologyMutationService.addEquivalentClass(projectId, item.id, definition);
            break;
          case 'SubClassOf':
            await ontologyMutationService.addSubClassOf(projectId, item.id, definition);
            break;
          case 'DisjointWith':
            await ontologyMutationService.addDisjointWith(projectId, item.id, definition);
            break;
        }
      } else {
        // For complex Manchester Syntax expressions, use addAxiom (requires backend Manchester parser)
        await ontologyMutationService.addAxiom(projectId, item.id, type, definition);
      }
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
    console.log('[ClassEditor] handleDeleteAxiom called:', { type, id, classIri: item.id });
    try {
      // Find the axiom object to check if it's a restriction
      let axiomArrays: { EquivalentTo?: Axiom[], SubClassOf?: Axiom[], DisjointWith?: Axiom[] } = {
        EquivalentTo: item.equivalentClassesAxioms,
        SubClassOf: item.subClassOfAxioms,
        DisjointWith: item.disjointClassesAxioms
      };
      const axiom = axiomArrays[type]?.find(a => a.id === id);
      console.log('[ClassEditor] Found axiom:', axiom);
      
      // Check if this is a restriction (isRestriction can be boolean or string "true")
      const isRestriction = axiom?.isRestriction === true || axiom?.isRestriction === 'true';
      
      if (isRestriction && axiom?.propertyIri && axiom?.restrictionType && axiom?.fillerIri) {
        // Delete restriction - map type to axiomType parameter
        const axiomType = type === 'EquivalentTo' ? 'EquivalentTo' : 'SubClassOf';
        
        // Check if it's a data property restriction
        const isDataProperty = axiom.propertyIri === 'http://www.w3.org/2002/07/owl#topDataProperty' 
          || dataProperties.some(p => p.id === axiom.propertyIri);
        
        console.log('[ClassEditor] Deleting restriction:', { 
          classIri: item.id, 
          axiomType, 
          propertyIri: axiom.propertyIri,
          restrictionType: axiom.restrictionType,
          fillerIri: axiom.fillerIri,
          isDataProperty
        });
        
        if (isDataProperty) {
          await ontologyMutationService.deleteDataRestriction(
            projectId,
            item.id,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as 'some' | 'only' | 'min' | 'max' | 'exactly',
            axiom.fillerIri
          );
        } else {
          await ontologyMutationService.deleteObjectRestriction(
            projectId,
            item.id,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value',
            axiom.fillerIri
          );
        }
        // Wait for GraphDB to process the deletion
        await new Promise(resolve => setTimeout(resolve, 300));
        await loadClassDetails();
      } else {
        // The id is usually the IRI of the related class
        // Always attempt to delete - the backend will handle validation
        console.log('[ClassEditor] Deleting simple class axiom:', { type, classIri: item.id, targetIri: id });
        
        switch (type) {
          case 'EquivalentTo':
            console.log('[ClassEditor] Calling deleteEquivalentClass');
            await ontologyMutationService.deleteEquivalentClass(projectId, item.id, id);
            break;
          case 'SubClassOf':
            console.log('[ClassEditor] Calling deleteSubClassOf with params:', { projectId, classIri: item.id, superClassIri: id });
            await ontologyMutationService.deleteSubClassOf(projectId, item.id, id);
            console.log('[ClassEditor] deleteSubClassOf completed');
            break;
          case 'DisjointWith':
            console.log('[ClassEditor] Calling deleteDisjointWith');
            await ontologyMutationService.deleteDisjointWith(projectId, item.id, id);
            break;
        }
        // Small delay to allow GraphDB to process the mutation
        await new Promise(resolve => setTimeout(resolve, 300));
        // Reload to reflect changes
        console.log('[ClassEditor] Reloading class details after delete');
        await loadClassDetails();
        console.log('[ClassEditor] loadClassDetails completed');
      }
    } catch (error) {
      console.error('[ClassEditor] Failed to delete axiom:', error);
      console.error('[ClassEditor] Delete axiom details:', { type, id, classIri: item.id });
      alert(`Failed to delete axiom: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  const handleEditAxiom = async (type: AxiomType, oldId: string, newDefinition: string) => {
    try {
      console.log('[ClassEditor] handleEditAxiom called:', { type, oldId, newDefinition });
      
      // Check if both old and new are simple IRIs
      const isOldSimpleIRI = oldId.startsWith('http://') || oldId.startsWith('https://') || oldId.startsWith('urn:');
      const isNewSimpleIRI = newDefinition.startsWith('http://') || newDefinition.startsWith('https://') || newDefinition.startsWith('urn:');
      
      // If both are simple IRIs, use atomic UPDATE operations
      if (isOldSimpleIRI && isNewSimpleIRI) {
        console.log('[ClassEditor] Using atomic UPDATE operation');
        switch (type) {
          case 'EquivalentTo':
            await ontologyMutationService.updateEquivalentClass(projectId, item.id, oldId, newDefinition);
            break;
          case 'SubClassOf':
            await ontologyMutationService.updateSubClassOf(projectId, item.id, oldId, newDefinition);
            break;
          case 'DisjointWith':
            await ontologyMutationService.updateDisjointWith(projectId, item.id, oldId, newDefinition);
            break;
        }
      } else {
        // For complex expressions or mixed cases, use delete + add
        console.log('[ClassEditor] Using DELETE + ADD approach for complex/mixed expressions');
        
        // Delete the old axiom
        if (isOldSimpleIRI) {
          switch (type) {
            case 'EquivalentTo':
              await ontologyMutationService.deleteEquivalentClass(projectId, item.id, oldId);
              break;
            case 'SubClassOf':
              await ontologyMutationService.deleteSubClassOf(projectId, item.id, oldId);
              break;
            case 'DisjointWith':
              await ontologyMutationService.deleteDisjointWith(projectId, item.id, oldId);
              break;
          }
        }
        
        // Small delay to allow deletion to process
        await new Promise(resolve => setTimeout(resolve, 300));
        
        // Add the new axiom
        if (isNewSimpleIRI) {
          switch (type) {
            case 'EquivalentTo':
              await ontologyMutationService.addEquivalentClass(projectId, item.id, newDefinition);
              break;
            case 'SubClassOf':
              await ontologyMutationService.addSubClassOf(projectId, item.id, newDefinition);
              break;
            case 'DisjointWith':
              await ontologyMutationService.addDisjointWith(projectId, item.id, newDefinition);
              break;
          }
        } else {
          // For complex Manchester Syntax expressions
          await ontologyMutationService.addAxiom(projectId, item.id, type, newDefinition);
        }
      }
      
      // Small delay before reloading
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after edit');
      await loadClassDetails();
      console.log('[ClassEditor] Edit completed successfully');
    } catch (error) {
      console.error('[ClassEditor] Failed to edit axiom:', error);
      alert(`Failed to edit axiom: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  // Handler for Disjoint With - adds owl:disjointWith for each selected class
  const handleDisjointWithConfirm = async (nodes: TreeNode[]) => {
    console.log('[ClassEditor] handleDisjointWithConfirm called:', { nodes: nodes.map(n => ({ id: n.id, label: n.label })), isEditing: !!editingDisjointWithId });
    try {
      const classIris = nodes.map(n => n.id);
      
      if (classIris.length < 1) {
        console.warn('[ClassEditor] Please select at least 1 class');
        alert('Please select at least 1 class');
        return;
      }
      
      // If editing, delete the old one first
      if (editingDisjointWithId) {
        console.log('[ClassEditor] Editing disjoint with - deleting old:', editingDisjointWithId);
        await ontologyMutationService.deleteDisjointWith(projectId, item.id, editingDisjointWithId);
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      
      // Add disjoint with for each selected class
      for (const targetIri of classIris) {
        console.log('[ClassEditor] Adding disjoint with:', { classIri: item.id, targetIri });
        await ontologyMutationService.addDisjointWith(projectId, item.id, targetIri);
      }
      
      // Small delay to allow GraphDB to process the mutations
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after adding disjoint with');
      await loadClassDetails();
      console.log('[ClassEditor] loadClassDetails completed after disjoint with');
    } catch (error) {
      console.error('[ClassEditor] Failed to add disjoint with:', error);
      alert(`Failed to add disjoint with: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsDisjointWithOpen(false);
      setEditingDisjointWithId(undefined);
      setEditingDisjointWithTarget(undefined);
    }
  };

  const handleEditDisjointWith = (axiomId: string) => {
    console.log('[ClassEditor] handleEditDisjointWith called:', { classIri: item.id, axiomId });
    // The axiomId is the IRI of the disjoint class
    setEditingDisjointWithId(axiomId);
    setEditingDisjointWithTarget(axiomId);
    setIsDisjointWithOpen(true);
  };

  const handleDisjointUnionConfirm = async (nodes: TreeNode[]) => {
    console.log('[ClassEditor] handleDisjointUnionConfirm called:', { nodes: nodes.map(n => ({ id: n.id, label: n.label })), isEditing: !!editingDisjointUnionId });
    try {
      // Get the IRIs of the selected classes
      const memberIris = nodes.map(n => n.id);
      
      if (memberIris.length < 2) {
        console.warn('[ClassEditor] Disjoint Union requires at least 2 classes');
        alert('Please select at least 2 classes for the disjoint union.');
        return;
      }
      
      // If editing, delete the old one first
      if (editingDisjointUnionId) {
        console.log('[ClassEditor] Editing disjoint union - deleting old:', editingDisjointUnionId);
        await ontologyMutationService.deleteDisjointUnion(projectId, item.id, editingDisjointUnionId);
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      
      console.log('[ClassEditor] Adding disjoint union:', { classIri: item.id, memberIris });
      // Use the new addDisjointUnion method
      await ontologyMutationService.addDisjointUnion(projectId, item.id, memberIris);
      console.log('[ClassEditor] addDisjointUnion completed');
      
      // Small delay to allow GraphDB to process the mutation
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after adding disjoint union');
      await loadClassDetails();
      console.log('[ClassEditor] loadClassDetails completed after disjoint union');
    } catch (error) {
      console.error('[ClassEditor] Failed to add disjoint union:', error);
      alert(`Failed to add disjoint union: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsDisjointUnionOpen(false);
      setEditingDisjointUnionId(undefined);
      setEditingDisjointUnionMembers([]);
    }
  };

  const handleEditDisjointUnion = async (listNodeId: string) => {
    console.log('[ClassEditor] handleEditDisjointUnion called:', { classIri: item.id, listNodeId });
    // Find the disjoint union axiom to get current members
    const disjointUnionAxiom = (classDetails?.disjointUnionAxioms || item.disjointUnionAxioms)?.find((ax: Axiom) => ax.id === listNodeId);
    if (!disjointUnionAxiom) {
      console.error('[ClassEditor] Disjoint union axiom not found:', listNodeId);
      return;
    }
    
    // Extract member IRIs from the axiom definition
    // The definition format is like: "Class1, Class2, Class3" or contains IRIs
    const members = disjointUnionAxiom.members || [];
    console.log('[ClassEditor] Found disjoint union members:', members);
    
    // Set edit state and open dialog
    setEditingDisjointUnionId(listNodeId);
    setEditingDisjointUnionMembers(members);
    setIsDisjointUnionOpen(true);
  };

  const handleDeleteDisjointUnion = async (listNodeId: string) => {
    console.log('[ClassEditor] handleDeleteDisjointUnion called:', { classIri: item.id, listNodeId });
    try {
      await ontologyMutationService.deleteDisjointUnion(projectId, item.id, listNodeId);
      console.log('[ClassEditor] deleteDisjointUnion completed');
      
      // Small delay to allow GraphDB to process the mutation
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after deleting disjoint union');
      await loadClassDetails();
      console.log('[ClassEditor] loadClassDetails completed after delete disjoint union');
    } catch (error) {
      console.error('[ClassEditor] Failed to delete disjoint union:', error);
      alert(`Failed to delete disjoint union: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  const handleEditHasKey = async (listNodeId: string) => {
    console.log('[ClassEditor] handleEditHasKey called:', { classIri: item.id, listNodeId });
    // Find the has key axiom to get current properties
    const hasKeyAxiom = (classDetails?.hasKeyAxioms || item.hasKeyAxioms)?.find((ax: Axiom) => ax.id === listNodeId);
    if (!hasKeyAxiom) {
      console.error('[ClassEditor] Has key axiom not found:', listNodeId);
      return;
    }
    
    // Extract property IRIs from the axiom
    const props = hasKeyAxiom.properties || [];
    console.log('[ClassEditor] Found has key properties:', props);
    
    // Set edit state and open dialog
    setEditingHasKeyId(listNodeId);
    setEditingHasKeyProperties(props);
    setIsHasKeyOpen(true);
  };

  const handleDeleteHasKey = async (listNodeId: string) => {
    console.log('[ClassEditor] handleDeleteHasKey called:', { classIri: item.id, listNodeId });
    try {
      await ontologyMutationService.deleteHasKey(projectId, item.id, listNodeId);
      console.log('[ClassEditor] deleteHasKey completed');
      
      // Small delay to allow GraphDB to process the mutation
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after deleting has key');
      await loadClassDetails();
      console.log('[ClassEditor] loadClassDetails completed after delete has key');
    } catch (error) {
      console.error('[ClassEditor] Failed to delete has key:', error);
      alert(`Failed to delete has key: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  const handleAddHasKey = async (propertyIris: string[]) => {
    console.log('[ClassEditor] handleAddHasKey called:', { classIri: item.id, propertyIris, isEditing: !!editingHasKeyId });
    try {
      if (propertyIris.length < 1) {
        console.warn('[ClassEditor] HasKey requires at least 1 property');
        alert('Please select at least 1 property for the key.');
        return;
      }
      
      // If editing, delete the old one first
      if (editingHasKeyId) {
        console.log('[ClassEditor] Editing has key - deleting old:', editingHasKeyId);
        await ontologyMutationService.deleteHasKey(projectId, item.id, editingHasKeyId);
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      
      console.log('[ClassEditor] Adding has key:', { classIri: item.id, propertyIris });
      await ontologyMutationService.addHasKey(projectId, item.id, propertyIris);
      console.log('[ClassEditor] addHasKey completed');
      
      // Small delay to allow GraphDB to process the mutation
      await new Promise(resolve => setTimeout(resolve, 300));
      console.log('[ClassEditor] Reloading class details after adding has key');
      await loadClassDetails();
      console.log('[ClassEditor] loadClassDetails completed after has key');
    } catch (error) {
      console.error('[ClassEditor] Failed to add has key:', error);
      alert(`Failed to add has key: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsHasKeyOpen(false);
      setEditingHasKeyId(undefined);
      setEditingHasKeyProperties([]);
    }
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
                  axioms={classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms}
                  onAdd={(def) => handleAddAxiom('EquivalentTo', def)}
                  onEdit={(id, newDef) => handleEditAxiom('EquivalentTo', id, newDef)}
                  onDelete={(id) => handleDeleteAxiom('EquivalentTo', id)}
                  onAddClick={() => openEditor('EquivalentTo', 'Equivalent Class Expression')}
                  onEditClick={(axiom, initialTab, restrictionData) => openEditor('EquivalentTo', 'Equivalent Class Expression', axiom.definition, axiom.id, initialTab, restrictionData)}
                  properties={properties}
                  dataProperties={dataProperties}
                />
                
                <AxiomSubsection
                  title="SubClass Of"
                  axioms={classDetails?.subClassOfAxioms || item.subClassOfAxioms}
                  onAdd={(def) => handleAddAxiom('SubClassOf', def)}
                  onEdit={(id, newDef) => handleEditAxiom('SubClassOf', id, newDef)}
                  onDelete={(id) => handleDeleteAxiom('SubClassOf', id)}
                  onAddClick={() => openEditor('SubClassOf', 'SubClass Expression')}
                  onEditClick={(axiom, initialTab, restrictionData) => openEditor('SubClassOf', 'SubClass Expression', axiom.definition, axiom.id, initialTab, restrictionData)}
                  properties={properties}
                  dataProperties={dataProperties}
                />
                
                <AxiomSubsection
                  title="Disjoint With"
                  axioms={classDetails?.disjointClassesAxioms || item.disjointClassesAxioms}
                  onAdd={(def) => handleAddAxiom('DisjointWith', def)}
                  onEdit={(id, newDef) => handleEditDisjointWith(id)}
                  onDelete={(id) => handleDeleteAxiom('DisjointWith', id)}
                  onAddClick={() => setIsDisjointWithOpen(true)}
                  onEditClick={(axiom) => handleEditDisjointWith(axiom.id)}
                  emptyMessage="No disjoint classes defined"
                  properties={properties}
                  dataProperties={dataProperties}
                />
                
                <AxiomSubsection
                  title="Disjoint Union Of"
                  axioms={classDetails?.disjointUnionAxioms || item.disjointUnionAxioms}
                  onAdd={() => {}}
                  onEdit={(id, newDef) => handleEditDisjointUnion(id)}
                  onDelete={(id) => handleDeleteDisjointUnion(id)}
                  onAddClick={() => setIsDisjointUnionOpen(true)}
                  onEditClick={(axiom) => handleEditDisjointUnion(axiom.id)}
                  emptyMessage="No disjoint unions defined"
                />

                <AxiomSubsection
                  title="Has Key"
                  axioms={classDetails?.hasKeyAxioms || item.hasKeyAxioms}
                  onAdd={() => {}}
                  onEdit={(id, newDef) => handleEditHasKey(id)}
                  onDelete={(id) => handleDeleteHasKey(id)}
                  onAddClick={() => setIsHasKeyOpen(true)}
                  onEditClick={(axiom) => handleEditHasKey(axiom.id)}
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
        onClose={() => {
          setIsEditorOpen(false);
          setEditorExistingValue(undefined);
          setEditorExistingId(undefined);
          setEditorInitialTab(undefined);
          setEditorInitialRestrictionData(undefined);
        }}
        onConfirm={handleEditorConfirm}
        title={editorTitle}
        initialValue={editorExistingValue}
        initialTab={editorInitialTab}
        initialRestrictionData={editorInitialRestrictionData}
        classHierarchy={classHierarchy}
        objectProperties={properties}
        dataProperties={dataProperties}
        objectPropertiesTree={objectPropertyHierarchy}
        dataPropertiesTree={dataPropertyHierarchy}
        expandedNodes={expandedNodes}
        onToggleNode={onToggleNode}
        projectId={projectId}
        onAddClass={onAddClass}
        onDeleteClass={onDeleteClass}
        onAddObjectProperty={onAddObjectProperty}
        onAddDataProperty={onAddDataProperty}
        onDeleteProperty={onDeleteProperty}
        onRefreshClasses={onRefreshClasses}
        metadata={metadata}
      />

      {/* Disjoint With Class Selector (like Desktop Protégé) */}
      <MultiClassSelectorDialog
        isOpen={isDisjointWithOpen}
        onClose={() => {
          setIsDisjointWithOpen(false);
          setEditingDisjointWithId(undefined);
          setEditingDisjointWithTarget(undefined);
        }}
        onConfirm={handleDisjointWithConfirm}
        classHierarchy={classHierarchy}
        projectId={projectId}
        onToggleNode={onToggleNode}
        externalExpandedNodes={expandedNodes}
        title={editingDisjointWithId ? "Edit Disjoint Class" : "Select Disjoint Classes"}
        excludeClassIds={[item.id]}
        minSelection={1}
        initialSelectedIds={editingDisjointWithTarget ? [editingDisjointWithTarget] : []}
      />

      {/* Disjoint Union Selector */}
      <MultiClassSelectorDialog
        isOpen={isDisjointUnionOpen}
        onClose={() => {
          setIsDisjointUnionOpen(false);
          setEditingDisjointUnionId(undefined);
          setEditingDisjointUnionMembers([]);
        }}
        onConfirm={handleDisjointUnionConfirm}
        classHierarchy={classHierarchy}
        projectId={projectId}
        onToggleNode={onToggleNode}
        externalExpandedNodes={expandedNodes}
        title={editingDisjointUnionId ? "Edit Disjoint Union Classes" : "Select Classes for Disjoint Union"}
        minSelection={2}
        initialSelectedIds={editingDisjointUnionMembers}
      />

      {/* Has Key Property Selector */}
      <MultiPropertySelectorDialog
        isOpen={isHasKeyOpen}
        onClose={() => {
          setIsHasKeyOpen(false);
          setEditingHasKeyId(undefined);
          setEditingHasKeyProperties([]);
        }}
        onConfirm={handleAddHasKey}
        objectProperties={properties}
        dataProperties={dataProperties}
        objectPropertyHierarchy={objectPropertyHierarchy}
        dataPropertyHierarchy={dataPropertyHierarchy}
        expandedNodes={expandedNodes}
        onToggleNode={onToggleNode}
        title={editingHasKeyId ? "Edit Key Properties (HasKey)" : "Select Key Properties (HasKey)"}
        minSelection={1}
        initialSelectedIds={editingHasKeyProperties}
        projectId={projectId}
        onAddObjectProperty={onAddObjectProperty}
        onAddDataProperty={onAddDataProperty}
        onDeleteProperty={onDeleteProperty}
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
