// @ts-nocheck
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useState, useEffect, useCallback, useRef } from "react";
import apiClient from "../../../services/apiClient";
import ontologyMutationService from "../../../services/ontologyMutationService";
import { draftTrackingService } from "../../../services/draftTrackingService";
import { notificationService } from "../../../services/notificationService";
import { importStageLabel, sanitizeImportMessage } from "../../../utils/importStatusText";
import { isDesktop, waitForDesktopOwlApiReady, isOwlApiWarmingResponse } from "../../../utils/desktop";
import { syncService } from "../../../services/syncService";
import { pluginLoader } from "../../../services/pluginLoader";
import type { DashboardState } from "./useDashboardState";
import {
  buildHierarchyTree,
  extractResponseData,
  combineReasonerResults,
  findParentNode,
  showNotification,
} from "../dashboardUtils";

function decodeTokenEmail(token: string | null): string | undefined {
  if (!token) return undefined;
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.email || payload.sub;
  } catch {
    return undefined;
  }
}

export function useDashboardInit(state: DashboardState) {
  const {
    user, projectId, setProjectId, metadata, setMetadata,
    classHierarchy, setClassHierarchy, inferredClassHierarchy, setInferredClassHierarchy,
    objectProperties, setObjectProperties, objectPropertyHierarchy, setObjectPropertyHierarchy,
    inferredObjectPropertyHierarchy, setInferredObjectPropertyHierarchy,
    dataProperties, setDataProperties, dataPropertyHierarchy, setDataPropertyHierarchy,
    inferredDataPropertyHierarchy, setInferredDataPropertyHierarchy,
    annotationProperties, setAnnotationProperties,
    inferredAnnotationPropertyHierarchy, setInferredAnnotationPropertyHierarchy,
    individuals, setIndividuals, inferredIndividuals, setInferredIndividuals,
    datatypes, setDatatypes, inferredDatatypes, setInferredDatatypes,
    selectedItem, setSelectedItem, expandedNodes, setExpandedNodes, expandedNodesRef,
    searchQuery, setSearchQuery, entitiesTab, mainTab,
    ontologyImports, setOntologyImports, generalClassAxioms, setGeneralClassAxioms,
    ontologyAnnotations, setOntologyAnnotations, prefixMappings, setPrefixMappings,
    isEditingOntologyId, setIsEditingOntologyId, ontologyIriDraft, setOntologyIriDraft,
    versionIriDraft, setVersionIriDraft,
    isPrefixEditing, setIsPrefixEditing, editingPrefixIndex, setEditingPrefixIndex,
    importDraft, setImportDraft, editingImportIndex, setEditingImportIndex,
    axiomDialogOpen, setAxiomDialogOpen, editingAxiomIndex, setEditingAxiomIndex,
    axiomDraft, setAxiomDraft,
    collaboratorCursors, setCollaboratorCursors,
    isPrefixDialogOpen, setIsPrefixDialogOpen, prefixDialogData, setPrefixDialogData,
    isImportDialogOpen, setIsImportDialogOpen, importDialogData, setImportDialogData,
    showImportClosure, setShowImportClosure, expandedImports, setExpandedImports,
    isOntologyAnnotationDialogOpen, setIsOntologyAnnotationDialogOpen,
    ontologyAnnotationEditTarget, setOntologyAnnotationEditTarget,
    showImportDialog, setShowImportDialog, isInitialLoading, setIsInitialLoading,
    hasFetchedProjects, setHasFetchedProjects, hasUserSelectedFile, setHasUserSelectedFile,
    hasUserSelectedFileRef, webviewReadySentRef, isExpectingFileReady, setIsExpectingFileReady,
    pendingImportProjectIdRef,
    showLoadingChoice, setShowLoadingChoice, loadingProjectName, setLoadingProjectName,
    loadingStatusMessage, setLoadingStatusMessage,
    loadingPromiseRef, userLoadingChoice,
    hasUnsavedChanges, setHasUnsavedChanges, draftCount, setDraftCount,
    isSaving, setIsSaving, syncMode, setSyncMode, draftTimerRef,
    projectImportStatuses, setProjectImportStatuses,
    showQueueStatus, setShowQueueStatus,
    showOpenDialog, setShowOpenDialog, activeOntologySubTab, setActiveOntologySubTab,
    importMode, setImportMode, partitionStrategy, setPartitionStrategy,
    showProjectSelector, setShowProjectSelector,
    isReasonerRunning, isReasonerSynced, setIsReasonerSynced, selectedReasoner,
    reasonerResults, setReasonerResults,
    deleteFileDialog, setDeleteFileDialog,
    isShareDialogOpen, setIsShareDialogOpen, shareFileId, setShareFileId,
    isCurrentFileShared, setIsCurrentFileShared,
    listOfFiles, setListOfFiles, projectFiles, setProjectFiles,
    myFiles, setMyFiles, sharedFiles, setSharedFiles,
    activeFileId, setActiveFileId, activeFileName, setActiveFileName,
    availableProjects, setAvailableProjects,
    pluginLoadingStates, setPluginLoadingStates,
    installedPlugins, setInstalledPlugins,
    visibleMainTabs, setVisibleMainTabs,
    setMainTab,
    duplicatePrompt, setDuplicatePrompt,
    duplicateCopyName, setDuplicateCopyName,
    duplicateCopyError, setDuplicateCopyError,
    duplicateCopySubmitting, setDuplicateCopySubmitting,
    confirmDialog, setConfirmDialog,
    unsavedChangesDialog, setUnsavedChangesDialog,
    selectedClassForIndividuals, setSelectedClassForIndividuals,
    classInstances, setClassInstances, classInstancesLoading, setClassInstancesLoading,
    classInstancesQuery, classInstancesView,
    selectedClassIndividual, setSelectedClassIndividual,
    selectedClassIndividualDetails, setSelectedClassIndividualDetails,
    selectedClassIndividualLoading, setSelectedClassIndividualLoading,
    classInstanceCounts, setClassInstanceCounts,
    classHierarchyRefreshInFlight, lastClassHierarchyRefreshAt,
    hierarchyViewModes, setHierarchyViewModes,
    showToast, encodeProjectId, applyInstanceCountsToTree, fetchProjectFiles,
    collaboration, startReasoner,
    isAddClassDialogOpen, setAddClassDialogOpen, addClassType, setAddClassType,
    classParentLabel, setClassParentLabel,
    isAddPropertyDialogOpen, setAddPropertyDialogOpen, addPropertyType, setAddPropertyType,
    propertyParentLabel, setPropertyParentLabel,
    isCreateIndividualModalOpen, setCreateIndividualModalOpen,
    isAddDatatypeDialogOpen, setAddDatatypeDialogOpen,
    showCollaborationPanel, setShowCollaborationPanel,
    isMergeWizardOpen, setMergeWizardOpen,
    isHistoryPanelOpen, setIsHistoryPanelOpen,
    isReportIssueModalOpen, setIsReportIssueModalOpen,
    isUserGuideOpen, setIsUserGuideOpen,
    isReasonerSettingsOpen, setIsReasonerSettingsOpen,
    selectedReasoner: _selectedReasonerFromState,
    currentHierarchyViewMode,
    collaborationPanelRef,
  } = state;

  const fileLoadingRef = useRef(false);
  const lastLoadedFileRef = useRef<string | null>(null);
  const isMountedRef = useRef(true);

  const handleInstallPlugin = useCallback(async (pluginId: string) => {
    try {
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: true, error: null } }));

      await pluginLoader.installPlugin(pluginId);
      await pluginLoader.loadPlugin(pluginId);

      setInstalledPlugins((prev) => new Set([...prev, pluginId]));
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: false, error: null } }));

      const pluginToTabMap: Record<string, string> = {
        "swrl-editor-plugin": "SWRL",
        "graph-view-plugin": "Graph",
        "fuzzy-ontology-plugin": "Fuzzy",
        "change-assistant-plugin": "Changes",
        "sparql-query-plugin": "SPARQL",
        "reasoner-plugin": "Reasoner",
      };

      const tabId = pluginToTabMap[pluginId];
      if (tabId) {
        setVisibleMainTabs((prev) => {
          if (!prev.includes(tabId)) {
            return [...prev, tabId];
          }
          return prev;
        });
      }

      notificationService.success("Plugin Installed", `${pluginId} has been installed successfully`);
    } catch (error) {
      console.error(`[Dashboard] Failed to install plugin ${pluginId}:`, error);
      setPluginLoadingStates((prev) => ({
        ...prev,
        [pluginId]: { loading: false, error: error instanceof Error ? error.message : "Unknown error" },
      }));
      notificationService.error(
        "Plugin Installation Failed",
        `Failed to install ${pluginId}. ${error instanceof Error ? error.message : "Please check console for details"}`,
      );

      try {
        await pluginLoader.uninstallPlugin(pluginId);
      } catch (uninstallError) {
        console.error("Failed to cleanup after failed installation:", uninstallError);
      }

      throw error;
    }
  }, []);

  const handleRetryLoadPlugin = useCallback(async (pluginId: string) => {
    try {
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: true, error: null } }));

      const component = await pluginLoader.loadPlugin(pluginId);

      if (component) {
        setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: false, error: null } }));

        setInstalledPlugins((prev) => new Set([...prev]));
        notificationService.success("Plugin Loaded", `${pluginId} has been loaded successfully`);
      } else {
        throw new Error("Failed to load plugin component");
      }
    } catch (error) {
      console.error(`[Dashboard] Failed to load plugin ${pluginId}:`, error);
      setPluginLoadingStates((prev) => ({
        ...prev,
        [pluginId]: { loading: false, error: error instanceof Error ? error.message : "Unknown error" },
      }));
      notificationService.error(
        "Plugin Load Failed",
        `Failed to load ${pluginId}. ${error instanceof Error ? error.message : "Please try again"}`,
      );
    }
  }, []);

  const handleUninstallPlugin = useCallback(async (pluginId: string) => {
    try {
      await pluginLoader.uninstallPlugin(pluginId);

      setInstalledPlugins((prev) => {
        const newSet = new Set(prev);
        newSet.delete(pluginId);
        return newSet;
      });

      const pluginToTabMap: Record<string, string> = {
        "swrl-editor-plugin": "SWRL",
        "graph-view-plugin": "Graph",
        "fuzzy-ontology-plugin": "Fuzzy",
        "change-assistant-plugin": "Changes",
        "sparql-query-plugin": "SPARQL",
        "reasoner-plugin": "Reasoner",
      };

      const tabId = pluginToTabMap[pluginId];
      if (tabId) {
        setVisibleMainTabs((prev) => prev.filter((t) => t !== tabId));

        setMainTab((current) => (current === tabId ? "Entities" : current));
      }

    } catch (error) {
      console.error(`[Dashboard] Failed to uninstall plugin ${pluginId}:`, error);
      throw error;
    }
  }, []);

  const waitForProcessingComplete = useCallback(
    async (currentProjectId: string): Promise<{ ready: boolean; error?: string; status?: string }> => {
      const POLL_INTERVAL_MS = 3000;
      const deadline = Date.now() + 15 * 60 * 1000; // 15-minute hard timeout

      while (true) {
        try {
          const statusRes = await apiClient.get<any>(`/api/ontology/status/${encodeProjectId(currentProjectId)}`);
          const status = statusRes?.data?.status || statusRes?.status;

          if (status === "COMPLETED") {
            setLoadingStatusMessage("");
            return { ready: true, status };
          }

          if (status === "ERROR") {
            console.error("[Dashboard] Project processing failed");
            const errorMessage = statusRes?.data?.errorMessage || statusRes?.data?.error || "Import failed";
            return { ready: false, error: errorMessage, status };
          }

          if (status === "PROCESSING") {
            const stage = statusRes?.data?.stage;
            const progress = statusRes?.data?.progress;
            const label = importStageLabel(stage, statusRes?.data?.statusMessage);
            const progressText = progress != null ? ` (${progress}%)` : "";
            setLoadingStatusMessage(`${label}${progressText}`);
            setIsInitialLoading(true);

            if (Date.now() >= deadline) {
              return {
                ready: false,
                error: "Processing is taking longer than expected. The file is large — please check back in a few minutes.",
                status,
              };
            }
            await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
            continue;
          }

          console.warn("[Dashboard] Unknown status, allowing load attempt:", status);
          return { ready: true, status };
        } catch (error) {
          console.error("[Dashboard] Error checking project status:", error);
          return { ready: true };
        }
      }
    },
    [encodeProjectId, setLoadingStatusMessage, setIsInitialLoading],
  );

  const resolveUserEmail = useCallback(() => {
    if (user?.email) return user.email;
    const token = user?.token || (typeof localStorage !== "undefined" ? localStorage.getItem("authToken") : null);
    return decodeTokenEmail(token);
  }, [user?.email, user?.token]);

  const fetchData = useCallback(
    async (currentProjectId: string, waitForCompletion = false, parentProjectId?: string, forceRefresh = false) => {

      if (!forceRefresh && currentProjectId === projectId && classHierarchy.length > 0 && metadata) {
        setIsInitialLoading(false);
        return null;
      }

      setSelectedItem(null);
      setSearchQuery("");

      if (waitForCompletion) {
        setIsInitialLoading(true);
      }

      const isAdminFlow = !!parentProjectId;

      const fetchDataPerfStart = Date.now();

      if (window.vscode) {
        window.vscode.postMessage({ type: "requestCollaborationStatus" });
      }

      try {

        if (!forceRefresh) {

          const procCheckStart = Date.now();
          const result = await waitForProcessingComplete(currentProjectId);

          if (!result.ready) {
            const errorTitle = result.status === "ERROR" ? "Import Failed" : "Loading Failed";
            const errorMessage = result.error || "Unable to load ontology";

            console.error(`[Dashboard] Cannot load project: ${result.status}`, result.error);
            notificationService.error(errorTitle, errorMessage);
            setIsInitialLoading(false);
            return null;
            return;
          }
        } else {
        }

        const encodedProjectId = encodeURIComponent(currentProjectId);

        const cacheBuster = forceRefresh ? `?_t=${Date.now()}` : "";

        const apiFetchStart = Date.now();
        const metadataRes = await apiClient.get<any>(`/api/ontology/metadata/${encodedProjectId}${cacheBuster}`);
        const [topLevelRes, instanceCountsRes, propertiesRes, individualsRes, annotationPropsRes, datatypesRes] =
          await Promise.all([
            apiClient.get<any>(`/api/ontology/classes/top-level/${encodedProjectId}${cacheBuster}`),
            apiClient
              .get<any>(`/api/ontology/classes/instance-counts/${encodedProjectId}${cacheBuster}`)
              .catch(() => null),
            apiClient.get<any>(`/api/ontology/properties/${encodedProjectId}${cacheBuster}`),
            apiClient.get<any>(`/api/ontology/individuals/${encodedProjectId}${cacheBuster}`),
            apiClient.get<any>(`/api/ontology/annotation-properties/${encodedProjectId}${cacheBuster}`),
            apiClient.get<any>(`/api/ontology/datatypes/${encodedProjectId}${cacheBuster}`),
          ]);

        if (!waitForCompletion) {
          setTimeout(() => {
            setIsInitialLoading(false);
          }, 500);
        }

        const apiFetchDuration = Date.now() - apiFetchStart;

        const metadataData = metadataRes?.data || metadataRes;
        const annotationsData = metadataData?.annotations || [];
        const imports = metadataData?.imports || [];
        const gciAxioms = metadataData?.axioms || [];

        if (metadataData?.filename) {
          setActiveFileName(metadataData.filename);
        }

        const transformedMetadata = {
          ...metadataData,
          annotations: annotationsData,

          classCount: metadataData?.classCount || metadataData?.counts?.classes || 0,
          objectPropertyCount: metadataData?.objectPropertyCount || metadataData?.counts?.objectProperties || 0,
          dataPropertyCount: metadataData?.dataPropertyCount || metadataData?.counts?.dataProperties || 0,
          individualCount: metadataData?.individualCount || metadataData?.counts?.individuals || 0,
          annotationPropertyCount:
            metadataData?.annotationPropertyCount || metadataData?.counts?.annotationProperties || 0,
          prefixes: metadataData?.prefixes || [],
        };
        setMetadata(transformedMetadata);

        const instanceCountsPayload = instanceCountsRes?.data || instanceCountsRes;
        const instanceCountsData = instanceCountsPayload?.data || instanceCountsPayload || {};
        if (instanceCountsData && typeof instanceCountsData === "object") {
          setClassInstanceCounts(instanceCountsData);
        }

        const validImportsData = Array.isArray(imports) ? imports : [];
        setOntologyImports(validImportsData);

        const mappedGciData = Array.isArray(gciAxioms)
          ? gciAxioms.map((axiom: any) => ({
            value: axiom.value,
            subClass: axiom.subClass || "",
            superClass: axiom.superClass || "",

            definition: axiom.subClass || axiom.definition || "",
            superClassIri: axiom.superClass || axiom.superClassIri || "",
            subExpression: axiom.subClass || axiom.subExpression || "",
          }))
          : [];
        setGeneralClassAxioms(mappedGciData);

        const validAnnotations = (Array.isArray(annotationsData) ? annotationsData : []).filter(
          (ann) => ann && (ann.propertyIri || ann.property) && ann.value !== undefined,
        );
        setOntologyAnnotations(validAnnotations);

        const prefixesData = metadataData?.prefixes || {};
        const prefixList = Object.entries(prefixesData).map(([prefix, namespace]) => ({

          prefix: prefix ? (prefix.endsWith(":") ? prefix : `${prefix}:`) : ":",
          namespace: String(namespace),
        }));
        setPrefixMappings(prefixList);

        let classes: any[] = [];

        if (Array.isArray(topLevelRes?.classes)) {
          classes = topLevelRes.classes;
        } else if (Array.isArray(topLevelRes?.data?.classes)) {
          classes = topLevelRes.data.classes;
        } else if (Array.isArray(topLevelRes?.data)) {
          classes = topLevelRes.data;
        } else if (Array.isArray(topLevelRes)) {
          classes = topLevelRes;
        } else {
          console.error("âŒ Could not find classes array in response structure!");
          console.error("Available keys:", Object.keys(topLevelRes || {}));
          if (topLevelRes?.data) {
            console.error("Data keys:", Object.keys(topLevelRes.data || {}));
          }

          if (topLevelRes && typeof topLevelRes === "object") {
            for (const key of Object.keys(topLevelRes)) {
              if (Array.isArray(topLevelRes[key])) {
              }
            }
          }
        }

        const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
          ...c,
          children: [],
          hasChildren: c.hasChildren,
          subClassOfAxioms: [{ id: "sub1", type: "SubClassOf", definition: "Thing" }],
        }));

        const owlThingNode: TreeNode = {
          id: "http://www.w3.org/2002/07/owl#Thing",
          label: "owl:Thing",
          children: topLevelNodes,
          hasChildren: topLevelNodes.length > 0,
          annotations: {},
        };

        const resolvedCounts = instanceCountsData && typeof instanceCountsData === "object" ? instanceCountsData : {};
        const hierarchyWithCounts = applyInstanceCountsToTree([owlThingNode], resolvedCounts);
        const stateHydrationStart = Date.now();
        setClassHierarchy(hierarchyWithCounts);

        const allProps = Array.isArray(propertiesRes?.data)
          ? propertiesRes.data
          : Array.isArray(propertiesRes?.properties)
            ? propertiesRes.properties
            : Array.isArray(propertiesRes)
              ? propertiesRes
              : [];
        const opList = allProps.filter((p: Property) => p.type === "ObjectProperty");
        setObjectProperties(opList);

        const opMap = new Map<string, any>();

        opList.forEach((p: Property) => {
          opMap.set(p.id, { ...p, children: [], hasChildren: false });
        });

        const topObjectProperty: any = {
          id: "http://www.w3.org/2002/07/owl#topObjectProperty",
          label: "owl:topObjectProperty",
          type: "ObjectProperty" as const,
          children: [] as any[],
          hasChildren: false,
          annotations: {},
        };

        opList.forEach((p: Property) => {
          const node = opMap.get(p.id);
          if (p.superProperties && p.superProperties.length > 0) {
            let added = false;
            p.superProperties.forEach((superId) => {
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

        const dpList = allProps.filter((p: Property) => p.type === "DatatypeProperty");
        setDataProperties(dpList);

        const dpMap = new Map<string, any>();
        dpList.forEach((p: Property) => {
          dpMap.set(p.id, { ...p, children: [], hasChildren: false });
        });

        const topDataProperty: any = {
          id: "http://www.w3.org/2002/07/owl#topDataProperty",
          label: "owl:topDataProperty",
          type: "DatatypeProperty",
          children: [] as any[],
          hasChildren: false,
          annotations: {},
        };

        dpList.forEach((p: Property) => {
          const node = dpMap.get(p.id);
          if (p.superProperties && p.superProperties.length > 0) {
            let added = false;
            p.superProperties.forEach((superId) => {
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

        setIndividuals(
          Array.isArray(individualsRes?.data)
            ? individualsRes.data
            : Array.isArray(individualsRes?.individuals)
              ? individualsRes.individuals
              : [],
        );
        setAnnotationProperties(
          Array.isArray(annotationPropsRes?.data)
            ? annotationPropsRes.data
            : Array.isArray(annotationPropsRes?.annotationProperties)
              ? annotationPropsRes.annotationProperties
              : [],
        );
        setDatatypes(
          Array.isArray(datatypesRes?.data)
            ? datatypesRes.data
            : Array.isArray(datatypesRes?.datatypes)
              ? datatypesRes.datatypes
              : [],
        );

        if (!isAdminFlow) {
          try {
            const lists = await fetchProjects();
            if (!lists) {
              console.warn("[Dashboard] ?? No project list available - defaulting to private mode");
              setIsCurrentFileShared(false);
              ontologyMutationService.setRealTimeSync(false);
              setSyncMode("private");
            }

            const myProjectsList = Array.isArray(lists?.myFiles) ? lists.myFiles : [];
            const sharedProjectsList = Array.isArray(lists?.sharedFiles) ? lists.sharedFiles : [];

            const isSharedWithMe = sharedProjectsList.some((f: any) => f.id === currentProjectId);
            const isSharedByMe = myProjectsList.some(
              (f: any) => f.id === currentProjectId && f.sharedWith && f.sharedWith.length > 0,
            );
            const hasProjectMembers = myProjectsList.some(
              (f: any) =>
                f.id === currentProjectId &&
                ((f.memberCount && f.memberCount > 1) || (f.members && f.members.length > 1)),
            );
            const isShared = isSharedWithMe || isSharedByMe || hasProjectMembers;
            setIsCurrentFileShared(isShared);

            ontologyMutationService.setRealTimeSync(isShared);
            setSyncMode(isShared ? "public" : "private");

            if (isShared) {

              const handleDataChanged = async (changedProjectId: string) => {
                notificationService.info("New Changes Available", "Another user saved changes. Refreshing data...");

                await fetchData(changedProjectId, false);
              };

              try {
                const timestampData = await apiClient.get<{ updatedAt: string }>(
                  `/api/ontology/metadata/${currentProjectId}/timestamp`,
                );
                if (timestampData && timestampData.updatedAt) {
                  const currentTimestamp = new Date(timestampData.updatedAt).getTime();
                  syncService.startMonitoring(currentProjectId, handleDataChanged, currentTimestamp);
                }
              } catch (error) {
                console.warn("[Dashboard] Could not start change monitoring:", error);
              }
            } else {
            }
          } catch (fileError) {
            console.error("[Dashboard] âŒ Failed to fetch files:", fileError);
            console.error(
              "[Dashboard] âŒ File error details:",
              fileError instanceof Error ? fileError.message : fileError,
            );
            setListOfFiles([]);
            setMyFiles([]);
            setSharedFiles([]);
          }
        } else {
        }

        syncService.stopMonitoring(currentProjectId);

        if (isAdminFlow && parentProjectId) {
          await fetchProjectFiles(parentProjectId);
        } else {
        }

        notificationService.success(
          "Ontology Loaded",
          `"${currentProjectId}" is ready! Found ${classes.length} classes, ${allProps.length} properties.`,
        );
      } catch (error) {
        console.error("Failed to fetch data:", error);

        notificationService.error("Loading Failed", `Failed to load ontology "${currentProjectId}". Please try again.`);
      } finally {
        setIsInitialLoading(false);
      }
    },
    [waitForProcessingComplete, applyInstanceCountsToTree, user, fetchProjectFiles, resolveUserEmail],
  ); // collaboration removed - was only used for logging, caused infinite re-render

  useEffect(() => {
    if (metadata?.ontologyIRI) {
      setOntologyIriDraft(metadata.ontologyIRI);
    }
    if (metadata?.versionIRI !== undefined) {
      setVersionIriDraft(metadata.versionIRI || "");
    }
  }, [metadata?.ontologyIRI, metadata?.versionIRI]);

  const refreshOntologyAnnotations = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(`/api/ontology/metadata/${encodeProjectId(projectId)}/annotations`);
      const payload = response?.data || response;
      const data = payload?.data || payload || [];
      const validAnnotations = (Array.isArray(data) ? data : [])
        .map((ann) => {
          if (!ann || ann.value === undefined) return null;
          const propertyIri = ann.propertyIri || ann.property;
          if (!propertyIri) return null;
          return { ...ann, propertyIri, property: propertyIri };
        })
        .filter(Boolean);

      setOntologyAnnotations((prev) => {
        if (validAnnotations.length === 0 && prev.length > 0) {
          return prev;
        }
        return validAnnotations;
      });
    } catch (error) {
      console.error("[Dashboard] Failed to refresh ontology annotations:", error);
    }
  };

  const refreshOntologyImports = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(`/api/ontology/metadata/${encodeProjectId(projectId)}/imports`);
      const payload = response?.data || response;
      const data = payload?.data || payload || [];
      const validImports = Array.isArray(data) ? data : [];

      setOntologyImports((prev) => {
        if (validImports.length === 0 && prev.length > 0) {
          return prev;
        }
        return validImports;
      });
    } catch (error) {
      console.error("[Dashboard] Failed to refresh ontology imports:", error);
    }
  };

  const refreshPrefixes = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(`/api/ontology/ontology/prefixes/${encodeProjectId(projectId)}`);
      const payload = response?.data || response;
      const data = payload?.data || payload || {};
      const list = Object.entries(data).map(([prefix, namespace]) => ({

        prefix: prefix ? (prefix.endsWith(":") ? prefix : `${prefix}:`) : ":",
        namespace: String(namespace),
      }));
      setPrefixMappings(list);
    } catch (error) {
      console.error("[Dashboard] Failed to refresh prefixes:", error);
    }
  };

  const handleSaveOntologyId = async () => {
    if (!projectId || !ontologyIriDraft.trim()) return;
    try {
      await apiClient.put(`/api/ontology/ontology/id/${encodeProjectId(projectId)}`, {
        ontologyIRI: ontologyIriDraft.trim(),
        versionIRI: versionIriDraft.trim() || null,
      });
      setIsEditingOntologyId(false);
      await apiClient
        .get(`/api/ontology/metadata/${projectId}`)
        .then((res) => {
          const data = res?.data || res;
          setMetadata({ ...(metadata || {}), ...data });
        })
        .catch(() => { });
    } catch (error) {
      console.error("[Dashboard] Failed to update ontology ID:", error);
      notificationService.error("Update Failed", "Could not update ontology IRI/version.");
    }
  };

  const handleAddOntologyAnnotation = async (
    propertyIri: string,
    value: string,
    datatype?: string,
    language?: string,
  ) => {
    if (!projectId) return;
    try {
      const payload: any = {
        propertyIri,
        value,
      };

      if (language && language.trim()) {
        payload.language = language.trim();
      }
      // Add datatype if provided (and no language)
      else if (datatype && datatype.trim()) {

        if (/^[a-z]{2}(-[A-Z]{2})?$/.test(datatype)) {
          payload.language = datatype;
        } else {

          payload.datatype = datatype;
        }
      }

      await apiClient.post(`/api/ontology/metadata/${projectId}/annotations`, payload);

      const newAnnotation: any = {
        propertyIri,
        value,
        datatype: payload.datatype || datatype,
        language: payload.language || language,
      };
      setOntologyAnnotations((prev) => [...prev, newAnnotation]);

      setTimeout(() => {
        refreshOntologyAnnotations();
      }, 300);
      notificationService.success("Annotation Added", "Ontology annotation added successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to add ontology annotation:", error);
      notificationService.error("Annotation Failed", "Could not add ontology annotation.");
    }
  };

  const handleUpdateOntologyAnnotation = async (
    propertyIri: string,
    oldValue: string,
    newValue: string,
    oldDatatype?: string,
    newDatatype?: string,
    newLanguage?: string,
  ) => {
    if (!projectId) return;
    try {
      const payload: any = {
        propertyIri,
        oldValue,
        newValue,
      };

      if (newLanguage && newLanguage.trim()) {
        payload.language = newLanguage.trim();
      }
      // Add datatype if provided (and no language)
      else if (newDatatype) {
        if (/^[a-z]{2}(-[A-Z]{2})?$/.test(newDatatype)) {
          payload.language = newDatatype;
        } else {
          payload.datatype = newDatatype;
        }
      }

      await apiClient.put(`/api/ontology/metadata/${projectId}/annotations`, payload);

      setOntologyAnnotations((prev) =>
        prev.map((ann) =>
          ann.propertyIri === propertyIri && ann.value === oldValue && ann.datatype === oldDatatype
            ? {
              ...ann,
              value: newValue,
              datatype: payload.datatype || newDatatype,
              language: payload.language || newLanguage,
            }
            : ann,
        ),
      );

      await refreshOntologyAnnotations();
      notificationService.success("Annotation Updated", "Ontology annotation updated successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to update ontology annotation:", error);
      notificationService.error("Annotation Failed", "Could not update ontology annotation.");
    }
  };

  const handleDeleteOntologyAnnotation = async (propertyIri: string, value: string, datatype?: string) => {
    if (!projectId) return;
    try {

      let queryString = `propertyIri=${encodeURIComponent(propertyIri)}&value=${encodeURIComponent(value)}`;

      if (datatype) {

        if (/^[a-z]{2}(-[A-Z]{2})?$/.test(datatype)) {
          queryString += `&language=${encodeURIComponent(datatype)}`;
        } else {

          queryString += `&datatype=${encodeURIComponent(datatype)}`;
        }
      }

      await apiClient.delete(`/api/ontology/metadata/${projectId}/annotations?${queryString}`);

      setOntologyAnnotations((prev) =>
        prev.filter((ann) => !(ann.propertyIri === propertyIri && ann.value === value && ann.datatype === datatype)),
      );

      setTimeout(() => {
        refreshOntologyAnnotations();
      }, 100);

      notificationService.success("Annotation Deleted", "Ontology annotation deleted successfully.");
    } catch (error: any) {
      console.error("[Dashboard] Failed to delete ontology annotation:", error);
      console.error("[Dashboard] Error details:", {
        message: error?.message,
        status: error?.status || error?.response?.status,
        data: error?.data || error?.response?.data,
        code: error?.code,
      });
      const errorMsg =
        error?.data?.message ||
        error?.response?.data?.message ||
        error?.message ||
        "Could not delete ontology annotation.";
      notificationService.error("Annotation Failed", errorMsg);
    }
  };

  const handleAddImport = async () => {
    if (!projectId || !importDraft.trim()) return;
    try {
      await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, {
        importIri: importDraft.trim(),
      });
      setImportDraft("");
      setEditingImportIndex(null);
      await refreshOntologyImports();
    } catch (error) {
      console.error("[Dashboard] Failed to add import:", error);
      notificationService.error("Import Failed", "Could not add import.");
    }
  };

  const handleAddImportDialog = () => {
    setImportDialogData({ iri: "", isEdit: false, originalIri: "" });
    setIsImportDialogOpen(true);
  };

  const handleEditImportDialog = (iri: string) => {
    setImportDialogData({ iri, isEdit: true, originalIri: iri });
    setIsImportDialogOpen(true);
  };

  const handleSaveImport = async (iri: string, isEdit: boolean, originalIri: string) => {
    if (!projectId || !iri.trim()) return;
    try {

      const isUrl = iri.startsWith("http://") || iri.startsWith("https://") || iri.startsWith("ftp://");

      const isLocalFile =
        !isUrl &&
        (/^[A-Za-z]:[\\\/]/.test(iri) || // Windows absolute path (C:\ or C:/)
          iri.startsWith("/") || // Unix absolute path
          iri.startsWith("./") ||
          iri.startsWith("../") || // Relative paths with ./ or ../
          iri.startsWith("file://") || // file:// protocol
          /^[^:\/]+\.(?:owl|rdf|ttl|n3|nt|xml)$/i.test(iri)); // Simple filename like "file.owl"

      let importIriForBackend = iri.trim();

      if (isLocalFile && !iri.startsWith("file://") && !isUrl) {

        let normalizedPath = iri.replace(/\\/g, "/");

        if (/^[A-Za-z]:\//.test(normalizedPath)) {

          importIriForBackend = "file:///" + normalizedPath;
        } else if (normalizedPath.startsWith("/")) {

          importIriForBackend = "file://" + normalizedPath;
        } else if (normalizedPath.startsWith("./") || normalizedPath.startsWith("../")) {

          importIriForBackend = normalizedPath.replace(/^\.\//, "").replace(/^\.\.\//, "");
        } else {

          importIriForBackend = normalizedPath;
        }
      }

      if (isEdit && originalIri !== importIriForBackend) {

        await apiClient.delete(
          `/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(originalIri)}`,
        );
      }

      if (!isEdit || originalIri !== importIriForBackend) {

        if (isEdit && originalIri !== importIriForBackend) {

          setOntologyImports((prev) => {
            const filtered = prev.filter((i) => i !== originalIri);
            return [...filtered, importIriForBackend];
          });
        } else if (!isEdit) {

          setOntologyImports((prev) => [...prev, importIriForBackend]);
        }

        await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, {
          importIri: importIriForBackend,
        });
      }

      const hasActualPath = iri.startsWith("file://") || /^[A-Za-z]:[\\\/]/.test(iri) || iri.startsWith("/");
      if (isLocalFile && hasActualPath && window.vscode) {

        const cleanPath = iri.startsWith("file://") ? iri.replace("file:///", "").replace("file://", "") : iri;
        window.vscode.postMessage({
          type: "importLocalFile",
          filePath: cleanPath,
          currentProjectId: projectId,
        });
      } else if (isLocalFile && !hasActualPath) {
      }

      setIsImportDialogOpen(false);

      setTimeout(() => {
        refreshOntologyImports();
      }, 100);

      notificationService.success(
        isEdit ? "Import Updated" : "Import Added",
        isEdit ? "Import updated successfully." : "Import added successfully.",
      );

      if (isLocalFile) {
        notificationService.info("File Upload", "Local file reference added to imports.");
      }
    } catch (error: any) {
      console.error("[Dashboard] âŒ Failed to save import:", error);
      console.error("[Dashboard] âŒ Error details:", {
        message: error?.message,
        response: error?.response?.data,
        status: error?.response?.status,
        statusText: error?.response?.statusText,
      });
      const errorMsg = error?.response?.data?.message || error?.message || "Could not save import.";
      notificationService.error("Import Failed", errorMsg);
    }
  };

  const handleEditImport = (index: number) => {
    setEditingImportIndex(index);
    setImportDraft(ontologyImports[index]);
  };

  const handleUpdateImport = async (oldIri: string) => {
    if (!projectId || !importDraft.trim()) return;
    try {

      await apiClient.delete(`/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(oldIri)}`);
      await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, {
        importIri: importDraft.trim(),
      });
      setImportDraft("");
      setEditingImportIndex(null);
      await refreshOntologyImports();
    } catch (error) {
      console.error("[Dashboard] Failed to update import:", error);
      notificationService.error("Import Failed", "Could not update import.");
    }
  };

  const handleRemoveImport = async (iri: string) => {
    if (!projectId) return;
    try {
      await apiClient.delete(`/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(iri)}`);

      setOntologyImports((prev) => prev.filter((i) => i !== iri));

      setTimeout(() => {
        refreshOntologyImports();
      }, 100);

      notificationService.success("Import Removed", "Import removed successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to remove import:", error);
      notificationService.error("Import Failed", "Could not remove import.");
    }
  };

  const handleEditPrefix = (index: number) => {
    setEditingPrefixIndex(index);
  };

  const handleAddPrefixDialog = () => {
    setPrefixDialogData({ prefix: "", namespace: "", isEdit: false, originalPrefix: "" });
    setIsPrefixDialogOpen(true);
  };

  const handleEditPrefixDialog = (prefix: string, namespace: string) => {
    setPrefixDialogData({ prefix, namespace, isEdit: true, originalPrefix: prefix });
    setIsPrefixDialogOpen(true);
  };

  const handleSavePrefix = async (prefix: string, namespace: string, isEdit: boolean, originalPrefix: string) => {
    if (!projectId) return;
    try {
      const cleanedPrefix = prefix.endsWith(":") ? prefix.slice(0, -1) : prefix;
      const cleanedOriginal = originalPrefix.endsWith(":") ? originalPrefix.slice(0, -1) : originalPrefix;

      const payload: any = {
        prefix: cleanedPrefix,
        iri: namespace,
      };

      if (isEdit) {
        payload.oldPrefix = cleanedOriginal;
      }

      await apiClient.post(`/api/ontology/metadata/${projectId}/prefixes`, payload);

      await refreshPrefixes();

      notificationService.success(
        isEdit ? "Prefix Updated" : "Prefix Added",
        isEdit ? "Prefix updated successfully." : "Prefix added successfully.",
      );
      setIsPrefixDialogOpen(false);
    } catch (error) {
      console.error("[Dashboard] Failed to save prefix:", error);
      notificationService.error("Prefix Failed", "Could not save prefix.");
    }
  };

  const handleDeletePrefix = async (prefix: string) => {
    if (!projectId) return;
    try {

      const cleanedPrefix = prefix.endsWith(":") ? prefix.slice(0, -1) : prefix;

      await apiClient.delete(
        `/api/ontology/metadata/${projectId}/prefixes?prefix=${encodeURIComponent(cleanedPrefix)}`,
      );

      await refreshPrefixes();

      notificationService.success("Prefix Deleted", "Prefix deleted successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to delete prefix:", error);
      notificationService.error("Prefix Failed", "Could not delete prefix.");
    }
  };

  const handleAddAxiom = async (definition?: string, superClassIri?: string) => {
    if (!projectId) return;

    const axiomDefinition = definition || axiomDraft.definition;
    const axiomSuperClass = superClassIri !== undefined ? superClassIri : axiomDraft.superClassIri;

    if (!axiomDefinition) return;

    try {
      await apiClient.post(`/api/ontology/metadata/${projectId}/gci`, {
        subClass: axiomDefinition,
        superClass: axiomSuperClass || "",
        draft: ontologyMutationService.resolveUseDraft(),
        userId: user?.id,
      });

      const newAxiom = {
        value: `${axiomDefinition} SubClassOf ${axiomSuperClass}`,
        subClass: axiomDefinition,
        superClass: axiomSuperClass || "",
        definition: axiomDefinition,
        superClassIri: axiomSuperClass || "",
        subExpression: axiomDefinition,
      };
      setGeneralClassAxioms([...generalClassAxioms, newAxiom]);

      setAxiomDraft({ definition: "", superClassIri: "" });
      setAxiomDialogOpen(false);
      setEditingAxiomIndex(null);

      setTimeout(() => refreshGeneralClassAxioms(), 100);

      notificationService.success("Axiom Added", "General class axiom added successfully.");
    } catch (error: any) {
      console.error("[Dashboard] Failed to add axiom:", error);
      console.error("[Dashboard] Error details:", {
        message: error?.message,
        status: error?.status || error?.response?.status,
        data: error?.data || error?.response?.data,
        code: error?.code,
      });
      const errorMsg =
        error?.data?.message ||
        error?.response?.data?.message ||
        error?.message ||
        "Could not add general class axiom.";
      notificationService.error("Axiom Failed", errorMsg);
    }
  };

  const handleEditAxiom = (index: number) => {
    const axiom = generalClassAxioms[index];
    setEditingAxiomIndex(index);
    setAxiomDraft({
      definition: axiom.subClass || axiom.definition || "",
      superClassIri: axiom.superClass || axiom.superClassIri || "",
    });
    setAxiomDialogOpen(true);
  };

  const handleUpdateAxiom = async (newSubClass?: string, newSuperClass?: string) => {
    if (!projectId || editingAxiomIndex === null) return;
    try {
      const oldAxiom = generalClassAxioms[editingAxiomIndex];
      const subClass = newSubClass !== undefined ? newSubClass : axiomDraft.definition;
      const superClass = newSuperClass !== undefined ? newSuperClass : axiomDraft.superClassIri;

      await apiClient.put(`/api/ontology/metadata/${projectId}/gci/${editingAxiomIndex}`, {
        oldValue: oldAxiom.value || oldAxiom.subClass || oldAxiom.definition || "",
        subClass,
        superClass: superClass || "",
        draft: ontologyMutationService.resolveUseDraft(),
        userId: user?.id,
      });

      const updatedAxioms = [...generalClassAxioms];
      updatedAxioms[editingAxiomIndex] = {
        value: `${subClass} SubClassOf ${superClass}`,
        subClass,
        superClass: superClass || "",
        definition: subClass,
        superClassIri: superClass || "",
        subExpression: subClass,
      };
      setGeneralClassAxioms(updatedAxioms);

      setAxiomDraft({ definition: "", superClassIri: "" });
      setEditingAxiomIndex(null);
      setAxiomDialogOpen(false);

      setTimeout(() => refreshGeneralClassAxioms(), 100);

      notificationService.success("Axiom Updated", "General class axiom updated successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to update axiom:", error);
      notificationService.error("Axiom Failed", "Could not update general class axiom.");
    }
  };

  const handleDeleteAxiom = async (index: number) => {
    if (!projectId) return;
    try {
      const axiom = generalClassAxioms[index];

      const value = axiom.value || axiom.subClass || axiom.definition || axiom.subExpression || "";

      if (!value) {
        notificationService.error("Axiom Failed", "Cannot delete axiom without a value.");
        return;
      }

      await apiClient.delete(
        `/api/ontology/metadata/${projectId}/gci?value=${encodeURIComponent(value)}` +
          `&draft=${ontologyMutationService.resolveUseDraft()}&userId=${encodeURIComponent(user?.id || "")}`,
      );

      const updatedAxioms = generalClassAxioms.filter((_, idx) => idx !== index);
      setGeneralClassAxioms(updatedAxioms);

      setTimeout(() => refreshGeneralClassAxioms(), 100);

      notificationService.success("Axiom Deleted", "General class axiom deleted successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to delete axiom:", error);
      notificationService.error("Axiom Failed", "Could not delete general class axiom.");
    }
  };

  const refreshGeneralClassAxioms = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(`/api/ontology/metadata/${projectId}/gci`);
      const payload = response?.data || response;
      const data = payload?.data || payload || [];

      const mappedData = Array.isArray(data)
        ? data.map((axiom: any) => ({
          value: axiom.value,
          subClass: axiom.subClass || "",
          superClass: axiom.superClass || "",
          definition: axiom.subClass || axiom.definition || "",
          superClassIri: axiom.superClass || axiom.superClassIri || "",
          subExpression: axiom.subClass || axiom.subExpression || "",
        }))
        : [];
      setGeneralClassAxioms(mappedData);
    } catch (error) {
      console.error("[Dashboard] Failed to refresh general class axioms:", error);
    }
  };

  const handleSavePrefixes = async () => {
    if (!projectId) return;
    try {

      setIsPrefixEditing(false);
      await refreshPrefixes();
      notificationService.info("Prefixes", "Edit mode disabled. Use individual add/edit/delete operations.");
    } catch (error) {
      console.error("[Dashboard] Failed to save prefixes:", error);
      notificationService.error("Prefixes Failed", "Could not save prefixes.");
    }
  };

  useEffect(() => {
    if (!projectId) return;

    const activeUsersInProject = Array.from(collaboration.state.activeUsers.values()).filter(
      (u) => u.projectId === projectId && u.userId !== user?.id,
    );

    if (activeUsersInProject.length > 0) {
      ontologyMutationService.setRealTimeSync(true);
      setSyncMode("public");
    }
  }, [projectId, collaboration.state.activeUsers, user?.id]);

  useEffect(() => {
    if (!projectId || !user) return;

    const broadcastCursor = (e: MouseEvent | PointerEvent) => {
      const newCursor = { x: e.clientX, y: e.clientY };
      setMyLocalCursor(newCursor);

      if (window.vscode) {
        window.vscode.postMessage({
          type: "broadcastCursor",
          projectId,
          userId: user.id,
          userName: user.username || user.email || "Anonymous",
          position: newCursor,
          timestamp: Date.now(),
        });
      }
    };

    const handleMouseMove = (e: MouseEvent) => {
      broadcastCursor(e);
    };

    const handleClick = (e: MouseEvent) => {

      broadcastCursor(e);
    };

    const throttledMouseMove = throttle(handleMouseMove, 50); // Throttle to 20fps

    document.addEventListener("mousemove", throttledMouseMove);
    document.addEventListener("click", handleClick); // Track all clicks

    return () => {
      document.removeEventListener("mousemove", throttledMouseMove);
      document.removeEventListener("click", handleClick);
    };
  }, [projectId, user]);

  useEffect(() => {
    if (!projectId) return;

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;

      if (message.type === "cursorUpdate" && message.userId !== user?.id) {

        const color = getUserColor(message.userId);

        setCollaboratorCursors((prev) => {
          const updated = new Map(prev);
          updated.set(message.userId, {
            x: message.position.x,
            y: message.position.y,
            userName: message.userName,
            color,
            timestamp: message.timestamp,
          });
          return updated;
        });
      }
    };

    window.addEventListener("message", handleMessage);

    const cleanupInterval = setInterval(() => {
      const now = Date.now();
      setCollaboratorCursors((prev) => {
        const updated = new Map(prev);
        let hasChanges = false;

        for (const [userId, cursor] of updated.entries()) {
          if (
            now - (cursor as { x: number; y: number; userName: string; color: string; timestamp: number }).timestamp >
            3000
          ) {
            updated.delete(userId);
            hasChanges = true;
          }
        }

        return hasChanges ? updated : prev;
      });
    }, 3000);

    return () => {
      window.removeEventListener("message", handleMessage);
      clearInterval(cleanupInterval);
    };
  }, [projectId, user]);

  const getUserColor = (userId: string): string => {
    const colors = [
      "#FF6B6B",
      "#4ECDC4",
      "#45B7D1",
      "#FFA07A",
      "#98D8C8",
      "#F7DC6F",
      "#BB8FCE",
      "#85C1E2",
      "#F8B739",
      "#52C5B6",
    ];
    const hash = userId.split("").reduce((acc, char) => acc + char.charCodeAt(0), 0);
    return colors[hash % colors.length];
  };

  const throttle = (func: Function, delay: number) => {
    let lastCall = 0;
    return (...args: any[]) => {
      const now = Date.now();
      if (now - lastCall >= delay) {
        lastCall = now;
        func(...args);
      }
    };
  };

  const loadClassInstances = useCallback(async () => {
    if (!projectId || !selectedClassForIndividuals) {
      setClassInstances([]);
      return;
    }
    setClassInstancesLoading(true);
    try {
      const response = await apiClient.get<any>(
        `/api/ontology/classes/instances/${projectId}?classIri=${encodeURIComponent(selectedClassForIndividuals.id)}`,
      );
      const payload = response?.data || response;
      const instances = Array.isArray(payload?.data) ? payload.data : Array.isArray(payload) ? payload : [];
      setClassInstances(instances);
    } catch (error) {
      console.error("[Dashboard] Failed to load class instances:", error);
      setClassInstances([]);
    } finally {
      setClassInstancesLoading(false);
    }
  }, [projectId, selectedClassForIndividuals]);

  useEffect(() => {
    setClassInstancesQuery("");
    setClassInstancesView("direct");
    setSelectedClassIndividual(null);
    setSelectedClassIndividualDetails(null);
    loadClassInstances();
  }, [loadClassInstances]);

  const refreshSelectedClassIndividualDetails = useCallback(async (afterMutation) => {
    if (!projectId || !selectedClassIndividual?.id) {
      setSelectedClassIndividualDetails(null);
      return;
    }
    setSelectedClassIndividualLoading(true);
    try {
      if (afterMutation) {

        if (isDesktop()) {
          await waitForDesktopOwlApiReady(projectId);
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
      }
      const detailsUrl = `/api/ontology/individual-details/${projectId}?individualIri=${encodeURIComponent(selectedClassIndividual.id)}`;
      let response = await apiClient.get<any>(detailsUrl);
      if (afterMutation && isOwlApiWarmingResponse(response)) {
        await waitForDesktopOwlApiReady(projectId);
        response = await apiClient.get<any>(detailsUrl);
      }
      const details = response?.data || response;
      if (details) {
        setSelectedClassIndividualDetails({
          ...selectedClassIndividual,
          types: details.types || selectedClassIndividual.types,
          annotations: details.annotations || selectedClassIndividual.annotations,
          propertyAssertions: details.propertyAssertions || [],
        });
      }
    } catch (error) {
      console.error("[Dashboard] Failed to load individual details:", error);
      setSelectedClassIndividualDetails(null);
    } finally {
      setSelectedClassIndividualLoading(false);
    }
  }, [projectId, selectedClassIndividual]);

  useEffect(() => {
    refreshSelectedClassIndividualDetails();
  }, [refreshSelectedClassIndividualDetails]);

  const decodeTokenEmail = (token?: string | null) => {
    if (!token) return null;
    try {
      const parts = token.split(".");
      if (parts.length !== 3) return null;
      const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
      return payload.email || null;
    } catch (error) {
      console.warn("[Dashboard] Failed to decode token for email:", error);
      return null;
    }
  };

  const fetchProjects = useCallback(async (): Promise<{ myFiles: any[]; sharedFiles: any[] } | null> => {
    try {
      const resolvedEmail = resolveUserEmail();
      const isWorkspaceMode = !!user?.workspaceId;
      const primaryEndpoint = isWorkspaceMode ? "/api/projects" : "/api/ontology/projects";
      const fallbackEndpoint = isWorkspaceMode ? "/api/ontology/projects" : "/api/projects";
      const params = new URLSearchParams();
      if (resolvedEmail) {
        params.set("userEmail", resolvedEmail);
      }
      const projectsUrl = params.toString() ? `${primaryEndpoint}?${params.toString()}` : primaryEndpoint;

      let response;
      try {
        response = await apiClient.get<any>(projectsUrl);
      } catch (error: any) {
        console.error("[Dashboard] âŒ Primary endpoint error:", error);
        const status = error?.status || error?.response?.status;
        const allowFallback = primaryEndpoint === "/api/projects";
        if (status === 404 && fallbackEndpoint !== primaryEndpoint && allowFallback) {
          const fallbackUrl = resolvedEmail
            ? `${fallbackEndpoint}?userEmail=${encodeURIComponent(resolvedEmail)}`
            : fallbackEndpoint;
          console.warn("[Dashboard] âš ï¸ Projects endpoint missing, falling back to:", fallbackUrl);
          response = await apiClient.get<any>(fallbackUrl);
        } else {
          throw error;
        }
      }

      const data = response?.data || response;

      setHasFetchedProjects(true);

      if (data?.success) {

        if (data.myFiles !== undefined && data.sharedFiles !== undefined) {

          const mapFileToProject = (p: any) => {

            if (p.contentType && p.length && !p.name && !p.filename) {
              console.warn("[Dashboard] âš ï¸ Skipping raw GridFS file entry:", p.id);
              return null;
            }
            return {
              ...p,

              id: p.id || p.projectId || p._id,
              filename: p.filename || p.name || p.id,
              name: p.name || p.filename || p.id,

              status: p.status,
              updatedAt: p.updatedAt,
              ownerEmail: p.ownerEmail,
              metadata: p.metadata,
            };
          };

          const myFilesWithNames = (data.myFiles || []).map(mapFileToProject).filter(Boolean);
          const sharedFilesWithNames = (data.sharedFiles || []).map(mapFileToProject).filter(Boolean);
          const allProjects = [...myFilesWithNames, ...sharedFilesWithNames];

          setAvailableProjects(allProjects);
          setMyFiles(myFilesWithNames);
          setSharedFiles(sharedFilesWithNames);
          setListOfFiles(allProjects);

          return { myFiles: myFilesWithNames, sharedFiles: sharedFilesWithNames };
        } else if (data.projects) {

          const mapFileToProject = (p: any) => {

            if (p.contentType && p.length && !p.name && !p.filename) {
              console.warn("[Dashboard] âš ï¸ Skipping raw GridFS file entry:", p.id);
              return null;
            }
            return {
              ...p,
              id: p.id || p.projectId || p._id,
              filename: p.filename || p.name || p.id,
              name: p.name || p.filename || p.id,
              status: p.status,
              updatedAt: p.updatedAt,
              ownerEmail: p.ownerEmail,
              metadata: p.metadata,
            };
          };

          const projectsWithNames = (data.projects || []).map(mapFileToProject).filter(Boolean);
          setAvailableProjects(projectsWithNames);

          const myFilesList = projectsWithNames.filter((p: any) => !p.sharedBy);
          const sharedFilesList = projectsWithNames.filter((p: any) => p.sharedBy);

          setMyFiles(myFilesList);
          setSharedFiles(sharedFilesList);
          setListOfFiles(projectsWithNames);

          return { myFiles: myFilesList, sharedFiles: sharedFilesList };
        } else {
          setMyFiles([]);
          setSharedFiles([]);
          setListOfFiles([]);
          return { myFiles: [], sharedFiles: [] };
        }
      } else {
        setMyFiles([]);
        setSharedFiles([]);
        setListOfFiles([]);
        return null;
      }
    } catch (error: any) {
      console.error("[Dashboard] âŒ Failed to fetch projects:", error);
      console.error("[Dashboard] âŒ Error details:", error?.response?.data || error?.message || error);
      setMyFiles([]);
      setSharedFiles([]);
      setListOfFiles([]);
      setIsInitialLoading(false);
      return null;
    }
  }, [user?.email, user?.workspaceId, resolveUserEmail]); // Track workspace mode changes + email fallback

  const handleProjectSelection = useCallback((selectedProjectId: string) => {
    setHasUserSelectedFile(true); // Mark that user has manually selected a file
    setProjectId(selectedProjectId);

    if (!initialProjectId) {
      setActiveFileId(selectedProjectId);
    } else {
      setActiveFileId(null);
    }
    setActiveFileName(null);
    setShowProjectSelector(false);
    fetchData(selectedProjectId);
  }, []);

  const handleDeleteFile = useCallback((projectIdToDelete: string, fileName: string) => {
    setDeleteFileDialog({ isOpen: true, projectId: projectIdToDelete, fileName });
  }, []);

  const confirmDeleteFile = useCallback(async () => {
    const targetProjectId = deleteFileDialog.projectId;
    const targetFileName = deleteFileDialog.fileName;
    setDeleteFileDialog({ isOpen: false, projectId: "", fileName: "" });

    if (!targetProjectId) {
      return;
    }

    try {
      const isWorkspaceMode = !!user?.workspaceId;
      const resolvedEmail = resolveUserEmail();
      const ownerEmail = !isWorkspaceMode && resolvedEmail ? `?ownerEmail=${encodeURIComponent(resolvedEmail)}` : "";
      const deleteEndpoint = isWorkspaceMode ? "/api/projects" : "/api/ontology/projects";
      await apiClient.delete(`${deleteEndpoint}/${encodeURIComponent(targetProjectId)}${ownerEmail}`);
      notificationService.success("File Deleted", `"${targetFileName}" deleted successfully.`);
      await fetchProjects();

      if (projectId === targetProjectId) {
        setProjectId(null);
        setActiveFileId(null);
        setActiveFileName(null);
        setSelectedItem(null);
        setHasUserSelectedFile(false);
      }
    } catch (error: any) {
      console.error("[Dashboard] Failed to delete file:", error);
      notificationService.error("Delete Failed", error?.message || "Could not delete the file.");
    }
  }, [
    deleteFileDialog.projectId,
    deleteFileDialog.fileName,
    fetchProjects,
    projectId,
    user?.workspaceId,
    resolveUserEmail,
  ]);

  const handleOpenProjectSelector = useCallback(() => {

    fetchProjects();
    setShowProjectSelector(true);
  }, [fetchProjects]);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = classHierarchy[0].id;
      const childCount = classHierarchy[0].children?.length || 0;

      if (childCount > 0 && !expandedNodes.includes(owlThingId)) {
        setExpandedNodes((prev) => (prev.includes(owlThingId) ? prev : [...prev, owlThingId]));
      }
    }
  }, [classHierarchy]);

  useEffect(() => {
    if (inferredClassHierarchy.length > 0 && inferredClassHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = inferredClassHierarchy[0].id;
      const childCount = inferredClassHierarchy[0].children?.length || 0;

      if (childCount > 0 && !expandedNodes.includes(owlThingId)) {
        setExpandedNodes((prev) => (prev.includes(owlThingId) ? prev : [...prev, owlThingId]));
      }
    }
  }, [inferredClassHierarchy]);

  useEffect(() => {
    if (!user) {
      return;
    }

    const resolvedEmail = resolveUserEmail();

    fetchProjects();

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.email, user?.workspaceId, resolveUserEmail]); // Only re-run when user identity changes

  useEffect(() => {
    setActiveFileId(null);
    setActiveFileName(null);
    setProjectFiles([]);
  }, [initialProjectId]);

  const fileLoadingRef = useRef(false);
  const lastLoadedFileRef = useRef<string | null>(null);

  useEffect(() => {
    if (selectedFileId && selectedFileName && initialProjectId) {

      if (fileLoadingRef.current || lastLoadedFileRef.current === selectedFileId) {
        return;
      }

      fileLoadingRef.current = true;
      lastLoadedFileRef.current = selectedFileId;

      setIsInitialLoading(true);
      setMainTab("Entities");
      setEntitiesTab("Classes");

      setHasUserSelectedFile(true); // Mark that file was selected

      handleLoadProjectFile(selectedFileId, selectedFileName).finally(() => {

        setTimeout(() => {
          fileLoadingRef.current = false;
        }, 1000);
      });
    }

    return () => {
    };
  }, [selectedFileId, selectedFileName, initialProjectId]);

  useEffect(() => {
    if (collaboration?.setCurrentProject) {
      collaboration.setCurrentProject(projectId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const isMountedRef = useRef(false);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const getLocalName = (iri?: string) => {
    if (!iri) return "";
    if (iri.includes("#")) {
      return iri.split("#").pop() || iri;
    }
    const parts = iri.split("/");
    return parts[parts.length - 1] || iri;
  };

  const resolvePropertyIriByLabel = (labelOrIri: string, properties: Property[]) => {
    if (!labelOrIri) return undefined;
    if (labelOrIri.startsWith("http://") || labelOrIri.startsWith("https://")) return labelOrIri;
    const normalized = labelOrIri.toLowerCase();
    const found = properties.find((prop) => (prop.label || getLocalName(prop.id)).toLowerCase() === normalized);
    return found?.id;
  };

  const resolveIndividualIriByLabel = (labelOrIri: string) => {
    if (!labelOrIri) return undefined;
    if (labelOrIri.startsWith("http://") || labelOrIri.startsWith("https://")) return labelOrIri;
    const normalized = labelOrIri.toLowerCase();
    const found = individuals.find((ind) => (ind.label || getLocalName(ind.id)).toLowerCase() === normalized);
    return found?.id;
  };

  const buildDefaultCopyName = (fileName: string, copyIndex: number = 1) => {
    const dotIndex = fileName.lastIndexOf(".");
    if (dotIndex > 0) {
      const baseName = fileName.substring(0, dotIndex);
      const extension = fileName.substring(dotIndex);
      return `${baseName}-copy-${copyIndex}${extension}`;
    }
    return `${fileName}-copy-${copyIndex}`;
  };

  const extractExtension = (fileName: string) => {
    const dotIndex = fileName.lastIndexOf(".");
    return dotIndex > 0 ? fileName.substring(dotIndex) : "";
  };

  const isSupportedOntologyExtension = (fileName: string) => {
    return /\.(owl|rdf|ttl|n3|nt|jsonld)$/i.test(fileName);
  };

  const sendDuplicatePromptResponse = useCallback(
    (action: "open_existing" | "replace" | "create_copy" | "cancel", copyName?: string) => {
      if (!window.vscode || !duplicatePrompt.requestId) {
        setDuplicatePrompt((prev) => ({ ...prev, isOpen: false, requestId: null }));
        return;
      }
      window.vscode.postMessage({
        type: "duplicateFilePromptResponse",
        requestId: duplicatePrompt.requestId,
        action,
        copyName,
      });
      setDuplicatePrompt((prev) => ({ ...prev, isOpen: false, requestId: null }));
      setDuplicateCopyError(null);
      setDuplicateCopyName("");
    },
    [duplicatePrompt.requestId],
  );

  const handleDuplicatePromptCancel = useCallback(() => {
    sendDuplicatePromptResponse("cancel");
  }, [sendDuplicatePromptResponse]);

  const handleDuplicateCreateCopy = useCallback(async () => {
    if (!duplicatePrompt.isOpen) {
      return;
    }
    setDuplicateCopyError(null);
    const originalExt = extractExtension(duplicatePrompt.fileName);
    let candidateName = duplicateCopyName.trim();
    if (!candidateName) {
      setDuplicateCopyError("Name is required.");
      return;
    }

    if (originalExt && !candidateName.toLowerCase().endsWith(originalExt.toLowerCase())) {
      candidateName = `${candidateName}${originalExt}`;
    }

    if (!isSupportedOntologyExtension(candidateName)) {
      setDuplicateCopyError("Unsupported file type.");
      return;
    }

    try {
      setDuplicateCopySubmitting(true);
      if (duplicatePrompt.context === "project" && duplicatePrompt.projectId) {
        const checkResponse = await apiClient.get(
          `/api/projects/${duplicatePrompt.projectId}/files/check?fileName=${encodeURIComponent(candidateName)}`,
        );
        const checkData = (checkResponse as any)?.data || checkResponse;
        if (checkData?.exists) {
          setDuplicateCopyError(`"${candidateName}" already exists. Please choose a different name.`);
          return;
        }
      } else {

        sendDuplicatePromptResponse("create_copy", candidateName);
        return;
      }

      sendDuplicatePromptResponse("create_copy", candidateName);
    } catch (error) {
      console.error("[Dashboard] Copy name duplicate check failed:", error);
      setDuplicateCopyError("Unable to validate copy name. Please try again.");
    } finally {
      setDuplicateCopySubmitting(false);
    }
  }, [duplicatePrompt, duplicateCopyName, sendDuplicatePromptResponse, resolveUserEmail]);

  const handleWaitForLoading = useCallback(() => {
    userLoadingChoice.current = "wait";

    // Dialog will be closed by IMPORT_COMPLETED handler when data loads
  }, []);

  const handleContinueWorking = useCallback(() => {
    userLoadingChoice.current = "continue";
    setShowLoadingChoice(false);

    setTimeout(() => {
      userLoadingChoice.current = null;
    }, 100);
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;

      if (message.type === "showLoading") {
        setHasUserSelectedFile(true);
        hasUserSelectedFileRef.current = true;
        pendingImportProjectIdRef.current = message.projectId; // Track which project is being imported
        setIsExpectingFileReady(true);

        setShowLoadingChoice(true);
        setLoadingProjectName(message.projectId || "Processing file upload...");

        return;
      }

      if (!isMountedRef.current) {
        return;
      }

      switch (message.type) {
        case "duplicateFilePrompt": {
          const defaultCopyName = message.defaultCopyName || buildDefaultCopyName(message.fileName, 1);
          setDuplicatePrompt({
            isOpen: true,
            requestId: message.requestId,
            fileName: message.fileName,
            context: message.context || "project",
            projectId: message.projectId,
            ownerEmail: message.ownerEmail,
            defaultCopyName,
            detail: message.detail,
            allowOpenExisting: message.allowOpenExisting !== false,
            error: message.error,
          });
          setDuplicateCopyName(defaultCopyName);
          setDuplicateCopyError(message.error || null);
          break;
        }
        case "openProjectFile":

          if (initialProjectId) {
            fetchProjectFiles(initialProjectId).then(() => {
            });
          }

          if (initialProjectId && message.projectId && message.projectId !== initialProjectId) {
            console.warn(
              "[Dashboard] openProjectFile project mismatch:",
              message.projectId,
              "expected",
              initialProjectId,
            );
            notificationService.error("Open Failed", "Selected file belongs to a different project.");
            break;
          }
          if (message.fileId && message.fileName) {
            setActiveFileId(message.fileId);
            setActiveFileName(message.fileName);
            if (onFileSelected) onFileSelected(message.fileId, message.fileName);
            handleLoadProjectFile(message.fileId, message.fileName);
          }
          break;
        case "fileReady":
        case "fileLoaded":

          if (initialProjectId) {

            const fetchWithRetry = async (retries = 3, delay = 1000) => {
              for (let attempt = 1; attempt <= retries; attempt++) {
                const fetchedFiles = await fetchProjectFiles(initialProjectId);

                if (message.uploadedFileId) {
                  const found = fetchedFiles.some((f) => f.id === message.uploadedFileId);

                  if (found) {
                    return true;
                  }

                  if (attempt < retries) {
                    await new Promise((resolve) => setTimeout(resolve, delay));
                  } else {
                    console.warn(`[Dashboard] âš ï¸ File ${message.uploadedFileId} not found after ${retries} attempts`);
                    console.warn(`[Dashboard] âš ï¸ This may indicate a database synchronization delay`);
                    return false;
                  }
                } else {

                  return true;
                }
              }
              return false;
            };

            fetchWithRetry()
              .then((success) => {
              })
              .catch((err) => {
                console.error("[Dashboard] âŒ Failed to refresh file list:", err);
              });
          } else {
          }

          if (initialProjectId && message.projectId === initialProjectId) {

            if (message.uploadedFileId && message.uploadedFileName) {

              fetchProjects();

              setTimeout(() => {
                handleLoadProjectFile(message.uploadedFileId, message.uploadedFileName);
              }, 500);
            } else {
              fetchProjects();
            }
            break;
          }
          if (
            initialProjectId &&
            pendingImportProjectIdRef.current &&
            message.projectId === pendingImportProjectIdRef.current
          ) {
            setHasUserSelectedFile(true);
            hasUserSelectedFileRef.current = true;
            setProjectId(message.projectId);
            setSelectedItem(null);
            setLoadingProjectName(message.projectId);

            if (loadingPromiseRef.current) {
            } else {
              loadingPromiseRef.current = fetchData(message.projectId, false, initialProjectId)
                .then(() => {
                  setShowLoadingChoice(false);
                  setShowQueueStatus(false);
                  setIsInitialLoading(false);
                  setTimeout(() => fetchProjects(), 300);
                  loadingPromiseRef.current = null;
                })
                .catch((error) => {
                  console.error("[Dashboard] Failed to load ontology:", error);
                  notificationService.error(
                    "Load Failed",
                    `Could not load "${message.projectId}". The file may still be processing.`,
                  );
                  setShowLoadingChoice(false);
                  setIsInitialLoading(false);
                  loadingPromiseRef.current = null;
                });
            }
            break;
          }

          setHasUserSelectedFile(true);
          hasUserSelectedFileRef.current = true;

          const currentBaseId = projectId?.replace(/-\d+$/, "");
          const newBaseId = message.projectId?.replace(/-\d+$/, "");
          if (currentBaseId !== newBaseId) {
            setProjectId(message.projectId);
          } else {
          }

          const projId = message.projectId || "";
          if (projId.includes(".owl") || projId.includes(".rdf") || projId.includes(".ttl")) {
            setActiveFileName(projId);
          } else {
            setActiveFileName(projId + ".owl"); // Default extension
          }
          setActiveFileId(null); // In free mode, fileId is same as projectId
          setSelectedItem(null);
          setLoadingProjectName(message.projectId);
          userLoadingChoice.current = null; // Reset choice for new loading
          setShowLoadingChoice(true);

          if (loadingPromiseRef.current) {
          } else {
            loadingPromiseRef.current = fetchData(message.projectId, false)
              .then(() => {

                setShowLoadingChoice(false);
                setShowQueueStatus(false);

                setTimeout(() => fetchProjects(), 300);
                loadingPromiseRef.current = null;
                // Dialog will auto-close via importStatusUpdate message when IMPORT_COMPLETED
              })
              .catch((error) => {
                console.error("[Dashboard] Failed to load ontology:", error);
                notificationService.error(
                  "Load Failed",
                  `Could not load "${message.projectId}". The file may still be processing.`,
                );
                setShowLoadingChoice(false);
                loadingPromiseRef.current = null;
                // Dialog will auto-close via importStatusUpdate message when IMPORT_FAILED
              });
          }
          break;
        case "loadingFailed":
          setIsInitialLoading(false);
          console.error("Loading failed:", message.error);
          notificationService.error("Loading Failed", message.error);
          break;
        case "switchView":

          if (message.view === "swrl") {
            setMainTab("SWRL");
          }
          break;
        case "importStatusUpdate":

          if (message.status.projectId) {
            setProjectImportStatuses((prev) => ({
              ...prev,
              [message.status.projectId]: {
                type: message.status.type,
                status: message.status.status,
                progress: message.status.progress,
              },
            }));

            if (message.status.type === "IMPORT_PROGRESS" && message.status.metadata?.message) {
              setLoadingStatusMessage(sanitizeImportMessage(message.status.metadata.message as string));
            } else if (message.status.type === "IMPORT_PROGRESS" && message.status.metadata?.stage) {
              const stage = message.status.metadata.stage as string;
              setLoadingStatusMessage(
                importStageLabel(stage, message.status.metadata?.message as string | undefined),
              );
            }
          }

          if (message.status.type === "IMPORT_COMPLETED") {

            const isCurrentProject = message.status.projectId === projectId;
            const isPendingImport = message.status.projectId === pendingImportProjectIdRef.current;
            const userChoice = userLoadingChoice.current;

            if (isCurrentProject || isPendingImport) {

              if (loadingPromiseRef.current) {

                pendingImportProjectIdRef.current = null;
                return;
              }

              if (isPendingImport) {

                const currentBaseId = projectId?.replace(/-\d+$/, "");
                const newBaseId = message.status.projectId?.replace(/-\d+$/, "");
                if (currentBaseId !== newBaseId) {
                  setProjectId(message.status.projectId);
                } else {
                }
                setLoadingProjectName(message.status.projectId);

                if (!initialProjectId) {
                  const nextFileName = message.status.filename || `${message.status.projectId}.owl`;
                  setActiveFileId(message.status.projectId);
                  setActiveFileName(nextFileName);
                }
              }

              pendingImportProjectIdRef.current = null;
              setIsExpectingFileReady(false);

              fetchData(message.status.projectId, false, initialProjectId)
                .then(() => {

                  setShowLoadingChoice(false);
                  setShowQueueStatus(false);
                  setShowProjectSelector(false);
                  setIsInitialLoading(false);

                  userLoadingChoice.current = null;
                })
                .catch((error) => {
                  console.error("[Dashboard] âŒ Failed to fetch data:", error);
                  setShowLoadingChoice(false);
                  setIsInitialLoading(false);
                  notificationService.error("Load Failed", "Failed to load ontology data");
                });

              setTimeout(() => fetchProjects(), 500);
            } else {
            }
          }

          if (message.status.type === "IMPORT_FAILED") {
            console.error("[Dashboard] Error details:", {
              statusMessage: message.status.statusMessage,
              error: message.status.metadata?.error,
              status: message.status.status,
            });

            const errorMessage =
              sanitizeImportMessage(message.status.statusMessage) ||
              sanitizeImportMessage(message.status.metadata?.error as string) ||
              "Import failed";
            const projectName = message.status.projectId || "unknown";

            let displayError = errorMessage;
            if (
              errorMessage.includes("UnknownHostException: graphdb") ||
              errorMessage.includes("UnknownHostException")
            ) {
              displayError = "Cannot connect to the ontology service. Please ensure backend services are running.";
            } else if (errorMessage.includes("Connection refused") || errorMessage.includes("ConnectException")) {
              displayError = "Ontology service connection refused. Please verify backend services are running.";
            } else if (errorMessage.includes("HTTP error code 404")) {
              displayError = "Ontology data store not found or not initialized. Please check service configuration.";
            } else if (errorMessage.includes("unable to start transaction")) {
              displayError =
                "Unable to start database transaction. Please verify backend services are running.";
            }

            notificationService.error("Import Failed", `Failed to import "${projectName}": ${displayError}`);

            if (message.status.projectId === projectId) {
              setTimeout(() => {
                setShowLoadingChoice(false);
                setShowQueueStatus(false);
              }, 2000);
            }
          }

          if (message.status.type === "IMPORT_STARTED" && message.status.projectId === projectId) {
            setShowQueueStatus(true);
          }

          if (
            message.status.projectId &&
            (message.status.type === "IMPORT_COMPLETED" || message.status.type === "IMPORT_FAILED")
          ) {
            setTimeout(
              () => {
                setProjectImportStatuses((prev) => {
                  const updated = { ...prev };
                  delete updated[message.status.projectId];
                  return updated;
                });
              },
              message.status.type === "IMPORT_COMPLETED" ? 3000 : 10000,
            );
          }
          break;

        case "importFailed":
          console.error("[Dashboard] âŒ Import failed for project:", message.projectId, message.error);
          setShowLoadingChoice(false);
          setShowQueueStatus(false);
          setIsInitialLoading(false);
          notificationService.error("Import Failed", `Failed to import ontology: ${message.error || "Unknown error"}`);
          break;

        case "importTimeout":
          console.error("[Dashboard] â±ï¸ Import timeout for project:", message.projectId);
          setShowLoadingChoice(false);
          setShowQueueStatus(false);
          setIsInitialLoading(false);
          notificationService.error(
            "Import Timeout",
            "The import operation took too long. Your ontology may still be processing. Please check back later.",
          );
          break;

        case "updateLoadingStatus":
          setLoadingStatusMessage(message.message);
          break;

        case "citationFormatted":

          break;

        case "zoteroLibraryData":
        case "zoteroLibraryError":

          break;
      }
    };

    window.addEventListener("message", handleMessage);

    if (window.vscode && !webviewReadySentRef.current) {
      webviewReadySentRef.current = true;
      window.vscode.postMessage({ type: "webviewReady" });
    }

    return () => {
      window.removeEventListener("message", handleMessage);
    };
  }, [projectId, initialProjectId, isExpectingFileReady]); // Remove fetchData to prevent infinite loop - it's captured in the closure

  const loadChildren = useCallback(
    async (nodeId: string) => {
      if (!projectId) return;
      try {
        const response = await apiClient.get<any>(
          `/api/ontology/classes/children/${projectId}?parentIri=${encodeURIComponent(nodeId)}`,
        );

        const children = Array.isArray(response)
          ? response
          : Array.isArray(response?.data)
            ? response.data
            : Array.isArray(response?.classes)
              ? response.classes
              : [];

        const updateTree = (nodes: TreeNode[]): TreeNode[] =>
          nodes.map((n: TreeNode) => {
            if (n.id === nodeId) {
              const mappedChildren = children.map((c: TopLevelClass) => ({
                ...c,
                children: c.hasChildren ? [] : undefined,
                hasChildren: c.hasChildren,
                subClassOfAxioms: [{ id: nodeId, type: "SubClassOf", definition: n.label }],
              }));
              const updatedNode = {
                ...n,
                children: applyInstanceCountsToTree(mappedChildren, classInstanceCounts),
                hasChildren: mappedChildren.length > 0,
              };
              return updatedNode;
            }
            if (n.children) {
              return { ...n, children: updateTree(n.children) };
            }
            return n;
          });

        setClassHierarchy((prevHierarchy) => {
          const updated = updateTree(prevHierarchy);
          return updated;
        });
      } catch (error) {
        console.error(`Failed to load children for ${nodeId}`, error);
      }
    },
    [projectId, classInstanceCounts, applyInstanceCountsToTree],
  );

  const fetchInferredChildren = useCallback(
    async (nodeId: string) => {
      if (!projectId) return [];
      try {

        const response = await apiClient.get<any>(`/api/ontology/${projectId}/reasoner/inferred-subclasses`, {
          classIri: nodeId,
          direct: true,
          reasonerType: selectedReasoner,
        });
        const payload = response?.data || response;
        const items = payload?.inferredSubClasses || payload?.data?.inferredSubClasses || [];
        return Array.isArray(items) ? items : [];
      } catch (error) {
        console.error("[Dashboard] Failed to load inferred subclasses:", error);
        return [];
      }
    },
    [projectId, selectedReasoner],
  );

  const loadInferredChildren = useCallback(
    async (nodeId: string) => {
      if (!projectId) return;
      const inferred = await fetchInferredChildren(nodeId);
      const mappedChildren: TreeNode[] = inferred
        .filter((item: any) => item?.iri && item.iri !== "http://www.w3.org/2002/07/owl#Nothing")
        .map((item: any) => ({
          id: item.iri,
          label: item.label || getLocalName(item.iri),
          children: [],
          hasChildren: true,
          subClassOfAxioms: [{ id: nodeId, type: "SubClassOf", definition: getLocalName(nodeId) || "Thing" }],
        }));

      const updateTree = (nodes: TreeNode[]): TreeNode[] =>
        nodes.map((n: TreeNode) => {
          if (n.id === nodeId) {
            const withCounts = applyInstanceCountsToTree(mappedChildren, classInstanceCounts);
            return {
              ...n,
              children: withCounts,
              hasChildren: withCounts.length > 0,
            };
          }
          if (n.children) {
            return { ...n, children: updateTree(n.children) };
          }
          return n;
        });

      setInferredClassHierarchy((prevHierarchy) => updateTree(prevHierarchy));
    },
    [projectId, fetchInferredChildren, applyInstanceCountsToTree, classInstanceCounts, getLocalName],
  );

  const updateItemInState = useCallback(
    (updatedItem: SelectableItem, markUnsaved: boolean = true) => {

      const updateRecursively = (items: SelectableItem[]): SelectableItem[] => {
        return items.map((item) => {
          if (item.id === updatedItem.id) {

            const existingChildren = (item as TreeNode).children;
            const newChildren = (updatedItem as TreeNode).children;

            return {
              ...updatedItem,
              children: newChildren && newChildren.length > 0 ? newChildren : existingChildren,
            };
          }
          const treeNode = item as TreeNode;
          if (treeNode.children) {
            return { ...item, children: updateRecursively(treeNode.children) };
          }
          return item;
        });
      };

      setSelectedItem((prev) => {
        if (prev?.id === updatedItem.id) {
          return updatedItem;
        }
        return prev;
      });

      switch (entitiesTab) {
        case "Classes":
          setClassHierarchy((prev) => updateRecursively(prev) as TreeNode[]);
          break;
        case "ObjectProperties":
          setObjectProperties((prev) => prev.map((p) => (p.id === updatedItem.id ? (updatedItem as Property) : p)));
          break;
        case "DataProperties":
          setDataProperties((prev) => prev.map((p) => (p.id === updatedItem.id ? (updatedItem as Property) : p)));
          break;
        case "AnnotationProperties":
          setAnnotationProperties((prev) =>
            prev.map((p) => (p.id === updatedItem.id ? (updatedItem as AnnotationProperty) : p)),
          );
          break;
        case "Individuals":
          setIndividuals((prev) => prev.map((i) => (i.id === updatedItem.id ? (updatedItem as Individual) : i)));
          break;
        case "Datatypes":
          setDatatypes((prev) => prev.map((d) => (d.id === updatedItem.id ? (updatedItem as Datatype) : d)));
          break;
      }

      if (markUnsaved) {
        setHasUnsavedChanges(true);
      }
    },
    [entitiesTab, user],
  );

  const refreshClassHierarchy = useCallback(async () => {
    if (!projectId) return;
    const now = Date.now();
    if (classHierarchyRefreshInFlight.current) {
      console.warn("[Dashboard] Skipping class hierarchy refresh: already in flight");
      return;
    }
    if (now - lastClassHierarchyRefreshAt.current < 2000) {
      console.warn("[Dashboard] Skipping class hierarchy refresh: throttled");
      return;
    }
    classHierarchyRefreshInFlight.current = true;
    lastClassHierarchyRefreshAt.current = now;
    try {
      const topLevelRes = await apiClient.get<any>(`/api/ontology/classes/top-level/${encodeProjectId(projectId)}`);

      let classes: any[] = [];
      if (Array.isArray(topLevelRes?.classes)) {
        classes = topLevelRes.classes;
      } else if (Array.isArray(topLevelRes?.data?.classes)) {
        classes = topLevelRes.data.classes;
      } else if (Array.isArray(topLevelRes?.data)) {
        classes = topLevelRes.data;
      } else if (Array.isArray(topLevelRes)) {
        classes = topLevelRes;
      }

      const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
        ...c,
        children: [],
        hasChildren: c.hasChildren,
        subClassOfAxioms: [{ id: "sub1", type: "SubClassOf", definition: "Thing" }],
      }));

      const owlThingNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#Thing",
        label: "owl:Thing",
        children: topLevelNodes,
        hasChildren: topLevelNodes.length > 0,
        annotations: {},
      };

      const hierarchyWithCounts = applyInstanceCountsToTree([owlThingNode], classInstanceCounts);
      setClassHierarchy(hierarchyWithCounts);

      const currentExpandedNodes = expandedNodesRef.current.filter(
        (id) => id !== "http://www.w3.org/2002/07/owl#Thing",
      );
      for (const nodeId of currentExpandedNodes) {
        try {
          await loadChildren(nodeId);
        } catch (err) {

        }
      }
    } catch (error) {
      console.error("[Dashboard] Failed to refresh class hierarchy:", error);
    } finally {
      classHierarchyRefreshInFlight.current = false;
    }
  }, [projectId, loadChildren, classInstanceCounts, applyInstanceCountsToTree]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "Classes") return;

    if (currentHierarchyViewMode === "inferred") {

      loadInferredHierarchy();
    } else {
      refreshClassHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, currentHierarchyViewMode]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "ObjectProperties") return;
    if (hierarchyViewModes.ObjectProperties === "inferred") {

      loadInferredObjectPropertyHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, hierarchyViewModes.ObjectProperties]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "DataProperties") return;
    if (hierarchyViewModes.DataProperties === "inferred") {

      loadInferredDataPropertyHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, hierarchyViewModes.DataProperties]);

  useEffect(() => {
    const handleRemoteEdit = (event: Event) => {
      const customEvent = event as CustomEvent;
      const edit = customEvent.detail;

      if (!projectId) {
        console.warn("[Dashboard] No project ID, cannot apply remote edit");
        return;
      }

      const editUserId = (edit as any).userId || (edit as any).user?.id || (edit as any).user;
      const currentUserId = user?.email || user?.id;
      if (editUserId && currentUserId && editUserId === currentUserId) {
        return;
      }

      switch (edit.type) {
        case "CLASS_ADDED":

          if ((edit as any).parent) {
            const parentId = (edit as any).parent;
            loadChildren(parentId);
          } else {

            refreshClassHierarchy();
          }
          break;

        case "CLASS_DELETED":
          {
            const deletedId =
              (edit as any).nodeId ||
              (edit as any).iri ||
              (edit as any).id ||
              (edit as any).metadata?.iri ||
              "";
            if (deletedId && typeof setClassHierarchy === "function") {
              const idSet = new Set<string>([String(deletedId)]);
              const removeNodesRecursively = (nodes: any[]): any[] =>
                nodes
                  .filter((node) => !idSet.has(node.id))
                  .map((node) =>
                    node.children ? { ...node, children: removeNodesRecursively(node.children) } : node,
                  );
              setClassHierarchy((prev: any) => removeNodesRecursively(prev || []));
            }
            if ((edit as any).parent) {
              loadChildren((edit as any).parent);
            } else {
              refreshClassHierarchy();
            }
          }
          break;

        case "CLASS_MODIFIED":
        case "CLASS_RENAMED":

          const classId = (edit as any).iri || (edit as any).id;
          if (classId) {

            setTimeout(() => {
              apiClient
                .get(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(classId)}`)
                .then((response) => {
                  const newData = response.data || response;

                  if (!newData.id && newData.iri) {
                    newData.id = newData.iri;
                  }
                  updateItemInState(newData);
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 200);
          } else {

            console.warn("[Dashboard] No class ID in edit event, falling back to full refresh");
            refreshClassHierarchy();
          }
          break;

        case "ANNOTATION_ADDED":
        case "ANNOTATION_MODIFIED":
        case "ANNOTATION_DELETED":

          setTimeout(() => {

            if (selectedItem) {
              const entityId = selectedItem.id || selectedItem.iri;

              const editSubject = (edit as any).subject || (edit as any).iri || (edit as any).id;

              if (editSubject && editSubject !== entityId) {
              }

              let url = `/api/ontology/class/${projectId}/${encodeURIComponent(entityId)}`;
              if (entitiesTab === "Classes") {
                url = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(entityId)}`;
              }

              apiClient
                .get(url)
                .then((response) => {
                  const newData = response.data || response;

                  if (!newData.id && newData.iri) {
                    newData.id = newData.iri;
                  }

                  updateItemInState(newData);
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh selected item:", error));
            } else {
            }
          }, 200); // 200ms delay
          break;

        case "PROPERTY_ADDED":
        case "PROPERTY_MODIFIED":
        case "PROPERTY_DELETED":

          apiClient
            .get(`/api/ontology/properties/${encodeProjectId(projectId)}`)
            .then((response) => {
              const allProps = Array.isArray(response.data)
                ? response.data
                : Array.isArray(response.properties)
                  ? response.properties
                  : Array.isArray(response)
                    ? response
                    : [];
              const opList = allProps.filter((p: any) => p.type === "ObjectProperty");
              setObjectProperties(opList);
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh properties:", error));
          break;

        case "INDIVIDUAL_ADDED":
        case "INDIVIDUAL_MODIFIED":
        case "INDIVIDUAL_DELETED":

          apiClient
            .get(`/api/ontology/individuals/${projectId}`)
            .then((response) => {
              setIndividuals(response.data || []);
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh individuals:", error));
          break;

        case "SPARQL_UPDATE":
          showNotification(`${(edit as any).username || "Someone"} executed a SPARQL update. Refreshing...`, "info");

          fetchData(projectId, false);
          break;

        case "CHANGE_REVERTED":
          showNotification(`${(edit as any).username || "Someone"} reverted a change. Refreshing...`, "info");

          fetchData(projectId, false);
          break;

        case "PROJECT_SAVED":
          showNotification(
            `${(edit as any).username || "Someone"} saved the project with ${(edit as any).appliedChanges || 0} changes`,
            "info",
          );

          fetchData(projectId, false);
          break;

        case "DISJOINT_ADDED":
        case "DISJOINT_REMOVED":
          refreshClassHierarchy();
          break;

        case "EQUIVALENT_ADDED":
        case "EQUIVALENT_REMOVED":

          if (selectedItem && selectedItem.id === (edit as any).nodeId) {

            setTimeout(() => {
              apiClient
                .get(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(selectedItem.id)}`)
                .then((response) => {
                  const details = response?.data?.data || response?.data || response;
                  updateItemInState({
                    ...selectedItem,
                    equivalentClassesAxioms: details.equivalentClassesAxioms || [],
                  });
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 1000);
          }
          break;

        case "SUBCLASS_ADDED":
        case "SUBCLASS_REMOVED":

          if (selectedItem && selectedItem.id === (edit as any).nodeId) {

            setTimeout(() => {
              apiClient
                .get(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(selectedItem.id)}`)
                .then((response) => {
                  const details = response?.data?.data || response?.data || response;
                  updateItemInState({
                    ...selectedItem,
                    subClassOfAxioms: details.subClassOfAxioms || [],
                  });

                  refreshClassHierarchy();
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 1000);
          } else {

            refreshClassHierarchy();
          }
          break;

        default:

          apiClient
            .get(`/api/ontology/metadata/${projectId}`)
            .then((response) => {
              setMetadata(response.data);
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh metadata:", error));
      }

      if (collaborationPanelRef.current) {
        collaborationPanelRef.current.refreshChanges();
      }
    };

    window.addEventListener("remoteEditReceived", handleRemoteEdit as EventListener);

    return () => {
      window.removeEventListener("remoteEditReceived", handleRemoteEdit as EventListener);
    };
  }, [projectId, selectedItem, entitiesTab]); // Removed fetchData, showNotification to prevent infinite loop

  useEffect(() => {
    const handleRollback = (event: Event) => {
      const customEvent = event as CustomEvent;
      const detail = customEvent.detail;

      if (!projectId || detail?.projectId !== projectId) {
        return;
      }

      const rollbackUser = detail.username || "Someone";
      const originalAuthor = detail.originalAuthor || "Unknown";
      const oldValue = detail.oldValue;
      const newValue = detail.newValue;

      let message = `${rollbackUser} rolled back change by ${originalAuthor}`;
      if (oldValue && newValue) {
        message += ` (from "${oldValue}" back to "${newValue}")`;
      } else if (newValue) {
        message += ` (restored to "${newValue}")`;
      }
      message += ". Refreshing data...";

      showNotification(message, "info");

      const isAddedRollback = detail.action && detail.action.toLowerCase() === "added";

      if (isAddedRollback) {

        if (selectedItem?.id === detail.entityIRI) {
          setSelectedItem(null);
        }

        if (entitiesTab === "Classes" || detail.changeType?.toLowerCase().includes("class")) {
          setClassHierarchy((prevHierarchy) => {
            const removeNodeFromTree = (nodes: TreeNode[]): TreeNode[] => {
              return nodes
                .filter((node) => node.id !== detail.entityIRI)
                .map((node) => ({
                  ...node,
                  children: node.children ? removeNodeFromTree(node.children) : [],
                }));
            };
            return removeNodeFromTree(prevHierarchy);
          });
        }

        if (detail.changeType?.toLowerCase().includes("objectproperty")) {
          setObjectProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes("dataproperty")) {
          setDataProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes("annotationproperty")) {
          setAnnotationProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        }

        if (detail.changeType?.toLowerCase().includes("individual")) {
          setIndividuals((prev) => prev.filter((i) => i.id !== detail.entityIRI));
        }

        return; // Don't try to fetch the deleted entity
      }

      setTimeout(() => {

        if (detail?.entityIRI) {

          const entityType = detail.entityType ? detail.entityType.toLowerCase() : "";
          let apiEndpoint = "";

          if (entitiesTab === "Classes" || entityType.includes("class") || entityType.includes("annotation")) {

            apiEndpoint = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(detail.entityIRI)}`;
          } else if (
            entitiesTab === "ObjectProperties" ||
            entityType.includes("objectproperty") ||
            entityType.includes("object_property")
          ) {
            apiEndpoint = `/api/ontology/${projectId}/object-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (
            entitiesTab === "DataProperties" ||
            entityType.includes("dataproperty") ||
            entityType.includes("data_property")
          ) {
            apiEndpoint = `/api/ontology/${projectId}/data-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === "AnnotationProperties") {
            apiEndpoint = `/api/ontology/${projectId}/annotation-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === "Individuals" || entityType.includes("individual")) {
            apiEndpoint = `/api/ontology/${projectId}/individuals/${encodeURIComponent(detail.entityIRI)}`;
          } else {

            apiEndpoint = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(detail.entityIRI)}`;
          }

          if (apiEndpoint) {
            apiClient
              .get(apiEndpoint)
              .then((response) => {
                const newData = response.data || response;
                if (!newData.id && newData.iri) {
                  newData.id = newData.iri;
                }

                updateItemInState(newData, false);

                if (selectedItem?.id === detail.entityIRI) {
                  setSelectedItem(newData);
                }

                const isAnnotationChange = entityType.includes("annotation") || (oldValue && newValue); // Has old/new values = annotation change

                if (isAnnotationChange && (entitiesTab === "Classes" || entityType.includes("class"))) {

                  setClassHierarchy((prevHierarchy) => {
                    const updateNodeInTree = (nodes: TreeNode[]): TreeNode[] => {
                      return nodes.map((node) => {
                        if (node.id === detail.entityIRI) {

                          return { ...node, annotations: newData.annotations || node.annotations };
                        }
                        if (node.children && node.children.length > 0) {

                          return { ...node, children: updateNodeInTree(node.children) };
                        }
                        return node;
                      });
                    };
                    return updateNodeInTree(prevHierarchy);
                  });
                } else {

                  if (entitiesTab === "Classes" || entityType.includes("class")) {
                    refreshClassHierarchy();
                  } else if (
                    entitiesTab === "ObjectProperties" ||
                    entitiesTab === "DataProperties" ||
                    entitiesTab === "AnnotationProperties"
                  ) {
                    refreshProperties();
                  } else if (entitiesTab === "Individuals") {

                    fetchData();
                  }
                }
              })
              .catch((error) => {
                console.error("[Dashboard] Failed to refresh entity after rollback:", error);

                fetchData();
              });
          } else {

            fetchData();
          }
        }
      }, 1500); // Increased delay to ensure GraphDB fully processes the rollback
    };

    window.addEventListener("ontologyRollback", handleRollback as EventListener);

    return () => {
      window.removeEventListener("ontologyRollback", handleRollback as EventListener);
    };
  }, [projectId, selectedItem, entitiesTab]); // Removed fetchData, showNotification to prevent infinite loop

  useEffect(() => {
    const handleFileShared = (event: CustomEvent) => {
      const notification = event.detail;

      showToast(
        `${notification.sharedByUsername} shared "${notification.fileName}" with you (${notification.permission} access)`,
        "info",
      );

      if (projectId) {
        setTimeout(() => {
          fetchData(projectId, false);
        }, 500);
      }
    };

    window.addEventListener("fileShared", handleFileShared as EventListener);

    return () => {
      window.removeEventListener("fileShared", handleFileShared as EventListener);
    };
  }, [projectId]); // Removed fetchData, showToast to prevent infinite loop

  useEffect(() => {
    const handleReconnection = (event: Event) => {
      if (projectId) {
        showNotification("Reconnected! Refreshing data...", "info");

        setTimeout(() => {
          fetchData(projectId, false);
        }, 500);
      }
    };

    window.addEventListener("collaborationReconnected", handleReconnection as EventListener);

    return () => {
      window.removeEventListener("collaborationReconnected", handleReconnection as EventListener);
    };
  }, [projectId]); // Removed fetchData, showNotification to prevent infinite loop

  useEffect(() => {

    const unsubscribe = notificationService.onToast((options) => {
      collaboration.addNotification({
        type: options.type,
        message: `${options.title}: ${options.message}`,
        userId: "system",
        username: "System",
        userColor: "#6366f1",
        timestamp: Date.now(),
      });
    });

    if (typeof window !== "undefined" && !window.vscode) {
      notificationService.requestPermission();
    }

    return unsubscribe;
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {

    const loadInstalledPlugins = async () => {
      try {
        pluginLoader.loadFromStorage();
        const installed = pluginLoader.getInstalledPlugins();

        const pluginIds = installed.map((p) => p.id);
        setInstalledPlugins(new Set(pluginIds));

        const pluginToTabMap: Record<string, string> = {
          "swrl-editor-plugin": "SWRL",
          "graph-view-plugin": "Graph",
          "fuzzy-ontology-plugin": "Fuzzy",
          "change-assistant-plugin": "Changes",
          "sparql-query-plugin": "SPARQL",
          "reasoner-plugin": "Reasoner",
        };

        const tabsToShow = pluginIds.map((id) => pluginToTabMap[id]).filter(Boolean);

        if (tabsToShow.length > 0) {
          setVisibleMainTabs((prev) => {
            const newTabs = [...prev];
            tabsToShow.forEach((tab) => {
              if (!newTabs.includes(tab)) {
                newTabs.push(tab);
              }
            });
            return newTabs;
          });
        }

        const loadPluginPromises = installed.map(async (plugin) => {
          try {

            setPluginLoadingStates((prev) => ({ ...prev, [plugin.id]: { loading: true, error: null } }));

            await pluginLoader.loadPlugin(plugin.id);

            setPluginLoadingStates((prev) => ({ ...prev, [plugin.id]: { loading: false, error: null } }));

            setInstalledPlugins((prev) => new Set([...prev]));
          } catch (error) {
            console.warn(`[Dashboard] Failed to auto-load plugin ${plugin.id}:`, error);

            setPluginLoadingStates((prev) => ({
              ...prev,
              [plugin.id]: { loading: false, error: error instanceof Error ? error.message : "Failed to load plugin" },
            }));
          }
        });

        await Promise.all(loadPluginPromises);
      } catch (error) {
        console.error("[Dashboard] Failed to load installed plugins:", error);
      }
    };

    loadInstalledPlugins();
  }, [projectId]);

  return {

    handleInstallPlugin, handleRetryLoadPlugin, handleUninstallPlugin,

    fetchData, fetchProjects, waitForProcessingComplete, resolveUserEmail,

    refreshOntologyAnnotations, refreshOntologyImports, refreshPrefixes, refreshGeneralClassAxioms,
    handleSaveOntologyId, handleAddOntologyAnnotation, handleUpdateOntologyAnnotation,
    handleDeleteOntologyAnnotation,
    handleAddImport, handleSaveImport, handleUpdateImport, handleRemoveImport,
    handleSavePrefix, handleDeletePrefix, handleSavePrefixes,
    handleAddAxiom, handleUpdateAxiom, handleDeleteAxiom,

    loadClassInstances, refreshSelectedClassIndividualDetails,

    handleProjectSelection, handleDeleteFile, confirmDeleteFile, handleOpenProjectSelector,

    sendDuplicatePromptResponse, handleDuplicatePromptCancel, handleDuplicateCreateCopy,

    handleWaitForLoading, handleContinueWorking,

    loadChildren, fetchInferredChildren, loadInferredChildren,
    updateItemInState, refreshClassHierarchy,

    handleDataChanged,

    resolvePropertyIriByLabel, resolveIndividualIriByLabel,

    fileLoadingRef, lastLoadedFileRef, isMountedRef,
  };
}

export type DashboardInit = ReturnType<typeof useDashboardInit>;