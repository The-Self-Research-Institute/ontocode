import React, { useState, useEffect } from 'react';
import { Plus, Trash2, Search, ExternalLink, AlertCircle, Edit3, User } from 'lucide-react';
import { Panel, AnnotationsDisplay, AxiomSubsection } from './common';
import { ClassExpressionDialog, MultiClassSelectorDialog, MultiPropertySelectorDialog, IRIEditorDialog, IndividualSelectorDialog, RestrictionData } from '../dialogs';
import apiClient from '../../services/apiClient';
import ontologyMutationService from '../../services/ontologyMutationService';
import { useAuth } from '../../custom-hook/useAuth';
import type { TreeNode, Axiom, ClassUsage, AxiomUsage, Individual } from '../../types';

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
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
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

  // Reset loaded state when class changes so the user must explicitly
  // load usage for each class (this query is very expensive on large graphs).
  useEffect(() => {
    setLoaded(false);
    setUsages([]);
    setFilter('');
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
      setLoaded(true);
    } catch (error) {
      console.error('Failed to load usage data:', error);
      setUsages([]);
      setLoaded(true);
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

  if (!loaded && !loading) {
    return (
      <div className="flex flex-col items-center justify-center h-full p-6 text-center">
        <div className="text-sm text-gray-600 mb-3">
          Usage lookup for <span className="font-semibold">{label}</span> can be slow on large ontologies.
        </div>
        <button
          onClick={loadUsages}
          className="px-4 py-2 text-sm rounded bg-purple-600 text-white hover:bg-purple-700"
        >
          Load usage
        </button>
      </div>
    );
  }

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
  onAddClassInline?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
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
  viewMode?: 'asserted' | 'inferred';
  // Individual-related props
  individuals?: Individual[];
  onAddIndividual?: (name: string, classIri: string) => Promise<void>;
  onDeleteIndividual?: (id: string) => Promise<void>;
  onRefreshIndividuals?: () => void;
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
}> = ({ item, projectId, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, classHierarchy = [], onToggleNode, expandedNodes = [], onAddClass, onAddClassInline, onDeleteClass, onRefreshClasses, onAddObjectProperty, onAddDataProperty, onDeleteProperty, metadata, objectPropertyHierarchy: propObjectPropertyHierarchy, dataPropertyHierarchy: propDataPropertyHierarchy, objectProperties: propObjectProperties, dataProperties: propDataProperties, viewMode = 'asserted', individuals: propIndividuals = [], onAddIndividual, onDeleteIndividual, onRefreshIndividuals, isViewOnly = false, onViewOnlyAction }) => {
  // Get current user for tracking mutations
  const { user } = useAuth();

  const [activeTab, setActiveTab] = useState<"annotations" | "usage" | "description">("annotations");
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [classDetails, setClassDetails] = useState<any>(null);

  // Manchester Syntax Editor State
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorType, setEditorType] = useState<AxiomType | null>(null);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorExistingValue, setEditorExistingValue] = useState<string | undefined>();
  const [editorExistingId, setEditorExistingId] = useState<string | undefined>();
  const [editorInitialTab, setEditorInitialTab] = useState<
    "hierarchy" | "objectRestriction" | "dataRestriction" | "classExpression" | undefined
  >();
  const [editorInitialRestrictionData, setEditorInitialRestrictionData] = useState<any>();

  // Properties for restriction creators - use props if available, otherwise local state
  const [properties, setProperties] = useState<any[]>(propObjectProperties || []);
  const [dataProperties, setDataProperties] = useState<any[]>(propDataProperties || []);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<TreeNode[]>(propObjectPropertyHierarchy || []);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<TreeNode[]>(propDataPropertyHierarchy || []);

  // Update local state when props change
  useEffect(() => {
    if (propObjectProperties) {
      console.log("[ClassEditor] Updating object properties from props:", propObjectProperties.length);
      setProperties(propObjectProperties);
    }
  }, [propObjectProperties]);

  useEffect(() => {
    if (propDataProperties) {
      console.log("[ClassEditor] Updating data properties from props:", propDataProperties.length);
      setDataProperties(propDataProperties);
    }
  }, [propDataProperties]);

  useEffect(() => {
    if (propObjectPropertyHierarchy) {
      console.log(
        "[ClassEditor] Updating object property hierarchy from props, nodes:",
        propObjectPropertyHierarchy.length,
      );
      setObjectPropertyHierarchy(propObjectPropertyHierarchy);
    }
  }, [propObjectPropertyHierarchy]);

  useEffect(() => {
    if (propDataPropertyHierarchy) {
      console.log(
        "[ClassEditor] Updating data property hierarchy from props, nodes:",
        propDataPropertyHierarchy.length,
      );
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

  // Instances State
  const [isInstancesOpen, setIsInstancesOpen] = useState(false);
  const [classInstances, setClassInstances] = useState<Individual[]>([]);
  const [loadingInstances, setLoadingInstances] = useState(false);
  const [editingInstanceId, setEditingInstanceId] = useState<string | undefined>();

  // General Class Axioms State (GCAs - SubClassOf with anonymous subclass)
  const [isGCAEditorOpen, setIsGCAEditorOpen] = useState(false);
  const [editingGCAId, setEditingGCAId] = useState<string | undefined>();

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
        console.warn("IRI renaming requires backend support - not yet implemented");
        alert("IRI renaming is not yet supported. Only label changes are saved.");
      }
    } catch (error) {
      console.error("Failed to update entity:", error);
      alert("Failed to update entity. See console for details.");
    }
  };

  // Load class details including annotations when component mounts.
  // Uses an "alive" flag so stale responses from a previously selected entity
  // are discarded (prevents showing the previous class's data).
  // Also resets local UI state immediately so users never see stale content.
  //
  // DEBOUNCE: Selection changes are debounced 200ms. When a user arrow-keys
  // through the tree or clicks quickly between classes, we cancel pending
  // requests before firing. This cuts backend load by 5-20× under real usage
  // and removes a huge amount of wasted GraphDB traffic at scale.
  useEffect(() => {
    if (!item.id || !projectId) return;

    let alive = true;

    // Reset visible state immediately so we never paint with the previous
    // entity's data while the new request is in flight.
    setClassDetails(null);
    setClassInstances([]);
    setLoadingDetails(true);
    setLoadingInstances(true);

    // Watchdog: if backend hangs, clear the spinner after 30s so the UI
    // does not appear permanently "Loading class details...".
    const watchdog = setTimeout(() => {
      if (alive) {
        setLoadingDetails(false);
        setLoadingInstances(false);
      }
    }, 30000);

    // Debounce: skip network calls if user moves off this node within 200ms.
    const debounceTimer = setTimeout(() => {
      if (!alive) return;
      runLoad();
    }, 200);

    const runLoad = () => {

    const currentId = item.id;
    const currentViewMode = viewMode;

    // ─── PERF TIMING ──────────────────────────────────────────────────────
    // Each stage logs its own start/end wall-clock ms. Compare these in the
    // browser console to locate the slow link (network vs. backend query vs.
    // React render). Look for `[perf][ClassEditor] …` lines.
    const tStart = performance.now();
    const shortIri = currentId.split(/[#/]/).pop() || currentId;
    console.log(`[perf][ClassEditor] ▶ select "${shortIri}" t0=0ms`);

    // Stage 1 (FAST, ~100ms): fetch ONLY annotations so the panel paints
    // immediately. The slower full-details call continues in stage 2 for
    // axiom lists. Annotations are what users glance at first.
    (async () => {
      const t1 = performance.now();
      try {
        const annResp = await apiClient.get<any>(
          `/api/ontology/classes/annotations/${projectId}?classIri=${encodeURIComponent(currentId)}`,
        );
        const t1Net = performance.now();
        console.log(
          `[perf][ClassEditor] ✓ stage-1 annotations network=${(t1Net - t1).toFixed(0)}ms total=${(t1Net - tStart).toFixed(0)}ms`,
        );
        if (!alive || currentId !== item.id) return;
        const annData = annResp?.data?.data || annResp?.data || annResp;
        if (annData && typeof annData === "object") {
          // Merge functionally so stage-2 full details will overwrite on arrival.
          setClassDetails((prev: any) => ({ ...(prev || {}), ...annData }));
          // Also surface annotations to the rendered item so the Annotations
          // panel paints without waiting for full details. Stage 2 will merge
          // axioms into the same item when it resolves.
          if (annData.annotations) {
            onUpdate({ ...item, annotations: annData.annotations } as TreeNode);
          }
          // Hide the top "Loading … details…" banner — annotations are visible
          // now. Edit handlers call loadClassDetails() which re-toggles the
          // spinner only when full data is actually needed.
          setLoadingDetails(false);
          console.log(
            `[perf][ClassEditor] ✓ stage-1 painted at ${(performance.now() - tStart).toFixed(0)}ms (annotations visible)`,
          );
        }
      } catch (e) {
        console.warn(`[perf][ClassEditor] ✗ stage-1 failed at ${(performance.now() - tStart).toFixed(0)}ms`, e);
        // Non-fatal: stage 2 will still populate annotations.
      }
    })();

    (async () => {
      const t2 = performance.now();
      try {
        // Fire both details and (if needed) inferred details IN PARALLEL.
        // Previously inferred was awaited serially after details, doubling
        // perceived latency in Inferred view mode.
        const detailsPromise = apiClient.get<any>(
          `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(currentId)}`,
        );
        const inferredPromise = currentViewMode === "inferred"
          ? apiClient.get<any>(
              `/api/ontology/${projectId}/reasoner/inferred-class-details?classIri=${encodeURIComponent(currentId)}`,
            ).catch((err) => {
              console.warn("[ClassEditor] Failed to load inferred details:", err);
              return null;
            })
          : Promise.resolve(null);

        const [detailsResponse, inferredResponse] = await Promise.all([detailsPromise, inferredPromise]);
        const t2Net = performance.now();
        console.log(
          `[perf][ClassEditor] ✓ stage-2 full details network=${(t2Net - t2).toFixed(0)}ms total=${(t2Net - tStart).toFixed(0)}ms (inferred=${currentViewMode === "inferred"})`,
        );
        if (!alive || currentId !== item.id) return;
        let details = detailsResponse?.data?.data || detailsResponse?.data || detailsResponse;

        if (currentViewMode === "inferred" && inferredResponse) {
          const inferredData = (inferredResponse as any)?.data?.data || (inferredResponse as any)?.data || {};
          details = {
            ...details,
            inferredSubClassOfAxioms: inferredData.inferredSubClassOfAxioms || [],
            inferredEquivalentClassesAxioms: inferredData.inferredEquivalentClassesAxioms || [],
            isUnsatisfiable: inferredData.isUnsatisfiable || false,
          };
        }

        if (!alive || currentId !== item.id) return;
        setClassDetails(details);

        const updatedItem: TreeNode = {
          ...item,
          annotations: details.annotations || item.annotations,
          subClassOfAxioms: details.subClassOfAxioms || item.subClassOfAxioms,
          equivalentClassesAxioms: details.equivalentClassesAxioms || item.equivalentClassesAxioms,
          disjointClassesAxioms: details.disjointClassesAxioms || item.disjointClassesAxioms,
          disjointUnionAxioms: details.disjointUnionAxioms || item.disjointUnionAxioms,
          hasKeyAxioms: details.hasKeyAxioms || item.hasKeyAxioms,
          inferredSubClassOfAxioms: details.inferredSubClassOfAxioms,
          inferredEquivalentClassesAxioms: details.inferredEquivalentClassesAxioms,
          isUnsatisfiable: details.isUnsatisfiable,
        };
        onUpdate(updatedItem);
        console.log(
          `[perf][ClassEditor] ✓ stage-2 painted at ${(performance.now() - tStart).toFixed(0)}ms (full axioms visible)`,
        );
      } catch (error) {
        if (alive && currentId === item.id) {
          console.error(
            `[perf][ClassEditor] ✗ stage-2 failed at ${(performance.now() - tStart).toFixed(0)}ms`,
            error,
          );
        }
      } finally {
        if (alive && currentId === item.id) setLoadingDetails(false);
      }
    })();

    (async () => {
      const t3 = performance.now();
      try {
        const response = await apiClient.get<any>(
          `/api/ontology/classes/instances/${projectId}?classIri=${encodeURIComponent(currentId)}`,
        );
        console.log(
          `[perf][ClassEditor] ✓ instances network=${(performance.now() - t3).toFixed(0)}ms total=${(performance.now() - tStart).toFixed(0)}ms`,
        );
        if (!alive || currentId !== item.id) return;
        const instances = response?.data?.data || response?.data || response || [];
        setClassInstances(Array.isArray(instances) ? instances : []);
      } catch (error) {
        if (alive && currentId === item.id) {
          console.error("Failed to load class instances:", error);
          setClassInstances([]);
        }
      } finally {
        if (alive && currentId === item.id) setLoadingInstances(false);
      }
    })();

    // Properties (object/data) are not entity-scoped; load lazily on first mount.
    loadProperties();
    }; // end runLoad

    return () => {
      alive = false;
      clearTimeout(watchdog);
      clearTimeout(debounceTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id, projectId, viewMode]);

  // Load instances only when the Instances panel is opened (lazy loading for better performance)
  // useEffect(() => {
  //   if (isInstancesOpen && item.id && projectId && classInstances.length === 0) {
  //     loadInstances();
  //   }
  // }, [isInstancesOpen, item.id, projectId]);

  const loadProperties = async () => {
    try {
      // Load all properties (both object and data)
      const allPropsResponse = await apiClient.get(`/api/ontology/properties/${projectId}`);
      // Backend returns { success: true, data: [...] }
      // apiClient might wrap it in { data: {...} }
      const allProps = allPropsResponse?.data?.data || allPropsResponse?.data || [];

      // Separate object and data properties
      const objProps = allProps.filter((p: any) => p.type === "ObjectProperty");
      const dataProps = allProps.filter((p: any) => p.type === "DatatypeProperty");

      setProperties(objProps);
      setDataProperties(dataProps);

      // Build Object Property Hierarchy (same logic as Dashboard)
      const opMap = new Map<string, any>();
      objProps.forEach((p: any) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topObjectProperty = {
        id: "http://www.w3.org/2002/07/owl#topObjectProperty",
        label: "owl:topObjectProperty",
        children: [],
        hasChildren: false,
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
        id: "http://www.w3.org/2002/07/owl#topDataProperty",
        label: "owl:topDataProperty",
        children: [],
        hasChildren: false,
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
      console.error("Failed to load properties:", error);
    }
  };

  const loadClassDetails = async () => {
    setLoadingDetails(true);
    try {
      const response = await apiClient.get<any>(
        `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(item.id)}`,
      );
      // Backend returns {success: true, data: {...}}
      let details = response?.data?.data || response?.data || response;
      console.log("[ClassEditor] Class details loaded:", details);

      // If in inferred mode, fetch inferred details from reasoner
      if (viewMode === "inferred") {
        try {
          const inferredResponse = await apiClient.get<any>(
            `/api/ontology/${projectId}/reasoner/inferred-class-details?classIri=${encodeURIComponent(item.id)}`,
          );
          const inferredData = inferredResponse?.data?.data || inferredResponse?.data || {};
          console.log("[ClassEditor] Inferred class details loaded:", inferredData);

          details = {
            ...details,
            inferredSubClassOfAxioms: inferredData.inferredSubClassOfAxioms || [],
            inferredEquivalentClassesAxioms: inferredData.inferredEquivalentClassesAxioms || [],
            isUnsatisfiable: inferredData.isUnsatisfiable || false,
          };
        } catch (err) {
          console.warn("[ClassEditor] Failed to load inferred details:", err);
        }
      }

      setClassDetails(details);

      // Update the item with all loaded details (annotations, axioms, etc.)
      // Debug logging for axioms
      console.log("[ClassEditor] Axioms from backend:", {
        subClassOf: details.subClassOfAxioms?.length || 0,
        equivalentTo: details.equivalentClassesAxioms?.length || 0,
        disjointWith: details.disjointClassesAxioms?.length || 0,
        disjointUnion: details.disjointUnionAxioms?.length || 0,
        hasKey: details.hasKeyAxioms?.length || 0,
        inferredSubClassOf: details.inferredSubClassOfAxioms?.length || 0,
        inferredEquivalentTo: details.inferredEquivalentClassesAxioms?.length || 0,
      });

      const updatedItem: TreeNode = {
        ...item,
        annotations: details.annotations || item.annotations,
        subClassOfAxioms: details.subClassOfAxioms || item.subClassOfAxioms,
        equivalentClassesAxioms: details.equivalentClassesAxioms || item.equivalentClassesAxioms,
        disjointClassesAxioms: details.disjointClassesAxioms || item.disjointClassesAxioms,
        disjointUnionAxioms: details.disjointUnionAxioms || item.disjointUnionAxioms,
        hasKeyAxioms: details.hasKeyAxioms || item.hasKeyAxioms,
        inferredSubClassOfAxioms: details.inferredSubClassOfAxioms,
        inferredEquivalentClassesAxioms: details.inferredEquivalentClassesAxioms,
        isUnsatisfiable: details.isUnsatisfiable,
      };
      console.log("[ClassEditor] Updated item:", updatedItem);
      onUpdate(updatedItem);
    } catch (error) {
      console.error("Failed to load class details:", error);
    } finally {
      setLoadingDetails(false);
    }
  };

  const loadInstances = async () => {
    setLoadingInstances(true);
    try {
      const response = await apiClient.get<any>(
        `/api/ontology/classes/instances/${projectId}?classIri=${encodeURIComponent(item.id)}`,
      );
      // Backend returns {success: true, data: [...]} or just the array
      const instances = response?.data?.data || response?.data || response || [];
      console.log("[ClassEditor] Class instances loaded:", instances.length);
      setClassInstances(Array.isArray(instances) ? instances : []);
    } catch (error) {
      console.error("Failed to load class instances:", error);
      setClassInstances([]);
    } finally {
      setLoadingInstances(false);
    }
  };

  // Navigation handler for clicking properties in axiom descriptions
  const handleNavigate = (iri: string, type: string) => {
    console.log("[ClassEditor] Navigate to:", { iri, type });

    if (type === "class") {
      // Find the class in the hierarchy
      const findInHierarchy = (nodes: TreeNode[]): TreeNode | null => {
        for (const node of nodes) {
          if (node.id === iri) return node;
          if (node.children) {
            const found = findInHierarchy(node.children);
            if (found) return found;
          }
        }
        return null;
      };

      const classNode = findInHierarchy(classHierarchy);
      if (classNode) {
        console.log("[ClassEditor] Navigating to class:", classNode);
        // Update the current item to trigger parent re-render with new selection
        onUpdate(classNode);
      } else {
        console.warn("[ClassEditor] Class not found in hierarchy:", iri);
      }
    } else if (type === "property" || type === "objectProperty" || type === "dataProperty") {
      // Find the property in the hierarchies
      const findInPropertyHierarchy = (nodes: TreeNode[]): TreeNode | null => {
        for (const node of nodes) {
          if (node.id === iri) return node;
          if (node.children) {
            const found = findInPropertyHierarchy(node.children);
            if (found) return found;
          }
        }
        return null;
      };

      // Check in object property hierarchy first
      let propertyNode = objectPropertyHierarchy.length > 0 ? findInPropertyHierarchy(objectPropertyHierarchy) : null;

      // If not found, check in data property hierarchy
      if (!propertyNode && dataPropertyHierarchy.length > 0) {
        propertyNode = findInPropertyHierarchy(dataPropertyHierarchy);
      }

      // If still not found, create a basic property node from the lists
      if (!propertyNode) {
        const property = properties.find((p) => p.id === iri) || dataProperties.find((p) => p.id === iri);
        if (property) {
          propertyNode = property;
        }
      }

      if (propertyNode) {
        console.log("[ClassEditor] Navigating to property:", propertyNode);
        // Navigate to the property by updating the selection
        onUpdate(propertyNode);
      } else {
        console.warn("[ClassEditor] Property not found in hierarchies:", iri);
      }
    }
  };

  const openEditor = (
    type: AxiomType,
    title: string,
    existingValue?: string,
    existingId?: string,
    initialTab?: "hierarchy" | "objectRestriction" | "dataRestriction" | "classExpression",
    restrictionData?: any,
  ) => {
    console.log("[ClassEditor] openEditor called:", { type, title, classHierarchyLength: classHierarchy.length });
    setEditorType(type);
    // Update title to indicate edit mode
    if (existingValue && existingId) {
      setEditorTitle(`Edit ${title}`);
      // For hierarchy tab (simple class axioms), pass the IRI as initialValue so it can be pre-selected
      // For other tabs (restrictions, expressions), pass the definition/label
      setEditorExistingValue(initialTab === "hierarchy" ? existingId : existingValue);
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
    console.log("[ClassEditor] handleEditorConfirm called:", {
      expression,
      restrictionData,
      editorType,
      editorExistingId,
      classIri: item.id,
    });

    if (!editorType) {
      console.error("[ClassEditor] handleEditorConfirm - editorType is null!");
      alert("Error: Editor type not set. Please try again.");
      return;
    }

    try {
      // If we have an existing axiom ID, this is an edit operation
      if (editorExistingId) {
        console.log("[ClassEditor] Edit operation - deleting old axiom:", {
          editorExistingId,
          editorType,
          newExpression: expression,
          classIri: item.id,
          editorInitialRestrictionData,
          restrictionData,
        });

        // For edit, we need to delete the old one first, then add the new one
        // Check if the old axiom was a restriction that needs special handling
        if (editorInitialRestrictionData) {
          // Delete the old restriction
          const axiomType = editorType === "EquivalentTo" ? "EquivalentTo" : "SubClassOf";

          console.log("[ClassEditor] Deleting old restriction:", editorInitialRestrictionData);
          if (editorInitialRestrictionData.isDataProperty) {
            await ontologyMutationService.deleteDataRestriction(
              projectId,
              item.id,
              axiomType,
              editorInitialRestrictionData.propertyIri!,
              editorInitialRestrictionData.restrictionType!,
              editorInitialRestrictionData.fillerIri!,
            );
          } else {
            await ontologyMutationService.deleteObjectRestriction(
              projectId,
              item.id,
              axiomType,
              editorInitialRestrictionData.propertyIri!,
              editorInitialRestrictionData.restrictionType!,
              editorInitialRestrictionData.fillerIri!,
            );
          }
          // Wait for GraphDB to process the deletion - increased delay for restrictions
          await new Promise((resolve) => setTimeout(resolve, 1000));
          console.log("[ClassEditor] Waited 1000ms after restriction deletion");
        } else {
          // Delete old simple class axiom (not a restriction)
          // The editorExistingId should be the IRI of the target class
          console.log("[ClassEditor] Editing simple class axiom - using UPDATE instead of DELETE+ADD:", {
            editorExistingId,
            editorType,
            classIri: item.id,
            oldTarget: editorExistingId,
            newTarget: expression,
          });

          // Use UPDATE operations to replace in a single transaction
          // This prevents creating duplicates
          const isNewSimpleIRI =
            expression.startsWith("http://") || expression.startsWith("https://") || expression.startsWith("urn:");

          if (isNewSimpleIRI) {
            switch (editorType) {
              case "EquivalentTo":
                console.log("[ClassEditor] Calling updateEquivalentClass with:", {
                  projectId,
                  classIri: item.id,
                  oldTarget: editorExistingId,
                  newTarget: expression,
                });
                await ontologyMutationService.updateEquivalentClass(
                  projectId,
                  item.id,
                  editorExistingId,
                  expression,
                  user?.email,
                  user?.displayName || user?.email,
                );
                console.log("[ClassEditor] updateEquivalentClass completed");
                break;
              case "SubClassOf":
                console.log("[ClassEditor] Calling updateSubClassOf with:", {
                  projectId,
                  classIri: item.id,
                  oldTarget: editorExistingId,
                  newTarget: expression,
                });
                await ontologyMutationService.updateSubClassOf(
                  projectId,
                  item.id,
                  editorExistingId,
                  expression,
                  user?.email,
                  user?.displayName || user?.email,
                );
                console.log("[ClassEditor] updateSubClassOf completed");
                break;
              case "DisjointWith":
                console.log("[ClassEditor] Calling updateDisjointWith with:", {
                  projectId,
                  classIri: item.id,
                  oldTarget: editorExistingId,
                  newTarget: expression,
                });
                await ontologyMutationService.updateDisjointWith(
                  projectId,
                  item.id,
                  editorExistingId,
                  expression,
                  user?.email,
                  user?.displayName || user?.email,
                );
                console.log("[ClassEditor] updateDisjointWith completed");
                break;
            }
            // Wait for GraphDB to process the update
            await new Promise((resolve) => setTimeout(resolve, 500));
            console.log("[ClassEditor] Waited 500ms after update operation");

            // Reload details to reflect the changes
            console.log("[ClassEditor] Reloading class details after edit");
            await loadClassDetails();
            console.log("[ClassEditor] Class details reloaded");

            // Close the dialog
            // Close the dialog
            setIsEditorOpen(false);
            setEditorExistingId(undefined);
            return;
          } else {
            // For complex expressions, still use delete+add
            console.log("[ClassEditor] Complex expression detected, using delete+add approach");
            switch (editorType) {
              case "EquivalentTo":
                await ontologyMutationService.deleteEquivalentClass(
                  projectId,
                  item.id,
                  editorExistingId,
                  user?.email,
                  user?.displayName || user?.email,
                );
                break;
              case "SubClassOf":
                await ontologyMutationService.deleteSubClassOf(
                  projectId,
                  item.id,
                  editorExistingId,
                  user?.email,
                  user?.displayName || user?.email,
                );
                break;
              case "DisjointWith":
                await ontologyMutationService.deleteDisjointWith(
                  projectId,
                  item.id,
                  editorExistingId,
                  user?.email,
                  user?.displayName || user?.email,
                );
                break;
            }
            // Wait for GraphDB to process the deletion
            await new Promise((resolve) => setTimeout(resolve, 500));

            // Add the new complex expression axiom
            await ontologyMutationService.addAxiom(projectId, item.id, editorType, expression);

            // Reload details to reflect the changes
            console.log("[ClassEditor] Reloading class details after complex expression edit");
            await loadClassDetails();
            console.log("[ClassEditor] Class details reloaded");

            // Close the dialog
            setIsEditorOpen(false);
            setEditorExistingId(undefined);
            return;
          }
        }

        // For restriction edits, continue with the delete+add flow
        console.log("[ClassEditor] Adding new restriction axiom after deletion:", {
          expression,
          restrictionData,
          editorType,
        });

        // SAFETY CHECK: If this is a restriction edit, verify that we're not adding a duplicate
        // Check if an axiom with the same property and filler already exists
        if (restrictionData) {
          // Get the correct axiom array based on axiom type
          let existingAxioms: any[] = [];
          if (editorType === "SubClassOf") {
            existingAxioms = classDetails?.subClassOfAxioms || item.subClassOfAxioms || [];
          } else if (editorType === "EquivalentTo") {
            existingAxioms = classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms || [];
          } else if (editorType === "DisjointWith") {
            existingAxioms = classDetails?.disjointWithAxioms || item.disjointWithAxioms || [];
          }

          const isDuplicate = existingAxioms.some((axiom: any) => {
            // Check if axiom has the same property and filler
            return (
              axiom.propertyIri === restrictionData.propertyIri &&
              axiom.fillerIri === restrictionData.fillerIri &&
              axiom.restrictionType === restrictionData.restrictionType
            );
          });

          if (isDuplicate) {
            console.warn(
              "[ClassEditor] ⚠️  DUPLICATE DETECTED - Not adding axiom that already exists with same restriction:",
              {
                editorType,
                propertyIri: restrictionData.propertyIri,
                fillerIri: restrictionData.fillerIri,
                restrictionType: restrictionData.restrictionType,
                existingAxiomsCount: existingAxioms.length,
              },
            );
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
        console.log("[ClassEditor] Add operation:", { expression, restrictionData });
        await handleAddAxiom(editorType, expression, restrictionData);
      }
    } catch (error) {
      console.error("[ClassEditor] handleEditorConfirm failed:", error);
      alert(`Failed to save axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsEditorOpen(false);
      setEditorType(null);
      setEditorExistingValue(undefined);
      setEditorExistingId(undefined);
      setEditorInitialTab(undefined);
      setEditorInitialRestrictionData(undefined);
    }
  };

  const handleAddAxiom = async (type: AxiomType, definition: string, restrictionData?: RestrictionData) => {
    console.log("[ClassEditor] handleAddAxiom called:", {
      type,
      definition,
      restrictionData,
      classHierarchyLength: classHierarchy.length,
    });
    try {
      // If we have structured restriction data, use the specific restriction methods
      // NOTE: DisjointWith does NOT support restrictions - it's only for class-to-class disjointness
      if (restrictionData && type !== "DisjointWith") {
        // Set the axiom type from the editor type
        restrictionData.axiomType = type;

        if (restrictionData.type === "objectRestriction") {
          await ontologyMutationService.addObjectRestriction(
            projectId,
            item.id,
            restrictionData.axiomType,
            restrictionData.propertyIri,
            restrictionData.restrictionType,
            restrictionData.fillerIri,
            restrictionData.cardinality,
          );
        } else if (restrictionData.type === "dataRestriction") {
          // Only allow valid restrictionType values for data restrictions
          const validDataRestrictionTypes = ["some", "only", "min", "max", "exactly"];
          if (validDataRestrictionTypes.includes(restrictionData.restrictionType)) {
            await ontologyMutationService.addDataRestriction(
              projectId,
              item.id,
              restrictionData.axiomType,
              restrictionData.propertyIri,
              restrictionData.restrictionType as "some" | "only" | "min" | "max" | "exactly",
              restrictionData.fillerIri,
              restrictionData.cardinality,
            );
          } else {
            console.warn("Invalid restrictionType for data restriction:", restrictionData.restrictionType);
          }
        }
        // Reload details to get the updated axioms
        await loadClassDetails();
        return;
      }

      if (!item.id) {
        alert("Cannot add axiom: class IRI is not yet available. Please wait for the class to finish loading.");
        return;
      }

      // Check if definition is a simple class IRI (starts with http:// or urn:)
      // For simple IRIs, use the specific mutation methods that work with the backend
      const isSimpleIRI =
        definition.startsWith("http://") || definition.startsWith("https://") || definition.startsWith("urn:");

      if (isSimpleIRI) {
        // Use specific mutation methods for simple class relationships
        try {
          switch (type) {
            case "EquivalentTo":
              console.log("[ClassEditor] Calling addEquivalentClass:", {
                projectId,
                classIri: item.id,
                equivalentClassIri: definition,
              });
              await ontologyMutationService.addEquivalentClass(
                projectId,
                item.id,
                definition,
                user?.email,
                user?.displayName || user?.email,
              );
              console.log("[ClassEditor] addEquivalentClass completed successfully");
              break;
            case "SubClassOf":
              console.log("[ClassEditor] Calling addSubClassOf:", {
                projectId,
                classIri: item.id,
                parentIri: definition,
              });
              await ontologyMutationService.addSubClassOf(
                projectId,
                item.id,
                definition,
                user?.email,
                user?.displayName || user?.email,
              );
              console.log("[ClassEditor] addSubClassOf completed successfully");
              break;
            case "DisjointWith":
              console.log("[ClassEditor] Calling addDisjointWith:", {
                projectId,
                classIri: item.id,
                disjointIri: definition,
              });
              await ontologyMutationService.addDisjointWith(
                projectId,
                item.id,
                definition,
                user?.email,
                user?.displayName || user?.email,
              );
              console.log("[ClassEditor] addDisjointWith completed successfully");
              break;
          }
        } catch (mutationError) {
          console.error("[ClassEditor] Mutation failed:", mutationError);
          throw mutationError;
        }
      } else {
        if (!item.id) {
          alert("Cannot add axiom: class IRI is not yet available. Please wait for the class to finish loading.");
          return;
        }
        if (!definition.trim()) {
          alert("Cannot add axiom: expression is empty.");
          return;
        }
        // For complex Manchester Syntax expressions, use addAxiom (requires backend Manchester parser)
        await ontologyMutationService.addAxiom(projectId, item.id, type, definition);
      }
      // Wait for GraphDB to index the new axiom (increased delay for SPARQL consistency)
      console.log("[ClassEditor] Waiting 800ms for GraphDB to index...");
      await new Promise((resolve) => setTimeout(resolve, 800));
      // Reload details to get the updated axioms
      console.log("[ClassEditor] Reloading class details after axiom addition");
      await loadClassDetails();
      console.log("[ClassEditor] Class details reloaded after axiom addition");
      // Also notify parent to update tree if needed (though axioms usually don't change tree structure unless it's subclassof)
      // onUpdate(item); // We might not need this if we reload details
    } catch (error) {
      console.error("[ClassEditor] Failed to add axiom:", error);
      alert(`Failed to add axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleDeleteAxiom = async (type: AxiomType, id: string) => {
    console.log("[ClassEditor] handleDeleteAxiom called:", { type, id, classIri: item.id });
    try {
      // Find the axiom object to check if it's a restriction
      // Use classDetails if available (most recent data), otherwise fall back to item
      let axiomArrays: { EquivalentTo?: Axiom[]; SubClassOf?: Axiom[]; DisjointWith?: Axiom[] } = {
        EquivalentTo: classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms,
        SubClassOf: classDetails?.subClassOfAxioms || item.subClassOfAxioms,
        DisjointWith: classDetails?.disjointClassesAxioms || item.disjointClassesAxioms,
      };
      const axiom = axiomArrays[type]?.find((a) => a.id === id);
      console.log("[ClassEditor] Found axiom:", axiom);

      // Check if this is a restriction (isRestriction can be boolean or string "true")
      const isRestriction = axiom?.isRestriction === true || axiom?.isRestriction === "true";

      if (isRestriction && axiom?.propertyIri && axiom?.restrictionType && axiom?.fillerIri) {
        // Delete restriction - map type to axiomType parameter
        const axiomType = type === "EquivalentTo" ? "EquivalentTo" : "SubClassOf";

        // Check if it's a data property restriction
        const isDataProperty =
          axiom.propertyIri === "http://www.w3.org/2002/07/owl#topDataProperty" ||
          dataProperties.some((p) => p.id === axiom.propertyIri);

        console.log("[ClassEditor] Deleting restriction:", {
          classIri: item.id,
          axiomType,
          propertyIri: axiom.propertyIri,
          restrictionType: axiom.restrictionType,
          fillerIri: axiom.fillerIri,
          isDataProperty,
        });

        if (isDataProperty) {
          await ontologyMutationService.deleteDataRestriction(
            projectId,
            item.id,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as "some" | "only" | "min" | "max" | "exactly",
            axiom.fillerIri,
          );
        } else {
          await ontologyMutationService.deleteObjectRestriction(
            projectId,
            item.id,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as "some" | "only" | "min" | "max" | "exactly" | "value",
            axiom.fillerIri,
          );
        }
        // Wait for GraphDB to process the deletion
        await new Promise((resolve) => setTimeout(resolve, 300));
        await loadClassDetails();
      } else {
        // The id is usually the IRI of the related class
        // Always attempt to delete - the backend will handle validation
        console.log("[ClassEditor] Deleting simple class axiom:", { type, classIri: item.id, targetIri: id });

        switch (type) {
          case "EquivalentTo":
            console.log("[ClassEditor] Calling deleteEquivalentClass");
            await ontologyMutationService.deleteEquivalentClass(
              projectId,
              item.id,
              id,
              user?.email,
              user?.displayName || user?.email,
            );
            break;
          case "SubClassOf":
            console.log("[ClassEditor] Calling deleteSubClassOf with params:", {
              projectId,
              classIri: item.id,
              superClassIri: id,
            });
            await ontologyMutationService.deleteSubClassOf(
              projectId,
              item.id,
              id,
              user?.email,
              user?.displayName || user?.email,
            );
            console.log("[ClassEditor] deleteSubClassOf completed");
            break;
          case "DisjointWith":
            console.log("[ClassEditor] Calling deleteDisjointWith");
            await ontologyMutationService.deleteDisjointWith(
              projectId,
              item.id,
              id,
              user?.email,
              user?.displayName || user?.email,
            );
            break;
        }
        // Small delay to allow GraphDB to process the mutation
        await new Promise((resolve) => setTimeout(resolve, 300));
        // Reload to reflect changes
        console.log("[ClassEditor] Reloading class details after delete");
        await loadClassDetails();
        console.log("[ClassEditor] loadClassDetails completed");
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to delete axiom:", error);
      console.error("[ClassEditor] Delete axiom details:", { type, id, classIri: item.id });
      alert(`Failed to delete axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleEditAxiom = async (type: AxiomType, oldId: string, newDefinition: string) => {
    try {
      console.log("[ClassEditor] handleEditAxiom called:", { type, oldId, newDefinition });

      // Check if both old and new are simple IRIs
      const isOldSimpleIRI = oldId.startsWith("http://") || oldId.startsWith("https://") || oldId.startsWith("urn:");
      const isNewSimpleIRI =
        newDefinition.startsWith("http://") || newDefinition.startsWith("https://") || newDefinition.startsWith("urn:");

      // If both are simple IRIs, use atomic UPDATE operations
      if (isOldSimpleIRI && isNewSimpleIRI) {
        console.log("[ClassEditor] Using atomic UPDATE operation");
        switch (type) {
          case "EquivalentTo":
            await ontologyMutationService.updateEquivalentClass(
              projectId,
              item.id,
              oldId,
              newDefinition,
              user?.email,
              user?.displayName || user?.email,
            );
            break;
          case "SubClassOf":
            await ontologyMutationService.updateSubClassOf(
              projectId,
              item.id,
              oldId,
              newDefinition,
              user?.email,
              user?.displayName || user?.email,
            );
            break;
          case "DisjointWith":
            await ontologyMutationService.updateDisjointWith(
              projectId,
              item.id,
              oldId,
              newDefinition,
              user?.email,
              user?.displayName || user?.email,
            );
            break;
        }
      } else {
        // For complex expressions or mixed cases, use delete + add
        console.log("[ClassEditor] Using DELETE + ADD approach for complex/mixed expressions");

        // Delete the old axiom
        if (isOldSimpleIRI) {
          switch (type) {
            case "EquivalentTo":
              await ontologyMutationService.deleteEquivalentClass(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
            case "SubClassOf":
              await ontologyMutationService.deleteSubClassOf(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
            case "DisjointWith":
              await ontologyMutationService.deleteDisjointWith(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
          }
        }

        // Small delay to allow deletion to process
        await new Promise((resolve) => setTimeout(resolve, 300));

        // Add the new axiom
        if (isNewSimpleIRI) {
          switch (type) {
            case "EquivalentTo":
              await ontologyMutationService.addEquivalentClass(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
            case "SubClassOf":
              await ontologyMutationService.addSubClassOf(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
            case "DisjointWith":
              await ontologyMutationService.addDisjointWith(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.displayName || user?.email,
              );
              break;
          }
        } else {
          // For complex Manchester Syntax expressions
          await ontologyMutationService.addAxiom(projectId, item.id, type, newDefinition);
        }
      }

      // Small delay before reloading
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after edit");
      await loadClassDetails();
      console.log("[ClassEditor] Edit completed successfully");
    } catch (error) {
      console.error("[ClassEditor] Failed to edit axiom:", error);
      alert(`Failed to edit axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  // Handler for Disjoint With - adds owl:disjointWith for each selected class
  const handleDisjointWithConfirm = async (nodes: TreeNode[]) => {
    console.log("[ClassEditor] handleDisjointWithConfirm called:", {
      nodes: nodes.map((n) => ({ id: n.id, label: n.label })),
      isEditing: !!editingDisjointWithId,
    });
    try {
      const classIris = nodes.map((n) => n.id);

      if (classIris.length < 1) {
        console.warn("[ClassEditor] Please select at least 1 class");
        alert("Please select at least 1 class");
        return;
      }

      // If editing, delete the old one first
      if (editingDisjointWithId) {
        console.log("[ClassEditor] Editing disjoint with - deleting old:", editingDisjointWithId);
        await ontologyMutationService.deleteDisjointWith(projectId, item.id, editingDisjointWithId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      // Add disjoint with for each selected class
      for (const targetIri of classIris) {
        console.log("[ClassEditor] Adding disjoint with:", { classIri: item.id, targetIri });
        await ontologyMutationService.addDisjointWith(projectId, item.id, targetIri);
      }

      // Small delay to allow GraphDB to process the mutations
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding disjoint with");
      await loadClassDetails();
      console.log("[ClassEditor] loadClassDetails completed after disjoint with");
    } catch (error) {
      console.error("[ClassEditor] Failed to add disjoint with:", error);
      alert(`Failed to add disjoint with: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsDisjointWithOpen(false);
      setEditingDisjointWithId(undefined);
      setEditingDisjointWithTarget(undefined);
    }
  };

  const handleEditDisjointWith = (axiomId: string) => {
    console.log("[ClassEditor] handleEditDisjointWith called:", { classIri: item.id, axiomId });
    // Find the axiom to edit
    const axiom =
      classDetails?.disjointClassesAxioms?.find((a: Axiom) => a.id === axiomId) ||
      item.disjointClassesAxioms?.find((a: Axiom) => a.id === axiomId);

    if (!axiom) {
      console.error("[ClassEditor] Axiom not found for editing:", axiomId);
      return;
    }

    // Check if it's a simple class axiom or complex expression
    const isSimpleIri = axiomId.startsWith("http://") || axiomId.startsWith("https://") || axiomId.startsWith("urn:");
    const isRestriction = axiom.isRestriction === true || axiom.isRestriction === "true";

    if (isRestriction && axiom.propertyIri) {
      // Open the editor with restriction data
      const isDataProperty =
        axiom.propertyIri === "http://www.w3.org/2002/07/owl#topDataProperty" ||
        dataProperties.some((p) => p.id === axiom.propertyIri);

      const restrictionData = {
        propertyIri: axiom.propertyIri,
        restrictionType: axiom.restrictionType,
        fillerIri: axiom.fillerIri,
        isDataProperty,
      };

      openEditor(
        "DisjointWith",
        "Disjoint Class Expression",
        axiom.definition,
        axiomId,
        isDataProperty ? "dataRestriction" : "objectRestriction",
        restrictionData,
      );
    } else if (isSimpleIri) {
      // Simple class axiom - open hierarchy tab
      openEditor("DisjointWith", "Disjoint Class Expression", axiom.definition, axiomId, "hierarchy");
    } else {
      // Complex expression - open class expression editor
      openEditor("DisjointWith", "Disjoint Class Expression", axiom.definition, axiomId, "classExpression");
    }
  };

  const handleDisjointUnionConfirm = async (nodes: TreeNode[]) => {
    console.log("[ClassEditor] handleDisjointUnionConfirm called:", {
      nodes: nodes.map((n) => ({ id: n.id, label: n.label })),
      isEditing: !!editingDisjointUnionId,
    });
    try {
      // Get the IRIs of the selected classes
      const memberIris = nodes.map((n) => n.id);

      if (memberIris.length < 2) {
        console.warn("[ClassEditor] Disjoint Union requires at least 2 classes");
        alert("Please select at least 2 classes for the disjoint union.");
        return;
      }

      // If editing, delete the old one first
      if (editingDisjointUnionId) {
        console.log("[ClassEditor] Editing disjoint union - deleting old:", editingDisjointUnionId);
        await ontologyMutationService.deleteDisjointUnion(projectId, item.id, editingDisjointUnionId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      console.log("[ClassEditor] Adding disjoint union:", { classIri: item.id, memberIris });
      // Use the new addDisjointUnion method
      await ontologyMutationService.addDisjointUnion(projectId, item.id, memberIris);
      console.log("[ClassEditor] addDisjointUnion completed");

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding disjoint union");
      await loadClassDetails();
      console.log("[ClassEditor] loadClassDetails completed after disjoint union");
    } catch (error) {
      console.error("[ClassEditor] Failed to add disjoint union:", error);
      alert(`Failed to add disjoint union: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsDisjointUnionOpen(false);
      setEditingDisjointUnionId(undefined);
      setEditingDisjointUnionMembers([]);
    }
  };

  const handleEditDisjointUnion = async (listNodeId: string) => {
    console.log("[ClassEditor] handleEditDisjointUnion called:", { classIri: item.id, listNodeId });
    // Find the disjoint union axiom to get current members
    const disjointUnionAxiom = (classDetails?.disjointUnionAxioms || item.disjointUnionAxioms)?.find(
      (ax: Axiom) => ax.id === listNodeId,
    );
    if (!disjointUnionAxiom) {
      console.error("[ClassEditor] Disjoint union axiom not found:", listNodeId);
      return;
    }

    // Extract member IRIs from the axiom definition
    // The definition format is like: "Class1, Class2, Class3" or contains IRIs
    const members = disjointUnionAxiom.members || [];
    console.log("[ClassEditor] Found disjoint union members:", members);

    // Set edit state and open dialog
    setEditingDisjointUnionId(listNodeId);
    setEditingDisjointUnionMembers(members);
    setIsDisjointUnionOpen(true);
  };

  const handleDeleteDisjointUnion = async (listNodeId: string) => {
    console.log("[ClassEditor] handleDeleteDisjointUnion called:", { classIri: item.id, listNodeId });
    try {
      await ontologyMutationService.deleteDisjointUnion(projectId, item.id, listNodeId);
      console.log("[ClassEditor] deleteDisjointUnion completed");

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after deleting disjoint union");
      await loadClassDetails();
      console.log("[ClassEditor] loadClassDetails completed after delete disjoint union");
    } catch (error) {
      console.error("[ClassEditor] Failed to delete disjoint union:", error);
      alert(`Failed to delete disjoint union: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleEditHasKey = async (listNodeId: string) => {
    console.log("[ClassEditor] handleEditHasKey called:", { classIri: item.id, listNodeId });
    // Find the has key axiom to get current properties
    const hasKeyAxiom = (classDetails?.hasKeyAxioms || item.hasKeyAxioms)?.find((ax: Axiom) => ax.id === listNodeId);
    if (!hasKeyAxiom) {
      console.error("[ClassEditor] Has key axiom not found:", listNodeId);
      return;
    }

    // Extract property IRIs from the axiom
    const props = hasKeyAxiom.properties || [];
    console.log("[ClassEditor] Found has key properties:", props);

    // Set edit state and open dialog
    setEditingHasKeyId(listNodeId);
    setEditingHasKeyProperties(props);
    setIsHasKeyOpen(true);
  };

  const handleDeleteHasKey = async (listNodeId: string) => {
    console.log("[ClassEditor] handleDeleteHasKey called:", { classIri: item.id, listNodeId });
    try {
      await ontologyMutationService.deleteHasKey(projectId, item.id, listNodeId);
      console.log("[ClassEditor] deleteHasKey completed");

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after deleting has key");
      await loadClassDetails();
      console.log("[ClassEditor] loadClassDetails completed after delete has key");
    } catch (error) {
      console.error("[ClassEditor] Failed to delete has key:", error);
      alert(`Failed to delete has key: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleAddHasKey = async (propertyIris: string[]) => {
    console.log("[ClassEditor] handleAddHasKey called:", {
      classIri: item.id,
      propertyIris,
      isEditing: !!editingHasKeyId,
    });
    try {
      if (propertyIris.length < 1) {
        console.warn("[ClassEditor] HasKey requires at least 1 property");
        alert("Please select at least 1 property for the key.");
        return;
      }

      // If editing, delete the old one first
      if (editingHasKeyId) {
        console.log("[ClassEditor] Editing has key - deleting old:", editingHasKeyId);
        await ontologyMutationService.deleteHasKey(projectId, item.id, editingHasKeyId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      console.log("[ClassEditor] Adding has key:", { classIri: item.id, propertyIris });
      await ontologyMutationService.addHasKey(projectId, item.id, propertyIris);
      console.log("[ClassEditor] addHasKey completed");

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding has key");
      await loadClassDetails();
      console.log("[ClassEditor] loadClassDetails completed after has key");
    } catch (error) {
      console.error("[ClassEditor] Failed to add has key:", error);
      alert(`Failed to add has key: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsHasKeyOpen(false);
      setEditingHasKeyId(undefined);
      setEditingHasKeyProperties([]);
    }
  };

  // Instance handlers
  const handleAddInstance = async (name: string) => {
    console.log("[ClassEditor] handleAddInstance called:", { name, classIri: item.id });
    try {
      if (onAddIndividual) {
        await onAddIndividual(name, item.id);
      } else {
        // Fallback to direct mutation service call
        await ontologyMutationService.addIndividual(projectId, name, item.id);
      }

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading instances after adding");
      await loadInstances();

      // Refresh individuals in parent if callback provided
      if (onRefreshIndividuals) {
        onRefreshIndividuals();
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to add instance:", error);
      alert(`Failed to add instance: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleDeleteInstance = async (individualIri: string) => {
    console.log("[ClassEditor] handleDeleteInstance called:", { individualIri, classIri: item.id });
    try {
      // Remove the class assertion (type) from the individual
      await ontologyMutationService.removeClassAssertion(projectId, individualIri, item.id);

      // Small delay to allow GraphDB to process the mutation
      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading instances after removing class assertion");
      await loadInstances();

      // Refresh individuals in parent if callback provided
      if (onRefreshIndividuals) {
        onRefreshIndividuals();
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to remove instance:", error);
      alert(`Failed to remove instance: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleEditInstance = (instanceId: string) => {
    console.log("[ClassEditor] handleEditInstance called:", { instanceId, classIri: item.id });
    setEditingInstanceId(instanceId);
    setIsInstancesOpen(true);
  };

  // General Class Axiom handlers
  const handleAddGCA = () => {
    console.log("[ClassEditor] Opening GCA editor for new axiom");
    setEditingGCAId(undefined);
    setIsGCAEditorOpen(true);
  };

  const handleEditGCA = (axiomId: string) => {
    console.log("[ClassEditor] Opening GCA editor for existing axiom:", axiomId);
    setEditingGCAId(axiomId);
    setIsGCAEditorOpen(true);
  };

  const handleDeleteGCA = async (axiomId: string) => {
    console.log("[ClassEditor] Deleting GCA:", axiomId);
    try {
      // GCAs are stored as SubClassOf axioms with blank node subjects
      // Delete the axiom by its blank node ID
      await ontologyMutationService.deleteAxiom(projectId, axiomId);
      await new Promise((resolve) => setTimeout(resolve, 500));
      await loadClassDetails();
    } catch (error) {
      console.error("[ClassEditor] Failed to delete GCA:", error);
      alert(`Failed to delete general class axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    }
  };

  const handleGCAConfirm = async (expression: string) => {
    console.log("[ClassEditor] GCA confirm:", { expression, editing: editingGCAId });
    try {
      if (editingGCAId) {
        // Edit existing GCA - delete old and add new
        await ontologyMutationService.deleteAxiom(projectId, editingGCAId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      // Add the new GCA - the expression should be in format "AnonymousExpression SubClassOf ClassName"
      // For now, we'll add it as a SubClassOf axiom mentioning this class
      await ontologyMutationService.addAxiom(projectId, item.id, "GeneralClassAxiom", expression);

      await new Promise((resolve) => setTimeout(resolve, 500));
      await loadClassDetails();
    } catch (error) {
      console.error("[ClassEditor] Failed to save GCA:", error);
      alert(`Failed to save general class axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsGCAEditorOpen(false);
      setEditingGCAId(undefined);
    }
  };

  const handleInstancesConfirm = async (selectedIndividuals: Individual[]) => {
    console.log("[ClassEditor] handleInstancesConfirm called:", {
      selectedCount: selectedIndividuals.length,
      classIri: item.id,
      isEditing: !!editingInstanceId,
    });
    // This handles adding existing individuals as instances of this class
    try {
      // If editing, remove the old instance first
      if (editingInstanceId) {
        console.log("[ClassEditor] Editing instance - removing old:", editingInstanceId);
        await ontologyMutationService.removeClassAssertion(projectId, editingInstanceId, item.id);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      // Add class assertions for selected individuals
      for (const individual of selectedIndividuals) {
        // Check if this individual is already an instance of this class
        if (!classInstances.some((i) => i.id === individual.id)) {
          // Add class assertion for this existing individual (not creating a new one)
          console.log("[ClassEditor] Adding class assertion:", { individualIri: individual.id, classIri: item.id });
          await ontologyMutationService.addClassAssertion(projectId, individual.id, item.id);
          console.log("[ClassEditor] Class assertion added successfully");
        }
      }

      // Small delay to allow GraphDB to process the mutations
      console.log("[ClassEditor] Waiting for GraphDB to process...");
      await new Promise((resolve) => setTimeout(resolve, 500));
      console.log("[ClassEditor] Reloading instances...");
      await loadInstances();
      console.log("[ClassEditor] Instances reloaded");

      if (onRefreshIndividuals) {
        onRefreshIndividuals();
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to add instances:", error);
      alert(`Failed to add instances: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsInstancesOpen(false);
      setEditingInstanceId(undefined);
    }
  };

  const annotationCount = Object.keys(item.annotations || {}).length;
  const displayAnnotations = loadingDetails ? {} : item.annotations || {};

  return (
    <div className="flex flex-col h-full bg-white">
      {loadingDetails && (
        <div className="sticky top-0 left-0 right-0 bg-blue-50 border-b border-blue-200 text-xs text-blue-800 px-3 py-1.5 z-20 flex items-center justify-center shadow-sm">
          <div className="animate-spin mr-2 h-3 w-3 border-2 border-blue-600 border-t-transparent rounded-full"></div>
          Loading <span className="font-semibold mx-1">{item.label || "class"}</span> details…
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
          onClick={() => setActiveTab("annotations")}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === "annotations" ? "border-purple-600 text-purple-700 bg-white" : "border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100"}`}
        >
          Annotations ({annotationCount})
        </button>
        <button
          onClick={() => setActiveTab("description")}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === "description" ? "border-purple-600 text-purple-700 bg-white" : "border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100"}`}
        >
          Description
        </button>
        <button
          onClick={() => setActiveTab("usage")}
          className={`px-4 py-2 text-xs font-medium border-b-2 transition-colors ${activeTab === "usage" ? "border-purple-600 text-purple-700 bg-white" : "border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100"}`}
        >
          Usage
        </button>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
        {activeTab === "usage" && <UsageTab classIri={item.id} projectId={projectId} label={item.label} />}

        {activeTab === "annotations" && (
          <div className="space-y-0">
            {/* Annotations Panel Header - Clean minimal style */}
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5 flex items-center justify-between">
              <span className="text-xs font-medium text-stone-700">Annotations: {item.label}</span>
              <button
                onClick={isViewOnly ? () => onViewOnlyAction?.() : onAddAnnotation}
                className="p-1 hover:bg-stone-200 rounded text-stone-500 hover:text-stone-700"
                title={isViewOnly ? "View-only: upgrade to edit" : "Add annotation"}
              >
                <Plus size={14} />
              </button>
            </div>
            {/* Annotations Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm">
              <AnnotationsDisplay
                annotations={displayAnnotations}
                onDelete={onDeleteAnnotation}
                onEdit={onEditAnnotation}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />
            </div>
          </div>
        )}

        {activeTab === "description" && (
          <div className="space-y-0">
            {/* Description Panel Header - Clean minimal style */}
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5">
              <span className="text-xs font-medium text-stone-700">Description: {item.label}</span>
            </div>
            {/* Description Content */}
            <div className="bg-white border border-t-0 border-gray-200 rounded-b-sm p-3 space-y-4">
              {/* Equivalent To Section */}
              <AxiomSubsection
                title="Equivalent To"
                axioms={classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms}
                inferredAxioms={classDetails?.inferredEquivalentClassesAxioms}
                onAdd={(def) => handleAddAxiom("EquivalentTo", def)}
                onEdit={(id, newDef) => handleEditAxiom("EquivalentTo", id, newDef)}
                onDelete={(id) => handleDeleteAxiom("EquivalentTo", id)}
                onAddClick={() => openEditor("EquivalentTo", "Equivalent Class Expression")}
                onEditClick={(axiom, initialTab, restrictionData) =>
                  openEditor("EquivalentTo", "Equivalent Class Expression", axiom.definition, axiom.id, initialTab, restrictionData)
                }
                properties={properties}
                dataProperties={dataProperties}
                themeColor="yellow"
                onNavigate={handleNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* SubClass Of Section */}
              <AxiomSubsection
                title="SubClass Of"
                axioms={classDetails?.subClassOfAxioms || item.subClassOfAxioms}
                inferredAxioms={classDetails?.inferredSubClassOfAxioms}
                onAdd={(def) => handleAddAxiom("SubClassOf", def)}
                onEdit={(id, newDef) => handleEditAxiom("SubClassOf", id, newDef)}
                onDelete={(id) => handleDeleteAxiom("SubClassOf", id)}
                onAddClick={() => openEditor("SubClassOf", "SubClass Expression")}
                onEditClick={(axiom, initialTab, restrictionData) =>
                  openEditor("SubClassOf", "SubClass Expression", axiom.definition, axiom.id, initialTab, restrictionData)
                }
                properties={properties}
                dataProperties={dataProperties}
                themeColor="yellow"
                onNavigate={handleNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* General Class Axioms Section */}
              <AxiomSubsection
                title="General class axioms"
                axioms={classDetails?.generalClassAxioms || []}
                onAdd={(def) => handleGCAConfirm(def)}
                onEdit={(id, newDef) => handleGCAConfirm(newDef)}
                onDelete={(id) => handleDeleteGCA(id)}
                onAddClick={handleAddGCA}
                onEditClick={(axiom) => handleEditGCA(axiom.id)}
                emptyMessage=""
                themeColor="yellow"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* SubClass Of (Anonymous Ancestor) */}
              <AxiomSubsection
                title="SubClass Of (Anonymous Ancestor)"
                axioms={classDetails?.inheritedAnonymousAxioms || []}
                onAdd={() => {}}
                onDelete={() => {}}
                themeColor="yellow"
                isViewOnly={true}
              />

              {/* Instances Section */}
              <AxiomSubsection
                title="Instances"
                axioms={classInstances.map((instance) => ({
                  id: instance.id,
                  type: "Instance",
                  definition: instance.label,
                }))}
                onAdd={() => {}}
                onEdit={(id, newDef) => handleEditInstance(id)}
                onDelete={(id) => handleDeleteInstance(id)}
                onAddClick={() => { setEditingInstanceId(undefined); setIsInstancesOpen(true); }}
                onEditClick={(axiom) => handleEditInstance(axiom.id)}
                emptyMessage=""
                themeColor="yellow"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* Target for Key Section */}
              <AxiomSubsection
                title="Target for Key"
                axioms={classDetails?.hasKeyAxioms || item.hasKeyAxioms}
                onAdd={() => {}}
                onEdit={(id, newDef) => handleEditHasKey(id)}
                onDelete={(id) => handleDeleteHasKey(id)}
                onAddClick={() => setIsHasKeyOpen(true)}
                onEditClick={(axiom) => handleEditHasKey(axiom.id)}
                emptyMessage=""
                themeColor="yellow"
                properties={properties}
                dataProperties={dataProperties}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* Disjoint With Section */}
              <AxiomSubsection
                title="Disjoint With"
                axioms={classDetails?.disjointClassesAxioms || item.disjointClassesAxioms}
                inferredAxioms={classDetails?.inferredDisjointClassesAxioms}
                onAdd={(def) => handleAddAxiom("DisjointWith", def)}
                onEdit={(id, newDef) => handleEditDisjointWith(id)}
                onDelete={(id) => handleDeleteAxiom("DisjointWith", id)}
                onAddClick={() => setIsDisjointWithOpen(true)}
                onEditClick={(axiom) => handleEditDisjointWith(axiom.id)}
                emptyMessage=""
                properties={properties}
                dataProperties={dataProperties}
                themeColor="yellow"
                onNavigate={handleNavigate}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />

              {/* Disjoint Union Of Section */}
              <AxiomSubsection
                title="Disjoint Union Of"
                axioms={classDetails?.disjointUnionAxioms || item.disjointUnionAxioms}
                onAdd={() => {}}
                onEdit={(id, newDef) => handleEditDisjointUnion(id)}
                onDelete={(id) => handleDeleteDisjointUnion(id)}
                onAddClick={() => setIsDisjointUnionOpen(true)}
                onEditClick={(axiom) => handleEditDisjointUnion(axiom.id)}
                emptyMessage=""
                themeColor="yellow"
                properties={properties}
                dataProperties={dataProperties}
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
              />
            </div>
          </div>
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
        onAddClass={onAddClassInline}
        onDeleteClass={onDeleteClass}
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
        onAddClass={onAddClassInline}
        onDeleteClass={onDeleteClass}
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

      {/* Instances Selector Dialog */}
      <IndividualSelectorDialog
        isOpen={isInstancesOpen}
        onClose={() => {
          setIsInstancesOpen(false);
          setEditingInstanceId(undefined);
        }}
        onConfirm={handleInstancesConfirm}
        individuals={propIndividuals}
        projectId={projectId}
        title={editingInstanceId ? "Edit Instance" : "Add Instance"}
        classIri={item.id}
        classLabel={item.label}
        excludeIndividualIds={classInstances.filter((i) => i.id !== editingInstanceId).map((i) => i.id)}
        minSelection={editingInstanceId ? 1 : 0}
        initialSelectedIds={editingInstanceId ? [editingInstanceId] : []}
        onAddIndividual={handleAddInstance}
        onDeleteIndividual={onDeleteIndividual}
      />

      {/* General Class Axiom (GCA) Editor Dialog - Simple text area like Protégé */}
      <ClassExpressionDialog
        isOpen={isGCAEditorOpen}
        onClose={() => {
          setIsGCAEditorOpen(false);
          setEditingGCAId(undefined);
        }}
        onConfirm={handleGCAConfirm}
        title={editingGCAId ? "Edit General Class Axiom" : "Add General Class Axiom"}
        classHierarchy={classHierarchy}
        expandedNodes={expandedNodes}
        onToggleNode={onToggleNode}
        objectProperties={properties || []}
        dataProperties={dataProperties || []}
        objectPropertiesTree={objectPropertyHierarchy}
        dataPropertiesTree={dataPropertyHierarchy}
        projectId={projectId}
        onAddClass={onAddClass}
        onDeleteClass={onDeleteClass}
        onRefreshClasses={onRefreshClasses}
        initialTab="classExpression"
        allowedTabs={["classExpression"]}
        initialValue={
          editingGCAId
            ? classDetails?.generalClassAxioms?.find((a: Axiom) => a.id === editingGCAId)?.definition
            : undefined
        }
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
