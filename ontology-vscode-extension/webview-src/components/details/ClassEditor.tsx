import React, { useState, useEffect, useRef } from 'react';
import ClassUsageTab from './ClassUsageTab';
import { Plus, Trash2, ExternalLink, AlertCircle, Edit3, User } from 'lucide-react';
import { Panel, AnnotationsDisplay, AxiomSubsection, CollaboratorPresenceBar } from './common';
import { ClassExpressionDialog, MultiClassSelectorDialog, MultiPropertySelectorDialog, IRIEditorDialog, IndividualSelectorDialog, RestrictionData } from '../dialogs';
import GCIEditorDialog from '../dialogs/GCIEditorDialog';
import expressionService from '../../services/expressionService';
import apiClient from '../../services/apiClient';
import ontologyMutationService from '../../services/ontologyMutationService';
import { notificationService } from '../../services/notificationService';
import { friendlyApiErrorMessage } from '../../utils/apiErrors';
import { isDesktop, waitForDesktopOwlApiReady, isOwlApiWarmingResponse } from '../../utils/desktop';
import { withDraftScope, withDraftAndUser, draftBodyFields } from '../../utils/draftScope';
import { useCommittedChangeRefresh } from '../../hooks/useCommittedChangeRefresh';
import { useAuth } from '../../custom-hook/useAuth';
import { useCollaboration } from '../../contexts/CollaborationContext';
import type { TreeNode, Axiom, ClassUsage, AxiomUsage, Individual, SelectableItem } from '../../types';
import { dispatchCollaborationNavigate } from '../../utils/collaborationNavigation';
import { readerUserId } from '../../utils/mutationActor';
import { buildLabelLookup, describeInheritance, type AncestorEntry } from '../../utils/ancestorInheritance';
import { normalizeReasonerType } from '../dashboard-parts/dashboardUtils';
import { cachedUnsatisfiable } from '../../services/reasonerSnapshotScope';


const conflictIrisCache = new Map<string, Set<string> | undefined>();
let conflictIrisCacheProjectId: string | undefined;

function conflictIrisCacheKey(reasonerLabel: string, classIri: string, draft: boolean, userId?: string): string {
  return `${reasonerLabel}::${draft ? `draft:${userId || ''}` : 'published'}::${classIri}`;
}

type AxiomType = 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';
type DirectParent = { iri: string; label: string };

const ClassEditor: React.FC<{
  item: TreeNode;
  projectId: string;
  onUpdate: (updatedItem: SelectableItem, markUnsaved?: boolean, previousId?: string) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  activeTheme?: string;
  classHierarchy?: TreeNode[];
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  expandedNodes?: string[];
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
  individuals?: Individual[];
  onAddIndividual?: (name: string, classIri: string) => Promise<void>;
  onDeleteIndividual?: (id: string) => Promise<void>;
  onRefreshIndividuals?: () => void;
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
  existingIris?: string[];
  selectedReasoner?: string;
  syncMode?: 'private' | 'public';
}> = ({ item, projectId, onUpdate, onAddAnnotation, onEditAnnotation, onDeleteAnnotation, activeTheme, classHierarchy = [], onToggleNode, expandedNodes = [], onAddClass, onAddClassInline, onDeleteClass, onRefreshClasses, onAddObjectProperty, onAddDataProperty, onDeleteProperty, metadata, objectPropertyHierarchy: propObjectPropertyHierarchy, dataPropertyHierarchy: propDataPropertyHierarchy, objectProperties: propObjectProperties, dataProperties: propDataProperties, viewMode = 'asserted', individuals: propIndividuals = [], onAddIndividual, onDeleteIndividual, onRefreshIndividuals, isViewOnly = false, onViewOnlyAction, existingIris = [], selectedReasoner = 'HERMIT', syncMode = 'public' }) => {
  const { user } = useAuth();
  const collaboration = useCollaboration();

  const itemRef = useRef(item);
  useEffect(() => {
    itemRef.current = item;
  });

  const [activeTab, setActiveTab] = useState<"annotations" | "usage" | "description">("description");
  const [loadingAnnotations, setLoadingAnnotations] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [loadingDetailsElapsed, setLoadingDetailsElapsed] = useState(0);
  const [axiomsLoaded, setAxiomsLoaded] = useState(false);
  const [descriptionTimedOut, setDescriptionTimedOut] = useState(false);
  const [classDetails, setClassDetails] = useState<any>(null);
  const [overviewReadyKey, setOverviewReadyKey] = useState('');
  const [parentResult, setParentResult] = useState<{ key: string; parents: DirectParent[] | null }>();
  const automaticDescriptionKey = useRef('');
  const [conflictIris, setConflictIris] = useState<Set<string> | undefined>(undefined);
  const [lastEditAt, setLastEditAt] = useState(0);

  useEffect(() => {
    if (!projectId || !item.id) {
      setConflictIris(undefined);
      return;
    }
    if (conflictIrisCacheProjectId !== projectId) {
      conflictIrisCache.clear();
      conflictIrisCacheProjectId = projectId;
    }
    let cancelled = false;

    const loadConflictIris = async () => {

      let unsatisfiable = item.isUnsatisfiable === true;
      const knownFromSnapshot = unsatisfiable ? null : cachedUnsatisfiable(projectId, selectedReasoner, item.id);
      if (knownFromSnapshot !== null) {
        unsatisfiable = knownFromSnapshot;
      } else if (!unsatisfiable) {
        try {
          const quick: any = await apiClient.get(withDraftAndUser(`/api/ontology/${projectId}/unsatisfiable-classes-quick`));
          const list: string[] = Array.isArray(quick?.unsatisfiableClasses) ? quick.unsatisfiableClasses : [];
          unsatisfiable = list.includes(item.id);
        } catch (e) {
          console.warn('[ClassEditor] conflictIris: unsatisfiable-classes-quick failed', e);
          unsatisfiable = false;
        }
      }
      if (cancelled) return;
      if (!unsatisfiable) {
        setConflictIris(undefined);
        return;
      }

      const draftFields = draftBodyFields();
      const cacheKey = conflictIrisCacheKey(selectedReasoner, item.id, draftFields.draft === 'true', draftFields.userId);
      if (conflictIrisCache.has(cacheKey)) {
        setConflictIris(conflictIrisCache.get(cacheKey));
        return;
      }

      try {
        const res = await apiClient.post<any>(`/plugin-service/api/reasoner/${projectId}/explain-entailment`, {
          entailmentType: 'UNSATISFIABLE_CLASS',
          subjectIri: item.id,
          mode: 'regular',
          maxJustifications: 10,
          ...draftFields,
        });
        if (cancelled) return;
        const data = (res as any)?.data ?? res;
        const justifications = data?.justifications ?? [];
        const iris = new Set<string>();
        for (const justification of justifications) {
          for (const axiom of justification?.axioms ?? []) {
            if (axiom?.role !== 'CONFLICT') continue;
            for (const entity of axiom?.entities ?? []) {
              if (entity?.iri && entity.iri !== item.id) iris.add(entity.iri);
            }
          }
        }
        conflictIrisCache.set(cacheKey, iris);
        setConflictIris(iris);
      } catch (e) {
        console.warn('[ClassEditor] conflictIris: explain-entailment failed', e);
        if (!cancelled) setConflictIris(undefined);
      }
    };

    const debounceTimer = setTimeout(() => { void loadConflictIris(); }, 300);
    return () => { cancelled = true; clearTimeout(debounceTimer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id, item.isUnsatisfiable, projectId, lastEditAt, selectedReasoner]);

  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorType, setEditorType] = useState<AxiomType | null>(null);
  const [editorTitle, setEditorTitle] = useState("");
  const [editorExistingValue, setEditorExistingValue] = useState<string | undefined>();
  const [editorExistingId, setEditorExistingId] = useState<string | undefined>();
  const [editorInitialClassIri, setEditorInitialClassIri] = useState<string | undefined>();
  const [editorInitialTab, setEditorInitialTab] = useState<
    "hierarchy" | "objectRestriction" | "dataRestriction" | "classExpression" | undefined
  >();
  const [editorInitialRestrictionData, setEditorInitialRestrictionData] = useState<any>();
  const [editorAllowedTabs, setEditorAllowedTabs] = useState<("hierarchy" | "objectRestriction" | "dataRestriction" | "classExpression")[] | undefined>();
  const [editorSubjectClassIri, setEditorSubjectClassIri] = useState<string | undefined>();
  const [allClassesLookup, setAllClassesLookup] = useState<Map<string, string>>(new Map());
  const allClassesLookupProjectRef = useRef<string | null>(null);

  const [properties, setProperties] = useState<any[]>(propObjectProperties || []);
  const [dataProperties, setDataProperties] = useState<any[]>(propDataProperties || []);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<TreeNode[]>(propObjectPropertyHierarchy || []);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<TreeNode[]>(propDataPropertyHierarchy || []);

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

  const [isDisjointWithOpen, setIsDisjointWithOpen] = useState(false);
  const [editingDisjointWithId, setEditingDisjointWithId] = useState<string | undefined>();
  const [editingDisjointWithTarget, setEditingDisjointWithTarget] = useState<string | undefined>();

  const [isDisjointUnionOpen, setIsDisjointUnionOpen] = useState(false);
  const [editingDisjointUnionId, setEditingDisjointUnionId] = useState<string | undefined>();
  const [editingDisjointUnionMembers, setEditingDisjointUnionMembers] = useState<string[]>([]);

  const [isHasKeyOpen, setIsHasKeyOpen] = useState(false);
  const [editingHasKeyId, setEditingHasKeyId] = useState<string | undefined>();
  const [editingHasKeyProperties, setEditingHasKeyProperties] = useState<string[]>([]);

  const [isInstancesOpen, setIsInstancesOpen] = useState(false);
  const [classInstances, setClassInstances] = useState<Individual[]>([]);
  const descriptionAbortRef = useRef<AbortController | null>(null);
  const detailsLoadGenRef = useRef(0);
  const isSavingAxiomRef = useRef(false);
  const [editingInstanceId, setEditingInstanceId] = useState<string | undefined>();

  const [isGCAEditorOpen, setIsGCAEditorOpen] = useState(false);
  const [editingGCAId, setEditingGCAId] = useState<string | undefined>();

  const [isSavingAxiom, setIsSavingAxiom] = useState(false);

  const [isIRIEditorOpen, setIsIRIEditorOpen] = useState(false);

  useEffect(() => {
    if (!isIRIEditorOpen) return;

    collaboration.requestLock(item.id);
    const refreshInterval = setInterval(() => {
      collaboration.requestLock(item.id);
    }, 15000);

    return () => {
      clearInterval(refreshInterval);
      collaboration.releaseLock(item.id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isIRIEditorOpen, item.id]);

  const openIRIEditor = () => {
    const existingLock = collaboration.state.locks.get(item.id);
    const myUserId = user?.userId || user?.username;
    if (existingLock && existingLock.userId !== myUserId && existingLock.expiresAt > Date.now()) {
      notificationService.warning("Locked", `${existingLock.username} is currently editing this — try again shortly.`);
      return;
    }
    setIsIRIEditorOpen(true);
  };

const handleSaveIRI = async (newIRI: string, newLabel: string) => {
  try {
    const iriChanged = newIRI !== item.id;
    const labelChanged = newLabel !== item.label;
    const previousId = item.id;

    const targetIri = iriChanged ? newIRI : item.id;

    if (iriChanged) {
      await ontologyMutationService.renameEntity(
        projectId, item.id, newIRI,
        user?.email || "anonymous", user?.username || "Anonymous",
      );
    }

    if (iriChanged || labelChanged) {
      await ontologyMutationService.updateClassLabel(projectId, targetIri, newLabel);
    }

    if (iriChanged || labelChanged) {
      const updatedItem = { ...item, id: newIRI, iri: newIRI, label: newLabel };
      onUpdate(updatedItem as TreeNode, true, iriChanged ? previousId : undefined);
    }
  } catch (error) {
    console.error("Failed to update entity:", error);
    notificationService.error("Rename Failed", error instanceof Error ? error.message : "Failed to rename entity.");
  }
};
  const inferredReasonerKey = viewMode === 'inferred' ? selectedReasoner : null;
  const readingUserId = readerUserId(user);
  const entityScopeKey = JSON.stringify([projectId, item.id, syncMode, readingUserId]);
  const descriptionKey = JSON.stringify([entityScopeKey, inferredReasonerKey]);
  useEffect(() => {
    if (!item.id || !projectId) return;

    let alive = true;

    const abortController = new AbortController();
    const signal = abortController.signal;

    setClassDetails(null);
    setClassInstances([]);
    setAxiomsLoaded(false);
    setDescriptionTimedOut(false);
    setLoadingAnnotations(true);
    setLoadingDetails(false);
    if (allClassesLookupProjectRef.current !== projectId) {
      setAllClassesLookup(new Map());
    }

    const query = `classIri=${encodeURIComponent(item.id)}${readingUserId ? `&userId=${encodeURIComponent(readingUserId)}` : ''}`;
    const annotationsAtFetchStart = item.annotations;
    const loadAnnotations = async () => {
      try {
        const annResp = await apiClient.get<any>(
          withDraftScope(`/api/ontology/classes/annotations/${projectId}?${query}`),
          undefined,
          { signal, timeout: 10_000 },
        );
        if (!alive) return;
        const annData = annResp?.data?.data || annResp?.data || annResp;
        const latestItem = itemRef.current;
        if (annData?.annotations && latestItem.id === item.id && latestItem.annotations === annotationsAtFetchStart) {
          onUpdate({ ...latestItem, annotations: annData.annotations } as TreeNode);
        }
      } catch (e) {
        if (alive) console.warn('[ClassEditor] Could not load annotations', e);
      } finally {
        if (alive) setLoadingAnnotations(false);
      }
    };
    const loadParents = async () => {
      try {
        const response = await apiClient.get<any>(
          withDraftScope(`/api/ontology/classes/details/${projectId}?${query}&view=parents`),
          undefined,
          { signal, timeout: 10_000 },
        );
        const parents = response?.data?.data || response?.data || response;
        if (alive) setParentResult({ key: descriptionKey, parents: Array.isArray(parents) ? parents : null });
      } catch {
        if (alive) setParentResult({ key: descriptionKey, parents: null });
      }
    };

    void Promise.all([loadAnnotations(), loadParents()]).then(() => {
      if (alive) setOverviewReadyKey(descriptionKey);
    });
    if (!propObjectProperties?.length && !propDataProperties?.length) void loadProperties();

    return () => {
      alive = false;
      abortController.abort();
      descriptionAbortRef.current?.abort();
      detailsLoadGenRef.current++;
      automaticDescriptionKey.current = '';
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [descriptionKey]);

  useEffect(() => {
    if (isEditorOpen || isGCAEditorOpen || (classDetails?.generalClassAxioms?.length ?? 0) > 0) {
      loadAllClassesLookup();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isEditorOpen, isGCAEditorOpen, classDetails?.generalClassAxioms?.length]);

  useEffect(() => {
    const handleRemoteEdit = (e: Event) => {
      const edit = (e as CustomEvent).detail;
      if (!edit || edit.nodeId !== item.id) return;
      if (isSavingAxiomRef.current) return;
      const CLASS_CHANGE_TYPES = new Set([
        "CLASS_MODIFIED", "CLASS_RENAMED",
        "EQUIVALENT_ADDED", "EQUIVALENT_REMOVED",
        "SUBCLASS_ADDED", "SUBCLASS_REMOVED",
        "DISJOINT_ADDED", "DISJOINT_REMOVED",
        "ANNOTATION_ADDED", "ANNOTATION_MODIFIED", "ANNOTATION_DELETED",
      ]);
      if (CLASS_CHANGE_TYPES.has(edit.type)) {
        loadClassDetails(undefined, true);
      }
    };
    window.addEventListener("remoteEditReceived", handleRemoteEdit);
    return () => window.removeEventListener("remoteEditReceived", handleRemoteEdit);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  const loadProperties = async () => {
    try {
      const allPropsResponse = await apiClient.get(withDraftScope(`/api/ontology/properties/${projectId}`));
      const allProps = allPropsResponse?.data?.data || allPropsResponse?.data || [];

      const objProps = allProps.filter((p: any) => p.type === "ObjectProperty");
      const dataProps = allProps.filter((p: any) => p.type === "DatatypeProperty");

      setProperties(objProps);
      setDataProperties(dataProps);

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

  const loadAllClassesLookup = async () => {
    if (allClassesLookup.size > 0 && allClassesLookupProjectRef.current === projectId) return;
    try {
      const resp = await apiClient.get<any>(withDraftScope(`/api/ontology/classes/all/${projectId}?limit=5000`));
      const classes: any[] = resp?.data?.classes ?? resp?.data ?? resp?.classes ?? (Array.isArray(resp) ? resp : []);
      const lookup = new Map<string, string>();
      for (const c of classes) {
        if (!c?.id) continue;
        if (c.label) lookup.set(c.label, c.id);
        const fragment = c.id.split(/[#\/]/).pop();
        if (fragment && fragment !== c.id) lookup.set(fragment, c.id);
        lookup.set(c.id, c.id);
      }
      allClassesLookupProjectRef.current = projectId;
      setAllClassesLookup(lookup);
    } catch (e) {
      console.warn("[ClassEditor] Failed to load all-classes lookup:", e);
    }
  };

  const loadClassDetails = async (signal?: AbortSignal, afterMutation?: boolean): Promise<any | null> => {
    const loadGen = ++detailsLoadGenRef.current;
    const annotationsAtStart = itemRef.current.annotations;
    setLoadingDetails(true);
    setLoadingDetailsElapsed(0);
    const elapsedTimer = setInterval(() => {
      setLoadingDetailsElapsed((s) => s + 1);
    }, 1000);
    try {
      if (afterMutation) {
        if (isDesktop()) {
          await waitForDesktopOwlApiReady(projectId);
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
      }
      const userId = readerUserId(user);
      const userParam = userId ? `&userId=${encodeURIComponent(userId)}` : '';
      let response = await apiClient.get<any>(
        withDraftScope(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(item.id)}${userParam}&_=${Date.now()}`),
        undefined,
        signal ? { signal } : undefined,
      );
      if (afterMutation && isOwlApiWarmingResponse(response)) {
        await waitForDesktopOwlApiReady(projectId);
        response = await apiClient.get<any>(
          withDraftScope(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(item.id)}${userParam}&_=${Date.now()}`),
          undefined,
          signal ? { signal } : undefined,
        );
      }
      if (signal?.aborted || loadGen !== detailsLoadGenRef.current) {
        console.log("[ClassEditor] Discarding stale class details response");
        return null;
      }
      let details = response?.data?.data || response?.data || response;
      const equivAxioms = details?.equivalentClassesAxioms || [];
      const restrictionEquivs = equivAxioms.filter(
        (a: Axiom) => a.isRestriction === true || a.isRestriction === "true",
      );
      console.log("[ClassEditor] Class details loaded:", {
        id: details?.id,
        equivalentCount: equivAxioms.length,
        restrictionEquivCount: restrictionEquivs.length,
        restrictionEquivs: restrictionEquivs.map((a: Axiom) => a.definition),
        full: details,
      });

      const hasOwlApiInferred =
        details?.inferredFromOwlApi === true ||
        (details != null && "inferredEquivalentClassesAxioms" in details);

      if (viewMode === "inferred" && !hasOwlApiInferred) {
        try {
          const inferredResponse = await apiClient.get<any>(
            withDraftAndUser(`/api/ontology/${projectId}/reasoner/inferred-class-details?classIri=${encodeURIComponent(item.id)}&reasonerType=${encodeURIComponent(normalizeReasonerType(selectedReasoner))}`),
            undefined,
            { signal, timeout: isDesktop() ? 15_000 : 45_000 },
          );
          const inferredData = inferredResponse?.data?.data || inferredResponse?.data || {};
          console.log("[ClassEditor] Inferred class details loaded:", inferredData);

          details = {
            ...details,
            inferredSubClassOfAxioms: inferredData.inferredSubClassOfAxioms || [],
            inferredEquivalentClassesAxioms: inferredData.inferredEquivalentClassesAxioms || [],
            inferredDisjointClassesAxioms: inferredData.inferredDisjointClassesAxioms || [],
            isUnsatisfiable: inferredData.isUnsatisfiable || false,
          };
        } catch (err) {
          console.warn("[ClassEditor] Failed to load inferred details:", err);
          if (!signal?.aborted && loadGen === detailsLoadGenRef.current) {
            notificationService.warning(
              "Inferred axioms unavailable",
              friendlyApiErrorMessage(err, "Reasoner timed out — showing asserted axioms only."),
            );
          }
        }
      }

      if (signal?.aborted || loadGen !== detailsLoadGenRef.current) return null;
      setClassDetails(details);
      setAxiomsLoaded(true);
      if (afterMutation) {
        setLastEditAt(Date.now());
      }

      const latestItem = itemRef.current;
      const updatedItem: TreeNode = {
        ...latestItem,
        annotations: latestItem.annotations === annotationsAtStart
          ? details.annotations || latestItem.annotations
          : latestItem.annotations,
        subClassOfAxioms: details.subClassOfAxioms || latestItem.subClassOfAxioms,
        equivalentClassesAxioms: details.equivalentClassesAxioms || latestItem.equivalentClassesAxioms,
        disjointClassesAxioms: details.disjointClassesAxioms || latestItem.disjointClassesAxioms,
        disjointUnionAxioms: details.disjointUnionAxioms || latestItem.disjointUnionAxioms,
        hasKeyAxioms: details.hasKeyAxioms || latestItem.hasKeyAxioms,
        inferredSubClassOfAxioms: details.inferredSubClassOfAxioms,
        inferredEquivalentClassesAxioms: details.inferredEquivalentClassesAxioms,
        isUnsatisfiable: details.isUnsatisfiable,
      };
      console.log("[ClassEditor] Updated item:", updatedItem);
      onUpdate(updatedItem);
      return details;
    } catch (error) {
      if (signal?.aborted || loadGen !== detailsLoadGenRef.current) return null;
      console.error("Failed to load class details:", error);
      notificationService.error(
        "Description not ready",
        friendlyApiErrorMessage(error, "Could not load class axioms"),
      );
    } finally {
      clearInterval(elapsedTimer);
      if (loadGen === detailsLoadGenRef.current) {
        setLoadingDetails(false);
        setLoadingDetailsElapsed(0);
      }
    }
    return null;
  };

  const axiomListForType = (details: any, axiomType: AxiomType): Axiom[] | undefined => {
    if (!details) return undefined;
    if (axiomType === "EquivalentTo") return details.equivalentClassesAxioms;
    if (axiomType === "SubClassOf") return details.subClassOfAxioms;
    return details.disjointClassesAxioms;
  };

  const restrictionMatchesAxiom = (
    axiom: Axiom,
    restrictionData: RestrictionData,
    axiomType: AxiomType,
  ): boolean => {
    const isRestriction = axiom.isRestriction === true || axiom.isRestriction === "true";
    if (!isRestriction) return false;
    if (axiom.type !== axiomType) return false;
    if (axiom.propertyIri !== restrictionData.propertyIri) return false;
    if (axiom.restrictionType !== restrictionData.restrictionType) return false;
    if (axiom.fillerIri !== restrictionData.fillerIri) return false;
    if (restrictionData.cardinality != null) {
      const card = typeof axiom.cardinality === "string" ? parseInt(axiom.cardinality, 10) : axiom.cardinality;
      if (card !== restrictionData.cardinality) return false;
    }
    return true;
  };

  const reloadDetailsUntilRestrictionVisible = async (
    restrictionData: RestrictionData,
    axiomType: AxiomType,
    maxAttempts = 6,
    delayMs = 500,
  ): Promise<void> => {
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      const details = await loadClassDetails();
      const axioms = axiomListForType(details, axiomType) || [];
      const found = axioms.some((a) => restrictionMatchesAxiom(a, restrictionData, axiomType));
      if (found) {
        console.log(`[ClassEditor] Restriction visible after attempt ${attempt}`);
        return;
      }
      if (attempt < maxAttempts) {
        console.log(`[ClassEditor] Restriction not visible yet (attempt ${attempt}/${maxAttempts}), retrying...`);
        await new Promise((resolve) => setTimeout(resolve, delayMs));
      }
    }
    console.warn("[ClassEditor] Restriction not found in class details after retries");
    notificationService.warning(
      "Restriction may not be visible yet",
      "The save succeeded but the new restriction is not showing. Refresh the Description tab or reload the project.",
    );
  };

  useEffect(() => {
    if (activeTab === 'description' && overviewReadyKey === descriptionKey && !axiomsLoaded && !loadingDetails
        && automaticDescriptionKey.current !== descriptionKey) {
      automaticDescriptionKey.current = descriptionKey;
      void loadDescription();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, axiomsLoaded, loadingDetails, overviewReadyKey, descriptionKey]);

  const loadDescription = async () => {
    descriptionAbortRef.current?.abort();
    const controller = new AbortController();
    descriptionAbortRef.current = controller;
    setDescriptionTimedOut(false);
    const watchdog = setTimeout(() => {
      if (controller.signal.aborted || descriptionAbortRef.current !== controller) return;
      controller.abort();
      setLoadingDetails(false);
      setDescriptionTimedOut(true);
    }, isDesktop() ? 45_000 : 90_000);
    try {
      const [details] = await Promise.all([loadClassDetails(controller.signal), loadInstances(controller.signal)]);
      if (!details && !controller.signal.aborted && descriptionAbortRef.current === controller) {
        setDescriptionTimedOut(true);
      }
    } finally {
      clearTimeout(watchdog);
    }
  };

  useCommittedChangeRefresh(projectId, () => {
    if (axiomsLoaded) {
      void loadDescription();
    }
  }, ['UNDO', 'REDO', 'APPLY']);

  const loadInstances = async (signal?: AbortSignal): Promise<any[]> => {
    try {
      const response = await apiClient.get<any>(
        withDraftScope(`/api/ontology/classes/instances/${projectId}?classIri=${encodeURIComponent(item.id)}`),
        undefined,
        signal ? { signal } : undefined,
      );
      const instances = response?.data?.data || response?.data || response || [];
      const list = Array.isArray(instances) ? instances : [];
      console.log("[ClassEditor] Class instances loaded:", list.length);
      if (!signal?.aborted) setClassInstances(list);
      return list;
    } catch (error) {
      console.error("Failed to load class instances:", error);
      if (!signal?.aborted) setClassInstances([]);
      return [];
    }
  };

  const handleNavigate = (iri: string, type: string) => {
    console.log("[ClassEditor] Navigate to:", { iri, type });
    dispatchCollaborationNavigate({ projectId, entityIRI: iri, entityType: type });
  };

  const openEditor = (
    type: AxiomType,
    title: string,
    existingValue?: string,
    existingId?: string,
    initialTab?: "hierarchy" | "objectRestriction" | "dataRestriction" | "classExpression",
    restrictionData?: any,
    subjectClassIri?: string,
  ) => {
    console.log("[ClassEditor] openEditor called:", { type, title, classHierarchyLength: classHierarchy.length });
    setEditorType(type);
    if (existingValue && existingId) {
      setEditorTitle(`Edit ${title}`);
      setEditorExistingValue(existingValue);
      setEditorExistingId(existingId);
      setEditorInitialClassIri(initialTab === "hierarchy" ? existingId : undefined);
    } else {
      setEditorTitle(`Add ${title}`);
      setEditorExistingValue(undefined);
      setEditorExistingId(undefined);
      setEditorInitialClassIri(undefined);
    }
    setEditorInitialTab(initialTab);
    setEditorInitialRestrictionData(restrictionData);
    setEditorSubjectClassIri(subjectClassIri);

    if (existingValue && existingId) {
      if (initialTab === "dataRestriction" || restrictionData?.isDataProperty) {
        setEditorAllowedTabs(["dataRestriction", "classExpression"]);
      } else if (initialTab === "objectRestriction" || (restrictionData && !restrictionData.isDataProperty)) {
        setEditorAllowedTabs(["objectRestriction", "classExpression"]);
      } else if (initialTab === "hierarchy") {
        setEditorAllowedTabs(["hierarchy", "classExpression"]);
      } else {
        setEditorAllowedTabs(["classExpression"]);
      }
    } else {
      setEditorAllowedTabs(undefined);
    }

    setIsEditorOpen(true);
  };

  const normalizeSingleClassExpressionForSave = (expression: string, restrictionData?: RestrictionData): string => {
    const trimmed = expression.trim();
    if (!trimmed || restrictionData) return expression;
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("urn:")) return trimmed;
    if (/\s+(and|or)\s+/i.test(trimmed)) return expression;
    return findClassIriByLabelOrIri(trimmed, classHierarchy) || expression;
  };

  const handleEditorConfirm = async (expression: string, restrictionData?: RestrictionData) => {
    const expressionToSave = normalizeSingleClassExpressionForSave(expression, restrictionData);

    if (!editorType) {
      notificationService.error("Editor Error", "Editor type not set. Please try again.");
      return;
    }

    const subjectClassIri = editorSubjectClassIri || item.id;

    try {
      if (editorExistingId) {
        const axiomType = editorType as "SubClassOf" | "EquivalentTo" | "DisjointWith";

        const oldDesc: Parameters<typeof ontologyMutationService.replaceAxiom>[3] =
          editorInitialRestrictionData
            ? {
                restriction: {
                  property: editorInitialRestrictionData.propertyIri!,
                  restrictionType: editorInitialRestrictionData.restrictionType!,
                  filler: editorInitialRestrictionData.fillerIri!,
                  cardinality: editorInitialRestrictionData.cardinality,
                  isData: editorInitialRestrictionData.type === 'dataRestriction',
                },
              }
            : { iri: editorExistingId };

        const isNewIRI =
          expressionToSave.startsWith("http://") ||
          expressionToSave.startsWith("https://") ||
          expressionToSave.startsWith("urn:");

        if (restrictionData) {
          const newDesc: Parameters<typeof ontologyMutationService.replaceAxiom>[4] = {
            restriction: {
              property: restrictionData.propertyIri!,
              restrictionType: restrictionData.restrictionType!,
              filler: restrictionData.fillerIri!,
              cardinality: restrictionData.cardinality,
              isData: restrictionData.type === 'dataRestriction',
            },
          };
          await ontologyMutationService.replaceAxiom(
            projectId, subjectClassIri, axiomType, oldDesc, newDesc,
            user?.email, user?.username || user?.email,
          );
        } else if (isNewIRI) {
          await ontologyMutationService.replaceAxiom(
            projectId, subjectClassIri, axiomType, oldDesc, { iri: expressionToSave },
            user?.email, user?.username || user?.email,
          );
        } else {
          const parsed = parseManchesterExpression(expressionToSave);
          if (parsed && axiomType !== "DisjointWith") {
            const newDesc: Parameters<typeof ontologyMutationService.replaceAxiom>[4] =
              parsed.expressionType === "intersection"
                ? { intersection: parsed.iris }
                : { union: parsed.iris };
            await ontologyMutationService.replaceAxiom(
              projectId, subjectClassIri, axiomType, oldDesc, newDesc,
              user?.email, user?.username || user?.email,
            );
          } else {
            await handleDeleteAxiom(axiomType, editorExistingId, subjectClassIri);
            await expressionService.addClassExpressionAxiom(
              projectId, subjectClassIri, axiomType, expressionToSave,
              user?.email, user?.username || user?.email,
            );
          }
        }

        await loadClassDetails(undefined, true);
      } else {
        await handleAddAxiom(editorType, expressionToSave, restrictionData);
      }
    } catch (error) {
      console.error("[ClassEditor] handleEditorConfirm failed:", error);
      notificationService.error("Save Failed", `Failed to save axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
      throw error;
    } finally {
      setIsEditorOpen(false);
      setEditorType(null);
      setEditorExistingValue(undefined);
      setEditorExistingId(undefined);
      setEditorInitialClassIri(undefined);
      setEditorInitialTab(undefined);
      setEditorInitialRestrictionData(undefined);
      setEditorSubjectClassIri(undefined);
    }
  };

  const findClassIriByLabelOrIri = (labelOrIri: string, nodes: TreeNode[]): string | null => {
    for (const node of nodes) {
      if (node.id === labelOrIri || node.label === labelOrIri) return node.id;
      if (node.children?.length) {
        const found = findClassIriByLabelOrIri(labelOrIri, node.children);
        if (found) return found;
      }
    }
    return allClassesLookup.get(labelOrIri) ?? null;
  };

  const parseManchesterExpression = (
    expr: string,
  ): { expressionType: "intersection" | "union"; iris: string[] } | null => {
    const trimmed = expr.trim();
    const andParts = trimmed.split(/\s+and\s+/i);
    const orParts = trimmed.split(/\s+or\s+/i);

    const tryResolve = (parts: string[]): string[] | null => {
      const iris: string[] = [];
      for (const part of parts) {
        const p = part.trim();
        if (!p) return null;
        const iri = findClassIriByLabelOrIri(p, classHierarchy);
        if (!iri) return null;
        iris.push(iri);
      }
      return iris.length >= 2 ? iris : null;
    };

    if (andParts.length >= 2) {
      const iris = tryResolve(andParts);
      if (iris) return { expressionType: "intersection", iris };
    }
    if (orParts.length >= 2) {
      const iris = tryResolve(orParts);
      if (iris) return { expressionType: "union", iris };
    }
    return null;
  };

  const resolveGCAAxiom = (
    definition: string,
  ): { subjectIris: string[]; targetIri: string } | null => {
    const match = definition.match(/^\s*\(?(.+?)\)?\s+SubClassOf\s+(.+?)\s*$/i);
    if (!match) return null;
    const [, subExprText, targetExprText] = match;
    const parsedSub = parseManchesterExpression(subExprText);
    if (!parsedSub || parsedSub.expressionType !== "intersection" || parsedSub.iris.length !== 2) {
      return null;
    }
    const targetIri = findClassIriByLabelOrIri(targetExprText.trim(), classHierarchy);
    if (!targetIri) return null;
    return { subjectIris: parsedSub.iris, targetIri };
  };

  const handleAddAxiom = async (type: AxiomType, definition: string, restrictionData?: RestrictionData) => {
    console.log("[ClassEditor] handleAddAxiom called:", {
      type,
      definition,
      restrictionData,
      classHierarchyLength: classHierarchy.length,
    });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      if (restrictionData && type !== "DisjointWith") {
        restrictionData.axiomType = type;

        if (restrictionData.type === "objectRestriction") {
          if (!restrictionData.propertyIri || !restrictionData.fillerIri) {
            console.error("[ClassEditor] Object restriction missing propertyIri or fillerIri:", restrictionData);
            notificationService.error("Save Failed", "Restriction is missing required property or filler class.");
            return;
          }
          console.log("[ClassEditor] Adding object restriction:", {
            axiomType: restrictionData.axiomType,
            propertyIri: restrictionData.propertyIri,
            restrictionType: restrictionData.restrictionType,
            fillerIri: restrictionData.fillerIri,
            cardinality: restrictionData.cardinality,
          });
          await ontologyMutationService.addObjectRestriction(
            projectId,
            item.id,
            restrictionData.axiomType,
            restrictionData.propertyIri,
            restrictionData.restrictionType,
            restrictionData.fillerIri,
            restrictionData.cardinality,
            user?.email,
            user?.username || user?.email,
          );
        } else if (restrictionData.type === "dataRestriction") {
          if (!restrictionData.propertyIri || !restrictionData.fillerIri) {
            console.error("[ClassEditor] Data restriction missing propertyIri or fillerIri:", restrictionData);
            notificationService.error("Save Failed", "Restriction is missing required property or datatype.");
            return;
          }
          const validDataRestrictionTypes = ["some", "only", "min", "max", "exactly"];
          if (!validDataRestrictionTypes.includes(restrictionData.restrictionType)) {
            console.warn("[ClassEditor] Invalid restrictionType for data restriction:", restrictionData.restrictionType);
            return;
          }
          console.log("[ClassEditor] Adding data restriction:", {
            axiomType: restrictionData.axiomType,
            propertyIri: restrictionData.propertyIri,
            restrictionType: restrictionData.restrictionType,
            fillerIri: restrictionData.fillerIri,
            cardinality: restrictionData.cardinality,
          });
          await ontologyMutationService.addDataRestriction(
            projectId,
            item.id,
            restrictionData.axiomType,
            restrictionData.propertyIri,
            restrictionData.restrictionType as "some" | "only" | "min" | "max" | "exactly",
            restrictionData.fillerIri,
            restrictionData.cardinality,
            user?.email,
            user?.username || user?.email,
          );
        }
        const isCardinalityRestriction = ["min", "max", "exactly"].includes(restrictionData.restrictionType);
        await new Promise((resolve) => setTimeout(resolve, isCardinalityRestriction ? 1500 : 400));
        await reloadDetailsUntilRestrictionVisible(restrictionData, type);
        return;
      }

      if (!item.id) {
        notificationService.warning("Not Ready", "Cannot add axiom: class IRI is not yet available. Please wait for the class to finish loading.");
        return;
      }

      const isSimpleIRI =
        definition.startsWith("http://") || definition.startsWith("https://") || definition.startsWith("urn:");

      if (isSimpleIRI) {
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
                user?.username || user?.email,
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
                user?.username || user?.email,
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
                user?.username || user?.email,
              );
              console.log("[ClassEditor] addDisjointWith completed successfully");
              break;
          }
        } catch (mutationError) {
          console.error("[ClassEditor] Mutation failed:", mutationError);
          throw mutationError;
        }
      } else {
        if (!definition.trim()) {
          notificationService.warning("Empty Expression", "Cannot add axiom: expression is empty.");
          return;
        }
        const parsed = parseManchesterExpression(definition);
        if (parsed && type !== "DisjointWith") {
          console.log(`[ClassEditor] Parsed ${parsed.expressionType} expression:`, parsed.iris);
          if (parsed.expressionType === "intersection") {
            await ontologyMutationService.addIntersection(projectId, item.id, parsed.iris, type as "EquivalentTo" | "SubClassOf");
          } else {
            await ontologyMutationService.addUnion(projectId, item.id, parsed.iris, type as "EquivalentTo" | "SubClassOf");
          }
        } else {
          await expressionService.addClassExpressionAxiom(
            projectId,
            item.id,
            type,
            definition,
            user?.email,
            user?.username || user?.email,
          );
        }
      }
      console.log("[ClassEditor] Waiting 800ms for GraphDB to index...");
      await new Promise((resolve) => setTimeout(resolve, 800));
      console.log("[ClassEditor] Reloading class details after axiom addition");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] Class details reloaded after axiom addition");
    } catch (error) {
      console.error("[ClassEditor] Failed to add axiom:", error);
      notificationService.error("Add Axiom Failed", `Failed to add axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleDeleteAxiom = async (type: AxiomType, id: string, classIriOverride?: string, axiomOverride?: Axiom) => {
    if (isSavingAxiomRef.current) return;
    const ownerIri = classIriOverride || item.id;
    console.log("[ClassEditor] handleDeleteAxiom called:", { type, id, classIri: ownerIri });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      let axiomArrays: { EquivalentTo?: Axiom[]; SubClassOf?: Axiom[]; DisjointWith?: Axiom[] } = {
        EquivalentTo: classDetails?.equivalentClassesAxioms || item.equivalentClassesAxioms,
        SubClassOf: classDetails?.subClassOfAxioms || item.subClassOfAxioms,
        DisjointWith: classDetails?.disjointClassesAxioms || item.disjointClassesAxioms,
      };
      const axiom = axiomOverride || axiomArrays[type]?.find((a) => a.id === id);
      console.log("[ClassEditor] Found axiom:", axiom);

      const isRestriction = axiom?.isRestriction === true || axiom?.isRestriction === "true";

      if (isRestriction && axiom?.propertyIri && axiom?.restrictionType && axiom?.fillerIri) {
        const axiomType = type === "EquivalentTo" ? "EquivalentTo" : type === "DisjointWith" ? "DisjointWith" : "SubClassOf";

        const isDataProperty =
          axiom.propertyIri === "http://www.w3.org/2002/07/owl#topDataProperty" ||
          dataProperties.some((p) => p.id === axiom.propertyIri);

        console.log("[ClassEditor] Deleting restriction:", {
          classIri: ownerIri,
          axiomType,
          propertyIri: axiom.propertyIri,
          restrictionType: axiom.restrictionType,
          fillerIri: axiom.fillerIri,
          isDataProperty,
        });

        const restrictionCardinality =
          axiom.cardinality != null
            ? typeof axiom.cardinality === "string"
              ? parseInt(axiom.cardinality, 10)
              : axiom.cardinality
            : undefined;

        if (isDataProperty) {
          await ontologyMutationService.deleteDataRestriction(
            projectId,
            ownerIri,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as "some" | "only" | "min" | "max" | "exactly",
            axiom.fillerIri,
            restrictionCardinality,
          );
        } else {
          await ontologyMutationService.deleteObjectRestriction(
            projectId,
            ownerIri,
            axiomType,
            axiom.propertyIri,
            axiom.restrictionType as "some" | "only" | "min" | "max" | "exactly" | "value",
            axiom.fillerIri,
            restrictionCardinality,
          );
        }
        await new Promise((resolve) => setTimeout(resolve, 300));
        await loadClassDetails(undefined, true);
      } else {
        console.log("[ClassEditor] Deleting simple class axiom:", { type, classIri: ownerIri, targetIri: id });

        const definitionText = axiom?.definition;
        switch (type) {
          case "EquivalentTo":
            console.log("[ClassEditor] Calling deleteEquivalentClass");
            await ontologyMutationService.deleteEquivalentClass(
              projectId,
              ownerIri,
              id,
              user?.email,
              user?.username || user?.email,
              definitionText,
            );
            break;
          case "SubClassOf":
            console.log("[ClassEditor] Calling deleteSubClassOf with params:", {
              projectId,
              classIri: ownerIri,
              superClassIri: id,
            });
            await ontologyMutationService.deleteSubClassOf(
              projectId,
              ownerIri,
              id,
              user?.email,
              user?.username || user?.email,
              definitionText,
            );
            console.log("[ClassEditor] deleteSubClassOf completed");
            break;
          case "DisjointWith":
            if (axiom?.isAllDisjointClasses) {
              console.log("[ClassEditor] Calling deleteFromAllDisjointClasses");
              await ontologyMutationService.deleteFromAllDisjointClasses(
                projectId,
                ownerIri,
                id,
                user?.email,
                user?.username || user?.email,
              );
            } else {
              console.log("[ClassEditor] Calling deleteDisjointWith");
              await ontologyMutationService.deleteDisjointWith(
                projectId,
                ownerIri,
                id,
                user?.email,
                user?.username || user?.email,
              );
            }
            break;
        }
        await new Promise((resolve) => setTimeout(resolve, 300));
        console.log("[ClassEditor] Reloading class details after delete");
        await loadClassDetails(undefined, true);
        console.log("[ClassEditor] loadClassDetails completed");
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to delete axiom:", error);
      console.error("[ClassEditor] Delete axiom details:", { type, id, classIri: ownerIri });
      notificationService.error("Delete Failed", `Failed to delete axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleEditAxiom = async (type: AxiomType, oldId: string, newDefinition: string) => {
    if (isSavingAxiom) return;
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      console.log("[ClassEditor] handleEditAxiom called:", { type, oldId, newDefinition });

      const isOldSimpleIRI = oldId.startsWith("http://") || oldId.startsWith("https://") || oldId.startsWith("urn:");
      const isNewSimpleIRI =
        newDefinition.startsWith("http://") || newDefinition.startsWith("https://") || newDefinition.startsWith("urn:");

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
              user?.username || user?.email,
            );
            break;
          case "SubClassOf":
            await ontologyMutationService.updateSubClassOf(
              projectId,
              item.id,
              oldId,
              newDefinition,
              user?.email,
              user?.username || user?.email,
            );
            break;
          case "DisjointWith":
            await ontologyMutationService.updateDisjointWith(
              projectId,
              item.id,
              oldId,
              newDefinition,
              user?.email,
              user?.username || user?.email,
            );
            break;
        }
      } else {
        console.log("[ClassEditor] Using DELETE + ADD approach for complex/mixed expressions");

        if (isOldSimpleIRI) {
          switch (type) {
            case "EquivalentTo":
              await ontologyMutationService.deleteEquivalentClass(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.username || user?.email,
              );
              break;
            case "SubClassOf":
              await ontologyMutationService.deleteSubClassOf(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.username || user?.email,
              );
              break;
            case "DisjointWith":
              await ontologyMutationService.deleteDisjointWith(
                projectId,
                item.id,
                oldId,
                user?.email,
                user?.username || user?.email,
              );
              break;
          }
        }

        await new Promise((resolve) => setTimeout(resolve, 300));

        if (isNewSimpleIRI) {
          switch (type) {
            case "EquivalentTo":
              await ontologyMutationService.addEquivalentClass(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.username || user?.email,
              );
              break;
            case "SubClassOf":
              await ontologyMutationService.addSubClassOf(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.username || user?.email,
              );
              break;
            case "DisjointWith":
              await ontologyMutationService.addDisjointWith(
                projectId,
                item.id,
                newDefinition,
                user?.email,
                user?.username || user?.email,
              );
              break;
          }
        } else {
          const parsed = parseManchesterExpression(newDefinition);
          if (parsed && type !== "DisjointWith") {
            if (parsed.expressionType === "intersection") {
              await ontologyMutationService.addIntersection(projectId, item.id, parsed.iris, type as "EquivalentTo" | "SubClassOf");
            } else {
              await ontologyMutationService.addUnion(projectId, item.id, parsed.iris, type as "EquivalentTo" | "SubClassOf");
            }
          } else {
            await expressionService.addClassExpressionAxiom(
              projectId, item.id, type, newDefinition,
              user?.email, user?.username || user?.email,
            );
          }
        }
      }

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after edit");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] Edit completed successfully");
    } catch (error) {
      console.error("[ClassEditor] Failed to edit axiom:", error);
      notificationService.error("Edit Failed", `Failed to edit axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleDisjointWithConfirm = async (nodes: TreeNode[]) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleDisjointWithConfirm called:", {
      nodes: nodes.map((n) => ({ id: n.id, label: n.label })),
      isEditing: !!editingDisjointWithId,
    });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      const classIris = nodes.map((n) => n.id);

      if (classIris.length < 1) {
        console.warn("[ClassEditor] Please select at least 1 class");
        notificationService.warning("Selection Required", "Please select at least 1 class.");
        return;
      }

      if (editingDisjointWithId) {
        console.log("[ClassEditor] Editing disjoint with - deleting old:", editingDisjointWithId);
        await ontologyMutationService.deleteDisjointWith(projectId, item.id, editingDisjointWithId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      for (const targetIri of classIris) {
        console.log("[ClassEditor] Adding disjoint with:", { classIri: item.id, targetIri });
        await ontologyMutationService.addDisjointWith(projectId, item.id, targetIri, user?.email, user?.username || user?.email);
      }

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding disjoint with");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] loadClassDetails completed after disjoint with");
    } catch (error) {
      console.error("[ClassEditor] Failed to add disjoint with:", error);
      notificationService.error("Add Failed", `Failed to add disjoint with: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
      setIsDisjointWithOpen(false);
      setEditingDisjointWithId(undefined);
      setEditingDisjointWithTarget(undefined);
    }
  };

  const handleEditDisjointWith = (axiomId: string) => {
    console.log("[ClassEditor] handleEditDisjointWith called:", { classIri: item.id, axiomId });
    const axiom =
      classDetails?.disjointClassesAxioms?.find((a: Axiom) => a.id === axiomId) ||
      item.disjointClassesAxioms?.find((a: Axiom) => a.id === axiomId);

    if (!axiom) {
      console.error("[ClassEditor] Axiom not found for editing:", axiomId);
      return;
    }

    const isSimpleIri = axiomId.startsWith("http://") || axiomId.startsWith("https://") || axiomId.startsWith("urn:");
    const isRestriction = axiom.isRestriction === true || axiom.isRestriction === "true";

    if (isRestriction && axiom.propertyIri) {
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
      setEditingDisjointWithId(axiomId);
      setEditingDisjointWithTarget(axiomId);
      setIsDisjointWithOpen(true);
    } else {
      openEditor("DisjointWith", "Disjoint Class Expression", axiom.definition, axiomId, "classExpression");
    }
  };

  const handleDisjointUnionConfirm = async (nodes: TreeNode[]) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleDisjointUnionConfirm called:", {
      nodes: nodes.map((n) => ({ id: n.id, label: n.label })),
      isEditing: !!editingDisjointUnionId,
    });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      const memberIris = nodes.map((n) => n.id);

      if (memberIris.length < 2) {
        console.warn("[ClassEditor] Disjoint Union requires at least 2 classes");
        notificationService.warning("Selection Required", "Please select at least 2 classes for the disjoint union.");
        return;
      }

      if (editingDisjointUnionId) {
        console.log("[ClassEditor] Editing disjoint union - deleting old:", editingDisjointUnionId);
        await ontologyMutationService.deleteDisjointUnion(projectId, item.id, editingDisjointUnionId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      console.log("[ClassEditor] Adding disjoint union:", { classIri: item.id, memberIris });
      await ontologyMutationService.addDisjointUnion(projectId, item.id, memberIris);
      console.log("[ClassEditor] addDisjointUnion completed");

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding disjoint union");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] loadClassDetails completed after disjoint union");
    } catch (error) {
      console.error("[ClassEditor] Failed to add disjoint union:", error);
      notificationService.error("Add Failed", `Failed to add disjoint union: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
      setIsDisjointUnionOpen(false);
      setEditingDisjointUnionId(undefined);
      setEditingDisjointUnionMembers([]);
    }
  };

  const handleEditDisjointUnion = async (listNodeId: string) => {
    console.log("[ClassEditor] handleEditDisjointUnion called:", { classIri: item.id, listNodeId });
    const disjointUnionAxiom = (classDetails?.disjointUnionAxioms || item.disjointUnionAxioms)?.find(
      (ax: Axiom) => ax.id === listNodeId,
    );
    if (!disjointUnionAxiom) {
      console.error("[ClassEditor] Disjoint union axiom not found:", listNodeId);
      return;
    }

    const members = disjointUnionAxiom.members || [];
    console.log("[ClassEditor] Found disjoint union members:", members);

    setEditingDisjointUnionId(listNodeId);
    setEditingDisjointUnionMembers(members);
    setIsDisjointUnionOpen(true);
  };

  const handleDeleteDisjointUnion = async (listNodeId: string) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleDeleteDisjointUnion called:", { classIri: item.id, listNodeId });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      await ontologyMutationService.deleteDisjointUnion(projectId, item.id, listNodeId);
      console.log("[ClassEditor] deleteDisjointUnion completed");

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after deleting disjoint union");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] loadClassDetails completed after delete disjoint union");
    } catch (error) {
      console.error("[ClassEditor] Failed to delete disjoint union:", error);
      notificationService.error("Delete Failed", `Failed to delete disjoint union: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleEditHasKey = async (listNodeId: string) => {
    console.log("[ClassEditor] handleEditHasKey called:", { classIri: item.id, listNodeId });
    const hasKeyAxiom = (classDetails?.hasKeyAxioms || item.hasKeyAxioms)?.find((ax: Axiom) => ax.id === listNodeId);
    if (!hasKeyAxiom) {
      console.error("[ClassEditor] Has key axiom not found:", listNodeId);
      return;
    }

    const props = hasKeyAxiom.properties || [];
    console.log("[ClassEditor] Found has key properties:", props);

    setEditingHasKeyId(listNodeId);
    setEditingHasKeyProperties(props);
    setIsHasKeyOpen(true);
  };

  const handleDeleteHasKey = async (listNodeId: string) => {
    if (isSavingAxiomRef.current) return;
    console.log("[ClassEditor] handleDeleteHasKey called:", { classIri: item.id, listNodeId });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      await ontologyMutationService.deleteHasKey(projectId, item.id, listNodeId);
      console.log("[ClassEditor] deleteHasKey completed");

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after deleting has key");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] loadClassDetails completed after delete has key");
    } catch (error) {
      console.error("[ClassEditor] Failed to delete has key:", error);
      notificationService.error("Delete Failed", `Failed to delete has key: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleAddHasKey = async (propertyIris: string[]) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleAddHasKey called:", {
      classIri: item.id,
      propertyIris,
      isEditing: !!editingHasKeyId,
    });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      if (propertyIris.length < 1) {
        console.warn("[ClassEditor] HasKey requires at least 1 property");
        notificationService.warning("Selection Required", "Please select at least 1 property for the key.");
        return;
      }

      if (editingHasKeyId) {
        console.log("[ClassEditor] Editing has key - deleting old:", editingHasKeyId);
        await ontologyMutationService.deleteHasKey(projectId, item.id, editingHasKeyId);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      console.log("[ClassEditor] Adding has key:", { classIri: item.id, propertyIris });
      await ontologyMutationService.addHasKey(projectId, item.id, propertyIris);
      console.log("[ClassEditor] addHasKey completed");

      await new Promise((resolve) => setTimeout(resolve, 300));
      console.log("[ClassEditor] Reloading class details after adding has key");
      await loadClassDetails(undefined, true);
      console.log("[ClassEditor] loadClassDetails completed after has key");
    } catch (error) {
      console.error("[ClassEditor] Failed to add has key:", error);
      notificationService.error("Add Failed", `Failed to add has key: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
      setIsHasKeyOpen(false);
      setEditingHasKeyId(undefined);
      setEditingHasKeyProperties([]);
    }
  };

  const handleAddInstance = async (name: string) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleAddInstance called:", { name, classIri: item.id });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      if (onAddIndividual) {
        await onAddIndividual(name, item.id);
      } else {
        await ontologyMutationService.addIndividual(projectId, name, item.id);
      }

      console.log("[ClassEditor] Reloading instances after adding");
      for (let attempt = 1; attempt <= 6; attempt++) {
        if (isDesktop()) {
          await waitForDesktopOwlApiReady(projectId);
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
        const list = await loadInstances();
        if (list.some((inst: any) => (inst?.label || inst?.id || "") === name || inst?.id?.endsWith(`#${name.replace(/\s+/g, "_")}`))) {
          break;
        }
        if (attempt === 6) {
          console.warn("[ClassEditor] New instance still not visible after retries");
        }
      }

      if (onRefreshIndividuals) {
        onRefreshIndividuals();
      }
    } catch (error) {
      console.error("[ClassEditor] Failed to add instance:", error);
      notificationService.error("Add Failed", `Failed to add instance: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleDeleteInstance = async (individualIri: string) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleDeleteInstance called:", { individualIri, classIri: item.id });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      await ontologyMutationService.removeClassAssertion(projectId, individualIri, item.id);
      for (let attempt = 1; attempt <= 6; attempt++) {
        if (isDesktop()) {
          await waitForDesktopOwlApiReady(projectId);
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
        const list = await loadInstances();
        if (!list.some((inst: any) => inst?.id === individualIri || inst?.iri === individualIri)) {
          break;
        }
        if (attempt === 6) {
          console.warn("[ClassEditor] Deleted instance still visible after retries");
        }
      }
      if (onRefreshIndividuals) onRefreshIndividuals();
    } catch (error) {
      console.error("[ClassEditor] Failed to delete instance:", error);
      notificationService.error("Delete Failed", `Failed to delete instance: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleEditInstance = (instanceId: string) => {
    console.log("[ClassEditor] handleEditInstance called:", { instanceId, classIri: item.id });
    setEditingInstanceId(instanceId);
    setIsInstancesOpen(true);
  };

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

  const handleDeleteGCA = async (axiomId: string, ancestorIri?: string) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] Deleting GCA:", axiomId, ancestorIri ? `(ancestor: ${ancestorIri})` : '');
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      const axiomList = ancestorIri
        ? classDetails?.anonymousAncestorAxioms
        : classDetails?.generalClassAxioms;
      const definition = (axiomList || []).find((a: Axiom) => a.id === axiomId)?.definition;
      await ontologyMutationService.deleteAxiom(projectId, axiomId, ancestorIri || item.id, definition);
      await new Promise((resolve) => setTimeout(resolve, 500));
      await loadClassDetails(undefined, true);
    } catch (error) {
      console.error("[ClassEditor] Failed to delete GCA:", error);
      notificationService.error("Delete Failed", `Failed to delete general class axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
    }
  };

  const handleGCAConfirm = async (subExpr: string, superExpr: string) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] GCA confirm:", { subExpr, superExpr, editing: editingGCAId });
    setIsSavingAxiom(true);
    isSavingAxiomRef.current = true;
    try {
      if (editingGCAId) {
        const existingDefinition = classDetails?.generalClassAxioms?.find((a: Axiom) => a.id === editingGCAId)?.definition;
        await ontologyMutationService.deleteAxiom(projectId, editingGCAId, item.id, existingDefinition);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }
      const superIri = findClassIriByLabelOrIri(superExpr, classHierarchy) || superExpr;
      const parsedSub = parseManchesterExpression(subExpr);
      if (parsedSub?.expressionType === "intersection") {
        await ontologyMutationService.addGCAIntersection(projectId, superIri, parsedSub.iris);
      } else if (parsedSub?.expressionType === "union") {
        await ontologyMutationService.addGCAUnion(projectId, superIri, parsedSub.iris);
      } else {
        const gcaActorId = readerUserId(user);
        const gcaDraft = ontologyMutationService.resolveUseDraft();
        await apiClient.post(
          `/api/ontology/${encodeURIComponent(projectId)}/expression/add-gca` +
            `?userId=${encodeURIComponent(gcaActorId || "")}&draft=${gcaDraft}`,
          {
            subClassExpression: subExpr.trim(),
            superClassExpression: superIri,
          },
        );
      }

      await new Promise((resolve) => setTimeout(resolve, 500));
      await loadClassDetails(undefined, true);
      window.dispatchEvent(new CustomEvent("gca:changed", { detail: { projectId } }));
    } catch (error) {
      console.error("[ClassEditor] Failed to save GCA:", error);
      notificationService.error("Save Failed", `Failed to save general class axiom: ${error instanceof Error ? error.message : "Unknown error"}`);
      throw error;
    } finally {
      setIsSavingAxiom(false);
      isSavingAxiomRef.current = false;
      setIsGCAEditorOpen(false);
      setEditingGCAId(undefined);
    }
  };

  const handleInstancesConfirm = async (selectedIndividuals: Individual[]) => {
    if (isSavingAxiom) return;
    console.log("[ClassEditor] handleInstancesConfirm called:", {
      selectedCount: selectedIndividuals.length,
      classIri: item.id,
      isEditing: !!editingInstanceId,
    });
    setIsSavingAxiom(true);
    try {
      if (editingInstanceId) {
        await ontologyMutationService.removeClassAssertion(projectId, editingInstanceId, item.id);
        await new Promise((resolve) => setTimeout(resolve, 300));
      }

      const addedIds: string[] = [];
      for (const individual of selectedIndividuals) {
        if (!classInstances.some((i) => i.id === individual.id)) {
          await ontologyMutationService.addClassAssertion(projectId, individual.id, item.id);
          addedIds.push(individual.id);
        }
      }

      for (let attempt = 1; attempt <= 6; attempt++) {
        if (isDesktop()) {
          await waitForDesktopOwlApiReady(projectId);
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
        const list = await loadInstances();
        if (addedIds.every((id) => list.some((inst: any) => inst?.id === id || inst?.iri === id))) {
          break;
        }
        if (attempt === 6) {
          console.warn("[ClassEditor] Newly added instances still not fully visible after retries");
        }
      }

      if (onRefreshIndividuals) onRefreshIndividuals();
    } catch (error) {
      console.error("[ClassEditor] Failed to add instances:", error);
      notificationService.error("Add Failed", `Failed to add instances: ${error instanceof Error ? error.message : "Unknown error"}`);
    } finally {
      setIsSavingAxiom(false);
      setIsInstancesOpen(false);
      setEditingInstanceId(undefined);
    }
  };

  const annotationCount = Object.keys(item.annotations || {}).length;
  const displayAnnotations = item.annotations || {};
  const directParents = parentResult?.key === descriptionKey ? parentResult.parents : undefined;

  const editingGCADefinition = editingGCAId
    ? (classDetails?.generalClassAxioms?.find((a: Axiom) => a.id === editingGCAId)?.definition ?? '')
    : '';
  const gcaSubClassOfParts = editingGCADefinition.match(/^(.+?)\s+SubClassOf\s+(.+)$/i);
  const gcaInitialSubClass = gcaSubClassOfParts ? gcaSubClassOfParts[1] : editingGCADefinition;
  const gcaInitialSuperClass = gcaSubClassOfParts ? gcaSubClassOfParts[2] : (editingGCAId ? '' : item.label);
  const gcaAvailableClasses = [...allClassesLookup.entries()].map(([label, id]) => ({ id, label }));

  return (
    <div className="flex flex-col h-full bg-white">
      {activeTab === "annotations" && loadingAnnotations && (
        <div className="sticky top-0 left-0 right-0 bg-blue-50 border-b border-blue-200 text-xs text-blue-800 px-3 py-1.5 z-20 flex items-center justify-center shadow-sm pointer-events-none">
          <div className="animate-spin mr-2 h-3 w-3 border-2 border-blue-600 border-t-transparent rounded-full"></div>
          Loading annotations for <span className="font-semibold mx-1">{item.label || "class"}</span>…
        </div>
      )}
      <div className="bg-gray-100 border-b border-gray-200 p-3 flex items-center justify-between">
        <div className="flex items-center gap-2 overflow-hidden">
          <div className="bg-yellow-200 text-yellow-800 p-1 rounded text-xs font-bold">C</div>
          <div className="flex flex-col min-w-0">
            <span className="font-bold text-sm truncate">{item.label}</span>
            <span className="text-xs text-gray-500 truncate font-mono">{item.id}</span>
          </div>
        </div>
        <button
          onClick={openIRIEditor}
          className="p-1.5 hover:bg-gray-200 rounded text-gray-600 hover:text-purple-600 flex-shrink-0"
          title="Edit IRI and Label"
        >
          <Edit3 size={16} />
        </button>
      </div>
      <CollaboratorPresenceBar entityId={item.id} />

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

      <div className="flex-1 overflow-y-auto bg-gray-50 p-3 min-h-0">
        <div className={activeTab !== "usage" ? "hidden" : ""}>
          <ClassUsageTab key={entityScopeKey} classIri={item.id} projectId={projectId} label={item.label}
            active={activeTab === "usage"} />
        </div>

        {activeTab === "annotations" && (
          <div className="space-y-0">
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
            <div className="bg-stone-100 border-b border-stone-300 px-3 py-1.5">
              <span className="text-xs font-medium text-stone-700">Description: {item.label}</span>
            </div>

            <section aria-label="Class annotations" className="bg-white border-x border-b border-gray-200 p-3">
              <h3 className="text-xs font-semibold text-stone-700">Annotations</h3>
              {loadingAnnotations && annotationCount === 0 ? (
                <p role="status" className="mt-1 text-xs text-gray-500">Loading annotations…</p>
              ) : (
                <AnnotationsDisplay annotations={displayAnnotations} onDelete={onDeleteAnnotation}
                  onEdit={onEditAnnotation} isViewOnly={isViewOnly} onViewOnlyAction={onViewOnlyAction} />
              )}
            </section>

            {!axiomsLoaded && (
              <section aria-label="Direct parents" className="bg-white border-x border-b border-gray-200 p-3">
                <h3 className="text-xs font-semibold text-stone-700">Direct parents</h3>
                {directParents?.length ? (
                  <ul className="mt-1 space-y-1">
                    {directParents.map(parent => (
                      <li key={parent.iri}>
                        <button type="button" onClick={() => handleNavigate(parent.iri, 'class')}
                          className="text-left text-xs text-purple-700 hover:underline" title={parent.iri}>
                          {parent.label}
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1 text-xs text-gray-500">
                    {directParents === undefined ? 'Loading direct parents…'
                      : directParents === null ? 'Direct parents unavailable.' : 'No named direct parents.'}
                  </p>
                )}
              </section>
            )}

            {!axiomsLoaded && !loadingDetails && (
              <div className="flex flex-col items-center px-3 py-3 text-center bg-white border border-t-0 border-gray-200 rounded-b-sm">
                {descriptionTimedOut ? (
                  <>
                    <div className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 mb-4 max-w-sm">
                      Could not load the remaining axioms and restrictions.
                    </div>
                    <button
                      type="button"
                      onClick={() => void loadDescription()}
                      data-testid="load-description-btn"
                      className="px-4 py-2 text-sm rounded bg-amber-600 text-white hover:bg-amber-700 shadow-sm"
                    >
                      Retry
                    </button>
                  </>
                ) : (
                  <div className="flex items-center gap-2 text-sm text-gray-500">
                    <div className="animate-spin h-4 w-4 border-2 border-purple-600 border-t-transparent rounded-full" />
                    <span>Loading axioms and restrictions…</span>
                  </div>
                )}
              </div>
            )}

            {loadingDetails && !axiomsLoaded && (
              <div className="flex flex-col items-center py-3 bg-white border border-t-0 border-gray-200 rounded-b-sm text-sm text-gray-500 gap-2">
                <div className="flex items-center gap-2">
                  <div className="animate-spin h-4 w-4 border-2 border-purple-600 border-t-transparent rounded-full" />
                  <span>
                    Loading axioms and restrictions
                    {loadingDetailsElapsed > 0 && (
                      <span className="ml-1 text-gray-400">({loadingDetailsElapsed}s)</span>
                    )}
                    …
                  </span>
                </div>
                {loadingDetailsElapsed >= 15 && (
                  <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-4 py-2 max-w-sm text-center">
                    Still running — querying a large ontology via SPARQL. First load can take up to 90 seconds; subsequent loads are cached.
                  </p>
                )}
              </div>
            )}

            {isSavingAxiom && (
              <div className="flex items-center gap-2 px-4 py-2 text-sm text-purple-700 bg-purple-50 border border-purple-200 rounded">
                <div className="animate-spin h-3.5 w-3.5 border-2 border-purple-600 border-t-transparent rounded-full flex-shrink-0" />
                Saving…
              </div>
            )}

            {axiomsLoaded && (
            <div className={`bg-white border border-t-0 border-gray-200 rounded-b-sm p-3 space-y-4 transition-opacity duration-150 ${isSavingAxiom ? "opacity-50 pointer-events-none" : ""}`}>
              <AxiomSubsection
                title="SubClass Of"
                viewMode={viewMode}
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
                projectId={projectId}
                parentEntityIri={item.id}
                conflictIris={conflictIris}
              />

              <AxiomSubsection
                title="Equivalent To"
                viewMode={viewMode}
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
                projectId={projectId}
                parentEntityIri={item.id}
                conflictIris={conflictIris}
              />

              <AxiomSubsection
                title="General class axioms"
                axioms={classDetails?.generalClassAxioms || []}
                onAdd={(def) => handleGCAConfirm(def, item.id)}
                onEdit={(id, newDef) => handleGCAConfirm(newDef, item.id)}
                onDelete={(id) => handleDeleteGCA(id)}
                onAddClick={handleAddGCA}
                onEditClick={(axiom) => handleEditGCA(axiom.id)}
                emptyMessage=""
                themeColor="yellow"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                projectId={projectId}
                parentEntityIri={item.id}
                resolveGCAAxiom={resolveGCAAxiom}
                onNavigate={handleNavigate}
                conflictIris={conflictIris}
              />

              <AxiomSubsection
                title="SubClass Of (Anonymous Ancestor)"
                axioms={(classDetails?.anonymousAncestorAxioms || []).filter(axiom =>
                  (axiom as Axiom & { ancestorIri?: string }).ancestorIri !== item.id
                  || !(classDetails?.subClassOfAxioms || item.subClassOfAxioms || []).some(direct => direct.id === axiom.id)
                )}
                onAdd={() => {}}
                onDelete={(id) => {
                  const anc = (classDetails?.anonymousAncestorAxioms || []).find((a: any) => a.id === id);
                  const isRestriction = anc && (anc.isRestriction === true || (anc as any).isRestriction === "true")
                      && (anc as any).propertyIri && (anc as any).restrictionType && (anc as any).fillerIri;
                  if (isRestriction) {
                    handleDeleteAxiom("SubClassOf", id, (anc as any).ancestorIri, anc as Axiom);
                    return;
                  }
                  handleDeleteGCA(id, (anc as any)?.ancestorIri);
                }}
                onEditClick={(axiom) => {
                  const target = axiom.id || '';
                  if (target.startsWith('http://') || target.startsWith('https://') || target.startsWith('urn:')) {
                    handleNavigate(target, 'class');
                    return;
                  }
                  const ancestorIri = (axiom as { ancestorIri?: string }).ancestorIri;
                  const manchester =
                    (axiom as { manchester?: string }).manchester ||
                    (axiom.definition && axiom.definition !== 'Anonymous superclass'
                      ? axiom.definition
                      : undefined);
                  if (manchester && ancestorIri) {
                    openEditor(
                      'SubClassOf',
                      'Anonymous Ancestor Expression',
                      manchester,
                      target,
                      'classExpression',
                      undefined,
                      ancestorIri,
                    );
                  } else if (target.startsWith('_:') || target.includes('genid')) {
                    handleEditGCA(target);
                  }
                }}
                onNavigate={handleNavigate}
                themeColor="yellow"
                isViewOnly={isViewOnly}
                onViewOnlyAction={onViewOnlyAction}
                projectId={projectId}
                parentEntityIri={item.id}
                renderRowFooter={(axiom) => {
                  const labelFor = buildLabelLookup(classDetails?.subClassOfAxioms, classDetails?.anonymousAncestorAxioms);
                  const info = describeInheritance(axiom as unknown as AncestorEntry, item.id, classDetails?.label || item.label, labelFor);
                  if (!info) return null;
                  return (
                    <div
                      className="px-3 pb-1.5 text-[11px]"
                      style={{ color: 'var(--text-tertiary)' }}
                      data-testid="ancestor-origin"
                    >
                      {info.kind === 'asserted' ? (
                        <span>Stated on this class</span>
                      ) : (
                        <span>
                          {info.fromDefinition ? 'From the definition of ' : 'Inherited from '}
                          <button
                            type="button"
                            className="underline"
                            style={{ color: 'var(--accent)' }}
                            onClick={() => info.fromIri && handleNavigate(info.fromIri, 'class')}
                          >
                            {info.fromLabel}
                          </button>
                          {info.note ? <span className="block">{info.note}</span> : null}
                        </span>
                      )}
                    </div>
                  );
                }}
              />

              <div className="relative">
                <AxiomSubsection
                  title="Instances"
                  viewMode={viewMode}
                  axioms={classInstances
                    .filter((instance) => !(instance as { isInferred?: boolean }).isInferred)
                    .map((instance) => ({
                      id: instance.id,
                      type: "Instance",
                      definition: instance.label,
                    }))}
                  inferredAxioms={classInstances
                    .filter((instance) => (instance as { isInferred?: boolean }).isInferred)
                    .map((instance) => ({
                      id: instance.id,
                      type: "Instance",
                      definition: instance.label,
                    }))}
                  onAdd={() => {}}
                  onEdit={(id, newDef) => { if (!isSavingAxiom) handleEditInstance(id); }}
                  onDelete={(id) => { if (!isSavingAxiom) handleDeleteInstance(id); }}
                  onAddClick={() => { if (!isSavingAxiom) { setEditingInstanceId(undefined); setIsInstancesOpen(true); } }}
                  onEditClick={(axiom) => { if (!isSavingAxiom) handleEditInstance(axiom.id); }}
                  emptyMessage=""
                  themeColor="yellow"
                  isViewOnly={isViewOnly}
                  onViewOnlyAction={onViewOnlyAction}
                  projectId={projectId}
                  parentEntityIri={item.id}
                  onNavigate={handleNavigate}
                />
              </div>

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
                projectId={projectId}
                parentEntityIri={item.id}
                onNavigate={handleNavigate}
              />

              <AxiomSubsection
                title="Disjoint With"
                viewMode={viewMode}
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
                projectId={projectId}
                parentEntityIri={item.id}
                conflictIris={conflictIris}
              />

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
                projectId={projectId}
                parentEntityIri={item.id}
                onNavigate={handleNavigate}
                conflictIris={conflictIris}
              />
            </div>
            )}
          </div>
        )}
      </div>

      <ClassExpressionDialog
        isOpen={isEditorOpen}
        onClose={() => {
          setIsEditorOpen(false);
          setEditorExistingValue(undefined);
          setEditorExistingId(undefined);
          setEditorInitialClassIri(undefined);
          setEditorInitialTab(undefined);
          setEditorInitialRestrictionData(undefined);
          setEditorAllowedTabs(undefined);
        }}
        onConfirm={handleEditorConfirm}
        axiomType={editorType === 'EquivalentTo' ? 'EquivalentTo' : editorType === 'DisjointWith' ? 'DisjointWith' : 'SubClassOf'}
        title={editorTitle}
        initialValue={editorExistingValue}
        initialClassIri={editorInitialClassIri}
        initialTab={editorInitialTab}
        initialRestrictionData={editorInitialRestrictionData}
        allowedTabs={editorAllowedTabs}
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
        onDeleteProperty={() => notificationService.info("Not available", "To delete a property, select it in the main property tree first.")}
        onRefreshClasses={onRefreshClasses}
        metadata={metadata}
      />

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
        onDeleteClass={() => notificationService.info("Not available", "To delete a class, select it in the main class tree first.")}
      />

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
        onDeleteClass={() => notificationService.info("Not available", "To delete a class, select it in the main class tree first.")}
      />

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
        onDeleteProperty={() => notificationService.info("Not available", "To delete a property, select it in the main property tree first.")}
      />

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

      <GCIEditorDialog
        isOpen={isGCAEditorOpen}
        onClose={() => {
          setIsGCAEditorOpen(false);
          setEditingGCAId(undefined);
        }}
        onSave={handleGCAConfirm}
        editMode={!!editingGCAId}
        projectId={projectId}
        availableClasses={gcaAvailableClasses}
        initialSubClass={gcaInitialSubClass}
        initialSuperClass={gcaInitialSuperClass}
      />

      <IRIEditorDialog
        isOpen={isIRIEditorOpen}
        onClose={() => setIsIRIEditorOpen(false)}
        currentIRI={item.id}
        currentLabel={item.label}
        entityType="Class"
        onSave={handleSaveIRI}
        existingIris={existingIris}
      />
    </div>
  );
};

export default ClassEditor;
