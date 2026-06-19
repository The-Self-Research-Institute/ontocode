// @ts-nocheck
/* eslint-disable @typescript-eslint/no-explicit-any */
import React, { useState, useEffect } from "react";
import {
  ChevronRight,
  ChevronDown,
  DatabaseZap,
  Edit2,
  Trash2,
  Plus,
  Network,
  Sparkles,
  Zap,
  Brain,
  RefreshCw,
  Search,
  Eye,
  PlusCircle,
} from "lucide-react";
import apiClient from "../../services/apiClient";
import ontologyMutationService from "../../services/ontologyMutationService";
import { syncService } from "../../services/syncService";
import { pluginLoader } from "../../services/pluginLoader";
import DLQueryPanel from "../DLQueryPanel";
import { PluginPlaceholder, CodeViewPanel } from "./index";
import type { DashboardState } from "./hooks/useDashboardState";
import type { DashboardInit } from "./hooks/useDashboardInit";
import type { DashboardHandlers } from "./hooks/useDashboardHandlers";

interface MainContentRouterProps {
  state: DashboardState;
  init: DashboardInit;
  handlers: DashboardHandlers;
  apiBaseUrl: string;
}

export const MainContentRouter: React.FC<MainContentRouterProps> = ({ state, init, handlers, apiBaseUrl }) => {
  const [importClosureMap, setImportClosureMap] = useState<Record<string, Array<{ iri: string; children?: any[] }>>>({});
  const {
    projectId, metadata, mainTab, entitiesTab,
    classHierarchy, inferredClassHierarchy,
    objectProperties, objectPropertyHierarchy, inferredObjectPropertyHierarchy,
    dataProperties, dataPropertyHierarchy, inferredDataPropertyHierarchy,
    annotationProperties, inferredAnnotationPropertyHierarchy,
    individuals, inferredIndividuals,
    datatypes, inferredDatatypes,
    selectedItem, expandedNodes,
    ontologyImports, ontologyAnnotations, prefixMappings, generalClassAxioms,
    activeOntologySubTab, setActiveOntologySubTab,
    isEditingOntologyId, setIsEditingOntologyId,
    ontologyIriDraft, setOntologyIriDraft,
    versionIriDraft, setVersionIriDraft,
    isPrefixEditing, setIsPrefixEditing,
    editingPrefixIndex, setEditingPrefixIndex,
    importDraft, setImportDraft,
    editingImportIndex, setEditingImportIndex,
    axiomDialogOpen, setAxiomDialogOpen,
    editingAxiomIndex, setEditingAxiomIndex,
    axiomDraft, setAxiomDraft,
    showImportClosure, setShowImportClosure,
    expandedImports, setExpandedImports,
    isPrefixDialogOpen, setIsPrefixDialogOpen,
    prefixDialogData, setPrefixDialogData,
    isImportDialogOpen, setIsImportDialogOpen,
    importDialogData, setImportDialogData,
    isOntologyAnnotationDialogOpen, setIsOntologyAnnotationDialogOpen,
    ontologyAnnotationEditTarget, setOntologyAnnotationEditTarget,
    isEditOntologyIRIDialogOpen, setEditOntologyIRIDialogOpen,
    isGCIEditorDialogOpen, setGCIEditorDialogOpen, editGCIData, setEditGCIData,
    selectedReasoner,
    isReasonerRunning, isReasonerSynced, isReasonerLoading, isConsistencyLoading,
    reasonerResults, consistencyResult, explanationState,
    isReasonerSettingsOpen, setIsReasonerSettingsOpen, setExplanationState,
    hierarchyViewModes, setHierarchyViewModes,
    selectedClassForIndividuals, setSelectedClassForIndividuals,
    classInstances, setClassInstances,
    classInstancesLoading, classInstancesQuery, setClassInstancesQuery,
    classInstancesView, setClassInstancesView,
    classTreeSearchQuery, setClassTreeSearchQuery,
    selectedClassIndividual, setSelectedClassIndividual,
    selectedClassIndividualDetails, setSelectedClassIndividualDetails,
    selectedClassIndividualLoading,
    classInstanceCounts,
    isClassIndividualAnnotationDialogOpen, setClassIndividualAnnotationDialogOpen,
    isClassIndividualTypeDialogOpen, setClassIndividualTypeDialogOpen,
    isClassIndividualPropertyDialogOpen, setClassIndividualPropertyDialogOpen,
    classIndividualPropertyIsObject, setClassIndividualPropertyIsObject,
    dlQuery, setDlQuery, dlQueryResults, isDlQueryLoading,
    pluginLoadingStates,
    installedPlugins, visibleMainTabs,
    showToast, encodeProjectId, applyInstanceCountsToTree,
    setPrefixMappings,
    startReasoner, stopReasoner, toggleReasonerSync,
    handleSelectReasoner, checkConsistency, explainInconsistency,
  } = state;
  const {
    fetchData, refreshClassHierarchy, refreshOntologyAnnotations,
    refreshOntologyImports, refreshPrefixes, refreshGeneralClassAxioms,
    updateItemInState, loadClassInstances, refreshSelectedClassIndividualDetails,
    handleSaveOntologyId, handleAddOntologyAnnotation, handleUpdateOntologyAnnotation,
    handleDeleteOntologyAnnotation,
    handleAddImport, handleSaveImport, handleUpdateImport, handleRemoveImport,
    handleSavePrefix, handleDeletePrefix, handleSavePrefixes,
    handleAddAxiom, handleUpdateAxiom, handleDeleteAxiom,
  } = init;
  const {
    handleAddItem, handleCreateClass, handleDeleteAnnotation,
    handleAddDlToOntology, handleSaveOntologyIRIs, handleSaveGCI, handleDeleteGCI,
  } = handlers;

  useEffect(() => {
    if (!projectId || !showImportClosure) return;
    const loadImportClosure = async () => {
      try {
        const res = await apiClient.get<any>(
          `/api/ontology/metadata/${encodeProjectId(projectId)}/imports/closure`,
        );
        const payload = res?.data || res;
        if (payload?.closure && typeof payload.closure === "object") {
          setImportClosureMap(payload.closure);
        }
      } catch (error) {
        console.warn("[MainContentRouter] Failed to load import closure:", error);
      }
    };
    void loadImportClosure();
  }, [projectId, showImportClosure, ontologyImports, encodeProjectId]);

  // #region Render Methods

  const getImportResolutionStatus = (iri: string): { label: string; tone: "success" | "warning" | "error" | "neutral"; detail: string } => {
    const resolution = (metadata as any)?.importResolution || {};
    const loaded = Array.isArray(resolution.loaded) ? resolution.loaded : [];
    const declaredOnly = Array.isArray(resolution.declaredOnly) ? resolution.declaredOnly : [];
    const failed = resolution.failed && typeof resolution.failed === "object" ? resolution.failed : {};

    if (loaded.includes(iri)) {
      return { label: "Loaded", tone: "success", detail: "Imported ontology content was resolved and loaded into this project graph." };
    }
    if (Object.prototype.hasOwnProperty.call(failed, iri)) {
      return { label: "Failed", tone: "error", detail: String(failed[iri] || "Import could not be loaded.") };
    }
    if (declaredOnly.includes(iri)) {
      return { label: "Declared only", tone: "warning", detail: "The owl:imports declaration exists, but content was not resolved on the server." };
    }
    return { label: "Declared", tone: "neutral", detail: "Declared by owl:imports. Load status is unknown until import resolution runs." };
  };

  // Cleanup sync service when switching projects
  useEffect(() => {
    return () => {
      if (projectId) {
        syncService.stopMonitoring(projectId);
        console.log("[Dashboard] Stopped monitoring for project:", projectId);
      }
    };
  }, [projectId]);

  // Helper component for rendering class hierarchy nodes with tooltips
  const ClassHierarchyNode: React.FC<{ node: any; level: number }> = ({ node, level }) => {
    const [showTooltip, setShowTooltip] = useState(false);
    const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 });

    const handleMouseEnter = (e: React.MouseEvent) => {
      setTooltipPos({ x: e.clientX, y: e.clientY });
      setShowTooltip(true);
    };

    const handleMouseLeave = () => {
      setShowTooltip(false);
    };

    return (
      <div style={{ marginLeft: `${level * 12}px` }} className="relative">
        <div
          className="flex items-center gap-1 py-1 px-2 hover:bg-blue-50 rounded cursor-pointer text-xs group"
          onMouseEnter={handleMouseEnter}
          onMouseLeave={handleMouseLeave}
        >
          {node.children && node.children.length > 0 && <ChevronRight size={12} className="text-gray-400" />}
          <span className="font-mono text-blue-700">{node.name || node.label || node}</span>
          {node.inferred && <span className="text-[10px] bg-green-100 text-green-700 px-1 rounded">inferred</span>}
        </div>

        {/* Explanation Tooltip */}
        {showTooltip && node.explanation && (
          <div
            className="fixed z-[9999] bg-yellow-50 border-2 border-yellow-400 rounded-lg shadow-xl p-3 max-w-sm"
            style={{
              left: `${tooltipPos.x + 10}px`,
              top: `${tooltipPos.y + 10}px`,
              pointerEvents: "none",
            }}
          >
            <div className="text-xs font-semibold text-gray-800 mb-1">Why inferred:</div>
            <div className="text-xs text-gray-700">{node.explanation}</div>
          </div>
        )}

        {Array.isArray(node.children) &&
          node.children.length > 0 &&
          node.children.map((child: any, idx: number) => (
            <ClassHierarchyNode key={idx} node={child} level={level + 1} />
          ))}
      </div>
    );
  };

  // Helper function to render class hierarchy
  const renderClassHierarchy = (nodes: any[]): React.ReactNode => {
    if (!Array.isArray(nodes) || nodes.length === 0) return null;

    // Filter out null/undefined nodes
    const validNodes = nodes.filter((node) => node && (node.iri || node.id));

    return validNodes.map((node: any, idx: number) => (
      <ClassHierarchyNode key={node.iri || node.id || idx} node={node} level={0} />
    ));
  };

  const renderMainContent = () => {
    switch (mainTab) {
      case "CodeView":
        return <CodeViewPanel projectId={projectId} />;
      case "SPARQL": {
        // Use dynamically loaded SPARQL Query Plugin
        const sparqlPlugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "sparql-query-plugin");
        const sparqlLoadingState = pluginLoadingStates["sparql-query-plugin"];

        if (sparqlPlugin?.component && projectId) {
          const SparqlPluginComponent = sparqlPlugin.component;
          return (
            <SparqlPluginComponent
              projectId={projectId}
              prefixes={(metadata as any)?.prefixes || []}
              context={{
                apiClient,
                showNotification: (msg: string, type: "info" | "success" | "warning" | "error") => {
                  console.log(`[${type}] ${msg}`);
                },
              }}
            />
          );
        }

        return (
          <PluginPlaceholder
            pluginId="sparql-query-plugin"
            pluginName="SPARQL Query Editor"
            description="Full-featured SPARQL query editor with syntax highlighting, query management, CSV export, and live results."
            icon={<DatabaseZap size={32} className="text-white" />}
            features={[
              "Query management (save/load)",
              "Sample queries library",
              "Live query execution",
              "Results in table or JSON",
              "CSV/JSON export",
              "Prefix management",
            ]}
            accentColor="from-purple-500 via-indigo-500 to-blue-600"
            onInstall={() => handleInstallPlugin("sparql-query-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("sparql-query-plugin")}
            isInstalled={installedPlugins.has("sparql-query-plugin")}
            isLoading={sparqlLoadingState?.loading || false}
            error={sparqlLoadingState?.error}
          />
        );
      }
      case "Graph": {
        // Use dynamically loaded Graph View Plugin
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "graph-view-plugin");
        const loadingState = pluginLoadingStates["graph-view-plugin"];

        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return (
            <PluginComponent
              projectId={projectId}
              context={{
                projectId,
                apiBaseUrl: getBaseUrl(),
                permissions: {
                  canEdit: !readonlyMode,
                  canDelete: !readonlyMode,
                  canShare: true,
                  canExport: true,
                },
              }}
              onNodeClick={handleGraphNodeClick}
            />
          );
        }

        return (
          <PluginPlaceholder
            pluginId="graph-view-plugin"
            pluginName="Graph Visualization"
            description="Next-generation D3.js-powered graph visualization with hierarchical lazy loading, instant load times, and enterprise-grade 60 FPS performance."
            icon={<Network size={32} className="text-white" />}
            features={[
              "Hierarchical lazy loading",
              "60 FPS performance",
              "Drag-and-drop nodes",
              "Multi-select support",
              "SVG/PNG export",
              "Physics simulation",
            ]}
            accentColor="from-cyan-500 via-blue-500 to-indigo-600"
            onInstall={() => handleInstallPlugin("graph-view-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("graph-view-plugin")}
            isInstalled={installedPlugins.has("graph-view-plugin")}
            isLoading={loadingState?.loading || false}
            error={loadingState?.error}
          />
        );
      }
      case "SWRL": {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "swrl-editor-plugin");
        const loadingState = pluginLoadingStates["swrl-editor-plugin"];

        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }

        return (
          <PluginPlaceholder
            pluginId="swrl-editor-plugin"
            pluginName="SWRL Rules Editor"
            description="Create, edit, and execute Semantic Web Rule Language (SWRL) rules with syntax validation and built-in function support."
            icon={<Brain size={32} className="text-white" />}
            features={[
              "Visual rule editor",
              "Syntax validation",
              "Built-in functions",
              "Rule execution engine",
              "Template library",
              "Inference results",
            ]}
            accentColor="from-purple-500 via-violet-500 to-indigo-600"
            onInstall={() => handleInstallPlugin("swrl-editor-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("swrl-editor-plugin")}
            isInstalled={installedPlugins.has("swrl-editor-plugin")}
            isLoading={loadingState?.loading || false}
            error={loadingState?.error}
          />
        );
      }
      case "Fuzzy": {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "fuzzy-ontology-plugin");
        const loadingState = pluginLoadingStates["fuzzy-ontology-plugin"];

        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }

        return (
          <PluginPlaceholder
            pluginId="fuzzy-ontology-plugin"
            pluginName="Fuzzy Ontology"
            description="Advanced fuzzy ontology support with 5 fuzzy modifiers, 5 membership functions, visual canvas editor, and comprehensive SPARQL integration."
            icon={<Sparkles size={32} className="text-white" />}
            features={[
              "5 fuzzy modifiers",
              "5 membership functions",
              "Visual canvas editor",
              "Real-time curve rendering",
              "T-norms/T-conorms",
              "SPARQL integration",
            ]}
            accentColor="from-amber-500 via-orange-500 to-red-500"
            onInstall={() => handleInstallPlugin("fuzzy-ontology-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("fuzzy-ontology-plugin")}
            isInstalled={installedPlugins.has("fuzzy-ontology-plugin")}
            isLoading={loadingState?.loading || false}
            error={loadingState?.error}
          />
        );
      }
      case "Changes": {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "change-assistant-plugin");
        const loadingState = pluginLoadingStates["change-assistant-plugin"];

        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }

        return (
          <PluginPlaceholder
            pluginId="change-assistant-plugin"
            pluginName="Change Assistant"
            description="Comprehensive change tracking and collaboration tool with real-time monitoring, conflict detection, approval workflows, and version control integration."
            icon={<GitMerge size={32} className="text-white" />}
            features={[
              "Real-time tracking",
              "Conflict detection",
              "Approval workflows",
              "Diff visualization",
              "Team comments",
              "Rollback support",
            ]}
            accentColor="from-emerald-500 via-teal-500 to-cyan-600"
            onInstall={() => handleInstallPlugin("change-assistant-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("change-assistant-plugin")}
            isInstalled={installedPlugins.has("change-assistant-plugin")}
            isLoading={loadingState?.loading || false}
            error={loadingState?.error}
          />
        );
      }
      case "Reasoner": {
        const plugin = pluginLoader.getInstalledPlugins().find((p) => p.id === "reasoner-plugin");
        const loadingState = pluginLoadingStates["reasoner-plugin"];

        if (plugin?.component) {
          const PluginComponent = plugin.component;
          return (
            <PluginComponent
              projectId={projectId || ""}
              apiBaseUrl={getBaseUrl()}
              selectedReasoner={selectedReasoner}
              isReasonerRunning={isReasonerRunning}
              isReasonerLoading={isReasonerLoading}
              reasonerResults={reasonerResults}
              consistencyResult={consistencyResult}
              inferredClassHierarchy={inferredClassHierarchy}
              inferredObjectPropertyHierarchy={inferredObjectPropertyHierarchy}
              inferredDataPropertyHierarchy={inferredDataPropertyHierarchy}
              onStartReasoner={startReasoner}
              onStopReasoner={stopReasoner}
              onSelectReasoner={handleSelectReasoner}
              onToggleSync={toggleReasonerSync}
              isReasonerSynced={isReasonerSynced}
            />
          );
        }

        return (
          <PluginPlaceholder
            pluginId="reasoner-plugin"
            pluginName="OWL Reasoner"
            description="Advanced OWL 2 DL reasoning with classification, consistency checking, explanations, and inferred hierarchies."
            icon={<Zap size={32} className="text-white" />}
            features={[
              "HermiT, ELK, Pellet, Openllet, Structural support",
              "Full classification with inferred hierarchy",
              "Consistency checks with unsat explanations",
              "Auto-sync with ontology edits",
              "Export inferred hierarchy (JSON/CSV)",
              "Detailed reasoner statistics",
            ]}
            accentColor="from-indigo-500 via-purple-500 to-pink-500"
            onInstall={() => handleInstallPlugin("reasoner-plugin")}
            onRetryLoad={() => handleRetryLoadPlugin("reasoner-plugin")}
            isInstalled={installedPlugins.has("reasoner-plugin")}
            isLoading={loadingState?.loading || false}
            error={loadingState?.error}
          />
        );
      }
      case "ActiveOntology":
        return (
          <div className="flex h-full" style={{ backgroundColor: "var(--surface-2)" }}>
            <div
              className="flex-1 flex flex-col border-r m-2 rounded shadow-sm overflow-hidden"
              style={{ backgroundColor: "var(--bg)", borderColor: "var(--border)" }}
            >
              {/* Ontology Header Section */}
              <div
                className="p-4 border-b"
                style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
              >
                <div className="flex justify-between items-start mb-4">
                  <h2 className="text-xs font-bold uppercase tracking-wider" style={{ color: "var(--text-primary)" }}>
                    Ontology header
                  </h2>
                  <button
                    onClick={() => setEditOntologyIRIDialogOpen(true)}
                    className="p-1 rounded transition-colors"
                    style={{ color: "var(--accent)" }}
                    onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = "var(--hover-overlay)")}
                    onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "transparent")}
                    title="Edit Ontology IRIs"
                  >
                    <Edit2 size={14} />
                  </button>
                </div>

                <div className="grid grid-cols-1 gap-4">
                  <div
                    className="p-3 border rounded shadow-sm"
                    style={{ backgroundColor: "var(--bg)", borderColor: "var(--border)" }}
                  >
                    <div className="text-[10px] font-bold uppercase mb-1" style={{ color: "var(--text-tertiary)" }}>
                      Ontology IRI
                    </div>
                    <div className="flex items-center gap-2">
                      <Globe size={14} className="flex-shrink-0" style={{ color: "var(--accent)" }} />
                      <a
                        href={(metadata as any)?.ontologyIRI || "#"}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-xs break-all font-medium hover:underline"
                        style={{ color: "var(--accent)" }}
                      >
                        {(metadata as any)?.ontologyIRI || "http://www.semanticweb.org/ontology"}
                      </a>
                    </div>
                  </div>

                  <div
                    className="p-3 border rounded shadow-sm"
                    style={{ backgroundColor: "var(--bg)", borderColor: "var(--border)" }}
                  >
                    <div className="text-[10px] font-bold uppercase mb-1" style={{ color: "var(--text-tertiary)" }}>
                      Ontology Version IRI
                    </div>
                    <div className="flex items-center gap-2">
                      <LinkIcon size={14} className="flex-shrink-0" style={{ color: "var(--success)" }} />
                      <div className="text-xs break-all font-medium" style={{ color: "var(--text-primary)" }}>
                        {(metadata as any)?.versionIRI || "Not specified"}
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {/* Annotations Section */}
              <div className="flex-1 overflow-y-auto p-4" style={{ minHeight: 0 }}>
                <div className="flex items-center justify-between mb-3">
                  <h3 className="text-xs font-semibold" style={{ color: "var(--text-primary)" }}>
                    Annotations
                  </h3>
                  <button
                    onClick={() => {
                      setOntologyAnnotationEditTarget(null);
                      setIsOntologyAnnotationDialogOpen(true);
                    }}
                    className="px-2 py-1 text-xs rounded transition-colors"
                    style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                    onMouseEnter={(e) => (e.currentTarget.style.opacity = "0.9")}
                    onMouseLeave={(e) => (e.currentTarget.style.opacity = "1")}
                  >
                    Add
                  </button>
                </div>
                {ontologyAnnotations.length > 0 ? (
                  <div className="space-y-2">
                    {ontologyAnnotations.map((annotation, idx) => {
                        const key = `${annotation.propertyIri || annotation.property}-${annotation.value}-${idx}`;
                        const propertyIri = annotation.propertyIri || annotation.property || "";
                        const propertyLabel = propertyIri.includes("#")
                          ? propertyIri.split("#").pop()
                          : propertyIri.includes("/")
                            ? propertyIri.split("/").pop()
                            : propertyIri;
                        return (
                          <div
                            key={key}
                            className="border rounded-md transition-colors"
                            style={{ borderColor: "var(--border)" }}
                            onMouseEnter={(e) => (e.currentTarget.style.borderColor = "var(--accent)")}
                            onMouseLeave={(e) => (e.currentTarget.style.borderColor = "var(--border)")}
                          >
                            <div
                              className="px-3 py-2 border-b flex items-center justify-between"
                              style={{ backgroundColor: "var(--accent-tint)", borderColor: "var(--border)" }}
                            >
                              <div>
                                <div className="text-xs font-semibold" style={{ color: "var(--text-primary)" }}>
                                  {propertyLabel}
                                </div>
                                <div
                                  className="text-[10px] font-mono truncate"
                                  style={{ color: "var(--text-tertiary)" }}
                                  title={annotation.propertyIri}
                                >
                                  {annotation.propertyIri}
                                </div>
                              </div>
                              <div className="flex gap-2">
                                <button
                                  onClick={() => {
                                    setOntologyAnnotationEditTarget({
                                      propertyIri: annotation.propertyIri,
                                      value: annotation.value,
                                      datatype: annotation.datatype,
                                    });
                                    setIsOntologyAnnotationDialogOpen(true);
                                  }}
                                  className="px-2 py-1 text-[10px] rounded transition-colors"
                                  style={{ backgroundColor: "var(--surface-2)", color: "var(--text-primary)" }}
                                  onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = "var(--hover-overlay)")}
                                  onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "var(--surface-2)")}
                                >
                                  Edit
                                </button>
                                <button
                                  onClick={() =>
                                    handleDeleteOntologyAnnotation(
                                      annotation.propertyIri,
                                      annotation.value,
                                      annotation.datatype,
                                    )
                                  }
                                  className="px-2 py-1 text-[10px] rounded transition-colors"
                                  style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                  onMouseEnter={(e) => (e.currentTarget.style.opacity = "0.8")}
                                  onMouseLeave={(e) => (e.currentTarget.style.opacity = "1")}
                                >
                                  Delete
                                </button>
                              </div>
                            </div>
                            <div
                              className="px-3 py-2 text-xs"
                              style={{ backgroundColor: "var(--bg)", color: "var(--text-primary)" }}
                            >
                              <div className="break-words">{annotation.value}</div>
                              {annotation.datatype && (
                                <div className="text-[10px] mt-1" style={{ color: "var(--text-tertiary)" }}>
                                  Datatype: {shortenDatatype(annotation.datatype)}
                                </div>
                              )}
                            </div>
                          </div>
                        );
                      })}
                  </div>
                ) : (
                  <div
                    className="text-xs italic p-8 text-center border border-dashed rounded"
                    style={{
                      color: "var(--text-tertiary)",
                      backgroundColor: "var(--surface-2)",
                      borderColor: "var(--border)",
                    }}
                  >
                    No annotations defined for this ontology
                  </div>
                )}
              </div>

              {/* Resize Handle */}
              <div
                className="relative cursor-ns-resize group"
                style={{
                  height: "6px",
                  backgroundColor: isResizing ? "var(--accent)" : "var(--border)",
                  transition: "background-color 0.2s",
                }}
                onMouseDown={(e) => {
                  setIsResizing(true);
                  const startY = e.clientY;
                  const startHeight = bottomTabsHeight;

                  const handleMouseMove = (moveEvent: MouseEvent) => {
                    const deltaY = startY - moveEvent.clientY;
                    const newHeight = Math.max(150, Math.min(600, startHeight + deltaY));
                    setBottomTabsHeight(newHeight);
                  };

                  const handleMouseUp = () => {
                    setIsResizing(false);
                    document.removeEventListener("mousemove", handleMouseMove);
                    document.removeEventListener("mouseup", handleMouseUp);
                  };

                  document.addEventListener("mousemove", handleMouseMove);
                  document.addEventListener("mouseup", handleMouseUp);
                }}
              >
                <div
                  className="absolute inset-x-0 top-1/2 -translate-y-1/2 h-1 mx-auto w-12 rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
                  style={{ backgroundColor: "var(--accent)" }}
                />
              </div>

              {/* Bottom Tabs Section (Imports, GCIs, Prefixes) */}
              <div
                className="border-t flex flex-col"
                style={{
                  borderColor: "var(--border)",
                  backgroundColor: "var(--surface-2)",
                  height: `${bottomTabsHeight}px`,
                  minHeight: "150px",
                }}
              >
                <div
                  className="flex text-[10px] font-bold uppercase tracking-tighter border-b"
                  style={{ backgroundColor: "var(--surface-3)", borderColor: "var(--border)" }}
                >
                  {[
                    { id: "prefixes", label: "Ontology Prefixes", icon: Hash },
                    { id: "imports", label: "Ontology Imports", icon: Download },
                    { id: "axioms", label: "General Class Axioms", icon: Code },
                  ].map((t) => (
                    <button
                      key={t.id}
                      onClick={() => setActiveOntologySubTab(t.id)}
                      className="px-4 py-2 flex items-center gap-2 border-r transition-all"
                      style={{
                        borderColor: "var(--border)",
                        backgroundColor: activeOntologySubTab === t.id ? "var(--bg)" : "transparent",
                        color: activeOntologySubTab === t.id ? "var(--accent)" : "var(--text-secondary)",
                        borderBottom: activeOntologySubTab === t.id ? "2px solid var(--accent)" : "none",
                      }}
                      onMouseEnter={(e) => {
                        if (activeOntologySubTab !== t.id)
                          e.currentTarget.style.backgroundColor = "var(--hover-overlay)";
                      }}
                      onMouseLeave={(e) => {
                        if (activeOntologySubTab !== t.id) e.currentTarget.style.backgroundColor = "transparent";
                      }}
                    >
                      <t.icon size={12} />
                      {t.label}
                    </button>
                  ))}
                </div>
                <div
                  className="p-2 text-sm overflow-hidden flex-1 flex flex-col"
                  style={{ backgroundColor: "var(--bg)" }}
                >
                  {activeOntologySubTab === "prefixes" && (
                    <div
                      className="border rounded flex flex-col flex-1 overflow-hidden"
                      style={{ borderColor: "var(--border)" }}
                    >
                      <div
                        className="flex items-center justify-between px-3 py-2 border-b flex-shrink-0"
                        style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
                      >
                        <div className="text-[11px] font-semibold" style={{ color: "var(--text-primary)" }}>
                          Prefix mappings
                        </div>
                        <button
                          onClick={handleAddPrefixDialog}
                          className="px-2 py-1 text-[10px] rounded flex items-center gap-1"
                          style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                        >
                          <Plus size={12} /> Add Prefix
                        </button>
                      </div>
                      <div className="flex-1 overflow-y-auto">
                        {prefixMappings.length === 0 ? (
                          <div className="p-4 text-center text-xs italic" style={{ color: "var(--text-tertiary)" }}>
                            No prefix mappings defined
                          </div>
                        ) : (
                          <table className="w-full text-left text-xs">
                            <thead className="sticky top-0" style={{ backgroundColor: "var(--surface-1)" }}>
                              <tr className="border-b" style={{ borderColor: "var(--border)" }}>
                                <th className="p-2 font-semibold w-1/4" style={{ color: "var(--text-primary)" }}>
                                  Prefix
                                </th>
                                <th className="p-2 font-semibold" style={{ color: "var(--text-primary)" }}>
                                  Namespace IRI
                                </th>
                                <th className="p-2 w-24" style={{ color: "var(--text-primary)" }}>
                                  Actions
                                </th>
                              </tr>
                            </thead>
                            <tbody>
                              {prefixMappings.map((p, idx) => (
                                <tr
                                  key={`${p.prefix}-${idx}`}
                                  className="border-b"
                                  style={{ borderColor: "var(--border)" }}
                                  onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = "var(--hover-overlay)")}
                                  onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "transparent")}
                                >
                                  <td className="p-2 font-mono" style={{ color: "var(--text-primary)" }}>
                                    {p.prefix}
                                  </td>
                                  <td className="p-2 break-all" style={{ color: "var(--accent)" }}>
                                    {p.namespace}
                                  </td>
                                  <td className="p-2">
                                    <div className="flex gap-1">
                                      <button
                                        onClick={() => handleEditPrefixDialog(p.prefix, p.namespace)}
                                        className="p-1 rounded text-[10px]"
                                        style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                        title="Edit"
                                      >
                                        <Edit2 size={12} />
                                      </button>
                                      <button
                                        onClick={() => handleDeletePrefix(p.prefix)}
                                        className="p-1 rounded text-[10px]"
                                        style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                        title="Delete"
                                      >
                                        <Trash2 size={12} />
                                      </button>
                                    </div>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </div>
                    </div>
                  )}
                  {activeOntologySubTab === "imports" && (
                    <div
                      className="border rounded flex flex-col flex-1 overflow-hidden"
                      style={{ borderColor: "var(--border)" }}
                    >
                      <div
                        className="flex items-center justify-between px-3 py-2 border-b flex-shrink-0"
                        style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
                      >
                        <div className="flex items-center gap-3">
                          <div className="text-[11px] font-semibold" style={{ color: "var(--text-primary)" }}>
                            Ontology Imports
                          </div>
                          <div
                            className="flex items-center gap-1 text-[10px]"
                            style={{ color: "var(--text-tertiary)" }}
                          >
                            <span>
                              ({ontologyImports.length} {ontologyImports.length === 1 ? "import" : "imports"})
                            </span>
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <label
                            className="flex items-center gap-1.5 text-[10px] cursor-pointer"
                            style={{ color: "var(--text-secondary)" }}
                          >
                            <input
                              type="checkbox"
                              checked={showImportClosure}
                              onChange={(e) => setShowImportClosure(e.target.checked)}
                              className="w-3 h-3"
                            />
                            <span>Show import closure</span>
                          </label>
                          <button
                            onClick={handleAddImportDialog}
                            className="px-2 py-1 text-[10px] rounded flex items-center gap-1"
                            style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                          >
                            <Plus size={12} /> Add Import
                          </button>
                        </div>
                      </div>
                      <div className="flex-1 overflow-y-auto">
                        {ontologyImports.length === 0 ? (
                          <div className="p-6 text-center">
                            <div className="mb-2" style={{ color: "var(--text-tertiary)" }}>
                              <Download size={32} className="mx-auto opacity-30" />
                            </div>
                            <div className="text-xs italic" style={{ color: "var(--text-tertiary)" }}>
                              No ontology imports
                            </div>
                            <div className="text-[10px] mt-1" style={{ color: "var(--text-quaternary)" }}>
                              Click "Add Import" to import an ontology
                            </div>
                          </div>
                        ) : (
                          <div className="divide-y" style={{ borderColor: "var(--border)" }}>
                            {ontologyImports.map((iri, idx) => {
                              const fileName = iri.substring(iri.lastIndexOf("/") + 1) || iri;
                              const isLocal =
                                iri.startsWith("file://") ||
                                (!iri.startsWith("http://") && !iri.startsWith("https://"));
                              const isExpanded = expandedImports.has(iri);
                              const resolutionStatus = getImportResolutionStatus(iri);
                              const statusStyle =
                                resolutionStatus.tone === "success"
                                  ? { backgroundColor: "rgba(34,197,94,0.14)", color: "rgb(34,197,94)" }
                                  : resolutionStatus.tone === "warning"
                                    ? { backgroundColor: "rgba(245,158,11,0.14)", color: "rgb(245,158,11)" }
                                    : resolutionStatus.tone === "error"
                                      ? { backgroundColor: "var(--error-tint)", color: "var(--error)" }
                                      : { backgroundColor: "var(--surface-3)", color: "var(--text-secondary)" };

                              return (
                                <div
                                  key={`${iri}-${idx}`}
                                  className="group"
                                  onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = "var(--hover-overlay)")}
                                  onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "transparent")}
                                >
                                  <div className="flex items-start gap-2 p-2.5">
                                    {showImportClosure && (
                                      <button
                                        onClick={() => {
                                          const newExpanded = new Set(expandedImports);
                                          if (isExpanded) {
                                            newExpanded.delete(iri);
                                          } else {
                                            newExpanded.add(iri);
                                          }
                                          setExpandedImports(newExpanded);
                                        }}
                                        className="p-0.5 rounded hover:bg-opacity-10"
                                        style={{ color: "var(--text-tertiary)" }}
                                      >
                                        {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                                      </button>
                                    )}
                                    <div className="flex-shrink-0 mt-0.5">
                                      {isLocal ? (
                                        <FileCode size={14} style={{ color: "var(--accent)" }} />
                                      ) : (
                                        <Globe size={14} style={{ color: "var(--accent)" }} />
                                      )}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <div
                                        className="text-[11px] font-medium truncate"
                                        style={{ color: "var(--text-primary)" }}
                                        title={fileName}
                                      >
                                        {fileName}
                                      </div>
                                      <div
                                        className="text-[10px] font-mono break-all mt-0.5"
                                        style={{ color: "var(--text-tertiary)" }}
                                      >
                                        {iri}
                                      </div>
                                      {isLocal && (
                                        <div className="flex items-center gap-1 mt-1">
                                          <span
                                            className="px-1.5 py-0.5 text-[9px] rounded"
                                            style={{
                                              backgroundColor: "var(--surface-3)",
                                              color: "var(--text-secondary)",
                                            }}
                                          >
                                            LOCAL
                                          </span>
                                        </div>
                                      )}
                                      <div className="flex items-center gap-1 mt-1">
                                        <span
                                          className="px-1.5 py-0.5 text-[9px] rounded"
                                          style={statusStyle}
                                          title={resolutionStatus.detail}
                                        >
                                          {resolutionStatus.label}
                                        </span>
                                      </div>
                                      {showImportClosure && isExpanded && (
                                        <div
                                          className="mt-2 ml-4 pl-3 border-l-2 text-[10px] space-y-1"
                                          style={{ borderColor: "var(--border)", color: "var(--text-tertiary)" }}
                                        >
                                          {(importClosureMap[iri] || []).length === 0 ? (
                                            <div className="italic">No transitive imports declared</div>
                                          ) : (
                                            (importClosureMap[iri] || []).map((child, childIdx) => {
                                              const renderClosure = (node: { iri: string; children?: any[] }, depth = 0): React.ReactNode => (
                                                <div key={`${node.iri}-${depth}`} style={{ marginLeft: depth * 12 }}>
                                                  <div className="font-mono break-all">{node.iri}</div>
                                                  {(node.children || []).map((c) => renderClosure(c, depth + 1))}
                                                </div>
                                              );
                                              return <div key={`${child.iri}-${childIdx}`}>{renderClosure(child)}</div>;
                                            })
                                          )}
                                        </div>
                                      )}
                                    </div>
                                    <div className="flex-shrink-0 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                                      <button
                                        onClick={() => handleEditImportDialog(iri)}
                                        className="p-1.5 rounded"
                                        style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                        title="Edit import"
                                      >
                                        <Edit2 size={11} />
                                      </button>
                                      <button
                                        onClick={() => handleRemoveImport(iri)}
                                        className="p-1.5 rounded"
                                        style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                        title="Remove import"
                                      >
                                        <Trash2 size={11} />
                                      </button>
                                    </div>
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                      {ontologyImports.length > 0 && (
                        <div
                          className="px-3 py-2 border-t text-[10px] flex items-center justify-between"
                          style={{
                            borderColor: "var(--border)",
                            backgroundColor: "var(--surface-2)",
                            color: "var(--text-tertiary)",
                          }}
                        >
                          <div className="flex items-center gap-2">
                            <Info size={12} />
                            <span>
                              Imports are owl:imports declarations. Loaded imports are included in the project graph;
                              declared-only imports match Protégé declarations but were not resolved on this server.
                            </span>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                  {activeOntologySubTab === "axioms" && (
                    <div
                      className="border rounded flex flex-col flex-1 overflow-hidden"
                      style={{ borderColor: "var(--border)" }}
                    >
                      <div
                        className="flex items-center justify-between px-3 py-2 border-b flex-shrink-0"
                        style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
                      >
                        <div className="text-[11px] font-semibold" style={{ color: "var(--text-primary)" }}>
                          General Class Axioms
                        </div>
                        <button
                          onClick={() => {
                            setEditingAxiomIndex(null);
                            setAxiomDraft({ definition: "", superClassIri: "" });
                            setAxiomDialogOpen(true);
                          }}
                          className="px-2 py-1 text-[10px] rounded flex items-center gap-1"
                          style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                        >
                          <Plus size={12} /> Add Axiom
                        </button>
                      </div>
                      <div className="flex-1 overflow-y-auto">
                        {generalClassAxioms.length === 0 ? (
                          <div className="p-4 text-center italic text-xs" style={{ color: "var(--text-tertiary)" }}>
                            No general class axioms detected
                          </div>
                        ) : (
                          <div className="divide-y" style={{ borderColor: "var(--border)" }}>
                            {generalClassAxioms.map((axiom, idx) => (
                              <div
                                key={`${axiom.subExpression}-${idx}`}
                                className="p-3"
                                onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = "var(--hover-overlay)")}
                                onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "transparent")}
                              >
                                <div className="flex items-start justify-between gap-2">
                                  <div className="flex-1">
                                    <div
                                      className="text-[10px] font-semibold mb-1"
                                      style={{ color: "var(--text-secondary)" }}
                                    >
                                      Axiom #{idx + 1}
                                    </div>
                                    <div className="font-medium text-xs mb-1" style={{ color: "var(--text-primary)" }}>
                                      {axiom.subClass || axiom.definition || "Anonymous class expression"}
                                    </div>
                                    {(axiom.superClass || axiom.superClassIri) && (
                                      <div
                                        className="text-[10px] font-mono break-all"
                                        style={{ color: "var(--text-tertiary)" }}
                                      >
                                        SubClassOf: {axiom.superClass || axiom.superClassIri}
                                      </div>
                                    )}
                                  </div>
                                  <div className="flex gap-1 flex-shrink-0">
                                    <button
                                      onClick={() => handleEditAxiom(idx)}
                                      className="p-1 rounded"
                                      style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                      title="Edit axiom"
                                    >
                                      <Edit2 size={12} />
                                    </button>
                                    <button
                                      onClick={() => handleDeleteAxiom(idx)}
                                      className="p-1 rounded"
                                      style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                      title="Delete axiom"
                                    >
                                      <Trash2 size={12} />
                                    </button>
                                  </div>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
            <div className="w-80 p-4 overflow-y-auto space-y-4" style={{ backgroundColor: "var(--bg)" }}>
              {[
                {
                  title: "Ontology metrics",
                  data: {
                    Axiom: (metadata as any)?.axiomCount,
                    "Logical axiom": (metadata as any)?.logicalAxiomCount,
                    "Declaration axiom": (metadata as any)?.declarationAxiomCount,
                    Class: (metadata as any)?.classCount,
                    "Object property": (metadata as any)?.objectPropertyCount,
                    "Data property": (metadata as any)?.dataPropertyCount,
                    Individual: (metadata as any)?.individualCount,
                    "Annotation property": (metadata as any)?.annotationPropertyCount ?? annotationProperties.length,
                    Datatype: (metadata as any)?.datatypeCount,
                    Imports: (metadata as any)?.importsCount,
                    Prefixes: (metadata as any)?.prefixCount,
                    Triples: (metadata as any)?.tripleCount,
                  },
                },
                {
                  title: "Class axioms",
                  data: {
                    SubClassOf: (metadata as any)?.subClassOfAxiomCount,
                    EquivalentClasses: (metadata as any)?.equivalentClassesAxiomCount,
                    DisjointClasses: (metadata as any)?.disjointClassesAxiomCount,
                    "GCI count": (metadata as any)?.gciCount,
                    "Hidden GCI Count": (metadata as any)?.hiddenGciCount,
                  },
                },
                {
                  title: "Property axioms",
                  data: {
                    SubObjectPropertyOf: (metadata as any)?.subObjectPropertyOfAxiomCount,
                    InverseObjectProperties: (metadata as any)?.inverseObjectPropertiesAxiomCount,
                    "Object domain": (metadata as any)?.objectPropertyDomainAxiomCount,
                    "Object range": (metadata as any)?.objectPropertyRangeAxiomCount,
                    "Data domain": (metadata as any)?.dataPropertyDomainAxiomCount,
                    "Data range": (metadata as any)?.dataPropertyRangeAxiomCount,
                  },
                },
                {
                  title: "Assertion axioms",
                  data: {
                    "Class assertions": (metadata as any)?.classAssertionAxiomCount,
                    "Object assertions": (metadata as any)?.objectPropertyAssertionCount,
                    "Data assertions": (metadata as any)?.dataPropertyAssertionCount,
                    "Annotation assertions": (metadata as any)?.annotationAssertionCount,
                  },
                },
              ].map((metricSection) => (
                <div key={metricSection.title}>
                  <h3
                    className="font-semibold text-sm mb-2 border-b pb-1"
                    style={{ color: "var(--text-primary)", borderColor: "var(--border)" }}
                  >
                    {metricSection.title}
                  </h3>
                  <div className="space-y-1 text-xs">
                    {Object.entries(metricSection.data).map(
                      ([key, value]) =>
                        (value ?? null) !== null && (
                          <div key={key} className="flex justify-between items-center">
                            <span style={{ color: "var(--text-primary)" }}>{key}</span>
                            <span
                              className="font-bold px-1.5 py-0.5 rounded"
                              style={{ color: "var(--text-primary)", backgroundColor: "var(--surface-2)" }}
                            >
                              {Number(value).toLocaleString()}
                            </span>
                          </div>
                        ),
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      case "IndividualsByClass": {
        const filteredInstances = classInstances.filter((ind) => {
          if (!classInstancesQuery) return true;
          const query = classInstancesQuery.toLowerCase();
          return (ind.label || "").toLowerCase().includes(query) || (ind.id || "").toLowerCase().includes(query);
        });
        const directInstances = filteredInstances.filter((ind) => !ind.isInferred);
        const inferredInstances = filteredInstances.filter((ind) => ind.isInferred);
        const visibleInstances =
          classInstancesView === "direct"
            ? directInstances
            : classInstancesView === "inferred"
              ? inferredInstances
              : filteredInstances;

        return (
          <div className="flex h-full">
            <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
              <div className="p-2 border-b text-sm font-semibold text-gray-700 flex items-center justify-between">
                <span>Class hierarchy</span>
                {selectedClassForIndividuals && (
                  <span className="text-xs text-gray-500">({filteredInstances.length} instances)</span>
                )}
              </div>
              <div className="flex-1 overflow-y-auto p-1">
                <EntityHierarchy
                  entitiesTab="Classes"
                  filteredData={classHierarchy}
                  selectedItem={selectedClassForIndividuals}
                  expandedNodes={expandedNodes}
                  searchQuery={classTreeSearchQuery}
                  onSearchQueryChange={setClassTreeSearchQuery}
                  onSelectItem={(item) => setSelectedClassForIndividuals(item as TreeNode)}
                  onToggleNode={toggleNode}
                  onAddItem={() => {
                    /* not used here */
                  }}
                  onDeleteItem={() => {
                    /* not used here */
                  }}
                />
              </div>
            </aside>
            <main className="flex-1 p-2 bg-gray-50">
              <div className="border bg-white h-full flex flex-col">
                <div className="flex items-center justify-between text-xs border-b flex-shrink-0">
                  <div className="flex">
                    <button
                      onClick={() => setClassInstancesView("direct")}
                      className={`px-3 py-1.5 border-r font-semibold ${classInstancesView === "direct" ? "bg-white" : "bg-gray-100 text-gray-700 hover:bg-gray-200"}`}
                    >
                      Direct instances ({directInstances.length})
                    </button>
                    <button
                      onClick={() => setClassInstancesView("inferred")}
                      className={`px-3 py-1.5 border-r font-semibold ${classInstancesView === "inferred" ? "bg-white" : "bg-gray-100 text-gray-700 hover:bg-gray-200"}`}
                    >
                      Inferred ({inferredInstances.length})
                    </button>
                    <button
                      onClick={() => setClassInstancesView("all")}
                      className={`px-3 py-1.5 font-semibold ${classInstancesView === "all" ? "bg-white" : "bg-gray-100 text-gray-700 hover:bg-gray-200"}`}
                    >
                      All ({filteredInstances.length})
                    </button>
                  </div>
                  <div className="px-2 flex items-center gap-2">
                    <input
                      value={classInstancesQuery}
                      onChange={(e) => setClassInstancesQuery(e.target.value)}
                      placeholder="Filter individuals..."
                      className="px-2 py-1 text-xs border rounded"
                    />
                    {selectedClassForIndividuals && (
                      <button
                        onClick={async () => {
                          const name = window.prompt(`Create individual in ${selectedClassForIndividuals.label}`);
                          if (!name || !projectId) return;
                          try {
                            await ontologyMutationService.addIndividual(
                              projectId,
                              name,
                              selectedClassForIndividuals.id,
                            );
                            await loadClassInstances();
                          } catch (error) {
                            console.error("[Dashboard] Failed to create individual:", error);
                            notificationService.error("Create Failed", "Could not create individual.");
                          }
                        }}
                        className="px-2 py-1 text-xs bg-purple-600 text-white rounded hover:bg-purple-700"
                      >
                        Add
                      </button>
                    )}
                  </div>
                </div>
                {selectedClassForIndividuals ? (
                  <div className="flex-1 flex min-h-0">
                    <div className="w-1/2 border-r border-gray-200 overflow-y-auto">
                      {classInstancesLoading ? (
                        <div className="p-4 text-sm text-gray-600 italic flex items-center justify-center h-full">
                          Loading individuals for {selectedClassForIndividuals.label}...
                        </div>
                      ) : visibleInstances.length > 0 ? (
                        visibleInstances.map((ind) => (
                          <div
                            key={ind.id}
                            onClick={() => setSelectedClassIndividual(ind)}
                            className={`group flex items-center justify-between p-1.5 text-xs hover:bg-gray-100 rounded cursor-pointer ${selectedClassIndividual?.id === ind.id ? "bg-purple-50" : ""}`}
                          >
                            <div className="flex items-center">
                              <User size={12} className="mr-2 text-purple-600" />
                              {ind.label}
                              {ind.isInferred && (
                                <span className="ml-2 px-1.5 py-0.5 text-[10px] rounded bg-purple-100 text-purple-700">
                                  Inferred
                                </span>
                              )}
                            </div>
                            {!ind.isInferred && (
                              <button
                                onClick={async (e) => {
                                  e.stopPropagation();
                                  if (!projectId || !selectedClassForIndividuals) return;
                                  try {
                                    await ontologyMutationService.removeClassAssertion(
                                      projectId,
                                      ind.id,
                                      selectedClassForIndividuals.id,
                                    );
                                    await loadClassInstances();
                                  } catch (error) {
                                    console.error("[Dashboard] Failed to remove class assertion:", error);
                                    notificationService.error("Remove Failed", "Could not remove class assertion.");
                                  }
                                }}
                                className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                              >
                                Remove
                              </button>
                            )}
                          </div>
                        ))
                      ) : (
                        <div className="p-4 text-sm text-gray-600 italic flex items-center justify-center h-full">
                          No instances found for {selectedClassForIndividuals.label}.
                        </div>
                      )}
                    </div>
                    <div className="w-1/2 overflow-y-auto bg-gray-50">
                      {selectedClassIndividualLoading ? (
                        <div className="p-4 text-sm text-gray-600 italic flex items-center justify-center h-full">
                          Loading individual details...
                        </div>
                      ) : selectedClassIndividualDetails ? (
                        <div className="p-3 space-y-3 text-xs">
                          <div className="bg-white border border-gray-200 rounded p-2">
                            <div className="font-semibold text-gray-800">{selectedClassIndividualDetails.label}</div>
                            <div className="text-[11px] text-gray-500 font-mono break-all">
                              {selectedClassIndividualDetails.id}
                            </div>
                          </div>
                          <div className="bg-white border border-gray-200 rounded p-2">
                            <div className="flex items-center justify-between mb-1">
                              <div className="font-semibold text-gray-700">Types</div>
                              <button
                                onClick={() => setClassIndividualTypeDialogOpen(true)}
                                className="px-2 py-0.5 text-[10px] bg-purple-100 text-purple-700 rounded hover:bg-purple-200"
                              >
                                Add
                              </button>
                            </div>
                            {selectedClassIndividualDetails.types?.length ? (
                              <div className="space-y-1">
                                {selectedClassIndividualDetails.types.map((type) => (
                                  <div
                                    key={type}
                                    className="group flex items-center justify-between text-[11px] text-gray-600"
                                  >
                                    <span className="truncate">{getLocalName(type)}</span>
                                    <button
                                      onClick={async () => {
                                        if (!projectId || !selectedClassIndividualDetails) return;
                                        try {
                                          await ontologyMutationService.removeClassAssertion(
                                            projectId,
                                            selectedClassIndividualDetails.id,
                                            type,
                                          );
                                          if (selectedClassForIndividuals?.id === type) {
                                            await loadClassInstances();
                                          }
                                          await refreshSelectedClassIndividualDetails();
                                        } catch (error) {
                                          console.error("[Dashboard] Failed to remove type assertion:", error);
                                          notificationService.error(
                                            "Remove Failed",
                                            "Could not remove type assertion.",
                                          );
                                        }
                                      }}
                                      className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                                    >
                                      Remove
                                    </button>
                                  </div>
                                ))}
                              </div>
                            ) : (
                              <div className="text-[11px] text-gray-400">No types</div>
                            )}
                          </div>
                          <div className="bg-white border border-gray-200 rounded p-2">
                            <div className="flex items-center justify-between mb-1">
                              <div className="font-semibold text-gray-700">Annotations</div>
                              <button
                                onClick={() => setClassIndividualAnnotationDialogOpen(true)}
                                className="px-2 py-0.5 text-[10px] bg-purple-100 text-purple-700 rounded hover:bg-purple-200"
                              >
                                Add
                              </button>
                            </div>
                            {selectedClassIndividualDetails.annotations &&
                            Object.keys(selectedClassIndividualDetails.annotations).length > 0 ? (
                              <div className="space-y-1">
                                {Object.entries(selectedClassIndividualDetails.annotations).map(([key, value]) => (
                                  <div
                                    key={key}
                                    className="group flex items-center justify-between text-[11px] text-gray-600"
                                  >
                                    <span className="truncate">
                                      <span className="font-mono">{getLocalName(key) || key}</span>: {String(value)}
                                    </span>
                                    <button
                                      onClick={async () => {
                                        if (!projectId || !selectedClassIndividualDetails) return;
                                        try {
                                          await ontologyMutationService.deleteAnnotation(
                                            projectId,
                                            selectedClassIndividualDetails.id,
                                            key,
                                            String(value),
                                          );
                                          await refreshSelectedClassIndividualDetails();
                                        } catch (error) {
                                          console.error("[Dashboard] Failed to remove annotation:", error);
                                          notificationService.error("Remove Failed", "Could not remove annotation.");
                                        }
                                      }}
                                      className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                                    >
                                      Remove
                                    </button>
                                  </div>
                                ))}
                              </div>
                            ) : (
                              <div className="text-[11px] text-gray-400">No annotations</div>
                            )}
                          </div>
                          <div className="bg-white border border-gray-200 rounded p-2">
                            <div className="flex items-center justify-between mb-1">
                              <div className="font-semibold text-gray-700">Property assertions</div>
                              <div className="flex items-center gap-1">
                                <button
                                  onClick={() => {
                                    setClassIndividualPropertyIsObject(true);
                                    setClassIndividualPropertyDialogOpen(true);
                                  }}
                                  className="px-2 py-0.5 text-[10px] bg-blue-100 text-blue-700 rounded hover:bg-blue-200"
                                >
                                  Add object
                                </button>
                                <button
                                  onClick={() => {
                                    setClassIndividualPropertyIsObject(false);
                                    setClassIndividualPropertyDialogOpen(true);
                                  }}
                                  className="px-2 py-0.5 text-[10px] bg-green-100 text-green-700 rounded hover:bg-green-200"
                                >
                                  Add data
                                </button>
                              </div>
                            </div>
                            {selectedClassIndividualDetails.propertyAssertions?.length ? (
                              <div className="space-y-1">
                                {selectedClassIndividualDetails.propertyAssertions.map((assertion) => (
                                  <div
                                    key={assertion.id}
                                    className={`group flex items-center justify-between text-[11px] ${
                                      assertion.isInferred ? "text-amber-800 bg-amber-50 border border-amber-100 rounded px-1" : "text-gray-600"
                                    }`}
                                  >
                                    <span className="truncate">
                                      <span className="font-semibold">{assertion.propertyLabel}</span>
                                      {assertion.isNegative ? " (not)" : ""}:{" "}
                                      {assertion.targetLabel || assertion.targetIri || assertion.targetLiteral}
                                      {assertion.isInferred && (
                                        <span className="ml-1 text-[9px] uppercase font-semibold text-amber-700">inferred</span>
                                      )}
                                    </span>
                                    {!assertion.isInferred && (
                                    <button
                                      onClick={async () => {
                                        if (!projectId || !selectedClassIndividualDetails) return;
                                        try {
                                          if (assertion.isObjectProperty) {
                                            const target = assertion.targetIri || assertion.targetLabel;
                                            if (!target) return;
                                            if (assertion.isNegative) {
                                              await ontologyMutationService.deleteNegativeObjectPropertyAssertion(
                                                projectId,
                                                selectedClassIndividualDetails.id,
                                                assertion.propertyIri,
                                                target,
                                              );
                                            } else {
                                              await ontologyMutationService.deleteObjectPropertyAssertion(
                                                projectId,
                                                selectedClassIndividualDetails.id,
                                                assertion.propertyIri,
                                                target,
                                              );
                                            }
                                          } else {
                                            const value = assertion.targetLiteral;
                                            if (!value) return;
                                            if (assertion.isNegative) {
                                              await ontologyMutationService.deleteNegativeDataPropertyAssertion(
                                                projectId,
                                                selectedClassIndividualDetails.id,
                                                assertion.propertyIri,
                                                value,
                                              );
                                            } else {
                                              await ontologyMutationService.deleteDataPropertyAssertion(
                                                projectId,
                                                selectedClassIndividualDetails.id,
                                                assertion.propertyIri,
                                                value,
                                              );
                                            }
                                          }
                                          await refreshSelectedClassIndividualDetails();
                                        } catch (error) {
                                          console.error("[Dashboard] Failed to remove property assertion:", error);
                                          notificationService.error(
                                            "Remove Failed",
                                            "Could not remove property assertion.",
                                          );
                                        }
                                      }}
                                      className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                                    >
                                      Remove
                                    </button>
                                    )}
                                  </div>
                                ))}
                              </div>
                            ) : (
                              <div className="text-[11px] text-gray-400">No assertions</div>
                            )}
                          </div>
                        </div>
                      ) : (
                        <div className="p-4 text-sm text-gray-400 italic flex items-center justify-center h-full">
                          Select an individual to see details.
                        </div>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="p-4 text-sm text-gray-400 italic flex items-center justify-center h-full">
                    Select a class to view its instances.
                  </div>
                )}
              </div>
            </main>
          </div>
        );
      }
      case "DLQuery":
        // Built-in OWL IRIs to exclude from counts
        const builtInIris = new Set([
          "http://www.w3.org/2002/07/owl#Thing",
          "http://www.w3.org/2002/07/owl#topObjectProperty",
          "http://www.w3.org/2002/07/owl#topDataProperty",
        ]);

        // Flatten the class hierarchy tree to get all classes (excluding owl:Thing)
        const flattenClassHierarchy = (nodes: TreeNode[]): { id: string; label: string }[] => {
          const result: { id: string; label: string }[] = [];
          const traverse = (nodeList: TreeNode[]) => {
            for (const node of nodeList) {
              if (!builtInIris.has(node.id)) {
                result.push({ id: node.id, label: node.label });
              }
              if (node.children && node.children.length > 0) {
                traverse(node.children);
              }
            }
          };
          traverse(nodes);
          return result;
        };

        // Flatten the property hierarchy tree (excluding owl:topObjectProperty/topDataProperty)
        const flattenPropertyHierarchy = (nodes: Property[]): { id: string; label: string }[] => {
          const result: { id: string; label: string }[] = [];
          const traverse = (nodeList: any[]) => {
            for (const node of nodeList) {
              if (!builtInIris.has(node.id)) {
                result.push({ id: node.id, label: node.label });
              }
              if (node.children && node.children.length > 0) {
                traverse(node.children);
              }
            }
          };
          traverse(nodes);
          return result;
        };

        return (
          <DLQueryPanel
            projectId={projectId || ""}
            classHierarchy={classHierarchy}
            classes={flattenClassHierarchy(classHierarchy)}
            objectProperties={flattenPropertyHierarchy(objectPropertyHierarchy)}
            dataProperties={flattenPropertyHierarchy(dataPropertyHierarchy)}
            individuals={individuals.map((i) => ({ id: i.id, label: i.label }))}
            metrics={{
              classCount: (metadata as any)?.classCount,
              objectPropertyCount: (metadata as any)?.objectPropertyCount,
              dataPropertyCount: (metadata as any)?.dataPropertyCount,
              individualCount: (metadata as any)?.individualCount,
            }}
            apiClient={apiClient}
            onAddToOntology={async (expression, className) => {
              try {
                await ontologyMutationService.addDlQueryClass(projectId || "", expression, className, user?.email);
                showToast(`Created class "${className}"`, "success");
                await refreshClassHierarchy();
                await fetchData(projectId, false);
              } catch (e) {
                const status = (e as any)?.status ?? (e as any)?.response?.status ?? (e as any)?.data?.status;
                if (status !== 404) {
                  console.warn("DL add failed:", e);
                  showToast(`Failed to create class: ${className}`, "error");
                  return;
                }

                const normalizedExpr = (expression || "").trim();
                const byIri = normalizedExpr.startsWith("http://") || normalizedExpr.startsWith("https://");
                const target = byIri
                  ? normalizedExpr
                  : flattenClassHierarchy(classHierarchy).find(
                      (c) => c.label?.toLowerCase() === normalizedExpr.toLowerCase(),
                    )?.id;

                if (!target) {
                  showToast("Only simple class names are supported for Add to Ontology right now.", "warning");
                  return;
                }

                const normalizedClassName = (className || "").trim().replace(/\s+/g, "_");
                const base = target.includes("#")
                  ? target.split("#")[0] + "#"
                  : target.includes("/")
                    ? target.substring(0, target.lastIndexOf("/") + 1)
                    : "http://example.com/ont#";

                const newIri = base + normalizedClassName;

                try {
                  await ontologyMutationService.addDlQueryClassViaMutations(
                    projectId || "",
                    newIri,
                    className,
                    target,
                    user?.email,
                    user?.username || user?.email,
                  );
                  showToast(`Created class "${className}"`, "success");
                  await refreshClassHierarchy();
                  await fetchData(projectId, false);
                } catch (e2) {
                  console.warn("Mutation fallback failed:", e2);
                  showToast(`Failed to create class: ${className}`, "error");
                }
              }
            }}
            showNotification={(message, type) => showToast(message, type)}
          />
        );
      default:
        return <div className="p-6 text-gray-400">Select a tab</div>;
    }
  };

  return renderMainContent();
};