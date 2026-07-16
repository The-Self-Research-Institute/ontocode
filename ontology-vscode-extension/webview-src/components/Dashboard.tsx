// src/Dashboard.tsx
import React, { useState, useEffect, useCallback, useRef, useMemo } from "react";
import {
  ChevronRight,
  ChevronDown,
  Settings,
  Search,
  FileText,
  Eye,
  Database,
  Tag,
  Share2,
  List,
  Code,
  Loader2,
  Package,
  Check,
  Trash2,
  PlusCircle,
  User,
  Type,
  GitBranch,
  Binary,
  LogOut,
  Play,
  Square,
  DatabaseZap,
  Upload,
  Sparkles,
  Clock,
  Users,
  Download,
  RefreshCw,
  AlertCircle,
  Puzzle,
  Zap,
  BookOpen,
  Brain,
  Network,
  GitMerge,
  GitPullRequest,
  Palette,
  Edit2,
  Plus,
  Globe,
  Link as LinkIcon,
  Hash,
  X,
  FileCode,
  Info,
  Crown,
  Rocket,
  Bug,
  FolderOpen,
  LayoutDashboard,
  AlertTriangle,
  Monitor,
} from "lucide-react";
import apiClient, { ApiError, getBaseUrl } from "../services/apiClient";
import ontologyMutationService from "../services/ontologyMutationService";
import expressionService, { isManchesterClassExpression, isSimpleOntologyIri } from "../services/expressionService";
import undoRedoService from "../services/undoRedoService";
import { draftTrackingService } from "../services/draftTrackingService";
import { userPreferencesService } from "../services/userPreferencesService";
import { notificationService } from "../services/notificationService";
import { syncService } from "../services/syncService";
import type {
  TreeNode,
  Property,
  Individual,
  OntologyMetadata,
  SelectableItem,
  AnnotationProperty,
  Datatype,
} from "../types";
import { useAuth } from "../custom-hook/useAuth";
import { isDesktop, warmOntologyInMemory, ensureDesktopFusekiSync, scheduleSilentDesktopFusekiSync, waitForDesktopOwlApiReady, isOwlApiWarmingResponse, getOntologyListWithRetry, isDesktopFusekiSyncPending } from "../utils/desktop";
import { resolveMutationActor } from "../utils/mutationActor";
import { COLLABORATION_NAVIGATE_EVENT, resolveEntitiesTab, type CollaborationNavigateDetail } from "../utils/collaborationNavigation";
import { formatQueueWait, importStageLabel, sanitizeImportMessage } from "../utils/importStatusText";
import { extractDeclarationCountsPatch } from "./dashboard-parts/dashboardUtils";
import { normalizeRole, parseWorkspaceRole, isWorkspaceViewerRole } from "../utils/roles";
import {
  validateJsonLdSyntax,
  buildZoteroCitationNode,
  insertCitationNodeIntoJsonLd,
  removeCitationNodeFromJsonLd,
  findGraphInsertionIndex,
  DEFAULT_JSONLD_CONTEXT,
} from "../utils/jsonLdCitation";
import { useCollaboration } from "../contexts/CollaborationContext";
import { useTheme } from "../contexts/ThemeContext";
import { useSubscription } from "../hooks/useSubscription";
import EntityHierarchy from "./EntityHierarchy";
import ClassEditor from "./details/ClassEditor";
import PropertyEditor from "./details/PropertyEditor";
import IndividualEditor from "./details/IndividualEditor";
import DatatypeEditor from "./details/DatatypeEditor";
import AnnotationPropertyEditor from "./details/AnnotationPropertyEditor";
import { Panel, AnnotationsDisplay } from "./details/common";
// SparqlQueryEditor moved to plugin: sparql-query-plugin
import { ProjectSelector } from "./ProjectSelector";
import CollaborationPanel, { CollaborationPanelRef } from "./CollaborationPanel";
import HistoryPanel from "./HistoryPanel";
import ToastNotification from "./ToastNotification";
import { CollaborativeCursors } from "./CollaborativeCursor";
import ShareDialog from "./ShareDialog";
import MergeWizard from "./MergeWizard";
import { ReportIssueModal } from "./ReportIssueModal";
import { UserGuideModal } from "./UserGuideModal";
import ThemeSettings from "./ThemeSettings";
// ImportProgressToast removed per user request
import { QueueStatusIndicator, GlobalQueueStats } from "./QueueStatusIndicator";
import {
  ClassSelectorDialog,
  CreateIndividualModal,
  AddAnnotationDialog,
  AddClassDialog,
  AddObjectPropertyDialog,
  ClassExpressionDialog,
  PropertyExpressionDialog,
  IndividualSelectorDialog,
  ObjectPropertyExpressionDialog,
  AddDatatypeDialog,
  PropertyAssertionDialog,
  KeyboardShortcutsDialog,
  EntityPreferencesDialog,
  AnnotationPropertyDomainDialog,
  AnnotationPropertyRangeDialog,
  AnnotationPropertySuperpropertyDialog,
  DataPropertyRangeDialog,
  TabType,
  GCIEditorDialog,
  AddImportDialog,
  EditOntologyIRIDialog,
  EditEntityIRIDialog,
  PrefixDialog,
} from "./dialogs";
import { useKeyboardShortcuts, DEFAULT_SHORTCUTS, KeyboardShortcut } from "../hooks/useKeyboardShortcuts";
import { useDebouncedVisible } from "../hooks/useDebouncedVisible";
import { TabCountBadge } from "./dashboard-parts/TabCountBadge";
import { useEntityPreferences } from "../contexts/EntityPreferencesContext";
import { CodeHighlighter, type CodeHighlighterHandle } from "./CodeHighlighter";
import { lintOntologyContent, type LintIssue } from "../utils/ontologyLinter";
import { PluginMarketplace } from "./PluginMarketplace";
import { pluginLoader } from "../services/pluginLoader";
import { checkForPluginUpdates, clearPluginUpdateCache } from "../services/pluginUpdateChecker";
import DLQueryPanel from "./DLQueryPanel";
import CitationPickerDialog from "./CitationPickerDialog";
import ManualCitationDialog from "./ManualCitationDialog";
import {
  LoadingDialog,
  SectionLoadingBar,
  ReasonerExplanationModal,
  ReasonerSettingsDialog,
  PluginPlaceholder,
  ConfirmDialog,
  DeleteClassDialog,
  DuplicateFileDialog,
  SaveErrorDialog,
  LintProblemsPanel,
  DetailsPanel,
  type TopLevelClass,
  type FileInfo,
  findParentNode,
  DATATYPE_IRI_MAP,
  REASONER_ID_MAP,
  REASONER_OPTIONS,
  normalizeReasonerType,
  buildHierarchyTree,
  extractResponseData,
  normalizePrefixMappings,
  normalizeOntologyAnnotation,
  normalizeOntologyAnnotations,
  mapAnnotationProperty,
  buildAnnotationPropertyHierarchy,
  STANDARD_ANNOTATION_PROPERTIES,
  mergeAnnotationProperties,
  combineReasonerResults,
  showNotification,
} from "./dashboard-parts";
import { OntoCodeLogo } from "./OntoCodeLogo";
import ReleaseNotesModal from "./ReleaseNotesModal";
import DraftCopyModal from "./dialogs/DraftCopyModal";
import PRsModal from "./PRsModal";
import DraftPRPanel from "./DraftPRPanel";
import PullPreviewDialog from "./PullPreviewDialog";

const TopMenuBar = ({
  fileList,
  myFiles,
  sharedFiles,
  currentProjectId,
  onShareFile,
  onSave,
  onSwitchFile,
  hasUnsavedChanges,
  isSaving,
  draftCount,
  conflictStatus,
  onOpenDialog,
  onOpenPluginMarketplace,
  hasPluginUpdates,
  onOpenHistory,
  onReportIssue,
  onOpenUserGuide,
  onOpenReleaseNotes,
  onOpenMergeWizard,
  syncMode,
  onToggleSyncMode,
  requireDraftForMembers,
  isProjectOwner,
  isDraftEditorRole,
  autoDraftStatus,
  onToggleRequireDraftForMembers,
  onSwitchToDraftMode,
  isReasonerRunning,
  isReasonerLoading,
  isReasonerSynced,
  selectedReasoner,
  onStartReasoner,
  onStopReasoner,
  onToggleReasonerSync,
  onSelectReasoner,
  onCheckConsistency,
  onExplainInconsistency,
  onOpenReasonerSettings,
  isConsistencyLoading,
  onGoToProjectDashboard,
  onGoToWorkspace,
  onOpenThemeSettings,
  subscription,
  onExportProAction,
  isViewOnly,
  hierarchyDisplayMode,
  onHierarchyDisplayModeChange,
  hierarchyImportsScope,
  onHierarchyImportsScopeChange,
  hierarchyAnnotationProperties,
  hierarchyAnnotationPropIri,
  onHierarchyAnnotationPropChange,
  hierarchyCustomTemplate,
  onHierarchyCustomTemplateChange,
}: {
  fileList: FileInfo[];
  myFiles: FileInfo[];
  sharedFiles: FileInfo[];
  currentProjectId: string | null;
  onShareFile: (fileId: string) => void;
  onSave: () => Promise<void>;
  onSwitchFile: (projectId: string) => void;
  hasUnsavedChanges: boolean;
  isSaving: boolean;
  draftCount?: number;
  conflictStatus?: 'idle' | 'checking' | 'clean' | 'conflict';
  onOpenDialog: () => void;
  onOpenPluginMarketplace: () => void;
  hasPluginUpdates?: boolean;
  onOpenHistory: () => void;
  onReportIssue: () => void;
  onOpenUserGuide: () => void;
  onOpenReleaseNotes: () => void;
  onOpenMergeWizard: () => void;
  syncMode: "private" | "public";
  onToggleSyncMode: () => void;
  requireDraftForMembers?: boolean;
  isProjectOwner?: boolean;
  isDraftEditorRole?: boolean;
  autoDraftStatus?: 'idle' | 'copying' | 'ready';
  onToggleRequireDraftForMembers?: () => void;
  onSwitchToDraftMode?: () => void;
  isReasonerRunning?: boolean;
  isReasonerLoading?: boolean;
  isReasonerSynced?: boolean;
  selectedReasoner?: string;
  onStartReasoner?: () => Promise<void>;
  onStopReasoner?: () => void;
  onToggleReasonerSync?: () => void;
  onSelectReasoner?: (reasoner: string) => void;
  onCheckConsistency?: () => void;
  onExplainInconsistency?: () => void;
  onOpenReasonerSettings?: () => void;
  isConsistencyLoading?: boolean;
  onGoToProjectDashboard?: () => void;
  onGoToWorkspace?: () => void;
  onOpenThemeSettings?: () => void;
  subscription?: any;
  onExportProAction?: () => void;
  isViewOnly?: boolean;
  hierarchyDisplayMode?: "label" | "id" | "annotation" | "custom";
  onHierarchyDisplayModeChange?: (mode: "label" | "id" | "annotation" | "custom") => void;
  hierarchyImportsScope?: "active" | "closure";
  onHierarchyImportsScopeChange?: (scope: "active" | "closure") => void;
  hierarchyAnnotationProperties?: Array<{ id: string; label: string }>;
  hierarchyAnnotationPropIri?: string;
  onHierarchyAnnotationPropChange?: (iri: string) => void;
  hierarchyCustomTemplate?: string;
  onHierarchyCustomTemplateChange?: (tpl: string) => void;
}) => {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [annotationSubmenuOpen, setAnnotationSubmenuOpen] = useState(false);
  const [customTemplateEditing, setCustomTemplateEditing] = useState(false);
  const [customTemplateDraft, setCustomTemplateDraft] = useState(hierarchyCustomTemplate ?? "{label} ({id})");
  const [searchFile, setSearchFile] = useState("");
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [showExportFormats, setShowExportFormats] = useState(false);
  const [exportingFormat, setExportingFormat] = useState<string | null>(null);
  // Display-only (Help menu "Version …"). The auto-open-release-notes logic
  // lives in the Dashboard component, which owns the modal state.
  const [appVersion, setAppVersion] = useState("");

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    import("../utils/appVersion").then(({ getAppVersion }) => {
      getAppVersion().then((v) => setAppVersion(v || "")).catch(() => setAppVersion(""));
    });
  }, []);

  const onSearchFileChange = (value: string) => {
    setSearchFile(value);

    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    timeoutRef.current = setTimeout(async () => {
      setIsLoading(true);
      try {
        // Build URL with proper query parameters
        const url = `/api/ontology/files?search=${encodeURIComponent(value)}&caseSensitive=true`;
        const response = await apiClient.get<{
          data: any;
          files: FileInfo[];
        }>(url);
        const files = response?.files || response?.data?.files || [];
        setFiles(files);
      } catch (error) {
        console.error("Failed to fetch data:", error);
      } finally {
        setIsLoading(false);
      }
    }, 1000);
  };

  useEffect(() => {
    setFiles(fileList);
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpenMenu(null);
        setShowExportFormats(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [fileList]);

  const displayedFiles = searchFile ? files : fileList;
  const menuItems = ["File", "Edit", "View", "Reasoner", "Tools", "Window", "Help"];

  return (
    <header
      ref={menuRef}
      className="ontocode-top-menu text-xs flex items-center px-1 sm:px-2 relative border-b h-8 flex-shrink-0 min-w-0"
      style={{
        backgroundColor: "var(--color-background)",
        color: "var(--color-text)",
        borderBottomColor: "var(--color-border)",
      }}
    >
      <div className="flex items-center gap-1 p-1 sm:p-2 mr-1 sm:mr-2 flex-shrink-0">
        <OntoCodeLogo size={20} />
      </div>
      <div className="flex items-center">
        {menuItems.map((item) => (
          <div key={item} className="relative flex-shrink-0">
            <button
              onClick={() => {
                const next = openMenu === item ? null : item;
                setOpenMenu(next);
                if (!next || next !== "View") { setAnnotationSubmenuOpen(false); setCustomTemplateEditing(false); }
              }}
              className={`ontocode-top-menu-button cursor-pointer disabled:cursor-not-allowed px-2 sm:px-3 py-1 rounded-sm transition-colors relative whitespace-nowrap ${openMenu === item ? "is-open" : ""}`}
            >
              {item}
              {item === "Tools" && hasPluginUpdates && (
                <span
                  className="absolute top-0.5 right-0.5 w-1.5 h-1.5 bg-red-500 rounded-full animate-pulse"
                  title="Plugin updates available"
                />
              )}
              {/* {item === 'Reasoner' && isReasonerRunning && (
                <span className="w-2 h-2 bg-green-500 rounded-full animate-pulse"></span>
              )}
              {item === 'Reasoner' && !isReasonerRunning && (
                <span className="text-[10px] text-gray-500">({selectedReasoner})</span>
              )} */}
            </button>
            {openMenu === item && (
              <div
                className={`ontocode-top-menu-dropdown absolute left-0 mt-1 ${item === "File" ? "w-[min(360px,calc(100vw-1rem))]" : "w-48 max-w-[calc(100vw-1rem)]"} bg-theme-surface border rounded-lg shadow-xl z-20 overflow-hidden`}
                style={{ borderColor: "var(--color-border)" }}
              >
                {item === "Tools" ? (
                  <div className="py-1">
                    <button
                      onClick={() => {
                        onOpenPluginMarketplace();
                        setOpenMenu(null);
                      }}
                      className="ontocode-top-menu-item cursor-pointer disabled:cursor-not-allowed w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <Package size={14} />
                      Plugin Marketplace
                      {hasPluginUpdates && (
                        <span
                          className="ml-auto w-1.5 h-1.5 bg-red-500 rounded-full animate-pulse"
                          title="Updates available"
                        />
                      )}
                    </button>
                  </div>
                ) : item === "View" ? (
                  <div className="py-1">
                    <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wide opacity-50">Rendering</div>
                    <button
                      onClick={() => { onHierarchyDisplayModeChange?.("label"); setOpenMenu(null); }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyDisplayMode === "label" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Render by label
                      <span className="ml-1 opacity-40 text-[10px]">(rdfs:label)</span>
                    </button>
                    <button
                      onClick={() => { onHierarchyDisplayModeChange?.("id"); setOpenMenu(null); }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyDisplayMode === "id" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Render by ID
                      <span className="ml-1 opacity-40 text-[10px]">(local name from IRI)</span>
                    </button>
                    <button
                      onClick={() => setAnnotationSubmenuOpen(v => !v)}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyDisplayMode === "annotation" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Render by annotation property
                      <span className="ml-auto opacity-50 text-[10px]">{annotationSubmenuOpen ? "▾" : "▸"}</span>
                    </button>
                    {annotationSubmenuOpen && (
                      <div className="mx-2 mb-1 border rounded overflow-hidden" style={{ borderColor: "var(--color-border)" }}>
                        {(hierarchyAnnotationProperties ?? []).length === 0 ? (
                          <div className="px-3 py-2 text-[10px] opacity-50 italic">No annotation properties found</div>
                        ) : (
                          (hierarchyAnnotationProperties ?? []).map(ap => (
                            <button
                              key={ap.id}
                              onClick={() => {
                                onHierarchyDisplayModeChange?.("annotation");
                                onHierarchyAnnotationPropChange?.(ap.id);
                                setAnnotationSubmenuOpen(false);
                                setOpenMenu(null);
                              }}
                              className={`ontocode-top-menu-item cursor-pointer w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 ${hierarchyDisplayMode === "annotation" && hierarchyAnnotationPropIri === ap.id ? "font-semibold" : ""}`}
                            >
                              <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyDisplayMode === "annotation" && hierarchyAnnotationPropIri === ap.id ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                              <span className="truncate">{ap.label || ap.id.split(/[#/]/).pop()}</span>
                            </button>
                          ))
                        )}
                      </div>
                    )}
                    <button
                      onClick={() => { onHierarchyDisplayModeChange?.("custom"); setCustomTemplateEditing(true); setCustomTemplateDraft(hierarchyCustomTemplate ?? "{label} ({id})"); }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyDisplayMode === "custom" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Custom rendering...
                    </button>
                    {customTemplateEditing && (
                      <div className="mx-2 mb-2 p-2 border rounded" style={{ borderColor: "var(--color-border)" }}>
                        <div className="text-[10px] opacity-60 mb-1">Template — use <code>{"{label}"}</code> <code>{"{id}"}</code> <code>{"{iri}"}</code></div>
                        <div className="flex gap-1">
                          <input
                            autoFocus
                            value={customTemplateDraft}
                            onChange={e => setCustomTemplateDraft(e.target.value)}
                            onKeyDown={e => {
                              if (e.key === "Enter" && customTemplateDraft.trim()) {
                                onHierarchyCustomTemplateChange?.(customTemplateDraft);
                                setCustomTemplateEditing(false);
                                setOpenMenu(null);
                              }
                              if (e.key === "Escape") setCustomTemplateEditing(false);
                            }}
                            className="flex-1 text-[10px] border rounded px-1.5 py-1 bg-theme-surface"
                            style={{ borderColor: "var(--color-border)" }}
                            placeholder="{label} ({id})"
                          />
                          <button
                            onClick={() => {
                              if (customTemplateDraft.trim()) {
                                onHierarchyCustomTemplateChange?.(customTemplateDraft);
                                setCustomTemplateEditing(false);
                                setOpenMenu(null);
                              }
                            }}
                            className="px-2 py-1 text-[10px] bg-purple-600 text-white rounded"
                          >Apply</button>
                        </div>
                      </div>
                    )}
                    <div className="border-t my-1" style={{ borderColor: "var(--color-border)" }} />
                    <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wide opacity-50">Ontology Scope</div>
                    <button
                      onClick={() => { onHierarchyImportsScopeChange?.("closure"); setOpenMenu(null); }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyImportsScope === "closure" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Show imports closure
                    </button>
                    <button
                      onClick={() => { onHierarchyImportsScopeChange?.("active"); setOpenMenu(null); }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <span className={`w-3 h-3 rounded-full border flex-shrink-0 ${hierarchyImportsScope === "active" ? "bg-purple-600 border-purple-600" : "border-gray-400"}`} />
                      Show only active ontology
                    </button>
                  </div>
                ) : item === "Window" ? (
                  <div className="py-1">
                    {onGoToProjectDashboard && (
                      <button
                        onClick={() => {
                          onGoToProjectDashboard();
                          setOpenMenu(null);
                        }}
                        className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                      >
                        <FolderOpen size={14} />
                        Project Dashboard
                      </button>
                    )}
                    {onGoToWorkspace ? (
                      <button
                        onClick={() => {
                          onGoToWorkspace();
                          setOpenMenu(null);
                        }}
                        className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                      >
                        <LayoutDashboard size={14} />
                        Workspace Selection
                      </button>
                    ) : isDesktop() ? (
                      <div className="w-full text-left px-4 py-2 text-xs flex items-center gap-2 opacity-40 cursor-not-allowed select-none">
                        <LayoutDashboard size={14} />
                        <span>Workspace Selection</span>
                        <span className="ml-auto text-[10px] italic">webapp only</span>
                      </div>
                    ) : null}
                    {(onGoToProjectDashboard || onGoToWorkspace || isDesktop()) && (
                      <div className="border-t my-1" style={{ borderColor: "var(--color-border)" }} />
                    )}
                    {isDesktop() && (
                      <>
                        <div className="border-t my-1" style={{ borderColor: "var(--color-border)" }} />
                        <button
                          onClick={() => {
                            window.vscode?.postMessage({ type: 'toggleDevTools' });
                            setOpenMenu(null);
                          }}
                          className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                        >
                          <Code size={14} />
                          Toggle Developer Tools
                        </button>
                      </>
                    )}
                    <button
                      onClick={() => {
                        onOpenThemeSettings?.();
                        setOpenMenu(null);
                      }}
                      className="ontocode-top-menu-item cursor-pointer w-full text-left px-4 py-2 text-xs flex items-center gap-2"
                    >
                      <Palette size={14} />
                      Appearance
                    </button>
                  </div>
                ) : // : item === "Reasoner" ? (
                // <div className="py-1">
                //   <button
                //     onClick={async () => {
                //       setOpenMenu(null);
                //       if (onStartReasoner) await onStartReasoner();
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs flex items-center gap-2 ${
                //       isReasonerRunning || isReasonerLoading
                //         ? 'text-gray-400 cursor-not-allowed'
                //         : 'hover:bg-gray-100'
                //     }`}
                //     disabled={isReasonerRunning || isReasonerLoading}
                //   >
                //     {isReasonerLoading ? <Loader2 size={12} className="animate-spin" /> : null}
                //     Start reasoner
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onCheckConsistency) onCheckConsistency();
                //     }}
                //     className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                //     disabled={isReasonerLoading || isConsistencyLoading}
                //   >
                //     {isConsistencyLoading ? <Loader2 size={12} className="animate-spin" /> : null}
                //     Check consistency
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onToggleReasonerSync) onToggleReasonerSync();
                //     }}
                //     className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                //   >
                //     <input type="checkbox" checked={isReasonerSynced} readOnly className="pointer-events-none" />
                //     Synchronize reasoner
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onStopReasoner) onStopReasoner();
                //     }}
                //     className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100"
                //     disabled={!isReasonerRunning}
                //   >
                //     Stop reasoner
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onExplainInconsistency) onExplainInconsistency();
                //     }}
                //     className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                //     disabled={isReasonerLoading}
                //   >
                //     Explain inconsistent ontology
                //   </button>
                //   <div className="border-t border-gray-200 my-1"></div>
                //   <button
                //     onClick={() => {
                //       // Configure reasoner preferences
                //       setOpenMenu(null);
                //       if (onOpenReasonerSettings) onOpenReasonerSettings();
                //     }}
                //     className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100"
                //   >
                //     Configure...
                //   </button>
                //   <div className="border-t border-gray-200 my-1"></div>
                //   <div className="px-4 py-1 text-[11px] text-gray-500 font-semibold">Select Reasoner:</div>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onSelectReasoner) onSelectReasoner('HermiT');
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2 ${
                //       selectedReasoner === 'HermiT' ? 'bg-blue-50 font-semibold' : ''
                //     }`}
                //   >
                //     {selectedReasoner === 'HermiT' ? '• ' : '  '}HermiT 1.4.5.519
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onSelectReasoner) onSelectReasoner('ELK');
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2 ${
                //       selectedReasoner === 'ELK' ? 'bg-blue-50 font-semibold' : ''
                //     }`}
                //   >
                //     {selectedReasoner === 'ELK' ? '• ' : '  '}ELK 0.4.3
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onSelectReasoner) onSelectReasoner('Pellet');
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs hover:bg-gray-100 ${
                //       selectedReasoner === 'Pellet' ? 'bg-blue-50 font-semibold' : ''
                //     }`}
                //   >
                //     {selectedReasoner === 'Pellet' ? '• ' : '  '}Pellet
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onSelectReasoner) onSelectReasoner('Openllet');
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs hover:bg-gray-100 ${
                //       selectedReasoner === 'Openllet' ? 'bg-blue-50 font-semibold' : ''
                //     }`}
                //   >
                //     {selectedReasoner === 'Openllet' ? '• ' : '  '}Openllet 2.6.5
                //   </button>
                //   <button
                //     onClick={() => {
                //       setOpenMenu(null);
                //       if (onSelectReasoner) onSelectReasoner('Structural');
                //     }}
                //     className={`w-full text-left px-4 py-2 text-xs hover:bg-gray-100 ${
                //       selectedReasoner === 'Structural' ? 'bg-blue-50 font-semibold' : ''
                //     }`}
                //   >
                //     {selectedReasoner === 'Structural' ? '• ' : '  '}Structural Reasoner
                //   </button>
                // </div>
                // )
                item === "Help" ? (
                  <div className="py-1">
                    {localStorage.getItem("deploymentType") !== "self-hosted" && (
                      <button
                        onClick={() => {
                          onOpenUserGuide();
                          setOpenMenu(null);
                        }}
                        className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                      >
                        <BookOpen size={14} />
                        User Guide
                      </button>
                    )}
                    <button
                      onClick={() => {
                        onReportIssue();
                        setOpenMenu(null);
                      }}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                    >
                      <Bug size={14} />
                      Report Issue
                    </button>
                    <div className="border-t my-1" style={{ borderColor: "var(--color-border)" }} />
                    <button
                      onClick={() => {
                        onOpenReleaseNotes();
                        setOpenMenu(null);
                      }}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                      title="View release notes"
                    >
                      <Info size={14} />
                      Version {appVersion || "…"}
                    </button>
                  </div>
                ) : item === "File" ? (
                  <div className="flex flex-col py-1">
                    <div className="px-4 py-2 text-[11px] uppercase tracking-wide text-gray-500">File</div>
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        onOpenDialog();
                        setOpenMenu(null);
                      }}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100"
                    >
                      Open
                    </button>
                    <div className="border-t border-gray-100 my-1" />
                    <button
                      onClick={async (e) => {
                        e.preventDefault();
                        await onSave();
                        setOpenMenu(null);
                      }}
                      disabled={!hasUnsavedChanges || isSaving || !currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                    >
                      Save {draftCount && draftCount > 0 ? `(${draftCount})` : ""}
                      {hasUnsavedChanges && <span className="text-orange-600 text-lg leading-none">•</span>}
                      {hasUnsavedChanges && conflictStatus === 'checking' && (
                        <span className="ml-auto text-[10px] text-gray-400 italic">checking…</span>
                      )}
                      {hasUnsavedChanges && conflictStatus === 'clean' && (
                        <span className="ml-auto text-[10px] text-green-600 font-medium">✓ No conflicts</span>
                      )}
                      {hasUnsavedChanges && conflictStatus === 'conflict' && (
                        <span className="ml-auto text-[10px] text-red-600 font-medium">⚠ Conflicts</span>
                      )}
                    </button>
                    {/* Export As submenu */}
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (currentProjectId && !isViewOnly) setShowExportFormats((v) => !v);
                        if (isViewOnly) onExportProAction?.();
                      }}
                      disabled={!currentProjectId}
                      title={isViewOnly ? "Viewers cannot export files" : undefined}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-between"
                    >
                      <span className="flex items-center gap-2">
                        <Download size={14} />
                        Export As…
                      </span>
                      {showExportFormats ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                    </button>
                    {showExportFormats && currentProjectId && (
                      <div className="pl-4 pb-1">
                        {([
                          { label: "RDF/XML (.owl)", format: "rdfxml", ext: "owl" },
                          { label: "Turtle (.ttl)", format: "turtle", ext: "ttl" },
                          { label: "JSON-LD (.jsonld)", format: "jsonld", ext: "jsonld" },
                          { label: "OWL/XML (.owlxml)", format: "owlxml", ext: "owlxml" },
                          { label: "Manchester (.omn)", format: "manchester", ext: "omn" },
                          { label: "Functional (.ofn)", format: "functional", ext: "ofn" },
                          { label: "OBO Flatfile (.obo)", format: "obo", ext: "obo" },
                        ] as { label: string; format: string; ext: string }[]).map(({ label, format, ext }) => (
                          <button
                            key={format}
                            disabled={exportingFormat === format}
                            onClick={async (e) => {
                              e.preventDefault();
                              if (!currentProjectId) return;
                              // Gate at the master export key first; multi-format
                              // is implied by hasExport on paid tiers but we
                              // keep both keys explicit so future tiers can
                              // diverge (e.g. a Starter plan with TTL-only).
                              if (isViewOnly || !subscription.canAccessFeature('hasExport')
                                  || !subscription.canAccessFeature('hasMultipleExportFormats')) {
                                onExportProAction?.();
                                return;
                              }
                              setExportingFormat(format);
                              const filename = `${currentProjectId}.${ext}`;
                              const url = `${getBaseUrl()}/api/ontology/export/${encodeURIComponent(currentProjectId)}?format=${format}`;
                              try {
                                if (window.vscode) {
                                  window.vscode.postMessage({ type: "downloadOntology", url, filename, projectId: currentProjectId, format });
                                  notificationService.success("Export Started", `Downloading ${filename}`);
                                } else {
                                  const res = await fetch(url, {
                                    headers: { Authorization: `Bearer ${localStorage.getItem("authToken") ?? ""}` },
                                  });
                                  if (!res.ok) throw new Error(`Export failed: HTTP ${res.status}`);
                                  const blob = await res.blob();
                                  const blobUrl = URL.createObjectURL(blob);
                                  const a = document.createElement("a");
                                  a.href = blobUrl;
                                  a.download = filename;
                                  a.click();
                                  URL.revokeObjectURL(blobUrl);
                                  notificationService.success("Export Complete", `${filename} downloaded`);
                                }
                              } catch (err: any) {
                                console.error("Export failed:", err);
                                notificationService.error("Export Failed", err.message || "Could not export ontology");
                              } finally {
                                setExportingFormat(null);
                                setShowExportFormats(false);
                                setOpenMenu(null);
                              }
                            }}
                            className="w-full text-left px-3 py-1.5 text-xs hover:bg-gray-100 disabled:opacity-50 flex items-center gap-2"
                          >
                            {exportingFormat === format ? (
                              <Loader2 size={12} className="animate-spin" />
                            ) : (
                              <FileCode size={12} />
                            )}
                            {label}
                          </button>
                        ))}
                      </div>
                    )}
                    <div className="border-t border-gray-100 my-1" />
                    {/* <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (currentProjectId) {
                          onShareFile(currentProjectId);
                        } else if (window.vscode) {
                          window.vscode.postMessage({
                            type: "error",
                            value: "No ontology loaded. Please open a file first.",
                          });
                        }
                        setOpenMenu(null);
                      }}
                      disabled={!currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      Share
                    </button> */}
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (isViewOnly) {
                          onExportProAction?.();
                        } else if (currentProjectId) {
                          onOpenMergeWizard();
                        } else if (window.vscode) {
                          window.vscode.postMessage({
                            type: "error",
                            value: "No ontology loaded. Please open a file first.",
                          });
                        }
                        setOpenMenu(null);
                      }}
                      disabled={!currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                    >
                      <GitMerge size={14} />
                      Merge Ontologies
                    </button>
                    {/* <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (currentProjectId) {
                          onOpenHistory();
                        } else if (window.vscode) {
                          window.vscode.postMessage({
                            type: 'error',
                            value: 'No ontology loaded. Please open a file first.'
                          });
                        }
                        setOpenMenu(null);
                      }}
                      disabled={!currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                    >
                      <Clock size={14} />
                      History
                    </button> */}
                  </div>
                ) : (
                  <div className="p-2 text-xs text-gray-400">No actions available</div>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="flex items-center ml-auto mr-1 sm:mr-4 gap-1 sm:gap-2 flex-shrink-0 pl-1">
        {isDesktop() ? (
          <span className="hidden sm:inline text-xs font-medium text-gray-500" title="Edits are kept in your private draft until you click Save">
            Private (Draft)
          </span>
        ) : (
          <>
        {autoDraftStatus === 'copying' && (
          <span className="hidden sm:inline text-xs text-blue-500 italic animate-pulse" title="Setting up your private draft workspace…">
            Setting up draft…
          </span>
        )}
        {autoDraftStatus === 'ready' && (
          <span className="hidden sm:inline text-xs text-green-600 font-medium">
            Draft ready
          </span>
        )}
        <span className={`hidden sm:inline text-xs font-medium ${
          (requireDraftForMembers && !isProjectOwner || isDraftEditorRole) && syncMode === 'public'
            ? "text-amber-600"
            : syncMode === "public" ? "text-green-600" : "text-gray-500"
        }`}>
          {(requireDraftForMembers && !isProjectOwner) || isDraftEditorRole
            ? (syncMode === 'public' ? "Public (View Only)" : "Draft Mode")
            : syncMode === "public" ? "Public (Live)" : "Private (Draft)"}
        </span>
        {((requireDraftForMembers && !isProjectOwner) || isDraftEditorRole) && syncMode === 'public' ? (
          // Draft-only member in view-only mode: offer explicit switch to draft
          <button
            onClick={onSwitchToDraftMode}
            className="ml-1 flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-medium border border-purple-300 bg-purple-50 text-purple-700 hover:bg-purple-100 transition-colors"
            title="Start your private draft copy to make edits"
          >
            Switch to Draft Mode
          </button>
        ) : ((requireDraftForMembers && !isProjectOwner) || isDraftEditorRole) && syncMode === 'private' ? (
          // Draft-only member in draft mode: allow switching back to view-only public
          <button
            onClick={onToggleSyncMode}
            className="relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 bg-gray-300"
            title="Switch back to Public view-only mode"
          >
            <span className="inline-block h-3 w-3 transform rounded-full bg-white transition-transform translate-x-1" />
          </button>
        ) : (
          // Owner or non-requireDraft project: show the normal toggle
          <button
            onClick={onToggleSyncMode}
            className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 ${
              syncMode === "public" ? "bg-green-500" : "bg-gray-300"
            }`}
            title={syncMode === "public" ? "Switch to Draft Mode" : "View Public (draft preserved)"}
          >
            <span
              className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
                syncMode === "public" ? "translate-x-5" : "translate-x-1"
              }`}
            />
          </button>
        )}
        {isProjectOwner && onToggleRequireDraftForMembers && (
          <button
            onClick={onToggleRequireDraftForMembers}
            className={`ml-1 flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium border transition-colors ${
              requireDraftForMembers
                ? 'bg-amber-50 border-amber-300 text-amber-700 hover:bg-amber-100'
                : 'bg-gray-50 border-gray-200 text-gray-500 hover:bg-gray-100'
            }`}
            title={requireDraftForMembers
              ? 'Members are locked to Draft Mode — click to allow public editing'
              : 'Allow members to edit publicly — click to require Draft Mode for members'}
          >
            {requireDraftForMembers ? '🔒 Draft required' : 'Allow public'}
          </button>
        )}
          </>
        )}
      </div>
    </header>
  );
};

const OpenFileDialog = ({
  isOpen,
  onClose,
  myFiles,
  sharedFiles,
  currentProjectId,
  currentFileId,
  currentFileName,
  onDeleteFile,
  onSwitchFile,
  parentProjectId,
  onLoadProjectFile,
  projectFiles,
  importMode,
  partitionStrategy,
  onImportModeChange,
  onPartitionStrategyChange,
  isWorkspaceMode,
  onRefresh,
  onCreateNewFile,
  isPlanExpired,
}: {
  isOpen: boolean;
  onClose: () => void;
  myFiles: FileInfo[];
  sharedFiles: FileInfo[];
  currentProjectId: string | null;
  currentFileId?: string | null;
  currentFileName?: string | null;
  onDeleteFile?: (projectId: string, fileName: string) => void;
  onSwitchFile: (projectId: string) => void;
  parentProjectId?: string;
  onLoadProjectFile?: (fileId: string, fileName: string) => void;
  projectFiles?: FileInfo[];
  importMode: "full" | "incremental" | "diff";
  partitionStrategy: "none" | "namespace";
  onImportModeChange: (mode: "full" | "incremental" | "diff") => void;
  onPartitionStrategyChange: (strategy: "none" | "namespace") => void;
  isWorkspaceMode?: boolean;
  onRefresh?: () => void;
  onCreateNewFile?: () => void;
  isPlanExpired?: boolean;
}) => {
  const [searchQuery, setSearchQuery] = useState("");
  const canOpenLocalFile = typeof window !== "undefined" && !!(window as any).vscode;
  const usingProjectFiles = !!parentProjectId;

  // Backend now filters to only return files (not projects), so just pass through
  const primaryFiles = usingProjectFiles ? projectFiles || [] : myFiles;
  const secondaryFiles = usingProjectFiles ? [] : sharedFiles;

  // Track when projectFiles prop changes
  useEffect(() => {
    if (usingProjectFiles) {
      console.log("[OpenFileDialog] 🔄 projectFiles prop changed:", {
        count: projectFiles?.length || 0,
        files: projectFiles?.map((f) => f.filename),
      });
    }
  }, [projectFiles, usingProjectFiles]);

  const handleOpenLocalFile = () => {
    if (!canOpenLocalFile || !window.vscode) {
      return;
    }
    window.vscode.postMessage({
      type: "openLocalFile",
      projectId: parentProjectId || undefined,
      importMode,
      partition: partitionStrategy,
    });
    onClose();
  };

  const handleCreateNewFile = async () => {
    if (isDesktop() && parentProjectId) {
      const fileName = window.prompt("Enter filename for new ontology:", "my-ontology.owl");
      if (!fileName?.trim()) return;
      const trimmed = fileName.trim();
      const validExtensions = [".owl", ".rdf", ".ttl", ".n3", ".nt", ".jsonld"];
      if (!validExtensions.some((ext) => trimmed.toLowerCase().endsWith(ext))) {
        alert("File must have a valid extension: .owl, .rdf, .ttl, .n3, .nt, or .jsonld");
        return;
      }
      const ontologyIRI = `http://example.org/ontologies/${trimmed.replace(/\.[^/.]+$/, "")}`;
      const content = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;
      const file = new File([content], trimmed, { type: "application/rdf+xml" });
      const formData = new FormData();
      formData.append("file", file, trimmed);
      formData.append("fileName", trimmed);
      formData.append("fileType", "application/rdf+xml");
      let uploadedFileId: string | undefined;
      try {
        const uploadResult = await apiClient.post<{ fileId?: string; filename?: string }>(
          `/api/projects/${parentProjectId}/files`,
          formData,
          { headers: { "Content-Type": "multipart/form-data" } },
        );
        uploadedFileId = uploadResult?.fileId;
      } catch (error: any) {
        console.error("[OpenFileDialog] Failed to create new file:", error);
        notificationService.error(
          "Create File Failed",
          error?.response?.data?.error || error?.message || "Could not create the new file. See console for details.",
        );
        return;
      }
      onCreateNewFile?.();
      // Load the new file directly instead of waiting for the "fileReady" WebSocket
      // message — on Desktop that signal isn't always delivered promptly (or at all),
      // which previously left the newly created file sitting in the project unopened
      // with no visible error. We already have its id from the upload response, so
      // open it the same way clicking an existing file in this list does.
      if (uploadedFileId && onLoadProjectFile) {
        onLoadProjectFile(uploadedFileId, trimmed);
      }
      onClose();
      return;
    }
    if (isDesktop()) {
      // Desktop without a project — use native save dialog to pick location
      const baseName = "my-ontology.owl";
      const ontologyIRI = `http://example.org/ontologies/my-ontology`;
      const content = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;
      const api = (window as any).electronAPI;
      if (!api?.saveAs) return;
      const savedPath = await api.saveAs(content, baseName);
      if (!savedPath) return;
      const fileName = savedPath.split(/[\\/]/).pop() || baseName;
      const fileContent = content;
      window.dispatchEvent(new CustomEvent("electron:file-opened", {
        detail: { fileName, fileContent, filePath: savedPath, fileSize: fileContent.length }
      }));
      onCreateNewFile?.();
      onClose();
      return;
    }
    if (!canOpenLocalFile || !window.vscode) {
      return;
    }

    onCreateNewFile?.();
    window.vscode.postMessage({
      type: "createNewFile",
      projectId: parentProjectId || undefined,
      importMode,
      partition: partitionStrategy,
    });
    onClose();
  };

  // console.log('[OpenFileDialog] Rendered with myFiles:', myFiles.length, 'sharedFiles:', sharedFiles.length, 'isOpen:', isOpen);
  // console.log('[OpenFileDialog] myFiles data:', myFiles);
  // console.log('[OpenFileDialog] sharedFiles data:', sharedFiles);

  if (!isOpen) return null;

  const allFiles = [...primaryFiles, ...secondaryFiles];
  const filteredFiles = searchQuery
    ? allFiles.filter((f) => f.filename.toLowerCase().includes(searchQuery.toLowerCase()))
    : allFiles;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-theme-surface rounded-lg shadow-2xl w-full max-w-md mx-4 max-h-[70vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-4 border-b" style={{ borderColor: "var(--color-border)" }}>
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-sm font-medium" style={{ color: "var(--color-text)" }}>
              {usingProjectFiles ? `Project Files (${filteredFiles.length})` : "Open File"}
            </h3>
          </div>
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search files..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                autoFocus
                className="w-full pl-10 pr-3 py-2 border rounded-lg focus:ring-2 text-sm"
                style={
                  {
                    borderColor: "var(--color-border)",
                    backgroundColor: "var(--color-surface)",
                    color: "var(--color-text)",
                    "--tw-ring-color": "var(--color-primary)",
                  } as React.CSSProperties
                }
              />
            </div>
            {usingProjectFiles && onRefresh && (
              <button
                onClick={() => {
                  console.log("[OpenFileDialog] 🔄 Manual refresh clicked");
                  onRefresh();
                }}
                className="p-2 rounded-md border hover:bg-gray-50 transition-colors"
                style={{
                  borderColor: "var(--color-border)",
                  color: "var(--color-text)",
                }}
                title="Refresh file list"
              >
                <RefreshCw size={16} />
              </button>
            )}
          </div>
        </div>
        {isPlanExpired && (
          <div className="mx-3 mt-3 px-3 py-2 rounded-lg border border-red-400/30 bg-red-500/10 flex items-center gap-2 text-xs text-red-400">
            <AlertTriangle size={13} className="flex-shrink-0" />
            <span>Plan validity has ended. Please renew your subscription to open files.</span>
          </div>
        )}
        <div className="flex-1 overflow-y-auto">
          {filteredFiles.length > 0 ? (
            <div className="p-3">
              <div className="space-y-0.5">
                {filteredFiles.map((file) => {
                  const fileProjectId =
                    file.projectId || file.id || (file.filename ? file.filename.replace(/\.[^/.]+$/, "") : "");
                  const isActiveById = currentFileId ? file.id === currentFileId : false;
                  const isActiveByName = currentFileName ? file.filename === currentFileName : false;
                  const isActiveByProjectId = currentProjectId
                    ? fileProjectId === currentProjectId || file.filename === currentProjectId
                    : false;
                  const isActive = isActiveById || isActiveByName || isActiveByProjectId;
                  const isSharedFile = sharedFiles.some((sf) => sf.id === file.id);

                  return (
                    <div
                      key={file.id}
                      onClick={() => {
                        if (isPlanExpired) return;
                        if (!isActive) {
                          if (parentProjectId && onLoadProjectFile) {
                            onLoadProjectFile(file.id, file.filename);
                          } else {
                            onSwitchFile(fileProjectId);
                          }
                        }
                        onClose();
                      }}
                      className={`flex items-center gap-3 p-2 px-3 rounded-md transition-all ${
                        isPlanExpired
                          ? "opacity-50 cursor-not-allowed"
                          : isActive
                            ? "selected cursor-pointer"
                            : "hover-overlay border border-transparent cursor-pointer"
                      }`}
                    >
                      <FileText size={18} className={isSharedFile ? "text-blue-500" : "text-accent"} />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium text-gray-900 truncate">{file.filename}</span>
                          {isActive && (
                            <span className="px-1.5 py-0.5 bg-green-100 text-green-700 text-[10px] font-semibold rounded">
                              ACTIVE
                            </span>
                          )}
                        </div>
                      </div>
                      {!usingProjectFiles && onDeleteFile && fileProjectId && !isSharedFile && (
                        <button
                          onClick={(event) => {
                            event.stopPropagation();
                            onDeleteFile(fileProjectId, file.filename);
                          }}
                          className="text-red-500 hover:text-red-700 p-1 rounded hover:bg-red-50"
                          title="Delete file"
                        >
                          <Trash2 size={14} />
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <Search size={40} className="mb-3 opacity-30" />
              <p className="text-base font-medium text-gray-600 mb-1">No files found</p>
              <p className="text-xs text-gray-500 max-w-xs text-center">
                {searchQuery
                  ? `No files match "${searchQuery}". Try a different search.`
                  : "Upload or open a local file to get started."}
              </p>
            </div>
          )}
        </div>
        <div className="p-3 border-t space-y-2" style={{ borderColor: "var(--color-border)" }}>
          <button
            onClick={handleCreateNewFile}
            disabled={!canOpenLocalFile || isPlanExpired}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 text-xs rounded-md border hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            style={{
              borderColor: "var(--color-border)",
              color: "var(--color-text)",
            }}
          >
            <Plus size={14} />
            Create New File
          </button>
          <button
            onClick={handleOpenLocalFile}
            disabled={!canOpenLocalFile || isPlanExpired}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 text-xs rounded-md border hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            style={{
              borderColor: "var(--color-border)",
              color: "var(--color-text)",
            }}
          >
            <FolderOpen size={14} />
            Open Local File...
          </button>
        </div>
      </div>
    </div>
  );
};



/**
 * Appends the explicit draft-scope opt-in the backend requires before reading from a
 * user's draft graph instead of main. userId alone isn't a scope signal — it's always
 * resolvable via the X-Ontocode-User-Id header/JWT, even on requests made while viewing
 * Public — so omitting/blanking userId doesn't stop a read from being scoped to draft.
 */
/**
 * True when reads/writes should carry draft=true. Draft/public graph scoping is a
 * WEBAPP-only concern: desktop is single-user OWLAPI-first with its own local-graph model
 * and no shared public/draft split, so we never send draft params there — that keeps
 * desktop's read/write behavior byte-for-byte unchanged by the draft-isolation work.
 */
function isDraftScopeActive(): boolean {
  return !isDesktop() && ontologyMutationService.isPrivateEditMode();
}

// Webapp + public/live sync: every mutation already writes straight to the shared
// graph (no per-user draft record), so there's nothing pending to flush and the
// "unsaved changes" dot should never light up. Desktop always tracks its own
// unsaved-to-disk state regardless of sync mode.
function isLiveWriteMode(): boolean {
  return !isDesktop() && !ontologyMutationService.isPrivateEditMode();
}

function withDraftScope(url: string): string {
  if (!isDraftScopeActive()) return url;
  return url + (url.includes("?") ? "&draft=true" : "?draft=true");
}

/**
 * Like withDraftScope but also appends userId — required by the ontology-metadata WRITE
 * endpoints (annotations/imports/iri) to scope the write to the caller's draft graph. Reads
 * can rely on the header userId, but these writes read userId explicitly from the request.
 */
function withDraftAndUser(url: string): string {
  if (!isDraftScopeActive()) return url;
  const uid = resolveMutationActor().userId;
  const sep = url.includes("?") ? "&" : "?";
  return `${url}${sep}draft=true&userId=${encodeURIComponent(uid || "")}`;
}

/**
 * Body fields to merge into POST/PUT metadata writes so they route to the draft graph
 * in private mode. Empty object in public mode (no draft, normal public write).
 */
function draftBodyFields(): Record<string, string> {
  if (!isDraftScopeActive()) return {};
  return { draft: "true", userId: resolveMutationActor().userId || "" };
}

interface DashboardProps {
  onBackToProjects?: () => void;
  onGoToProjectDashboard?: () => void;
  onGoToWorkspace?: () => void;
  onFileSelected?: (fileId: string, fileName: string) => void;
  selectedFileId?: string;
  selectedFileName?: string;
  projectId?: string; // Renamed to initialProjectId to avoid naming conflict
}

const Dashboard: React.FC<DashboardProps> = ({
  onBackToProjects,
  onGoToProjectDashboard,
  onGoToWorkspace,
  onFileSelected,
  selectedFileId,
  selectedFileName,
  projectId: initialProjectId,
}) => {
  // #region State
  const { user, logout } = useAuth();
  const collaboration = useCollaboration();
  const { actualMode } = useTheme();
  const subscription = useSubscription();
  const readonlyMode = false; // Allow editing by default
  // FREE plan members (non-owners inside a workspace) are view-only.
  // PRO plan allows members and admins to edit.
  const workspaceRoleParsed = parseWorkspaceRole(user?.workspaceRole, undefined);
  const [userProjectRole, setUserProjectRole] = useState<string | null>(null);
  const isProjectViewerRole = userProjectRole === 'VIEWER';
  const isProjectDraftEditorRole = userProjectRole === 'DRAFT_EDITOR';
  // userProjectRole is fetched asynchronously (via fetchProjectFiles) and can resolve after
  // the initial-load promise chain that reads isProjectDraftEditorRole/isProjectViewerRole in
  // a .then() has already captured its closure — that .then() would otherwise see stale
  // `false` values even after the role updates. These refs always reflect the latest value
  // for such callbacks.
  const isProjectDraftEditorRoleRef = useRef(isProjectDraftEditorRole);
  useEffect(() => { isProjectDraftEditorRoleRef.current = isProjectDraftEditorRole; }, [isProjectDraftEditorRole]);
  const isProjectViewerRoleRef = useRef(isProjectViewerRole);
  useEffect(() => { isProjectViewerRoleRef.current = isProjectViewerRole; }, [isProjectViewerRole]);
  const isViewOnlyMember =
    !isDesktop() && (
      (subscription.isFree && user?.workspaceRole != null && normalizeRole(user.workspaceRole) !== "OWNER") ||
      isWorkspaceViewerRole(workspaceRoleParsed) ||
      isProjectViewerRole
    );
  const viewOnlyMessage = isProjectViewerRole
    ? "You have view-only access to this project. Contact the project owner to request edit permissions."
    : "You have view-only access. Upgrade your plan to edit.";
  const [showProPromptType, setShowProPromptType] = useState<'edit' | 'export' | 'viewer' | 'draftRequired' | null>(null);
  const handleViewOnlyAction = () => setShowProPromptType(isProjectViewerRole ? 'viewer' : 'edit');
  const handleExportProAction = () => setShowProPromptType('export');
  const [showThemeSettings, setShowThemeSettings] = useState(false);
  const [isPlanExpired, setIsPlanExpired] = useState(false);
  const isCurrentWorkspaceOwner = user?.workspaceRole == null || normalizeRole(user?.workspaceRole ?? "") === "OWNER";
  const openFileIsPlanExpired = isPlanExpired && isCurrentWorkspaceOwner;
  const deploymentType = localStorage.getItem("deploymentType") as "self-hosted" | "cloud" | null;
  const isCloudDeployment = deploymentType === "cloud";

  useEffect(() => {
    // Desktop has no billing — plan/expiry is governed by the license file.
    if (isDesktop()) return;
    apiClient.get("/api/billing/subscription")
      .then((res: any) => {
        const d = res?.data || res;
        const status = d.status || "";
        const planName = (d.planName || "FREE").toUpperCase();
        setIsPlanExpired(
          planName !== "FREE" &&
          status !== "" &&
          status !== "active" &&
          status !== "trialing"
        );
      })
      .catch(() => {});
  }, []);

  // If the workspace owner's paid plan expired, redirect owners back to workspace selection.
  // Members are not blocked — they may still view until the owner renews.
  useEffect(() => {
    if (isDesktop()) return;
    const wid = user?.workspaceId;
    if (!wid) return;
    const isOwner = !user?.workspaceRole || normalizeRole(user.workspaceRole) === "OWNER";
    if (!isOwner) return;
    apiClient.get(`/api/billing/workspace-owner-status/${wid}`)
      .then((res: any) => {
        const d = res?.data || res;
        if (d.isExpired && !d.enterpriseDomainBypass) {
          onGoToWorkspace?.();
        }
      })
      .catch(() => {});
  }, [user?.workspaceId, user?.workspaceRole]);

  const applyInstanceCountsToTree = useCallback(
    (nodes: TreeNode[], counts: Record<string, { direct?: number; inferred?: number; total?: number }>): TreeNode[] => {
      if (!Array.isArray(nodes)) return [];
      // Skip tree walk entirely when no counts are loaded yet
      if (!counts || Object.keys(counts).length === 0) return nodes;

      return nodes.map((node) => {
        const countEntry = counts[node.id];
        const direct = countEntry?.direct;
        const inferred = countEntry?.inferred;
        const total = countEntry ? (countEntry.total ?? (direct ?? 0) + (inferred ?? 0)) : undefined;
        const children = Array.isArray(node.children) ? node.children : [];

        return {
          ...node,
          directInstanceCount: direct,
          inferredInstanceCount: inferred,
          totalInstanceCount: total,
          children: children.length > 0 ? applyInstanceCountsToTree(children, counts) : [],
          hasChildren: node.hasChildren !== undefined ? node.hasChildren : children.length > 0,
        };
      });
    },
    [],
  );

  const countNodes = (nodes: any[]): number => {
    let count = 0;
    for (const node of nodes) {
      // Don't count owl:Thing or owl:Nothing in the total class count
      const id = node.id || node.iri;
      if (
        id !== "http://www.w3.org/2002/07/owl#Thing" &&
        id !== "owl:Thing" &&
        id !== "http://www.w3.org/2002/07/owl#Nothing" &&
        id !== "owl:Nothing"
      ) {
        count++;
      }
      if (node.children && node.children.length > 0) {
        count += countNodes(node.children);
      }
    }
    return count;
  };

  const showToast = useCallback(
    (message: string, type: "success" | "error" | "info" | "warning" = "info") => {
      collaboration.addNotification({
        type,
        message,
        userId: user?.email || "system",
        username: user?.username || "You",
        userColor: "#6366f1",
        timestamp: Date.now(),
      });

      if (window.vscode) {
        window.vscode.postMessage({
          type: "showNotification",
          notification: {
            type,
            title: "OntoCode",
            message,
          },
        });
      }
    },
    [user?.email, user?.username], // collaboration.addNotification is stable, no need to include
  );
  const isNonWorkspaceMode = !initialProjectId && !user?.workspaceId;
  // Desktop always has a workspace id but opens files directly via localStorage (no project library).
  // Honor explicit editor deep-links/tests that set suppress_workspace_auto_open + lastProjectId.
  const suppressWorkspaceAutoOpen =
    typeof localStorage !== "undefined" &&
    localStorage.getItem("ontocode_suppress_workspace_auto_open") === "true";
  const shouldRestoreLastOpenedFile = isDesktop() || isNonWorkspaceMode || suppressWorkspaceAutoOpen;
  const storedProjectId = shouldRestoreLastOpenedFile ? localStorage.getItem("ontocode_lastProjectId") : null;

  const [projectId, setProjectIdInternal] = useState<string | null>(initialProjectId || null);
  const prevProjectIdRef = useRef<string | null>(null);

  // Switching ontology files: keep the previous tree visible (dimmed) until new data arrives.
  useEffect(() => {
    if (!projectId || projectId === prevProjectIdRef.current) return;
    prevProjectIdRef.current = projectId;
    fetchDataGenerationRef.current += 1;
    if (fetchAbortControllerRef.current) {
      fetchAbortControllerRef.current.abort();
    }
    setSelectedItem(null);
    setMetadata(null);
    setIsHierarchyLoading(true);
    setIsMetadataLoading(true);
    setIsPropertiesLoading(true);
    setIsIndividualsLoading(true);
    setIsAnnotationPropertiesLoading(true);
    setIsDatatypesLoading(true);
    setLoadingStatusMessage("Loading ontology…");
  }, [projectId]);

  const setProjectId = useCallback(
    (value: string | null | ((prev: string | null) => string | null)) => {
      setProjectIdInternal((prev) => (typeof value === "function" ? value(prev) : value));
    },
    [],
  );

  // Helper function to encode project ID for use in URL paths
  // Handles hierarchical project IDs like "project-123/file-456"
  const encodeProjectId = (id: string | null | undefined): string => {
    if (!id) return "";
    return encodeURIComponent(id);
  };
  const [availableProjects, setAvailableProjects] = useState<any[]>([]);
  const [showProjectSelector, setShowProjectSelector] = useState(false);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [ontologyImports, setOntologyImports] = useState<string[]>([]);
  const [generalClassAxioms, setGeneralClassAxioms] = useState<
    Array<{
      subExpression: string;
      superClassIri?: string;
      superClassLabel?: string;
      definition?: string;
    }>
  >([]);
  const [ontologyAnnotations, setOntologyAnnotations] = useState<
    Array<{
      propertyIri: string;
      value: string;
      datatype?: string;
      lang?: string;
    }>
  >([]);
  const [prefixMappings, setPrefixMappings] = useState<Array<{ prefix: string; namespace: string }>>([]);
  const [isEditingOntologyId, setIsEditingOntologyId] = useState(false);
  const [ontologyIriDraft, setOntologyIriDraft] = useState("");
  const [versionIriDraft, setVersionIriDraft] = useState("");
  const [isPrefixEditing, setIsPrefixEditing] = useState(false);
  const [editingPrefixIndex, setEditingPrefixIndex] = useState<number | null>(null);
  const [importDraft, setImportDraft] = useState("");
  const [editingImportIndex, setEditingImportIndex] = useState<number | null>(null);
  const [bottomTabsHeight, setBottomTabsHeight] = useState(200);
  const [isResizing, setIsResizing] = useState(false);
  const [axiomDialogOpen, setAxiomDialogOpen] = useState(false);
  const [editingAxiomIndex, setEditingAxiomIndex] = useState<number | null>(null);
  const [axiomDraft, setAxiomDraft] = useState({ definition: "", superClassIri: "" });
  const [collaboratorCursors, setCollaboratorCursors] = useState<
    Map<string, { x: number; y: number; userName: string; color: string; timestamp: number }>
  >(new Map());
  const [myLocalCursor, setMyLocalCursor] = useState({ x: 0, y: 0 });
  const [isPrefixDialogOpen, setIsPrefixDialogOpen] = useState(false);
  const [prefixDialogData, setPrefixDialogData] = useState({
    prefix: "",
    namespace: "",
    isEdit: false,
    originalPrefix: "",
  });
  const [isImportDialogOpen, setIsImportDialogOpen] = useState(false);
  const [importDialogData, setImportDialogData] = useState({ iri: "", isEdit: false, originalIri: "" });
  const [showImportClosure, setShowImportClosure] = useState(false);
  const [expandedImports, setExpandedImports] = useState<Set<string>>(new Set());
  const [isOntologyAnnotationDialogOpen, setIsOntologyAnnotationDialogOpen] = useState(false);
  const [quickEditParentItem, setQuickEditParentItem] = useState<SelectableItem | null>(null);
  const [quickEditNoteItem, setQuickEditNoteItem] = useState<SelectableItem | null>(null);
  const [isQuickParentDialogOpen, setQuickParentDialogOpen] = useState(false);
  const [isQuickPropertyParentDialogOpen, setQuickPropertyParentDialogOpen] = useState(false);
  const [isQuickNoteDialogOpen, setQuickNoteDialogOpen] = useState(false);
  const [ontologyAnnotationEditTarget, setOntologyAnnotationEditTarget] = useState<{
    propertyIri: string;
    value: string;
    datatype?: string;
  } | null>(null);
  const [mainTab, setMainTab] = useState("Entities");
  const [entitiesTab, setEntitiesTab] = useState("Classes");
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>(["http://www.w3.org/2002/07/owl#Thing"]); // Pre-expand owl:Thing
  const expandedNodesRef = useRef<string[]>(["http://www.w3.org/2002/07/owl#Thing"]);
  useEffect(() => { expandedNodesRef.current = expandedNodes; }, [expandedNodes]);

  // Tracks which tree nodes are currently fetching children (shows per-node spinner)
  const [loadingNodes, setLoadingNodes] = useState<Set<string>>(new Set());

  // Fetch files for the currently selected project
  const fetchProjectFiles = useCallback(async (currentProjectId: string): Promise<FileInfo[]> => {
    if (!currentProjectId) return [];

    try {
      console.log("[Dashboard] 📂 Fetching files for project:", currentProjectId);
      const filesResponse = await apiClient.get<{ files: any[]; count: number; userProjectRole?: string }>(
        `/api/projects/${currentProjectId}/files`,
      );

      console.log("[Dashboard] 📥 Raw files response:", filesResponse);

      if (filesResponse && Array.isArray(filesResponse.files)) {
        console.log("[Dashboard] 📄 Found", filesResponse.files.length, "files in project");

        // Map file metadata to FileInfo format
        const projectFiles = filesResponse.files.map((file: any) => ({
          id: file.id,
          filename: file.name || file.fileName || file.id,
          contentType: file.type === "owl" ? "application/rdf+xml" : `application/${file.type}`,
          uploadDate: file.uploadedAt || new Date().toISOString(),
          length: file.size || 0,
          uploadedBy: file.uploadedBy,
        }));

        console.log(`[Dashboard] 📋 Mapped ${projectFiles.length} project files`);
        console.log("[Dashboard] 📋 Mapped project files:", projectFiles);
        console.log(
          "[Dashboard] 📋 File details:",
          projectFiles.map((f) => ({ id: f.id, name: f.filename })),
        );

        console.log("[Dashboard] 📋 About to call setProjectFiles with", projectFiles.length, "files");
        setProjectFiles(projectFiles);
        console.log("[Dashboard] ✅ setProjectFiles called");

        // Only update listOfFiles for backward compatibility
        // Do NOT overwrite myFiles/sharedFiles - those are user-specific and should be
        // populated by fetchProjects() which gets files by user email
        setListOfFiles(projectFiles);

        console.log("[Dashboard] ✅ File menu updated with project files (listOfFiles only)");
        console.log("[Dashboard] ✅ projectFiles state updated with", projectFiles.length, "files");

        // Capture the user's role in this project so isViewOnlyMember is correct in the editor
        if (filesResponse.userProjectRole) {
          setUserProjectRole(filesResponse.userProjectRole);
        }

        return projectFiles; // Return the files for verification
      } else if (filesResponse && filesResponse.files === undefined) {
        // Maybe files are at a different level or API returned error
        console.log("[Dashboard] ⚠️ Response has no files array:", filesResponse);
        // Try to handle different response formats
        if (Array.isArray(filesResponse)) {
          const projectFiles = filesResponse.map((file: any) => ({
            id: file.id,
            filename: file.name || file.fileName || file.id,
            contentType: file.type === "owl" ? "application/rdf+xml" : `application/${file.type}`,
            uploadDate: file.uploadedAt || new Date().toISOString(),
            length: file.size || 0,
            uploadedBy: file.uploadedBy,
          }));
          setProjectFiles(projectFiles);

          // Only update listOfFiles, not myFiles/sharedFiles
          setListOfFiles(projectFiles);
          return projectFiles; // Return the files
        } else {
          console.log("[Dashboard] ⚠️ Unable to parse files from response, clearing file menu");
          // setMyFiles([]);
          // setSharedFiles([]);
          setProjectFiles([]);
          setListOfFiles([]);
          return []; // Return empty array
        }
      } else {
        console.log("[Dashboard] ℹ️ No files found in project or empty response");
        // Don't clear myFiles/sharedFiles - they contain user's files from fetchProjects
        setProjectFiles([]);
        setListOfFiles([]);
        return []; // Return empty array
      }
    } catch (error: any) {
      console.error("[Dashboard] ❌ Failed to fetch project files:", error);
      console.error("[Dashboard] ❌ Error details:", error?.response?.data || error?.message || error);
      // Don't clear the file menu on error - keep showing projects list
      return []; // Return empty array on error
    }
  }, []);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchOptions, setSearchOptions] = useState({
    useRegex: false,
    searchAnnotations: false,
    hideDeprecated: false,
    hideBuiltins: false,
  });
  const [showImportDialog, setShowImportDialog] = useState(false);
  const [isInitialLoading, setIsInitialLoading] = useState(false);
  const [hasFetchedProjects, setHasFetchedProjects] = useState(false);
  const [hasUserSelectedFile, setHasUserSelectedFile] = useState(false);
  const hasUserSelectedFileRef = useRef(false);
  const webviewReadySentRef = useRef(false); // Track if we've sent webviewReady
  const [isExpectingFileReady, setIsExpectingFileReady] = useState(false); // Don't auto-load if expecting upload
  const pendingImportProjectIdRef = useRef<string | null>(null); // Track which project is being imported (using ref for persistence)
  const [pendingImportProjectId, setPendingImportProjectId] = useState<string | null>(null);
  const [showLoadingChoice, setShowLoadingChoice] = useState(false);
  const [loadingProjectName, setLoadingProjectName] = useState("");
  const [loadingStatusMessage, setLoadingStatusMessage] = useState<string>(""); // Track import progress message
  const loadingPromiseRef = useRef<Promise<void> | null>(null);
  const userLoadingChoice = useRef<"wait" | "continue" | null>(null);
  // Stable ref so async callbacks always call the latest navigation function.
  const onGoToProjectDashboardRef = useRef(onGoToProjectDashboard);
  useEffect(() => { onGoToProjectDashboardRef.current = onGoToProjectDashboard; }, [onGoToProjectDashboard]);
  const autoLoadNewFileRef = useRef(false); // Set when user clicks "Create New File" — skip loading dialog on fileReady
  const codeViewDirtyRef = useRef(false);
  const metadataRefreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [draftCount, setDraftCount] = useState(0);
  const [isSaving, setIsSaving] = useState(false);
  const [syncMode, setSyncMode] = useState<"private" | "public">("public");
  const [publishConflictStatus, setPublishConflictStatus] = useState<'idle' | 'checking' | 'clean' | 'conflict'>('idle');
  const [draftCopyPhase, setDraftCopyPhase] = useState<'idle' | 'import-blocked' | 'copying' | 'ready' | 'failed'>('idle');
  const [draftCopyTripleCount, setDraftCopyTripleCount] = useState<number | undefined>(undefined);
  const [requireDraftForMembers, setRequireDraftForMembers] = useState(false);
  const [isProjectOwner, setIsProjectOwner] = useState(false);
  const [autoDraftStatus, setAutoDraftStatus] = useState<'idle' | 'copying' | 'ready'>('idle');
  const [showPRsModal, setShowPRsModal] = useState(false);
  const [pendingPRCount, setPendingPRCount] = useState(0);
  const isWorkspaceAdminRole = normalizeRole(user?.workspaceRole ?? "") === "ADMIN";
  const canReviewPR = isProjectOwner
    || userProjectRole === 'OWNER'
    || userProjectRole === 'ADMIN'
    || userProjectRole === 'EDITOR'
    || ((isCurrentWorkspaceOwner || isWorkspaceAdminRole) && !isProjectDraftEditorRole);
  const canRaisePR = !isDesktop() && (
    isProjectDraftEditorRole ||
    (syncMode === 'private' && !isViewOnlyMember)
  );
  const showPRButton = !isDesktop() && !!projectId && (
    canRaisePR || canReviewPR ||
    ((isCurrentWorkspaceOwner || isWorkspaceAdminRole) && !isProjectDraftEditorRole)
  );
  const autoDraftPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const draftCopyPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const conflictCheckTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [showDraftPRPanel, setShowDraftPRPanel] = useState(false);
  const [showPullPreview, setShowPullPreview] = useState(false);
  const [openPRCount, setOpenPRCount] = useState(0);

  const startDraftCopySession = useCallback((
    targetProjectId: string,
    userId: string,
    options?: { showModal?: boolean; onReady?: () => void }
  ) => {
    if (!targetProjectId || !userId) return;

    const pollUntilReady = () => {
      const pollRef = options?.showModal ? draftCopyPollRef : autoDraftPollRef;
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(async () => {
        try {
          const status = await draftTrackingService.getDraftCopyStatus(targetProjectId, userId);
          if (status === 'READY') {
            if (pollRef.current) clearInterval(pollRef.current);
            options?.onReady?.();
            if (options?.showModal) {
              setDraftCopyPhase('ready');
              setTimeout(() => setDraftCopyPhase('idle'), 2000);
            } else {
              setAutoDraftStatus('ready');
              setTimeout(() => setAutoDraftStatus('idle'), 3000);
            }
          } else if (status === 'FAILED') {
            if (pollRef.current) clearInterval(pollRef.current);
            if (options?.showModal) setDraftCopyPhase('failed');
            else setAutoDraftStatus('idle');
          }
        } catch {
          if (pollRef.current) clearInterval(pollRef.current);
          if (options?.showModal) setDraftCopyPhase('failed');
        }
      }, 2000);
    };

    draftTrackingService.getDraftCopyStatus(targetProjectId, userId).then((status) => {
      if (status === 'READY') {
        options?.onReady?.();
        return;
      }
      if (options?.showModal) setDraftCopyPhase('copying');
      else setAutoDraftStatus('copying');

      const beginCopy = () => {
        draftTrackingService.initiateDraftCopy(targetProjectId, userId)
          .then(({ tripleCount }) => {
            if (options?.showModal) setDraftCopyTripleCount(tripleCount);
            pollUntilReady();
          })
          .catch((err: any) => {
            if (options?.showModal) {
              if (err?.response?.status === 409 || (err?.message || '').includes('import')) {
                setDraftCopyPhase('import-blocked');
              } else {
                setDraftCopyPhase('failed');
              }
            } else {
              setAutoDraftStatus('idle');
            }
          });
      };

      if (status === 'COPYING') {
        pollUntilReady();
      } else {
        beginCopy();
      }
    }).catch(() => {});
  }, []);

  // Callback shared by the "Switch to Draft Mode" toolbar button and the draftRequired dialog button.
  const handleSwitchToDraftMode = useCallback(() => {
    setShowProPromptType(null);
    if (!projectId) return;
    const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
    // Unblock mutations — they will now target the draft graph (realTimeSync=false after copy is ready).
    ontologyMutationService.setDraftRequired(false);
    startDraftCopySession(projectId, effectiveUserId, {
      showModal: true,
      onReady: () => {
        setSyncMode('private');
        ontologyMutationService.setRealTimeSync(false);
        localStorage.setItem(`ontocode_sync_mode_${projectId}`, 'private');
        userPreferencesService.saveSyncMode(projectId, 'private');
        notificationService.info("Draft Mode Active", "Editing your private draft — changes won't affect others until you publish.");
      },
    });
  }, [projectId, user, startDraftCopySession]);

  const handlePullComplete = useCallback(() => {
    if (!projectId) return;
    notificationService.success("Pull Complete", "Public changes were merged into your draft.");
    // The draft graph was just merged server-side — the already-loaded tree reflects the
    // pre-merge draft content, so refresh it.
    fetchData(projectId, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fetchData is declared further
    // down this component; referencing it here in the deps array (not just the closure body)
    // would hit the const temporal-dead-zone during this render.
  }, [projectId]);

  const refreshOpenPRCount = useCallback(async () => {
    if (!projectId) return;
    try {
      const res = await apiClient.get<any>(`/api/ontology/${projectId}/draft-prs?status=OPEN`);
      const data = res?.data || res;
      setOpenPRCount(Number(data.openCount ?? (data.prs?.length ?? 0)));
    } catch {
      // non-blocking
    }
  }, [projectId]);

  useEffect(() => {
    if (projectId && (canReviewPR || canRaisePR)) refreshOpenPRCount();
  }, [projectId, canReviewPR, canRaisePR]);

  // Track background import progress (visible after user clicks "Continue Working")
  const [backgroundImportActive, setBackgroundImportActive] = useState(false);
  const [backgroundImportProgress, setBackgroundImportProgress] = useState<number | undefined>(undefined);
  // Track import status for all projects (for ProjectSelector)
  const [projectImportStatuses, setProjectImportStatuses] = useState<{
    [projectId: string]: { type: string; status: string; progress?: number; metadata?: Record<string, unknown> };
  }>({});
  // Queue status visibility
  const [showQueueStatus, setShowQueueStatus] = useState(false);
  // Queue position tracking for loading dialog
  const [queuePosition, setQueuePosition] = useState<number | undefined>(undefined);
  const [totalInQueue, setTotalInQueue] = useState<number | undefined>(undefined);
  const [estimatedWaitTimeMs, setEstimatedWaitTimeMs] = useState<number | undefined>(undefined);
  const [inImportQueue, setInImportQueue] = useState(false);
  const [importReadyToBrowse, setImportReadyToBrowse] = useState(false);
  const [loadFailure, setLoadFailure] = useState<{ message: string; projectId?: string } | null>(null);
  const collaborationPanelRef = useRef<CollaborationPanelRef>(null);
  const [showOpenDialog, setShowOpenDialog] = useState(false);
  const [activeOntologySubTab, setActiveOntologySubTab] = useState("prefixes");
  const [importMode, setImportMode] = useState<"full" | "incremental" | "diff">("full");
  const [partitionStrategy, setPartitionStrategy] = useState<"none" | "namespace">("none");
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  const [isCreateIndividualForClassOpen, setCreateIndividualForClassOpen] = useState(false);
  const [isAddAnnotationDialogOpen, setAddAnnotationDialogOpen] = useState(false);
  const [isEditAnnotationDialogOpen, setEditAnnotationDialogOpen] = useState(false);
  const [isEditOntologyIRIDialogOpen, setEditOntologyIRIDialogOpen] = useState(false);
  const [isEditEntityIRIDialogOpen, setIsEditEntityIRIDialogOpen] = useState(false);
  const [editEntityIRITarget, setEditEntityIRITarget] = useState<SelectableItem | null>(null);
  const [isGCIEditorDialogOpen, setGCIEditorDialogOpen] = useState(false);
  const [editGCIData, setEditGCIData] = useState<{
    subClass: string;
    superClass: string;
    value: string;
    index: number;
  } | null>(null);
  const [editAnnotationData, setEditAnnotationData] = useState<{
    propertyIri: string;
    currentValue: string;
    entityId: string;
    language?: string;
    datatype?: string;
    originalPropertyIri?: string;
  } | null>(null);
  const [isAddClassDialogOpen, setAddClassDialogOpen] = useState(false);
  const [addClassType, setAddClassType] = useState<"subclass" | "sibling">("subclass");
  const [classParentLabel, setClassParentLabel] = useState("owl:Thing");
  const [isAddPropertyDialogOpen, setAddPropertyDialogOpen] = useState(false);
  const [addPropertyType, setAddPropertyType] = useState<"subproperty" | "sibling" | "root">("root");
  const [propertyParentLabel, setPropertyParentLabel] = useState("owl:topObjectProperty");
  const [isAddDatatypeDialogOpen, setAddDatatypeDialogOpen] = useState(false);
  const [isKeyboardShortcutsDialogOpen, setKeyboardShortcutsDialogOpen] = useState(false);
  const [isEntityPreferencesDialogOpen, setEntityPreferencesDialogOpen] = useState(false);
  const classHierarchyRefreshInFlight = useRef(false);
  const lastClassHierarchyRefreshAt = useRef(0);
  const classHierarchyRefreshRetryCount = useRef(0);
  // Set when a caller's refresh request is dropped by the in-flight/throttle guards below —
  // ensures that request isn't silently lost (e.g. two classes saved back-to-back: the
  // second save's refresh used to just vanish if the first was still resolving, with
  // nothing to pick it up again except an unrelated tab switch).
  const classHierarchyRefreshQueued = useRef(false);

  useEffect(() => {
    hasUserSelectedFileRef.current = hasUserSelectedFile;
  }, [hasUserSelectedFile]);

  // Entity Preferences
  const { preferences, updatePreferences } = useEntityPreferences();

  // Selector Dialog State
  const [isClassSelectorOpen, setIsClassSelectorOpen] = useState(false);
  const [isPropertyExpressionDialogOpen, setIsPropertyExpressionDialogOpen] = useState(false);
  const [isObjectPropertyExpressionDialogOpen, setIsObjectPropertyExpressionDialogOpen] = useState(false);
  const [isClassExpressionDialogOpen, setIsClassExpressionDialogOpen] = useState(false);
  const [selectorTarget, setSelectorTarget] = useState<
    "domain" | "range" | "subProperty" | "inverse" | "disjoint" | "equivalent" | null
  >(null);
  const [selectorEditingItem, setSelectorEditingItem] = useState<string | null>(null);
  const [selectorAllowedTabs, setSelectorAllowedTabs] = useState<TabType[]>(['hierarchy', 'objectRestriction', 'classExpression']);
  const [selectorInitialTab, setSelectorInitialTab] = useState<TabType>('hierarchy');

  // Annotation Property Description Dialogs (Protégé-style)
  const [isAnnotationDomainDialogOpen, setIsAnnotationDomainDialogOpen] = useState(false);
  const [isAnnotationRangeDialogOpen, setIsAnnotationRangeDialogOpen] = useState(false);
  const [isAnnotationSuperpropertyDialogOpen, setIsAnnotationSuperpropertyDialogOpen] = useState(false);

  // Data Property Range Dialog (Protégé-style - shows datatypes)
  const [isDataPropertyRangeDialogOpen, setIsDataPropertyRangeDialogOpen] = useState(false);

  // Publish conflict dialog (three-way merge vs force publish)
  const [publishConflictDialog, setPublishConflictDialog] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    conflicts: Array<{
      entityIRI: string;
      entityLabel?: string;
      changedBy?: string;
      yourAxioms?: string;
      mainAxioms?: string;
    }>;
    onForce: () => void;
  }>({
    isOpen: false,
    title: "",
    message: "",
    conflicts: [],
    onForce: () => {},
  });
  // Per-conflict resolution choices: entityIRI → action ("KEEP_SOURCE" | "KEEP_TARGET" | "MERGE")
  const [conflictResolutions, setConflictResolutions] = useState<Record<string, string>>({});

  // Confirm dialog state
  const [confirmDialog, setConfirmDialog] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
    onCancel?: () => void;
    confirmLabel?: string;
    cancelLabel?: string;
  }>({
    isOpen: false,
    title: "",
    message: "",
    onConfirm: () => {},
    onCancel: undefined,
    confirmLabel: undefined,
    cancelLabel: undefined,
  });

  // Prevents concurrent mutations (delete, rename, etc.) that would fire multiple simultaneous
  // SPARQL UPDATEs against GraphDB Free (2-connection cap) and trigger 504s.
  const isMutatingRef = useRef(false);

  // Protégé-style class delete dialog (radio choice: class only vs. class + asserted descendants).
  // Separate from confirmDialog above since it needs its own descendant-fetch + radio state.
  const [deleteClassDialog, setDeleteClassDialog] = useState<{
    isOpen: boolean;
    iri: string;
    label: string;
  }>({ isOpen: false, iri: "", label: "" });

  // Dedicated unsaved-changes warning dialog (separate from generic confirmDialog)
  const [unsavedChangesDialog, setUnsavedChangesDialog] = useState<{
    isOpen: boolean;
    onLeave: () => void;
  }>({
    isOpen: false,
    onLeave: () => {},
  });

  const [duplicatePrompt, setDuplicatePrompt] = useState<{
    isOpen: boolean;
    requestId: string | null;
    fileName: string;
    context: "project" | "ontology";
    projectId?: string;
    ownerEmail?: string;
    defaultCopyName?: string;
    detail?: string;
    allowOpenExisting?: boolean;
    error?: string;
  }>({
    isOpen: false,
    requestId: null,
    fileName: "",
    context: "project",
    projectId: undefined,
    ownerEmail: undefined,
    defaultCopyName: undefined,
    detail: undefined,
    allowOpenExisting: true,
    error: undefined,
  });
  const [duplicateCopyName, setDuplicateCopyName] = useState("");
  const [duplicateCopyError, setDuplicateCopyError] = useState<string | null>(null);
  const [duplicateCopySubmitting, setDuplicateCopySubmitting] = useState(false);

  const [selectedClassForIndividuals, setSelectedClassForIndividuals] = useState<TreeNode | null>(null);
  const [classInstances, setClassInstances] = useState<Individual[]>([]);
  const [classInstancesLoading, setClassInstancesLoading] = useState(false);
  const [classInstancesQuery, setClassInstancesQuery] = useState("");
  const [classInstancesView, setClassInstancesView] = useState<"direct" | "inferred" | "all">("direct");
  const [classTreeSearchQuery, setClassTreeSearchQuery] = useState("");
  const [selectedClassIndividual, setSelectedClassIndividual] = useState<Individual | null>(null);
  const [selectedClassIndividualDetails, setSelectedClassIndividualDetails] = useState<Individual | null>(null);
  const [selectedClassIndividualLoading, setSelectedClassIndividualLoading] = useState(false);
  const [classIndividualInfoTab, setClassIndividualInfoTab] = useState<"annotations" | "usage">("annotations");
  const [classIndividualUsages, setClassIndividualUsages] = useState<any[]>([]);
  const [classIndividualUsageLoading, setClassIndividualUsageLoading] = useState(false);
  const [classInstanceCounts, setClassInstanceCounts] = useState<
    Record<string, { direct?: number; inferred?: number; total?: number }>
  >({});
  const [hierarchyViewModes, setHierarchyViewModes] = useState<Record<string, "asserted" | "inferred">>({
    Classes: "asserted",
    ObjectProperties: "asserted",
    DataProperties: "asserted",
    AnnotationProperties: "asserted",
    Individuals: "asserted",
    Datatypes: "asserted",
  });
  const [hierarchyDisplayMode, setHierarchyDisplayMode] = useState<"label" | "id" | "annotation" | "custom">(
    () => userPreferencesService.getDisplayMode()
  );
  const [hierarchyAnnotationPropIri, setHierarchyAnnotationPropIri] = useState<string>(
    () => userPreferencesService.getAnnotationPropIri()
  );
  const [hierarchyCustomTemplate, setHierarchyCustomTemplate] = useState<string>(
    () => userPreferencesService.getCustomTemplate()
  );
  const [hierarchyAnnotationProperties, setHierarchyAnnotationProperties] = useState<Array<{ id: string; label: string }>>([]);
  const [hierarchyAnnotationValues, setHierarchyAnnotationValues] = useState<Map<string, string>>(new Map());
  const [hierarchyImportsScope, setHierarchyImportsScope] = useState<"active" | "closure">("active");
  const fetchedAnnotationIrisRef = useRef<Set<string>>(new Set());

  // Persist display mode preferences across sessions
  useEffect(() => {
    userPreferencesService.saveDisplayPreferences(hierarchyDisplayMode, hierarchyAnnotationPropIri, hierarchyCustomTemplate);
  }, [hierarchyDisplayMode, hierarchyAnnotationPropIri, hierarchyCustomTemplate]);
  const [isClassIndividualAnnotationDialogOpen, setClassIndividualAnnotationDialogOpen] = useState(false);
  const [isClassIndividualTypeDialogOpen, setClassIndividualTypeDialogOpen] = useState(false);
  const [isClassIndividualPropertyDialogOpen, setClassIndividualPropertyDialogOpen] = useState(false);
  const [classIndividualPropertyIsObject, setClassIndividualPropertyIsObject] = useState(true);
  const [classIndividualSameDiffDialog, setClassIndividualSameDiffDialog] = useState<null | { mode: "same" | "different" }>(null);
  const [classIndividualCandidateIndividuals, setClassIndividualCandidateIndividuals] = useState<Individual[]>([]);
  const [dlQuery, setDlQuery] = useState("Pizza and hasTopping some MozzarellaTopping");
  const [dlQueryResults, setDlQueryResults] = useState<string[] | null>(null);
  const [isDlQueryLoading, setIsDlQueryLoading] = useState(false);

  const [classHierarchy, setClassHierarchy] = useState<TreeNode[]>([]);
  const [topLevelTruncated, setTopLevelTruncated] = useState(false);
  const [topLevelTotal, setTopLevelTotal] = useState(0);
  const [isLoadingMoreTopLevel, setIsLoadingMoreTopLevel] = useState(false);
  const [isHierarchyLoading, setIsHierarchyLoading] = useState(false);
  // Per-section loaders — classes can appear while metadata/properties/etc. still load.
  const [isMetadataLoading, setIsMetadataLoading] = useState(false);
  const [isPropertiesLoading, setIsPropertiesLoading] = useState(false);
  const [isIndividualsLoading, setIsIndividualsLoading] = useState(false);
  const [isAnnotationPropertiesLoading, setIsAnnotationPropertiesLoading] = useState(false);
  const [isDatatypesLoading, setIsDatatypesLoading] = useState(false);
  const [inferredClassHierarchy, setInferredClassHierarchy] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<any[]>([]);
  const [inferredObjectPropertyHierarchy, setInferredObjectPropertyHierarchy] = useState<TreeNode[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<any[]>([]);
  const [inferredDataPropertyHierarchy, setInferredDataPropertyHierarchy] = useState<TreeNode[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<AnnotationProperty[]>([]);
  const [annotationPropertyHierarchy, setAnnotationPropertyHierarchy] = useState<TreeNode[]>([]);
  const [inferredAnnotationPropertyHierarchy, setInferredAnnotationPropertyHierarchy] = useState<TreeNode[]>([]);
  const [importClosureMap, setImportClosureMap] = useState<Record<string, Array<{ iri: string; children?: any[] }>>>({});
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [inferredIndividuals, setInferredIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<Datatype[]>([]);
  const [inferredDatatypes, setInferredDatatypes] = useState<Datatype[]>([]);

  const [listOfFiles, setListOfFiles] = useState<FileInfo[]>([]);
  const [projectFiles, setProjectFiles] = useState<FileInfo[]>([]);
  const [myFiles, setMyFiles] = useState<FileInfo[]>([]);
  const [sharedFiles, setSharedFiles] = useState<FileInfo[]>([]);
  const [activeFileId, setActiveFileId] = useState<string | null>(null);
  const [activeFileName, setActiveFileName] = useState<string | null>(null);
  const [deleteFileDialog, setDeleteFileDialog] = useState<{ isOpen: boolean; projectId: string; fileName: string }>({
    isOpen: false,
    projectId: "",
    fileName: "",
  });
  const [isShareDialogOpen, setIsShareDialogOpen] = useState(false);
  const [shareFileId, setShareFileId] = useState<string | null>(null);
  const [isCurrentFileShared, setIsCurrentFileShared] = useState(false);
  const [isMergeWizardOpen, setMergeWizardOpen] = useState(false);
  const [isHistoryPanelOpen, setIsHistoryPanelOpen] = useState(false);
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);
  const [isReleaseNotesOpen, setIsReleaseNotesOpen] = useState(false);
  const [appVersion, setAppVersion] = useState("");
  const [isUserGuideOpen, setIsUserGuideOpen] = useState(false);

  // Auto-open release notes once per new app version; closing the modal
  // records the seen version (see ReleaseNotesModal onClose below). Lives
  // here — not in the menu bar — because this component owns the modal state.
  useEffect(() => {
    import("../utils/appVersion").then(({ getAppVersion }) => {
      getAppVersion().then((v) => {
        setAppVersion(v || "");
        if (!v) return;
        const lastSeen = localStorage.getItem("ontocode_release_notes_seen") || "";
        if (lastSeen !== v) {
          setIsReleaseNotesOpen(true);
        }
      }).catch(() => setAppVersion(""));
    });
  }, []);
  const [showCollaborationPanel, setShowCollaborationPanel] = useState(false);

  // Auto-close collaboration panel if permissions are lost (downgrade/expiration)
  useEffect(() => {
    if (showCollaborationPanel && isCloudDeployment && !subscription.canAccessFeature('hasAdvancedCollaboration')) {
      console.log("[Dashboard] 🛡️ Closing collaboration panel due to permission change");
      setShowCollaborationPanel(false);
      showToast("Collaboration is no longer available on your current plan. Upgrade to resume.", "info");
    }
  }, [subscription, isCloudDeployment, showCollaborationPanel]);

  const [visibleMainTabs, setVisibleMainTabs] = useState([
    "ActiveOntology",
    "Entities",
    "IndividualsByClass",
    "DLQuery",
    "SPARQL",
    "Reasoner",
    "CodeView",
  ]);
  const [showPluginMarketplace, setShowPluginMarketplace] = useState(false);
  const [hasPluginUpdates, setHasPluginUpdates] = useState(false);
  const [installedPlugins, setInstalledPlugins] = useState<Set<string>>(new Set());
  const [pluginLoadingStates, setPluginLoadingStates] = useState<
    Record<string, { loading: boolean; error: string | null }>
  >({});

  const [codeViewFormat, setCodeViewFormat] = useState<
    "rdfxml" | "turtle" | "ntriples" | "owlxml" | "manchester" | "functional" | "jsonld"
  >("rdfxml");
  const [codeViewContent, setCodeViewContent] = useState<string>("");
  const [codeViewLoading, setCodeViewLoading] = useState(false);
  // Large-file guard: CodeHighlighter materializes per-line gutter elements and
  // scans the whole document for fold ranges, so past this size the webview
  // freezes. Above the cap we show a read-only preview of the head of the file
  // and point users at Export for the full content. null = not truncated.
  // (Legacy fallback — only used when the backend lacks /content-page.)
  const [codeViewTruncation, setCodeViewTruncation] = useState<{ totalChars: number; previewLines: number } | null>(null);
  // Paged read-only mode for large ontologies: the backend serves 10k-line
  // windows from disk, so neither side ever holds the whole 200MB document.
  // null = normal full-content editing mode.
  const [codeViewPage, setCodeViewPage] = useState<{
    startLine: number;
    lineCount: number;
    totalLines: number;
    totalBytes: number;
  } | null>(null);
  const CODE_VIEW_PAGE_LINES = 10_000;
  // Above this serialized size, Code View switches to paged read-only mode.
  const CODE_VIEW_LARGE_FILE_BYTES = 10 * 1024 * 1024;
  const [hasLocalCodeViewChanges, setHasLocalCodeViewChanges] = useState(false);
  const [codeViewSyntaxError, setCodeViewSyntaxError] = useState<string | null>(null);
  // Blocking dialog for a genuine save failure (reimport into the ontology failed) — replaces
  // the old silent cache-only fallback, which showed "Saved" even when nothing synced.
  const [codeViewSaveError, setCodeViewSaveError] = useState<string | null>(null);
  const [savingCodeView, setSavingCodeView] = useState(false);
  const lastCodeViewSaveContentRef = useRef<string>("");
  // Lint runs right before save (see handleSaveCodeContent) — non-blocking content
  // warnings (e.g. an IRI outside the ontology's namespace), shown in a dockable
  // Problems panel rather than a modal, since the user needs to see the editor to fix them.
  const [codeViewLintIssues, setCodeViewLintIssues] = useState<LintIssue[]>([]);
  const codeHighlighterRef = useRef<CodeHighlighterHandle>(null);
  const [citationJustInserted, setCitationJustInserted] = useState(false); // Track recent citation insertion for format refresh
  const [showCitationPicker, setShowCitationPicker] = useState(false);
  const [showManualCitationDialog, setShowManualCitationDialog] = useState(false);
  const [pendingCitation, setPendingCitation] = useState<any | null>(null);
  const [citationInsertionMode, setCitationInsertionMode] = useState(false);
  const [citationRemovalMode, setCitationRemovalMode] = useState(false);
  const [selectedInsertionLine, setSelectedInsertionLine] = useState<number | null>(null);

  // Reasoner state management
  const [selectedReasoner, setSelectedReasoner] = useState<string>("HermiT");
  const [isReasonerRunning, setIsReasonerRunning] = useState(false);
  const [isReasonerSynced, setIsReasonerSynced] = useState(false);
  const [reasonerResults, setReasonerResults] = useState<any>(null);
  const [isReasonerLoading, setIsReasonerLoading] = useState(false);
  const [isConsistencyLoading, setIsConsistencyLoading] = useState(false);
  const [consistencyResult, setConsistencyResult] = useState<any | null>(null);
  const [explanationState, setExplanationState] = useState<{
    open: boolean;
    loading: boolean;
    data: any;
    error: string | null;
  }>({ open: false, loading: false, data: null, error: null });
  const [isReasonerSettingsOpen, setIsReasonerSettingsOpen] = useState(false);

  const currentHierarchyViewMode = hierarchyViewModes[entitiesTab] || "asserted";

  const isEntitiesSectionLoading = useMemo(() => {
    switch (entitiesTab) {
      case "Classes":
        return isHierarchyLoading;
      case "ObjectProperties":
      case "DataProperties":
        return isPropertiesLoading;
      case "Individuals":
        return isIndividualsLoading;
      case "AnnotationProperties":
        return isAnnotationPropertiesLoading;
      case "Datatypes":
        return isDatatypesLoading;
      default:
        return false;
    }
  }, [
    entitiesTab,
    isHierarchyLoading,
    isPropertiesLoading,
    isIndividualsLoading,
    isAnnotationPropertiesLoading,
    isDatatypesLoading,
  ]);

  const backgroundLoadingSections = useMemo(() => {
    const parts: string[] = [];
    if (isHierarchyLoading) parts.push("classes");
    if (isMetadataLoading) parts.push("ontology metadata");
    if (isPropertiesLoading) parts.push("properties");
    if (isIndividualsLoading) parts.push("individuals");
    if (isAnnotationPropertiesLoading) parts.push("annotation properties");
    if (isDatatypesLoading) parts.push("datatypes");
    return parts;
  }, [
    isHierarchyLoading,
    isMetadataLoading,
    isPropertiesLoading,
    isIndividualsLoading,
    isAnnotationPropertiesLoading,
    isDatatypesLoading,
  ]);

  const sectionBarSections = useMemo(() => {
    if (isInitialLoading || showLoadingChoice) return [];
    const list =
      mainTab === "Entities"
        ? backgroundLoadingSections.filter((s) => s !== "ontology metadata")
        : backgroundLoadingSections;
    return list;
  }, [isInitialLoading, showLoadingChoice, mainTab, backgroundLoadingSections]);

  const showSectionLoadingBar = useDebouncedVisible(sectionBarSections.length > 0, {
    showDelayMs: 180,
    minVisibleMs: 400,
  });

  const lastSectionBarLabelsRef = useRef<string[]>([]);
  if (sectionBarSections.length > 0) {
    lastSectionBarLabelsRef.current = sectionBarSections;
  }
  const sectionBarLabels =
    sectionBarSections.length > 0 ? sectionBarSections : lastSectionBarLabelsRef.current;
  const [sectionBarMounted, setSectionBarMounted] = useState(false);
  useEffect(() => {
    if (showSectionLoadingBar && sectionBarLabels.length > 0) {
      setSectionBarMounted(true);
      return;
    }
    if (!showSectionLoadingBar) {
      const t = setTimeout(() => setSectionBarMounted(false), 320);
      return () => clearTimeout(t);
    }
  }, [showSectionLoadingBar, sectionBarLabels.length]);

  const entitiesTabs = [
    {
      id: "Classes",
      label: "Classes",
      icon: Package,
      count: Number((metadata as any)?.classCount) || 0,
      theme: "bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]",
    },
    {
      id: "ObjectProperties",
      label: "Object properties",
      icon: Share2,
      count:
        hierarchyViewModes.ObjectProperties === "inferred"
          ? countNodes(
              inferredObjectPropertyHierarchy.length > 0
                ? inferredObjectPropertyHierarchy
                : Array.isArray(reasonerResults?.objectPropertyHierarchy)
                  ? reasonerResults.objectPropertyHierarchy
                  : [],
            )
          : (() => {
              const metaCount = Number((metadata as any)?.objectPropertyCount) || 0;
              const treeCount =
                !isPropertiesLoading && objectPropertyHierarchy.length > 0
                  ? countNodes(objectPropertyHierarchy)
                  : 0;
              return Math.max(metaCount, treeCount);
            })(),
      theme: "bg-gradient-to-b from-blue-300 to-blue-500 text-white border-blue-600",
    },
    {
      id: "DataProperties",
      label: "Data properties",
      icon: Database,
      count:
        hierarchyViewModes.DataProperties === "inferred"
          ? countNodes(
              inferredDataPropertyHierarchy.length > 0
                ? inferredDataPropertyHierarchy
                : Array.isArray(reasonerResults?.dataPropertyHierarchy)
                  ? reasonerResults.dataPropertyHierarchy
                  : [],
            )
          : (() => {
              const metaCount = Number((metadata as any)?.dataPropertyCount) || 0;
              const treeCount =
                !isPropertiesLoading && dataPropertyHierarchy.length > 0
                  ? countNodes(dataPropertyHierarchy)
                  : 0;
              return Math.max(metaCount, treeCount);
            })(),
      theme: "bg-gradient-to-b from-green-300 to-green-500 text-white border-green-600",
    },
    {
      id: "AnnotationProperties",
      label: "Annotation properties",
      icon: Tag,
      count: !isAnnotationPropertiesLoading
        ? annotationProperties.length ||
          (annotationPropertyHierarchy.length > 0 ? countNodes(annotationPropertyHierarchy) : 0) ||
          (metadata as any)?.annotationPropertyCount ||
          0
        : (metadata as any)?.annotationPropertyCount ?? undefined,
      theme: "bg-gradient-to-b from-orange-300 to-orange-500 text-white border-orange-600",
    },
    {
      id: "Datatypes",
      label: "Datatypes",
      icon: Settings,
      count: !isDatatypesLoading
        ? datatypes.length || (metadata as any)?.datatypeCount || 0
        : (metadata as any)?.datatypeCount || 0,
      theme: "bg-gradient-to-b from-red-300 to-red-500 text-white border-red-600",
    },
    {
      id: "Individuals",
      label: "Individuals",
      icon: Eye,
      count: !isIndividualsLoading
        ? individuals.length || (metadata as any)?.individualCount || 0
        : (metadata as any)?.individualCount ?? undefined,
      theme: "bg-gradient-to-b from-purple-300 to-purple-500 text-white border-purple-600",
    },
  ];
  const activeTheme = entitiesTabs.find((t) => t.id === entitiesTab)?.theme;

  const sourceData = React.useMemo(() => {
    console.log("[Dashboard sourceData] entitiesTab:", entitiesTab);
    console.log("[Dashboard sourceData] hierarchyViewModes.Classes:", hierarchyViewModes.Classes);
    console.log("[Dashboard sourceData] classHierarchy:", classHierarchy);
    console.log("[Dashboard sourceData] classHierarchy length:", classHierarchy.length);
    console.log("[Dashboard sourceData] classHierarchy first element:", classHierarchy[0]);

    switch (entitiesTab) {
      case "Classes":
        if (hierarchyViewModes.Classes === "inferred") {
          // Use inferredClassHierarchy if available, otherwise fall back to reasoner results
          const inferred =
            inferredClassHierarchy.length > 0
              ? inferredClassHierarchy
              : Array.isArray(reasonerResults?.classHierarchyTree)
                ? reasonerResults.classHierarchyTree
                : Array.isArray(reasonerResults?.classHierarchy)
                  ? reasonerResults.classHierarchy
                  : [];

          console.log(
            "[Dashboard] Using inferred class hierarchy, length:",
            Array.isArray(inferred) ? inferred.length : 0,
          );
          return Array.isArray(inferred) ? inferred : [];
        }
        console.log("[Dashboard] Using asserted class hierarchy, length:", classHierarchy.length);
        console.log("[Dashboard] Returning classHierarchy:", classHierarchy);
        return classHierarchy;
      case "ObjectProperties":
        console.log(
          inferredObjectPropertyHierarchy,
          "[Dashboard] Hierarchy view mode for ObjectProperties:",
          hierarchyViewModes.ObjectProperties,
        );
        const objPropData =
          hierarchyViewModes.ObjectProperties === "inferred"
            ? inferredObjectPropertyHierarchy.length > 0
              ? inferredObjectPropertyHierarchy
              : Array.isArray(reasonerResults?.objectPropertyHierarchy)
                ? reasonerResults.objectPropertyHierarchy
                : []
            : objectPropertyHierarchy;
        return Array.isArray(objPropData) ? objPropData : [];
      case "DataProperties":
        const dataPropData =
          hierarchyViewModes.DataProperties === "inferred"
            ? inferredDataPropertyHierarchy.length > 0
              ? inferredDataPropertyHierarchy
              : Array.isArray(reasonerResults?.dataPropertyHierarchy)
                ? reasonerResults.dataPropertyHierarchy
                : []
            : dataPropertyHierarchy;
        return Array.isArray(dataPropData) ? dataPropData : [];
      case "AnnotationProperties": {
        const base =
          hierarchyViewModes.AnnotationProperties === "inferred"
            ? inferredAnnotationPropertyHierarchy.length > 0
              ? inferredAnnotationPropertyHierarchy
              : annotationPropertyHierarchy.length > 0
                ? annotationPropertyHierarchy
                : annotationProperties
            : annotationPropertyHierarchy.length > 0
              ? annotationPropertyHierarchy
              : annotationProperties;
        return Array.isArray(base) ? base : [];
      }
      case "Individuals":
        return hierarchyViewModes.Individuals === "inferred"
          ? inferredIndividuals.length > 0
            ? inferredIndividuals
            : individuals
          : individuals;
      case "Datatypes":
        return hierarchyViewModes.Datatypes === "inferred"
          ? inferredDatatypes.length > 0
            ? inferredDatatypes
            : datatypes
          : datatypes;
      default:
        return [];
    }
  }, [
    entitiesTab,
    classHierarchy,
    inferredClassHierarchy,
    objectPropertyHierarchy,
    dataPropertyHierarchy,
    inferredObjectPropertyHierarchy,
    inferredDataPropertyHierarchy,
    annotationPropertyHierarchy,
    inferredAnnotationPropertyHierarchy,
    inferredIndividuals,
    inferredDatatypes,
    hierarchyViewModes.Classes,
    hierarchyViewModes.ObjectProperties,
    hierarchyViewModes.DataProperties,
    hierarchyViewModes.AnnotationProperties,
    hierarchyViewModes.Individuals,
    hierarchyViewModes.Datatypes,
    annotationProperties,
    individuals,
    datatypes,
    reasonerResults,
  ]);

  const filteredData = React.useMemo(() => {
    const trimmedQuery = searchQuery.trim();
    const lowercasedQuery = trimmedQuery.toLowerCase();
    let regex: RegExp | null = null;
    if (searchOptions.useRegex && trimmedQuery) {
      try {
        regex = new RegExp(trimmedQuery, "i");
      } catch (error) {
        console.warn("[Dashboard] Invalid regex:", error);
        regex = null;
      }
    }

    const builtinsByTab: Record<string, Set<string>> = {
      Classes: new Set(["http://www.w3.org/2002/07/owl#Thing", "http://www.w3.org/2002/07/owl#Nothing"]),
      ObjectProperties: new Set([
        "http://www.w3.org/2002/07/owl#topObjectProperty",
        "http://www.w3.org/2002/07/owl#bottomObjectProperty",
      ]),
      DataProperties: new Set([
        "http://www.w3.org/2002/07/owl#topDataProperty",
        "http://www.w3.org/2002/07/owl#bottomDataProperty",
      ]),
      AnnotationProperties: new Set([
        "http://www.w3.org/2000/01/rdf-schema#label",
        "http://www.w3.org/2000/01/rdf-schema#comment",
        "http://www.w3.org/2000/01/rdf-schema#seeAlso",
        "http://www.w3.org/2000/01/rdf-schema#isDefinedBy",
        "http://www.w3.org/2002/07/owl#deprecated",
      ]),
      Datatypes: new Set([
        "http://www.w3.org/2000/01/rdf-schema#Literal",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral",
        "http://www.w3.org/2001/XMLSchema#string",
        "http://www.w3.org/2001/XMLSchema#integer",
        "http://www.w3.org/2001/XMLSchema#decimal",
        "http://www.w3.org/2001/XMLSchema#float",
        "http://www.w3.org/2001/XMLSchema#double",
        "http://www.w3.org/2001/XMLSchema#boolean",
        "http://www.w3.org/2001/XMLSchema#date",
        "http://www.w3.org/2001/XMLSchema#dateTime",
        "http://www.w3.org/2001/XMLSchema#anyURI",
      ]),
    };

    const isBuiltIn = (item: SelectableItem) => {
      if (!searchOptions.hideBuiltins) return false;
      const builtins = builtinsByTab[entitiesTab];
      return builtins ? builtins.has(item.id) : false;
    };

    const isDeprecated = (item: SelectableItem) => {
      if (!searchOptions.hideDeprecated) return false;
      const annotations = (item as any).annotations || {};
      const deprecatedValue = annotations["http://www.w3.org/2002/07/owl#deprecated"] || annotations["owl:deprecated"];
      if (!deprecatedValue) return false;
      const normalized = String(deprecatedValue).toLowerCase();
      return normalized === "true" || normalized === "1" || normalized === "yes";
    };

    const matchesQuery = (item: SelectableItem) => {
      if (!trimmedQuery) return true;
      const annotationBlob =
        searchOptions.searchAnnotations && (item as any).annotations
          ? Object.entries((item as any).annotations)
              .map(([key, value]) => `${key} ${String(value)}`)
              .join(" ")
          : "";
      const haystack = `${item.label || ""} ${item.id || ""} ${annotationBlob}`;
      if (regex) return regex.test(haystack);
      return haystack.toLowerCase().includes(lowercasedQuery);
    };

    const filterRecursively = (items: SelectableItem[]): SelectableItem[] => {
      // Safety check: ensure items is an array
      if (!Array.isArray(items)) {
        console.warn("[Dashboard] filterRecursively received non-array:", items);
        return [];
      }

      const results: SelectableItem[] = [];
      for (const item of items) {
        // Skip null/undefined items
        if (!item || !item.id) {
          continue;
        }

        if (isDeprecated(item)) {
          continue;
        }

        // Safely get children array
        const children = Array.isArray((item as any).children) ? (item as any).children : [];

        if (isBuiltIn(item) && children.length > 0) {
          const childResults = filterRecursively(children);
          results.push(...childResults);
          continue;
        }

        let matches = matchesQuery(item);
        if (children.length > 0) {
          const childResults = filterRecursively(children);
          if (childResults.length > 0) {
            results.push({ ...item, children: childResults, hasChildren: true } as any);
            matches = true;
          }
        }
        if (matches && !results.find((r) => r.id === item.id)) {
          // Ensure children is always an array when adding to results
          results.push({ ...item, children: children.length > 0 ? children : [] } as any);
        }
      }
      return results;
    };

    // Safety check on sourceData before filtering
    if (!Array.isArray(sourceData)) {
      console.warn("[Dashboard] sourceData is not an array:", sourceData);
      return [];
    }

    return filterRecursively(sourceData);
  }, [searchQuery, sourceData, entitiesTab, searchOptions]);

  const fetchReasonerBundle = useCallback(
    async (reasonerType: string) => {
      if (!projectId) {
        throw new Error("No ontology loaded");
      }

      const encodedProjectId = encodeURIComponent(projectId);

      // Start classification (async — returns a taskId)
      const startResponse: any = await apiClient.post(`/plugin-service/api/reasoner/${encodedProjectId}/classify`, {
        reasonerType,
      });

      const startData = startResponse?.data ?? startResponse;

      // If the backend returned a taskId, poll for completion
      if (startData?.taskId) {
        const taskId = startData.taskId;
        const POLL_INTERVAL = 3000; // 3 seconds
        const MAX_POLL_TIME = 1_800_000; // 30 minutes — large ontologies (e.g. Mondo 3.1M triples) need time to load

        const pollForResult = async (): Promise<any> => {
          const deadline = Date.now() + MAX_POLL_TIME;
          while (Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, POLL_INTERVAL));
            const statusResp: any = await apiClient.get(
              `/plugin-service/api/reasoner/${encodedProjectId}/classify/status/${taskId}`,
            );
            const statusData = statusResp?.data ?? statusResp;
            if (statusData?.status === "COMPLETED") {
              return statusData;
            }
            if (statusData?.status === "FAILED") {
              throw new Error(statusData?.error || "Classification failed");
            }
            // still RUNNING — continue polling
          }
          throw new Error("Classification timed out after 30 minutes");
        };

        const [classificationResponse, statsResponse] = await Promise.all([
          pollForResult(),
          apiClient
            .get(`/api/ontology/${encodedProjectId}/reasoner/stats?reasonerType=${reasonerType}`)
            .catch((error) => {
              console.warn("[Dashboard] Reasoner stats request failed:", error);
              return null;
            }),
        ]);

        return combineReasonerResults(classificationResponse, statsResponse ?? undefined);
      }

      // Fallback: backend returned a synchronous result (older API)
      const [statsResponse] = await Promise.all([
        apiClient
          .get(`/api/ontology/${encodedProjectId}/reasoner/stats?reasonerType=${reasonerType}`)
          .catch((error) => {
            console.warn("[Dashboard] Reasoner stats request failed:", error);
            return null;
          }),
      ]);

      return combineReasonerResults(startResponse, statsResponse ?? undefined);
    },
    [projectId],
  );

  const normalizeHierarchyNode = useCallback((node: any): any => {
    const id = node.id || node.iri;
    const children = Array.isArray(node.children) ? node.children.map(normalizeHierarchyNode) : [];
    const hasChildren = node.hasChildren !== undefined ? node.hasChildren : children.length > 0;
    let label = node.label;
    if (!label && id === "http://www.w3.org/2002/07/owl#Nothing") label = "owl:Nothing";
    if (!label && id === "http://www.w3.org/2002/07/owl#Thing") label = "owl:Thing";
    const isUnsatisfiable =
      node.isUnsatisfiable === true || id === "http://www.w3.org/2002/07/owl#Nothing";
    return { ...node, id, label, children, hasChildren, isUnsatisfiable };
  }, []);

  const loadInferredHierarchy = useCallback(async () => {
    if (!projectId) return;

    // HermiT/Pellet cannot handle large ontologies — auto-downgrade to ELK above 500K triples.
    // The editor service also enforces this server-side, but doing it here avoids a wasted round-trip.
    const HEAVY_REASONER_TRIPLE_LIMIT = 500_000;
    const ontologyTripleCount = (metadata as any)?.tripleCount ?? 0;
    const isHeavyReasoner = selectedReasoner === 'HERMIT' || selectedReasoner === 'HermiT' || selectedReasoner === 'PELLET';
    const effectiveReasoner = (isHeavyReasoner && ontologyTripleCount > HEAVY_REASONER_TRIPLE_LIMIT)
      ? 'ELK'
      : selectedReasoner;

    const fetchWithReasoner = async (reasoner: string) => {
      const response = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-class-hierarchy?reasonerType=${reasoner}`,
      );
      return response?.data || response;
    };

    const applyPayload = (payload: any, timedOut = false) => {
      const hierarchy = payload?.hierarchy || [];
      if (timedOut || !Array.isArray(hierarchy) || hierarchy.length === 0) {
        setInferredClassHierarchy([]);
        return false;
      }
      setInferredClassHierarchy(applyInstanceCountsToTree(hierarchy.map(normalizeHierarchyNode), classInstanceCounts));
      return true;
    };

    try {
      const payload = await fetchWithReasoner(effectiveReasoner);

      // Backend signals the ontology is logically inconsistent — reasoning cannot
      // proceed. Tell the user instead of silently showing an empty tree.
      if (payload?.inconsistent) {
        setInferredClassHierarchy([]);
        showNotification(
          payload?.message ||
            "The ontology is inconsistent — reasoning cannot proceed. Use 'Explain inconsistency' to find the conflicting axioms.",
          "error",
        );
        return;
      }
      // Backend converted an error into a friendly message with success:false —
      // surface it rather than rendering an empty hierarchy.
      if (payload && payload.success === false && (payload.message || payload.error)) {
        setInferredClassHierarchy([]);
        showNotification(payload.message || payload.error, "error");
        return;
      }

      // Backend signals ontology is too large — retry with STRUCTURAL (no inference, always fast)
      if (payload?.tooLargeForReasoner && effectiveReasoner !== 'STRUCTURAL') {
        const fallbackPayload = await fetchWithReasoner('STRUCTURAL');
        applyPayload(fallbackPayload);
      // Backend signals timeout — auto-retry with STRUCTURAL
      } else if (payload?.timeout && effectiveReasoner !== 'STRUCTURAL') {
        const fallbackPayload = await fetchWithReasoner('STRUCTURAL');
        applyPayload(fallbackPayload);
      } else {
        applyPayload(payload);
      }
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred class hierarchy:", error);
      setInferredClassHierarchy([]);
    }
  }, [projectId, metadata, applyInstanceCountsToTree, classInstanceCounts, selectedReasoner, normalizeHierarchyNode]);

  const loadInferredObjectPropertyHierarchy = useCallback(async () => {
    if (!projectId) return;
    const ontologyTripleCount = (metadata as any)?.tripleCount ?? 0;
    const isHeavyReasoner = selectedReasoner === 'HERMIT' || selectedReasoner === 'HermiT' || selectedReasoner === 'PELLET';
    const effectiveReasoner = (isHeavyReasoner && ontologyTripleCount > 500_000) ? 'ELK' : selectedReasoner;
    try {
      const res = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-object-property-hierarchy?reasonerType=${effectiveReasoner}`,
      );
      const payload = res?.data || res;
      if (payload?.tooLargeForReasoner) { setInferredObjectPropertyHierarchy([]); return; }
      const hierarchy = payload?.hierarchy || payload?.data?.hierarchy || [];
      setInferredObjectPropertyHierarchy(Array.isArray(hierarchy) ? hierarchy : []);
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred object property hierarchy:", error);
      setInferredObjectPropertyHierarchy([]);
    }
  }, [projectId, metadata, selectedReasoner]);

  const loadInferredDataPropertyHierarchy = useCallback(async () => {
    if (!projectId) return;
    const ontologyTripleCount = (metadata as any)?.tripleCount ?? 0;
    const isHeavyReasoner = selectedReasoner === 'HERMIT' || selectedReasoner === 'HermiT' || selectedReasoner === 'PELLET';
    const effectiveReasoner = (isHeavyReasoner && ontologyTripleCount > 500_000) ? 'ELK' : selectedReasoner;
    try {
      const res = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-data-property-hierarchy?reasonerType=${effectiveReasoner}`,
      );
      const payload = res?.data || res;
      if (payload?.tooLargeForReasoner) { setInferredDataPropertyHierarchy([]); return; }
      const hierarchy = payload?.hierarchy || payload?.data?.hierarchy || [];
      setInferredDataPropertyHierarchy(Array.isArray(hierarchy) ? hierarchy : []);
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred data property hierarchy:", error);
      setInferredDataPropertyHierarchy([]);
    }
  }, [projectId, metadata, selectedReasoner]);

  const loadInferredAnnotationPropertyHierarchy = useCallback(async () => {
    if (!projectId) return;
    console.log("[Dashboard] Loading inferred annotation property hierarchy...");
    try {
      const res = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-annotation-property-hierarchy?reasonerType=${selectedReasoner}`,
      );
      const payload = res?.data || res;
      const hierarchy = payload?.hierarchy || payload?.data?.hierarchy || [];
      console.log(
        "[Dashboard] Inferred annotation properties loaded:",
        Array.isArray(hierarchy) ? hierarchy.length : 0,
        "items",
      );
      setInferredAnnotationPropertyHierarchy(Array.isArray(hierarchy) ? hierarchy : []);
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred annotation property hierarchy:", error);
      setInferredAnnotationPropertyHierarchy([]);
    }
  }, [projectId, selectedReasoner]);

  const loadInferredDatatypes = useCallback(async () => {
    if (!projectId) return;
    console.log("[Dashboard] Loading inferred datatypes...");
    try {
      const res = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-datatypes?reasonerType=${selectedReasoner}`,
      );
      const payload = res?.data || res;
      const datatypes = payload?.datatypes || payload?.data?.datatypes || [];
      console.log("[Dashboard] Inferred datatypes loaded:", Array.isArray(datatypes) ? datatypes.length : 0, "items");
      setInferredDatatypes(Array.isArray(datatypes) ? datatypes : []);
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred datatypes:", error);
      setInferredDatatypes([]);
    }
  }, [projectId, selectedReasoner]);

  const loadInferredIndividuals = useCallback(async () => {
    if (!projectId) return;
    console.log("[Dashboard] Loading inferred individuals...");
    try {
      const res = await apiClient.get<any>(
        `/api/ontology/${encodeProjectId(projectId)}/reasoner/inferred-individuals?reasonerType=${selectedReasoner}`,
      );
      const payload = res?.data || res;
      const individuals = payload?.individuals || payload?.data?.individuals || [];
      console.log(
        "[Dashboard] Inferred individuals loaded:",
        Array.isArray(individuals) ? individuals.length : 0,
        "items",
      );
      setInferredIndividuals(Array.isArray(individuals) ? individuals : []);
    } catch (error) {
      console.error("[Dashboard] Failed to load inferred individuals:", error);
      setInferredIndividuals([]);
    }
  }, [projectId, selectedReasoner]);

  const loadClassInstances = useCallback(async () => {
    if (!projectId || !selectedClassForIndividuals) {
      setClassInstances([]);
      return;
    }
    setClassInstancesLoading(true);
    try {
      const response = await apiClient.get<any>(
        withDraftScope(`/api/ontology/classes/instances/${projectId}?classIri=${encodeURIComponent(selectedClassForIndividuals.id)}`),
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

  // Refresh instances whenever the IndividualsByClass tab becomes active (handles stale data
  // after individuals are created in the Entities tab while this tab was in the background)
  useEffect(() => {
    if (mainTab === "IndividualsByClass" && selectedClassForIndividuals) {
      loadClassInstances();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mainTab]);

  const startReasoner = useCallback(async () => {
    if (!projectId) {
      notificationService.error("No Ontology Loaded", "Please load an ontology first");
      return;
    }

    if (isReasonerLoading) {
      return;
    }

    setIsReasonerLoading(true);
    setIsReasonerRunning(true);

    try {
      const reasonerType = normalizeReasonerType(selectedReasoner);
      const results = await fetchReasonerBundle(reasonerType);
      setReasonerResults(results);

      // Realize individuals (inferred types) after classification
      try {
        await apiClient.post(`/plugin-service/api/reasoner/${encodeURIComponent(projectId)}/realize`, {
          reasonerType,
        });
      } catch (realizeError) {
        console.warn("[Dashboard] Realization step failed (non-fatal):", realizeError);
      }

      // After successful classification, load full recursive hierarchies from the main API
      // This ensures we have the full depth like Desktop Protégé, not just the bundle's view
      console.log("[Dashboard] Reasoner completed, loading full recursive hierarchies...");

      // Load hierarchies sequentially to avoid overwhelming GraphDB
      await loadInferredHierarchy();
      await loadInferredObjectPropertyHierarchy();
      await loadInferredDataPropertyHierarchy();
      await loadInferredAnnotationPropertyHierarchy();
      await loadInferredDatatypes();
      await loadInferredIndividuals();

      console.log("[Dashboard] ✅ All inferred hierarchies processed");

      // Refresh class instances if user is viewing Individuals by Class
      if (selectedClassForIndividuals) {
        await loadClassInstances();
      }

      // Automatically switch Classes tab to inferred mode to show the inferred hierarchy
      setHierarchyViewModes((prev) => ({ ...prev, Classes: "inferred" }));
      console.log("[Dashboard] ✅ Automatically switched Classes tab to inferred mode");

      notificationService.success(
        "Classification Complete",
        `${selectedReasoner} reasoner completed successfully. View inferred hierarchy in Entities > Classes tab.`,
      );
    } catch (error: any) {
      console.error("[Dashboard] Reasoner error:", error);
      notificationService.error(
        "Classification Failed",
        error?.response?.data?.error || error?.message || "Classification failed",
      );
      setIsReasonerRunning(false);
    } finally {
      setIsReasonerLoading(false);
    }
  }, [
    fetchReasonerBundle,
    isReasonerLoading,
    projectId,
    selectedReasoner,
    loadInferredHierarchy,
    loadInferredObjectPropertyHierarchy,
    loadInferredDataPropertyHierarchy,
    loadInferredAnnotationPropertyHierarchy,
    loadInferredDatatypes,
    loadInferredIndividuals,
    loadClassInstances,
    selectedClassForIndividuals,
  ]);

  const stopReasoner = useCallback(async () => {
    if (projectId) {
      try {
        await apiClient.post(`/api/ontology/${encodeURIComponent(projectId)}/reasoner/stop`, {});
        await apiClient.post(`/plugin-service/api/reasoner/${encodeURIComponent(projectId)}/stop`, {});
      } catch (error) {
        console.warn("[Dashboard] Stop reasoner API failed (local state cleared):", error);
      }
    }
    setIsReasonerRunning(false);
    setIsReasonerLoading(false);
    setReasonerResults(null);
    notificationService.success("Reasoner Stopped", "Reasoner session has been disposed");
  }, [projectId]);

  // Reasoner state belongs to a single project — reset when switching projects so the
  // Start button isn't left permanently disabled by a stale isReasonerRunning flag.
  useEffect(() => {
    setIsReasonerRunning(false);
    setIsReasonerLoading(false);
    setReasonerResults(null);
    setInferredClassHierarchy([]);
  }, [projectId]);

  const toggleReasonerSync = useCallback(() => {
    const newSyncState = !isReasonerSynced;
    setIsReasonerSynced(newSyncState);
    console.log("[Dashboard] Reasoner auto-sync:", newSyncState ? "enabled" : "disabled");
    if (newSyncState) {
      notificationService.success("Auto-sync Enabled", "Reasoner will automatically re-run on changes");
    } else {
      notificationService.info("Auto-sync Disabled", "Reasoner will only run manually");
    }
  }, [isReasonerSynced]);

  const handleSelectReasoner = useCallback(
    (reasoner: string) => {
      // Stop current reasoner if running
      if (isReasonerRunning) {
        setIsReasonerRunning(false);
        setReasonerResults(null);
        notificationService.info("Reasoner Stopped", "Previous reasoner stopped due to type change");
      }

      setSelectedReasoner(reasoner);

      // Show reasoner description
      const descriptions: Record<string, string> = {
        HermiT: "Hypertableau-based reasoner with full OWL 2 DL support - best for complex ontologies",
        ELK: "High-performance reasoner optimized for EL++ profile - best for large taxonomies",
        Pellet: "Complete OWL DL reasoner with SWRL support",
        Openllet: "Modern fork of Pellet with improved performance and OWL API 5 support",
        Structural: "Lightweight structural reasoner - fast but limited inference",
      };

      notificationService.info("Reasoner Selected", descriptions[reasoner] || `${reasoner} reasoner is now active`);
    },
    [isReasonerRunning],
  );

  const checkConsistency = useCallback(async () => {
    if (!projectId) {
      notificationService.error("No Ontology Loaded", "Please load an ontology first");
      return;
    }

    try {
      setIsConsistencyLoading(true);
      const reasonerType = normalizeReasonerType(selectedReasoner);
      const encodedProjectId = encodeURIComponent(projectId);
      const resp = await apiClient.post(`/plugin-service/api/reasoner/${encodedProjectId}/consistency`, {
        reasonerType,
      });
      const data = extractResponseData(resp);
      setConsistencyResult(data);

      const inconsistent = data?.consistent === false || data?.isConsistent === false;
      if (inconsistent) {
        notificationService.error("Ontology Inconsistent", "Open the explanation to inspect the causes.");
      } else {
        notificationService.success("Consistency Checked", `${selectedReasoner} reports the ontology is consistent`);
      }
    } catch (error: any) {
      console.error("[Dashboard] Consistency check failed:", error);
      setConsistencyResult({ error: error?.message || "Consistency check failed" });
      notificationService.error("Consistency Check Failed", error?.message || "Unable to check ontology consistency");
    } finally {
      setIsConsistencyLoading(false);
    }
  }, [projectId, selectedReasoner]);

  const explainInconsistency = useCallback(async () => {
    if (!projectId) {
      notificationService.error("No Ontology Loaded", "Please load an ontology first");
      return;
    }

    try {
      setExplanationState({ open: true, loading: true, data: null, error: null });
      const reasonerType = normalizeReasonerType(selectedReasoner);
      const encodedProjectId = encodeURIComponent(projectId);
      const resp = await apiClient.post(`/plugin-service/api/reasoner/${encodedProjectId}/explain-inconsistency`, {
        reasonerType,
      });
      const data = extractResponseData(resp);
      setExplanationState({ open: true, loading: false, data, error: null });
      notificationService.info("Explanation Ready", "Review the inconsistency report.");
    } catch (error: any) {
      console.error("[Dashboard] Explain inconsistency failed:", error);
      setExplanationState({
        open: true,
        loading: false,
        data: null,
        error: error?.message || "Failed to explain inconsistency",
      });
      notificationService.error("Explain Inconsistency Failed", error?.message || "Could not compute explanation");
    }
  }, [projectId, selectedReasoner]);

  const setCurrentHierarchyViewMode = (mode: "asserted" | "inferred") => {
    setHierarchyViewModes((prev) => ({ ...prev, [entitiesTab]: mode }));
  };

  const resolveDatatypeIri = (datatype?: string) => {
    if (!datatype) return undefined;
    return DATATYPE_IRI_MAP[datatype] || datatype;
  };

  const shortenDatatype = (datatype?: string) => {
    if (!datatype) return "xsd:string";
    const entry = Object.entries(DATATYPE_IRI_MAP).find(([, iri]) => iri === datatype);
    if (entry) return entry[0];
    if (datatype.includes("#")) {
      return `xsd:${datatype.split("#").pop()}`;
    }
    return datatype;
  };

  // Calculate active users count for current project
  const activeUsersInProject = Array.from(collaboration.state.activeUsers.values()).filter(
    (user) => user.projectId === projectId,
  );
  const hasMultipleActiveUsers = activeUsersInProject.length > 1;
  // #endregion

  // #region Data Fetching and Initialization
  // Plugin marketplace handlers

  // Lightweight, cached (24h) plugin update check. Fires on mount and on
  // file-open (see handleLoadProjectFile). Results drive the pulsing dot on
  // View → Plugin Marketplace.
  const runPluginUpdateCheck = useCallback(async (force = false) => {
    try {
      const updates = await checkForPluginUpdates(force);
      setHasPluginUpdates(updates.length > 0);
    } catch {
      /* silent */
    }
  }, []);

  useEffect(() => {
    runPluginUpdateCheck();
  }, [runPluginUpdateCheck]);

  const handleInstallPlugin = useCallback(async (pluginId: string, version?: string) => {
    try {
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: true, error: null } }));

      // Use pluginLoader to install and load the plugin (version optional for rollback/pinned install)
      await pluginLoader.installPlugin(pluginId, version);
      await pluginLoader.loadPlugin(pluginId);

      // Only update state if installation and loading succeeded
      setInstalledPlugins((prev) => new Set([...prev, pluginId]));
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: false, error: null } }));

      // Map plugin IDs to their corresponding tab IDs and add to visible tabs
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

      console.log(`[Dashboard] Plugin ${pluginId} installed and loaded`);
      notificationService.success("Plugin Installed", `${pluginId} has been installed successfully`);
      clearPluginUpdateCache();
      setHasPluginUpdates(false);
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

      // Make sure to uninstall if loading failed
      try {
        await pluginLoader.uninstallPlugin(pluginId);
      } catch (uninstallError) {
        console.error("Failed to cleanup after failed installation:", uninstallError);
      }

      throw error;
    }
  }, []);

  // Handler to retry loading an installed plugin
  const handleRetryLoadPlugin = useCallback(async (pluginId: string) => {
    try {
      setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: true, error: null } }));

      const component = await pluginLoader.loadPlugin(pluginId);

      if (component) {
        setPluginLoadingStates((prev) => ({ ...prev, [pluginId]: { loading: false, error: null } }));
        // Force re-render by updating installedPlugins
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

      // Map plugin IDs to internal plugin IDs and deactivate
      // Remove the corresponding tab from visible tabs
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
        // Switch to Entities tab if the current tab is being removed
        setMainTab((current) => (current === tabId ? "Entities" : current));
      }

      console.log(`[Dashboard] Plugin ${pluginId} uninstalled`);
      clearPluginUpdateCache();
      setHasPluginUpdates(false);
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

          console.log(`[Dashboard] Project ${currentProjectId} status:`, status);

          if (status === "COMPLETED") {
            const topLevel = Number(statusRes?.data?.topLevelClasses ?? 0);
            const hierarchyReady = statusRes?.data?.hierarchyReady ?? topLevel > 0;
            const graphReady = statusRes?.data?.graphReady ?? (Number(statusRes?.data?.graphSize ?? 0) > 0);
            if (hierarchyReady || topLevel > 0) {
              setLoadingStatusMessage("");
              return { ready: true, status };
            }
            // Graph loaded but class tree still building — keep polling. Returning early
            // here used to abort waitForProcessingComplete during merge/re-import and
            // left the editor with an empty class tree while the OWL file on disk was fine.
            if (graphReady || statusRes?.data?.hierarchyWarming) {
              setLoadingStatusMessage(
                sanitizeImportMessage(statusRes?.data?.statusMessage) || "Loading class hierarchy…",
              );
              setIsHierarchyLoading(true);
              if (Date.now() >= deadline) {
                return {
                  ready: false,
                  status: "HIERARCHY_WARMING",
                  error: "Class hierarchy is still building. The file is large — try opening it again in a few minutes.",
                };
              }
              await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
              continue;
            }
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

          // Unknown status — allow loading attempt
          console.warn("[Dashboard] Unknown status, allowing load attempt:", status);
          return { ready: true, status };
        } catch (error: any) {
          // 404 = editor has no import record yet (file only in auth/GridFS until first open).
          if (error?.status === 404) {
            console.log("[Dashboard] No editor status record yet for", currentProjectId);
            return { ready: true, status: "NOT_REGISTERED" };
          }
          console.error("[Dashboard] Error checking project status:", error);
          return { ready: true };
        }
      }
    },
    [],
  );

  const resolveUserEmail = useCallback(() => {
    if (user?.email) return user.email;
    const token = user?.token || (typeof localStorage !== "undefined" ? localStorage.getItem("authToken") : null);
    return decodeTokenEmail(token);
  }, [user?.email, user?.token]);

  const fetchData = useCallback(
    async (currentProjectId: string, waitForCompletion = false, parentProjectId?: string, forceRefresh = false) => {
      setLoadFailure(null);
      // Skip re-fetching if this project is already loaded and no force refresh requested
      if (!forceRefresh && currentProjectId === projectId && classHierarchy.length > 0 && metadata) {
        console.log("[Dashboard] ⚡ Project already loaded, skipping re-fetch:", currentProjectId);
        setIsInitialLoading(false);
        setIsHierarchyLoading(false);
        setIsMetadataLoading(false);
        setIsPropertiesLoading(false);
        setIsIndividualsLoading(false);
        setIsAnnotationPropertiesLoading(false);
        setIsDatatypesLoading(false);
        setLoadingStatusMessage("");
        return null;
      }

      // Don't block UI - let user continue working
      setSelectedItem(null);
      setSearchQuery("");

      // Show loading indicator if user chose to wait
      if (waitForCompletion) {
        setIsInitialLoading(true);
      }

      // Determine if we're in admin flow (parentProjectId provided means files should be loaded from project library)
      const isAdminFlow = !!parentProjectId;

      // Restore the user's last explicit sync-mode choice for this project immediately,
      // regardless of admin/non-admin flow. The admin (file-open) flow — the common path
      // when reopening a specific file — has no sync-mode restoration logic of its own
      // further down, so without this it silently stays on the "public" useState default
      // on every reload, even if the user had explicitly switched to Draft.
      if (currentProjectId) {
        const earlySyncModeKey = `ontocode_sync_mode_${currentProjectId}`;
        const earlySavedSyncMode = localStorage.getItem(earlySyncModeKey);
        if (earlySavedSyncMode !== null) {
          const applyDirectly = earlySavedSyncMode === "public";
          ontologyMutationService.setRealTimeSync(applyDirectly);
          setSyncMode(applyDirectly ? "public" : "private");
          console.log("[Dashboard] 🔍 Early syncMode restore (covers admin flow):", {
            currentProjectId,
            isAdminFlow,
            earlySavedSyncMode,
            applyDirectly,
          });
        }
      }

      // Notify user that loading has started
      console.log(`Loading ontology "${currentProjectId}"...`);
      console.log("[Dashboard] 🔄 Fetching data for project:", currentProjectId);
      console.log("[Dashboard]  Admin flow:", isAdminFlow, "Parent project:", parentProjectId);
      console.log("[Dashboard] 👤 User context:", {
        email: user?.email,
        username: user?.username,
        isAdmin: user?.isAdmin,
        workspaceId: user?.workspaceId,
      });

      // Request collaboration status when loading a new file
      if (window.vscode) {
        window.vscode.postMessage({ type: "requestCollaborationStatus" });
      }

      let loadGeneration = 0;
      let keepInitialLoadingForHierarchy = false;

      try {
        // Skip status check for files being reopened (not fresh uploads)
        // Only check status if this is a fresh import (isExpectingFileReady = true)
        // This significantly improves load time for files already in GraphDB
        if (!forceRefresh && !isExpectingFileReady) {
          console.log("[Dashboard] ⚡ Skipping status check for existing file - loading directly from GraphDB");
        } else if (!forceRefresh) {
          // Wait for processing to complete before fetching data (fresh imports only)
          console.log("[Dashboard] Waiting for file processing to complete...");
          const result = await waitForProcessingComplete(currentProjectId);

          if (!result.ready) {
            if (result.status === "HIERARCHY_WARMING") {
              console.log("[Dashboard] Graph ready, hierarchy still warming — proceeding with retry loop");
              setIsHierarchyLoading(true);
              setLoadingStatusMessage(result.error || "Loading class tree…");
            } else {
              const errorTitle = result.status === "ERROR" ? "Import Failed" : "Loading Failed";
              const errorMessage = result.error || "Unable to load ontology";

              console.error(`[Dashboard] Cannot load project: ${result.status}`, result.error);
              notificationService.error(errorTitle, errorMessage);
              setIsInitialLoading(false);
              return null;
            }
          }
        } else {
          console.log("[Dashboard] ⚡ Force refresh mode - skipping processing status check");
        }

        console.log("[Dashboard] File processing complete, fetching ontology data...");
        console.log("[Dashboard] 📡 Loading data from GraphDB database for:", currentProjectId);

        // Encode project ID for use in URL paths (handles slashes in hierarchical project IDs)
        const encodedProjectId = encodeURIComponent(currentProjectId);

        // Add cache-busting parameter when forceRefresh is true to bypass any HTTP/browser caching
        const cacheBuster = forceRefresh ? `?_t=${Date.now()}` : "";
        // Explicit opt-in the backend requires before scoping reads to the draft graph —
        // userId alone isn't a scope signal (it's always resolvable via header/JWT, even
        // while viewing Public). See hierarchyUserId/draftScopeParam comment further below.
        const entityDraftScopeQuery = isDraftScopeActive()
          ? (cacheBuster ? "&draft=true" : "?draft=true")
          : "";

        // Abort any previous in-flight fetch and create a fresh controller for this load
        loadGeneration = ++fetchDataGenerationRef.current;
        if (fetchAbortControllerRef.current) {
          fetchAbortControllerRef.current.abort();
        }
        if (isDesktop()) {
          desktopDeferredSectionsLoadedRef.current.clear();
        }
        setIsHierarchyLoading(true);
        setIsMetadataLoading(true);
        setIsPropertiesLoading(true);
        setIsIndividualsLoading(true);
        setIsAnnotationPropertiesLoading(true);
        setIsDatatypesLoading(true);
        setLoadingStatusMessage(
          isDesktop() ? "Loading ontology into memory…" : "Loading classes...",
        );
        const abortController = new AbortController();
        fetchAbortControllerRef.current = abortController;
        const signal = abortController.signal;
        const isStaleLoad = () => signal.aborted || fetchDataGenerationRef.current !== loadGeneration;

        const applyDeclarationCounts = (countsRes: any) => {
          const patch = extractDeclarationCountsPatch(countsRes);
          if (!patch) return;
          setMetadata((prev) => ({
            ...(prev || {}),
            ...patch,
          }) as OntologyMetadata);
        };

        const applyMetadataResponse = (metadataRes: any) => {
          if (!metadataRes) return;
          const metadataData = metadataRes?.data || metadataRes;
          if (!metadataData || typeof metadataData !== "object") return;
          const annotationsData = metadataData?.annotations || [];
          const imports = metadataData?.imports || [];
          const gciAxioms = metadataData?.axioms || [];
          if (metadataData?.filename) setActiveFileName(metadataData.filename);
          setMetadata((prev) => ({
            ...metadataData,
            annotations: annotationsData,
            prefixes: metadataData?.prefixes || [],
            // Preserve OWLAPI-sourced counts when the metadata API returns 0.
            // Use a strict positive-number check so a genuine 0 from metadata doesn't
            // erase a valid OWLAPI count already stored in prev.
            classCount: (metadataData?.classCount > 0 ? metadataData.classCount : null)
              ?? (metadataData?.counts?.classes > 0 ? metadataData.counts.classes : null)
              ?? (prev as any)?.classCount
              ?? 0,
            objectPropertyCount: (metadataData?.objectPropertyCount > 0 ? metadataData.objectPropertyCount : null)
              ?? (metadataData?.counts?.objectProperties > 0 ? metadataData.counts.objectProperties : null)
              ?? (prev as any)?.objectPropertyCount
              ?? 0,
            dataPropertyCount: (metadataData?.dataPropertyCount > 0 ? metadataData.dataPropertyCount : null)
              ?? (metadataData?.counts?.dataProperties > 0 ? metadataData.counts.dataProperties : null)
              ?? (prev as any)?.dataPropertyCount
              ?? 0,
            individualCount: (metadataData?.individualCount > 0 ? metadataData.individualCount : null)
              ?? (metadataData?.counts?.individuals > 0 ? metadataData.counts.individuals : null)
              ?? (prev as any)?.individualCount
              ?? 0,
            annotationPropertyCount: (metadataData?.annotationPropertyCount > 0 ? metadataData.annotationPropertyCount : null)
              ?? (metadataData?.counts?.annotationProperties > 0 ? metadataData.counts.annotationProperties : null)
              ?? (prev as any)?.annotationPropertyCount
              ?? 0,
            gciCount: metadataData?.gciCount ?? (prev as any)?.gciCount,
            hiddenGciCount: metadataData?.hiddenGciCount ?? (prev as any)?.hiddenGciCount,
          }) as OntologyMetadata);
          setOntologyImports(Array.isArray(imports) ? imports : []);
          setGeneralClassAxioms(
            Array.isArray(gciAxioms)
              ? gciAxioms.map((axiom: any) => ({
                  id: axiom.id,
                  value: axiom.value,
                  subClass: axiom.subClass || "",
                  superClass: axiom.superClass || "",
                  definition: axiom.subClass || axiom.definition || "",
                  superClassIri: axiom.superClass || axiom.superClassIri || "",
                  subExpression: axiom.subClass || axiom.subExpression || "",
                }))
              : [],
          );
          setOntologyAnnotations(normalizeOntologyAnnotations(annotationsData));
          setPrefixMappings(normalizePrefixMappings(metadataData?.prefixes));
          // NOTE: do NOT call applyDeclarationCounts(metadataData) here.
          // The setMetadata above already handles all counts with prev fallback.
          // A second setMetadata call would queue after and overwrite the preserved classCount.
        };

        const applyPropertiesResponse = (propertiesRes: any) => {
          if (!propertiesRes) return;
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
          opList.forEach((p: Property) => opMap.set(p.id, { ...p, children: [], hasChildren: false }));
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
          dpList.forEach((p: Property) => dpMap.set(p.id, { ...p, children: [], hasChildren: false }));
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
        };

        let desktopOwlapiReady = false;
        if (isDesktop()) {
          const warm = await warmOntologyInMemory(currentProjectId, {
            timeoutMs: 300_000,
            onStatus: (msg) => {
              if (!isStaleLoad()) setLoadingStatusMessage(msg);
            },
          });
          if (isStaleLoad()) return null;
          desktopOwlapiReady = warm.ready;
          if (warm.ready) {
            owlapiReadyHandledRef.current = currentProjectId;
            desktopHierarchyDeferredForProject.current = null;
            console.log("[Dashboard] OWLAPI fast-open ready — using in-memory hierarchy");
            applyDeclarationCounts(warm);
            setLoadingStatusMessage("Loading classes…");
            desktopDeferredSectionsLoadedRef.current.clear();
          } else if (warm.sparqlFallback) {
            console.warn(
              "[Dashboard] OWLAPI fast-open unavailable (no on-disk file or insufficient heap) — SPARQL/snapshot fallback",
            );
            if (isDesktop()) {
              desktopHierarchyDeferredForProject.current = currentProjectId;
              setIsHierarchyLoading(true);
              setLoadingStatusMessage("Opening ontology (fast path)…");
            }
          } else {
            console.log("[Dashboard] OWLAPI warm in progress — deferring hierarchy until fast-open completes");
            desktopHierarchyDeferredForProject.current = currentProjectId;
            setIsHierarchyLoading(true);
            setLoadingStatusMessage("Opening ontology (fast path)…");
          }
        } else {
          console.log("[Dashboard] Web mode — Fuseki/Mongo hierarchy (no auto OWLAPI warm)");
        }

        // Phase 1: classes first — unblock the editor immediately
        // Instance-counts is a full-graph SPARQL GROUP BY scan. Firing it before top-level
        // classes saturates Fuseki and can delay the class tree response enough to trigger
        // the 2-minute retry loop. Defer it until after the class tree resolves.
        let instanceCountsData: any = {};
        setClassInstanceCounts({});

        // On desktop: skip SPARQL hierarchy fetch when OWLAPI is still loading.
        // We already have the hierarchy skeleton visible (isHierarchyLoading=true).
        // The owlapiReadyHandledRef poller will call refreshClassHierarchy() once
        // OWLAPI is confirmed ready, producing ONE clean render with the full count.
        // Edge case: if OWLAPI never loads (OOM/corrupt file) the poller will NOT fire
        // and the skeleton will stay until the user navigates away — acceptable because
        // the fallback means OWLAPI genuinely cannot serve the ontology.
        if (!desktopOwlapiReady && desktopHierarchyDeferredForProject.current === currentProjectId) {
          console.log("[Dashboard] Deferring class hierarchy — awaiting OWLAPI fast-open");
          setIsHierarchyLoading(true);
          setLoadingStatusMessage("Opening ontology (fast path)…");
        }

        // Explicit userId (not just JWT) so the backend can scope reads to this user's
        // draft graph when they're drafting — mirrors how mutation calls already pass it.
        const hierarchyUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
        // userId alone isn't a scope signal — it's always resolvable via JWT even while
        // viewing Public. draft=true is the explicit, request-level opt-in the backend
        // requires before it will read from the draft graph instead of main.
        const draftScopeParam = isDraftScopeActive() ? "&draft=true" : "";
        const topLevelClassesRes = (!desktopOwlapiReady && desktopHierarchyDeferredForProject.current === currentProjectId) ? null : await apiClient
          .get<any>(
            `/api/ontology/classes/top-level/${encodedProjectId}?limit=5000&userId=${encodeURIComponent(hierarchyUserId)}${draftScopeParam}${cacheBuster ? "&" + cacheBuster.substring(1) : ""}`,
            undefined,
            { signal },
          )
          .catch((e: any) => {
            if (e?.name === "AbortError" || e?.code === "ERR_CANCELED") throw e;
            const status = e?.status ?? e?.response?.status;
            console.error("[Dashboard] Top-level class fetch failed:", e?.message || e);
            if (status === 503) {
              if (!isStaleLoad()) {
                setIsHierarchyLoading(true);
                setLoadingStatusMessage("Ontology editor is busy — still loading…");
              }
            } else {
              setLoadingStatusMessage("Could not load the class hierarchy. Retrying may help.");
            }
            return null;
          });

        const hierarchyBuilding =
          topLevelClassesRes &&
          (topLevelClassesRes?.hierarchyReady === false ||
            topLevelClassesRes?.status === 202 ||
            topLevelClassesRes?.success === false);
        if (hierarchyBuilding && !isStaleLoad()) {
          setLoadingStatusMessage(
            topLevelClassesRes?.message ||
              "Loading class tree…",
          );
          setIsHierarchyLoading(true);
        }

        let topLevelClasses: any[] = [];
        const tlRes = topLevelClassesRes?.data ?? topLevelClassesRes;
        if (topLevelClassesRes && !hierarchyBuilding) {
          topLevelClasses = Array.isArray(tlRes?.classes)
            ? tlRes.classes
            : Array.isArray(tlRes?.data?.classes)
              ? tlRes.data.classes
              : Array.isArray(tlRes?.data)
                ? tlRes.data
                : Array.isArray(topLevelClassesRes)
                  ? topLevelClassesRes
                  : [];
        }
        const isTruncated = !!(tlRes?.truncated);
        // Preserve undefined vs 0: undefined means server didn't confirm count;
        // 0 means server confirmed the ontology has no top-level classes.
        const tlTotal = tlRes?.topLevelTotal !== undefined ? Number(tlRes.topLevelTotal) : undefined;
        if (!isStaleLoad()) {
          setTopLevelTruncated(isTruncated);
          setTopLevelTotal(tlTotal ?? 0);
        }
        const topLevelNodes: TreeNode[] = topLevelClasses.map((c: TopLevelClass) => ({
          ...c,
          label: c.label ?? c.id ?? "",
          children: [],
          hasChildren: c.hasChildren !== false,
          subClassOfAxioms: [
            { id: "http://www.w3.org/2002/07/owl#Thing", type: "SubClassOf", definition: "owl:Thing" },
          ],
        }));
        const resolvedCounts = instanceCountsData && typeof instanceCountsData === "object" ? instanceCountsData : {};
        // Sentinel node — rendered as "Load more" button by EntityHierarchy when top-level is truncated.
        const sentinelNode: TreeNode = {
          id: "__load_more_top_level__",
          label: `Load more classes…`,
          children: [],
          hasChildren: false,
          annotations: {},
        };
        const owlThingChildren = applyInstanceCountsToTree(topLevelNodes, resolvedCounts);
        if (isTruncated) owlThingChildren.push(sentinelNode);
        const owlThingNode: TreeNode = {
          id: "http://www.w3.org/2002/07/owl#Thing",
          label: "owl:Thing",
          children: owlThingChildren,
          hasChildren: topLevelNodes.length > 0,
          annotations: {},
        };
        const applyTopLevelToHierarchy = (classes: TopLevelClass[], truncated = false, total = 0) => {
          const nodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
            ...c,
            label: c.label ?? c.id ?? "",
            children: [],
            hasChildren: c.hasChildren !== false,
            subClassOfAxioms: [
              { id: "http://www.w3.org/2002/07/owl#Thing", type: "SubClassOf", definition: "owl:Thing" },
            ],
          }));
          const children = applyInstanceCountsToTree(nodes, resolvedCounts);
          if (truncated) {
            children.push({
              id: "__load_more_top_level__",
              label: "Load more classes…",
              children: [],
              hasChildren: false,
              annotations: {},
            });
          }
          setClassHierarchy(
            applyInstanceCountsToTree(
              [
                {
                  id: "http://www.w3.org/2002/07/owl#Thing",
                  label: "owl:Thing",
                  children,
                  hasChildren: nodes.length > 0,
                  annotations: {},
                },
              ],
              resolvedCounts,
            ),
          );
          if (!isStaleLoad()) {
            setTopLevelTruncated(truncated);
            setTopLevelTotal(total);
          }
        };

        if (!hierarchyBuilding) {
          const hierarchyDeferred =
            isDesktop() && desktopHierarchyDeferredForProject.current === currentProjectId;
          if (!hierarchyDeferred) {
            applyTopLevelToHierarchy(topLevelClasses, isTruncated, tlTotal ?? 0);
            if (!isStaleLoad()) {
              if (topLevelClasses.length > 0) {
                setLoadingStatusMessage("");
                setIsHierarchyLoading(false);
                notificationService.success("Ready", "Class tree is available.");
              } else if (tlTotal === 0) {
                // Server confirmed empty ontology — stop loading immediately
                setLoadingStatusMessage("");
                setIsHierarchyLoading(false);
              }
            }
          }
        }
        applyDeclarationCounts(topLevelClassesRes);

        // Fire instance-counts AFTER the class tree resolves — avoids Fuseki contention
        // that could delay the top-level response and trigger the retry loop.
        if (!desktopOwlapiReady && !isStaleLoad()) {
          apiClient
            .get<any>(withDraftScope(`/api/ontology/classes/instance-counts/${encodedProjectId}${cacheBuster}`), undefined, { signal })
            .then((instanceCountsRes: any) => {
              if (isStaleLoad()) return;
              const payload = instanceCountsRes?.data || instanceCountsRes;
              const data = payload?.data || payload || {};
              if (!data || typeof data !== "object") return;
              setClassInstanceCounts(data);
              setClassHierarchy((prev) => (prev.length > 0 ? applyInstanceCountsToTree(prev, data) : prev));
            })
            .catch((e: any) => {
              console.warn("[Dashboard] Instance counts fetch failed (non-blocking):", e?.message);
            });
        }

        // Only retry when the server response was non-null (we got a real response, not a timeout)
        // AND the server did not explicitly confirm 0 top-level classes (tlTotal === 0 means
        // the ontology is genuinely empty — no point polling for 120 seconds).
        const needsHierarchyRetry =
          topLevelClassesRes !== null && !hierarchyBuilding && topLevelClasses.length === 0 && (tlTotal ?? 0) > 0 && !isStaleLoad();
        if (needsHierarchyRetry) {
          setIsHierarchyLoading(true);
          setLoadingStatusMessage("Loading class hierarchy…");
          void (async () => {
            for (let i = 0; i < 300 && !signal.aborted; i++) {
              await new Promise((r) => setTimeout(r, 2000));
              try {
                const retry = await apiClient.get<any>(
                  `/api/ontology/classes/top-level/${encodedProjectId}?limit=5000&userId=${encodeURIComponent(hierarchyUserId)}${draftScopeParam}${cacheBuster ? "&" + cacheBuster.substring(1) : ""}`,
                  undefined,
                  { signal },
                );
                const classes = Array.isArray(retry?.classes)
                  ? retry.classes
                  : Array.isArray(retry?.data?.classes)
                    ? retry.data.classes
                    : [];
                if (classes.length > 0) {
                  applyTopLevelToHierarchy(classes, !!retry?.truncated, Number(retry?.topLevelTotal) || 0);
                  applyDeclarationCounts(retry);
                  setLoadingStatusMessage("");
                  setIsHierarchyLoading(false);
                  setIsInitialLoading(false);
                  notificationService.success("Ready", "Class tree is available.");
                  return;
                }
              } catch {
                /* retry */
              }
            }
            setIsHierarchyLoading(false);
            setIsInitialLoading(false);
            showToast("Could not load the class hierarchy. The ontology may be too large or the server timed out.", "error");
            onGoToProjectDashboardRef.current?.();
          })();
        } else if (hierarchyBuilding && !isStaleLoad()) {
          void (async () => {
            // Desktop: OWLAPI may still be loading the OWL file (top-level returned 202).
            // Poll cache-status for owlapiReady; cloud: poll for hierarchyReady.
            // Desktop gets a longer timeout (300 * 2s = 10 min) for large OWL files.
            const maxIter = isDesktop() ? 300 : 120;
            for (let i = 0; i < maxIter && !signal.aborted; i++) {
              await new Promise((r) => setTimeout(r, 2000));
              try {
                const cs = await apiClient.get<any>(
                  `/api/ontology/cache-status/${encodedProjectId}${cacheBuster}`,
                  undefined,
                  { signal },
                );
                applyDeclarationCounts(cs);
                if (cs?.hierarchyReady ?? cs?.owlapiReady) {
                  const retry = await apiClient.get<any>(
                    `/api/ontology/classes/top-level/${encodedProjectId}?limit=5000&userId=${encodeURIComponent(hierarchyUserId)}${draftScopeParam}${cacheBuster ? "&" + cacheBuster.substring(1) : ""}`,
                    undefined,
                    { signal },
                  );
                  const classes = Array.isArray(retry?.classes) ? retry.classes : [];
                  if (classes.length > 0) {
                    applyTopLevelToHierarchy(classes, !!retry?.truncated, Number(retry?.topLevelTotal) || 0);
                    applyDeclarationCounts(retry);
                    setLoadingStatusMessage("");
                    setIsHierarchyLoading(false);
                    setIsInitialLoading(false);
                    return;
                  }
                }
              } catch {
                /* retry */
              }
            }
            setIsHierarchyLoading(false);
            setIsInitialLoading(false);
            if (isDesktop()) {
              setLoadFailure({
                message: "Ontology index build timed out. The file may be too large for this machine.",
                projectId: currentProjectId,
              });
            } else {
              showToast("Ontology index build timed out. The file may be too large for the current configuration.", "error");
              onGoToProjectDashboardRef.current?.();
            }
          })();
        }
        if (isDesktop() && !isStaleLoad()) {
          try {
            const cs = await apiClient.get<any>(
              `/api/ontology/cache-status/${encodedProjectId}${cacheBuster}`,
              undefined,
              { signal },
            );
            if (cs?.owlapiReady ?? cs?.data?.owlapiReady) {
              desktopOwlapiReady = true;
            }
            applyDeclarationCounts(cs);
          } catch (e) {
            console.debug("[Dashboard] cache-status after top-level:", e);
          }
        }
        const desktopDeferredHierarchy = isDesktop() && !desktopOwlapiReady;
        const hierarchyVisible = topLevelClasses.length > 0 || tlTotal === 0;
        // Do not block the full-screen modal on OWLAPI warm — entity tabs wait in the
        // background (desktopOwlApiGate). Blocking here left users stuck with no error.
        keepInitialLoadingForHierarchy =
          !hierarchyVisible || hierarchyBuilding || needsHierarchyRetry;
        if (!hierarchyBuilding && !needsHierarchyRetry && hierarchyVisible) {
          setLoadingStatusMessage(desktopDeferredHierarchy ? "Loading ontology into memory…" : "");
          setIsHierarchyLoading(false);
          setIsInitialLoading(false);
        } else if (!hierarchyVisible && !isStaleLoad()) {
          setIsHierarchyLoading(true);
          setLoadingStatusMessage((prev) => prev || "Loading class tree…");
        }

        // Desktop: if class tree is already visible but OWLAPI still warming, close the
        // modal as soon as the in-memory model is ready (or show failure after timeout).
        if (desktopDeferredHierarchy && hierarchyVisible && !isStaleLoad()) {
          void (async () => {
            for (let i = 0; i < 90 && !signal.aborted; i++) {
              await new Promise((r) => setTimeout(r, 2000));
              try {
                const cs = await apiClient.get<any>(
                  `/api/ontology/cache-status/${encodedProjectId}${cacheBuster}`,
                  undefined,
                  { signal },
                );
                applyDeclarationCounts(cs);
                if (cs?.owlapiReady ?? cs?.data?.owlapiReady) {
                  setLoadingStatusMessage("");
                  setIsInitialLoading(false);
                  return;
                }
                if (i === 2) {
                  void warmOntologyInMemory(currentProjectId, {
                    timeoutMs: 120_000,
                    onStatus: (m) => setLoadingStatusMessage(m || "Opening ontology (fast path)…"),
                  });
                }
              } catch {
                /* retry */
              }
            }
            setLoadingStatusMessage("");
            setIsInitialLoading(false);
          })();
        }

        // Phase 2: load other entity sections in background (tab spinners + bottom bar).
        if (!isStaleLoad()) {
          setIsMetadataLoading(true);
          setIsPropertiesLoading(true);
          setIsIndividualsLoading(true);
          setIsAnnotationPropertiesLoading(true);
          setIsDatatypesLoading(true);
        }

        // Desktop owlapi-first: Fuseki may not be synced yet — wait for OWLAPI before
        // properties/individuals/etc. (otherwise they 503 on SPARQL fallback).
        const desktopOwlApiGate: Promise<void> = (async () => {
          if (!isDesktop() || signal.aborted) return;
          setLoadingStatusMessage("Opening ontology (fast path)…");
          const warm = await warmOntologyInMemory(currentProjectId, { timeoutMs: 300_000 });
          if (!warm.ready && !signal.aborted) {
            await waitForDesktopOwlApiReady(currentProjectId, {
              timeoutMs: 300_000,
              pollMs: 2000,
              signal,
            });
          }
          if (!isStaleLoad() && !warm.ready) {
            console.warn("[Dashboard] OWLAPI warm still in progress — entity tabs will retry");
          }
          if (!isStaleLoad()) setLoadingStatusMessage("");
        })();

        void (async () => {
          await desktopOwlApiGate;
          if (isStaleLoad()) return;
          try {
            if (isDesktop()) {
              try {
                const cs = await apiClient.get<any>(
                  `/api/ontology/cache-status/${encodedProjectId}${cacheBuster}`,
                  undefined,
                  { signal },
                );
                if (!isStaleLoad()) applyDeclarationCounts(cs);
              } catch (e) {
                console.debug("[Dashboard] cache-status counts:", e);
              }
            }
            const res = await apiClient.get<any>(
              `/api/ontology/metadata/${encodedProjectId}${cacheBuster}${cacheBuster ? "&" : "?"}userId=${encodeURIComponent(hierarchyUserId)}${draftScopeParam}`,
              undefined,
              { signal },
            );
            if (!isStaleLoad()) applyMetadataResponse(res);

            // Web/large-file: class counts are computed async after import. If we opened
            // before METADATA_READY, the first response can still show 0 — poll a few times.
            if (!isDesktop() && !isStaleLoad()) {
              const firstCount = Number((res?.data || res)?.classCount ?? (res?.data || res)?.counts?.classes ?? 0);
              if (!(firstCount > 0)) {
                for (let i = 0; i < 20 && !signal.aborted; i++) {
                  await new Promise((r) => setTimeout(r, 3000));
                  if (isStaleLoad()) return;
                  try {
                    const again = await apiClient.get<any>(
                      `/api/ontology/metadata/${encodedProjectId}?_t=${Date.now()}&userId=${encodeURIComponent(hierarchyUserId)}${draftScopeParam}`,
                      undefined,
                      { signal },
                    );
                    const againCount = Number((again?.data || again)?.classCount ?? (again?.data || again)?.counts?.classes ?? 0);
                    if (againCount > 0) {
                      if (!isStaleLoad()) applyMetadataResponse(again);
                      break;
                    }
                  } catch {
                    /* retry */
                  }
                }
              }
            }
          } catch (e: any) {
            if (e?.name !== "AbortError" && e?.code !== "ERR_CANCELED") {
              console.error("[Dashboard] Metadata/counts load failed:", e?.message || e);
            }
          } finally {
            if (!isStaleLoad()) setIsMetadataLoading(false);
          }
        })();

        void (async () => {
          await desktopOwlApiGate;
          if (isStaleLoad()) return;
          try {
            const fetchUrl = `/api/ontology/properties/${encodedProjectId}${cacheBuster}${entityDraftScopeQuery}`;
            const res = isDesktop()
              ? await getOntologyListWithRetry<any>(fetchUrl, { signal, maxAttempts: 20, delayMs: 2000 })
              : await apiClient.get<any>(fetchUrl, undefined, { signal });
            if (!isStaleLoad() && res) {
              applyPropertiesResponse(res);
            }
          } catch (e: any) {
            if (e?.name !== "AbortError" && e?.code !== "ERR_CANCELED") {
              console.error("[Dashboard] Properties load failed:", e?.message || e);
            }
          } finally {
            if (!isStaleLoad()) setIsPropertiesLoading(false);
          }
        })();

        void (async () => {
          await desktopOwlApiGate;
          if (isStaleLoad()) return;
          try {
            const fetchUrl = `/api/ontology/individuals/${encodedProjectId}${cacheBuster}${entityDraftScopeQuery}`;
            const res = isDesktop()
              ? await getOntologyListWithRetry<any>(fetchUrl, { signal, maxAttempts: 20, delayMs: 2000 })
              : await apiClient.get<any>(fetchUrl, undefined, { signal });
            if (!isStaleLoad() && res) {
              setIndividuals(
                Array.isArray(res?.data)
                  ? res.data
                  : Array.isArray(res?.individuals)
                    ? res.individuals
                    : [],
              );
            }
          } catch (e: any) {
            if (e?.name !== "AbortError" && e?.code !== "ERR_CANCELED") {
              console.error("[Dashboard] Individuals load failed:", e?.message || e);
            }
          } finally {
            if (!isStaleLoad()) setIsIndividualsLoading(false);
          }
        })();

        void (async () => {
          await desktopOwlApiGate;
          if (isStaleLoad()) return;
          try {
            const fetchUrl = `/api/ontology/annotation-properties/${encodedProjectId}${cacheBuster}${entityDraftScopeQuery}`;
            const res = isDesktop()
              ? await getOntologyListWithRetry<any>(fetchUrl, { signal, maxAttempts: 20, delayMs: 2000 })
              : await apiClient.get<any>(fetchUrl, undefined, { signal });
            if (!isStaleLoad() && res) {
              const merged = mergeAnnotationProperties(
                (Array.isArray(res?.data)
                  ? res.data
                  : Array.isArray(res?.annotationProperties)
                    ? res.annotationProperties
                    : []
                ).map(mapAnnotationProperty),
              );
              setAnnotationProperties(merged);
              setAnnotationPropertyHierarchy(buildAnnotationPropertyHierarchy(merged));
            }
          } catch (e: any) {
            if (e?.name !== "AbortError" && e?.code !== "ERR_CANCELED") {
              console.error("[Dashboard] Annotation properties load failed:", e?.message || e);
            }
          } finally {
            if (!isStaleLoad()) setIsAnnotationPropertiesLoading(false);
          }
        })();

        void (async () => {
          await desktopOwlApiGate;
          if (isStaleLoad()) return;
          try {
            const fetchUrl = `/api/ontology/datatypes/${encodedProjectId}${cacheBuster}${entityDraftScopeQuery}`;
            const res = isDesktop()
              ? await getOntologyListWithRetry<any>(fetchUrl, { signal, maxAttempts: 20, delayMs: 2000 })
              : await apiClient.get<any>(fetchUrl, undefined, { signal });
            if (!isStaleLoad() && res) {
              setDatatypes(
                Array.isArray(res?.data) ? res.data : Array.isArray(res?.datatypes) ? res.datatypes : [],
              );
            }
          } catch (e: any) {
            if (e?.name !== "AbortError" && e?.code !== "ERR_CANCELED") {
              console.error("[Dashboard] Datatypes load failed:", e?.message || e);
            }
          } finally {
            if (!isStaleLoad()) setIsDatatypesLoading(false);
          }
        })();

        // Fetch files list separately (not in parallel to avoid blocking main data load)
        // Admin flow will fetch project-specific files later, regular users fetch all their files here
        console.log("[Dashboard] 🔍 File loading decision - isAdminFlow:", isAdminFlow);
        if (!isAdminFlow) {
          console.log("[Dashboard] ✅ Non-admin flow - fetching files for user");
          try {
            const lists = await fetchProjects();

            // Non-workspace web: apply directly to avoid draft loss when navigating away.
            // Desktop: always use Protege-style private draft until Save (local named graph).
            const isNonWorkspaceMode = !initialProjectId && !user?.workspaceId && !isDesktop();

            if (!lists) {
              console.warn("[Dashboard] ?? No project list available");
              setIsCurrentFileShared(false);
              if (isNonWorkspaceMode) {
                ontologyMutationService.setRealTimeSync(true);
                setSyncMode("public");
                console.log("[Dashboard] ?? Non-workspace mode - applying changes directly to GraphDB");
              } else {
                ontologyMutationService.setRealTimeSync(false);
                setSyncMode("private");
                console.log("[Dashboard] ?? File is private - using draft mode (click Save to apply changes)");
              }
            }

            const myProjectsList = Array.isArray(lists?.myFiles) ? lists.myFiles : [];
            const sharedProjectsList = Array.isArray(lists?.sharedFiles) ? lists.sharedFiles : [];

            // Check if current file is shared (for real-time collaboration)
            // Use the freshly fetched data, not state variables
            // A file is shared if:
            // 1. It's in sharedFiles list (shared WITH me by someone else)
            // 2. It's in myFiles and has sharedWith array (shared BY me with others)
            // 3. It's in myFiles and has project members > 1 (team collaboration)
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

            console.log("[Dashboard] 📊 File shared status:", isShared, "for project:", currentProjectId);
            console.log("[Dashboard] 📥 Shared WITH me:", isSharedWithMe);
            console.log("[Dashboard] 📤 Shared BY me:", isSharedByMe);
            console.log("[Dashboard] 👥 Has project members:", hasProjectMembers);
            console.log(
              "[Dashboard] 📋 Shared files list:",
              sharedProjectsList.map((f: any) => f.id),
            );
            console.log(
              "[Dashboard] 📋 My files list:",
              myProjectsList.map((f: any) => f.id),
            );

            // Configure mutation service: shared/live OR non-workspace web direct-write.
            // Desktop and private web projects use per-user draft graphs until Save.
            // localStorage is the immediate source (written on every explicit toggle, so it is
            // always up-to-date on the same device). On first visit to a project on a new
            // device (no localStorage entry), we await the DB once to pick up a cross-device
            // preference. That await only blocks on genuine first-visit; all other loads are instant.
            const syncModeKey = projectId ? `ontocode_sync_mode_${projectId}` : null;
            const savedSyncMode = syncModeKey ? localStorage.getItem(syncModeKey) : null;
            let shouldApplyDirectly: boolean;
            if (isNonWorkspaceMode) {
              // Non-workspace files have no durable draft storage — always apply directly
              // to avoid silently losing edits when navigating away.
              shouldApplyDirectly = true;
            } else if (savedSyncMode !== null) {
              // Trust the user's own explicit choice for this project on this device, even if
              // shared — a reload must not silently revert a mode the user just picked via the
              // toggle (localStorage is written on every explicit mode change).
              shouldApplyDirectly = savedSyncMode === "public";
            } else if (isShared) {
              shouldApplyDirectly = true;
            } else if (projectId) {
              // First visit on this device: fetch from DB (one-time cost) for cross-device restore.
              const dbSyncMode = await userPreferencesService.getSyncMode(projectId);
              if (dbSyncMode !== null) {
                shouldApplyDirectly = dbSyncMode === "public";
                if (syncModeKey) localStorage.setItem(syncModeKey, dbSyncMode);
              } else {
                shouldApplyDirectly = true;
              }
            } else {
              shouldApplyDirectly = true;
            }
            // TEMP DIAGNOSTIC: confirming why sync mode isn't restoring on reload.
            console.warn("[Dashboard] 🔍 syncMode restore decision:", {
              isNonWorkspaceMode,
              initialProjectId,
              workspaceId: user?.workspaceId,
              isDesktopFlag: isDesktop(),
              isShared,
              syncModeKey,
              savedSyncMode,
              shouldApplyDirectly,
            });
            ontologyMutationService.setRealTimeSync(shouldApplyDirectly);
            ontologyMutationService.setDraftRequired(false); // Clear any stale block from a prior project.
            setSyncMode(shouldApplyDirectly ? "public" : "private");

            if (!shouldApplyDirectly && projectId && !isShared && !isNonWorkspaceMode) {
              const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
              startDraftCopySession(projectId, effectiveUserId, { showModal: true });
            }

            // Check requireDraftForMembers — members stay in public view-only until they
            // explicitly choose "Switch to Draft Mode"; no auto-copy on project open.
            if (projectId && !isNonWorkspaceMode && !isShared) {
              const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
              draftTrackingService.getDraftSettings(projectId, effectiveUserId)
                .then(({ requireDraftForMembers: rdm, isOwner }) => {
                  setRequireDraftForMembers(rdm);
                  setIsProjectOwner(isOwner);
                  refreshOpenPRCount();
                  if ((rdm || isProjectDraftEditorRoleRef.current) && !isOwner && !isProjectViewerRoleRef.current) {
                    if (shouldApplyDirectly) {
                      // Member has no saved draft preference (public view) — block direct mutations
                      // so they must explicitly switch to Draft Mode before editing.
                      ontologyMutationService.setDraftRequired(true, () => setShowProPromptType('draftRequired'));
                    }
                    // When !shouldApplyDirectly: preference restore already started the copy above;
                    // mutations are already going to the draft graph — no block needed.
                  }
                })
                .catch(() => { /* getDraftSettings failed — non-blocking */ });
            }

            if (isDesktop() && !isShared) {
              console.log("[Dashboard] 📝 Desktop private draft mode (Protege-style — Save to publish)");
            } else if (isNonWorkspaceMode && !isShared) {
              console.log("[Dashboard] 📝 Non-workspace mode - mutations apply directly to GraphDB");
            }

            // Only start monitoring for shared files (real-time collaboration)
            if (isShared) {
              console.log("[Dashboard] 📤 File is shared - enabling real-time collaboration");

              // Start monitoring for changes from other users
              const handleDataChanged = async (changedProjectId: string) => {
                // Handle project deletion signal
                if (changedProjectId.startsWith("__deleted__:")) {
                  const deletedId = changedProjectId.replace("__deleted__:", "");
                  console.log("[Dashboard] ⚠️ Project deleted by another user:", deletedId);
                  notificationService.error("Project Deleted", "This project has been deleted by another user.");
                  return;
                }

                console.log("[Dashboard] 🔄 Change detected from another user! Refreshing data...");
                notificationService.info("New Changes Available", "Another user saved changes. Refreshing data...");

                // Refresh data with forceRefresh to bypass the cache guard
                await fetchData(changedProjectId, false, undefined, true);
                console.log("[Dashboard] ✅ Refresh complete, monitoring restarted");
              };

              try {
                const timestampData = await apiClient.get<{ updatedAt: string }>(
                  `/api/ontology/metadata/${currentProjectId}/timestamp`,
                );
                if (timestampData && timestampData.updatedAt) {
                  const currentTimestamp = new Date(timestampData.updatedAt).getTime();
                  syncService.startMonitoring(currentProjectId, handleDataChanged, currentTimestamp);
                  console.log("[Dashboard] 🔍 Started monitoring for changes (5 minutes)");
                }
              } catch (error) {
                console.warn("[Dashboard] Could not start change monitoring:", error);
              }
            } else {
              console.log("[Dashboard] 📝 File is private - using draft mode (click Save to apply changes)");
            }
          } catch (fileError) {
            console.error("[Dashboard] ❌ Failed to fetch files:", fileError);
            console.error(
              "[Dashboard] ❌ File error details:",
              fileError instanceof Error ? fileError.message : fileError,
            );
            setListOfFiles([]);
            setMyFiles([]);
            setSharedFiles([]);
          }
        } else {
          console.log("[Dashboard] ℹ️ Admin flow detected - skipping user file fetch (will use project files)");
        }
        // End of !isAdminFlow block

        // Stop any previous monitoring for this project
        syncService.stopMonitoring(currentProjectId);

        // Only fetch project-specific files in admin flow (from ProjectLibrary)
        // Regular users and workspace members already have their files loaded above
        if (isAdminFlow && parentProjectId) {
          console.log("[Dashboard] 📂 Admin flow - Fetching files from project:", parentProjectId);
          await fetchProjectFiles(parentProjectId);
        } else {
          console.log("[Dashboard] ℹ️ Regular user flow - files already loaded from user email query");
        }

        if (isDesktop() && !isStaleLoad()) {
          scheduleSilentDesktopFusekiSync(currentProjectId);
        }

        // Class-tree notification fires when top-level classes are applied (see hierarchy block above).
      } catch (error: any) {
        // Ignore cancellations – these happen when the user switches files mid-load
        if (error?.name === "AbortError" || error?.code === "ERR_CANCELED" || error?.message?.includes("aborted")) {
          console.log("[Dashboard] fetchData cancelled (user switched files)");
          if (fetchDataGenerationRef.current === loadGeneration) {
            setIsInitialLoading(false);
            setIsHierarchyLoading(false);
            setIsMetadataLoading(false);
            setIsPropertiesLoading(false);
            setIsIndividualsLoading(false);
            setIsAnnotationPropertiesLoading(false);
            setIsDatatypesLoading(false);
            setLoadingStatusMessage("");
          }
          return null;
        }
        console.error("Failed to fetch data:", error);

        // Notify user of the error
        notificationService.error("Loading Failed", `Failed to load ontology "${currentProjectId}". Please try again.`);
        if (fetchDataGenerationRef.current === loadGeneration) {
          setIsHierarchyLoading(false);
          setIsMetadataLoading(false);
          setIsPropertiesLoading(false);
          setIsIndividualsLoading(false);
          setIsAnnotationPropertiesLoading(false);
          setIsDatatypesLoading(false);
        }
      } finally {
        // Keep the modal spinner until top-level classes are visible (see keepInitialLoadingForHierarchy).
        if (fetchDataGenerationRef.current === loadGeneration && !keepInitialLoadingForHierarchy) {
          setIsInitialLoading(false);
        }
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
      console.log("[Dashboard] 🔄 Refreshing ontology annotations for project:", projectId);
      const response = await apiClient.get<any>(withDraftScope(`/api/ontology/metadata/${encodeProjectId(projectId)}/annotations`));
      const payload = response?.data || response;
      const data = payload?.data || payload || [];
      console.log("[Dashboard] 📥 Raw annotations data received:", data);
      const validAnnotations = normalizeOntologyAnnotations(data);
      console.log("[Dashboard] ✅ Valid annotations after filtering:", validAnnotations);

      setOntologyAnnotations(validAnnotations);
    } catch (error) {
      console.error("[Dashboard] Failed to refresh ontology annotations:", error);
    }
  };

  const refreshOntologyImports = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(withDraftScope(`/api/ontology/metadata/${encodeProjectId(projectId)}/imports`));
      const payload = response?.data || response;
      const data = payload?.data || payload || [];
      const validImports = Array.isArray(data) ? data : [];
      console.log("[Dashboard] 📥 Loaded imports from backend:", validImports);
      console.log(
        "[Dashboard] Local imports:",
        validImports.filter((imp: string) => !imp.startsWith("http://") && !imp.startsWith("https://")),
      );

      setOntologyImports(validImports);
    } catch (error) {
      console.error("[Dashboard] Failed to refresh ontology imports:", error);
    }
  };

  const refreshPrefixes = async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<any>(withDraftScope(`/api/ontology/ontology/prefixes/${encodeProjectId(projectId)}`));
      const payload = response?.data || response;
      const data = payload?.data || payload || {};
      setPrefixMappings(normalizePrefixMappings(data));
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
        .get(withDraftScope(`/api/ontology/metadata/${projectId}`))
        .then((res) => {
          const data = res?.data || res;
          setMetadata({ ...(metadata || {}), ...data });
        })
        .catch(() => {});
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

      // Add language if provided
      if (language && language.trim()) {
        payload.language = language.trim();
      }
      // Add datatype if provided (and no language)
      else if (datatype && datatype.trim()) {
        // If datatype looks like a language tag, send as language
        if (/^[a-z]{2}(-[A-Z]{2})?$/.test(datatype)) {
          payload.language = datatype;
        } else {
          // Otherwise it's a datatype IRI
          payload.datatype = datatype;
        }
      }

      console.log("[Dashboard] Adding annotation with payload:", payload);
      await apiClient.post(`/api/ontology/metadata/${projectId}/annotations`, { ...payload, ...draftBodyFields() });

      // Immediate optimistic UI update
      const newAnnotation: any = {
        propertyIri,
        value,
        datatype: payload.datatype || datatype,
        language: payload.language || language,
      };
      setOntologyAnnotations((prev) => [...prev, newAnnotation]);
      console.log("[Dashboard] ✅ Annotation added, optimistically updated UI");

      // Delay refresh to allow backend to process
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

      // Add language if provided
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

      console.log("[Dashboard] Updating annotation with payload:", payload);
      await apiClient.put(`/api/ontology/metadata/${projectId}/annotations`, { ...payload, ...draftBodyFields() });

      // Immediate optimistic UI update
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
      console.log("[Dashboard] ✅ Annotation updated, optimistically updated UI");

      // Then refresh from server
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
      // Build query string with URL-encoded parameters
      let queryString = `propertyIri=${encodeURIComponent(propertyIri)}&value=${encodeURIComponent(value)}`;

      // Backend expects 'language' parameter for language tags
      if (datatype) {
        // If it's a language tag, send as language
        if (/^[a-z]{2}(-[A-Z]{2})?$/.test(datatype)) {
          queryString += `&language=${encodeURIComponent(datatype)}`;
        } else {
          // Otherwise send as datatype (though backend DELETE doesn't use it currently)
          queryString += `&datatype=${encodeURIComponent(datatype)}`;
        }
      }

      await apiClient.delete(withDraftAndUser(`/api/ontology/metadata/${projectId}/annotations?${queryString}`));

      // Immediate UI update
      setOntologyAnnotations((prev) =>
        prev.filter((ann) => !(ann.propertyIri === propertyIri && ann.value === value && ann.datatype === datatype)),
      );

      // Delayed refresh
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
        ...draftBodyFields(),
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
      // Check if it's a URL (http/https/ftp)
      const isUrl = iri.startsWith("http://") || iri.startsWith("https://") || iri.startsWith("ftp://");

      // Check if it's a local file import
      const isLocalFile =
        !isUrl &&
        (/^[A-Za-z]:[\\\/]/.test(iri) || // Windows absolute path (C:\ or C:/)
          iri.startsWith("/") || // Unix absolute path
          iri.startsWith("./") ||
          iri.startsWith("../") || // Relative paths with ./ or ../
          iri.startsWith("file://") || // file:// protocol
          /^[^:\/]+\.(?:owl|rdf|ttl|n3|nt|xml)$/i.test(iri)); // Simple filename like "file.owl"

      console.log("[Dashboard] Import IRI:", iri);
      console.log("[Dashboard] Is URL:", isUrl);
      console.log("[Dashboard] Is local file:", isLocalFile);

      // Convert local file paths to proper URIs for backend storage
      let importIriForBackend = iri.trim();

      if (isLocalFile && !iri.startsWith("file://") && !isUrl) {
        // Convert Windows backslashes to forward slashes
        let normalizedPath = iri.replace(/\\/g, "/");

        if (/^[A-Za-z]:\//.test(normalizedPath)) {
          // Windows absolute path like C:/path/file.owl -> file:///C:/path/file.owl
          importIriForBackend = "file:///" + normalizedPath;
        } else if (normalizedPath.startsWith("/")) {
          // Unix absolute path like /path/file.owl -> file:///path/file.owl
          importIriForBackend = "file://" + normalizedPath;
        } else if (normalizedPath.startsWith("./") || normalizedPath.startsWith("../")) {
          // Strip relative path prefix - backend expects just the filename
          importIriForBackend = normalizedPath.replace(/^\.\//, "").replace(/^\.\.\//, "");
        } else {
          // Simple filename like "file.owl" - send as-is
          importIriForBackend = normalizedPath;
        }
        console.log("[Dashboard] Converted to URI:", importIriForBackend);
      }

      if (isEdit && originalIri !== importIriForBackend) {
        // Delete old and add new
        await apiClient.delete(
          withDraftAndUser(`/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(originalIri)}`),
        );
      }

      if (!isEdit || originalIri !== importIriForBackend) {
        console.log("[Dashboard] Posting import IRI to backend:", importIriForBackend);

        // Immediate optimistic UI update BEFORE API call
        if (isEdit && originalIri !== importIriForBackend) {
          // Remove old import and add new one
          setOntologyImports((prev) => {
            const filtered = prev.filter((i) => i !== originalIri);
            return [...filtered, importIriForBackend];
          });
        } else if (!isEdit) {
          // Just add new import
          setOntologyImports((prev) => [...prev, importIriForBackend]);
        }
        console.log("[Dashboard] ⚡ Optimistically added import to UI");

        await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, {
          importIri: importIriForBackend,
          ...draftBodyFields(),
        });
        console.log("[Dashboard] ✅ Import IRI saved to backend");
      }

      // If it's a local file WITH AN ACTUAL PATH (not just a filename), trigger upload to make it available in "My Files"
      // Only upload files with absolute paths (C:\path or /path) or file:// URIs
      const hasActualPath = iri.startsWith("file://") || /^[A-Za-z]:[\\\/]/.test(iri) || iri.startsWith("/");
      if (isLocalFile && hasActualPath && window.vscode) {
        console.log("[Dashboard] Local file with actual path detected, requesting upload:", iri);
        // Strip file:// protocol if present for the VSCode message
        const cleanPath = iri.startsWith("file://") ? iri.replace("file:///", "").replace("file://", "") : iri;
        window.vscode.postMessage({
          type: "importLocalFile",
          filePath: cleanPath,
          currentProjectId: projectId,
        });
      } else if (isLocalFile && !hasActualPath) {
        console.log("[Dashboard] Local file is a relative reference (filename only), skipping upload:", iri);
      }

      // Close dialog first for immediate feedback
      setIsImportDialogOpen(false);

      // Refresh from server to get canonical data
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
      console.error("[Dashboard] ❌ Failed to save import:", error);
      console.error("[Dashboard] ❌ Error details:", {
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
      // Remove old and add new
      await apiClient.delete(withDraftAndUser(`/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(oldIri)}`));
      await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, {
        importIri: importDraft.trim(),
        ...draftBodyFields(),
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
      await apiClient.delete(withDraftAndUser(`/api/ontology/metadata/${projectId}/imports?importIri=${encodeURIComponent(iri)}`));

      // Immediate UI update
      setOntologyImports((prev) => prev.filter((i) => i !== iri));

      // Delayed refresh
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

      // Backend expects POST with { prefix, iri, oldPrefix? }
      const payload: any = {
        prefix: cleanedPrefix,
        iri: namespace,
      };

      if (isEdit) {
        payload.oldPrefix = cleanedOriginal;
      }

      console.log("[Dashboard] Saving prefix:", payload);
      await apiClient.post(`/api/ontology/metadata/${projectId}/prefixes`, { ...payload, ...draftBodyFields() });

      // Refresh prefixes from server
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
      // Normalize prefix by removing colon
      const cleanedPrefix = prefix.endsWith(":") ? prefix.slice(0, -1) : prefix;

      console.log("[Dashboard] Deleting prefix:", cleanedPrefix);
      await apiClient.delete(
        withDraftAndUser(`/api/ontology/metadata/${projectId}/prefixes?prefix=${encodeURIComponent(cleanedPrefix)}`),
      );

      // Refresh from server
      await refreshPrefixes();

      notificationService.success("Prefix Deleted", "Prefix deleted successfully.");
    } catch (error) {
      console.error("[Dashboard] Failed to delete prefix:", error);
      notificationService.error("Prefix Failed", "Could not delete prefix.");
    }
  };

  const handleAddAxiom = async (definition?: string, superClassIri?: string) => {
    if (!projectId) return;

    // Use parameters if provided, otherwise fall back to axiomDraft state
    const axiomDefinition = definition || axiomDraft.definition;
    const axiomSuperClass = superClassIri !== undefined ? superClassIri : axiomDraft.superClassIri;

    if (!axiomDefinition) return;

    // Validate axiom definition - basic checks before sending to backend
    const trimmed = axiomDefinition.trim();
    if (trimmed.length === 0) {
      notificationService.error("Invalid Axiom", "Axiom definition cannot be empty.");
      return;
    }
    // Reject definitions with unbalanced parentheses
    let parenDepth = 0;
    for (const ch of trimmed) {
      if (ch === "(") parenDepth++;
      else if (ch === ")") parenDepth--;
      if (parenDepth < 0) break;
    }
    if (parenDepth !== 0) {
      notificationService.error("Invalid Axiom", "Axiom definition has unbalanced parentheses.");
      return;
    }
    // Reject if superClass is provided but looks invalid (empty after trim or has spaces only)
    if (axiomSuperClass && axiomSuperClass.trim().length === 0) {
      notificationService.error("Invalid Axiom", "Super class IRI cannot be blank.");
      return;
    }

    try {
      console.log("[Dashboard] Adding general class axiom:", {
        projectId,
        subClass: axiomDefinition,
        superClass: axiomSuperClass,
      });
      await apiClient.post(`/api/ontology/metadata/${projectId}/gci`, {
        subClass: axiomDefinition,
        superClass: axiomSuperClass || "",
        draft: ontologyMutationService.resolveUseDraft(),
        userId: resolveMutationActor(user?.userId || user?.email, user?.username).userId,
      });

      // Immediately update the UI with the new axiom
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

      // Refresh from server to get complete data
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
      definition: axiom.subExpression || axiom.definition || "",
      superClassIri: axiom.superClassIri || "",
    });
    setAxiomDialogOpen(true);
  };

  const handleUpdateAxiom = async (newSubClass?: string, newSuperClass?: string) => {
    if (!projectId || editingAxiomIndex === null) return;
    try {
      const oldAxiom = generalClassAxioms[editingAxiomIndex] as {
        id?: string;
        value?: string;
        subClass?: string;
        superClass?: string;
        subExpression?: string;
        definition?: string;
      };
      const subClass = newSubClass !== undefined ? newSubClass : axiomDraft.definition;
      const superClass = newSuperClass !== undefined ? newSuperClass : axiomDraft.superClassIri;
      const oldValue =
        oldAxiom.id ||
        oldAxiom.value ||
        (oldAxiom.subClass && oldAxiom.superClass
          ? `${oldAxiom.subClass} SubClassOf ${oldAxiom.superClass}`
          : "") ||
        oldAxiom.subExpression ||
        oldAxiom.definition ||
        "";
      console.log("[Dashboard] Updating general class axiom:", {
        projectId,
        oldAxiom,
        oldValue,
        newAxiom: { subClass, superClass },
      });

      await apiClient.put(`/api/ontology/metadata/${projectId}/gci/${editingAxiomIndex}`, {
        oldValue,
        subClass,
        superClass: superClass || "",
        draft: ontologyMutationService.resolveUseDraft(),
        userId: resolveMutationActor(user?.userId || user?.email, user?.username).userId,
      });

      // Immediately update UI
      const updatedAxioms = [...generalClassAxioms];
      updatedAxioms[editingAxiomIndex] = {
        definition: subClass,
        superClassIri: superClass || "",
        subExpression: subClass,
      };
      setGeneralClassAxioms(updatedAxioms);

      setAxiomDraft({ definition: "", superClassIri: "" });
      setEditingAxiomIndex(null);
      setAxiomDialogOpen(false);

      // Refresh from server
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
      const axiom = generalClassAxioms[index] as {
        id?: string;
        value?: string;
        subClass?: string;
        superClass?: string;
        subExpression?: string;
        definition?: string;
      };
      const value =
        axiom.id ||
        axiom.value ||
        (axiom.subClass && axiom.superClass
          ? `${axiom.subClass} SubClassOf ${axiom.superClass}`
          : "") ||
        axiom.subExpression ||
        axiom.definition ||
        "";
      console.log("[Dashboard] Deleting general class axiom:", { projectId, axiom, value });

      if (!value) {
        notificationService.error("Axiom Failed", "Cannot delete axiom without a value.");
        return;
      }

      const gciDeleteActorId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      await apiClient.delete(
        `/api/ontology/metadata/${projectId}/gci?value=${encodeURIComponent(value)}` +
          `&draft=${ontologyMutationService.resolveUseDraft()}&userId=${encodeURIComponent(gciDeleteActorId)}`,
      );

      // Immediately update UI
      const updatedAxioms = generalClassAxioms.filter((_, idx) => idx !== index);
      setGeneralClassAxioms(updatedAxioms);

      // Refresh from server
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
      const response = await apiClient.get<any>(withDraftScope(`/api/ontology/metadata/${projectId}/gci`));
      const payload = response?.data || response;
      const data = payload?.data || payload || [];
      // Map backend fields to frontend expected structure
      const mappedData = Array.isArray(data)
        ? data.map((axiom: any) => ({
            id: axiom.id || axiom.subClass || "",
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
      // Individual prefix updates should be done via handleSavePrefix
      // This bulk save is not supported by backend
      setIsPrefixEditing(false);
      await refreshPrefixes();
      notificationService.info("Prefixes", "Edit mode disabled. Use individual add/edit/delete operations.");
    } catch (error) {
      console.error("[Dashboard] Failed to save prefixes:", error);
      notificationService.error("Prefixes Failed", "Could not save prefixes.");
    }
  };

  // Collaborative cursor tracking - includes clicks and mouse movement
  useEffect(() => {
    if (!projectId || !user) return;

    const broadcastCursor = (e: MouseEvent | PointerEvent) => {
      const newCursor = { x: e.clientX, y: e.clientY };
      setMyLocalCursor(newCursor);

      // Broadcast cursor position via vscode postMessage
      if (window.vscode) {
        window.vscode.postMessage({
          type: "broadcastCursor",
          projectId,
          userId: user.userId,
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
      // Immediately broadcast cursor position on click
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

  // Listen for cursor updates from other users
  useEffect(() => {
    if (!projectId) return;

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;

      if (message.type === "cursorUpdate" && message.userId !== user?.userId) {
        // Generate consistent color for each user
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

    // Clean up stale cursors every 3 seconds
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

  // Helper function to generate consistent colors for users
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

  // Throttle helper
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

  const refreshSelectedClassIndividualDetails = useCallback(async () => {
    if (!projectId || !selectedClassIndividual?.id) {
      setSelectedClassIndividualDetails(null);
      return;
    }
    setSelectedClassIndividualLoading(true);
    try {
      const response = await apiClient.get<any>(
        `/api/ontology/individual-details/${projectId}?individualIri=${encodeURIComponent(selectedClassIndividual.id)}`,
      );
      const details = response?.data || response;
      if (details) {
        setSelectedClassIndividualDetails({
          ...selectedClassIndividual,
          types: details.types || selectedClassIndividual.types,
          annotations: details.annotations || selectedClassIndividual.annotations,
          propertyAssertions: details.propertyAssertions || [],
          sameIndividualAs: details.sameIndividualAs || selectedClassIndividual.sameIndividualAs,
          differentIndividualFrom: details.differentIndividualFrom || selectedClassIndividual.differentIndividualFrom,
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

  const openClassIndividualSameDiffDialog = useCallback(
    async (mode: "same" | "different") => {
      setClassIndividualSameDiffDialog({ mode });
      try {
        if (!projectId) return;
        const response = await apiClient.get<any>(withDraftScope(`/api/ontology/individuals/${projectId}`));
        const loadedIndividuals = Array.isArray(response?.data)
          ? response.data
          : response?.data?.individuals || response?.individuals || [];
        setClassIndividualCandidateIndividuals(loadedIndividuals);
      } catch (error) {
        console.error("[Dashboard] Failed to load individuals for same/different dialog:", error);
        setClassIndividualCandidateIndividuals([]);
      }
    },
    [projectId],
  );

  const deleteClassIndividualSameDifferent = useCallback(
    async (mode: "same" | "different", targetIri: string) => {
      if (!projectId || !selectedClassIndividualDetails) return;
      try {
        if (mode === "same") {
          await ontologyMutationService.deleteSameIndividual(projectId, selectedClassIndividualDetails.id, targetIri);
        } else {
          await ontologyMutationService.deleteDifferentIndividual(projectId, selectedClassIndividualDetails.id, targetIri);
        }
        await refreshSelectedClassIndividualDetails();
      } catch (error) {
        console.error("[Dashboard] Failed to remove same/different individual assertion:", error);
        notificationService.error("Remove Failed", "Could not remove same/different individual assertion.");
      }
    },
    [projectId, selectedClassIndividualDetails, refreshSelectedClassIndividualDetails],
  );

  useEffect(() => {
    if (!projectId || !selectedClassIndividual?.id) {
      setClassIndividualUsages([]);
      return;
    }

    let alive = true;
    setClassIndividualUsageLoading(true);

    (async () => {
      try {
        const response = await apiClient.get<any>(
          withDraftScope(`/api/ontology/individuals/usage/${projectId}?individualIri=${encodeURIComponent(selectedClassIndividual.id)}`),
        );
        const usageData = response?.data?.data || response?.data || response || [];
        if (alive) setClassIndividualUsages(Array.isArray(usageData) ? usageData : []);
      } catch (error) {
        console.error("[Dashboard] Failed to load individual usage:", error);
        if (alive) setClassIndividualUsages([]);
      } finally {
        if (alive) setClassIndividualUsageLoading(false);
      }
    })();

    return () => {
      alive = false;
    };
  }, [projectId, selectedClassIndividual?.id]);

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
      console.log("[Dashboard] 📂 fetchProjects called");
      console.log("[Dashboard] 📂 User:", { email: user?.email, workspaceId: user?.workspaceId, isWorkspaceMode });
      console.log("[Dashboard] 📂 Fetching from:", projectsUrl);
      console.log("[Dashboard] 📂 resolvedEmail:", resolvedEmail);

      let response;
      try {
        response = await apiClient.get<any>(projectsUrl);
        console.log("[Dashboard] 📥 Primary endpoint response:", response);
      } catch (error: any) {
        console.error("[Dashboard] ❌ Primary endpoint error:", error);
        const status = error?.status || error?.response?.status;
        const allowFallback = primaryEndpoint === "/api/projects";
        if (status === 404 && fallbackEndpoint !== primaryEndpoint && allowFallback) {
          const fallbackUrl = resolvedEmail
            ? `${fallbackEndpoint}?userEmail=${encodeURIComponent(resolvedEmail)}`
            : fallbackEndpoint;
          console.warn("[Dashboard] ⚠️ Projects endpoint missing, falling back to:", fallbackUrl);
          response = await apiClient.get<any>(fallbackUrl);
          console.log("[Dashboard] 📥 Fallback endpoint response:", response);
        } else {
          throw error;
        }
      }

      console.log("[Dashboard] 📥 fetchProjects RAW response:", response);
      console.log("[Dashboard] 📥 fetchProjects response type:", typeof response);
      console.log("[Dashboard] 📥 fetchProjects response keys:", response ? Object.keys(response) : "null");

      // Handle case where response might be wrapped in a data property
      const data = response?.data || response;

      console.log("[Dashboard] 📥 fetchProjects processed data:", {
        success: data?.success,
        hasMyFiles: data?.myFiles !== undefined,
        myFilesCount: data?.myFiles?.length || 0,
        hasSharedFiles: data?.sharedFiles !== undefined,
        sharedFilesCount: data?.sharedFiles?.length || 0,
        hasProjects: data?.projects !== undefined,
        projectsCount: data?.projects?.length || 0,
        myFilesData: data?.myFiles,
        sharedFilesData: data?.sharedFiles,
      });
      setHasFetchedProjects(true);

      if (data?.success) {
        // Handle new format with myFiles and sharedFiles (check if properties exist, not just truthy)
        if (data.myFiles !== undefined && data.sharedFiles !== undefined) {
          // Map files to ensure they have proper project structure, not just GridFS file IDs
          const mapFileToProject = (p: any) => {
            // If this looks like a raw GridFS file (has only id, contentType, length), skip it
            if (p.contentType && p.length && !p.name && !p.filename) {
              console.warn("[Dashboard] ⚠️ Skipping raw GridFS file entry:", p.id);
              return null;
            }
            return {
              ...p,
              // Ensure we have proper display properties
              id: p.id || p.projectId || p._id,
              filename: p.filename || p.name || p.id,
              name: p.name || p.filename || p.id,
              // Preserve other project metadata
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

          console.log(
            "[Dashboard] ✅ Files loaded - My Files:",
            myFilesWithNames.length,
            "Shared:",
            sharedFilesWithNames.length,
          );
          console.log("[Dashboard] 📋 Sample myFile:", myFilesWithNames[0]);
          console.log("[Dashboard] 📋 Sample sharedFile:", sharedFilesWithNames[0]);

          return { myFiles: myFilesWithNames, sharedFiles: sharedFilesWithNames };
        } else if (data.projects) {
          // Backward compatibility with old format
          const mapFileToProject = (p: any) => {
            // If this looks like a raw GridFS file, skip it
            if (p.contentType && p.length && !p.name && !p.filename) {
              console.warn("[Dashboard] ⚠️ Skipping raw GridFS file entry:", p.id);
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

          // Assume all projects are "myFiles" if no sharedBy field
          const myFilesList = projectsWithNames.filter((p: any) => !p.sharedBy);
          const sharedFilesList = projectsWithNames.filter((p: any) => p.sharedBy);

          setMyFiles(myFilesList);
          setSharedFiles(sharedFilesList);
          setListOfFiles(projectsWithNames);

          console.log(
            "[Dashboard] ✅ Files loaded (legacy format) - My Files:",
            myFilesList.length,
            "Shared:",
            sharedFilesList.length,
          );
          console.log("[Dashboard] 📋 Sample project:", projectsWithNames[0]);

          return { myFiles: myFilesList, sharedFiles: sharedFilesList };
        } else {
          console.log("[Dashboard] ⚠️ No myFiles/sharedFiles or projects in response - setting empty arrays");
          console.log("[Dashboard] ⚠️ Full response data:", data);
          setMyFiles([]);
          setSharedFiles([]);
          setListOfFiles([]);
          return { myFiles: [], sharedFiles: [] };
        }
      } else {
        console.log("[Dashboard] ⚠️ Response not successful:", data);
        setMyFiles([]);
        setSharedFiles([]);
        setListOfFiles([]);
        return null;
      }
    } catch (error: any) {
      console.error("[Dashboard] ❌ Failed to fetch projects:", error);
      console.error("[Dashboard] ❌ Error details:", error?.response?.data || error?.message || error);
      setMyFiles([]);
      setSharedFiles([]);
      setListOfFiles([]);
      setIsInitialLoading(false);
      return null;
    }
  }, [user?.email, user?.workspaceId, resolveUserEmail]); // Track workspace mode changes + email fallback

  const handleProjectSelection = useCallback((selectedProjectId: string) => {
    // Webapp: don't navigate to editor while import is still in progress
    const importState = projectImportStatuses[selectedProjectId];
    if (!isDesktop() && importState &&
        importState.type !== "IMPORT_COMPLETED" && importState.type !== "IMPORT_FAILED") {
      return;
    }

    setHasUserSelectedFile(true); // Mark that user has manually selected a file
    setProjectId(selectedProjectId);
    // In free mode, use projectId as the active file identifier
    if (!initialProjectId) {
      setActiveFileId(selectedProjectId);
    } else {
      setActiveFileId(null);
    }
    setActiveFileName(null);
    setShowProjectSelector(false);
    fetchData(selectedProjectId);
  }, [projectImportStatuses]);
  // fetchData captured in closure, removed to prevent infinite loop

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
    // Fetch projects when user opens the selector
    fetchProjects();
    setShowProjectSelector(true);
  }, [fetchProjects]);

  const handleLoadOpenAnother = useCallback(() => {
    setLoadFailure(null);
    setIsInitialLoading(false);
    setShowLoadingChoice(false);
    setIsExpectingFileReady(false);
    loadingPromiseRef.current = null;
    fetchProjects();
    if (isDesktop()) {
      setShowOpenDialog(true);
    } else {
      setShowProjectSelector(true);
    }
  }, [fetchProjects]);

  const handleLoadRetry = useCallback(async () => {
    const pid = loadFailure?.projectId || projectId;
    if (!pid) return;
    setLoadFailure(null);
    setIsInitialLoading(true);
    setIsExpectingFileReady(false);
    setLoadingStatusMessage("Retrying…");
    loadingPromiseRef.current = null;
    try {
      if (pid.includes("--")) {
        await apiClient.post(`/api/ontology/reload/${encodeProjectId(pid)}`, {});
        const poll = (window as any).electronAPI?.pollImportStatus;
        if (isDesktop() && poll) poll(pid);
      }
      loadingPromiseRef.current = fetchData(pid, true, initialProjectId, true);
      await loadingPromiseRef.current;
    } catch (e: any) {
      setLoadFailure({
        message: e?.message || "Retry failed. Try opening the file again.",
        projectId: pid,
      });
      setIsInitialLoading(false);
    }
  }, [loadFailure, projectId, fetchData, initialProjectId]);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = classHierarchy[0].id;
      const childCount = classHierarchy[0].children?.length || 0;
      console.log("[Dashboard] Class hierarchy loaded, owl:Thing has", childCount, "top-level children");

      // Classes are usable once the hierarchy fetch completes — close the import modal.
      // Section loaders (tab spinners + SectionLoadingBar) cover metadata/properties/etc.
      if (!isHierarchyLoading) {
        setIsInitialLoading(false);
      }

      // Auto-expand owl:Thing when it has children (preserve other expanded nodes)
      if (childCount > 0 && !expandedNodes.includes(owlThingId)) {
        console.log("[Dashboard] Auto-expanding owl:Thing (preserving existing expanded nodes)");
        console.log("[DEBUG] useEffect[classHierarchy] triggering setExpandedNodes");
        setExpandedNodes((prev) => (prev.includes(owlThingId) ? prev : [...prev, owlThingId]));
      }
    }
  }, [classHierarchy]);

  // ── Bulletproof anti-hang watchdog ─────────────────────────────────────────
  // The loading modal is opened by many code paths (file upload, fileReady
  // WebSocket message, project switch, browser mode…). On desktop the WebSocket
  // "completed" signal is unreliable, so any of those paths can leave the
  // spinner stuck forever. This watchdog makes that impossible: while the modal
  // is open it polls the backend import status and force-closes the spinner as
  // soon as the backend reports the project is ready (or errored), and in any
  // case after a hard time cap. It NEVER blocks legitimate work — it only ever
  // closes a spinner, and the data continues loading via the normal effects.
  useEffect(() => {
    const open = isInitialLoading || showLoadingChoice;
    // Only poll status for file-level project IDs (contain '--').
    // Parent-only IDs (e.g. "proj-abc123") return 404 from the status endpoint.
    if (!open || !projectId || !projectId.includes("--")) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const startedAt = Date.now();
    const HARD_CAP_MS = isDesktop() ? 180_000 : 600_000;

    const closeSpinner = (reason: string) => {
      if (cancelled) return;
      console.warn("[Dashboard] Loading watchdog closing modal spinner:", reason);
      setIsInitialLoading(false);
      setShowLoadingChoice(false);
      setLoadingStatusMessage("");
      // Release the in-flight load lock so a previous/stuck load can't make the
      // next open silently skip with "Already loading, skipping duplicate".
      loadingPromiseRef.current = null;
    };

    const failAndRedirect = (msg: string) => {
      if (cancelled) return;
      closeSpinner(msg);
      if (isDesktop()) {
        setLoadFailure({ message: msg, projectId: projectId || undefined });
      } else {
        showToast(msg, "error");
        onGoToProjectDashboardRef.current?.();
      }
    };

    const tick = async () => {
      if (cancelled) return;
      if (Date.now() - startedAt > HARD_CAP_MS) {
        failAndRedirect(
          isDesktop()
            ? "Loading timed out. OWLAPI could not open this file in time. Try again or choose another file."
            : "Loading timed out. The ontology may be too large or the server is busy. Please try again.",
        );
        return;
      }
      try {
        if (isDesktop()) {
          const cs = await apiClient.get<any>(`/api/ontology/cache-status/${encodeProjectId(projectId)}`);
          if (cs?.owlapiReady ?? cs?.data?.owlapiReady) {
            setImportReadyToBrowse(true);
            setLoadingStatusMessage("OWLAPI ready — opening editor…");
            closeSpinner("desktop owlapi ready");
            return;
          }
        }
        const res = await apiClient.get<any>(`/api/ontology/status/${encodeProjectId(projectId)}`);
        const status = res?.data?.status || res?.status;
        if (status === "ERROR") {
          const errMsg = res?.data?.error || res?.error || "Failed to load ontology. Please check the file and try again.";
          failAndRedirect(errMsg);
          setImportReadyToBrowse(false);
          return;
        }
        if (status === "COMPLETED") {
          setImportReadyToBrowse(true);
          setLoadingStatusMessage("Ready to browse — class tree and annotations available");
          closeSpinner(`backend status=${status} — ready to browse`);
          return;
        }
      } catch (error: any) {
        // A 404 here is terminal, not transient: the outer guard already restricts polling
        // to file-level IDs (contain "--"), for which the status endpoint always exists once
        // the upload succeeded. A 404 means the file/project record itself is gone (upload
        // never completed, or was removed) — waiting out the multi-minute hard cap just to
        // show the same failure is a bad experience, so fail fast instead.
        const status = error?.status ?? error?.response?.status;
        if (status === 404) {
          failAndRedirect(
            "This file could not be found on the server. The upload may not have completed — please try again.",
          );
          return;
        }
        // Any other error (network blip, 5xx, server still warming up) — keep waiting until the hard cap.
      }
      if (!cancelled) timer = setTimeout(tick, 3000);
    };

    timer = setTimeout(tick, 3000);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [isInitialLoading, showLoadingChoice, projectId, showToast]);

  useEffect(() => {
    if (!projectId || !showImportClosure) return;
    const loadImportClosure = async () => {
      try {
        const res = await apiClient.get<any>(
          withDraftScope(`/api/ontology/metadata/${encodeProjectId(projectId)}/imports/closure`),
        );
        const payload = res?.data || res;
        if (payload?.closure && typeof payload.closure === "object") {
          setImportClosureMap(payload.closure);
        }
      } catch (error) {
        console.warn("[Dashboard] Failed to load import closure:", error);
      }
    };
    void loadImportClosure();
  }, [projectId, showImportClosure, ontologyImports]);

  // Fetch projects list on mount (but don't auto-load a file)
  // This populates the file selector dropdown when user clicks it
  useEffect(() => {
    if (!user) {
      console.log("[Dashboard] Skipping initial fetch - user not available");
      return;
    }

    // Only run once on mount - use a ref to track if we've already fetched
    console.log("[Dashboard] Initial mount - fetching projects list");
    const resolvedEmail = resolveUserEmail();
    console.log("[Dashboard] User details:", {
      email: resolvedEmail || user.email,
      username: user.username,
      isAdmin: user.isAdmin,
      workspaceId: user.workspaceId,
    });
    console.log("[Dashboard] Component props:", {
      initialProjectId,
      projectId,
      onBackToProjects: !!onBackToProjects,
    });

    // Always fetch user's files for the OpenFileDialog
    // This populates myFiles and sharedFiles based on user email
    console.log(
      "[Dashboard] 🔍 useEffect triggered - user.email:",
      user?.email,
      "user.workspaceId:",
      user?.workspaceId,
    );
    console.log("[Dashboard] ✅ Fetching all projects for user email:", resolvedEmail || "(none)");
    fetchProjects();

    // Desktop / non-workspace: auto-load the last opened ontology from localStorage
    if (shouldRestoreLastOpenedFile && storedProjectId && !hasUserSelectedFileRef.current) {
      console.log("[Dashboard] 🔄 Restoring last opened ontology:", storedProjectId);
      hasUserSelectedFileRef.current = true;
      setHasUserSelectedFile(true);
      setProjectId(storedProjectId);
      if (!initialProjectId) {
        setActiveFileId(storedProjectId);
      }
      setActiveFileName(storedProjectId);
      fetchData(storedProjectId, false)
        .then(() => {
          console.log("[Dashboard] ✅ Last file restored:", storedProjectId);
        })
        .catch((err) => {
          console.warn("[Dashboard] ⚠️ Failed to restore last file:", storedProjectId, err);
          // Clear the stored project ID if it can't be loaded
          localStorage.removeItem("ontocode_lastProjectId");
          setProjectId(null);
          setHasUserSelectedFile(false);
          hasUserSelectedFileRef.current = false;
        });
    }

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.email, user?.workspaceId, resolveUserEmail]); // Only re-run when user identity changes

  useEffect(() => {
    setActiveFileId(null);
    setActiveFileName(null);
    setProjectFiles([]);
  }, [initialProjectId]);

  // When editor opens with a project but no specific file (e.g. Editor button from Project Library),
  // pre-fetch project files so the Open File dialog isn't empty.
  useEffect(() => {
    if (initialProjectId && (!selectedFileId || selectedFileId === "__editor__")) {
      fetchProjectFiles(initialProjectId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialProjectId]);

  // Track if a file is currently being loaded to prevent duplicate loads
  const fileLoadingRef = useRef(false);
  const lastLoadedFileRef = useRef<string | null>(null);

  /** Skip hierarchy/cache polls until file-scoped projectId is active (not parent-only). */
  const shouldDeferHierarchyDuringFileOpen = useCallback(() => {
    if (fileLoadingRef.current) return true;
    if (initialProjectId && activeFileId && projectId === initialProjectId) return true;
    return false;
  }, [initialProjectId, activeFileId, projectId]);

  // AbortController for cancelling in-flight fetchData requests when the user switches files
  const fetchAbortControllerRef = useRef<AbortController | null>(null);
  // Bumped on each fetchData call so stale finally/handlers cannot clear a newer load's spinners.
  const fetchDataGenerationRef = useRef(0);
  /** Desktop: Fuseki sections deferred until the user opens each Entities tab. */
  const desktopDeferredSectionsLoadedRef = useRef<Set<string>>(new Set());

  // Auto-load selected file from Project Library (admin flow)
  useEffect(() => {
    if (selectedFileId && selectedFileName && initialProjectId) {
      // Prevent loading the same file multiple times or loading while another load is in progress
      if (fileLoadingRef.current || lastLoadedFileRef.current === selectedFileId) {
        console.log("[Dashboard] Skipping duplicate load for:", selectedFileId);
        return;
      }

      console.log("[Dashboard] Auto-loading selected file:", selectedFileId, selectedFileName);
      console.log("[Dashboard] Parent project for file menu:", initialProjectId);

      // Mark as loading
      fileLoadingRef.current = true;
      lastLoadedFileRef.current = selectedFileId;

      // Clear any previous file state
      console.log("[Dashboard] 🧹 Cleaning up previous file state...");
      setIsInitialLoading(true);
      setMainTab("Entities");
      setEntitiesTab("Classes");

      setHasUserSelectedFile(true); // Mark that file was selected

      // Upload the file to GraphDB first, then it will auto-load
      handleLoadProjectFile(selectedFileId, selectedFileName).finally(() => {
        // Reset loading flag after a delay to prevent rapid re-loads
        setTimeout(() => {
          fileLoadingRef.current = false;
        }, 1000);
      });
    }

    // Cleanup when unmounting
    return () => {
      console.log("[Dashboard] 🧹 Cleanup on unmount");
    };
  }, [selectedFileId, selectedFileName, initialProjectId]);

  // Update collaboration context when projectId changes
  useEffect(() => {
    if (collaboration?.setCurrentProject) {
      collaboration.setCurrentProject(projectId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  // Track if component is mounted to prevent race conditions
  const isMountedRef = useRef(false);

  // Send 'webviewReady' to extension when mounted
  // NOTE: This useEffect runs BEFORE the message listener is attached,
  // but the actual webviewReady signal is sent from the message listener useEffect
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      if (autoDraftPollRef.current) clearInterval(autoDraftPollRef.current);
      if (draftCopyPollRef.current) clearInterval(draftCopyPollRef.current);
      if (conflictCheckTimerRef.current) clearTimeout(conflictCheckTimerRef.current);
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
        // Some deployments don't expose /api/ontology/check-duplicate via gateway.
        // For create-copy, it's safe to skip this check and let the backend resolve conflicts.
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

  // Handle loading choice dialog actions
  const handleWaitForLoading = useCallback(() => {
    userLoadingChoice.current = "wait";
    // Keep dialog open, show waiting state
    console.log("[Dashboard] Wait for Loading clicked - keeping dialog open");
    // Dialog will be closed by IMPORT_COMPLETED handler when data loads
  }, []);

  const handleContinueWorking = useCallback(() => {
    userLoadingChoice.current = "continue";
    setShowLoadingChoice(false);
    setBackgroundImportActive(true);
    console.log("[Dashboard] Continue Working clicked - closing dialog, showing persistent progress banner");
    // Keep isExpectingFileReady=true so IMPORT_COMPLETED will auto-load
    // Reset choice after a short delay
    setTimeout(() => {
      userLoadingChoice.current = null;
    }, 100);
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      console.log(message, "message");
      // CRITICAL: Always handle showLoading even before mount - this is time-sensitive
      // The extension sends showLoading right after file selection, before the webview may be fully ready
      if (message.type === "uploadProgress") {
        const targetProject = message.projectId;
        const isRelevant =
          targetProject === projectId ||
          targetProject === pendingImportProjectIdRef.current ||
          hasUserSelectedFileRef.current;
        if (isRelevant) {
          setBackgroundImportProgress(message.percent);
          setLoadingStatusMessage(message.message || `Uploading: ${message.percent}%`);
        }
        return;
      }

      if (message.type === "showLoading") {
        console.log("[Dashboard] showLoading received - file upload starting for project:", message.projectId);
        setHasUserSelectedFile(true);
        hasUserSelectedFileRef.current = true;
        pendingImportProjectIdRef.current = message.projectId; // Track which project is being imported
        setPendingImportProjectId(message.projectId);
        console.log("[Dashboard] Set pendingImportProjectIdRef.current to:", pendingImportProjectIdRef.current);
        setIsExpectingFileReady(true);
        setImportReadyToBrowse(false);
        setLoadingProjectName(message.fileName || message.projectId || "Processing file upload...");
        setBackgroundImportProgress(0);
        setLoadingStatusMessage("Preparing upload...");
        if (window.vscode && message.projectId) {
          window.vscode.postMessage({ type: "getQueueStatus", projectId: message.projectId });
        }
        if (isDesktop()) {
          // Desktop: block with Protégé-style loading dialog
          setShowLoadingChoice(true);
        } else {
          // Webapp: stay in project library — import card shows live progress
          setShowProjectSelector(true);
        }
        // Don't fetch projects yet - wait for upload to complete
        return;
      }

      // Ignore other messages until component is fully mounted
      if (!isMountedRef.current) {
        console.log("[Dashboard] Ignoring message before mount:", event.data.type);
        return;
      }

      console.log("[Dashboard] Received message:", message.type, message);
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
          // Refresh file list in workspace mode to ensure new files are shown
          if (initialProjectId) {
            console.log("[Dashboard] 📋 Refreshing file list before opening project file");
            fetchProjectFiles(initialProjectId).then(() => {
              console.log("[Dashboard] ✅ File list refreshed, proceeding to open file");
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
          // Always refresh file list in workspace mode when a fileReady message is received
          if (initialProjectId) {
            console.log(
              "[Dashboard] 📋 File list refresh triggered by fileReady, initialProjectId:",
              initialProjectId,
              "message.projectId:",
              message.projectId,
              "uploadedFileId:",
              message.uploadedFileId,
              "uploadedFileName:",
              message.uploadedFileName,
            );
            console.log("[Dashboard] 📋 Current projectFiles count before refresh:", projectFiles.length);

            // Retry mechanism to ensure newly uploaded file appears in list
            const fetchWithRetry = async (retries = 3, delay = 300) => {
              for (let attempt = 1; attempt <= retries; attempt++) {
                console.log(`[Dashboard] 📋 Fetch attempt ${attempt}/${retries}...`);
                const fetchedFiles = await fetchProjectFiles(initialProjectId);

                // If we're looking for a specific uploaded file, verify it's in the list
                if (message.uploadedFileId) {
                  const found = fetchedFiles.some((f) => f.id === message.uploadedFileId);
                  console.log(
                    `[Dashboard] 📋 Looking for file ${message.uploadedFileId} (${message.uploadedFileName}) in ${fetchedFiles.length} files, found: ${found}`,
                  );
                  console.log(
                    `[Dashboard] 📋 File IDs in list:`,
                    fetchedFiles.map((f) => f.id),
                  );

                  if (found) {
                    console.log(`[Dashboard] ✅ File found in list after ${attempt} attempt(s)!`);
                    return true;
                  }

                  if (attempt < retries) {
                    console.log(`[Dashboard] ⏳ File not found, waiting ${delay}ms before retry ${attempt + 1}...`);
                    await new Promise((resolve) => setTimeout(resolve, delay));
                  } else {
                    console.warn(`[Dashboard] ⚠️ File ${message.uploadedFileId} not found after ${retries} attempts`);
                    console.warn(`[Dashboard] ⚠️ This may indicate a database synchronization delay`);
                    return false;
                  }
                } else {
                  // No specific file to look for, just refresh once
                  console.log("[Dashboard] ✅ File list refreshed (no specific file verification)");
                  return true;
                }
              }
              return false;
            };

            fetchWithRetry()
              .then((success) => {
                console.log("[Dashboard] ✅ File list refresh complete, success:", success);
                console.log("[Dashboard] 📋 ProjectFiles count after refresh:", projectFiles.length);
              })
              .catch((err) => {
                console.error("[Dashboard] ❌ Failed to refresh file list:", err);
              });
          } else {
            console.log("[Dashboard] ⚠️ fileReady received but no initialProjectId, cannot refresh");
          }

          if (initialProjectId && message.projectId === initialProjectId) {
            // If this fileReady came from creating/uploading a new file into the project,
            // auto-load it so the user sees it immediately instead of requiring a manual click.
            if (message.uploadedFileId && message.uploadedFileName) {
              console.log(
                "[Dashboard] 📂 New file created in project, auto-loading:",
                message.uploadedFileId,
                message.uploadedFileName,
              );
              // Wait for the file list refresh to complete, then load the new file
              fetchProjects();
              // Use a small delay to let the file list state update.
              // Guard with isMountedRef so navigation away before the timer fires
              // does not re-set selectedFileId in App.tsx via onFileSelected.
              setTimeout(() => {
                if (!isMountedRef.current) return;
                handleLoadProjectFile(message.uploadedFileId, message.uploadedFileName);
              }, 200);
            } else {
              console.log("[Dashboard] File list updated for project, skipping ontology load:", message.projectId);
              fetchProjects();
            }
            break;
          }
          if (
            initialProjectId &&
            pendingImportProjectIdRef.current &&
            message.projectId === pendingImportProjectIdRef.current
          ) {
            console.log("[Dashboard] FileReady for project file import:", message);
            setHasUserSelectedFile(true);
            hasUserSelectedFileRef.current = true;
            setProjectId(message.projectId);
            setSelectedItem(null);
            setLoadingProjectName(message.uploadedFileName);
            // userLoadingChoice.current = null;
            // setShowLoadingChoice(true);

            // Check if we're already loading this project to avoid duplicate fetches
            if (loadingPromiseRef.current) {
              console.log("[Dashboard] Already loading, skipping duplicate fetchData call");
            } else {
              loadingPromiseRef.current = fetchData(message.projectId, false, initialProjectId)
                .then(() => {
                  console.log("[Dashboard] Loading completed for:", message.projectId);
                  setShowLoadingChoice(false);
                  setShowQueueStatus(false);
                  setQueuePosition(undefined);
                  setTotalInQueue(undefined);
                  setEstimatedWaitTimeMs(undefined);
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
          // Show loading choice dialog
          console.log("[Dashboard] Loading project:", message.projectId);
          // Don't clear isExpectingFileReady here - let IMPORT_COMPLETED handler do it
          setHasUserSelectedFile(true);
          hasUserSelectedFileRef.current = true;
          // Only update projectId if it's different (ignoring timestamp suffixes)
          const currentBaseId = projectId?.replace(/-\d+$/, "");
          const newBaseId = message.projectId?.replace(/-\d+$/, "");
          const isSameFile = currentBaseId === newBaseId;
          if (!isSameFile) {
            console.log("[Dashboard] Updating projectId from", projectId, "to", message.projectId);
            setProjectId(message.projectId);
          } else {
            console.log("[Dashboard] ProjectId essentially same, keeping current:", projectId);
          }
          // In free mode, projectId IS the file identifier, so set activeFileName for ACTIVE badge
          // Extract filename from projectId if it looks like a filename (has extension)
          const projId = message.projectId || "";
          console.log("[Dashboard] Setting active file name for projectId:", projId);
          if (projId.includes(".owl") || projId.includes(".rdf") || projId.includes(".ttl")) {
            setActiveFileName(projId);
          } else {
            setActiveFileName(projId + ".owl"); // Default extension
          }
          setActiveFileId(null); // In free mode, fileId is same as projectId
          setSelectedItem(null);
          console.log(message, "message=====>", projId);
          setLoadingProjectName(message.uploadedFileName);
          userLoadingChoice.current = null; // Reset choice for new loading

          // If the same file is already loaded, skip the blocking loading dialog — the data
          // is already in state. A silent background refresh keeps counts up to date.
          // Use ref (not state) to avoid stale closure capturing the wrong value.
          if (isSameFile && hasUserSelectedFileRef.current) {
            console.log("[Dashboard] Same file already loaded — skipping loading dialog, doing silent refresh");
            setShowLoadingChoice(false);
            setIsInitialLoading(false);
            if (!loadingPromiseRef.current) {
              loadingPromiseRef.current = fetchData(message.projectId, false)
                .then(() => { loadingPromiseRef.current = null; })
                .catch(() => { loadingPromiseRef.current = null; });
            }
            break;
          }

          // If triggered by "Create New File", skip the choice dialog and load immediately
          if (autoLoadNewFileRef.current) {
            autoLoadNewFileRef.current = false;
          } else if (isDesktop()) {
            // Webapp uses the import card in ProjectSelector, not this blocking dialog
            setShowLoadingChoice(true);
          }

          // Start loading in background and store the promise (only if not already loading)
          if (loadingPromiseRef.current) {
            console.log("[Dashboard] Already loading, skipping duplicate fetchData call");
          } else {
            loadingPromiseRef.current = fetchData(message.projectId, false)
              .then(() => {
                console.log("[Dashboard] Loading completed for:", message.projectId);
                // Close loading dialog immediately on success
                setShowLoadingChoice(false);
                setShowQueueStatus(false);
                setQueuePosition(undefined);
                setTotalInQueue(undefined);
                setEstimatedWaitTimeMs(undefined);
                // Refresh projects list
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
          // Switch to SWRL view (now handled via plugins)
          if (message.view === "swrl") {
            setMainTab("SWRL");
          }
          break;
        case "importStatusUpdate":
          // Handle import status updates from WebSocket
          console.log(
            `[Dashboard] 📨 Import status update for "${message.status.projectId}": ${message.status.type}`,
            message.status,
          );
          console.log(
            `[Dashboard] 📍 Current projectId: ${projectId} | Message projectId: ${message.status.projectId}`,
          );
          console.log(`[Dashboard] 🎯 Status: ${message.status.status} | Progress: ${message.status.progress}%`);

          // Update project-specific import status for ProjectSelector
          if (message.status.projectId) {
            setProjectImportStatuses((prev) => ({
              ...prev,
              [message.status.projectId]: {
                type: message.status.type,
                status: message.status.status,
                progress: message.status.progress,
                metadata: message.status.metadata,
              },
            }));

            // Update loading status message for user feedback
            if (message.status.type === "IMPORT_PROGRESS" && message.status.metadata?.message) {
              setLoadingStatusMessage(sanitizeImportMessage(message.status.metadata.message as string));
              if (message.status.progress !== undefined) setBackgroundImportProgress(message.status.progress);
            } else if (message.status.type === "IMPORT_PROGRESS" && message.status.metadata?.stage) {
              const stage = message.status.metadata.stage as string;
              setLoadingStatusMessage(
                importStageLabel(stage, message.status.metadata?.message as string | undefined),
              );
              if (message.status.progress !== undefined) setBackgroundImportProgress(message.status.progress);
            }

            // Background metadata indexing finishes after IMPORT_COMPLETED. On web the
            // class-count badge is often still 0 if the user opened before METADATA_READY.
            if (
              message.status.type === "IMPORT_PROGRESS" &&
              message.status.status === "METADATA_READY" &&
              message.status.projectId === projectId
            ) {
              const meta = message.status.metadata || {};
              const classCount = Number(meta.classCount);
              if (Number.isFinite(classCount) && classCount > 0) {
                setMetadata((prev) =>
                  ({
                    ...(prev || {}),
                    classCount,
                    tripleCount: meta.tripleCount ?? (prev as any)?.tripleCount,
                  }) as OntologyMetadata,
                );
              } else {
                // Counts missing from notification — refetch so the badge updates.
                void apiClient
                  .get<any>(`/api/ontology/metadata/${encodeProjectId(message.status.projectId)}?_t=${Date.now()}`)
                  .then((res) => {
                    const data = res?.data || res;
                    if (!data || typeof data !== "object") return;
                    setMetadata((prev) =>
                      ({
                        ...(prev || {}),
                        ...data,
                        classCount:
                          data.classCount > 0
                            ? data.classCount
                            : data.counts?.classes > 0
                              ? data.counts.classes
                              : (prev as any)?.classCount ?? 0,
                      }) as OntologyMetadata,
                    );
                  })
                  .catch((err) => console.warn("[Dashboard] METADATA_READY refresh failed:", err));
              }
            }
          }

          // Handle import completion
          if (message.status.type === "IMPORT_COMPLETED") {
            console.log("[Dashboard] ✅ IMPORT_COMPLETED for project:", message.status.projectId);
            console.log("[Dashboard] User choice:", userLoadingChoice.current);
            console.log("[Dashboard] Current projectId:", projectId);
            console.log("[Dashboard] pendingImportProjectIdRef.current:", pendingImportProjectIdRef.current);
            console.log("[Dashboard] isExpectingFileReady:", isExpectingFileReady);

            const isCurrentProject = message.status.projectId === projectId;
            const isPendingImport = message.status.projectId === pendingImportProjectIdRef.current;
            const userChoice = userLoadingChoice.current;

            // Only auto-load if:
            // 1. This is the current project being viewed, OR
            // 2. This matches the pendingImportProjectId (new upload)
            if (isCurrentProject || isPendingImport) {
              console.log("[Dashboard] Should auto-load:", isPendingImport ? "pending import" : "current project");

              // For new uploads, always switch to the newly imported project
              if (isPendingImport) {
                console.log("[Dashboard] Setting projectId to:", message.status.projectId);
                // Only update projectId if it's actually different (ignoring timestamp suffix)
                const currentBaseId = projectId?.replace(/-\d+$/, "");
                const newBaseId = message.status.projectId?.replace(/-\d+$/, "");
                if (currentBaseId !== newBaseId) {
                  console.log(
                    "[Dashboard] ProjectId is different, updating from",
                    projectId,
                    "to",
                    message.status.projectId,
                  );
                  setProjectId(message.status.projectId);
                } else {
                  console.log("[Dashboard] ProjectId is essentially the same (ignoring timestamp), skipping update");
                }
                console.log(message, "message--->");
                setLoadingProjectName(message.status.filename || message.status.projectId);
                // In free mode, mark the new file as active immediately
                if (!initialProjectId) {
                  const nextFileName = message.status.filename || `${message.status.projectId}.owl`;
                  setActiveFileId(message.status.projectId);
                  setActiveFileName(nextFileName);
                }
              }

              // Clear pending import tracking
              pendingImportProjectIdRef.current = null;
              setPendingImportProjectId(null);
              setInImportQueue(false);
              setQueuePosition(undefined);
              setTotalInQueue(undefined);
              setEstimatedWaitTimeMs(undefined);
              console.log("[Dashboard] Cleared pendingImportProjectIdRef");
              setIsExpectingFileReady(false);

              // Webapp: keep user in project library — the card turns green, user clicks to open
              if (!isDesktop()) {
                setShowLoadingChoice(false);
                setBackgroundImportActive(false);
                setBackgroundImportProgress(undefined);
                setIsInitialLoading(false);
                userLoadingChoice.current = null;
                setTimeout(() => fetchProjects(), 500);
                break;
              }

              // Data loading is handled by the fileReady message (always sent before IMPORT_COMPLETED).
              // Here we only clean up UI state. If fetchData is already in progress, chain cleanup onto it;
              // otherwise close dialogs immediately.
              const cleanupUI = () => {
                console.log("[Dashboard] Closing dialogs after IMPORT_COMPLETED");
                setShowLoadingChoice(false);
                setShowQueueStatus(false);
                setQueuePosition(undefined);
                setTotalInQueue(undefined);
                setEstimatedWaitTimeMs(undefined);
                setShowProjectSelector(false);
                setBackgroundImportActive(false);
                setBackgroundImportProgress(undefined);
                userLoadingChoice.current = null;
              };

              setIsHierarchyLoading(true);
              setLoadingStatusMessage(
                message.status.metadata?.message || "Loading class tree…",
              );

              if (loadingPromiseRef.current) {
                console.log("[Dashboard] fetchData already in progress (from fileReady), chaining UI cleanup");
                loadingPromiseRef.current.then(cleanupUI).catch(() => {
                  cleanupUI();
                });
              } else {
                const targetProjectId = message.status.projectId || projectId;
                // Guard: skip if the project is already loaded.
                // The background checker fires every 5s and re-dispatches IMPORT_COMPLETED
                // after the first load completes (loadingPromiseRef is null at that point),
                // which would cause infinite reload loops.
                const owlThingChildren =
                  classHierarchy.find((n) => n.id === "http://www.w3.org/2002/07/owl#Thing")?.children?.length ?? 0;
                if (targetProjectId === projectId && owlThingChildren > 0 && metadata) {
                  console.log("[Dashboard] IMPORT_COMPLETED: project already loaded, skipping redundant fetch");
                  cleanupUI();
                  setIsInitialLoading(false);
                  setIsHierarchyLoading(false);
                } else {
                  // Server-side import (upload-by-file-ref) bypasses the VSCode bridge,
                  // so no fileReady message is sent. Trigger fetchData here directly.
                  console.log("[Dashboard] No fetchData in progress — server-side import flow, triggering fetchData now");
                  loadingPromiseRef.current = fetchData(targetProjectId, false, initialProjectId)
                    .then(() => {
                      loadingPromiseRef.current = null;
                      cleanupUI();
                    })
                    .catch(() => {
                      loadingPromiseRef.current = null;
                      cleanupUI();
                    });
                }
              }

              // Refresh projects list
              setTimeout(() => fetchProjects(), 500);
            } else {
              console.log("[Dashboard] Import completed for different project - not auto-loading");
            }
          }

          // If import failed, handle it appropriately
          if (message.status.type === "IMPORT_FAILED") {
            console.log("[Dashboard] ❌ IMPORT_FAILED for project:", message.status.projectId);
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

            // Extract more user-friendly error message
            let displayError = errorMessage;
            if (
              errorMessage.includes("UnknownHostException: graphdb") ||
              errorMessage.includes("UnknownHostException")
            ) {
              displayError = "Cannot connect to the ontology service. Please ensure backend services are running.";
              console.log("[Dashboard] 🔄 Translated error to user-friendly message (UnknownHost)");
            } else if (errorMessage.includes("Connection refused") || errorMessage.includes("ConnectException")) {
              displayError = "Ontology service connection refused. Please verify backend services are running.";
              console.log("[Dashboard] 🔄 Translated error to user-friendly message (Connection refused)");
            } else if (errorMessage.includes("HTTP error code 404")) {
              displayError = "Ontology data store not found or not initialized. Please check service configuration.";
              console.log("[Dashboard] 🔄 Translated error to user-friendly message (404)");
            } else if (errorMessage.includes("unable to start transaction")) {
              displayError =
                "Unable to start database transaction. Please verify backend services are running.";
              console.log("[Dashboard] 🔄 Translated error to user-friendly message (transaction)");
            }

            console.log("[Dashboard] 📝 Display error:", displayError);

            // Show notification for all failed imports
            notificationService.error("Import Failed", `Failed to import "${projectName}": ${displayError}`);

            // Close dialogs and clear background progress if this is the current project
            if (message.status.projectId === projectId) {
              console.log("[Dashboard] Closing dialogs for current project");
              setLoadFailure({ message: displayError, projectId: message.status.projectId });
              setShowLoadingChoice(false);
              setShowQueueStatus(false);
              setIsInitialLoading(false);
              setIsExpectingFileReady(false);
              setQueuePosition(undefined);
              setTotalInQueue(undefined);
              setEstimatedWaitTimeMs(undefined);
              setBackgroundImportActive(false);
              setBackgroundImportProgress(undefined);
              loadingPromiseRef.current = null;
            }
          }

          // Show queue status when import starts.
          // Also: if IMPORT_PROGRESS arrives for the current project with nothing showing,
          // activate the background banner (not the modal) so the user sees progress without
          // a dialog popping up again after they dismissed it.
          if (message.status.type === "IMPORT_STARTED" && message.status.projectId === projectId) {
            setShowQueueStatus(true);
          }
          if (
            message.status.type === "IMPORT_PROGRESS" &&
            message.status.projectId === projectId &&
            !showLoadingChoice && !showQueueStatus && !backgroundImportActive
          ) {
            // Auto-activate the background progress banner so the user always has visibility
            setBackgroundImportActive(true);
            if (message.status.progress !== undefined) setBackgroundImportProgress(message.status.progress);
          }

          // Clear project-specific status after completion/failure
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
          console.error("[Dashboard] ❌ Import failed for project:", message.projectId, message.error);
          setShowLoadingChoice(false);
          setShowQueueStatus(false);
          setIsInitialLoading(false);
          setIsExpectingFileReady(false);
          setBackgroundImportActive(false);
          setBackgroundImportProgress(undefined);
          setQueuePosition(undefined);
          setTotalInQueue(undefined);
          setEstimatedWaitTimeMs(undefined);
          loadingPromiseRef.current = null;
          if (message.projectId === projectId || !projectId) {
            setLoadFailure({
              message: sanitizeImportMessage(message.error) || "Import failed",
              projectId: message.projectId,
            });
          }
          notificationService.error("Import Failed", `Failed to import ontology: ${message.error || "Unknown error"}`);
          break;

        case "importTimeout":
          console.error("[Dashboard] ⏱️ Import timeout for project:", message.projectId);
          setShowLoadingChoice(false);
          setShowQueueStatus(false);
          setIsInitialLoading(false);
          setBackgroundImportActive(false);
          setBackgroundImportProgress(undefined);
          setQueuePosition(undefined);
          setTotalInQueue(undefined);
          setEstimatedWaitTimeMs(undefined);
          setInImportQueue(false);
          setPendingImportProjectId(null);
          notificationService.error(
            "Import Timeout",
            "The import operation took too long. Your ontology may still be processing. Please check back later.",
          );
          break;

        case "updateLoadingStatus":
          console.log(
            "[Dashboard] 📊 Loading status update:",
            message.message,
            `(${message.attempt}/${message.maxAttempts})`,
          );
          setLoadingStatusMessage(message.message);
          break;

        case "queueStatusUpdate": {
          const qProjectId = message.status?.projectId;
          const isQueueRelevant =
            qProjectId === projectId ||
            qProjectId === pendingImportProjectIdRef.current ||
            isExpectingFileReady;
          if (isQueueRelevant && message.status) {
            const status = message.status.status;
            const position = message.status.queuePosition ?? 0;
            setQueuePosition(position);
            setTotalInQueue(message.status.totalInQueue);
            setEstimatedWaitTimeMs(message.status.estimatedWaitTimeMs);
            if (message.status.message) {
              setLoadingStatusMessage(message.status.message);
            }
            if (status === "QUEUED" || status === "PROCESSING") {
              setInImportQueue(true);
              setShowQueueStatus(true);
            }
            if (status === "COMPLETED" || status === "FAILED") {
              setQueuePosition(undefined);
              setTotalInQueue(undefined);
              setEstimatedWaitTimeMs(undefined);
              setInImportQueue(false);
            }
          }
          break;
        }

        case "citationFormatted":
          // Handle formatted citation from extension (legacy path)
          console.log("[Dashboard] 📚 Received formatted citation");
          if (message.citation && message.projectId === projectId) {
            // Insert citation before closing tags for XML formats, or append for others
            setCodeViewContent((prev) => {
              if (!prev) return message.citation;
              // For RDF/XML, insert before closing </rdf:RDF> tag
              const closingTagMatch = prev.match(/(\s*<\/rdf:RDF\s*>\s*)$/i);
              if (closingTagMatch && closingTagMatch.index !== undefined) {
                return prev.substring(0, closingTagMatch.index) + "\n\n" + message.citation + "\n" + closingTagMatch[0];
              }
              return prev + "\n\n" + message.citation;
            });

            // Show success notification
            if (message.metadata?.title) {
              notificationService.success("Citation Inserted", `Added: ${message.metadata.title}`);
            }
          }
          break;

        case "zoteroLibraryData":
        case "zoteroLibraryError":
          // These will be handled by CitationPickerDialog component
          break;
      }
    };

    console.log("[Dashboard] 📢 Attaching message listener");
    window.addEventListener("message", handleMessage);

    // CollaborationContext dispatches CustomEvent("importStatusUpdate") from WebSocket.
    // The "message" listener above only catches VSCode postMessage — bridge the gap here.
    const handleImportStatusCustomEvent = (e: Event) => {
      const status = (e as CustomEvent).detail;
      handleMessage({ data: { type: "importStatusUpdate", status } } as MessageEvent);
    };
    window.addEventListener("importStatusUpdate", handleImportStatusCustomEvent);

    const handleQueueStatusCustomEvent = (e: Event) => {
      const status = (e as CustomEvent).detail;
      handleMessage({ data: { type: "queueStatusUpdate", status } } as MessageEvent);
    };
    window.addEventListener("queueStatusUpdate", handleQueueStatusCustomEvent);

    const handleQueueStatsCustomEvent = (e: Event) => {
      const stats = (e as CustomEvent).detail;
      handleMessage({ data: { type: "queueStats", stats } } as MessageEvent);
    };
    window.addEventListener("queueStatsUpdate", handleQueueStatsCustomEvent);

    // Force-close the loading dialog when the preload signals import is done
    // but the normal condition (isCurrentProject || isPendingImport) didn't fire.
    const handleForceClose = () => {
      console.log("[Dashboard] forceCloseLoadingDialog received — clearing spinner");
      setIsInitialLoading(false);
      setShowLoadingChoice(false);
      setShowQueueStatus(false);
      setBackgroundImportActive(false);
      setBackgroundImportProgress(undefined);
      pendingImportProjectIdRef.current = null;
      setPendingImportProjectId(null);
      setInImportQueue(false);
      setQueuePosition(undefined);
      setTotalInQueue(undefined);
      setEstimatedWaitTimeMs(undefined);
    };
    window.addEventListener("forceCloseLoadingDialog", handleForceClose);

    // CRITICAL: Send webviewReady AFTER listener is attached, but ONLY ONCE
    // This ensures we receive any immediate messages (like showLoading) from the extension
    if (window.vscode && !webviewReadySentRef.current) {
      webviewReadySentRef.current = true;
      console.log("[Dashboard] 📢 Sending webviewReady to extension (first time only)");
      window.vscode.postMessage({ type: "webviewReady" });
    }

    return () => {
      console.log("[Dashboard] 📢 Removing message listener");
      window.removeEventListener("message", handleMessage);
      window.removeEventListener("importStatusUpdate", handleImportStatusCustomEvent);
      window.removeEventListener("queueStatusUpdate", handleQueueStatusCustomEvent);
      window.removeEventListener("queueStatsUpdate", handleQueueStatsCustomEvent);
      window.removeEventListener("forceCloseLoadingDialog", handleForceClose);
    };
  }, [projectId, initialProjectId, isExpectingFileReady, showLoadingChoice]); // Remove fetchData to prevent infinite loop - it's captured in the closure

  // Poll import queue while a file upload/import is in progress (covers desktop/no-auth WS gaps)
  useEffect(() => {
    const activeProjectId = pendingImportProjectId || projectId;
    if (!activeProjectId || (!showLoadingChoice && !isExpectingFileReady)) {
      return;
    }

    const pollQueue = async () => {
      try {
        const positionData: any = await apiClient.get(`/api/import-queue/position/${activeProjectId}`);
        if (!positionData?.inQueue) {
          setInImportQueue(false);
          return;
        }
        setInImportQueue(true);
        setQueuePosition(positionData.position ?? 0);
        setTotalInQueue(positionData.totalInQueue ?? 0);
        setEstimatedWaitTimeMs(positionData.estimatedWaitMs ?? 0);
        if (positionData.message) {
          setLoadingStatusMessage(sanitizeImportMessage(positionData.message));
        }
        setShowQueueStatus(true);
      } catch {
        // Queue endpoint may be unavailable during startup
      }
    };

    pollQueue();
    const intervalId = setInterval(pollQueue, 3000);
    return () => clearInterval(intervalId);
  }, [showLoadingChoice, isExpectingFileReady, projectId, pendingImportProjectId]);

  const loadChildren = useCallback(
    async (nodeId: string) => {
      if (!projectId) return;
      try {
        console.log(`[loadChildren] Loading children for node: ${nodeId}`);

        // Check if children are already loaded to avoid redundant API calls
        const findNode = (nodes: TreeNode[]): TreeNode | undefined => {
          for (const n of nodes) {
            if (n.id === nodeId) return n;
            if (n.children) {
              const found = findNode(n.children);
              if (found) return found;
            }
          }
          return undefined;
        };

        // Use functional state access to check current hierarchy without adding it as dependency
        let alreadyLoaded = false;
        setClassHierarchy((prev) => {
          const node = findNode(prev);
          if (node?.children && node.children.length > 0) {
            alreadyLoaded = true;
          }
          return prev; // Don't modify state
        });

        if (alreadyLoaded) {
          console.log(`[loadChildren] ⚡ Children already loaded for node: ${nodeId}, skipping API call`);
          return;
        }

        // Special case: when loading children of owl:Thing, use the top-level endpoint
        // which finds ALL top-level classes (not just those with explicit rdfs:subClassOf owl:Thing)
        // OPTIMIZED: Use limit parameter for faster initial load (backend has caching)
        const isOwlThing = nodeId === "http://www.w3.org/2002/07/owl#Thing";
        const childrenUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
        // See hierarchyUserId/draftScopeParam comment above — userId isn't a scope signal.
        const childrenDraftScopeParam = isDraftScopeActive() ? "&draft=true" : "";
        const endpoint = isOwlThing
          ? `/api/ontology/classes/top-level/${projectId}?limit=5000&scope=${hierarchyImportsScope}&userId=${encodeURIComponent(childrenUserId)}${childrenDraftScopeParam}`
          : `/api/ontology/classes/children/${projectId}?parentIri=${encodeURIComponent(nodeId)}&scope=${hierarchyImportsScope}&userId=${encodeURIComponent(childrenUserId)}${childrenDraftScopeParam}`;

        const response = await apiClient.get<any>(endpoint);

        // Extract array from response - handle both direct array and wrapped responses
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
              return {
                ...n,
                children: applyInstanceCountsToTree(mappedChildren, classInstanceCounts),
                hasChildren: mappedChildren.length > 0,
              };
            }
            if (n.children) {
              return { ...n, children: updateTree(n.children) };
            }
            return n;
          });

        setClassHierarchy((prevHierarchy) => updateTree(prevHierarchy));
      } catch (error) {
        console.error(`Failed to load children for ${nodeId}`, error);
      }
    },
    [projectId, classInstanceCounts, applyInstanceCountsToTree, hierarchyImportsScope, user],
  );

  const fetchInferredChildren = useCallback(
    async (nodeId: string) => {
      if (!projectId) return [];
      try {
        const response = await apiClient.get<any>(`/api/ontology/${projectId}/reasoner/inferred-subclasses`, {
          params: {
            classIri: nodeId,
            direct: true,
            reasonerType: selectedReasoner,
          },
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
      const nothingIri = "http://www.w3.org/2002/07/owl#Nothing";
      const mappedChildren: TreeNode[] = inferred
        .filter((item: any) => {
          if (!item?.iri) return false;
          if (nodeId === nothingIri) return true;
          return item.iri !== nothingIri;
        })
        .map((item: any) => ({
          id: item.iri,
          label: item.label || getLocalName(item.iri),
          children: [],
          hasChildren: item.hasChildren !== undefined ? item.hasChildren : true,
          isUnsatisfiable: nodeId === nothingIri || item.isUnsatisfiable === true,
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
      console.log("[DEBUG] updateItemInState called for item:", updatedItem.id, "markUnsaved:", markUnsaved);
      console.log("[CHANGE TRACKING] Entity updated:", {
        entityId: updatedItem.id,
        entityLabel: updatedItem.label,
        entityType: entitiesTab,
        modifiedBy: user?.username || "anonymous",
        timestamp: new Date().toISOString(),
      });

      const updateRecursively = (items: SelectableItem[]): SelectableItem[] => {
        return items.map((item) => {
          if (item.id === updatedItem.id) {
            // Preserve children from the existing item if the new item doesn't have them populated
            // The updatedItem from details endpoint usually doesn't have the full children tree
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

      // Update selected item if it matches
      setSelectedItem((prev) => {
        if (prev?.id === updatedItem.id) {
          console.log("[Dashboard] Updating selected item in state (ID match)");
          return updatedItem;
        }
        return prev;
      });

      switch (entitiesTab) {
        case "Classes":
          setClassHierarchy((prev) => updateRecursively(prev) as TreeNode[]);
          break;
        case "ObjectProperties":
          setObjectProperties((prev: Property[]) => prev.map((p: Property) => (p.id === updatedItem.id ? (updatedItem as Property) : p)));
          setObjectPropertyHierarchy((prev: TreeNode[]) => updateRecursively(prev) as TreeNode[]);
          break;
        case "DataProperties":
          setDataProperties((prev: Property[]) => prev.map((p: Property) => (p.id === updatedItem.id ? (updatedItem as Property) : p)));
          setDataPropertyHierarchy((prev: TreeNode[]) => updateRecursively(prev) as TreeNode[]);
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

      // Mark as unsaved to enable Save button (only if markUnsaved is true).
      // Skipped in webapp public/live sync since the mutation already landed live.
      if (markUnsaved && !isLiveWriteMode()) {
        setHasUnsavedChanges(true);
      }
    },
    [entitiesTab, user],
  );

  const refreshClassHierarchy = useCallback(async () => {
    if (!projectId) return;
    if (shouldDeferHierarchyDuringFileOpen()) {
      console.log("[Dashboard] Deferring class hierarchy refresh during file open");
      return;
    }
    const now = Date.now();
    if (classHierarchyRefreshInFlight.current) {
      console.warn("[Dashboard] Skipping class hierarchy refresh: already in flight — queuing a follow-up so this request isn't lost");
      classHierarchyRefreshQueued.current = true;
      return;
    }
    if (now - lastClassHierarchyRefreshAt.current < 2000) {
      console.warn("[Dashboard] Skipping class hierarchy refresh: throttled — queuing a follow-up so this request isn't lost");
      classHierarchyRefreshQueued.current = true;
      return;
    }
    classHierarchyRefreshInFlight.current = true;
    lastClassHierarchyRefreshAt.current = now;
    try {
      const refreshUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      // See hierarchyUserId/draftScopeParam comment above — userId isn't a scope signal.
      const refreshDraftScopeParam = isDraftScopeActive() ? "&draft=true" : "";
      const topLevelRes = await apiClient.get<any>(`/api/ontology/classes/top-level/${encodeProjectId(projectId)}?scope=${hierarchyImportsScope}&userId=${encodeURIComponent(refreshUserId)}${refreshDraftScopeParam}&_t=${now}`);

      const hierarchyBuilding =
        topLevelRes?.hierarchyReady === false ||
        topLevelRes?.status === 202 ||
        topLevelRes?.success === false ||
        isOwlApiWarmingResponse(topLevelRes);
      if (hierarchyBuilding) {
        // Backend snapshot is stale/rebuilding after a recent mutation (e.g. add class).
        // Keep the current (optimistic) tree instead of overwriting it with an empty
        // result, and retry shortly until the rebuilt snapshot is ready.
        if (classHierarchyRefreshRetryCount.current < 60) {
          classHierarchyRefreshRetryCount.current += 1;
          console.warn(
            `[Dashboard] Class hierarchy snapshot not ready yet (attempt ${classHierarchyRefreshRetryCount.current}) — keeping current tree and retrying`,
          );
          window.setTimeout(() => {
            lastClassHierarchyRefreshAt.current = 0;
            refreshClassHierarchy();
          }, 2000);
        } else {
          console.error("[Dashboard] Class hierarchy snapshot never became ready after retries — giving up");
          classHierarchyRefreshRetryCount.current = 0;
        }
        return;
      }
      classHierarchyRefreshRetryCount.current = 0;

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
        label: c.label ?? c.id ?? "",
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
      setIsHierarchyLoading(false);
      console.log("[Dashboard] ✅ Class hierarchy refreshed via refreshClassHierarchy");

      // Re-load children for all previously expanded nodes to preserve tree state.
      // Deduplicate — expandedNodes can contain the same ID multiple times when a class
      // has multiple parents and was expanded from different parent contexts.
      const owlThing = "http://www.w3.org/2002/07/owl#Thing";
      const seenIds = new Set<string>();
      const currentExpandedNodes = expandedNodesRef.current.filter(
        (id) => id !== owlThing && !seenIds.has(id) && seenIds.add(id),
      );
      for (const nodeId of currentExpandedNodes) {
        try {
          await loadChildren(nodeId);
        } catch (err) {
          console.log(`[Dashboard] Could not reload children for ${nodeId}:`, err);
        }
      }
    } catch (error: any) {
      const status = error?.status ?? error?.response?.status;
      if (status === 503) {
        console.warn("[Dashboard] Editor busy (503) during hierarchy refresh — keeping loading state");
        setIsHierarchyLoading(true);
        setLoadingStatusMessage("Ontology editor is busy — still loading…");
      } else {
        console.error("[Dashboard] Failed to refresh class hierarchy:", error);
        setIsHierarchyLoading(false);
      }
    } finally {
      classHierarchyRefreshInFlight.current = false;
      // A refresh request arrived while this one was in flight (or throttled) and got
      // queued instead of dropped — run it now so that mutation is never permanently lost.
      if (classHierarchyRefreshQueued.current) {
        classHierarchyRefreshQueued.current = false;
        lastClassHierarchyRefreshAt.current = 0;
        refreshClassHierarchy();
      }
    }
  }, [projectId, loadChildren, classInstanceCounts, applyInstanceCountsToTree, shouldDeferHierarchyDuringFileOpen, user]);

  // Keep hierarchyAnnotationProperties in sync with the existing annotation property list
  useEffect(() => {
    setHierarchyAnnotationProperties(
      annotationProperties.map((p) => ({ id: p.id, label: p.label }))
    );
  }, [annotationProperties]);

  // Batch-fetch annotation values for all current hierarchy nodes when annotation rendering is active
  const loadHierarchyAnnotationValues = useCallback(async (iris: string[], propertyIri: string) => {
    if (!projectId || !propertyIri || iris.length === 0) return;
    try {
      const res = await apiClient.post<any>(
        `/api/ontology/annotations/batch/${encodeProjectId(projectId)}`,
        { iris, propertyIri }
      );
      const data: Record<string, string> = res?.data ?? res ?? {};
      setHierarchyAnnotationValues((prev) => {
        const next = new Map(prev);
        Object.entries(data).forEach(([iri, val]) => next.set(iri, val));
        return next;
      });
    } catch {
      // fail silently — hierarchy will fall back to labels
    }
  }, [projectId]);

  // Reset annotation cache when property or mode changes
  useEffect(() => {
    fetchedAnnotationIrisRef.current = new Set();
    if (hierarchyDisplayMode !== "annotation") setHierarchyAnnotationValues(new Map());
  }, [hierarchyAnnotationPropIri, hierarchyDisplayMode]);

  // Incrementally fetch annotation values for nodes not yet fetched (avoids full re-fetch on every expansion)
  useEffect(() => {
    if (hierarchyDisplayMode !== "annotation" || !hierarchyAnnotationPropIri) return;
    const collectIris = (nodes: TreeNode[]): string[] =>
      nodes.flatMap((n) => [n.id, ...collectIris((n.children as TreeNode[]) ?? [])]);
    const newIris = [
      ...collectIris(classHierarchy),
      ...collectIris(objectPropertyHierarchy as TreeNode[]),
      ...collectIris(dataPropertyHierarchy as TreeNode[]),
      ...collectIris(annotationPropertyHierarchy),
      ...individuals.map(i => i.id),
      ...datatypes.map(d => d.id),
    ]
      .filter(Boolean)
      .filter(iri => !fetchedAnnotationIrisRef.current.has(iri));
    if (newIris.length === 0) return;
    newIris.forEach(iri => fetchedAnnotationIrisRef.current.add(iri));
    loadHierarchyAnnotationValues(newIris, hierarchyAnnotationPropIri);
  }, [hierarchyDisplayMode, hierarchyAnnotationPropIri, classHierarchy, objectPropertyHierarchy, dataPropertyHierarchy, annotationPropertyHierarchy, individuals, datatypes, loadHierarchyAnnotationValues]);

  // Reload hierarchy when imports scope changes — bypass throttle since this is an explicit user action
  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "Classes") return;
    lastClassHierarchyRefreshAt.current = 0;
    refreshClassHierarchy();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hierarchyImportsScope]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "Classes") return;
    if (shouldDeferHierarchyDuringFileOpen()) return;

    console.log("[Dashboard] Classes tab active, view mode:", currentHierarchyViewMode);
    if (currentHierarchyViewMode === "inferred") {
      // Always load full recursive hierarchy from API when in inferred mode
      // to ensure we have the full depth like Desktop Protégé
      console.log("[Dashboard] Loading inferred hierarchy from API...");
      loadInferredHierarchy();
    } else {
      console.log("[Dashboard] Refreshing asserted hierarchy...");
      refreshClassHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, currentHierarchyViewMode]);

  // ── Desktop lazy-Fuseki preparation ────────────────────────────────────
  // Tabs whose content queries the triple store. Opening any of them starts
  // Fuseki + syncs the ontology. Graph/Fuzzy additionally block rendering
  // until ready, because their plugins fetch on mount and would otherwise
  // race the cold start into a silent blank view. Web is untouched: every
  // branch below is behind isDesktop().
  const [desktopFusekiPrep, setDesktopFusekiPrep] = useState<{
    projectId: string | null;
    status: "idle" | "preparing" | "ready" | "failed" | "bypassed";
    error?: string;
  }>({ projectId: null, status: "idle" });
  const desktopFusekiPrepRef = useRef(desktopFusekiPrep);
  desktopFusekiPrepRef.current = desktopFusekiPrep;

  const startDesktopFusekiPrep = useCallback((pid: string) => {
    setDesktopFusekiPrep({ projectId: pid, status: "preparing" });
    // Hard UI timeout — never leave an endless spinner. Large ontologies can
    // legitimately take minutes, so this only flips the UI to a retry/continue
    // state; a late success still upgrades it back to ready.
    const timeout = window.setTimeout(() => {
      const cur = desktopFusekiPrepRef.current;
      if (cur.projectId === pid && cur.status === "preparing") {
        setDesktopFusekiPrep({
          projectId: pid,
          status: "failed",
          error: "Preparing is taking longer than expected. It may still finish in the background.",
        });
      }
    }, 180_000);
    void ensureDesktopFusekiSync(pid).then((r) => {
      window.clearTimeout(timeout);
      const cur = desktopFusekiPrepRef.current;
      if (cur.projectId !== pid || cur.status === "bypassed") return;
      if (r.synced) {
        setDesktopFusekiPrep({ projectId: pid, status: "ready" });
      } else {
        setDesktopFusekiPrep({
          projectId: pid,
          status: "failed",
          error: r.error || "The triple store could not be prepared.",
        });
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!isDesktop() || !projectId) return;
    const fusekiTabs = ["SPARQL", "Graph", "WebVOWL", "Fuzzy", "DLQuery", "Reasoner"];
    if (!fusekiTabs.includes(mainTab)) return;
    const cur = desktopFusekiPrepRef.current;
    if (cur.projectId === projectId) {
      // 'failed' waits for an explicit Try-again click; 'preparing' is already
      // syncing; 'bypassed' was the user's choice. 'ready' can't be trusted as-is:
      // OWLAPI mutations re-mark the deferred-sync flag, and serving these tabs
      // from a stale triple store is exactly what this gate exists to prevent —
      // re-check the marker on every activation and re-enter the loader if set.
      if (cur.status === "ready") {
        let cancelled = false;
        void isDesktopFusekiSyncPending(projectId).then((pending) => {
          if (cancelled || !pending) return;
          const latest = desktopFusekiPrepRef.current;
          if (latest.projectId === projectId && latest.status === "ready") {
            startDesktopFusekiPrep(projectId);
          }
        });
        return () => {
          cancelled = true;
        };
      }
      if (cur.status !== "idle") return;
    }
    startDesktopFusekiPrep(projectId);
  }, [mainTab, projectId, startDesktopFusekiPrep]);

  // Blocks Fuseki-dependent plugin mounts on desktop until the store is usable.
  const desktopFusekiBlocked =
    isDesktop() && !!projectId &&
    desktopFusekiPrep.status !== "ready" && desktopFusekiPrep.status !== "bypassed";

  const renderDesktopFusekiGate = () => (
    <div className="flex flex-col items-center justify-center h-full min-h-[320px] gap-4 p-8 text-center">
      {desktopFusekiPrep.status === "failed" ? (
        <>
          <AlertTriangle size={40} className="text-amber-400" />
          <p className="text-white font-semibold text-lg">Triple store isn't ready</p>
          <p className="text-slate-400 text-sm max-w-md">{desktopFusekiPrep.error}</p>
          <div className="flex gap-3">
            <button
              onClick={() => projectId && startDesktopFusekiPrep(projectId)}
              className="px-4 py-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white text-sm font-semibold hover:from-purple-700 hover:to-indigo-700 transition-all">
              Try again
            </button>
            <button
              onClick={() => setDesktopFusekiPrep((p) => ({ ...p, status: "bypassed" }))}
              className="px-4 py-2 rounded-xl border border-white/20 bg-white/5 text-slate-300 text-sm font-medium hover:bg-white/10 transition-all">
              Continue anyway
            </button>
          </div>
        </>
      ) : (
        <>
          <Loader2 size={40} className="animate-spin text-purple-400" />
          <p className="text-white font-semibold text-lg">Preparing triple store…</p>
          <p className="text-slate-400 text-sm max-w-md">
            Starting the local SPARQL engine and indexing your ontology. Large
            ontologies can take a minute — this view opens automatically when ready.
          </p>
        </>
      )}
    </div>
  );

  // Non-blocking variant for tabs where the user can keep working while the
  // store catches up (SPARQL/DLQuery/Reasoner) — queries just wait for indexing.
  const renderDesktopFusekiBanner = () => {
    if (!desktopFusekiBlocked) return null;
    if (desktopFusekiPrep.status === "failed") {
      return (
        <div className="flex items-center gap-2 px-4 py-2 bg-amber-500/10 border-b border-amber-400/20 text-amber-300 text-sm">
          <AlertTriangle size={14} className="flex-shrink-0" />
          <span>{desktopFusekiPrep.error || "The triple store could not be prepared."}</span>
          <button
            onClick={() => projectId && startDesktopFusekiPrep(projectId)}
            className="ml-auto underline hover:text-amber-200 flex-shrink-0">
            Try again
          </button>
        </div>
      );
    }
    return (
      <div className="flex items-center gap-2 px-4 py-2 bg-purple-500/10 border-b border-purple-400/20 text-purple-300 text-sm">
        <Loader2 size={14} className="animate-spin flex-shrink-0" />
        <span>Preparing triple store — queries will run once indexing finishes.</span>
      </div>
    );
  };

  // ── Desktop OWLAPI deferred-hierarchy resolver ────────────────────────────
  // Single responsibility: when fetchData deferred the hierarchy render because
  // warmOntologyInMemory returned ready=false, this effect polls cache-status
  // until OWLAPI is confirmed ready, then renders the hierarchy ONCE cleanly.
  //
  // Design principles:
  //   • Only active when fetchData set desktopHierarchyDeferredForProject.
  //   • Polls every 5 s (not 1.5 s) — OWLAPI load takes tens of seconds.
  //   • 5-minute hard timeout: falls back to SPARQL so the user is never stuck.
  //   • fetchData marks owlapiReadyHandledRef when warm.ready=true, preventing
  //     any redundant second render from this effect.
  const owlapiReadyHandledRef = useRef<string | null>(null);
  // Set by fetchData when OWLAPI wait was deferred (warm returned ready=false).
  const desktopHierarchyDeferredForProject = useRef<string | null>(null);

  useEffect(() => {
    if (!isDesktop()) return;
    if (!projectId) return;
    // Only run when fetchData explicitly deferred the hierarchy for this project.
    if (desktopHierarchyDeferredForProject.current !== projectId) return;
    // Already handled (warm succeeded in fetchData or previous poll iteration).
    if (owlapiReadyHandledRef.current === projectId) return;

    let cancelled = false;
    let elapsedMs = 0;
    const POLL_INTERVAL_MS = 1_500;
    const TIMEOUT_MS = 300_000; // 5 min — matches warmOntologyInMemory timeout
    let timer: ReturnType<typeof setTimeout> | undefined;

    const applyOwlapiReady = (res: any) => {
      owlapiReadyHandledRef.current = projectId;
      desktopHierarchyDeferredForProject.current = null;
      const patch = extractDeclarationCountsPatch(res);
      if (patch) setMetadata((prev) => ({ ...(prev || {}), ...patch }) as OntologyMetadata);
      lastClassHierarchyRefreshAt.current = 0;
      setIsHierarchyLoading(true); // briefly show skeleton during transition
      refreshClassHierarchy();     // fetches from OWLAPI → single clean render
    };

    const fallbackToSparql = () => {
      // Desktop owlapi-first: Fuseki is often not running on open — SPARQL hierarchy hangs forever.
      console.warn("[Dashboard] Desktop: OWLAPI warm slow — retrying warm POST (no SPARQL fallback)");
      void warmOntologyInMemory(projectId, { timeoutMs: 120_000 }).then((warm) => {
        if (warm.ready) {
          applyOwlapiReady(warm);
          return;
        }
        if (!warm.sparqlFallback) {
          setLoadingStatusMessage("Still opening ontology (fast path)…");
          elapsedMs = 0;
          timer = setTimeout(poll, POLL_INTERVAL_MS);
          return;
        }
        console.warn("[Dashboard] Desktop: OWLAPI unavailable — last-resort SPARQL hierarchy");
        owlapiReadyHandledRef.current = projectId;
        desktopHierarchyDeferredForProject.current = null;
        lastClassHierarchyRefreshAt.current = 0;
        setIsHierarchyLoading(false);
        setIsInitialLoading(false);
        setLoadingStatusMessage("");
        refreshClassHierarchy();
      });
    };

    const poll = async () => {
      if (cancelled) return;
      try {
        const res = await apiClient.get<any>(`/api/ontology/cache-status/${encodeProjectId(projectId)}`);
        const ready = res?.owlapiReady ?? res?.data?.owlapiReady;
        if (ready && !cancelled) {
          console.log("[Dashboard] Desktop deferred hierarchy — OWLAPI now ready, rendering once");
          applyOwlapiReady(res);
          return;
        }
      } catch {
        // cache-status unavailable — stop quietly, leave skeleton as-is
        owlapiReadyHandledRef.current = projectId;
        return;
      }
      elapsedMs += POLL_INTERVAL_MS;
      if (!cancelled && elapsedMs < TIMEOUT_MS) {
        timer = setTimeout(poll, POLL_INTERVAL_MS);
      } else if (!cancelled) {
        fallbackToSparql();
      }
    };

    timer = setTimeout(poll, POLL_INTERVAL_MS); // first check after 5 s
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  // Load inferred object property hierarchy when switching to inferred mode
  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "ObjectProperties") return;
    console.log("[Dashboard] ObjectProperties tab active, view mode:", hierarchyViewModes.ObjectProperties);
    if (hierarchyViewModes.ObjectProperties === "inferred") {
      // Always reload to ensure fresh data from the reasoner
      console.log("[Dashboard] Loading inferred object property hierarchy from API...");
      loadInferredObjectPropertyHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, hierarchyViewModes.ObjectProperties]);

  // Load inferred data property hierarchy when switching to inferred mode
  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "DataProperties") return;
    console.log("[Dashboard] DataProperties tab active, view mode:", hierarchyViewModes.DataProperties);
    if (hierarchyViewModes.DataProperties === "inferred") {
      // Always reload to ensure fresh data from the reasoner
      console.log("[Dashboard] Loading inferred data property hierarchy from API...");
      loadInferredDataPropertyHierarchy();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, mainTab, entitiesTab, hierarchyViewModes.DataProperties]);

  // On-demand property detail loading: when a property is selected, fetch full details (domains, ranges, etc.)
  useEffect(() => {
    if (!projectId || !selectedItem) return;
    const isProperty =
      (selectedItem as any).type === "ObjectProperty" ||
      (selectedItem as any).type === "DatatypeProperty" ||
      (selectedItem as any).type === "AnnotationProperty";
    if (!isProperty) return;
    // Skip if full details were already merged from the detail endpoint
    if ((selectedItem as any)._propertyDetailsLoaded) return;
    // Also skip for built-in top properties
    if (
      selectedItem.id === "http://www.w3.org/2002/07/owl#topObjectProperty" ||
      selectedItem.id === "http://www.w3.org/2002/07/owl#topDataProperty"
    )
      return;

    const encodedProjectId = encodeURIComponent(projectId);
    const encodedIri = encodeURIComponent(selectedItem.id);
    apiClient
      .get<any>(withDraftScope(`/api/ontology/properties/detail/${encodedProjectId}?iri=${encodedIri}`))
      .then((res: any) => {
        const payload = res?.data ?? res;
        const detail = payload?.data ?? payload;
        if (detail && detail.id) {
          // Merge detail fields into the selected item
          setSelectedItem((prev) => {
            if (prev?.id !== detail.id) return prev;
            return { ...prev, ...detail, _propertyDetailsLoaded: true };
          });
        }
      })
      .catch((e: any) => console.warn("[Dashboard] Property detail fetch failed (non-critical):", e?.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, selectedItem?.id]);

  // Handle remote edits from collaborative users in real-time
  useEffect(() => {
    const handleRemoteEdit = (event: Event) => {
      const customEvent = event as CustomEvent;
      const edit = customEvent.detail;

      console.log("[Dashboard] 🔄 Handling remote edit event:", edit);

      // Immediately reload the affected data based on edit type
      if (!projectId) {
        console.warn("[Dashboard] No project ID, cannot apply remote edit");
        return;
      }

      // Metadata events must ALWAYS refresh — same user on two devices must stay in sync.
      // Only skip for entity-mutation events where local optimistic state was already applied.
      const METADATA_EVENTS = new Set([
        "ONTOLOGY_ANNOTATION_ADDED", "ONTOLOGY_ANNOTATION_MODIFIED", "ONTOLOGY_ANNOTATION_DELETED",
        "IMPORT_ADDED", "IMPORT_REMOVED",
        "GCI_ADDED", "GCI_REMOVED",
      ]);
      if (!METADATA_EVENTS.has(edit.type)) {
        const editUserId = (edit as any).userId || (edit as any).user?.id || (edit as any).user;
        const currentUserId = user?.email || user?.userId;
        if (editUserId && currentUserId && editUserId === currentUserId) {
          console.log("[Dashboard] ⏭️ Skipping refresh - edit was made by current user");
          return;
        }
      }

      // Map edit type to which data needs refreshing
      switch (edit.type) {
        case "CLASS_ADDED":
          console.log("[Dashboard] 📚 Class added by another user, refreshing hierarchy");
          // If we have parent info, try to refresh just that part of the tree
          if ((edit as any).parent) {
            const parentId = (edit as any).parent;
            console.log(`[Dashboard] Refreshing children of parent: ${parentId}`);
            loadChildren(parentId);
          } else {
            // Fallback to full refresh
            refreshClassHierarchy();
          }
          break;

        case "CLASS_DELETED":
          console.log("[Dashboard] 🗑️ Class deleted by remote user, refreshing hierarchy");
          // Always do full refresh on deletion — partial refresh can leave orphaned subtrees
          refreshClassHierarchy();
          break;

        case "CLASS_MODIFIED":
        case "CLASS_RENAMED":
          console.log("[Dashboard] ✏️ Class modified/renamed:", edit);
          // For modification, we can just fetch details and update state
          // This preserves the tree structure
          const classId = (edit as any).nodeId || (edit as any).iri || (edit as any).id;
          if (classId) {
            console.log(`[Dashboard] Fetching details for modified class: ${classId}`);
            const userId = user?.email || user?.userId;
            const userParam = userId ? `&userId=${encodeURIComponent(userId)}` : '';
            // Add delay to ensure backend is ready
            setTimeout(() => {
              apiClient
                .get(withDraftScope(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(classId)}${userParam}&_=${Date.now()}`))
                .then((response) => {
                  const details = response?.data?.data || response?.data || response;
                  if (!details || typeof details !== "object" || details.success) {
                    console.warn("[Dashboard] Unexpected class details response shape:", details);
                    return;
                  }
                  const merged = {
                    ...selectedItem,
                    ...details,
                    id: details.id || details.iri || classId,
                  };
                  console.log("[Dashboard] Received updated class data:", merged);
                  updateItemInState(merged);
                  console.log("[Dashboard] ✅ Class updated in state");
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 200);
          } else {
            // Fallback
            console.warn("[Dashboard] No class ID in edit event, falling back to full refresh");
            refreshClassHierarchy();
          }
          break;

        case "ANNOTATION_ADDED":
        case "ANNOTATION_MODIFIED":
        case "ANNOTATION_DELETED":
          console.log("[Dashboard] 📝 Refreshing annotation due to annotation edit:", edit);

          // Add a small delay to ensure backend consistency
          setTimeout(() => {
            // Trigger refresh of current selected item to show updated annotations
            if (selectedItem) {
              const entityId = selectedItem.id || (selectedItem as any).iri;
              // Check if the edit is relevant to the selected item (optional optimization, but good for correctness)
              // The edit object usually has 'subject' or 'iri'
              const editSubject = (edit as any).subject || (edit as any).iri || (edit as any).id;

              if (editSubject && editSubject !== entityId) {
                console.log(
                  `[Dashboard] Edit subject (${editSubject}) does not match selected item (${entityId}), but refreshing anyway to be safe`,
                );
              }

              console.log(`[Dashboard] Refreshing selected item: ${entityId}`);

              // Use the appropriate endpoint based on entity type to ensure we get full details (including annotations)
              let url: string;
              if (entitiesTab === "ObjectProperties" || entitiesTab === "DataProperties" || entitiesTab === "AnnotationProperties") {
                url = `/api/ontology/properties/detail/${projectId}?iri=${encodeURIComponent(entityId)}`;
              } else if (entitiesTab === "Individuals") {
                url = `/api/ontology/individuals/${projectId}?iri=${encodeURIComponent(entityId)}`;
              } else {
                url = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(entityId)}`;
              }

              apiClient
                .get(withDraftScope(url))
                .then((response) => {
                  const newData = response.data || response;
                  // Ensure ID is present (map IRI to ID if needed)
                  if (!newData.id && newData.iri) {
                    newData.id = newData.iri;
                  }

                  console.log("[Dashboard] Received updated entity data:", newData);
                  // Update both selected item and the item in the state/tree
                  updateItemInState(newData);
                  console.log("[Dashboard] ✅ Selected item refreshed with new annotations");
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh selected item:", error));
            } else {
              console.log("[Dashboard] No item selected, skipping annotation refresh");
            }
          }, 200); // 200ms delay
          break;

        case "PROPERTY_ADDED":
        case "PROPERTY_MODIFIED":
        case "PROPERTY_DELETED":
          console.log("[Dashboard] 🔗 Refreshing all properties due to property edit");
          // Refresh object + data property hierarchies
          refreshProperties();
          // Refresh annotation properties (separate endpoint)
          handleRefreshAnnotationProperties();
          break;

        case "INDIVIDUAL_ADDED":
        case "INDIVIDUAL_MODIFIED":
        case "INDIVIDUAL_DELETED":
          console.log("[Dashboard] 👤 Refreshing individuals due to individual edit");
          // Trigger refresh of individuals
          apiClient
            .get(withDraftScope(`/api/ontology/individuals/${projectId}`))
            .then((response) => {
              setIndividuals(response.data || []);
              console.log("[Dashboard] ✅ Individuals refreshed");
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh individuals:", error));
          break;

        // Handle SPARQL updates - need full refresh since we don't know what changed
        case "SPARQL_UPDATE":
          console.log("[Dashboard] 📊 SPARQL update detected, refreshing all data");
          showNotification(`${(edit as any).username || "Someone"} executed a SPARQL update. Refreshing...`, "info");
          // Full refresh since SPARQL can change anything
          fetchData(projectId, false);
          break;

        // Handle change reverts - need full refresh
        case "CHANGE_REVERTED":
          console.log("[Dashboard] ⏪ Change reverted, refreshing all data");
          showNotification(`${(edit as any).username || "Someone"} reverted a change. Refreshing...`, "info");
          // Full refresh to get the reverted state
          fetchData(projectId, false);
          break;

        // Handle project saved by another user
        case "PROJECT_SAVED":
          console.log("[Dashboard] 💾 Project saved by another user");
          showNotification(
            `${(edit as any).username || "Someone"} saved the project with ${(edit as any).appliedChanges || 0} changes`,
            "info",
          );
          // Refresh to get the latest saved state
          fetchData(projectId, false);
          break;

        // Handle disjoint axiom changes
        case "DISJOINT_ADDED":
        case "DISJOINT_REMOVED":
          console.log("[Dashboard] 🔗 Disjoint axiom changed, refreshing class hierarchy");
          refreshClassHierarchy();
          break;

        // Handle equivalent class axiom changes
        case "EQUIVALENT_ADDED":
        case "EQUIVALENT_REMOVED":
          console.log("[Dashboard] ⚖️ Equivalent class axiom changed, refreshing selected item:", edit);
          // If the edit is for the currently selected class, refresh its details
          if (selectedItem && selectedItem.id === (edit as any).nodeId) {
            console.log("[Dashboard] Refreshing selected class details for equivalent axiom change");
            // Use 1000ms delay to allow ClassEditor's 800ms refresh to complete first
            setTimeout(() => {
              apiClient
                .get(withDraftScope(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(selectedItem.id)}`))
                .then((response) => {
                  const details = response?.data?.data || response?.data || response;
                  console.log("[Dashboard] ✅ Class details refreshed with equivalent axioms:", details);
                  updateItemInState({
                    ...selectedItem,
                    equivalentClassesAxioms: details.equivalentClassesAxioms || [],
                  });
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 1000);
          }
          break;

        // Handle subclass axiom changes
        case "SUBCLASS_ADDED":
        case "SUBCLASS_REMOVED":
          console.log("[Dashboard] ⬆️ Subclass axiom changed, refreshing selected item:", edit);
          // If the edit is for the currently selected class, refresh its details
          if (selectedItem && selectedItem.id === (edit as any).nodeId) {
            console.log("[Dashboard] Refreshing selected class details for subclass axiom change");
            // Use 1000ms delay to allow ClassEditor's 800ms refresh to complete first
            setTimeout(() => {
              apiClient
                .get(withDraftScope(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(selectedItem.id)}`))
                .then((response) => {
                  const details = response?.data?.data || response?.data || response;
                  console.log("[Dashboard] ✅ Class details refreshed with subclass axioms:", details);
                  updateItemInState({
                    ...selectedItem,
                    subClassOfAxioms: details.subClassOfAxioms || [],
                  });
                  // Also refresh class hierarchy since parent relationships changed
                  refreshClassHierarchy();
                })
                .catch((error) => console.error("[Dashboard] Failed to refresh class details:", error));
            }, 1000);
          } else {
            // Still refresh hierarchy for subclass changes
            refreshClassHierarchy();
          }
          break;

        case "IMPORT_ADDED":
        case "IMPORT_REMOVED":
          console.log("[Dashboard] 📦 Import changed by remote user, refreshing imports");
          refreshOntologyImports();
          break;

        case "ONTOLOGY_ANNOTATION_ADDED":
        case "ONTOLOGY_ANNOTATION_MODIFIED":
        case "ONTOLOGY_ANNOTATION_DELETED":
          console.log("[Dashboard] 📝 Ontology annotation changed by remote user, refreshing");
          refreshOntologyAnnotations();
          break;

        case "SWRL_RULE_ADDED":
        case "SWRL_RULE_MODIFIED":
        case "SWRL_RULE_DELETED":
          console.log("[Dashboard] 📏 SWRL rule changed by remote user, notifying SWRL plugin");
          showNotification(
            `${(edit as any).username || "Someone"} ${
              edit.type === "SWRL_RULE_ADDED" ? "added" : edit.type === "SWRL_RULE_MODIFIED" ? "modified" : "deleted"
            } a SWRL rule`,
            "info",
          );
          window.dispatchEvent(new CustomEvent("swrlRulesUpdated", { detail: { projectId, editType: edit.type } }));
          break;

        case "GCI_ADDED":
        case "GCI_REMOVED":
          console.log("[Dashboard] 🔢 GCI changed by remote user, refreshing GCIs");
          apiClient
            .get(withDraftScope(`/api/ontology/metadata/${projectId}/gci`))
            .then((response) => {
              const raw = response?.data?.data || response?.data?.axioms || response?.axioms || response?.data || response;
              const gcis = Array.isArray(raw)
                ? raw.map((axiom: any) => ({
                    value: axiom.value,
                    subClass: axiom.subClass || axiom.definition || "",
                    superClass: axiom.superClass || axiom.superClassIri || "",
                    definition: axiom.subClass || axiom.definition || "",
                    superClassIri: axiom.superClass || axiom.superClassIri || "",
                    subExpression: axiom.subClass || axiom.subExpression || "",
                  }))
                : [];
              setGeneralClassAxioms(gcis);
              console.log("[Dashboard] ✅ GCIs refreshed");
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh GCIs:", error));
          break;

        default:
          console.log("[Dashboard] 🔄 Generic remote edit, refreshing metadata");
          // Generic refresh for other edit types
          // Only sent in private/draft mode — see hierarchyUserId comment above.
          apiClient
            .get(`/api/ontology/metadata/${projectId}?userId=${encodeURIComponent(resolveMutationActor(user?.userId || user?.email, user?.username).userId)}${isDraftScopeActive() ? "&draft=true" : ""}`)
            .then((response) => {
              setMetadata(response.data);
              console.log("[Dashboard] ✅ Metadata refreshed");
            })
            .catch((error) => console.error("[Dashboard] Failed to refresh metadata:", error));
      }

      // Refresh collaboration panel changes list to show the new edit immediately
      if (collaborationPanelRef.current) {
        console.log("[Dashboard] 🔄 Refreshing collaboration panel changes");
        collaborationPanelRef.current.refreshChanges();
      }
    };

    // Listen for remoteEditReceived events
    window.addEventListener("remoteEditReceived", handleRemoteEdit as EventListener);
    console.log("[Dashboard] 🎧 Registered listener for remote edits");

    return () => {
      window.removeEventListener("remoteEditReceived", handleRemoteEdit as EventListener);
      console.log("[Dashboard] 🎧 Unregistered listener for remote edits");
    };
  }, [projectId, selectedItem, entitiesTab]); // Removed fetchData, showNotification to prevent infinite loop

  // Handle rollback events from Change Assistant plugin - refresh data
  useEffect(() => {
    const handleRollback = (event: Event) => {
      const customEvent = event as CustomEvent;
      const detail = customEvent.detail;
      console.log("[Dashboard] 🔄 Rollback event received:", detail);

      if (!projectId || detail?.projectId !== projectId) {
        return;
      }

      const rollbackUser = detail.username || "Someone";
      const originalAuthor = detail.originalAuthor || "Unknown";
      const oldValue = detail.oldValue;
      const newValue = detail.newValue;

      // Build notification message with value changes if available
      let message = `${rollbackUser} rolled back change by ${originalAuthor}`;
      if (oldValue && newValue) {
        message += ` (from "${oldValue}" back to "${newValue}")`;
      } else if (newValue) {
        message += ` (restored to "${newValue}")`;
      }
      message += ". Refreshing data...";

      showNotification(message, "info");

      // Check if this is a rollback of an "added" change (which means deleting the entity)
      const isAddedRollback = detail.action && detail.action.toLowerCase() === "added";

      if (isAddedRollback) {
        // Entity was deleted by rollback - remove it from UI
        console.log("[Dashboard] 🗑️ Rollback of added change - removing entity from UI:", detail.entityIRI);

        // Clear selection if this was the selected item
        if (selectedItem?.id === detail.entityIRI) {
          setSelectedItem(null);
        }

        // Remove from class hierarchy
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

        // Remove from properties lists
        if (detail.changeType?.toLowerCase().includes("objectproperty")) {
          setObjectProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes("dataproperty")) {
          setDataProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes("annotationproperty")) {
          setAnnotationProperties((prev) => prev.filter((p) => p.id !== detail.entityIRI));
        }

        // Remove from individuals list
        if (detail.changeType?.toLowerCase().includes("individual")) {
          setIndividuals((prev) => prev.filter((i) => i.id !== detail.entityIRI));
        }

        return; // Don't try to fetch the deleted entity
      }

      // Refresh the data after rollback with longer delay to ensure GraphDB has processed
      setTimeout(() => {
        // If we have the entity IRI, refresh its details first
        if (detail?.entityIRI) {
          console.log("[Dashboard] 🔄 Refreshing entity details after rollback for:", detail.entityIRI);
          console.log("[Dashboard] 🔄 Entity type from event:", detail.entityType, "Current tab:", entitiesTab);

          // Determine the correct API endpoint based on current tab first, then entity type
          // Annotation changes should use the entity's actual type (class, property, individual)
          const entityType = detail.entityType ? detail.entityType.toLowerCase() : "";
          let apiEndpoint = "";

          // For annotation changes, we need to refresh the entity that has the annotation
          // The entityIRI is the entity whose annotation was changed
          // Use entitiesTab as primary indicator since that's what the user is viewing
          if (entitiesTab === "Classes" || entityType.includes("class") || entityType.includes("annotation")) {
            // For annotation changes on classes, fetch class details
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
            // Fallback: try to determine by the entityIRI or default to class details
            console.log("[Dashboard] 🔄 No specific tab match, using current entitiesTab:", entitiesTab);
            // Default to class details as most common case
            apiEndpoint = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(detail.entityIRI)}`;
          }

          if (apiEndpoint) {
            apiClient
              .get(withDraftScope(apiEndpoint))
              .then((response) => {
                const newData = response.data || response;
                if (!newData.id && newData.iri) {
                  newData.id = newData.iri;
                }
                console.log("[Dashboard] ✅ Refreshed entity after rollback:", newData);
                console.log("[Dashboard] 📝 Updated label:", newData.label);

                // Update the entity in the appropriate list (don't mark as unsaved - rollback is already in DB)
                updateItemInState(newData, false);

                // If this is the selected item, update it to show new values immediately
                if (selectedItem?.id === detail.entityIRI) {
                  setSelectedItem(newData);
                }

                // For annotation changes, just update the node in place without refreshing hierarchy
                // This prevents the tree from collapsing
                const isAnnotationChange = entityType.includes("annotation") || (oldValue && newValue); // Has old/new values = annotation change

                if (isAnnotationChange && (entitiesTab === "Classes" || entityType.includes("class"))) {
                  console.log("[Dashboard] 📝 Soft refresh: updating class node annotations in place");
                  // Update the class hierarchy node without reloading the tree
                  setClassHierarchy((prevHierarchy) => {
                    const updateNodeInTree = (nodes: TreeNode[]): TreeNode[] => {
                      return nodes.map((node) => {
                        if (node.id === detail.entityIRI) {
                          // Update this node's annotations
                          return { ...node, annotations: newData.annotations || node.annotations };
                        }
                        if (node.children && node.children.length > 0) {
                          // Recursively update children
                          return { ...node, children: updateNodeInTree(node.children) };
                        }
                        return node;
                      });
                    };
                    return updateNodeInTree(prevHierarchy);
                  });
                } else {
                  // For non-annotation changes, do a full refresh
                  if (entitiesTab === "Classes" || entityType.includes("class")) {
                    refreshClassHierarchy();
                  } else if (
                    entitiesTab === "ObjectProperties" ||
                    entitiesTab === "DataProperties" ||
                    entitiesTab === "AnnotationProperties"
                  ) {
                    refreshProperties();
                  } else if (entitiesTab === "Individuals") {
                    // Refresh individuals list
                    if (projectId) fetchData(projectId, false);
                  }
                }
              })
              .catch((error) => {
                console.error("[Dashboard] Failed to refresh entity after rollback:", error);
                // If specific entity fetch fails, try a full data refresh
                console.log("[Dashboard] Attempting full data refresh after rollback error");
                if (projectId) fetchData(projectId, false);
              });
          } else {
            // No specific endpoint, do a full refresh
            console.log("[Dashboard] No API endpoint matched, doing full refresh");
            if (projectId) fetchData(projectId, false);
          }
        }
      }, 1500); // Increased delay to ensure GraphDB fully processes the rollback
    };

    window.addEventListener("ontologyRollback", handleRollback as EventListener);
    console.log("[Dashboard] 🎧 Registered listener for rollback events");

    return () => {
      window.removeEventListener("ontologyRollback", handleRollback as EventListener);
    };
  }, [projectId, selectedItem, entitiesTab]); // Removed fetchData, showNotification to prevent infinite loop

  // Handle file share notifications
  useEffect(() => {
    const handleFileShared = (event: CustomEvent) => {
      console.log("[Dashboard] 📨 File shared event received:", event.detail);
      const notification = event.detail;

      // Show toast notification
      showToast(
        `${notification.sharedByUsername} shared "${notification.fileName}" with you (${notification.permission} access)`,
        "info",
      );

      // Refresh the file list to show the new shared file
      if (projectId) {
        console.log("[Dashboard] Refreshing data after file share...");
        setTimeout(() => {
          fetchData(projectId, false);
        }, 500);
      }
    };

    window.addEventListener("fileShared", handleFileShared as EventListener);
    console.log("[Dashboard] 🎧 Registered listener for file share events");

    return () => {
      window.removeEventListener("fileShared", handleFileShared as EventListener);
    };
  }, [projectId]); // Removed fetchData, showToast to prevent infinite loop

  // Handle reconnection after WebSocket disconnect - refresh data to sync
  useEffect(() => {
    const handleReconnection = (event: Event) => {
      console.log("[Dashboard] 🔄 Collaboration reconnected, refreshing data...");
      if (projectId) {
        showNotification("Reconnected! Refreshing data...", "info");
        // Give server a moment to be ready
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
    // Request notification permission for web browsers
    // (toast subscription is handled in useDashboardInit to avoid double-firing)
    if (typeof window !== "undefined" && !window.vscode) {
      notificationService.requestPermission();
    }
  }, []);

  useEffect(() => {
    // Load previously installed plugins from localStorage
    const loadInstalledPlugins = async () => {
      try {
        pluginLoader.loadFromStorage();
        // Protégé parity: ship SPARQL, Reasoner, Graph, SWRL, etc. as built-in tabs (not marketplace-only).
        pluginLoader.ensureDefaultBuiltInPlugins();
        const installed = pluginLoader.getInstalledPlugins();

        // Update state with installed plugin IDs
        const pluginIds = installed.map((p) => p.id);
        setInstalledPlugins(new Set(pluginIds));

        // Map plugin IDs to tab IDs and show tabs for installed plugins
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

        // Auto-load installed plugins in PARALLEL for better performance
        const loadPluginPromises = installed.map(async (plugin) => {
          try {
            // Set loading state
            setPluginLoadingStates((prev) => ({ ...prev, [plugin.id]: { loading: true, error: null } }));

            await pluginLoader.loadPlugin(plugin.id);
            console.log(`[Dashboard] Auto-loaded plugin: ${plugin.id}`);

            // Clear loading state on success
            setPluginLoadingStates((prev) => ({ ...prev, [plugin.id]: { loading: false, error: null } }));
            // Force re-render
            setInstalledPlugins((prev) => new Set([...prev]));
          } catch (error) {
            console.warn(`[Dashboard] Failed to auto-load plugin ${plugin.id}:`, error);
            // Set error state
            setPluginLoadingStates((prev) => ({
              ...prev,
              [plugin.id]: { loading: false, error: error instanceof Error ? error.message : "Failed to load plugin" },
            }));
          }
        });

        // Wait for all plugins to load in parallel
        await Promise.all(loadPluginPromises);
        console.log(`[Dashboard] All plugins loaded in parallel`);
      } catch (error) {
        console.error("[Dashboard] Failed to load installed plugins:", error);
      }
    };

    loadInstalledPlugins();
  }, [projectId]);

  // #endregion

  // #region Event Handlers

  const toggleNode = useCallback(
    async (nodeId: string) => {
      if (expandedNodes.includes(nodeId)) {
        setExpandedNodes((prev) => prev.filter((id) => id !== nodeId));
      } else {
        const findNode = (nodes: TreeNode[], id: string): TreeNode | null => {
          for (const node of nodes) {
            if (node.id === id) return node;
            if (node.children) {
              const found = findNode(node.children, id);
              if (found) return found;
            }
          }
          return null;
        };
        // Mirror the inferred-class fallback used by the rendered tree (sourceData):
        // while inferredClassHierarchy is still loading the UI shows reasonerResults,
        // so toggleNode must search that same source or it can't find the node the
        // user clicked during the initial-load window.
        const inferredClasses: TreeNode[] =
          inferredClassHierarchy.length > 0
            ? inferredClassHierarchy
            : Array.isArray(reasonerResults?.classHierarchyTree)
              ? reasonerResults.classHierarchyTree
              : Array.isArray(reasonerResults?.classHierarchy)
                ? reasonerResults.classHierarchy
                : [];
        const currentHierarchy =
          mainTab === "IndividualsByClass"
            ? hierarchyViewModes.Classes === "inferred"
              ? inferredClasses
              : classHierarchy
            : entitiesTab === "Classes"
            ? currentHierarchyViewMode === "inferred"
              ? inferredClasses
              : classHierarchy
            : entitiesTab === "ObjectProperties"
              ? hierarchyViewModes.ObjectProperties === "inferred"
                ? inferredObjectPropertyHierarchy
                : objectPropertyHierarchy
              : hierarchyViewModes.DataProperties === "inferred"
                ? inferredDataPropertyHierarchy
                : dataPropertyHierarchy;

        // Primary search in the tab's own hierarchy; fall back to classHierarchy so
        // that domain/range/types dialogs (open while on a non-Classes tab) can still
        // expand class nodes.
        let node = findNode(currentHierarchy as TreeNode[], nodeId);
        const isClassNodeFromDialog = !node && currentHierarchy !== classHierarchy
          ? findNode(classHierarchy, nodeId)
          : null;

        setExpandedNodes((prev) => {
          if (prev.includes(nodeId)) return prev; // prevent duplicates → stops repeated children fetches
          return [...prev, nodeId];
        });

        // Load class children when a class node is expanded from a dialog on a non-Classes tab
        if (isClassNodeFromDialog) {
          const classNode = isClassNodeFromDialog;
          if (classNode.hasChildren && (!classNode.children || classNode.children.length === 0)) {
            setLoadingNodes((prev) => new Set([...prev, nodeId]));
            try {
              await loadChildren(nodeId);
            } catch (err: any) {
              console.warn(`[toggleNode] Failed to load class children for ${nodeId}:`, err);
            } finally {
              setLoadingNodes((prev) => { const n = new Set(prev); n.delete(nodeId); return n; });
            }
          }
        } else if (node && node.hasChildren && (!node.children || node.children.length === 0)) {
          if (entitiesTab === "Classes" || mainTab === "IndividualsByClass") {
            setLoadingNodes((prev) => new Set([...prev, nodeId]));
            // Spinner timeout: stop the spinner after 30s but keep the node expanded.
            // loadChildren continues in the background — children appear when the API
            // responds (Spring @Cacheable makes retries fast after the first warm-up).
            const spinnerTimeout = setTimeout(() => {
              setLoadingNodes((prev) => { const n = new Set(prev); n.delete(nodeId); return n; });
            }, 30000);
            try {
              const shouldLoadInferredClassChildren =
                mainTab === "IndividualsByClass"
                  ? hierarchyViewModes.Classes === "inferred"
                  : currentHierarchyViewMode === "inferred";
              if (shouldLoadInferredClassChildren) {
                await loadInferredChildren(nodeId);
              } else {
                await loadChildren(nodeId);
              }
            } catch (err: any) {
              console.warn(`[toggleNode] Failed to load children for ${nodeId}:`, err);
            } finally {
              clearTimeout(spinnerTimeout);
              setLoadingNodes((prev) => { const n = new Set(prev); n.delete(nodeId); return n; });
            }
          }
        }
      }
    },
    [
      expandedNodes,
      classHierarchy,
      inferredClassHierarchy,
      reasonerResults,
      objectPropertyHierarchy,
      dataPropertyHierarchy,
      inferredObjectPropertyHierarchy,
      inferredDataPropertyHierarchy,
      hierarchyViewModes,
      loadChildren,
      loadInferredChildren,
      entitiesTab,
      currentHierarchyViewMode,
      mainTab,
    ],
  );

  // Expose a safe global for bundles/minified code paths that still reference toggleNode
  useEffect(() => {
    (window as any).toggleNode = toggleNode;
    return () => {
      if ((window as any).toggleNode === toggleNode) {
        delete (window as any).toggleNode;
      }
    };
  }, [toggleNode]);

  // Load next batch of top-level classes when owl:Thing children are truncated
  const handleLoadMoreTopLevel = useCallback(async () => {
    if (isLoadingMoreTopLevel || !projectId) return;
    setIsLoadingMoreTopLevel(true);
    try {
      const encoded = encodeURIComponent(projectId);
      const currentLoaded = classHierarchy[0]?.children?.filter(
        (c) => c.id !== "__load_more_top_level__"
      ).length ?? 0;
      const loadMoreUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      // See hierarchyUserId/draftScopeParam comment above — userId isn't a scope signal.
      const loadMoreDraftScopeParam = isDraftScopeActive() ? "&draft=true" : "";
      const res: any = await apiClient.get(
        `/api/ontology/classes/top-level/${encoded}?limit=5000&offset=${currentLoaded}&userId=${encodeURIComponent(loadMoreUserId)}${loadMoreDraftScopeParam}`
      );
      const data = res?.data ?? res;
      const newClasses: TreeNode[] = (Array.isArray(data?.classes) ? data.classes : []).map(
        (c: any) => ({ ...c, children: [], hasChildren: c.hasChildren !== false, annotations: c.annotations || {} })
      );
      const stillTruncated = !!(data?.truncated);
      setTopLevelTruncated(stillTruncated);
      setClassHierarchy((prev) => {
        if (!prev.length || prev[0].id !== "http://www.w3.org/2002/07/owl#Thing") return prev;
        const owlThing = prev[0];
        const existingChildren = (owlThing.children || []).filter(
          (c) => c.id !== "__load_more_top_level__"
        );
        const merged = [...existingChildren, ...newClasses];
        if (stillTruncated) {
          merged.push({
            id: "__load_more_top_level__",
            label: "Load more classes…",
            children: [],
            hasChildren: false,
            annotations: {},
          });
        }
        return [{ ...owlThing, children: merged }];
      });
    } catch (e) {
      console.error("[Dashboard] loadMoreTopLevel failed:", e);
    } finally {
      setIsLoadingMoreTopLevel(false);
    }
  }, [isLoadingMoreTopLevel, projectId, classHierarchy, user]);

  // Update draft count
  const updateDraftCount = useCallback(async () => {
    if (!projectId) return;
    try {
      const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      console.log("[Dashboard] Updating draft count for project:", projectId, "user:", effectiveUserId);
      const stats = await draftTrackingService.getDraftStats(projectId, effectiveUserId);
      console.log("[Dashboard] Draft stats received:", stats);
      setDraftCount(stats.unappliedDrafts);
      setHasUnsavedChanges(stats.unappliedDrafts > 0);
    } catch (error) {
      console.error("[Dashboard] Failed to update draft count:", error);
      // Don't show error notification - just log it
      // The user can still work, we'll try again later
    }
  }, [projectId, user?.userId, user?.email]);

  // Silently refresh axiom/entity counts from backend after mutations
  const silentRefreshMetadata = useCallback(async () => {
    if (!projectId) return;
    try {
      const metaUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      // See hierarchyUserId/draftScopeParam comment above — userId isn't a scope signal.
      const metaDraftScopeParam = isDraftScopeActive() ? "&draft=true" : "";
      const res = await apiClient.get<any>(`/api/ontology/metadata/${encodeProjectId(projectId)}?userId=${encodeURIComponent(metaUserId)}${metaDraftScopeParam}`);
      const data = res?.data || res;
      if (data) {
        setMetadata((prev) => prev ? {
          ...prev,
          ...data,
          // Flatten counts from nested structure if present
          classCount: data.classCount || data.counts?.classes || prev.classCount,
          objectPropertyCount: data.objectPropertyCount || data.counts?.objectProperties || prev.objectPropertyCount,
          dataPropertyCount: data.dataPropertyCount || data.counts?.dataProperties || prev.dataPropertyCount,
          individualCount: data.individualCount || data.counts?.individuals || prev.individualCount,
          annotationPropertyCount: data.annotationPropertyCount || data.counts?.annotationProperties || prev.annotationPropertyCount,
        } : prev);
      }
    } catch (err) {
      console.debug("[Dashboard] Silent metadata refresh failed:", err);
    }
  }, [projectId, user]);

  // Mark as unsaved (called after mutations)
  const markAsUnsaved = useCallback(() => {
    console.log("[DEBUG] markAsUnsaved called");
    codeViewDirtyRef.current = true;
    if (!isLiveWriteMode()) {
      setHasUnsavedChanges(true);
      // Wait 1.5 s before polling draft count — gives the backend time to persist the draft record
      setTimeout(() => updateDraftCount(), 1500);
    }
    // Debounced silent stats refresh (1.5s after last mutation)
    if (metadataRefreshTimerRef.current) clearTimeout(metadataRefreshTimerRef.current);
    metadataRefreshTimerRef.current = setTimeout(() => silentRefreshMetadata(), 1500);
    // Debounced conflict check (3s after last mutation, private mode only)
    if (conflictCheckTimerRef.current) clearTimeout(conflictCheckTimerRef.current);
    setPublishConflictStatus('checking');
    conflictCheckTimerRef.current = setTimeout(async () => {
      if (!projectId) { setPublishConflictStatus('idle'); return; }
      const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
      try {
        const preview = await draftTrackingService.getPublishPreview(projectId, effectiveUserId);
        const hasConflicts = Array.isArray((preview as any).conflicts) && (preview as any).conflicts.length > 0;
        setPublishConflictStatus(hasConflicts ? 'conflict' : 'clean');
      } catch {
        setPublishConflictStatus('idle');
      }
    }, 3000);

    // Auto-sync reasoner if enabled
    if (isReasonerSynced && isReasonerRunning && projectId) {
      console.log("[DEBUG] Auto-sync: Re-running reasoner after ontology change");
      // Debounce reasoner re-run to avoid too many calls
      setTimeout(async () => {
        try {
          const reasonerType = normalizeReasonerType(selectedReasoner);
          const results = await fetchReasonerBundle(reasonerType);
          setReasonerResults(results);
          console.log("[DEBUG] Auto-sync: Reasoner updated successfully");
        } catch (error) {
          console.error("[DEBUG] Auto-sync: Reasoner update failed", error);
        }
      }, 2000); // Wait 2 seconds after last change
    }
  }, [updateDraftCount, isReasonerSynced, isReasonerRunning, projectId, selectedReasoner, fetchReasonerBundle, user]);

  // Save changes to backend (publishes only this user's draft to main graph)
  const handleSave = useCallback(async (options?: {
    force?: boolean;
    merge?: boolean;
    resolutions?: Record<string, { action: string }>;
  }) => {
    const forcePublish = options?.force ?? false;
    const mergePublish = options?.merge ?? false;
    const mergeResolutions = options?.resolutions;
    console.log("[DEBUG] handleSave called", { forcePublish, mergePublish });
    if (!projectId || isSaving) return;

    // Desktop: explicit Save promotes the on-disk draft (draft/ontology.draft.owl)
    // to ontology.current.owl and deletes the draft folder — Protégé-style save.
    if (isDesktop()) {
      setIsSaving(true);
      try {
        const resp: any = await apiClient.post(`/api/desktop/save/${encodeURIComponent(projectId)}`);
        const d = resp?.data || resp;
        setHasUnsavedChanges(false);
        notificationService.success(
          "Saved",
          d?.saved ? "Your changes were saved." : "Nothing to save — already up to date.",
        );
      } catch (err) {
        notificationService.error("Save Failed", err instanceof Error ? err.message : "Could not save changes.");
      } finally {
        setIsSaving(false);
      }
      return;
    }

    const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;

    const performSave = async (force: boolean, merge: boolean, resolutions?: Record<string, { action: string }>) => {
      setIsSaving(true);
      console.log("[Dashboard] 💾 Saving changes to backend...");

      syncService.notifyLocalSave(projectId);

      const startTime = Date.now();
      const params = new URLSearchParams({
        userId: effectiveUserId,
        username: user?.username || "Anonymous",
      });
      if (force) params.set("force", "true");
      if (merge) params.set("merge", "true");
      const saveUrl = `/api/ontology/save/${projectId}?${params.toString()}`;
      console.log("[Dashboard] 📤 Save URL:", saveUrl);
      const response = await apiClient.post(saveUrl, merge && resolutions ? resolutions : undefined);
      const duration = Date.now() - startTime;

      console.log(`[Dashboard] Save response received after ${duration}ms:`, response);

      const data = response.data || response;

      if (data && data.success) {
        setHasUnsavedChanges(false);
        setDraftCount(0);
        setPublishConflictStatus('idle');

        notificationService.success(
          "Saved to Database",
          `${data.appliedDrafts || 0} change${(data.appliedDrafts || 0) !== 1 ? "s" : ""} saved${merge ? " with three-way merge" : ""}.`,
        );

        await fetchData(projectId, false, undefined, true);
        collaborationPanelRef.current?.refreshChanges();
      } else {
        const errorMsg = (data && data.error) || "Save failed - no response from server";
        throw new Error(errorMsg);
      }
    };

    try {
      await performSave(forcePublish, mergePublish, mergeResolutions);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && error.data) {
        const conflictType = error.data.conflictType as string | undefined;
        const conflicts = (error.data.conflicts as Array<Record<string, string>>) || [];
        const mainChanged = Boolean(error.data.mainChangedSinceDraft);

        let message = "";
        if (conflictType === "IRI_OVERLAP" && conflicts.length > 0) {
          message = `${conflicts.length} ${conflicts.length === 1 ? "entity was" : "entities were"} changed by someone else since you started your draft. Review each conflict below and choose how to resolve it.`;
        } else if (conflictType === "MAIN_CHANGED" || mainChanged) {
          message = "The shared ontology was updated while you were editing. Review the changes below and choose how to resolve each conflict.";
        } else {
          message = (error.data.message as string | undefined) || (error.data.error as string | undefined) || error.message || "Your draft conflicts with changes on the shared ontology.";
        }

        setConflictResolutions({});
        setPublishConflictDialog({
          isOpen: true,
          title: conflictType === "IRI_OVERLAP" ? "Merge conflicts" : "Shared ontology changed",
          message,
          conflicts: conflicts.map((c) => ({
            entityIRI: c.entityIRI || "",
            entityLabel: c.entityLabel,
            changedBy: c.changedBy,
            yourAxioms: c.yourAxioms,
            mainAxioms: c.mainAxioms,
          })),
          onForce: () => {
            setPublishConflictDialog((prev) => ({ ...prev, isOpen: false }));
            void handleSave({ force: true });
          },
        });
        return;
      }

      console.error("[Dashboard] Save failed with error:", error);
      const errorMessage = error instanceof Error ? error.message : "Could not save changes. Please try again.";
      notificationService.error("Save Failed", errorMessage);
    } finally {
      setIsSaving(false);
    }
  }, [projectId, isSaving, user?.userId, user?.username]);

  // Expose the current project to the Electron main process so its exit-time
  // unsaved-draft check (main.js close handler → /api/desktop/draft-status)
  // knows which project to query.
  useEffect(() => {
    if (isDesktop()) {
      (window as any).__ONTOCODE_PROJECT_ID__ = projectId || null;
    }
  }, [projectId]);

  // Switch to a different file (with unsaved changes check)
  const handleSwitchFile = useCallback(
    (newProjectId: string) => {
      const switchFile = async () => {
        console.log("[Dashboard] 🔄 Switching to file:", newProjectId);
        console.log("[Dashboard] 🧹 Clearing current state for:", projectId);

        // Clear all current state (including metadata so old counts don't persist)
        setClassHierarchy([]);
        setObjectProperties([]);
        setDataProperties([]);
        setAnnotationProperties([]);
        setIndividuals([]);
        setDatatypes([]);
        setMetadata(null);
        setSelectedItem(null);
        setSearchQuery("");
        setActiveFileId(null);
        setActiveFileName(newProjectId); // Use new project ID as file name if no explicit file ID Provided
        setHasUnsavedChanges(false);
        setDraftCount(0);

        // Update projectId so the Dashboard knows which file is active
        setProjectId(newProjectId);
        hasUserSelectedFileRef.current = true;
        setHasUserSelectedFile(true);

        // Cancel any in-flight HTTP requests for the previous file before starting the new load
        if (fetchAbortControllerRef.current) {
          fetchAbortControllerRef.current.abort();
          fetchAbortControllerRef.current = null;
        }

        if (window.vscode) {
          // Show loading dialog immediately before the round-trip to the extension
          setShowLoadingChoice(true);
          setLoadingProjectName(newProjectId);
          window.vscode.postMessage({
            type: "fileLoaded",
            projectId: newProjectId,
          });
        } else {
          // Browser mode: show LoadingDialog and load data directly from GraphDB
          console.log("[Dashboard] 🌐 Browser mode - loading file via fetchData:", newProjectId);
          setIsInitialLoading(true);
          fetchData(newProjectId, true);
        }

        console.log("[Dashboard] ✅ State cleared, loading new file:", newProjectId);
      };

      // If no unsaved changes or draft count is 0, switch directly
      if (!hasUnsavedChanges || draftCount === 0) {
        console.log("[Dashboard] No unsaved changes, switching directly");
        switchFile();
        return;
      }

      // Show unsaved-changes warning
      setUnsavedChangesDialog({
        isOpen: true,
        onLeave: () => {
          setUnsavedChangesDialog((prev) => ({ ...prev, isOpen: false }));
          switchFile();
        },
      });
    },
    [
      hasUnsavedChanges,
      draftCount,
      projectId,
      fetchData,
      setProjectId,
      setIsInitialLoading,
      setShowLoadingChoice,
      setLoadingProjectName,
    ],
  );

  // Back to projects (with unsaved changes check)
  const handleBackToProjects = useCallback(() => {
    if (!onBackToProjects) return;

    // If no unsaved changes or draft count is 0, navigate directly
    if (!hasUnsavedChanges || draftCount === 0) {
      onBackToProjects();
      return;
    }

    // Show unsaved-changes warning
    setUnsavedChangesDialog({
      isOpen: true,
      onLeave: () => {
        setUnsavedChangesDialog((prev) => ({ ...prev, isOpen: false }));
        onBackToProjects();
      },
    });
  }, [onBackToProjects, hasUnsavedChanges, draftCount]);

  // Intercept browser/VS Code back navigation when there are unsaved changes
  useEffect(() => {
    if (!hasUnsavedChanges || draftCount === 0) return;

    const handlePopState = () => {
      // Push state back to cancel the navigation
      window.history.pushState(null, "", window.location.href);
      // Show the unsaved changes warning
      setUnsavedChangesDialog({
        isOpen: true,
        onLeave: () => {
          setUnsavedChangesDialog((prev) => ({ ...prev, isOpen: false }));
          // Actually go back now
          window.history.go(-2);
        },
      });
    };

    // Push an extra history entry so we can intercept the back
    window.history.pushState(null, "", window.location.href);
    window.addEventListener("popstate", handlePopState);

    return () => {
      window.removeEventListener("popstate", handlePopState);
    };
  }, [hasUnsavedChanges, draftCount]);

  // Load a file from the project (admin flow) - fetch content and upload to ontology editor
  const handleLoadProjectFile = useCallback(
    async (fileId: string, fileName: string) => {
      if (!initialProjectId) {
        console.error("[Dashboard] Cannot load project file without parent project ID");
        return;
      }

      // Lightweight, cached (24h) plugin update check piggy-backed on file-open.
      runPluginUpdateCheck();

      // If this file is already loaded, skip re-fetching all data
      const ontologyProjectIdCheck = `${initialProjectId}--${fileId}`;
      if (projectId === ontologyProjectIdCheck && classHierarchy.length > 0) {
        console.log("[Dashboard] ✅ File already loaded, skipping re-fetch:", fileId);
        setActiveFileId(fileId);
        setActiveFileName(fileName);
        if (onFileSelected && isMountedRef.current) onFileSelected(fileId, fileName);
        return;
      }

      try {
        const loadFilePerfStart = Date.now();
        console.log(
          `[Dashboard] [PERF] ⏱️ handleLoadProjectFile started at ${new Date().toISOString()} for file: ${fileName} (${fileId})`,
        );
        console.log("[Dashboard] 📂 Loading file from project:", fileId, fileName);

        // Mark refs so the auto-load useEffect won't double-fire when
        // onFileSelected updates the selectedFileId prop from the parent.
        lastLoadedFileRef.current = fileId;
        fileLoadingRef.current = true;

        const ontologyProjectId = `${initialProjectId}--${fileId}`;

        setActiveFileId(fileId);
        setActiveFileName(fileName);
        if (onFileSelected && isMountedRef.current) onFileSelected(fileId, fileName);

        // File-scoped id before slow graphdb/warm-up so effects never query parent-only projectId.
        setProjectId(ontologyProjectId);
        setLoadingProjectName(fileName);
        setIsHierarchyLoading(true);
        setIsMetadataLoading(true);
        setIsPropertiesLoading(true);
        setIsIndividualsLoading(true);
        setIsAnnotationPropertiesLoading(true);
        setIsDatatypesLoading(true);
        setLoadingStatusMessage(`Loading ${fileName}…`);

        // ⚡ FAST PATH: Check if data already exists in GraphDB — skip MongoDB fetch + re-upload
        try {
          const graphCheck = await apiClient.get<{
            success: boolean;
            exists: boolean;
            graphSize?: number;
          }>(
            `/api/ontology/${encodeProjectId(ontologyProjectId)}/graphdb/check?fileName=${encodeURIComponent(fileName)}&fileId=${encodeURIComponent(fileId)}`,
          );

          if (graphCheck?.exists && (graphCheck.graphSize ?? 0) > 0) {
            console.log(
              `[Dashboard] [PERF] GraphDB cache check: ${Date.now() - loadFilePerfStart}ms (HIT: ${graphCheck.graphSize} triples)`,
            );
            console.log(`[Dashboard] ⚡ File already in GraphDB (${graphCheck.graphSize} triples), loading directly`);
            notificationService.info("Loading", `Loading ${fileName} from cache...`);
            await fetchData(ontologyProjectId, false, initialProjectId, true);
            setShowLoadingChoice(false);
            setShowQueueStatus(false);
            setQueuePosition(undefined);
            setTotalInQueue(undefined);
            setEstimatedWaitTimeMs(undefined);
            setShowProjectSelector(false);
            setIsInitialLoading(false);
            return;
          }
        } catch (checkErr) {
          console.warn("[Dashboard] GraphDB check failed, falling back to full upload:", checkErr);
          console.log(`[Dashboard] [PERF] GraphDB cache check: ${Date.now() - loadFilePerfStart}ms (MISS/ERROR)`);
        }

        // Viewers can only read data already loaded by the project owner — never import.
        if (isViewOnlyMember) {
          notificationService.error("Not Available", "This file hasn't been loaded yet. Ask the project owner to open it first.");
          setIsInitialLoading(false);
          setIsHierarchyLoading(false);
          setIsMetadataLoading(false);
          setIsPropertiesLoading(false);
          setIsIndividualsLoading(false);
          setIsAnnotationPropertiesLoading(false);
          setIsDatatypesLoading(false);
          return;
        }

        notificationService.info("Loading File", `Loading ${fileName}...`);

        // Reset all entity state before loading new file
        console.log("[Dashboard] 🔄 Resetting state for new file...");
        setClassHierarchy([]);
        setObjectProperties([]);
        setDataProperties([]);
        setAnnotationProperties([]);
        setIndividuals([]);
        setDatatypes([]);
        setSelectedItem(null);
        setExpandedNodes(["http://www.w3.org/2002/07/owl#Thing"]);
        setSearchQuery("");
        setHasUnsavedChanges(false);
        setDraftCount(0);

        // Server-side import: editor reads file directly from MongoDB GridFS.
        // Eliminates the browser download+re-upload roundtrip that caused 10+ min
        // timeouts for large files (e.g. 224 MB go-plus.owl).
        const resolvedEmail = resolveUserEmail();

        // Set project state before dispatching so IMPORT_COMPLETED targets the right project.
        setProjectId(ontologyProjectId);
        pendingImportProjectIdRef.current = ontologyProjectId;
        setPendingImportProjectId(ontologyProjectId);

        const importResult = await apiClient.post<{
          success: boolean;
          projectId: string;
          filename: string;
          message: string;
        }>(
          `/api/ontology/upload-by-file-ref/${encodeProjectId(ontologyProjectId)}`,
          null,
          {
            params: {
              fileId,
              parentProjectId: initialProjectId,
              ownerEmail: resolvedEmail || "",
              workspaceId: user?.workspaceId || "",
              importMode,
              partition: partitionStrategy,
              action: "replace",
            },
          },
        );

        if (!importResult?.success) {
          throw new Error(importResult?.message || "Failed to trigger server-side import");
        }

        console.log(
          `[Dashboard] [PERF] Server-side import dispatched: ${Date.now() - loadFilePerfStart}ms`,
        );

        // Desktop fast-path: backend skipped re-import (Fuseki already has data).
        // No WebSocket IMPORT_COMPLETED will fire — fetch data directly instead.
        if ((importResult as any)?.status === "ALREADY_LOADED") {
          console.log("[Dashboard] ✅ Desktop cache hit (ALREADY_LOADED) — fetching data directly");
          pendingImportProjectIdRef.current = null;
          setPendingImportProjectId(null);
          setInImportQueue(false);
          setIsInitialLoading(false);
          await fetchData(ontologyProjectId, false, initialProjectId, true);
          return;
        }

        console.log("[Dashboard] ✅ Server-side import triggered via uploadByFileRef");
        console.log("[Dashboard] Pending import project:", ontologyProjectId);

        // The fileReady message will trigger fetchData via the message handler
        setIsExpectingFileReady(true);
        notificationService.info("Loading", `Loading ${fileName}...`);
      } catch (error: any) {
        console.error("[Dashboard] ❌ Failed to load project file:", error);
        notificationService.error("Load Failed", error?.message || "Failed to load file");
        setShowLoadingChoice(false);
        setIsExpectingFileReady(false);
      } finally {
        // Allow new loads after a brief delay to prevent rapid re-triggers
        setTimeout(() => {
          fileLoadingRef.current = false;
        }, 1000);
      }
    },
    [initialProjectId, resolveUserEmail, user?.workspaceId, importMode, partitionStrategy, fetchData],
  );

  // Create Property from Class Expression Dialog
  const handleCreatePropertyFromDialog = useCallback(() => {
    setEntitiesTab("ObjectProperties");
    setSelectedItem(null);
    setAddPropertyType("root");
    setPropertyParentLabel("owl:topObjectProperty");
    setAddPropertyDialogOpen(true);
    setIsClassExpressionDialogOpen(false);
  }, []);

  // Load draft from localStorage if exists
  useEffect(() => {
    if (projectId) {
      const draftKey = `draft_${projectId}`;
      const savedDraft = localStorage.getItem(draftKey);
      if (savedDraft) {
        try {
          const draft = JSON.parse(savedDraft);
          const age = Date.now() - draft.timestamp;
          // Only restore drafts less than 24 hours old
          if (age < 24 * 60 * 60 * 1000) {
            console.log("[Dashboard] Found draft, restoring...", draft);
            // Could show a dialog asking if user wants to restore draft
            setHasUnsavedChanges(true);
          } else {
            localStorage.removeItem(draftKey);
          }
        } catch (e) {
          console.error("[Dashboard] Failed to parse draft:", e);
          localStorage.removeItem(draftKey);
        }
      }
    }
  }, [projectId]);

  // Keyboard shortcut for Save (Ctrl+S)

  const handleAddAnnotation = useCallback(async () => {
    if (!projectId) return;
    setAddAnnotationDialogOpen(true);
  }, [projectId]);

  const updateActiveOntologyAnnotations = useCallback((updater: (annotations: any[]) => any[]) => {
    setMetadata((prev) => {
      if (!prev) {
        return prev;
      }

      const currentAnnotations = Array.isArray((prev as any).annotations) ? [...(prev as any).annotations] : [];

      const nextAnnotations = updater(currentAnnotations);
      return {
        ...(prev as any),
        annotations: nextAnnotations,
      } as OntologyMetadata;
    });
  }, []);

  const handleRefreshAnnotationProperties = useCallback(async () => {
    if (!projectId) return;
    // On desktop, a mutation can leave the OWLAPI in-memory model briefly evicted/re-warming
    // (version-mismatch check in ensureFreshForRead) — a plain GET right after create/delete can
    // land on that transient "warming" response (data: []), which would otherwise wipe the whole
    // list. Retry instead of trusting the first empty response.
    const res = await getOntologyListWithRetry<any>(
      withDraftScope(`/api/ontology/annotation-properties/${encodeProjectId(projectId)}`),
    );
    if (res === null) {
      console.warn("[Dashboard] Annotation properties still warming after retries — keeping current list");
      return;
    }
    const rawProperties = Array.isArray(res?.data)
      ? res.data
      : Array.isArray(res?.annotationProperties)
        ? res.annotationProperties
        : [];
    const merged = mergeAnnotationProperties(rawProperties.map(mapAnnotationProperty));
    setAnnotationProperties(merged);
    setAnnotationPropertyHierarchy(buildAnnotationPropertyHierarchy(merged));
  }, [projectId]);

  const handleDialogCreateAnnotationProperty = useCallback(
    async (iri: string, label: string) => {
      if (!projectId) return;
      await ontologyMutationService.createAnnotationProperty(
        projectId,
        iri,
        label,
        user?.email,
        user?.username,
      );
      await handleRefreshAnnotationProperties();
    },
    [projectId, user?.email, user?.username, handleRefreshAnnotationProperties],
  );

  const handleAnnotationDialogAdd = useCallback(
    async (propertyIri: string, value: string, datatype?: string, lang?: string) => {
      if (!projectId) return;

      const isEntityAnnotation = mainTab !== "ActiveOntology" && !!selectedItem;

      try {
        if (isEntityAnnotation && selectedItem) {
          // Mark unsaved optimistically so the indicator appears immediately on confirm
          markAsUnsaved();

          // Entity annotation
          await ontologyMutationService.addAnnotation(
            projectId,
            selectedItem.id,
            propertyIri,
            value,
            user?.email || "anonymous",
            user?.username || "Anonymous",
            lang,
            datatype,
          );

          // Update local state
          const updatedAnnotations = { ...selectedItem.annotations, [propertyIri]: value };
          const updatedItem: SelectableItem = { ...selectedItem, annotations: updatedAnnotations };
          // If rdfs:label was added, also update the display label
          if (propertyIri === "http://www.w3.org/2000/01/rdf-schema#label" || propertyIri === "rdfs:label") {
            updatedItem.label = value;
          }
          updateItemInState(updatedItem);
        } else {
          // Ontology annotation
          updateActiveOntologyAnnotations((current) => [
            ...current,
            {
              property: propertyIri,
              value,
              language: lang || undefined,
              datatype,
            },
          ]);

          await apiClient.post(`/api/ontology/metadata/${projectId}/annotations`, {
            propertyIri,
            value,
            language: lang,
            datatype,
            ...draftBodyFields(),
          });

          await refreshOntologyAnnotations();
        }

        showNotification("Annotation added successfully!", "info");
      } catch (error) {
        console.error("Failed to add annotation:", error);
        if (!isEntityAnnotation) {
          await refreshOntologyAnnotations();
        }
        showNotification("Failed to add annotation. See console for details.", "error");
      }
    },
    [
      selectedItem,
      updateItemInState,
      projectId,
      user,
      mainTab,
      updateActiveOntologyAnnotations,
      refreshOntologyAnnotations,
    ],
  );

  const handleEditAnnotation = useCallback(
    async (propertyIri: string, currentValue: string) => {
      if (!projectId) return;

      // Open dialog with current value pre-filled
      setEditAnnotationData({
        propertyIri,
        originalPropertyIri: propertyIri,
        currentValue,
        entityId: mainTab !== "ActiveOntology" && selectedItem ? selectedItem.id : "ONTOLOGY",
      });
      setEditAnnotationDialogOpen(true);
    },
    [selectedItem, projectId, mainTab],
  );

  const handleAnnotationDialogEdit = useCallback(
    async (
      propertyIri: string,
      oldValue: string,
      newValue: string,
      datatype?: string,
      lang?: string,
      originalPropertyIri?: string,
      oldLang?: string,
      oldDatatype?: string,
    ) => {
      console.log("[Dashboard] handleAnnotationDialogEdit called with:", propertyIri, oldValue, newValue);
      if (!projectId) return;

      const isEntityAnnotation = mainTab !== "ActiveOntology" && !!selectedItem;
      const targetPropertyIri = originalPropertyIri || propertyIri;

      try {
        if (isEntityAnnotation && selectedItem) {
          // Entity annotation
          await ontologyMutationService.updateAnnotation(
            projectId,
            selectedItem.id,
            propertyIri,
            newValue,
            user?.email || "anonymous",
            user?.username || "Anonymous",
            oldValue,
            lang,
            datatype,
          );

          // Update local state
          const updatedAnnotations = { ...selectedItem.annotations, [propertyIri]: newValue };
          const updatedItem: SelectableItem = { ...selectedItem, annotations: updatedAnnotations };
          // If rdfs:label was edited, also update the display label
          if (propertyIri === "http://www.w3.org/2000/01/rdf-schema#label" || propertyIri === "rdfs:label") {
            updatedItem.label = newValue;
          }
          updateItemInState(updatedItem);
          markAsUnsaved();
        } else {
          // Ontology annotation
          updateActiveOntologyAnnotations((current) => {
            const matchIndex = current.findIndex((annotation: any) => {
              if (!annotation) {
                return false;
              }

              const matchesProperty = annotation.property === targetPropertyIri;
              const existingValue = annotation.value?.toString?.() ?? annotation.value;
              const matchesValue = existingValue === oldValue;
              const matchesLanguage = (annotation.language || "") === (oldLang || "");
              const matchesDatatype = (annotation.datatype || "") === (oldDatatype || "");
              return matchesProperty && matchesValue && matchesLanguage && matchesDatatype;
            });

            const nextValue = {
              ...(matchIndex >= 0 ? current[matchIndex] : {}),
              property: propertyIri,
              value: newValue,
              language: lang || undefined,
              datatype,
            };

            if (matchIndex >= 0) {
              current[matchIndex] = nextValue;
            } else {
              current.push(nextValue);
            }

            return current;
          });

          await apiClient.put(`/api/ontology/metadata/${projectId}/annotations`, {
            propertyIri,
            originalPropertyIri: originalPropertyIri || propertyIri,
            oldValue,
            newValue,
            language: lang,
            datatype,
            ...draftBodyFields(),
          });

          await refreshOntologyAnnotations();
        }

        showNotification("Annotation updated successfully!", "info");
      } catch (error) {
        console.error("Failed to update annotation:", error);
        if (!isEntityAnnotation) {
          await refreshOntologyAnnotations();
        }
        showNotification("Failed to update annotation. See console for details.", "error");
      }
    },
    [
      selectedItem,
      updateItemInState,
      projectId,
      user,
      mainTab,
      updateActiveOntologyAnnotations,
      refreshOntologyAnnotations,
    ],
  );

  const handleSaveOntologyIRIs = useCallback(
    async (ontologyIri: string, versionIri: string) => {
      if (!projectId) return;

      const normalizedOntologyIri = ontologyIri.trim();
      const normalizedVersionIri = versionIri.trim();
      const absoluteIriPattern = /^https?:\/\/.+/i;

      if (!absoluteIriPattern.test(normalizedOntologyIri)) {
        showNotification("Ontology IRI must be an absolute http(s) URL.", "error");
        throw new Error("Invalid ontology IRI");
      }
      if (normalizedVersionIri && !absoluteIriPattern.test(normalizedVersionIri)) {
        showNotification("Version IRI must be an absolute http(s) URL when provided.", "error");
        throw new Error("Invalid version IRI");
      }

      try {
        const response = await apiClient.put<{ success?: boolean; error?: string }>(
          `/api/ontology/metadata/${projectId}/iri`,
          { ontologyIri: normalizedOntologyIri, versionIri: normalizedVersionIri, ...draftBodyFields() },
        );
        if (response?.success === false) {
          throw new Error(response.error || "Failed to update ontology IRIs.");
        }

        setMetadata((prev) => ({
          ...(prev || {}),
          ontologyIRI: normalizedOntologyIri,
          versionIRI: normalizedVersionIri || undefined,
        }));

        const metadataRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}`));
        const metadataData = extractResponseData(metadataRes);
        if (metadataData && typeof metadataData === "object") {
          setMetadata((prev) => ({ ...(prev || {}), ...metadataData }));
        }

        showNotification("Ontology IRIs updated successfully!", "info");
      } catch (error) {
        console.error("Failed to update ontology IRIs:", error);
        showNotification("Failed to update ontology IRIs.", "error");
        throw error;
      }
    },
    [projectId],
  );

  const handleSaveGCI = useCallback(
    async (subClass: string, superClass: string) => {
      if (!projectId) return;
      try {
        const gciActorId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
        const gciDraft = ontologyMutationService.resolveUseDraft();
        if (editGCIData) {
          // Update existing GCI
          await apiClient.put(`/api/ontology/metadata/${projectId}/gci/${editGCIData.index}`, {
            subClass,
            superClass,
            oldValue: editGCIData.value,
            draft: gciDraft,
            userId: gciActorId,
          });
        } else {
          // Add new GCI
          await apiClient.post(`/api/ontology/metadata/${projectId}/gci`, {
            subClass, superClass, draft: gciDraft, userId: gciActorId,
          });
        }

        // Refresh GCIs
        const gciRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}/gci`));
        const gciData = Array.isArray(gciRes?.data)
          ? gciRes.data
          : Array.isArray(gciRes?.axioms)
            ? gciRes.axioms
            : Array.isArray(gciRes)
              ? gciRes
              : [];
        setGeneralClassAxioms(gciData);

        showNotification(editGCIData ? "GCI updated successfully!" : "GCI added successfully!", "info");
        setEditGCIData(null);
      } catch (error) {
        console.error("Failed to save GCI:", error);
        showNotification("Failed to save GCI.", "error");
      }
    },
    [projectId, editGCIData, user],
  );

  const handleDeleteGCI = useCallback(
    async (axiom: any, index: number) => {
      if (!projectId) return;

      setConfirmDialog({
        isOpen: true,
        title: "Delete GCI",
        message: `Are you sure you want to delete this General Class Axiom?`,
        onConfirm: async () => {
          try {
            await apiClient.delete(`/api/ontology/metadata/${projectId}/gci`, {
              value: axiom.id || axiom.value,
              draft: ontologyMutationService.resolveUseDraft(),
              userId: resolveMutationActor(user?.userId || user?.email, user?.username).userId,
            });

            // Refresh GCIs
            const gciRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}/gci`));
            const gciData = Array.isArray(gciRes?.data)
              ? gciRes.data
              : Array.isArray(gciRes?.axioms)
                ? gciRes.axioms
                : Array.isArray(gciRes)
                  ? gciRes
                  : [];
            setGeneralClassAxioms(gciData);

            showNotification("GCI deleted successfully!", "info");
          } catch (error) {
            console.error("Failed to delete GCI:", error);
            showNotification("Failed to delete GCI.", "error");
          }
        },
      });
    },
    [projectId, user],
  );

  const handleDeleteAnnotation = useCallback(
    async (key: string, explicitValue?: string) => {
      if (!selectedItem || !selectedItem.annotations || !projectId) return;

      const raw = selectedItem.annotations[key];
      const value = explicitValue ?? (Array.isArray(raw) ? raw[0] : raw);

      // Show confirm dialog instead of using confirm()
      setConfirmDialog({
        isOpen: true,
        title: "Delete Annotation",
        message: `Are you sure you want to delete this annotation value?`,
        onConfirm: async () => {
          try {
            await ontologyMutationService.deleteAnnotation(
              projectId,
              selectedItem.id,
              key,
              value,
              user?.email || "anonymous",
              user?.username || "Anonymous",
            );

            const remainingAnnotations = { ...selectedItem.annotations } as Record<string, string | string[]>;
            if (Array.isArray(raw)) {
              const nextValues = raw.filter((v) => v !== value);
              if (nextValues.length > 0) {
                remainingAnnotations[key] = nextValues;
              } else {
                delete remainingAnnotations[key];
              }
            } else {
              delete remainingAnnotations[key];
            }
            // If the deleted annotation was rdfs:label, sync the display label too
            let updatedLabel = selectedItem.label;
            if (key === "http://www.w3.org/2000/01/rdf-schema#label") {
              const remaining = remainingAnnotations[key];
              updatedLabel = Array.isArray(remaining) && remaining.length > 0
                ? remaining[0]
                : typeof remaining === "string"
                  ? remaining
                  : selectedItem.id.split(/[#/]/).pop() ?? selectedItem.id;
            }
            const updatedItem = { ...selectedItem, label: updatedLabel, annotations: remainingAnnotations };
            updateItemInState(updatedItem);
            // Sync annotation-mode display cache for the renamed node
            if (key === hierarchyAnnotationPropIri) {
              setHierarchyAnnotationValues((prev) => {
                const next = new Map(prev);
                next.delete(selectedItem.id);
                return next;
              });
              fetchedAnnotationIrisRef.current.delete(selectedItem.id);
            }
            markAsUnsaved();
            showNotification("Annotation deleted successfully!", "info");
          } catch (error) {
            console.error("Failed to delete annotation:", error);
            showNotification("Failed to delete annotation. See console for details.", "error");
          }
        },
      });
    },
    [selectedItem, updateItemInState, projectId, hierarchyAnnotationPropIri],
  );

  // Function to refresh only properties (not classes) to avoid closing dialogs
  const refreshProperties = useCallback(async () => {
    if (!projectId) return;

    try {
      console.log("[refreshProperties] Starting property refresh...");
      if (isDesktop()) {
        await waitForDesktopOwlApiReady(projectId);
      }
      let propertiesRes = await apiClient.get<any>(withDraftScope(`/api/ontology/properties/${projectId}`));
      if (isOwlApiWarmingResponse(propertiesRes)) {
        await waitForDesktopOwlApiReady(projectId);
        propertiesRes = await apiClient.get<any>(withDraftScope(`/api/ontology/properties/${projectId}`));
      }

      const allProps = Array.isArray(propertiesRes?.data)
        ? propertiesRes.data
        : Array.isArray(propertiesRes?.properties)
          ? propertiesRes.properties
          : Array.isArray(propertiesRes)
            ? propertiesRes
            : [];

      console.log("[refreshProperties] Total properties fetched:", allProps.length);

      const opList = allProps.filter((p: any) => p.type === "ObjectProperty");
      console.log("[refreshProperties] Object properties:", opList.length);
      setObjectProperties((prev: Property[]) => {
        const prevMap = new Map(prev.map((p: Property) => [p.id, p]));
        return opList.map((freshProp: Property) => {
          const existing = prevMap.get(freshProp.id) as any;
          // Preserve locally loaded details (incl. draft-mode annotations) so tab switches
          // don't wipe optimistic updates that haven't been flushed to Fuseki yet.
          if (existing?._propertyDetailsLoaded) {
            return { ...existing, superProperties: freshProp.superProperties, label: freshProp.label || existing.label };
          }
          return freshProp;
        });
      });

      console.log("[Dashboard] ✅ Properties refreshed");
      // Build object property hierarchy
      const opMap = new Map<string, TreeNode>();
      opList.forEach((p: any) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topOpNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#topObjectProperty",
        label: "owl:topObjectProperty",
        type: "ObjectProperty",
        children: [],
        hasChildren: false,
        annotations: {},
      };

      opList.forEach((p: any) => {
        const node = opMap.get(p.id)!;
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach((parentId: string) => {
            if (parentId === topOpNode.id) {
              topOpNode.children!.push(node);
              topOpNode.hasChildren = true;
              added = true;
            } else if (opMap.has(parentId)) {
              const parentNode = opMap.get(parentId)!;
              parentNode.children!.push(node);
              parentNode.hasChildren = true;
              added = true;
            }
          });
          if (!added) {
            topOpNode.children!.push(node);
            topOpNode.hasChildren = true;
          }
        } else {
          topOpNode.children!.push(node);
          topOpNode.hasChildren = true;
        }
      });

      console.log(
        "[refreshProperties] Built object property hierarchy with",
        topOpNode.children?.length,
        "top-level properties",
      );
      // Create a new array to ensure React detects the change
      setObjectPropertyHierarchy([{ ...topOpNode, children: [...(topOpNode.children || [])] }]);

      // Build data property hierarchy
      const dpList = allProps.filter((p: any) => p.type === "DatatypeProperty");
      console.log("[refreshProperties] Data properties:", dpList.length);
      setDataProperties((prev: Property[]) => {
        const prevMap = new Map(prev.map((p: Property) => [p.id, p]));
        return dpList.map((freshProp: Property) => {
          const existing = prevMap.get(freshProp.id) as any;
          if (existing?._propertyDetailsLoaded) {
            return { ...existing, superProperties: freshProp.superProperties, label: freshProp.label || existing.label };
          }
          return freshProp;
        });
      });

      const dpMap = new Map<string, TreeNode>();
      dpList.forEach((p: any) => {
        dpMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topDpNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#topDataProperty",
        label: "owl:topDataProperty",
        type: "DatatypeProperty",
        children: [],
        hasChildren: false,
        annotations: {},
      };

      dpList.forEach((p: any) => {
        const node = dpMap.get(p.id)!;
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach((parentId: string) => {
            if (parentId === topDpNode.id) {
              topDpNode.children!.push(node);
              topDpNode.hasChildren = true;
              added = true;
            } else if (dpMap.has(parentId)) {
              const parentNode = dpMap.get(parentId)!;
              parentNode.children!.push(node);
              parentNode.hasChildren = true;
              added = true;
            }
          });
          if (!added) {
            topDpNode.children!.push(node);
            topDpNode.hasChildren = true;
          }
        } else {
          topDpNode.children!.push(node);
          topDpNode.hasChildren = true;
        }
      });

      console.log(
        "[refreshProperties] Built data property hierarchy with",
        topDpNode.children?.length,
        "top-level properties",
      );
      // Create a new array to ensure React detects the change
      setDataPropertyHierarchy([{ ...topDpNode, children: [...(topDpNode.children || [])] }]);
      console.log("[refreshProperties] Property refresh complete");
    } catch (error) {
      console.error("Failed to refresh properties:", error);
    }
  }, [projectId]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "ObjectProperties") return;
    if (hierarchyViewModes.ObjectProperties === "inferred") {
      loadInferredObjectPropertyHierarchy();
    } else {
      refreshProperties();
    }
  }, [
    projectId,
    mainTab,
    entitiesTab,
    hierarchyViewModes.ObjectProperties,
    loadInferredObjectPropertyHierarchy,
    refreshProperties,
  ]);

  useEffect(() => {
    if (!projectId || mainTab !== "Entities" || entitiesTab !== "DataProperties") return;
    if (hierarchyViewModes.DataProperties === "inferred") {
      loadInferredDataPropertyHierarchy();
    } else {
      refreshProperties();
    }
  }, [
    projectId,
    mainTab,
    entitiesTab,
    hierarchyViewModes.DataProperties,
    loadInferredDataPropertyHierarchy,
    refreshProperties,
  ]);

  // Desktop: Phase-2 Fuseki sections are skipped on open; load them when the user opens each tab.
  useEffect(() => {
    if (!isDesktop() || !projectId || mainTab !== "Entities") return;
    const sectionKey = entitiesTab;
    if (desktopDeferredSectionsLoadedRef.current.has(sectionKey)) return;

    const encodedProjectId = encodeURIComponent(projectId);
    const markLoaded = () => desktopDeferredSectionsLoadedRef.current.add(sectionKey);

    if (sectionKey === "ObjectProperties" || sectionKey === "DataProperties") {
      if (objectPropertyHierarchy.length > 0 || dataPropertyHierarchy.length > 0) {
        markLoaded();
        return;
      }
      setIsPropertiesLoading(true);
      refreshProperties()
        .finally(() => {
          markLoaded();
          setIsPropertiesLoading(false);
        });
      return;
    }

    if (sectionKey === "Individuals") {
      if (individuals.length > 0) {
        markLoaded();
        return;
      }
      setIsIndividualsLoading(true);
      apiClient
        .get<any>(withDraftScope(`/api/ontology/individuals/${encodedProjectId}`))
        .then((res) => {
          setIndividuals(
            Array.isArray(res?.data) ? res.data : Array.isArray(res?.individuals) ? res.individuals : [],
          );
        })
        .catch((e) => console.error("[Dashboard] Desktop individuals load failed:", e))
        .finally(() => {
          markLoaded();
          setIsIndividualsLoading(false);
        });
      return;
    }

    if (sectionKey === "AnnotationProperties") {
      if (annotationProperties.length > 0) {
        markLoaded();
        return;
      }
      setIsAnnotationPropertiesLoading(true);
      handleRefreshAnnotationProperties().finally(() => {
        markLoaded();
        setIsAnnotationPropertiesLoading(false);
      });
      return;
    }

    if (sectionKey === "Datatypes") {
      if (datatypes.length > 0) {
        markLoaded();
        return;
      }
      setIsDatatypesLoading(true);
      apiClient
        .get<any>(withDraftScope(`/api/ontology/datatypes/${encodedProjectId}`))
        .then((res) => {
          setDatatypes(Array.isArray(res?.data) ? res.data : Array.isArray(res?.datatypes) ? res.datatypes : []);
        })
        .catch((e) => console.error("[Dashboard] Desktop datatypes load failed:", e))
        .finally(() => {
          markLoaded();
          setIsDatatypesLoading(false);
        });
    }
  }, [
    projectId,
    mainTab,
    entitiesTab,
    individuals.length,
    annotationProperties.length,
    datatypes.length,
    objectPropertyHierarchy.length,
    dataPropertyHierarchy.length,
    refreshProperties,
    handleRefreshAnnotationProperties,
  ]);

  // Handler for creating object properties with name parameter
  const handleAddObjectProperty = useCallback(
    async (type: "subclass" | "sibling", parentId?: string, name?: string) => {
      if (!projectId) return;
      if (isViewOnlyMember) {
        showNotification(viewOnlyMessage, "error");
        return;
      }

      try {
        console.log("[handleAddObjectProperty] Creating property:", name, "type:", type, "parentId:", parentId);
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const cleanName = (name || "NewObjectProperty").replace(/\s+/g, "_");
        const newIri = `${baseIri}${baseIri.endsWith("#") || baseIri.endsWith("/") ? "" : "#"}${cleanName}`;

        let parentIri = "http://www.w3.org/2002/07/owl#topObjectProperty";

        if (parentId) {
          if (type === "subclass") {
            parentIri = parentId;
          } else if (type === "sibling") {
            const parent = findParentNode(objectPropertyHierarchy, parentId);
            if (parent) parentIri = parent.id;
          }
        }

        console.log("[handleAddObjectProperty] Creating with IRI:", newIri, "parent:", parentIri);
        await ontologyMutationService.createObjectProperty(
          projectId,
          newIri,
          name || "NewObjectProperty",
          parentIri,
          user?.email || "anonymous",
          user?.username || "Anonymous",
        );

        console.log("[handleAddObjectProperty] Property created, refreshing...");
        // Add a small delay to ensure backend has processed the property
        await new Promise((resolve) => setTimeout(resolve, 300));
        await refreshProperties();
        console.log("[handleAddObjectProperty] Refresh complete");
        showNotification(`Object property "${name}" created successfully!`);
      } catch (error) {
        console.error("Failed to create object property:", error);
        showNotification("Failed to create object property. See console for details.", "error");
        throw error;
      }
    },
    [projectId, metadata, objectPropertyHierarchy, user, refreshProperties, showNotification],
  );

  // Handler for creating data properties with name parameter
  const handleAddDataProperty = useCallback(
    async (type: "subclass" | "sibling", parentId?: string, name?: string) => {
      if (!projectId) return;
      if (isViewOnlyMember) {
        showNotification(viewOnlyMessage, "error");
        return;
      }

      try {
        console.log("[handleAddDataProperty] Creating property:", name, "type:", type, "parentId:", parentId);
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const cleanName = (name || "NewDataProperty").replace(/\s+/g, "_");
        const newIri = `${baseIri}${baseIri.endsWith("#") || baseIri.endsWith("/") ? "" : "#"}${cleanName}`;

        let parentIri = "http://www.w3.org/2002/07/owl#topDataProperty";

        if (parentId) {
          if (type === "subclass") {
            parentIri = parentId;
          } else if (type === "sibling") {
            const parent = findParentNode(dataPropertyHierarchy, parentId);
            if (parent) parentIri = parent.id;
          }
        }

        console.log("[handleAddDataProperty] Creating with IRI:", newIri, "parent:", parentIri);
        await ontologyMutationService.createDataProperty(
          projectId,
          newIri,
          name || "NewDataProperty",
          parentIri,
          user?.email || "anonymous",
          user?.username || "Anonymous",
        );

        console.log("[handleAddDataProperty] Property created, refreshing...");
        // Add a small delay to ensure backend has processed the property
        await new Promise((resolve) => setTimeout(resolve, 300));
        await refreshProperties();
        console.log("[handleAddDataProperty] Refresh complete");
        showNotification(`Data property "${name}" created successfully!`);
      } catch (error) {
        console.error("Failed to create data property:", error);
        showNotification("Failed to create data property. See console for details.", "error");
        throw error;
      }
    },
    [projectId, metadata, dataPropertyHierarchy, user, refreshProperties, showNotification],
  );

  // Handler for creating classes with name parameter (for inline creation in dialogs)
  const handleAddClassInline = useCallback(
    async (type: "subclass" | "sibling", parentId?: string, name?: string) => {
      if (!projectId) return;
      if (isViewOnlyMember) {
        showNotification(viewOnlyMessage, "error");
        return;
      }

      try {
        console.log("[handleAddClassInline] Creating class:", name, "type:", type, "parentId:", parentId);
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const cleanName = (name || "NewClass").replace(/\s+/g, "_");
        const newIri = `${baseIri}${baseIri.endsWith("#") || baseIri.endsWith("/") ? "" : "#"}${cleanName}`;

        let parentIri = "http://www.w3.org/2002/07/owl#Thing";

        if (parentId) {
          if (type === "subclass") {
            parentIri = parentId;
          } else if (type === "sibling") {
            const parent = findParentNode(classHierarchy, parentId);
            if (parent) parentIri = parent.id;
          }
        }

        console.log("[handleAddClassInline] Creating with IRI:", newIri, "parent:", parentIri);
        await ontologyMutationService.createClass(
          projectId,
          newIri,
          name || "NewClass",
          parentIri,
          user?.email || "anonymous",
          user?.username || "Anonymous",
        );

        // Optimistic local state update so the class appears immediately
        const newNode: TreeNode = {
          id: newIri,
          label: name || "NewClass",
          children: undefined,
          hasChildren: false,
          annotations: { "rdfs:label": name || "NewClass" },
        };

        setExpandedNodes((prev) => {
          if (parentIri && !prev.includes(parentIri)) {
            return [...prev, parentIri];
          }
          return prev;
        });

        setClassHierarchy((prev) => {
          // Same fallback as handleCreateClass: if parentIri (e.g. owl:Thing when
          // there's no selection) isn't itself a rendered node, append at the root
          // instead of silently dropping the new class from the optimistic update.
          let inserted = false;
          const addNodeRecursively = (nodes: TreeNode[]): TreeNode[] => {
            return nodes.map((node) => {
              if (type === "subclass" && node.id === parentIri) {
                inserted = true;
                const children = node.children ? [...node.children, newNode] : [newNode];
                return { ...node, children, hasChildren: true };
              }
              if (type === "sibling" && node.children?.some((child: TreeNode) => child.id === parentId)) {
                inserted = true;
                return { ...node, children: [...(node.children || []), newNode] };
              }
              if (node.children) {
                return { ...node, children: addNodeRecursively(node.children) };
              }
              return node;
            });
          };
          const updated = addNodeRecursively(prev);
          return inserted ? updated : [...prev, newNode];
        });

        markAsUnsaved();

        // Ensure parent is expanded so the new child is visible
        setExpandedNodes((prev) => (prev.includes(parentIri) ? prev : [...prev, parentIri]));

        // Targeted refresh: reload only the parent's children instead of the full hierarchy.
        // For large ontologies (60k+ classes) a full refreshClassHierarchy() takes many seconds
        // and wipes the optimistic update in the meantime. Clearing + reloading just the parent
        // is one API call and keeps the rest of the tree stable.
        await new Promise((resolve) => setTimeout(resolve, 300));

        // Clear the parent's cached children so loadChildren makes a fresh API call
        setClassHierarchy((prev) => {
          const clearParentChildren = (nodes: TreeNode[]): TreeNode[] =>
            nodes.map((n) => {
              if (n.id === parentIri) return { ...n, children: [] };
              if (n.children) return { ...n, children: clearParentChildren(n.children) };
              return n;
            });
          return clearParentChildren(prev);
        });

        await loadChildren(parentIri);

        console.log("[handleAddClassInline] Refresh complete");
        showNotification(`Class "${name}" created successfully!`);
      } catch (error) {
        console.error("Failed to create class:", error);
        showNotification("Failed to create class. See console for details.", "error");
        throw error;
      }
    },
    [projectId, metadata, classHierarchy, user, loadChildren, showNotification, expandedNodes, markAsUnsaved],
  );

  const handleAddItem = useCallback(
    async (type: "subclass" | "sibling" | "individual") => {
      if (!projectId) return;

      if (isProjectDraftEditorRole && syncMode !== 'private') {
        setShowProPromptType('draftRequired');
        return;
      }

      if (type === "individual") {
        setCreateIndividualModalOpen(true);
        return;
      }

      const activeEntitiesTab =
        mainTab === "IndividualsByClass" ? "Classes" : entitiesTab;
      const activeSelectedItem =
        mainTab === "IndividualsByClass" ? selectedClassForIndividuals : selectedItem;

      // Bug #46 / #45 — primary "Add" button is contextual:
      //   • sibling + no selection  → create at top level (under
      //     owl:Thing / owl:topObjectProperty / owl:topDataProperty / no
      //     parent for annotation properties).
      //   • sibling + selection     → create as sibling of selection.
      //   • subclass + no selection → notify; subclass always needs a parent.
      //   • subclass + selection    → create as child of selection.

      if (activeEntitiesTab === "ObjectProperties") {
        if (type === "subclass" && !activeSelectedItem) {
          showNotification(
            "Select an object property first to add a sub-property.",
            "warning",
          );
          return;
        }

        if (!activeSelectedItem) {
          // Top-level object property under owl:topObjectProperty.
          setAddPropertyType("root");
          setPropertyParentLabel("owl:topObjectProperty");
          setAddPropertyDialogOpen(true);
          return;
        }

        // Sibling of an already top-level property is just another root.
        if (type === "sibling") {
          const parent = findParentNode(objectPropertyHierarchy, activeSelectedItem.id);
          const isTopLevel =
            !parent || activeSelectedItem.id.includes("topObjectProperty") || activeSelectedItem.label === "owl:topObjectProperty";
          if (isTopLevel) {
            setAddPropertyType("root");
            setPropertyParentLabel("owl:topObjectProperty");
            setAddPropertyDialogOpen(true);
            return;
          }
        }

        const parentLabel =
          type === "subclass"
            ? activeSelectedItem.label
            : findParentNode(objectPropertyHierarchy, activeSelectedItem.id)?.label || "owl:topObjectProperty";

        setAddPropertyType(type === "subclass" ? "subproperty" : "sibling");
        setPropertyParentLabel(parentLabel);
        setAddPropertyDialogOpen(true);
        return;
      }

      if (activeEntitiesTab === "DataProperties") {
        if (type === "subclass" && !activeSelectedItem) {
          showNotification(
            "Select a data property first to add a sub-property.",
            "warning",
          );
          return;
        }

        if (!activeSelectedItem) {
          setAddPropertyType("root");
          setPropertyParentLabel("owl:topDataProperty");
          setAddPropertyDialogOpen(true);
          return;
        }

        if (type === "sibling") {
          const parent = findParentNode(dataPropertyHierarchy, activeSelectedItem.id);
          const isTopLevel =
            !parent || activeSelectedItem.id.includes("topDataProperty") || activeSelectedItem.label === "owl:topDataProperty";
          if (isTopLevel) {
            setAddPropertyType("root");
            setPropertyParentLabel("owl:topDataProperty");
            setAddPropertyDialogOpen(true);
            return;
          }
        }

        const parentLabel =
          type === "subclass"
            ? activeSelectedItem.label
            : findParentNode(dataPropertyHierarchy, activeSelectedItem.id)?.label || "owl:topDataProperty";

        setAddPropertyType(type === "subclass" ? "subproperty" : "sibling");
        setPropertyParentLabel(parentLabel);
        setAddPropertyDialogOpen(true);
        return;
      }

      if (activeEntitiesTab === "AnnotationProperties") {
        // Bug #45 — annotation properties were always created at root and
        // had no toolbar add. Now matches the other property panes.
        if (type === "subclass" && !activeSelectedItem) {
          showNotification(
            "Select an annotation property first to add a sub-property.",
            "warning",
          );
          return;
        }

        if (!activeSelectedItem) {
          setAddPropertyType("root");
          setPropertyParentLabel("Annotation Property");
          setAddPropertyDialogOpen(true);
          return;
        }

        // Annotation properties currently render as a flat list in
        // asserted mode (only inferredAnnotationPropertyHierarchy is built).
        // "sibling" therefore means "another root-level annotation property"
        // until the asserted hierarchy is wired through; "subclass" creates
        // a sub-annotation-property under the selected one.
        const parentLabel =
          type === "subclass" ? activeSelectedItem.label : "Annotation Property";

        setAddPropertyType(type === "subclass" ? "subproperty" : "root");
        setPropertyParentLabel(parentLabel);
        setAddPropertyDialogOpen(true);
        return;
      }

      if (activeEntitiesTab === "Datatypes") {
        setAddDatatypeDialogOpen(true);
        return;
      }

      if (activeEntitiesTab !== "Classes") {
        showNotification("This action is available only for classes right now.", "warning");
        return;
      }

      // Subclass without a selected class can't proceed — owl:Thing children
      // ARE the top level, so use the contextual sibling button instead.
      if (type === "subclass" && !activeSelectedItem) {
        showNotification("Select a class first to add a subclass.", "warning");
        return;
      }

      // Sibling without selection: create a top-level class under owl:Thing.
      if (!activeSelectedItem) {
        setAddClassType("subclass"); // creates as child of owl:Thing
        setClassParentLabel("owl:Thing");
        setAddClassDialogOpen(true);
        return;
      }

      // Sibling of owl:Thing → top-level class (same effect, friendlier).
      if (type === "sibling") {
        const parent = findParentNode(classHierarchy, activeSelectedItem.id);
        const isTopLevel = !parent || activeSelectedItem.id.includes("Thing") || activeSelectedItem.label === "owl:Thing";

        if (isTopLevel) {
          setAddClassType("subclass");
          setClassParentLabel("owl:Thing");
          setAddClassDialogOpen(true);
          return;
        }
      }

      // For parent label, compute via functional state accessor.
      let parentLabel = activeSelectedItem.label;
      if (type === "sibling") {
        setClassHierarchy((currentHierarchy) => {
          const parent = findParentNode(currentHierarchy, activeSelectedItem.id);
          parentLabel = parent?.label || "owl:Thing";
          return currentHierarchy; // No change
        });
      }

      setAddClassType(type);
      setClassParentLabel(parentLabel);
      setAddClassDialogOpen(true);
    },
    [projectId, mainTab, entitiesTab, selectedItem, selectedClassForIndividuals, showNotification, objectPropertyHierarchy, dataPropertyHierarchy, classHierarchy, isProjectDraftEditorRole, syncMode],
  );

  const handleCreateClass = useCallback(
    async (name: string) => {
      if (!projectId) return;

      const type = addClassType;

      try {
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const newIri = `${baseIri}#${name.replace(/\s+/g, "_")}`;

        // Determine parent IRI based on type
        let parentIri = "http://www.w3.org/2002/07/owl#Thing";

        if (entitiesTab === "Classes") {
          if (type === "subclass" && selectedItem?.id) {
            parentIri = selectedItem.id;
          } else if (type === "sibling" && selectedItem?.id) {
            // Use functional update to find parent without dependency
            let foundParentIri = "http://www.w3.org/2002/07/owl#Thing";
            setClassHierarchy((currentHierarchy) => {
              const findParent = (
                nodes: TreeNode[],
                targetId: string,
                parent: TreeNode | null = null,
              ): TreeNode | null => {
                for (const node of nodes) {
                  if (node.id === targetId) return parent;
                  if (node.children) {
                    const found = findParent(node.children, targetId, node);
                    if (found) return found;
                  }
                }
                return null;
              };
              const parent = findParent(currentHierarchy, selectedItem.id);
              foundParentIri = parent?.id || "http://www.w3.org/2002/07/owl#Thing";
              return currentHierarchy; // No change yet
            });
            parentIri = foundParentIri;
          }

          // Call backend API with user info
          await ontologyMutationService.createClass(
            projectId,
            newIri,
            name,
            parentIri,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );

          // Update local state
          const newNode: TreeNode = {
            id: newIri,
            label: name,
            children: undefined,
            hasChildren: false,
            annotations: { "rdfs:label": name },
          };

          setExpandedNodes((prev) => {
            if (type === "subclass" && selectedItem?.id && !prev.includes(selectedItem.id)) {
              return [...prev, selectedItem.id];
            }
            return prev;
          });

          setClassHierarchy((prev) => {
            // Root-level creation (no selection, or a parent not found in the loaded
            // tree — e.g. owl:Thing, which is never itself a rendered node) has no
            // node to attach to; track whether we actually found one and fall back
            // to appending at the root instead of silently dropping the new class
            // from the optimistic update (it was still created on the backend, but
            // wouldn't show up until a full reload).
            let inserted = false;
            const addNodeRecursively = (nodes: TreeNode[]): TreeNode[] => {
              return nodes.map((node) => {
                if (type === "subclass" && node.id === selectedItem?.id) {
                  inserted = true;
                  const children = node.children ? [...node.children, newNode] : [newNode];
                  return { ...node, children, hasChildren: true };
                }
                if (type === "sibling" && node.children?.some((child: TreeNode) => child.id === selectedItem?.id)) {
                  inserted = true;
                  return { ...node, children: [...(node.children || []), newNode] };
                }
                if (node.children) {
                  return { ...node, children: addNodeRecursively(node.children) };
                }
                return node;
              });
            };

            // If adding sibling at root level
            if (type === "sibling" && prev.some((node) => node.id === selectedItem?.id)) {
              return [...prev, newNode];
            }
            const updated = addNodeRecursively(prev);
            return inserted ? updated : [...prev, newNode];
          });
          markAsUnsaved();
          setMetadata((prev) => (prev ? { ...prev, classCount: (prev.classCount || 0) + 1 } : prev));
        } else if (entitiesTab === "ObjectProperties") {
          // Handle Object Property Creation
          parentIri = "http://www.w3.org/2002/07/owl#topObjectProperty";
          if (type === "subclass" && selectedItem?.id) {
            parentIri = selectedItem.id;
          } else if (type === "sibling" && selectedItem?.id) {
            // Find parent of selected item in hierarchy
            const findParent = (nodes: any[], targetId: string, parent: any | null = null): any | null => {
              for (const node of nodes) {
                if (node.id === targetId) return parent;
                if (node.children) {
                  const found = findParent(node.children, targetId, node);
                  if (found) return found;
                }
              }
              return null;
            };
            const parent = findParent(objectPropertyHierarchy, selectedItem.id);
            parentIri = parent?.id || "http://www.w3.org/2002/07/owl#topObjectProperty";
          }

          await ontologyMutationService.createObjectProperty(
            projectId,
            newIri,
            name,
            parentIri,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );

          const newProp: Property & TreeNode = {
            id: newIri,
            label: name,
            type: "ObjectProperty" as const,
            annotations: { "rdfs:label": name },
            children: [],
            hasChildren: false,
          };

          setObjectProperties((prev) => [...prev, newProp]);

          // Update Hierarchy
          const addNodeRecursively = (nodes: any[]): any[] => {
            return nodes.map((node) => {
              if (node.id === parentIri) {
                const children = node.children ? [...node.children, newProp] : [newProp];
                return { ...node, children, hasChildren: true };
              }
              if (node.children) {
                return { ...node, children: addNodeRecursively(node.children) };
              }
              return node;
            });
          };

          setObjectPropertyHierarchy((prev) => addNodeRecursively(prev));

          if (parentIri && !expandedNodes.includes(parentIri)) {
            setExpandedNodes((prev) => [...prev, parentIri]);
          }
          setMetadata((prev) => (prev ? { ...prev, objectPropertyCount: (prev.objectPropertyCount || 0) + 1 } : prev));
          // Reconcile with backend truth — the optimistic update above is what desktop's
          // annotation-property "not displayed after add" bug was: relying solely on it left
          // the list wrong whenever the desktop OWLAPI cache had to re-warm after the mutation.
          await refreshProperties();
        }

        showNotification(`${entitiesTab === "Classes" ? "Class" : "Property"} created successfully!`, "info");
        setAddClassDialogOpen(false);
      } catch (error) {
        console.error("Failed to create entity:", error);
        showNotification("Failed to create entity. See console for details.", "error");
      }
    },
    [projectId, selectedItem, addClassType, entitiesTab, metadata, markAsUnsaved, refreshProperties],
  );

  const handleCreateObjectProperty = useCallback(
    async (name: string) => {
      if (!projectId) return;

      const type = addPropertyType;

      try {
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const newIri = `${baseIri}#${name.replace(/\s+/g, "_")}`;

        let parentIri = "http://www.w3.org/2002/07/owl#topObjectProperty";
        if (type === "subproperty" && selectedItem?.id) {
          parentIri = selectedItem.id;
        } else if (type === "sibling" && selectedItem?.id) {
          const parent = findParentNode(objectPropertyHierarchy, selectedItem.id);
          parentIri = parent?.id || "http://www.w3.org/2002/07/owl#topObjectProperty";
        }

        await ontologyMutationService.createObjectProperty(projectId, newIri, name, parentIri);

        const newProp: Property & TreeNode = {
          id: newIri,
          label: name,
          type: "ObjectProperty",
          annotations: { "rdfs:label": name },
          children: [],
          hasChildren: false,
        };

        setObjectProperties((prev) => [...prev, newProp]);

        // If parentIri (e.g. owl:topObjectProperty when there's no selection) isn't
        // itself a rendered node, append at the root instead of silently dropping
        // the new property from the optimistic update.
        let objectPropInserted = false;
        const addNodeRecursively = (nodes: any[]): any[] => {
          return nodes.map((node) => {
            if (node.id === parentIri) {
              objectPropInserted = true;
              const children = node.children ? [...node.children, newProp] : [newProp];
              return { ...node, children, hasChildren: true };
            }
            if (node.children) {
              return { ...node, children: addNodeRecursively(node.children) };
            }
            return node;
          });
        };

        setObjectPropertyHierarchy((prev) => {
          const updated = addNodeRecursively(prev);
          return objectPropInserted ? updated : [...prev, newProp];
        });

        if (parentIri && !expandedNodes.includes(parentIri)) {
          setExpandedNodes((prev) => [...prev, parentIri]);
        }

        markAsUnsaved();
        setMetadata((prev) => (prev ? { ...prev, objectPropertyCount: (prev.objectPropertyCount || 0) + 1 } : prev));
        // Reconcile with backend truth (see comment on the other object-property create path).
        await refreshProperties();
        showNotification("Property created successfully!", "info");
        setAddPropertyDialogOpen(false);
        setPropertyParentLabel("owl:topObjectProperty");
      } catch (error) {
        console.error("Failed to create property:", error);
        showNotification("Failed to create property. See console for details.", "error");
      }
    },
    [projectId, selectedItem, addPropertyType, objectPropertyHierarchy, expandedNodes, metadata, markAsUnsaved, refreshProperties],
  );

  const handleCreateDataProperty = useCallback(
    async (name: string) => {
      if (!projectId) return;

      const type = addPropertyType;

      try {
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const newIri = `${baseIri}#${name.replace(/\s+/g, "_")}`;

        let parentIri = "http://www.w3.org/2002/07/owl#topDataProperty";
        if (type === "subproperty" && selectedItem?.id) {
          parentIri = selectedItem.id;
        } else if (type === "sibling" && selectedItem?.id) {
          const parent = findParentNode(dataPropertyHierarchy, selectedItem.id);
          parentIri = parent?.id || "http://www.w3.org/2002/07/owl#topDataProperty";
        }

        await ontologyMutationService.createDataProperty(projectId, newIri, name, parentIri);

        const newProp: Property & TreeNode = {
          id: newIri,
          label: name,
          type: "DatatypeProperty" as const,
          annotations: { "rdfs:label": name },
          children: [],
          hasChildren: false,
        };

        setDataProperties((prev) => [...prev, newProp]);

        // If parentIri (e.g. owl:topDataProperty when there's no selection) isn't
        // itself a rendered node, append at the root instead of silently dropping
        // the new property from the optimistic update.
        let dataPropInserted = false;
        const addNodeRecursively = (nodes: any[]): any[] => {
          return nodes.map((node) => {
            if (node.id === parentIri) {
              dataPropInserted = true;
              const children = node.children ? [...node.children, newProp] : [newProp];
              return { ...node, children, hasChildren: true };
            }
            if (node.children) {
              return { ...node, children: addNodeRecursively(node.children) };
            }
            return node;
          });
        };

        setDataPropertyHierarchy((prev) => {
          const updated = addNodeRecursively(prev);
          return dataPropInserted ? updated : [...prev, newProp];
        });

        if (parentIri && !expandedNodes.includes(parentIri)) {
          setExpandedNodes((prev) => [...prev, parentIri]);
        }

        markAsUnsaved();
        setMetadata((prev) => (prev ? { ...prev, dataPropertyCount: (prev.dataPropertyCount || 0) + 1 } : prev));
        // Reconcile with backend truth (see comment on the object-property create paths).
        await refreshProperties();
        showNotification("Data property created successfully!", "info");
        setAddPropertyDialogOpen(false);
        setPropertyParentLabel("owl:topDataProperty");
      } catch (error) {
        console.error("Failed to create data property:", error);
        showNotification("Failed to create data property. See console for details.", "error");
      }
    },
    [projectId, selectedItem, addPropertyType, dataPropertyHierarchy, expandedNodes, metadata, markAsUnsaved, refreshProperties],
  );

  const handleCreateDatatype = useCallback(
    async (name: string) => {
      if (!projectId) return;

      try {
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const newIri = `${baseIri}#${name.replace(/\s+/g, "_")}`;

        await ontologyMutationService.createDatatype(
          projectId,
          newIri,
          name,
          user?.email || "anonymous",
          user?.username || "Anonymous",
        );

        const newDatatype: Datatype = {
          id: newIri,
          label: name,
          annotations: { "rdfs:label": name },
        };

        setDatatypes((prev) => [...prev, newDatatype]);

        markAsUnsaved();
        showNotification("Datatype created successfully!", "info");
        setAddDatatypeDialogOpen(false);
      } catch (error) {
        console.error("Failed to create datatype:", error);
        showNotification("Failed to create datatype. See console for details.", "error");
      }
    },
    [projectId, metadata, markAsUnsaved, showNotification],
  );

  const handleCreateAnnotationProperty = useCallback(
    async (name: string) => {
      if (!projectId) return;

      try {
        const baseIri = (metadata as any)?.ontologyIRI || "http://example.com/onto";
        const newIri = `${baseIri}#${name.replace(/\s+/g, "_")}`;

        await ontologyMutationService.createAnnotationProperty(projectId, newIri, name);

        // Bug #45: support sub-annotation-properties. The backend's
        // createAnnotationProperty mutation doesn't accept a parent IRI, but
        // the generic addSubPropertyOf does (it just emits
        // `<child> rdfs:subPropertyOf <parent>`, which is the correct
        // assertion for annotation properties under OWL 2).
        if (addPropertyType === "subproperty" && selectedItem?.id) {
          try {
            await ontologyMutationService.addSubPropertyOf(projectId, newIri, selectedItem.id);
          } catch (linkErr) {
            console.error(
              "[Dashboard] Created annotation property but failed to link as sub-property:",
              linkErr,
            );
            showNotification(
              "Annotation property created, but the sub-property relationship could not be saved.",
              "warning",
            );
          }
        }

        markAsUnsaved();
        setMetadata((prev) =>
          prev ? { ...prev, annotationPropertyCount: (prev.annotationPropertyCount || 0) + 1 } : prev,
        );
        // Refresh from backend so the new property (with full data) appears in the list
        await handleRefreshAnnotationProperties();
        showNotification("Annotation property created successfully!", "info");
        setAddPropertyDialogOpen(false);
      } catch (error) {
        console.error("Failed to create annotation property:", error);
        showNotification("Failed to create annotation property. See console for details.", "error");
      }
    },
    [projectId, metadata, markAsUnsaved, showNotification, addPropertyType, selectedItem, handleRefreshAnnotationProperties],
  );

  const handleAddIndividual = useCallback(
    async (name: string) => {
      if (!projectId) {
        showNotification("No project loaded.", "error");
        return;
      }
      if (isViewOnlyMember) {
        showNotification(viewOnlyMessage, "error");
        return;
      }

      const base = (metadata as any)?.ontologyIRI || "http://example.com/onto";
      const id = `${base}#${name.replace(/\s+/g, "_")}`;

      // Determine the class IRI - use selected class if available, otherwise owl:Thing
      const classIri =
        entitiesTab === "Classes" && selectedItem?.id ? selectedItem.id : "http://www.w3.org/2002/07/owl#Thing";

      try {
        // Call the mutation service to persist the individual
        await ontologyMutationService.createIndividual(projectId, id, name, classIri);

        // Update local state
        const newIndividual: Individual = {
          id,
          iri: id,
          label: name,
          annotations: { "rdfs:label": name },
          types: [classIri],
        };
        setIndividuals((prev) => [...prev, newIndividual]);

        markAsUnsaved();
        setMetadata((prev) => (prev ? { ...prev, individualCount: (prev.individualCount || 0) + 1 } : prev));
        showNotification(`Individual "${name}" created successfully!`, "info");
      } catch (error) {
        console.error("Failed to create individual:", error);
        showNotification("Failed to create individual. See console for details.", "error");
      }
    },
    [projectId, metadata, entitiesTab, selectedItem, markAsUnsaved, showNotification],
  );

  const handleMakeSiblingsDisjoint = useCallback(async () => {
    console.log("[DEBUG] handleMakeSiblingsDisjoint called");
    const activeEntitiesTab =
      mainTab === "IndividualsByClass" ? "Classes" : entitiesTab;
    const activeSelectedItem =
      mainTab === "IndividualsByClass" ? selectedClassForIndividuals : selectedItem;
    if (!projectId || !activeSelectedItem || activeEntitiesTab !== "Classes") return;

    // Find siblings of selected class - use classHierarchy directly as a dependency
    const findSiblings = (nodes: TreeNode[], targetId: string, parent: TreeNode | null = null): TreeNode[] => {
      for (const node of nodes) {
        if (node.id === targetId && parent && parent.children) {
          // Return all children of parent except the target
          return parent.children.filter((child: TreeNode) => child.id !== targetId);
        }
        if (node.children) {
          const foundSiblings = findSiblings(node.children, targetId, node);
          if (foundSiblings.length > 0) return foundSiblings;
        }
      }
      return [];
    };

    const siblings = findSiblings(classHierarchy, activeSelectedItem.id);

    if (siblings.length === 0) {
      showNotification("No siblings found for the selected class.", "info");
      return;
    }

    // Show confirmation dialog
    setConfirmDialog({
      isOpen: true,
      title: "Make Siblings Disjoint",
      message: `This will make ${siblings.length + 1} sibling classes pairwise disjoint. Continue?`,
      onConfirm: async () => {
        try {
          // Include the selected class itself in the disjoint set
          const allClasses = [activeSelectedItem as TreeNode, ...siblings];
          const classIds = allClasses.map((c) => c.id);

          // Call backend to create pairwise disjoint axioms
          await ontologyMutationService.makeSiblingsDisjoint(
            projectId,
            classIds,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );

          showNotification(`Successfully made ${classIds.length} classes pairwise disjoint.`, "info");
          console.log(`[MUTATION:disjoint] ✓ ${classIds.length} classes — refreshing hierarchy`);

          // Re-fetch the class hierarchy so all sibling nodes reflect the new disjoint axioms
          lastClassHierarchyRefreshAt.current = 0;
          refreshClassHierarchy();
        } catch (error) {
          console.error("Failed to make siblings disjoint:", error);
          showNotification("Failed to make siblings disjoint. See console for details.", "error");
        }
      },
    });
  }, [projectId, mainTab, selectedItem, selectedClassForIndividuals, entitiesTab, classHierarchy, updateItemInState, showNotification, user, refreshClassHierarchy]);

  // Deletes one or more classes (a single class, or a class + its asserted descendants
  // from DeleteClassDialog) in one atomic request, then reconciles local state for all of them.
  const performDeleteClasses = useCallback(
    async (iris: string[]) => {
      if (!projectId || iris.length === 0) return;
      if (isMutatingRef.current) {
        showNotification("Please wait for the current operation to finish before deleting another item.", "warning");
        return;
      }
      isMutatingRef.current = true;
      try {
        // For a single (non-cascade) delete, check up front whether the target has children —
        // if so, "delete only" orphans them (they lose their sole parent and must reappear as
        // top-level classes, Protégé-parity). The local tree surgery below removes the deleted
        // node's whole subtree wholesale, so it can't know the orphans' new position — a real
        // hierarchy refresh is needed instead of leaving them hidden until the user manually
        // refreshes. Cascade deletes (iris.length > 1) don't need this: every descendant is
        // already explicitly deleted, so there's nothing left to orphan.
        let deletedNodeHadChildren = false;
        if (iris.length === 1) {
          const findNode = (nodes: TreeNode[]): TreeNode | undefined => {
            for (const node of nodes) {
              if (node.id === iris[0]) return node;
              if (node.children) {
                const found = findNode(node.children);
                if (found) return found;
              }
            }
            return undefined;
          };
          const target = findNode(classHierarchy);
          deletedNodeHadChildren = !!(target && (target.hasChildren || (target.children && target.children.length > 0)));
        }

        // Desktop is OWLAPI-first with lazy Fuseki sync (see refreshProperties) — the fast
        // in-memory delete path (DesktopOwlApiMutationService.tryApply) only engages once the
        // OWLAPI cache is warm. Deleting before that falls through to a raw SPARQL DELETE
        // against a graph that hasn't caught up yet, so the class can appear to come back.
        // If the cache still isn't warm after the wait, abort rather than take that path.
        if (isDesktop()) {
          const owlApiReady = await waitForDesktopOwlApiReady(projectId);
          if (!owlApiReady) {
            showNotification(
              "The ontology is still loading into memory. Please try deleting again in a moment.",
              "warning",
            );
            return;
          }
        }

        if (iris.length === 1) {
          await ontologyMutationService.deleteClass(
            projectId, iris[0], user?.email || "anonymous", user?.username || "Anonymous",
          );
        } else {
          await ontologyMutationService.deleteClasses(
            projectId, iris, user?.email || "anonymous", user?.username || "Anonymous",
          );
        }

        const idSet = new Set(iris);
        const removeNodesRecursively = (nodes: TreeNode[]): TreeNode[] =>
          nodes
            .filter((node) => !idSet.has(node.id))
            .map((node) => (node.children ? { ...node, children: removeNodesRecursively(node.children) } : node));
        setClassHierarchy((prev) => removeNodesRecursively(prev));
        if (deletedNodeHadChildren) {
          lastClassHierarchyRefreshAt.current = 0;
          refreshClassHierarchy();
        }
        // Remove individuals whose only type(s) were among the deleted classes
        setIndividuals((prev) => prev.filter((ind) => !(ind as any).types?.some((t: string) => idSet.has(t))));
        // Evict deleted classes from annotation cache
        setHierarchyAnnotationValues((prev) => {
          const m = new Map(prev);
          idSet.forEach((id) => m.delete(id));
          return m;
        });
        idSet.forEach((id) => fetchedAnnotationIrisRef.current.delete(id));
        setSelectedItem((prev) => (prev && idSet.has(prev.id) ? null : prev));
        setMetadata((prev) =>
          prev ? { ...prev, classCount: Math.max(0, ((prev as any).classCount || 0) - iris.length) } : prev,
        );
        console.log(`[MUTATION:delete] ✓ Classes:${iris.join(", ")}`);
        showNotification(
          iris.length > 1 ? `Deleted ${iris.length} classes successfully!` : "Class deleted successfully!",
          "info",
        );
      } catch (error) {
        console.error("Failed to delete class(es):", error);
        showNotification("Failed to delete item. See console for details.", "error");
      } finally {
        isMutatingRef.current = false;
      }
    },
    [projectId, user, showNotification, classHierarchy, refreshClassHierarchy],
  );

  const handleDeleteItem = useCallback(
    async (itemOverride?: SelectableItem, tabOverride?: typeof entitiesTab) => {
      const item = itemOverride || selectedItem;
      const activeTab = tabOverride || entitiesTab;
      if (!item || !projectId) return;

      // Validate item has a valid IRI
      if (!item.id) {
        console.error("[DELETE] Item has no IRI:", item);
        showNotification("Cannot delete: item has no valid IRI", "error");
        return;
      }

      // Block concurrent deletes — multiple simultaneous DELETEs hit GraphDB Free's
      // 2-connection cap, queue up, and trigger 504 gateway timeouts.
      if (isMutatingRef.current) {
        showNotification("Please wait for the current operation to finish before deleting another item.", "warning");
        return;
      }

      // Prevent deletion of built-in annotation properties (rdfs:label, rdfs:comment, etc.)
      if (activeTab === "AnnotationProperties" && STANDARD_ANNOTATION_PROPERTIES.some((p) => p.id === item.id)) {
        showNotification(`"${item.label}" is a built-in annotation property and cannot be deleted.`, "error");
        return;
      }

      // Classes get the Protégé-style "delete only" vs "delete + descendants" dialog instead
      // of the generic confirm dialog — see performDeleteClasses/DeleteClassDialog.
      if (activeTab === "Classes") {
        setDeleteClassDialog({ isOpen: true, iri: item.id, label: item.label });
        return;
      }

      // Show confirm dialog instead of using confirm()
      setConfirmDialog({
        isOpen: true,
        title: "Delete Item",
        message: `Are you sure you want to delete "${item.label}"? This action cannot be undone.`,
        onConfirm: async () => {
          isMutatingRef.current = true;
          try {
            console.log("[DELETE] Deleting item:", { id: item.id, label: item.label, tab: activeTab });

            // Call backend API based on entity type (Classes are handled by performDeleteClasses)
            switch (activeTab) {
              case "Individuals":
                await ontologyMutationService.deleteIndividual(
                  projectId,
                  item.id,
                  user?.email || "anonymous",
                  user?.username || "Anonymous",
                );
                break;
              case "ObjectProperties":
                await ontologyMutationService.deleteObjectProperty(
                  projectId,
                  item.id,
                  user?.email || "anonymous",
                  user?.username || "Anonymous",
                );
                break;
              case "DataProperties":
                await ontologyMutationService.deleteDataProperty(
                  projectId,
                  item.id,
                  user?.email || "anonymous",
                  user?.username || "Anonymous",
                );
                break;
              case "AnnotationProperties":
                await ontologyMutationService.deleteAnnotationProperty(
                  projectId,
                  item.id,
                  user?.email || "anonymous",
                  user?.username || "Anonymous",
                );
                break;
              case "Datatypes":
                await ontologyMutationService.deleteDatatype(
                  projectId,
                  item.id,
                  user?.email || "anonymous",
                  user?.username || "Anonymous",
                );
                break;
            }

            // Update local state (Classes are handled by performDeleteClasses)
            switch (activeTab) {
              case "Individuals":
                setIndividuals((prev) => prev.filter((ind) => ind.id !== item.id));
                break;
              case "ObjectProperties": {
                setObjectProperties((prev) => prev.filter((p) => p.id !== item.id));
                const removeOpRecursively = (nodes: any[], id: string): any[] =>
                  nodes
                    .filter((node) => node.id !== id)
                    .map((node) =>
                      node.children ? { ...node, children: removeOpRecursively(node.children, id) } : node,
                    );
                setObjectPropertyHierarchy((prev) => removeOpRecursively(prev, item.id));
                // Reconcile with backend truth — same rationale as the create paths above.
                await refreshProperties();
                break;
              }
              case "DataProperties": {
                setDataProperties((prev) => prev.filter((p) => p.id !== item.id));
                const removeDpRecursively = (nodes: any[], id: string): any[] =>
                  nodes
                    .filter((node) => node.id !== id)
                    .map((node) =>
                      node.children ? { ...node, children: removeDpRecursively(node.children, id) } : node,
                    );
                setDataPropertyHierarchy((prev) => removeDpRecursively(prev, item.id));
                // Reconcile with backend truth — same rationale as the create paths above.
                await refreshProperties();
                break;
              }
              case "AnnotationProperties":
                setAnnotationProperties((prev) => prev.filter((p) => p.id !== item.id));
                await handleRefreshAnnotationProperties();
                break;
              case "Datatypes":
                setDatatypes((prev) => prev.filter((d) => d.id !== item.id));
                break;
            }
            setSelectedItem((prev) => (prev?.id === item.id ? null : prev));
            // Decrement metadata count based on entity type
            const countField = {
              Classes: "classCount",
              Individuals: "individualCount",
              ObjectProperties: "objectPropertyCount",
              DataProperties: "dataPropertyCount",
              AnnotationProperties: "annotationPropertyCount",
            }[activeTab];
            if (countField) {
              setMetadata((prev) =>
                prev ? { ...prev, [countField]: Math.max(0, ((prev as any)[countField] || 0) - 1) } : prev,
              );
            }
            console.log(`[MUTATION:delete] ✓ ${activeTab}:${item.id}`);
            showNotification(`"${item.label}" deleted successfully!`, "info");
          } catch (error) {
            console.error("Failed to delete item:", error);
            showNotification("Failed to delete item. See console for details.", "error");
          } finally {
            isMutatingRef.current = false;
          }
        },
      });
    },
    [selectedItem, entitiesTab, projectId, refreshProperties, handleRefreshAnnotationProperties],
  );

  const handleChangeEntityIri = useCallback(
    (item: SelectableItem) => {
      if (isViewOnlyMember) { handleViewOnlyAction(); return; }
      setEditEntityIRITarget(item);
      setIsEditEntityIRIDialogOpen(true);
    },
    [isViewOnlyMember],
  );

  const handleSaveEntityIri = useCallback(
    async (newIri: string) => {
      if (!projectId || !editEntityIRITarget) return;
      await ontologyMutationService.renameEntity(
        projectId,
        editEntityIRITarget.id,
        newIri,
        user?.email || "anonymous",
        user?.username || "Anonymous",
      );
      showNotification("Entity IRI updated successfully", "info");
      setEditEntityIRITarget(null);
      // Force refresh so the hierarchy reflects the renamed IRI.
      await fetchData(projectId, false, undefined, true);
    },
    [projectId, editEntityIRITarget, user, fetchData],
  );

  const handleRenameItem = useCallback(
    async (itemId: string, newLabel: string) => {
      if (isViewOnlyMember) { handleViewOnlyAction(); return; }
      console.log("[DEBUG] handleRenameItem called for itemId:", itemId, "newLabel:", newLabel);
      if (!projectId || !newLabel.trim()) return;

      try {
        // Update the label via backend
        // We'll use the itemId directly rather than searching for the item
        // The backend knows the entity type from the IRI

        // Try to update via class label endpoint first (works for classes)
        try {
          await ontologyMutationService.updateClassLabel(
            projectId,
            itemId,
            newLabel,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );
        } catch (classError) {
          // If class update fails, try annotation-based update (for other entity types)
          const currentLabel = selectedItem?.id === itemId
            ? selectedItem.label
            : itemId.split(/[#/]/).pop() ?? itemId;
          await ontologyMutationService.deleteAnnotation(
            projectId,
            itemId,
            "http://www.w3.org/2000/01/rdf-schema#label",
            currentLabel,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );
          await ontologyMutationService.addAnnotation(
            projectId,
            itemId,
            "http://www.w3.org/2000/01/rdf-schema#label",
            newLabel,
            user?.email || "anonymous",
            user?.username || "Anonymous",
          );
        }

        // Update local state by creating a minimal updated item
        const updatedItem = {
          ...(selectedItem || { id: itemId, label: newLabel }),
          label: newLabel,
        } as SelectableItem;
        updateItemInState(updatedItem);
        // Sync annotation-mode display cache if showing rdfs:label
        if (hierarchyAnnotationPropIri === "http://www.w3.org/2000/01/rdf-schema#label") {
          setHierarchyAnnotationValues((prev) => {
            const next = new Map(prev);
            next.set(itemId, newLabel);
            return next;
          });
        }
        console.log(`[MUTATION:rename] ✓ ${itemId} → "${newLabel}", annotProp cache updated:`, hierarchyAnnotationPropIri === "http://www.w3.org/2000/01/rdf-schema#label");
        showNotification(`Renamed to "${newLabel}"`, "info");
      } catch (error) {
        console.error("Failed to rename item:", error);
        showNotification("Failed to rename item. See console for details.", "error");
      }
    },
    [projectId, selectedItem, updateItemInState, hierarchyAnnotationPropIri],
  );

  const handleMoveClass = useCallback(
    async (classId: string, newParentId: string) => {
      if (isViewOnlyMember) {
        handleViewOnlyAction();
        return;
      }
      if (!projectId || !classId || !newParentId || classId === newParentId) return;

      const owlThing = "http://www.w3.org/2002/07/owl#Thing";
      if (classId === owlThing) {
        showNotification("Cannot move owl:Thing", "warning");
        return;
      }

      try {
        const currentParent = findParentNode(classHierarchy, classId);
        if (currentParent && currentParent.id !== newParentId) {
          await ontologyMutationService.deleteSubClassOf(
            projectId,
            classId,
            currentParent.id,
            user?.email,
            user?.username || user?.email,
          );
        }
        if (newParentId !== owlThing) {
          await ontologyMutationService.addSubClassOf(
            projectId,
            classId,
            newParentId,
            user?.email,
            user?.username || user?.email,
          );
        }
        await refreshClassHierarchy();
        setExpandedNodes((prev) => (prev.includes(newParentId) ? prev : [...prev, newParentId]));
        markAsUnsaved();
      } catch (error) {
        console.error("[Dashboard] Failed to move class:", error);
        showNotification("Failed to move class in hierarchy", "error");
      }
    },
    [
      projectId,
      classHierarchy,
      isViewOnlyMember,
      user,
      refreshClassHierarchy,
      showNotification,
      handleViewOnlyAction,
      markAsUnsaved,
    ],
  );

  const handleGraphNodeClick = useCallback(
    (nodeId: string) => {
      console.log("[DEBUG] handleGraphNodeClick called for nodeId:", nodeId);
      const flatten = (nodes: TreeNode[]): TreeNode[] =>
        nodes.flatMap((n) => [n, ...(n.children ? flatten(n.children) : [])]);

      const allItems: SelectableItem[] = [...flatten(classHierarchy), ...individuals];
      const item = allItems.find((i: SelectableItem) => i.id === nodeId);
      if (item) {
        let tab = "Classes";
        if ("types" in item) tab = "Individuals";

        setEntitiesTab(tab);
        setSelectedItem(item);
        setMainTab("Entities");
      }
    },
    [classHierarchy, individuals],
  );

  const findClassNodeById = useCallback(
    (targetId: string): TreeNode | null => {
      const traverse = (nodes: TreeNode[]): TreeNode | null => {
        for (const node of nodes) {
          if (node.id === targetId) {
            return node;
          }
          if (node.children) {
            const found = traverse(node.children);
            if (found) {
              return found;
            }
          }
        }
        return null;
      };
      return traverse(classHierarchy);
    },
    [classHierarchy],
  );

  const flattenTree = useCallback((nodes: TreeNode[]): TreeNode[] => {
    return nodes.flatMap((n) => [n, ...(n.children ? flattenTree(n.children) : [])]);
  }, []);

  useEffect(() => {
    const handleCollaborationNavigate = (event: Event) => {
      const detail = (event as CustomEvent<CollaborationNavigateDetail>).detail;
      if (!detail?.entityIRI) return;
      if (detail.projectId && projectId && detail.projectId !== projectId) return;

      const tab = resolveEntitiesTab(detail.entityType, detail.changeType);
      setMainTab("Entities");
      setEntitiesTab(tab);

      const label =
        detail.entityLabel || detail.entityIRI.split(/[#/]/).pop() || detail.entityIRI;

      let item: SelectableItem | null = null;
      if (tab === "Classes") {
        item = findClassNodeById(detail.entityIRI);
      } else if (tab === "Individuals") {
        item = individuals.find((i) => i.id === detail.entityIRI) || null;
      } else if (tab === "ObjectProperties") {
        item = flattenTree(objectPropertyHierarchy).find((n) => n.id === detail.entityIRI) || null;
        if (!item) item = objectProperties.find((p) => p.id === detail.entityIRI) || null;
      } else if (tab === "DataProperties") {
        item = flattenTree(dataPropertyHierarchy).find((n) => n.id === detail.entityIRI) || null;
        if (!item) item = dataProperties.find((p) => p.id === detail.entityIRI) || null;
      } else if (tab === "AnnotationProperties") {
        item = flattenTree(annotationPropertyHierarchy).find((n) => n.id === detail.entityIRI) || null;
        if (!item) item = annotationProperties.find((p) => p.id === detail.entityIRI) || null;
      } else if (tab === "Datatypes") {
        item = datatypes.find((d) => d.id === detail.entityIRI) || null;
      }

      if (!item) {
        item = { id: detail.entityIRI, label, children: [], hasChildren: false } as TreeNode;
      }

      setSelectedItem(item);
      notificationService.info("Navigated", `Opened ${label} in ${tab}`);
    };

    window.addEventListener(COLLABORATION_NAVIGATE_EVENT, handleCollaborationNavigate as EventListener);
    return () => window.removeEventListener(COLLABORATION_NAVIGATE_EVENT, handleCollaborationNavigate as EventListener);
  }, [
    projectId,
    findClassNodeById,
    flattenTree,
    individuals,
    objectPropertyHierarchy,
    dataPropertyHierarchy,
    annotationPropertyHierarchy,
    objectProperties,
    dataProperties,
    annotationProperties,
    datatypes,
  ]);

  useEffect(() => {
    const handleGraphAddClass = (event: Event) => {
      const custom = event as CustomEvent<{
        action: "subclass" | "sibling";
        targetNodeId: string;
        targetNodeLabel?: string;
        parentId?: string | null;
        parentLabel?: string | null;
        projectId?: string;
      }>;

      const detail = custom.detail;
      if (!detail) return;
      if (detail.projectId && projectId && detail.projectId !== projectId) {
        return;
      }

      if (isProjectDraftEditorRole && syncMode !== 'private') {
        setShowProPromptType('draftRequired');
        return;
      }

      const targetNode = findClassNodeById(detail.targetNodeId);
      if (!targetNode) {
        showNotification("Selected class not found in hierarchy. Please refresh the graph and try again.", "warning");
        return;
      }

      setMainTab("Entities");
      setEntitiesTab("Classes");
      setSelectedItem(targetNode);

      if (detail.action === "sibling") {
        const parent = detail.parentId
          ? findClassNodeById(detail.parentId)
          : findParentNode(classHierarchy, targetNode.id);
        setClassParentLabel(parent?.label || detail.parentLabel || "owl:Thing");
        setAddClassType("sibling");
      } else {
        setClassParentLabel(targetNode.label);
        setAddClassType("subclass");
      }

      setAddClassDialogOpen(true);
    };

    window.addEventListener("graph-view:add-class", handleGraphAddClass as EventListener);
    return () => window.removeEventListener("graph-view:add-class", handleGraphAddClass as EventListener);
  }, [classHierarchy, findClassNodeById, projectId, showNotification, isProjectDraftEditorRole, syncMode]);

  // Listen for classes created directly by the graph plugin (S6 fix)
  useEffect(() => {
    const handleGraphClassCreated = (event: Event) => {
      const custom = event as CustomEvent<{
        id: string;
        label: string;
        parentId: string;
        projectId?: string;
      }>;
      const detail = custom.detail;
      if (!detail) return;
      if (detail.projectId && projectId && detail.projectId !== projectId) return;

      const newNode: TreeNode = {
        id: detail.id,
        label: detail.label,
        children: [],
        hasChildren: false,
        annotations: { "rdfs:label": detail.label },
      };

      setClassHierarchy((prev) => {
        const addChild = (nodes: TreeNode[]): TreeNode[] =>
          nodes.map((node) => {
            if (node.id === detail.parentId) {
              return { ...node, children: [...(node.children || []), newNode], hasChildren: true };
            }
            if (node.children) {
              return { ...node, children: addChild(node.children) };
            }
            return node;
          });
        return addChild(prev);
      });

      setMetadata((prev) => (prev ? { ...prev, classCount: (prev.classCount || 0) + 1 } : prev));
      markAsUnsaved();
    };

    window.addEventListener("graph-view:class-created", handleGraphClassCreated as EventListener);
    return () => window.removeEventListener("graph-view:class-created", handleGraphClassCreated as EventListener);
  }, [projectId, markAsUnsaved]);

  useEffect(() => {
    const handleGraphDelete = (event: Event) => {
      const custom = event as CustomEvent<{
        nodeId: string;
        nodeLabel?: string;
        projectId?: string;
      }>;
      const detail = custom.detail;
      if (!detail) return;
      if (detail.projectId && projectId && detail.projectId !== projectId) {
        return;
      }

      const targetNode = findClassNodeById(detail.nodeId);
      if (!targetNode) {
        showNotification(`Class "${detail.nodeLabel || detail.nodeId}" not found in hierarchy.`, "warning");
        return;
      }

      setMainTab("Entities");
      setEntitiesTab("Classes");
      setSelectedItem(targetNode);
      handleDeleteItem(targetNode, "Classes");
    };

    window.addEventListener("graph-view:delete-class", handleGraphDelete as EventListener);
    return () => window.removeEventListener("graph-view:delete-class", handleGraphDelete as EventListener);
  }, [findClassNodeById, handleDeleteItem, projectId, showNotification]);

  // Keyboard shortcuts (Protégé-style) - must be after handleAddItem and handleDeleteItem
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Ignore if user is typing in input/textarea
      const target = e.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable) {
        return;
      }

      // Only handle shortcuts when Entities tab is active
      if (mainTab !== "Entities") return;

      // F2 - Rename (works for all entity types)
      if (e.key === "F2" && selectedItem) {
        e.preventDefault();
        // Trigger rename by posting message to EntityHierarchy
        // We'll use a custom event since we can't directly access EntityHierarchy's state
        const renameEvent = new CustomEvent("triggerRename", { detail: { itemId: selectedItem.id } });
        window.dispatchEvent(renameEvent);
        return;
      }

      // Other shortcuts only for Classes tab
      if (entitiesTab !== "Classes") return;

      // Ctrl+E (tooltip) or Ctrl+\ - Add Subclass
      if ((e.ctrlKey || e.metaKey) && (e.key === "e" || e.key === "E" || e.key === "\\")) {
        e.preventDefault();
        handleAddItem("subclass");
      }
      // Ctrl+/ or Cmd+/ - Add Sibling
      else if ((e.ctrlKey || e.metaKey) && e.key === "/") {
        e.preventDefault();
        handleAddItem("sibling");
      }
      // Ctrl+Backspace or Cmd+Backspace - Delete
      else if ((e.ctrlKey || e.metaKey) && e.key === "Backspace") {
        e.preventDefault();
        handleDeleteItem();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [mainTab, entitiesTab, handleAddItem, handleDeleteItem, selectedItem]);

  // Undo / Redo (Protégé-style) — rolls back via Change Assistant history API
  useEffect(() => {
    if (!projectId) return;

    const handleUndoRedo = async (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable) {
        return;
      }
      if (!(e.ctrlKey || e.metaKey)) return;

      const userId = user?.email || "anonymous";
      const username = user?.username || "Anonymous";
      const isRedo = e.key === "y" || (e.key === "z" && e.shiftKey);

      if (e.key === "z" && !e.shiftKey) {
        e.preventDefault();
        const result = await undoRedoService.undo(projectId, userId, username);
        if (result.success) {
          notificationService.success("Undo", "Undid last change");
        } else if (result.error) {
          notificationService.info("Undo", result.error);
        }
      } else if (isRedo) {
        e.preventDefault();
        const result = await undoRedoService.redo(projectId, userId, username);
        if (result.success) {
          notificationService.success("Redo", "Redid change");
        } else if (result.error) {
          notificationService.info("Redo", result.error);
        }
      }
    };

    window.addEventListener("keydown", handleUndoRedo);
    return () => window.removeEventListener("keydown", handleUndoRedo);
  }, [projectId, user]);

  const handleExecuteDlQuery = () => {
    setIsDlQueryLoading(true);
    setDlQueryResults(null);
    setTimeout(() => {
      if (dlQuery.toLowerCase().includes("pizza")) {
        const pizzaResults = individuals.filter((i) => i.label.toLowerCase().includes("pizza")).map((i) => i.label);
        setDlQueryResults(
          pizzaResults.length > 0 ? pizzaResults : ["MargheritaPizza", "AmericanHotPizza", "SohoPizza"],
        );
      } else {
        setDlQueryResults([]);
      }
      setIsDlQueryLoading(false);
    }, 1500);
  };

  // Optional helper to “add to ontology” (safe no-op if backend route is missing)
  const handleAddDlToOntology = useCallback(async () => {
    if (!projectId || !dlQuery.trim()) return;
    try {
      await ontologyMutationService.addDlQueryClass(projectId, dlQuery, dlQuery, user?.email);
      console.log("DL expression submitted to backend.");
    } catch (e) {
      console.warn("DL add endpoint not available; skipping.", e);
    }
  }, [projectId, dlQuery, user?.email]);
  // #endregion

  // #region Render Methods
  const fetchCodeViewContent = useCallback(
    async (
      format: "rdfxml" | "turtle" | "ntriples" | "owlxml" | "manchester" | "functional" | "jsonld",
      forceRefresh: boolean = false,
      forceReload: boolean = false,
    ) => {
      if (!projectId) return;

      // Clear any previous syntax error / lint warnings when loading new content
      setCodeViewSyntaxError(null);
      setCodeViewLintIssues([]);

      // If clicking same format without force refresh/reload, just return (prevents unnecessary reloads)
      if (format === codeViewFormat && !forceRefresh && !forceReload && codeViewContent) {
        console.log("[Dashboard] Same format clicked, content already loaded");
        return;
      }

      // If force refresh, clear the cache so we get fresh content from GraphDB
      if (forceRefresh) {
        console.log("[Dashboard] Force refresh - clearing code view cache");
        try {
          await apiClient.delete(`/api/ontology/${projectId}/code-view-cache`);
          console.log("[Dashboard] Code view cache cleared");
        } catch (cacheError) {
          console.warn("[Dashboard] Failed to clear code view cache:", cacheError);
        }
      }

      setCodeViewLoading(true);
      try {
        // Desktop is OWLAPI-first with lazy Fuseki sync — Code View's /content endpoint always
        // exports from Fuseki (it has no OWLAPI-aware read path), so without this it can show
        // stale content whenever OWLAPI has edits Fuseki hasn't caught up on yet (e.g. right
        // after a class add/delete). No-ops instantly off-desktop.
        if (isDesktop()) {
          await ensureDesktopFusekiSync(projectId);
        }

        // Probe the paged endpoint first: it reports the total serialized size without
        // shipping the whole document, so a 200MB ontology never crosses the wire as one
        // JSON string. Small files fall through to the normal full-content fetch below
        // (now a server-side cache hit, since the probe generated the cache file).
        // Older backends without /content-page fall through too.
        try {
          const probe = await apiClient.get<{
            success: boolean;
            content: string;
            startLine: number;
            lineCount: number;
            totalLines: number;
            totalBytes: number;
          }>(`/api/ontology/${projectId}/content-page`, {
            format,
            startLine: "0",
            lineCount: String(CODE_VIEW_PAGE_LINES),
          });
          if (probe?.success && Number(probe.totalBytes) > CODE_VIEW_LARGE_FILE_BYTES) {
            setCodeViewContent(probe.content ?? "");
            setCodeViewPage({
              startLine: 0,
              lineCount: Number(probe.lineCount) || 0,
              totalLines: Number(probe.totalLines) || 0,
              totalBytes: Number(probe.totalBytes) || 0,
            });
            setCodeViewTruncation(null);
            setCodeViewFormat(format);
            setHasLocalCodeViewChanges(false);
            codeViewDirtyRef.current = false;
            return;
          }
        } catch (probeError) {
          console.warn("[Dashboard] content-page probe unavailable, using full content path:", probeError);
        }
        setCodeViewPage(null);

        const response = await apiClient.get<{
          success: boolean;
          content: string;
          format: string;
          cached?: boolean;
          error?: string;
        }>(`/api/ontology/${projectId}/content`, { format, forceRefresh: forceRefresh ? "true" : "false" });
        if (response.success) {
          // Guard the editor against huge documents (see codeViewTruncation).
          // Above 10M chars (≈10MB serialized) switch to a read-only preview,
          // capped at 10k lines so the per-line gutter rendering stays snappy.
          // Cut on a line boundary so the preview never ends mid-statement.
          const CODE_VIEW_MAX_CHARS = 10 * 1024 * 1024;
          const CODE_VIEW_PREVIEW_LINES = 10_000;
          let content = response.content ?? "";
          if (content.length > CODE_VIEW_MAX_CHARS) {
            const totalChars = content.length;
            let cut = -1;
            let previewLines = 0;
            for (let i = 0; i < CODE_VIEW_MAX_CHARS; i++) {
              if (content.charCodeAt(i) === 10) {
                cut = i;
                previewLines++;
                if (previewLines >= CODE_VIEW_PREVIEW_LINES) break;
              }
            }
            if (cut <= 0) cut = CODE_VIEW_MAX_CHARS;
            content = content.slice(0, cut);
            setCodeViewTruncation({ totalChars, previewLines: Math.max(previewLines, 1) });
          } else {
            setCodeViewTruncation(null);
          }
          setCodeViewContent(content);
          setCodeViewFormat(format);
          setHasLocalCodeViewChanges(false);
          codeViewDirtyRef.current = false;
          if (response.cached) {
            console.log("[Dashboard] Content loaded from cache (line positions preserved)");
          } else {
            console.log("[Dashboard] Content loaded fresh from GraphDB");
          }
        } else {
          console.error("[Dashboard] Code view content fetch returned success=false:", response.error);
          setCodeViewTruncation(null);
          setCodeViewContent(
            `// Error loading ${format} content: ${response.error || "Unknown error"}\n// Try using Turtle or RDF/XML format instead.`,
          );
          setCodeViewFormat(format);
        }
      } catch (error: any) {
        console.error("Failed to fetch code view content:", error);
        const msg = error?.message || error?.toString() || "Unknown error";
        setCodeViewTruncation(null);
        setCodeViewContent(
          `// Error loading ${format} content: ${msg}\n// The backend may not support this format for this ontology.\n// Try using Turtle or RDF/XML format instead.`,
        );
        setCodeViewFormat(format);
      } finally {
        setCodeViewLoading(false);
      }
    },
    [projectId, codeViewFormat, codeViewContent],
  );

  // Navigate to another window of a large document in paged Code View mode.
  const loadCodeViewPage = useCallback(
    async (startLine: number) => {
      if (!projectId || !codeViewPage) return;
      const clamped = Math.max(0, Math.min(startLine, Math.max(0, codeViewPage.totalLines - 1)));
      setCodeViewLoading(true);
      try {
        const res = await apiClient.get<{
          success: boolean;
          content: string;
          startLine: number;
          lineCount: number;
          totalLines: number;
          totalBytes: number;
          error?: string;
        }>(`/api/ontology/${projectId}/content-page`, {
          format: codeViewFormat,
          startLine: String(clamped),
          lineCount: String(CODE_VIEW_PAGE_LINES),
        });
        if (res?.success) {
          setCodeViewContent(res.content ?? "");
          setCodeViewPage({
            startLine: clamped,
            lineCount: Number(res.lineCount) || 0,
            totalLines: Number(res.totalLines) || 0,
            totalBytes: Number(res.totalBytes) || 0,
          });
        } else {
          notificationService.error("Load Failed", res?.error || "Could not load this section of the file.");
        }
      } catch (error: any) {
        console.error("[Dashboard] Failed to load code view page:", error);
        notificationService.error("Load Failed", error?.message || "Could not load this section of the file.");
      } finally {
        setCodeViewLoading(false);
      }
    },
    [projectId, codeViewFormat, codeViewPage],
  );

  // Download the complete serialized file for the current Code View format —
  // the editing path for documents too large to edit in the browser.
  const downloadFullCodeViewFile = useCallback(() => {
    if (!projectId) return;
    const extByFormat: Record<string, string> = {
      rdfxml: "owl", turtle: "ttl", ntriples: "nt", owlxml: "owlxml",
      manchester: "omn", functional: "ofn", jsonld: "jsonld",
    };
    const ext = extByFormat[codeViewFormat] || "owl";
    const filename = `${projectId}.${ext}`;
    const url = `${getBaseUrl()}/api/ontology/export/${encodeURIComponent(projectId)}?format=${codeViewFormat}`;
    if (window.vscode) {
      window.vscode.postMessage({ type: "downloadOntology", url, filename, projectId, format: codeViewFormat });
      notificationService.success("Export Started", `Downloading ${filename}`);
    } else {
      window.open(url, "_blank");
    }
  }, [projectId, codeViewFormat]);

  // Citation insertion handlers
  const handleCitationSelection = useCallback((citation: any) => {
    if (citation === "manual") {
      setPendingCitation(null);
      setCitationInsertionMode(false);
      setShowCitationPicker(false);
      setShowManualCitationDialog(true);
      return;
    }

    // Set up for search-based insertion
    setPendingCitation(citation);
    setCitationInsertionMode(true);
    setShowCitationPicker(false);
  }, []);

  const handleManualCitationSubmit = useCallback((citationData: any) => {
    // Format manual citation data to match CitationItem interface
    const manualCitation = {
      key: `manual_${Date.now()}`,
      title: citationData.title,
      data: {
        title: citationData.title,
        creators: citationData.authors.split(",").map((author: string) => {
          const parts = author.trim().split(" ");
          return {
            firstName: parts.slice(0, -1).join(" "),
            lastName: parts[parts.length - 1] || "",
            creatorType: "author",
          };
        }),
        date: citationData.year,
        doi: citationData.doi,
        url: citationData.url,
        itemType: citationData.itemType,
        publicationTitle: citationData.publicationTitle,
      },
    };

    console.log("[Dashboard] Manual citation created:", manualCitation);

    // Set up for search-based insertion (same flow as Zotero citations)
    setPendingCitation(manualCitation);
    setCitationInsertionMode(true);
    setShowManualCitationDialog(false);

    console.log("[Dashboard] Citation insertion mode enabled - search for location to insert");
  }, []);

  // Handle code content changes from editable code view
  const handleCodeContentChange = useCallback(
    (newContent: string) => {
      setCodeViewContent(newContent);
      setHasLocalCodeViewChanges(true);
      if (codeViewFormat === "jsonld") {
        // JSON-LD errors are cheap to detect (JSON.parse) — validate live as the user types
        // so the error gutter/banner in CodeHighlighter updates immediately, not just on save.
        setCodeViewSyntaxError(validateJsonLdSyntax(newContent));
      } else {
        setCodeViewSyntaxError(null); // clear error as user edits; re-validated on save
      }
      console.log("[Dashboard] Code view content updated via editing");
    },
    [codeViewFormat],
  );

  // Handle saving code content to backend
  const handleSaveCodeContent = useCallback(
    async (content: string, skipLintCheck: boolean = false) => {
      // Free-plan non-owners cannot edit or save — show the Pro upgrade dialog
      if (isViewOnlyMember) {
        setShowProPromptType('edit');
        return;
      }

      // Code-view save does a whole-ontology reimport into the PUBLIC/main graph
      // (bulkLoadChunked) — it has no draft-graph path. In the WEBAPP, saving it while in
      // Draft mode would overwrite the shared public ontology, so block it there.
      // Desktop is single-user and ALWAYS in "private" mode (Protégé-style, Save-to-publish);
      // there is no shared public graph to protect and code-view save is a normal desktop
      // operation, so it must NOT be blocked on desktop.
      if (!isDesktop() && ontologyMutationService.isPrivateEditMode()) {
        notificationService.error(
          "Not available in Draft Mode",
          "Source (code view) editing writes to the public ontology and isn't supported in Draft Mode. Switch to Public mode to edit source, or use the entity editors to make draft changes.",
        );
        return;
      }

      if (!projectId) {
        console.error("[Dashboard] No projectId available for save");
        notificationService.error("Save Failed", "No project selected");
        return;
      }

      // Hard block, not just UI-disabled: the editor holds only a window/preview
      // of a large file, and code-view save replaces the whole graph — saving
      // here would silently delete everything outside the visible portion.
      if (codeViewTruncation || codeViewPage) {
        notificationService.error(
          "Save Disabled",
          "This file is too large to edit in Code View — only a preview is shown. Export the file, edit it externally, and re-upload.",
        );
        return;
      }

      // ── Client-side validation before sending to backend ─────────────────
      // This catches common parse errors instantly without a round-trip.
      if (codeViewFormat === 'rdfxml' || codeViewFormat === 'owlxml') {
        // Use the browser's built-in XML parser
        const parser = new DOMParser();
        const doc = parser.parseFromString(content, 'application/xml');
        const parseErrorEl = doc.querySelector('parsererror');
        if (parseErrorEl) {
          // Extract the text — browsers include line/column info in the text
          const rawErr = parseErrorEl.textContent || 'Invalid XML structure';
          // Trim verbose Gecko/WebKit prefix so only the useful message shows
          const cleanErr = rawErr
            .replace(/^.*?error\s*:\s*/i, '')
            .replace(/^This page contains the following errors:\s*/i, '')
            .trim();
          console.error('[Dashboard] Client-side XML validation failed:', cleanErr);
          setCodeViewSyntaxError(cleanErr);
          notificationService.error('XML Validation Error', 'Fix the highlighted error before saving.');
          return;
        }
      } else if (codeViewFormat === 'turtle' || codeViewFormat === 'ntriples') {
        // Heuristic: non-empty Turtle/N-Triples files must have at least one triple-terminating dot
        const trimmed = content.trim();
        if (trimmed && !trimmed.includes('.')) {
          const msg = 'Turtle/N-Triples content appears malformed: no statement-terminating dot (.) found.';
          setCodeViewSyntaxError(msg);
          notificationService.error('Validation Error', msg);
          return;
        }
      } else if (codeViewFormat === 'jsonld') {
        const err = validateJsonLdSyntax(content);
        if (err) {
          console.error('[Dashboard] Client-side JSON-LD validation failed:', err);
          setCodeViewSyntaxError(err);
          notificationService.error('JSON-LD Validation Error', 'Fix the highlighted error before saving.');
          return;
        }
      }
      // ─────────────────────────────────────────────────────────────────────

      // ── Content lint: best-effort semantic checks the parser above can't catch ──
      // A missing '#' or an undeclared-but-referenced entity is still syntactically
      // valid XML/Turtle/etc., so it sails through the checks above. This is a
      // warning, not a hard stop — show it and let the user decide whether to fix
      // first or save anyway (e.g. a deliberate cross-ontology reference).
      if (!skipLintCheck) {
        const issues = lintOntologyContent(content, codeViewFormat);
        if (issues.length > 0) {
          setCodeViewLintIssues(issues);
          lastCodeViewSaveContentRef.current = content;
          return;
        }
      }
      setCodeViewLintIssues([]);

      // Remembered so the "Retry Save" button in SaveErrorDialog can resubmit the exact
      // content that failed, even if the user keeps typing while the dialog is open.
      lastCodeViewSaveContentRef.current = content;
      setSavingCodeView(true);
      try {
        console.log(
          "[Dashboard] Saving code view content to backend, format:",
          codeViewFormat,
          "size:",
          content.length,
        );

        // Single path: reimport into the ontology and sync all format caches. No cache-only
        // fallback — a save that didn't reach the ontology must never look like it succeeded,
        // since Graph View / Hierarchy Tree / DL Query all read from the ontology, not this cache.
        let response: any;
        try {
          response = await apiClient.post(`/api/ontology/${projectId}/code-view-save`, {
            content: content,
            format: codeViewFormat,
          });
        } catch (syncError: any) {
          const errMsg = syncError?.message || "Failed to reach the save endpoint";
          console.error("[Dashboard] code-view-save request failed:", errMsg);
          setCodeViewSaveError(errMsg);
          return;
        }

        if (response.success) {
          console.log("[MUTATION:code-save] ✓ synced — refreshing hierarchy+properties");
          notificationService.success("Saved", "Code content saved and synced across all formats");
          setHasLocalCodeViewChanges(false);
          setCodeViewSyntaxError(null);
          setCodeViewSaveError(null);
          // The ontology file was rewritten — reload all entity views to reflect the new state
          lastClassHierarchyRefreshAt.current = 0;
          refreshClassHierarchy();
          refreshProperties();
          // Let other open views (Graph View plugin, etc.) know the ontology changed so they
          // can drop their caches and refetch too — mirrors ontologyMutationService's broadcast
          // for normal entity-editor mutations, which this save path bypasses (it POSTs directly
          // to code-view-save, not through applyMutations()).
          try {
            window.dispatchEvent(new CustomEvent("ontology:mutated", {
              detail: { projectId, ops: ["codeViewSave"] },
            }));
          } catch {
            /* non-fatal */
          }
        } else {
          const errMsg = (response.error || "Failed to save content").replace(
            "Failed to save and sync code view: ",
            "",
          );
          console.error("[Dashboard] Save failed:", errMsg);
          setCodeViewSaveError(errMsg);
        }
      } catch (error: any) {
        console.error("[Dashboard] Error saving code content:", error);
        setCodeViewSaveError(error.message || "Failed to save content to backend");
      } finally {
        setSavingCodeView(false);
      }
    },
    [projectId, codeViewFormat, isViewOnlyMember, codeViewTruncation, codeViewPage, setShowProPromptType, refreshClassHierarchy, refreshProperties],
  );

  // Handle insertion at selected location in code view
  const handleInsertCitationAtLocation = useCallback(
    async (lineNumber: number) => {
      if (!pendingCitation || !codeViewContent) {
        console.error("[Dashboard] Missing pendingCitation or codeViewContent");
        return;
      }

      // Show loading notification
      notificationService.info("Inserting Citation", "Adding citation to all formats...");

      let insertAtIndex = 0; // Declare at function scope

      try {
        // Extract citation data - Zotero citations have data nested in .data property
        const citationData = pendingCitation.data || pendingCitation;
        const citationKey = pendingCitation.key || `citation_${Date.now()}`;

        // Split code into lines
        const lines = codeViewContent.split("\n");

        console.log("[Dashboard] Citation insertion triggered at line:", lineNumber);
        console.log("[Dashboard] Total lines in content:", lines.length);
        console.log("[Dashboard] Pending citation:", pendingCitation);
        console.log("[Dashboard] Citation data extracted:", citationData);

        // Ensure line number is within bounds (lineNumber is 0-based)
        insertAtIndex = Math.max(0, Math.min(lineNumber, lines.length));
        console.log("[Dashboard] Citation will be inserted at index:", insertAtIndex);

        // Extract entity name/IRI from the clicked line for reference in other formats
        // Supports ALL OWL constructs: classes, properties, individuals, axioms, restrictions, annotations
        const clickedLine = lines[Math.min(lineNumber, lines.length - 1)] || "";
        let referencedEntity = "";

        // ========== COMPREHENSIVE OWL ELEMENT EXTRACTION ==========
        // PRIORITY 1: RDF/XML and OWL/XML attribute patterns (extract URLs first)
        const rdfAboutMatch = clickedLine.match(/rdf:about="([^"]+)"/);
        const rdfIdMatch = clickedLine.match(/rdf:ID="([^"]+)"/);
        const rdfResourceMatch = clickedLine.match(/rdf:resource="([^"]+)"/);
        const owlXmlIriMatch = clickedLine.match(/\bIRI="([^"]+)"/);
        const owlXmlAbbrevMatch = clickedLine.match(/abbreviatedIRI="([^"]+)"/);
        // JSON-LD node identifier, e.g. "@id": "http://example.org/Foo"
        const jsonLdIdMatch = clickedLine.match(/"@id"\s*:\s*"([^"]+)"/);

        // PRIORITY 2: Full URI patterns (all formats) - MUST be a valid URI, not an XML tag
        // Only match URIs that start with http(s):, urn:, file:, or contain ://
        const fullUriMatch = clickedLine.match(/<((?:https?|urn|file):[^\s>]+|[^\s>]*:\/\/[^\s>]+)>/);

        // PRIORITY 3: Prefixed name patterns (Turtle, Manchester) - only if no URL found
        const prefixedNameMatch = clickedLine.match(/\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\b/);

        // 4. Manchester syntax patterns (Class:, Individual:, ObjectProperty:, etc.)
        const manchesterDeclMatch = clickedLine.match(
          /(?:Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):\s*([<:][^\s]+|[a-zA-Z_][a-zA-Z0-9_:-]*)/,
        );

        // 5. Functional syntax patterns - extract from Declaration, ClassAssertion, etc.
        const functionalEntityMatch = clickedLine.match(
          /(?:Declaration|ClassAssertion|ObjectPropertyAssertion|DataPropertyAssertion|AnnotationAssertion|SubClassOf|EquivalentClasses|DisjointClasses|SubObjectPropertyOf|EquivalentObjectProperties|SubDataPropertyOf|ObjectPropertyDomain|ObjectPropertyRange|DataPropertyDomain|DataPropertyRange|SameIndividual|DifferentIndividuals)\s*\(\s*(?:[^(]*\()?\s*([<][^>]+[>]|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*)/,
        );

        // 6. OWL axiom patterns in Turtle (SubClassOf, EquivalentClass, etc.)
        const owlAxiomMatch = clickedLine.match(
          /(?:owl:equivalentClass|owl:disjointWith|rdfs:subClassOf|rdfs:subPropertyOf|owl:inverseOf|owl:propertyChainAxiom|owl:hasKey)\s+([<][^>]+[>]|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*)/,
        );

        // 7. Property restriction patterns
        const restrictionMatch = clickedLine.match(
          /(?:owl:onProperty|owl:someValuesFrom|owl:allValuesFrom|owl:hasValue|owl:onClass|owl:onDataRange|owl:minCardinality|owl:maxCardinality|owl:cardinality|owl:minQualifiedCardinality|owl:maxQualifiedCardinality|owl:qualifiedCardinality)\s+([<][^>]+[>]|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*|\d+)/,
        );

        // 8. Annotation patterns
        const annotationMatch = clickedLine.match(
          /(?:rdfs:label|rdfs:comment|rdfs:seeAlso|rdfs:isDefinedBy|owl:versionInfo|dc:title|dc:description|dc:creator|skos:prefLabel|skos:altLabel|skos:definition|skos:example|skos:note)\s+(?:"[^"]*"|([<][^>]+[>]|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*))/,
        );

        // 9. SWRL rule patterns
        const swrlMatch = clickedLine.match(
          /(?:swrl:body|swrl:head|swrl:argument1|swrl:argument2|swrl:classPredicate|swrl:propertyPredicate)\s+([<][^>]+[>]|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*)/,
        );

        // 10. RDF/XML OWL element tags
        const xmlOwlElementMatch = clickedLine.match(
          /<owl:(Class|ObjectProperty|DatatypeProperty|AnnotationProperty|NamedIndividual|Restriction|AllDifferent|AllDisjointClasses|AllDisjointProperties|NegativePropertyAssertion|Datatype|FunctionalProperty|InverseFunctionalProperty|TransitiveProperty|SymmetricProperty|AsymmetricProperty|ReflexiveProperty|IrreflexiveProperty)/,
        );

        // 11. Import declaration - comprehensive pattern for all formats
        // Matches: owl:imports <URI>, Import(<URI>), Import: <URI>, rdf:resource in owl:imports context
        const importMatch = clickedLine.match(/(?:owl:imports|Import)\s*[:(]?\s*<([^>]+)>/);

        // 12. Datatype patterns
        const datatypeMatch = clickedLine.match(
          /\^\^([<][^>]+[>]|xsd:[a-zA-Z]+|[a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*)/,
        );

        // 14. N-Triples subject pattern
        const ntriplesSubjectMatch = clickedLine.match(/^([<][^>]+[>])\s+[<]/);

        // Priority order for entity extraction - XML attributes FIRST (most precise for URLs)
        console.log("[Dashboard] Entity extraction patterns - checking in priority order...");
        console.log("[Dashboard] Clicked line:", clickedLine.substring(0, 150));

        // PRIORITY 1: XML attribute patterns (extract full URLs from rdf:about, IRI, etc.)
        if (rdfAboutMatch) {
          referencedEntity = rdfAboutMatch[1];
          console.log("[Dashboard] ✓ Extracted from rdf:about:", referencedEntity);
        } else if (rdfIdMatch) {
          referencedEntity = rdfIdMatch[1];
          console.log("[Dashboard] ✓ Extracted from rdf:ID:", referencedEntity);
        } else if (owlXmlIriMatch) {
          referencedEntity = owlXmlIriMatch[1];
          console.log("[Dashboard] ✓ Extracted from IRI attribute:", referencedEntity);
        } else if (owlXmlAbbrevMatch) {
          referencedEntity = owlXmlAbbrevMatch[1];
          console.log("[Dashboard] ✓ Extracted from abbreviatedIRI:", referencedEntity);
        } else if (rdfResourceMatch) {
          referencedEntity = rdfResourceMatch[1];
          console.log("[Dashboard] ✓ Extracted from rdf:resource:", referencedEntity);
        } else if (jsonLdIdMatch) {
          referencedEntity = jsonLdIdMatch[1];
          console.log("[Dashboard] ✓ Extracted from JSON-LD @id:", referencedEntity);
        }
        // PRIORITY 2: Import declarations (HIGH priority for import lines)
        else if (importMatch) {
          referencedEntity = importMatch[1];
          console.log("[Dashboard] ✓ Extracted from import declaration:", referencedEntity);
        }
        // PRIORITY 3: Full URI in angle brackets
        else if (fullUriMatch) {
          referencedEntity = fullUriMatch[1];
          console.log("[Dashboard] ✓ Extracted full URI from angle brackets:", referencedEntity);
        }
        // PRIORITY 4: N-Triples subject (full URI)
        else if (ntriplesSubjectMatch) {
          referencedEntity = ntriplesSubjectMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from N-Triples subject:", referencedEntity);
        }
        // PRIORITY 5: Format-specific declarations
        else if (manchesterDeclMatch) {
          referencedEntity = manchesterDeclMatch[1].replace(/^[<:]/, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from Manchester declaration:", referencedEntity);
        } else if (functionalEntityMatch) {
          referencedEntity = functionalEntityMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from Functional syntax:", referencedEntity);
        }
        // PRIORITY 6: OWL axioms and properties
        else if (owlAxiomMatch) {
          referencedEntity = owlAxiomMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from OWL axiom:", referencedEntity);
        } else if (restrictionMatch && !restrictionMatch[1].match(/^\d+$/)) {
          referencedEntity = restrictionMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from restriction:", referencedEntity);
        } else if (annotationMatch && annotationMatch[1]) {
          referencedEntity = annotationMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from annotation:", referencedEntity);
        } else if (swrlMatch) {
          referencedEntity = swrlMatch[1].replace(/^</, "").replace(/>$/, "");
          console.log("[Dashboard] ✓ Extracted from SWRL rule:", referencedEntity);
        }
        // PRIORITY 7: Prefixed names (lowest priority)
        else if (prefixedNameMatch) {
          referencedEntity = prefixedNameMatch[1];
          console.log("[Dashboard] ✓ Extracted prefixed name:", referencedEntity);
        }
        // PRIORITY 7: XML element tags (look nearby for entity reference)
        else if (xmlOwlElementMatch) {
          console.log("[Dashboard] Found OWL XML element tag, looking for nearby entity...");
          // For OWL element tags, look for rdf:about or rdf:ID on same or nearby lines
          const nearbyLines = lines
            .slice(Math.max(0, lineNumber - 2), Math.min(lines.length, lineNumber + 3))
            .join(" ");
          const nearbyAbout = nearbyLines.match(/rdf:about="([^"]+)"/);
          const nearbyId = nearbyLines.match(/rdf:ID="([^"]+)"/);
          if (nearbyAbout) {
            referencedEntity = nearbyAbout[1];
            console.log("[Dashboard] ✓ Extracted from nearby rdf:about:", referencedEntity);
          } else if (nearbyId) {
            referencedEntity = nearbyId[1];
            console.log("[Dashboard] ✓ Extracted from nearby rdf:ID:", referencedEntity);
          }
        }

        if (!referencedEntity) {
          console.log("[Dashboard] ✗ No entity found from primary patterns, trying fallbacks...");
        }

        // AGGRESSIVE FALLBACK: If still no entity found, extract any IRI or prefixed name from the line
        if (!referencedEntity) {
          // Try to find any full IRI in angle brackets (must be a URL, not an XML tag)
          // Must start with http(s): or urn: or file: or contain :// to be considered a URI
          const anyIriMatch = clickedLine.match(/<((?:https?|urn|file):[^\s>]+|[^\s>]*:\/\/[^\s>]+)>/);
          if (anyIriMatch) {
            referencedEntity = anyIriMatch[1];
            console.log("[Dashboard] FALLBACK: Extracted IRI from angle brackets:", referencedEntity);
          }

          // Try to find any prefixed name (e.g., ex:Person, foaf:name)
          if (!referencedEntity) {
            const anyPrefixedMatch = clickedLine.match(/\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]+)\b/);
            if (
              anyPrefixedMatch &&
              !anyPrefixedMatch[1].startsWith("http:") &&
              !anyPrefixedMatch[1].startsWith("https:")
            ) {
              referencedEntity = anyPrefixedMatch[1];
              console.log("[Dashboard] FALLBACK: Extracted prefixed name:", referencedEntity);
            }
          }

          // Super aggressive fallback: look at nearby lines for context (URLs only)
          if (!referencedEntity && lineNumber >= 0) {
            console.log("[Dashboard] SUPER FALLBACK: Searching nearby lines (±3) for entity URL...");
            const contextLines = lines.slice(Math.max(0, lineNumber - 3), Math.min(lines.length, lineNumber + 3));
            for (let i = 0; i < contextLines.length; i++) {
              const contextLine = contextLines[i];
              // ONLY try to extract URLs from XML attributes (most precise)
              const contextAbout = contextLine.match(/rdf:about="([^"]+)"/);
              const contextIri = contextLine.match(/IRI="([^"]+)"/);
              const contextResource = contextLine.match(/rdf:resource="([^"]+)"/);

              if (contextAbout) {
                referencedEntity = contextAbout[1];
                console.log(
                  `[Dashboard] SUPER FALLBACK: Found rdf:about in line ${lineNumber - 3 + i}:`,
                  referencedEntity,
                );
                break;
              } else if (contextIri) {
                referencedEntity = contextIri[1];
                console.log(`[Dashboard] SUPER FALLBACK: Found IRI in line ${lineNumber - 3 + i}:`, referencedEntity);
                break;
              } else if (contextResource) {
                referencedEntity = contextResource[1];
                console.log(
                  `[Dashboard] SUPER FALLBACK: Found rdf:resource in line ${lineNumber - 3 + i}:`,
                  referencedEntity,
                );
                break;
              }
            }

            // If still nothing, try angle brackets for URLs
            if (!referencedEntity) {
              for (let i = 0; i < contextLines.length; i++) {
                const contextLine = contextLines[i];
                // Only match actual URIs, not XML tags
                const contextIri = contextLine.match(/<((?:https?|urn|file):[^\s>]+|[^\s>]*:\/\/[^\s>]+)>/);
                if (contextIri) {
                  referencedEntity = contextIri[1];
                  console.log("[Dashboard] SUPER FALLBACK: Found IRI in nearby line:", referencedEntity);
                  break;
                }
                // Try prefixed name
                const contextPrefixed = contextLine.match(/\b([a-zA-Z_][a-zA-Z0-9_-]*:[a-zA-Z_][a-zA-Z0-9_-]*)\b/);
                if (
                  contextPrefixed &&
                  !contextPrefixed[1].startsWith("http:") &&
                  !contextPrefixed[1].startsWith("https:")
                ) {
                  referencedEntity = contextPrefixed[1];
                  console.log("[Dashboard] SUPER FALLBACK: Found prefixed name in nearby line:", referencedEntity);
                  break;
                }
              }
            }
          }
        } // Close aggressive fallback if (!referencedEntity) block started around line 7936

        console.log("[Dashboard] ========== ENTITY EXTRACTION DEBUG ==========");
        console.log("[Dashboard] Clicked line content:", clickedLine);
        console.log("[Dashboard] Format:", codeViewFormat);
        console.log("[Dashboard] Referenced entity extracted:", referencedEntity || "(none detected)");
        console.log("[Dashboard] =============================================");

        // Helper function to escape Turtle strings
        const escapeTurtle = (str: string): string => {
          if (!str) return "";
          return str
            .replace(/\\/g, "\\\\")
            .replace(/"/g, '\\"')
            .replace(/\n/g, "\\n")
            .replace(/\r/g, "\\r")
            .replace(/\t/g, "\\t");
        };

        // Helper function to escape XML strings
        const escapeXml = (str: string): string => {
          if (!str) return "";
          return str
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&apos;");
        };

        // Extract all citation fields
        const title = citationData.title || "Untitled";
        const authors =
          citationData.creators?.map((c: any) => `${c.firstName || ""} ${c.lastName || ""}`.trim()).join(", ") ||
          "Unknown";
        const year = citationData.date ? citationData.date.match(/\d{4}/)?.[0] || "" : "";
        const doi = citationData.DOI || citationData.doi || "";
        const url = citationData.url || "";
        const abstractNote = citationData.abstractNote || "";
        const publicationTitle = citationData.publicationTitle || "";
        const volume = citationData.volume || "";
        const issue = citationData.issue || "";
        const pages = citationData.pages || "";
        const publisher = citationData.publisher || "";
        const itemType = citationData.itemType || "document";
        const tags = citationData.tags?.map((t: any) => t.tag || t).filter(Boolean) || [];
        const isbn = citationData.ISBN || "";
        const issn = citationData.ISSN || "";
        const language = citationData.language || "";
        const rights = citationData.rights || "";

        // Generate complete citation block
        const citationLines: string[] = [];

        if (codeViewFormat === "turtle" || codeViewFormat === "ntriples") {
          // Generate comprehensive Turtle format with all Zotero details
          citationLines.push("");
          citationLines.push("###  Zotero Citation: " + escapeTurtle(title));
          citationLines.push(
            `<urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}> rdf:type owl:NamedIndividual ,`,
          );
          citationLines.push("         prov:Entity ;");
          citationLines.push(`    dc:title "${escapeTurtle(title)}" ;`);
          citationLines.push(`    dc:creator "${escapeTurtle(authors)}" ;`);

          if (year) {
            citationLines.push(`    dc:date "${year}"^^xsd:gYear ;`);
          }

          if (publicationTitle) {
            citationLines.push(`    dc:source "${escapeTurtle(publicationTitle)}" ;`);
          }

          if (publisher) {
            citationLines.push(`    dc:publisher "${escapeTurtle(publisher)}" ;`);
          }

          if (doi) {
            citationLines.push(`    dc:identifier "doi:${escapeTurtle(doi)}" ;`);
            citationLines.push(`    bibo:doi "${escapeTurtle(doi)}" ;`);
          }

          if (isbn) {
            citationLines.push(`    bibo:isbn "${escapeTurtle(isbn)}" ;`);
          }

          if (issn) {
            citationLines.push(`    bibo:issn "${escapeTurtle(issn)}" ;`);
          }

          if (url) {
            citationLines.push(`    foaf:homepage <${url}> ;`);
          }

          if (volume) {
            citationLines.push(`    bibo:volume "${escapeTurtle(volume)}" ;`);
          }

          if (issue) {
            citationLines.push(`    bibo:issue "${escapeTurtle(issue)}" ;`);
          }

          if (pages) {
            citationLines.push(`    bibo:pages "${escapeTurtle(pages)}" ;`);
          }

          if (language) {
            citationLines.push(`    dc:language "${escapeTurtle(language)}" ;`);
          }

          if (rights) {
            citationLines.push(`    dc:rights "${escapeTurtle(rights)}" ;`);
          }

          if (itemType) {
            citationLines.push(`    dc:type "${escapeTurtle(itemType)}" ;`);
          }

          // Add tags as dc:subject
          if (tags.length > 0) {
            tags.forEach((tag: string) => {
              citationLines.push(`    dc:subject "${escapeTurtle(tag)}" ;`);
            });
          }

          // Add full abstract (not truncated)
          if (abstractNote) {
            citationLines.push(`    dc:description "${escapeTurtle(abstractNote)}" ;`);
          }

          // Replace last semicolon with period
          citationLines[citationLines.length - 1] = citationLines[citationLines.length - 1].replace(/ ;$/, " .");
          citationLines.push("");
        } else if (codeViewFormat === "manchester") {
          // Generate Manchester OWL syntax format
          const escManchester = (s: string) => s.replace(/"/g, '\\"').replace(/\n/g, " ");
          citationLines.push("");
          citationLines.push(`# Zotero Citation: ${title}`);
          citationLines.push(`Individual: <urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}>`);
          citationLines.push(`    Types: prov:Entity`);
          citationLines.push(`    Annotations:`);
          citationLines.push(`        dc:title "${escManchester(title)}",`);
          citationLines.push(
            `        dc:creator "${escManchester(authors)}"${year || publicationTitle || doi || abstractNote ? "," : ""}`,
          );
          if (year)
            citationLines.push(
              `        dc:date "${year}"^^xsd:gYear${publicationTitle || doi || abstractNote ? "," : ""}`,
            );
          if (publicationTitle)
            citationLines.push(
              `        dc:source "${escManchester(publicationTitle)}"${doi || abstractNote ? "," : ""}`,
            );
          if (publisher)
            citationLines.push(`        dc:publisher "${escManchester(publisher)}"${doi || abstractNote ? "," : ""}`);
          if (doi) citationLines.push(`        bibo:doi "${escManchester(doi)}"${abstractNote ? "," : ""}`);
          if (url) citationLines.push(`        foaf:homepage <${url}>${abstractNote ? "," : ""}`);
          if (abstractNote) citationLines.push(`        dc:description "${escManchester(abstractNote)}"`);
          citationLines.push("");
        } else if (codeViewFormat === "functional") {
          // Generate OWL 2 Functional syntax format
          const escFunc = (s: string) => s.replace(/"/g, '\\"').replace(/\n/g, " ");
          const citUri = `<urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}>`;
          citationLines.push("");
          citationLines.push(`# Zotero Citation: ${title}`);
          citationLines.push(`Declaration(NamedIndividual(${citUri}))`);
          citationLines.push(`ClassAssertion(<http://www.w3.org/ns/prov#Entity> ${citUri})`);
          citationLines.push(
            `AnnotationAssertion(<http://purl.org/dc/elements/1.1/title> ${citUri} "${escFunc(title)}")`,
          );
          citationLines.push(
            `AnnotationAssertion(<http://purl.org/dc/elements/1.1/creator> ${citUri} "${escFunc(authors)}")`,
          );
          if (year)
            citationLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/date> ${citUri} "${year}"^^xsd:gYear)`,
            );
          if (publicationTitle)
            citationLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/source> ${citUri} "${escFunc(publicationTitle)}")`,
            );
          if (publisher)
            citationLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/publisher> ${citUri} "${escFunc(publisher)}")`,
            );
          if (doi)
            citationLines.push(`AnnotationAssertion(<http://purl.org/ontology/bibo/doi> ${citUri} "${escFunc(doi)}")`);
          if (url) citationLines.push(`AnnotationAssertion(<http://xmlns.com/foaf/0.1/homepage> ${citUri} <${url}>)`);
          if (abstractNote)
            citationLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/description> ${citUri} "${escFunc(abstractNote)}")`,
            );
          citationLines.push("");
        } else if (codeViewFormat === "owlxml") {
          // Generate OWL/XML format
          citationLines.push("");
          citationLines.push(`    <!-- Zotero Citation: ${escapeXml(title)} -->`);
          citationLines.push(`    <Declaration>`);
          citationLines.push(
            `        <NamedIndividual IRI="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}"/>`,
          );
          citationLines.push(`    </Declaration>`);
          citationLines.push(`    <ClassAssertion>`);
          citationLines.push(`        <Class IRI="http://www.w3.org/ns/prov#Entity"/>`);
          citationLines.push(
            `        <NamedIndividual IRI="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}"/>`,
          );
          citationLines.push(`    </ClassAssertion>`);
          citationLines.push(`    <AnnotationAssertion>`);
          citationLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/title"/>`);
          citationLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
          citationLines.push(`        <Literal>${escapeXml(title)}</Literal>`);
          citationLines.push(`    </AnnotationAssertion>`);
          citationLines.push(`    <AnnotationAssertion>`);
          citationLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/creator"/>`);
          citationLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
          citationLines.push(`        <Literal>${escapeXml(authors)}</Literal>`);
          citationLines.push(`    </AnnotationAssertion>`);
          if (year) {
            citationLines.push(`    <AnnotationAssertion>`);
            citationLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/date"/>`);
            citationLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
            citationLines.push(
              `        <Literal datatypeIRI="http://www.w3.org/2001/XMLSchema#gYear">${year}</Literal>`,
            );
            citationLines.push(`    </AnnotationAssertion>`);
          }
          if (doi) {
            citationLines.push(`    <AnnotationAssertion>`);
            citationLines.push(`        <AnnotationProperty IRI="http://purl.org/ontology/bibo/doi"/>`);
            citationLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
            citationLines.push(`        <Literal>${escapeXml(doi)}</Literal>`);
            citationLines.push(`    </AnnotationAssertion>`);
          }
          if (abstractNote) {
            citationLines.push(`    <AnnotationAssertion>`);
            citationLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/description"/>`);
            citationLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
            citationLines.push(`        <Literal>${escapeXml(abstractNote)}</Literal>`);
            citationLines.push(`    </AnnotationAssertion>`);
          }
          citationLines.push("");
        } else if (codeViewFormat === "rdfxml") {
          // Generate comprehensive RDF/XML format with all Zotero details
          citationLines.push("");
          citationLines.push(`    <!-- Zotero Citation: ${escapeXml(title)} -->`);
          citationLines.push(
            `    <owl:NamedIndividual rdf:about="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}">`,
          );
          citationLines.push(`        <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>`);
          citationLines.push(`        <dc:title>${escapeXml(title)}</dc:title>`);
          citationLines.push(`        <dc:creator>${escapeXml(authors)}</dc:creator>`);

          if (year) {
            citationLines.push(
              `        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#gYear">${year}</dc:date>`,
            );
          }

          if (publicationTitle) {
            citationLines.push(`        <dc:source>${escapeXml(publicationTitle)}</dc:source>`);
          }

          if (publisher) {
            citationLines.push(`        <dc:publisher>${escapeXml(publisher)}</dc:publisher>`);
          }

          if (doi) {
            citationLines.push(`        <dc:identifier>doi:${escapeXml(doi)}</dc:identifier>`);
            citationLines.push(
              `        <bibo:doi xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(doi)}</bibo:doi>`,
            );
          }

          if (isbn) {
            citationLines.push(
              `        <bibo:isbn xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(isbn)}</bibo:isbn>`,
            );
          }

          if (issn) {
            citationLines.push(
              `        <bibo:issn xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(issn)}</bibo:issn>`,
            );
          }

          if (url) {
            citationLines.push(`        <foaf:homepage rdf:resource="${escapeXml(url)}"/>`);
          }

          if (volume) {
            citationLines.push(
              `        <bibo:volume xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(volume)}</bibo:volume>`,
            );
          }

          if (issue) {
            citationLines.push(
              `        <bibo:issue xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(issue)}</bibo:issue>`,
            );
          }

          if (pages) {
            citationLines.push(
              `        <bibo:pages xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(pages)}</bibo:pages>`,
            );
          }

          if (language) {
            citationLines.push(`        <dc:language>${escapeXml(language)}</dc:language>`);
          }

          if (rights) {
            citationLines.push(`        <dc:rights>${escapeXml(rights)}</dc:rights>`);
          }

          if (itemType) {
            citationLines.push(`        <dc:type>${escapeXml(itemType)}</dc:type>`);
          }

          // Add tags as dc:subject
          if (tags.length > 0) {
            tags.forEach((tag: string) => {
              citationLines.push(`        <dc:subject>${escapeXml(tag)}</dc:subject>`);
            });
          }

          // Add full abstract (not truncated)
          if (abstractNote) {
            citationLines.push(`        <dc:description>${escapeXml(abstractNote)}</dc:description>`);
          }

          citationLines.push(`    </owl:NamedIndividual>`);
          citationLines.push("");
        }

        console.log("[Dashboard] Inserting full citation details at index:", insertAtIndex);
        console.log("[Dashboard] Citation lines count:", citationLines.length);
        console.log("[Dashboard] Total lines before insertion:", lines.length);

        let modifiedContent: string;

        if (codeViewFormat === "jsonld") {
          // JSON-LD is not line-oriented — splicing raw text at insertAtIndex would very
          // likely break the JSON. Instead, parse the document, figure out which @graph node
          // the clicked line falls inside (so the citation lands right after it, mirroring how
          // the other formats insert near the clicked entity), and re-serialize.
          const citationNode = buildZoteroCitationNode({
            key: citationKey,
            title,
            authors,
            year,
            doi,
            url,
            abstractNote,
            publicationTitle,
            volume,
            issue,
            pages,
            publisher,
            itemType,
            tags,
            isbn,
            issn,
            language,
            rights,
          });
          const afterIndex = findGraphInsertionIndex(codeViewContent, lineNumber);
          modifiedContent = insertCitationNodeIntoJsonLd(codeViewContent, citationNode, afterIndex);
          console.log("[Dashboard] JSON-LD citation node inserted into @graph after index", afterIndex ?? "(end)");
        } else {
          // For RDF/XML and OWL/XML formats, ensure insertion is AFTER the root element opening tag
          // to prevent "Content is not allowed in prolog" errors
          if (codeViewFormat === "rdfxml" || codeViewFormat === "owlxml") {
            // Find the line with the opening <rdf:RDF> or <Ontology> root element
            let rootElementLine = -1;
            for (let i = 0; i < Math.min(50, lines.length); i++) {
              const trimmed = lines[i].trim();
              if (
                trimmed.startsWith("<rdf:RDF") ||
                trimmed.startsWith("<Ontology") ||
                trimmed.startsWith("<owl:Ontology")
              ) {
                rootElementLine = i;
                break;
              }
            }

            // Find the actual closing > of the root tag (may span multiple lines with xmlns)
            let rootTagCloseLine = rootElementLine;
            if (rootElementLine >= 0) {
              for (let j = rootElementLine; j < Math.min(rootElementLine + 100, lines.length); j++) {
                if (lines[j].includes(">")) {
                  const lastQuote = lines[j].lastIndexOf('"');
                  const lastGt = lines[j].lastIndexOf(">");
                  if (lastGt > lastQuote || lastQuote === -1) {
                    rootTagCloseLine = j;
                    break;
                  }
                }
              }
            }

            if (rootElementLine >= 0 && insertAtIndex <= rootTagCloseLine) {
              // User clicked before or inside the root element opening tag - insert after it
              insertAtIndex = rootTagCloseLine + 1;
              console.log(
                `[Dashboard] ${codeViewFormat.toUpperCase()}: Adjusted insertion to line`,
                insertAtIndex,
                "to respect XML structure",
              );
            }

            // Also check if trying to insert after the closing tag
            for (let i = lines.length - 1; i >= Math.max(0, lines.length - 10); i--) {
              const trimmed = lines[i].trim();
              if (trimmed === "</rdf:RDF>" || trimmed === "</Ontology>" || trimmed === "</owl:Ontology>") {
                if (insertAtIndex > i) {
                  insertAtIndex = i; // Insert before the closing tag
                  console.log(
                    `[Dashboard] ${codeViewFormat.toUpperCase()}: Adjusted insertion to line`,
                    insertAtIndex,
                    "to stay inside root element",
                  );
                }
                break;
              }
            }
          }

          console.log("[Dashboard] Final insertion index after adjustments:", insertAtIndex);

          // Insert the citation details AT the adjusted line
          lines.splice(insertAtIndex, 0, ...citationLines);

          console.log("[Dashboard] Total lines after insertion:", lines.length);

          // Create modified content with the citation details inserted
          modifiedContent = lines.join("\n");
        }
        console.log("[Dashboard] Modified content length:", modifiedContent.length, "bytes");

        // Step 1: Update local code view immediately for UX feedback
        setCodeViewContent(modifiedContent);
        setHasLocalCodeViewChanges(true); // Mark that we have local modifications
        console.log("[Dashboard] Code view updated locally with full citation at line", insertAtIndex);

        // Step 2: Cache the modified content and insert citation in ALL formats
        // The modified content is stored in the code view cache, which the export endpoint will use
        // This preserves the exact line positions (no GraphDB re-serialization)
        // Current format: at clicked line, Other formats: near the referenced entity
        console.log(
          "[Dashboard] Inserting citation - current format at line",
          insertAtIndex,
          ", other formats near entity:",
          referencedEntity || "(none)",
        );

        // Define all supported formats for multi-format sync
        const allFormats = ["turtle", "rdfxml", "ntriples", "owlxml", "manchester", "functional", "jsonld"] as const;
        const otherFormats = allFormats.filter((f) => f !== codeViewFormat);

        // Helper to generate citation in Turtle format
        function generateTurtleCitationBlock(): string[] {
          const citLines: string[] = [];
          citLines.push("");
          citLines.push("###  Zotero Citation: " + escapeTurtle(title));
          citLines.push(`<urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}> rdf:type owl:NamedIndividual ,`);
          citLines.push("         prov:Entity ;");
          citLines.push(`    dc:title "${escapeTurtle(title)}" ;`);
          citLines.push(`    dc:creator "${escapeTurtle(authors)}" ;`);
          if (year) citLines.push(`    dc:date "${year}"^^xsd:gYear ;`);
          if (publicationTitle) citLines.push(`    dc:source "${escapeTurtle(publicationTitle)}" ;`);
          if (publisher) citLines.push(`    dc:publisher "${escapeTurtle(publisher)}" ;`);
          if (doi) {
            citLines.push(`    dc:identifier "doi:${escapeTurtle(doi)}" ;`);
            citLines.push(`    bibo:doi "${escapeTurtle(doi)}" ;`);
          }
          if (isbn) citLines.push(`    bibo:isbn "${escapeTurtle(isbn)}" ;`);
          if (issn) citLines.push(`    bibo:issn "${escapeTurtle(issn)}" ;`);
          if (url) citLines.push(`    foaf:homepage <${url}> ;`);
          if (volume) citLines.push(`    bibo:volume "${escapeTurtle(volume)}" ;`);
          if (issue) citLines.push(`    bibo:issue "${escapeTurtle(issue)}" ;`);
          if (pages) citLines.push(`    bibo:pages "${escapeTurtle(pages)}" ;`);
          if (language) citLines.push(`    dc:language "${escapeTurtle(language)}" ;`);
          if (rights) citLines.push(`    dc:rights "${escapeTurtle(rights)}" ;`);
          if (itemType) citLines.push(`    dc:type "${escapeTurtle(itemType)}" ;`);
          tags.forEach((tag: string) => {
            citLines.push(`    dc:subject "${escapeTurtle(tag)}" ;`);
          });
          if (abstractNote) citLines.push(`    dc:description "${escapeTurtle(abstractNote)}" ;`);
          citLines[citLines.length - 1] = citLines[citLines.length - 1].replace(/ ;$/, " .");
          citLines.push("");
          return citLines;
        }

        // Helper to generate citation in RDF/XML format
        function generateRdfXmlCitationBlock(): string[] {
          const citLines: string[] = [];
          citLines.push("");
          citLines.push(`    <!-- Zotero Citation: ${escapeXml(title)} -->`);
          citLines.push(
            `    <owl:NamedIndividual rdf:about="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}">`,
          );
          citLines.push(`        <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>`);
          citLines.push(`        <dc:title>${escapeXml(title)}</dc:title>`);
          citLines.push(`        <dc:creator>${escapeXml(authors)}</dc:creator>`);
          if (year)
            citLines.push(`        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#gYear">${year}</dc:date>`);
          if (publicationTitle) citLines.push(`        <dc:source>${escapeXml(publicationTitle)}</dc:source>`);
          if (publisher) citLines.push(`        <dc:publisher>${escapeXml(publisher)}</dc:publisher>`);
          if (doi) {
            citLines.push(`        <dc:identifier>doi:${escapeXml(doi)}</dc:identifier>`);
            citLines.push(`        <bibo:doi xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(doi)}</bibo:doi>`);
          }
          if (isbn)
            citLines.push(
              `        <bibo:isbn xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(isbn)}</bibo:isbn>`,
            );
          if (issn)
            citLines.push(
              `        <bibo:issn xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(issn)}</bibo:issn>`,
            );
          if (url) citLines.push(`        <foaf:homepage rdf:resource="${escapeXml(url)}"/>`);
          if (volume)
            citLines.push(
              `        <bibo:volume xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(volume)}</bibo:volume>`,
            );
          if (issue)
            citLines.push(
              `        <bibo:issue xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(issue)}</bibo:issue>`,
            );
          if (pages)
            citLines.push(
              `        <bibo:pages xmlns:bibo="http://purl.org/ontology/bibo/">${escapeXml(pages)}</bibo:pages>`,
            );
          if (language) citLines.push(`        <dc:language>${escapeXml(language)}</dc:language>`);
          if (rights) citLines.push(`        <dc:rights>${escapeXml(rights)}</dc:rights>`);
          if (itemType) citLines.push(`        <dc:type>${escapeXml(itemType)}</dc:type>`);
          tags.forEach((tag: string) => {
            citLines.push(`        <dc:subject>${escapeXml(tag)}</dc:subject>`);
          });
          if (abstractNote) citLines.push(`        <dc:description>${escapeXml(abstractNote)}</dc:description>`);
          citLines.push(`    </owl:NamedIndividual>`);
          citLines.push("");
          return citLines;
        }

        // Helper to generate citation in N-Triples format
        function generateNTriplesCitationBlock(): string[] {
          const citLines: string[] = [];
          const uri = `<urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}>`;
          const escNt = (s: string) =>
            s.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n").replace(/\r/g, "\\r");
          citLines.push("");
          citLines.push(`# Zotero Citation: ${title}`);
          citLines.push(
            `${uri} <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#NamedIndividual> .`,
          );
          citLines.push(
            `${uri} <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/prov#Entity> .`,
          );
          citLines.push(`${uri} <http://purl.org/dc/elements/1.1/title> "${escNt(title)}" .`);
          citLines.push(`${uri} <http://purl.org/dc/elements/1.1/creator> "${escNt(authors)}" .`);
          if (year)
            citLines.push(
              `${uri} <http://purl.org/dc/elements/1.1/date> "${year}"^^<http://www.w3.org/2001/XMLSchema#gYear> .`,
            );
          if (publicationTitle)
            citLines.push(`${uri} <http://purl.org/dc/elements/1.1/source> "${escNt(publicationTitle)}" .`);
          if (publisher) citLines.push(`${uri} <http://purl.org/dc/elements/1.1/publisher> "${escNt(publisher)}" .`);
          if (doi) {
            citLines.push(`${uri} <http://purl.org/dc/elements/1.1/identifier> "doi:${escNt(doi)}" .`);
            citLines.push(`${uri} <http://purl.org/ontology/bibo/doi> "${escNt(doi)}" .`);
          }
          if (isbn) citLines.push(`${uri} <http://purl.org/ontology/bibo/isbn> "${escNt(isbn)}" .`);
          if (url) citLines.push(`${uri} <http://xmlns.com/foaf/0.1/homepage> <${url}> .`);
          if (abstractNote)
            citLines.push(`${uri} <http://purl.org/dc/elements/1.1/description> "${escNt(abstractNote)}" .`);
          citLines.push("");
          return citLines;
        }

        // Helper to generate citation in Manchester syntax format
        function generateManchesterCitationBlock(): string[] {
          const citLines: string[] = [];
          const escManchester = (s: string) => s.replace(/"/g, '\\"').replace(/\n/g, " ");
          citLines.push("");
          citLines.push(`# Zotero Citation: ${title}`);
          citLines.push(`Individual: <urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}>`);
          citLines.push(`    Types: prov:Entity`);
          citLines.push(`    Annotations:`);
          citLines.push(`        dc:title "${escManchester(title)}",`);
          citLines.push(
            `        dc:creator "${escManchester(authors)}"${year || publicationTitle || doi || abstractNote ? "," : ""}`,
          );
          if (year)
            citLines.push(`        dc:date "${year}"^^xsd:gYear${publicationTitle || doi || abstractNote ? "," : ""}`);
          if (publicationTitle)
            citLines.push(`        dc:source "${escManchester(publicationTitle)}"${doi || abstractNote ? "," : ""}`);
          if (doi) citLines.push(`        bibo:doi "${escManchester(doi)}"${abstractNote ? "," : ""}`);
          if (abstractNote) citLines.push(`        dc:description "${escManchester(abstractNote)}"`);
          citLines.push("");
          return citLines;
        }

        // Helper to generate citation in OWL Functional syntax format
        function generateFunctionalCitationBlock(): string[] {
          const citLines: string[] = [];
          const escFunc = (s: string) => s.replace(/"/g, '\\"').replace(/\n/g, " ");
          const citUri = `<urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}>`;
          citLines.push("");
          citLines.push(`# Zotero Citation: ${title}`);
          citLines.push(`Declaration(NamedIndividual(${citUri}))`);
          citLines.push(`ClassAssertion(<http://www.w3.org/ns/prov#Entity> ${citUri})`);
          citLines.push(`AnnotationAssertion(<http://purl.org/dc/elements/1.1/title> ${citUri} "${escFunc(title)}")`);
          citLines.push(
            `AnnotationAssertion(<http://purl.org/dc/elements/1.1/creator> ${citUri} "${escFunc(authors)}")`,
          );
          if (year)
            citLines.push(`AnnotationAssertion(<http://purl.org/dc/elements/1.1/date> ${citUri} "${year}"^^xsd:gYear)`);
          if (publicationTitle)
            citLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/source> ${citUri} "${escFunc(publicationTitle)}")`,
            );
          if (doi)
            citLines.push(`AnnotationAssertion(<http://purl.org/ontology/bibo/doi> ${citUri} "${escFunc(doi)}")`);
          if (abstractNote)
            citLines.push(
              `AnnotationAssertion(<http://purl.org/dc/elements/1.1/description> ${citUri} "${escFunc(abstractNote)}")`,
            );
          citLines.push("");
          return citLines;
        }

        // Helper to generate citation in OWL/XML format
        function generateOwlXmlCitationBlock(): string[] {
          const citLines: string[] = [];
          citLines.push("");
          citLines.push(`    <!-- Zotero Citation: ${escapeXml(title)} -->`);
          citLines.push(`    <Declaration>`);
          citLines.push(`        <NamedIndividual IRI="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}"/>`);
          citLines.push(`    </Declaration>`);
          citLines.push(`    <ClassAssertion>`);
          citLines.push(`        <Class IRI="http://www.w3.org/ns/prov#Entity"/>`);
          citLines.push(`        <NamedIndividual IRI="urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}"/>`);
          citLines.push(`    </ClassAssertion>`);
          citLines.push(`    <AnnotationAssertion>`);
          citLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/title"/>`);
          citLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
          citLines.push(`        <Literal>${escapeXml(title)}</Literal>`);
          citLines.push(`    </AnnotationAssertion>`);
          citLines.push(`    <AnnotationAssertion>`);
          citLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/creator"/>`);
          citLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
          citLines.push(`        <Literal>${escapeXml(authors)}</Literal>`);
          citLines.push(`    </AnnotationAssertion>`);
          if (year) {
            citLines.push(`    <AnnotationAssertion>`);
            citLines.push(`        <AnnotationProperty IRI="http://purl.org/dc/elements/1.1/date"/>`);
            citLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
            citLines.push(`        <Literal datatypeIRI="http://www.w3.org/2001/XMLSchema#gYear">${year}</Literal>`);
            citLines.push(`    </AnnotationAssertion>`);
          }
          if (doi) {
            citLines.push(`    <AnnotationAssertion>`);
            citLines.push(`        <AnnotationProperty IRI="http://purl.org/ontology/bibo/doi"/>`);
            citLines.push(`        <IRI>urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}</IRI>`);
            citLines.push(`        <Literal>${escapeXml(doi)}</Literal>`);
            citLines.push(`    </AnnotationAssertion>`);
          }
          citLines.push("");
          return citLines;
        }

        // Store modified content for current format (already modified with citation at clicked line)
        // Note: We do NOT clear caches first - this preserves previously added citations
        // Also store citation-entity mapping for smart repositioning when exporting from GraphDB
        const citationUrn = `urn:citation:${citationKey.replace(/[^a-zA-Z0-9]/g, "")}`;
        try {
          await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
            content: modifiedContent,
            format: codeViewFormat,
            citationUrn: citationUrn,
            referencedEntity: referencedEntity || "",
          });
          console.log("[Dashboard] Current format cache stored:", codeViewFormat);
          console.log("[Dashboard] Stored citation-entity mapping:", citationUrn, "->", referencedEntity || "(none)");
        } catch (e) {
          console.warn("[Dashboard] Failed to store current format cache:", e);
        }

        // Now fetch and update ALL other formats with citation near the same entity
        // Helper to find entity location in content - supports ALL OWL formats
        function findEntityLocation(content: string, entity: string): number {
          if (!entity) {
            console.log("[findEntityLocation] No entity provided");
            return -1;
          }

          const lines = content.split("\n");

          // Extract just the local name from the entity for matching
          // If entity has a fragment (#), use that; else use last path segment
          let localName = entity.includes("#")
            ? entity.split("#").pop() || ""
            : entity.includes("/")
              ? entity.split("/").pop() || ""
              : entity.includes(":") && !entity.includes("://")
                ? entity.split(":").pop() || ""
                : entity;

          // Clean up any trailing quotes or special characters
          localName = localName.replace(/["'>]+$/, "").replace(/^["'<]+/, "");

          // Also extract the prefix if it's a prefixed name
          const prefix = entity.includes(":") && !entity.includes("://") ? entity.split(":")[0] : "";

          console.log(`[findEntityLocation] ========== SEARCHING FOR ENTITY ==========`);
          console.log(`[findEntityLocation] Full entity: '${entity}'`);
          console.log(`[findEntityLocation] Local name: '${localName}'`);
          console.log(`[findEntityLocation] Prefix: '${prefix || "(none)"}'`);
          console.log(`[findEntityLocation] Total lines to search: ${lines.length}`);
          console.log(`[findEntityLocation] =============================================`);

          // Escape special regex characters in entity and localName
          const escapeRegex = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
          const safeEntity = escapeRegex(entity);
          const safeLocalName = escapeRegex(localName);
          const safePrefix = prefix ? escapeRegex(prefix) : "";

          // Build comprehensive search patterns for ALL formats - ORDERED BY PRIORITY
          const patterns: Array<{ pattern: string | RegExp; desc: string; priority: number }> = [
            // HIGHEST PRIORITY (100): Exact full entity match
            { pattern: entity, desc: "Exact entity string", priority: 100 },
            { pattern: `<${entity}>`, desc: "Entity in angle brackets", priority: 99 },

            // HIGH PRIORITY (90-95): Exact entity in XML/RDF attributes
            { pattern: `rdf:about="${entity}"`, desc: "RDF about attribute", priority: 95 },
            { pattern: `rdf:resource="${entity}"`, desc: "RDF resource attribute", priority: 94 },
            { pattern: `IRI="${entity}"`, desc: "OWL/XML IRI attribute", priority: 93 },
            ...(prefix
              ? [{ pattern: `abbreviatedIRI="${prefix}:${localName}"`, desc: "OWL/XML abbreviated IRI", priority: 92 }]
              : []),

            // MEDIUM-HIGH PRIORITY (80-89): Prefixed name matches
            ...(prefix
              ? [
                  {
                    pattern: new RegExp(`\\b${safePrefix}:${safeLocalName}(?![a-zA-Z0-9_-])`),
                    desc: "Exact prefixed name",
                    priority: 88,
                  },
                ]
              : []),
            {
              pattern: new RegExp(`\\b[a-zA-Z_][a-zA-Z0-9_-]*:${safeLocalName}(?![a-zA-Z0-9_-])`),
              desc: "Any prefix with local name",
              priority: 85,
            },

            // MEDIUM PRIORITY (70-79): Fragment/path patterns for URIs
            ...(entity.includes("#") || entity.includes("/")
              ? [
                  { pattern: `#${localName}>`, desc: "Fragment in angle brackets", priority: 78 },
                  { pattern: `#${localName}`, desc: "Fragment reference", priority: 77 },
                  { pattern: `/${localName}>`, desc: "Path in angle brackets", priority: 76 },
                  { pattern: `/${localName}`, desc: "Path reference", priority: 75 },
                ]
              : []),

            // MEDIUM-LOW PRIORITY (60-69): Fragment/local name in attributes
            { pattern: `rdf:about="#${localName}"`, desc: "RDF about with fragment", priority: 68 },
            { pattern: `rdf:ID="${localName}"`, desc: "RDF ID attribute", priority: 67 },
            { pattern: `rdf:resource="#${localName}"`, desc: "RDF resource with fragment", priority: 66 },
            { pattern: `IRI="#${localName}"`, desc: "IRI with fragment", priority: 65 },
            {
              pattern: new RegExp(`abbreviatedIRI="[^"]*:${safeLocalName}"`),
              desc: "Abbreviated IRI any prefix",
              priority: 64,
            },

            // LOW PRIORITY (40-59): Declaration patterns
            {
              pattern: new RegExp(
                `(?:Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):\\s*<[^>]*${safeLocalName}>`,
              ),
              desc: "Manchester declaration with IRI",
              priority: 55,
            },
            {
              pattern: new RegExp(
                `(?:Class|Individual|ObjectProperty|DataProperty):\\s*[a-zA-Z_][a-zA-Z0-9_-]*:${safeLocalName}(?![a-zA-Z0-9_-])`,
              ),
              desc: "Manchester declaration prefixed",
              priority: 54,
            },
            {
              pattern: new RegExp(`Declaration\\s*\\([^)]*<[^>]*${safeLocalName}>`),
              desc: "Functional declaration",
              priority: 53,
            },
            {
              pattern: new RegExp(
                `(?:NamedIndividual|Class|ObjectProperty|DataProperty)\\s*\\(<[^>]*${safeLocalName}>`,
              ),
              desc: "Functional construct",
              priority: 52,
            },
            ...(prefix
              ? [
                  {
                    pattern: new RegExp(
                      `(?:Declaration|ClassAssertion|SubClassOf)\\s*\\([^)]*${safePrefix}:${safeLocalName}`,
                    ),
                    desc: "Functional with prefix",
                    priority: 51,
                  },
                ]
              : []),

            // VERY LOW PRIORITY (20-39): N-Triples and word boundary
            { pattern: new RegExp(`^<[^>]*${safeLocalName}>\\s+<`), desc: "N-Triples subject", priority: 35 },
            {
              pattern: new RegExp(`\\b${safeLocalName}\\b`, "i"),
              desc: "Word boundary match (case-insensitive)",
              priority: 20,
            },
          ];

          let bestMatch = -1;
          let bestMatchPriority = -1;
          let bestMatchDesc = "";
          let matchCount = 0;

          for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            const line = lines[lineIdx];

            for (const { pattern, desc, priority } of patterns) {
              const matched = pattern instanceof RegExp ? pattern.test(line) : line.includes(pattern);

              if (matched) {
                matchCount++;
                console.log(
                  `[findEntityLocation] ✓ Match #${matchCount} at line ${lineIdx} (priority ${priority}): ${desc}`,
                );
                console.log(
                  `[findEntityLocation]   Line preview: ${line.substring(0, 120)}${line.length > 120 ? "..." : ""}`,
                );

                // Keep track of best match (highest priority, or first if same priority)
                if (priority > bestMatchPriority) {
                  bestMatch = lineIdx;
                  bestMatchPriority = priority;
                  bestMatchDesc = desc;
                  console.log(`[findEntityLocation]   >>> NEW BEST MATCH (priority ${priority})`);

                  // If we found a very high-priority match (>= 90), use it immediately
                  if (priority >= 90) {
                    console.log(
                      `[findEntityLocation] High-priority match found (${priority} >= 90), using immediately`,
                    );
                    const endLine = findEntityBlockEnd(lines, lineIdx);
                    console.log(`[findEntityLocation] ========== MATCH FOUND ==========`);
                    console.log(`[findEntityLocation] Line: ${lineIdx}, End: ${endLine}, Reason: ${desc}`);
                    console.log(`[findEntityLocation] ===================================`);
                    return endLine;
                  }
                }
              }
            }
          }

          // If we found any match, use the best one
          if (bestMatch >= 0) {
            const endLine = findEntityBlockEnd(lines, bestMatch);
            console.log(`[findEntityLocation] ========== BEST MATCH FOUND ==========`);
            console.log(`[findEntityLocation] Total matches: ${matchCount}`);
            console.log(`[findEntityLocation] Best match line: ${bestMatch}`);
            console.log(`[findEntityLocation] Best match priority: ${bestMatchPriority}`);
            console.log(`[findEntityLocation] Best match reason: ${bestMatchDesc}`);
            console.log(`[findEntityLocation] Inserting after line: ${endLine}`);
            console.log(`[findEntityLocation] =====================================`);
            return endLine;
          }

          console.log(`[findEntityLocation] ========== NO MATCH FOUND ==========`);
          console.log(`[findEntityLocation] Entity '${entity}' not found in ${lines.length} lines`);
          console.log(`[findEntityLocation] Showing first 10 lines for debugging:`);
          for (let i = 0; i < Math.min(10, lines.length); i++) {
            console.log(`[findEntityLocation]   Line ${i}: ${lines[i].substring(0, 100)}`);
          }
          console.log(`[findEntityLocation] ===================================`);

          return -1;
        }

        // Helper to find the end of an entity's definition block
        function findEntityBlockEnd(lines: string[], startLine: number): number {
          const line = lines[startLine];

          // Detect format based on line content
          const isTurtle = line.includes("@prefix") || line.match(/^\s*[<:a-zA-Z].*[;.]$/);
          const isXml =
            line.includes("<owl:") || line.includes("<rdf:") || line.includes("<rdfs:") || line.includes("IRI=");
          const isManchester = line.match(/(?:Class|Individual|ObjectProperty|DataProperty):/);
          const isFunctional = line.match(/(?:Declaration|ClassAssertion|SubClassOf)\s*\(/);
          const isNTriples = line.match(/^<[^>]+>\s+<[^>]+>\s+/);

          // Find the end of this entity's definition block
          for (let j = startLine; j < lines.length; j++) {
            const checkLine = lines[j];

            // Turtle: ends with .
            if ((isTurtle || isNTriples) && checkLine.trim().endsWith(".") && !checkLine.trim().startsWith("@")) {
              return j + 1;
            }

            // XML: closing tag
            if (
              isXml &&
              (checkLine.includes("</owl:") ||
                checkLine.includes("</rdf:") ||
                checkLine.includes("</rdfs:") ||
                checkLine.includes("</Declaration>") ||
                checkLine.includes("</ClassAssertion>") ||
                checkLine.includes("</AnnotationAssertion>") ||
                checkLine.includes("</NamedIndividual>"))
            ) {
              return j + 1;
            }

            // Manchester: next declaration block starts
            if (
              isManchester &&
              j > startLine &&
              checkLine.match(/^(?:Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):/)
            ) {
              return j;
            }

            // Functional: closing parenthesis or next declaration
            if (isFunctional && j > startLine && (checkLine.match(/^\)/) || checkLine.match(/^[A-Z][a-zA-Z]+\s*\(/))) {
              return j;
            }

            // Blank line after definition
            if (j > startLine && checkLine.trim() === "") {
              return j;
            }

            // Safety: don't look more than 50 lines ahead
            if (j > startLine + 50) {
              return startLine + 1;
            }
          }

          return startLine + 1;
        }

        const succeededFormats: string[] = [codeViewFormat];
        const failedFormats: string[] = [];

        for (const fmt of otherFormats) {
          try {
            // Fetch cached content for this format to preserve existing citations
            let fmtContent: string | null = null;
            try {
              const response = await apiClient.get<{ success: boolean; content: string }>(
                `/api/ontology/${projectId}/content`,
                { format: fmt, forceRefresh: "false" },
              );
              if (response.success && response.content) {
                fmtContent = response.content;
              }
            } catch (fetchError) {
              console.warn(
                `[Dashboard] Could not fetch ${fmt} content from server, will generate minimal document:`,
                fetchError,
              );
            }

            // JSON-LD is not line-oriented — handle it structurally and skip the generic
            // skeleton/splice logic below entirely (which would corrupt JSON).
            if (fmt === "jsonld") {
              const jsonLdCitationNode = buildZoteroCitationNode({
                key: citationKey,
                title,
                authors,
                year,
                doi,
                url,
                abstractNote,
                publicationTitle,
                volume,
                issue,
                pages,
                publisher,
                itemType,
                tags,
                isbn,
                issn,
                language,
                rights,
              });
              const jsonLdBase = fmtContent || JSON.stringify({ "@context": DEFAULT_JSONLD_CONTEXT, "@graph": [] });
              const fmtModifiedContent = insertCitationNodeIntoJsonLd(jsonLdBase, jsonLdCitationNode);
              await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
                content: fmtModifiedContent,
                format: fmt,
                citationUrn: citationUrn,
                referencedEntity: referencedEntity || "",
              });
              console.log("[Dashboard] JSON-LD format cache stored with citation node");
              succeededFormats.push(fmt);
              continue;
            }

            // If we couldn't get format content, generate a minimal skeleton
            if (!fmtContent) {
              console.log(`[Dashboard] Generating minimal ${fmt} skeleton for citation storage`);
              if (fmt === "turtle") {
                fmtContent = `@prefix owl: <http://www.w3.org/2002/07/owl#> .\n@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n@prefix dc: <http://purl.org/dc/elements/1.1/> .\n@prefix bibo: <http://purl.org/ontology/bibo/> .\n\n`;
              } else if (fmt === "rdfxml") {
                fmtContent = `<?xml version="1.0"?>\n<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"\n    xmlns:owl="http://www.w3.org/2002/07/owl#"\n    xmlns:dc="http://purl.org/dc/elements/1.1/"\n    xmlns:bibo="http://purl.org/ontology/bibo/">\n\n</rdf:RDF>\n`;
              } else if (fmt === "owlxml") {
                fmtContent = `<?xml version="1.0"?>\n<Ontology xmlns="http://www.w3.org/2002/07/owl#"\n    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n\n</Ontology>\n`;
              } else if (fmt === "manchester") {
                fmtContent = `Prefix: owl: <http://www.w3.org/2002/07/owl#>\nPrefix: dc: <http://purl.org/dc/elements/1.1/>\nPrefix: bibo: <http://purl.org/ontology/bibo/>\n\nOntology:\n\n`;
              } else if (fmt === "functional") {
                fmtContent = `Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\nPrefix(dc:=<http://purl.org/dc/elements/1.1/>)\nPrefix(bibo:=<http://purl.org/ontology/bibo/>)\n\nOntology(\n\n)\n`;
              } else if (fmt === "ntriples") {
                fmtContent = ``;
              }
            }

            if (fmtContent !== null) {
              const fmtLines = fmtContent.split("\n");

              console.log(
                `[Dashboard] Processing format ${fmt}: ${fmtLines.length} lines, searching for entity: '${referencedEntity || "(none)"}'`,
              );

              // Generate citation in appropriate format
              let fmtCitationLines: string[] = [];
              if (fmt === "turtle") {
                fmtCitationLines = generateTurtleCitationBlock();
              } else if (fmt === "manchester") {
                fmtCitationLines = generateManchesterCitationBlock();
              } else if (fmt === "functional") {
                fmtCitationLines = generateFunctionalCitationBlock();
              } else if (fmt === "rdfxml") {
                fmtCitationLines = generateRdfXmlCitationBlock();
              } else if (fmt === "owlxml") {
                fmtCitationLines = generateOwlXmlCitationBlock();
              } else if (fmt === "ntriples") {
                fmtCitationLines = generateNTriplesCitationBlock();
              }

              // Find where to insert based on referenced entity
              let fmtInsertIndex = -1;
              if (referencedEntity) {
                console.log(`[Dashboard] Searching for entity '${referencedEntity}' in ${fmt} format...`);
                fmtInsertIndex = findEntityLocation(fmtContent, referencedEntity);
                if (fmtInsertIndex >= 0) {
                  console.log(`[Dashboard] ✓ Found entity '${referencedEntity}' at line ${fmtInsertIndex} in ${fmt}`);
                } else {
                  console.log(`[Dashboard] ✗ Entity '${referencedEntity}' NOT found in ${fmt}`);
                  // Show first few lines and clicked line for debugging
                  console.log(`[Dashboard] First 5 lines of ${fmt}:`, fmtLines.slice(0, 5).join("\n"));
                  console.log(`[Dashboard] Original clicked line:`, clickedLine.substring(0, 150));
                }
              }

              // If entity not found, insert near end of file (safer default than proportional positioning)
              if (fmtInsertIndex < 0) {
                // Find a safe insertion point near the end
                // For XML formats, this will be adjusted later to be before closing tags
                fmtInsertIndex = Math.max(0, fmtLines.length - 5);
                console.log(
                  `[Dashboard] No entity found - inserting near end of file at line ${fmtInsertIndex} (will be adjusted for XML formats)`,
                );
              }

              // For RDF/XML and OWL/XML formats, ensure insertion is AFTER the root element opening tag
              if (fmt === "rdfxml" || fmt === "owlxml") {
                // Find the line with the opening <rdf:RDF> or <Ontology> root element
                let rootElementLine = -1;
                for (let i = 0; i < Math.min(50, fmtLines.length); i++) {
                  const trimmed = fmtLines[i].trim();
                  if (
                    trimmed.startsWith("<rdf:RDF") ||
                    trimmed.startsWith("<Ontology") ||
                    trimmed.startsWith("<owl:Ontology")
                  ) {
                    rootElementLine = i;
                    break;
                  }
                }

                // Find the actual closing > of the root tag (may span multiple lines with xmlns)
                let rootTagCloseLine = rootElementLine;
                if (rootElementLine >= 0) {
                  for (let j = rootElementLine; j < Math.min(rootElementLine + 100, fmtLines.length); j++) {
                    if (fmtLines[j].includes(">")) {
                      const lastQuote = fmtLines[j].lastIndexOf('"');
                      const lastGt = fmtLines[j].lastIndexOf(">");
                      if (lastGt > lastQuote || lastQuote === -1) {
                        rootTagCloseLine = j;
                        break;
                      }
                    }
                  }
                }

                if (rootElementLine >= 0 && fmtInsertIndex <= rootTagCloseLine) {
                  fmtInsertIndex = rootTagCloseLine + 1;
                  console.log(
                    `[Dashboard] ${fmt.toUpperCase()}: Adjusted insertion to line ${fmtInsertIndex} to respect XML structure`,
                  );
                }

                // Check if trying to insert after the closing tag
                for (let i = fmtLines.length - 1; i >= Math.max(0, fmtLines.length - 10); i--) {
                  const trimmed = fmtLines[i].trim();
                  if (trimmed === "</rdf:RDF>" || trimmed === "</Ontology>" || trimmed === "</owl:Ontology>") {
                    if (fmtInsertIndex > i) {
                      fmtInsertIndex = i;
                      console.log(
                        `[Dashboard] ${fmt.toUpperCase()}: Adjusted insertion to line ${fmtInsertIndex} to stay inside root element`,
                      );
                    }
                    break;
                  }
                }
              }

              // Insert citation at found location
              fmtLines.splice(fmtInsertIndex, 0, ...fmtCitationLines);

              // Store in cache with citation-entity mapping for smart repositioning
              const fmtModifiedContent = fmtLines.join("\n");
              await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
                content: fmtModifiedContent,
                format: fmt,
                citationUrn: citationUrn,
                referencedEntity: referencedEntity || "",
              });
              console.log("[Dashboard] Format cache stored with citation near entity:", fmt);
              succeededFormats.push(fmt);
            }
          } catch (fmtError) {
            console.warn(`[Dashboard] Failed to update format ${fmt}:`, fmtError);
            failedFormats.push(fmt);
          }
        }

        if (failedFormats.length > 0) {
          console.warn(`[Dashboard] Citation sync failed for formats: ${failedFormats.join(", ")}`);
        }
        console.log(`[Dashboard] Citation inserted in formats: ${succeededFormats.join(", ")}`);
        setHasLocalCodeViewChanges(false);

        // Step 3: GraphDB insertion disabled (endpoint not available)
        // Citations are persisted in code-view-cache for all formats
        // GraphDB will be updated when user saves/exports the ontology
        console.log("[Dashboard] Citations stored in cache for all formats, GraphDB will be updated on save/export");

        console.log("[Dashboard] ========== CITATION INSERTION SUMMARY ==========");
        console.log("[Dashboard] Current format:", codeViewFormat);
        console.log("[Dashboard] Inserted at line:", insertAtIndex);
        console.log(
          "[Dashboard] Referenced entity:",
          referencedEntity || "(NONE - citations went to default location)",
        );
        console.log(
          "[Dashboard] Entity was",
          referencedEntity
            ? "FOUND and used for cross-format placement"
            : "NOT FOUND - fallback to default location used",
        );
        console.log("[Dashboard] ================================================");

        notificationService.success(
          "Citation Inserted",
          failedFormats.length === 0
            ? `Added "${title}" - synced to all formats (${succeededFormats.join(", ")})`
            : `Added "${title}" - synced to ${succeededFormats.join(", ")}${failedFormats.length > 0 ? `. Could not sync: ${failedFormats.join(", ")} (will sync when format is loaded)` : ""}`,
        );

        // Step 4: Mark that citation was just inserted so format switches will reload from cache
        setCitationJustInserted(true);
        console.log("[Dashboard] Citation insertion flag set - next format switch will reload from cache");

        // Step 5: Reset citation insertion mode after successful insertion
        setPendingCitation(null);
        setCitationInsertionMode(false);
        setSelectedInsertionLine(null);
        console.log("[Dashboard] Citation insertion mode reset after successful insertion");
      } catch (error) {
        console.error("[Dashboard] Error inserting citation at location:", error);
        notificationService.error("Citation Error", "Failed to insert citation at location");
        // Reset citation insertion mode and clear flag on error
        setPendingCitation(null);
        setCitationInsertionMode(false);
        setSelectedInsertionLine(null);
        setCitationJustInserted(false);
        console.log("[Dashboard] Citation insertion mode reset due to error");
      }
    },
    [pendingCitation, projectId, codeViewFormat, codeViewContent],
  );

  // Handler for removing citations from the code view
  // Removes a citation (block or JSON-LD node) from every format's cache OTHER than
  // `excludeFormat`, regardless of which format the removal was triggered from.
  const syncCitationRemovalToOtherFormats = useCallback(
    async (citationUri: string, excludeFormat: string) => {
      const allFormats = ["turtle", "rdfxml", "ntriples", "owlxml", "manchester", "functional", "jsonld"] as const;
      const otherFormats = allFormats.filter((f) => f !== excludeFormat);

      for (const fmt of otherFormats) {
        try {
          const response = await apiClient.get<{ success: boolean; content: string }>(
            `/api/ontology/${projectId}/content`,
            { format: fmt, forceRefresh: "false" },
          );
          if (!response.success || !response.content) continue;

          if (fmt === "jsonld") {
            const result = removeCitationNodeFromJsonLd(response.content, citationUri);
            if (result.removed) {
              await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
                content: result.content,
                format: fmt,
              });
              console.log(`[Dashboard] Format ${fmt} cache updated after removal (JSON-LD node removed)`);
            }
            continue;
          }

          // Remove citation from this (line-oriented) format too, using the same
          // comment-marker / closing-statement block detection as the primary removal path.
          const fmtLines = response.content.split("\n");
          const fmtLinesToRemove = new Set<number>();

          const fmtIsXml = fmt === "rdfxml" || fmt === "owlxml";
          const fmtIsTurtle = fmt === "turtle" || fmt === "ntriples";
          const fmtIsManchester = fmt === "manchester";
          const fmtIsFunctional = fmt === "functional";

          const fmtCitationLines: number[] = [];
          for (let i = 0; i < fmtLines.length; i++) {
            if (fmtLines[i].includes(`urn:citation:${citationUri}`)) {
              fmtCitationLines.push(i);
            }
          }

          for (const uriLineNum of fmtCitationLines) {
            let blockStart = uriLineNum;
            for (let i = uriLineNum - 1; i >= Math.max(0, uriLineNum - 10); i--) {
              const line = fmtLines[i].trim();
              if (line.includes("Zotero Citation") || line.startsWith("###") || line.startsWith("<!--")) {
                blockStart = i;
                break;
              }
              if (line === "") break;
              if (
                fmtIsXml &&
                (line.startsWith("<Declaration>") ||
                  line.startsWith("<owl:NamedIndividual") ||
                  line.startsWith("<ClassAssertion>"))
              ) {
                blockStart = i;
              }
            }

            for (let i = uriLineNum; i < Math.min(fmtLines.length, uriLineNum + 50); i++) {
              const line = fmtLines[i];
              const trimmedLine = line.trim();
              fmtLinesToRemove.add(i);

              if (fmtIsXml) {
                if (
                  trimmedLine === "</owl:NamedIndividual>" ||
                  trimmedLine === "</Declaration>" ||
                  trimmedLine === "</ClassAssertion>" ||
                  trimmedLine === "</AnnotationAssertion>"
                ) {
                  if (i + 1 < fmtLines.length && fmtLines[i + 1].trim() === "") {
                    fmtLinesToRemove.add(i + 1);
                  }
                  break;
                }
              } else if (fmtIsTurtle) {
                if (trimmedLine.endsWith(".") && !trimmedLine.startsWith("@") && !trimmedLine.startsWith("#")) {
                  if (i + 1 < fmtLines.length && fmtLines[i + 1].trim() === "") {
                    fmtLinesToRemove.add(i + 1);
                  }
                  break;
                }
              } else if (fmtIsManchester || fmtIsFunctional) {
                if (
                  trimmedLine === "" ||
                  (fmtIsManchester &&
                    trimmedLine.match(/^(Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):/))
                ) {
                  if (trimmedLine === "") fmtLinesToRemove.add(i);
                  break;
                }
              }
            }

            for (let i = blockStart; i < uriLineNum; i++) {
              fmtLinesToRemove.add(i);
            }
          }

          const sortedFmtLines = [...fmtLinesToRemove].sort((a, b) => a - b);
          if (sortedFmtLines.length > 0) {
            const firstLine = sortedFmtLines[0];
            for (let i = firstLine - 1; i >= Math.max(0, firstLine - 3); i--) {
              const line = fmtLines[i].trim();
              if (line.includes("Zotero Citation") || (line.startsWith("#") && line.includes("Citation"))) {
                fmtLinesToRemove.add(i);
              } else if (line === "") {
                if (i > 0 && fmtLines[i - 1].includes("Zotero Citation")) {
                  fmtLinesToRemove.add(i);
                }
              } else {
                break;
              }
            }
          }

          const uniqueFmtLinesToRemove = [...fmtLinesToRemove].sort((a, b) => b - a);
          const newFmtLines = [...fmtLines];
          for (const lineIdx of uniqueFmtLinesToRemove) {
            newFmtLines.splice(lineIdx, 1);
          }

          const fmtModifiedContent = newFmtLines.join("\n");
          await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
            content: fmtModifiedContent,
            format: fmt,
          });
          console.log(
            `[Dashboard] Format ${fmt} cache updated after removal (${uniqueFmtLinesToRemove.length} lines removed)`,
          );
        } catch (fmtError) {
          console.warn(`[Dashboard] Failed to update format ${fmt} after removal:`, fmtError);
        }
      }
    },
    [projectId],
  );

  const handleRemoveCitationAtLocation = useCallback(
    async (lineNumber: number) => {
      if (!codeViewContent) {
        console.warn("[Dashboard] No code view content available for citation removal");
        return;
      }

      // Show loading notification
      notificationService.info("Removing Citation", "Scanning for citation and removing from all formats...");

      console.log("[Dashboard] ========================================");
      console.log("[Dashboard] Attempting to remove citation at line:", lineNumber);

      const lines = codeViewContent.split("\n");

      // Find the citation block boundaries (start and end lines)
      const clickedLine = lines[lineNumber] || "";
      console.log("[Dashboard] Clicked line content:", clickedLine.substring(0, 100));
      console.log("[Dashboard] Current format:", codeViewFormat);
      console.log("[Dashboard] Total lines in content:", lines.length);

      // Citation detection pattern
      const citationUriPattern = /urn:citation:([a-zA-Z0-9]+)/i;

      // Extract citation URI from the clicked line or nearby lines
      let citationUri = "";
      const searchRange = 20; // Search 20 lines up and down
      console.log(
        "[Dashboard] Searching for citation URI from line",
        Math.max(0, lineNumber - searchRange),
        "to",
        Math.min(lines.length, lineNumber + searchRange),
      );

      for (let i = Math.max(0, lineNumber - searchRange); i < Math.min(lines.length, lineNumber + searchRange); i++) {
        const match = lines[i].match(citationUriPattern);
        if (match) {
          citationUri = match[1];
          console.log("[Dashboard] Found citation URI at line", i, ":", citationUri);
          console.log("[Dashboard] Full match:", match[0]);
          console.log("[Dashboard] Line content:", lines[i].substring(0, 100));
          break;
        }
      }

      if (!citationUri) {
        console.warn("[Dashboard] No citation URI found near clicked line");
        console.warn(
          "[Dashboard] Searched lines",
          Math.max(0, lineNumber - searchRange),
          "to",
          Math.min(lines.length, lineNumber + searchRange),
        );
        notificationService.warning(
          "Remove Citation",
          `Could not identify a citation near line ${lineNumber + 1}. Please click on a line that is part of a citation block (highlighted in red when in removal mode).`,
        );
        return;
      }

      console.log("[Dashboard] Citation URI to remove:", citationUri);

      // JSON-LD is not line-oriented — the line-based block detection below (which relies on
      // comment markers and closing tags/statements that don't exist in JSON) would corrupt
      // the document. Remove the citation node structurally instead.
      if (codeViewFormat === "jsonld") {
        const jsonLdRemoval = removeCitationNodeFromJsonLd(codeViewContent, citationUri);
        if (!jsonLdRemoval.removed) {
          notificationService.warning("Remove Citation", "Could not find the citation node in the JSON-LD document.");
          return;
        }

        setConfirmDialog({
          isOpen: true,
          title: "Remove Citation",
          message: "Are you sure you want to remove this citation from the JSON-LD document?",
          onConfirm: async () => {
            try {
              const modifiedContent = jsonLdRemoval.content;
              setCodeViewContent(modifiedContent);
              setHasLocalCodeViewChanges(true);

              try {
                await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
                  content: modifiedContent,
                  format: "jsonld",
                });
              } catch (e) {
                console.warn("[Dashboard] Failed to store jsonld cache after removal:", e);
              }

              if (window.vscode) {
                window.vscode.postMessage({
                  type: "uploadOntologyContent",
                  content: modifiedContent,
                  format: "jsonld",
                  projectId: projectId,
                });
                await new Promise((resolve) => setTimeout(resolve, 500));
              }

              await syncCitationRemovalToOtherFormats(citationUri, "jsonld");

              console.log("[Dashboard] Removing citation from GraphDB:", citationUri);
              window.vscode?.postMessage({
                type: "removeCitationFromGraphDB",
                citationUri: `urn:citation:${citationUri}`,
                projectId: projectId,
              });

              notificationService.success("Citation Removed", "Successfully removed citation from all formats");
              setCitationRemovalMode(false);
              setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
            } catch (error) {
              console.error("[Dashboard] Error removing JSON-LD citation:", error);
              notificationService.error("Citation Error", "Failed to remove citation");
              setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
            }
          },
          onCancel: () => {
            console.log("[Dashboard] Citation removal cancelled by user");
            setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
          },
        });
        return;
      }

      // Find citation block using a more reliable approach
      // Step 1: Find all lines that contain the citation URI
      const citationUriLines: number[] = [];
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].includes(`urn:citation:${citationUri}`)) {
          citationUriLines.push(i);
        }
      }

      if (citationUriLines.length === 0) {
        notificationService.warning("Remove Citation", "Could not find the citation in the content.");
        return;
      }

      console.log("[Dashboard] Lines containing citation URI:", citationUriLines);

      // Step 2: Find the complete block boundaries
      const linesToRemove = new Set<number>();

      // Format-specific block detection
      const isXmlFormat = codeViewFormat === "rdfxml" || codeViewFormat === "owlxml";
      const isTurtleFormat = codeViewFormat === "turtle" || codeViewFormat === "ntriples";
      const isManchesterFormat = codeViewFormat === "manchester";
      const isFunctionalFormat = codeViewFormat === "functional";

      for (const uriLineNum of citationUriLines) {
        // Find block start (look backwards for comment or opening tag)
        let blockStart = uriLineNum;
        let foundComment = false;

        for (let i = uriLineNum - 1; i >= Math.max(0, uriLineNum - 15); i--) {
          const line = lines[i].trim();

          // Check for comment line (Zotero Citation marker)
          if (line.includes("Zotero Citation") || line.startsWith("###") || line.startsWith("<!--")) {
            blockStart = i;
            foundComment = true;
            // Continue searching backwards for any blank lines before the comment
            for (let j = i - 1; j >= Math.max(0, i - 2); j--) {
              if (lines[j].trim() === "") {
                blockStart = j;
              } else {
                break;
              }
            }
            break;
          }

          // For XML, check for opening tag
          if (
            isXmlFormat &&
            (line.startsWith("<Declaration>") ||
              line.startsWith("<owl:NamedIndividual") ||
              line.startsWith("<ClassAssertion>"))
          ) {
            blockStart = i;
          }

          // Keep going backwards until we hit non-empty content that's not part of citation
          if (line !== "" && !line.includes("urn:citation:") && !isXmlFormat) {
            // Found content that's not part of this citation
            break;
          }
        }

        // Find block end (look forwards for closing statement)
        let blockEnd = uriLineNum;
        for (let i = uriLineNum; i < Math.min(lines.length, uriLineNum + 50); i++) {
          const line = lines[i];
          const trimmedLine = line.trim();

          linesToRemove.add(i);

          if (isXmlFormat) {
            // End at closing tag
            if (
              trimmedLine === "</owl:NamedIndividual>" ||
              trimmedLine === "</Declaration>" ||
              trimmedLine === "</ClassAssertion>" ||
              trimmedLine === "</AnnotationAssertion>"
            ) {
              blockEnd = i;
              // Include trailing blank line
              if (i + 1 < lines.length && lines[i + 1].trim() === "") {
                linesToRemove.add(i + 1);
                blockEnd = i + 1;
              }
              break;
            }
          } else if (isTurtleFormat) {
            // End at line ending with .
            if (trimmedLine.endsWith(".") && !trimmedLine.startsWith("@") && !trimmedLine.startsWith("#")) {
              blockEnd = i;
              // Include trailing blank line
              if (i + 1 < lines.length && lines[i + 1].trim() === "") {
                linesToRemove.add(i + 1);
                blockEnd = i + 1;
              }
              break;
            }
          } else if (isManchesterFormat) {
            // End at blank line or next declaration
            if (
              trimmedLine === "" ||
              trimmedLine.match(/^(Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):/)
            ) {
              if (trimmedLine === "") {
                linesToRemove.add(i);
              }
              blockEnd = i;
              break;
            }
          } else if (isFunctionalFormat) {
            // End at blank line (each statement is one line, but citation has multiple)
            if (trimmedLine === "") {
              linesToRemove.add(i);
              blockEnd = i;
              break;
            }
          }
        }

        // Add all lines from blockStart to blockEnd
        for (let i = blockStart; i <= blockEnd; i++) {
          linesToRemove.add(i);
        }

        console.log(`[Dashboard] Citation block: lines ${blockStart} to ${blockEnd}`);
      }

      // Also capture preceding comment lines (### Zotero Citation) that might be separate
      const sortedLines = [...linesToRemove].sort((a, b) => a - b);
      if (sortedLines.length > 0) {
        const firstLine = sortedLines[0];
        // Check lines before for comments
        for (let i = firstLine - 1; i >= Math.max(0, firstLine - 3); i--) {
          const line = lines[i].trim();
          if (line.includes("Zotero Citation") || (line.startsWith("#") && line.includes("Citation"))) {
            linesToRemove.add(i);
          } else if (line === "") {
            // Include blank line before comment
            if (i > 0 && lines[i - 1].includes("Zotero Citation")) {
              linesToRemove.add(i);
            }
          } else {
            break;
          }
        }
      }

      // Sort and deduplicate lines to remove (descending for safe removal)
      const uniqueLinesToRemove = [...linesToRemove].sort((a, b) => b - a);

      if (uniqueLinesToRemove.length === 0) {
        console.error("[Dashboard] No lines to remove - this should not happen");
        notificationService.warning("Remove Citation", "Could not find the citation block to remove.");
        return;
      }

      console.log("[Dashboard] ========================================");
      console.log("[Dashboard] Lines to remove (descending):", uniqueLinesToRemove);
      console.log("[Dashboard] Total lines to remove:", uniqueLinesToRemove.length);
      console.log("[Dashboard] Lines content preview (first 10):");
      uniqueLinesToRemove.slice(0, 10).forEach((idx) => {
        console.log(`  Line ${idx}: ${lines[idx]?.substring(0, 80)}...`);
      });
      console.log("[Dashboard] ========================================");

      // Confirm removal with user using custom dialog
      const lineCount = uniqueLinesToRemove.length;
      setConfirmDialog({
        isOpen: true,
        title: "Remove Citation",
        message: `Are you sure you want to remove this citation? ${lineCount} line${lineCount !== 1 ? "s" : ""} will be deleted.`,
        onConfirm: async () => {
          try {
            console.log("[Dashboard] ========================================");
            console.log("[Dashboard] User confirmed citation removal");
            console.log("[Dashboard] Performing citation removal for URI:", citationUri);
            console.log("[Dashboard] Removing", uniqueLinesToRemove.length, "lines");

            // Remove lines from content (remove from end to start to preserve indices)
            const newLines = [...lines];
            console.log("[Dashboard] Original line count:", newLines.length);

            for (const lineIdx of uniqueLinesToRemove) {
              console.log("[Dashboard] Removing line", lineIdx, ":", newLines[lineIdx]?.substring(0, 60));
              newLines.splice(lineIdx, 1);
            }

            console.log("[Dashboard] New line count:", newLines.length);
            console.log("[Dashboard] Lines removed:", lines.length - newLines.length);

            const modifiedContent = newLines.join("\n");
            console.log("[Dashboard] Modified content length:", modifiedContent.length, "characters");

            // Update local code view
            setCodeViewContent(modifiedContent);
            setHasLocalCodeViewChanges(true);
            console.log("[Dashboard] Code view content updated with modified content");
            console.log("[Dashboard] hasLocalCodeViewChanges set to true");

            // Store modified content for current format cache
            // (Don't clear all caches — other format caches are needed for cross-format removal below)
            try {
              await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
                content: modifiedContent,
                format: codeViewFormat,
              });
              console.log("[Dashboard] Current format cache stored after removal");
            } catch (e) {
              console.warn("[Dashboard] Failed to store current format cache:", e);
            }

            // Upload modified content to backend
            if (window.vscode) {
              window.vscode.postMessage({
                type: "uploadOntologyContent",
                content: modifiedContent,
                format: codeViewFormat,
                projectId: projectId,
              });

              await new Promise((resolve) => setTimeout(resolve, 500));
            }

            // Remove citation from all other format caches
            await syncCitationRemovalToOtherFormats(citationUri, codeViewFormat);

            // Remove citation from GraphDB
            console.log("[Dashboard] Removing citation from GraphDB:", citationUri);
            window.vscode?.postMessage({
              type: "removeCitationFromGraphDB",
              citationUri: `urn:citation:${citationUri}`,
              projectId: projectId,
            });

            notificationService.success("Citation Removed", `Successfully removed citation from all formats`);

            // Reset removal mode
            setCitationRemovalMode(false);
            console.log("[Dashboard] Citation removal mode reset");

            // Close dialog after successful removal
            setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
          } catch (error) {
            console.error("[Dashboard] Error in citation removal:", error);
            notificationService.error("Citation Error", "Failed to remove citation");
            // Close dialog even on error
            setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
          }
        },
        onCancel: () => {
          console.log("[Dashboard] Citation removal cancelled by user");
          setConfirmDialog((prev) => ({ ...prev, isOpen: false }));
        },
      });
    },
    [projectId, codeViewFormat, codeViewContent, syncCitationRemovalToOtherFormats],
  );

  // Helper function to check if a line is related to a citation block
  function isCitationRelatedLine(line: string, format: string): boolean {
    const commonPatterns = [
      /dc:title/i,
      /dc:creator/i,
      /dc:date/i,
      /dc:source/i,
      /dc:publisher/i,
      /dc:description/i,
      /dc:identifier/i,
      /dc:language/i,
      /dc:rights/i,
      /dc:type/i,
      /dc:subject/i,
      /bibo:doi/i,
      /bibo:isbn/i,
      /bibo:issn/i,
      /bibo:volume/i,
      /bibo:issue/i,
      /bibo:pages/i,
      /foaf:homepage/i,
      /prov:Entity/i,
      /owl:NamedIndividual/i,
      /rdf:type/i,
    ];

    // XML-specific closing tags
    if (format === "rdfxml" || format === "owlxml") {
      if (line.trim().startsWith("</")) return true;
      if (line.trim().startsWith("<")) return true;
    }

    // Turtle continuation (ends with ; or has property assertions)
    if (format === "turtle" || format === "ntriples") {
      if (line.trim().endsWith(";")) return true;
      if (
        line.trim().startsWith("dc:") ||
        line.trim().startsWith("bibo:") ||
        line.trim().startsWith("foaf:") ||
        line.trim().startsWith("prov:")
      )
        return true;
    }

    // Manchester annotations continuation
    if (format === "manchester") {
      if (
        line.trim().startsWith("dc:") ||
        line.trim().startsWith("bibo:") ||
        line.includes("Annotations:") ||
        line.includes("Types:")
      )
        return true;
    }

    // Functional syntax assertions
    if (format === "functional") {
      if (line.includes("AnnotationAssertion(") || line.includes("ClassAssertion(")) return true;
    }

    return commonPatterns.some((pattern) => pattern.test(line));
  }

  // Cleanup sync service when switching projects
  useEffect(() => {
    return () => {
      if (projectId) {
        syncService.stopMonitoring(projectId);
        console.log("[Dashboard] Stopped monitoring for project:", projectId);
      }
    };
  }, [projectId]);

  // Load code view content when switching to CodeView tab
  useEffect(() => {
    if (mainTab === "CodeView" && projectId) {
      if (!codeViewContent) {
        fetchCodeViewContent(codeViewFormat);
      } else if (codeViewDirtyRef.current) {
        // Ontology was mutated since last load — reload without clearing cache
        fetchCodeViewContent(codeViewFormat, false, true);
      }
    }
  }, [mainTab, projectId, codeViewContent, codeViewFormat, fetchCodeViewContent]);

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
        return (
          <div className="flex h-full" style={{ backgroundColor: "var(--color-background)" }}>
            <div className="flex-1 flex flex-col bg-theme-surface">
              <div className="p-4 border-b border-gray-200">
                <h2 className="text-lg font-semibold">OWL/RDF Code View</h2>
                <p className="text-sm text-gray-600 mt-1">View the ontology in different serialization formats</p>
              </div>
              <div className="flex-1 flex flex-col overflow-hidden p-4">
                <div className="mb-4 flex gap-2 flex-wrap flex-shrink-0">
                  <button
                    onClick={() => {
                      fetchCodeViewContent("turtle", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "turtle"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    Turtle
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("rdfxml", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "rdfxml"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    RDF/XML
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("ntriples", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "ntriples"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    N-Triples
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("owlxml", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "owlxml"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    OWL/XML
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("manchester", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "manchester"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    Manchester
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("functional", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "functional"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    Functional
                  </button>
                  <button
                    onClick={() => {
                      fetchCodeViewContent("jsonld", false, citationJustInserted);
                      setCitationJustInserted(false);
                    }}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === "jsonld"
                        ? "bg-purple-600 text-white hover:bg-purple-700"
                        : "bg-gray-200 text-gray-700 hover:bg-gray-300"
                    }`}
                  >
                    JSON-LD
                  </button>
                  <button
                    onClick={() => {
                      if (isViewOnlyMember) { handleViewOnlyAction(); return; }
                      if (codeViewPage || codeViewTruncation) {
                        notificationService.error("Unavailable for Large Files", "Citations can't be edited in the large-file preview. Export the file to edit it.");
                        return;
                      }
                      setShowCitationPicker(true);
                    }}
                    className="ml-auto px-3 py-1 text-sm bg-green-600 text-white rounded-md hover:bg-green-700 flex items-center gap-1"
                    title={isViewOnlyMember ? "Pro feature: Zotero citations require a Pro plan" : "Insert citation from Zotero"}
                  >
                    <BookOpen size={16} />
                    Zotero Citation
                  </button>
                  <button
                    onClick={() => {
                      if (isViewOnlyMember) { handleViewOnlyAction(); return; }
                      if (codeViewPage || codeViewTruncation) {
                        notificationService.error("Unavailable for Large Files", "Citations can't be edited in the large-file preview. Export the file to edit it.");
                        return;
                      }
                      setShowManualCitationDialog(true);
                    }}
                    className="px-3 py-1 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700 flex items-center gap-1"
                    title={isViewOnlyMember ? "Pro feature: manual citations require a Pro plan" : "Add citation manually"}
                  >
                    <Edit2 size={16} />
                    Manual Citation
                  </button>
                  <button
                    onClick={() => {
                      if (isViewOnlyMember) { handleViewOnlyAction(); return; }
                      if (codeViewPage || codeViewTruncation) {
                        notificationService.error("Unavailable for Large Files", "Citations can't be edited in the large-file preview. Export the file to edit it.");
                        return;
                      }
                      setCitationRemovalMode(!citationRemovalMode);
                      if (citationInsertionMode) {
                        setCitationInsertionMode(false);
                        setPendingCitation(null);
                        setCitationJustInserted(false);
                      }
                    }}
                    className={`px-3 py-1 text-sm rounded-md flex items-center gap-1 ${
                      citationRemovalMode
                        ? "bg-red-700 text-white hover:bg-red-800"
                        : "bg-red-600 text-white hover:bg-red-700"
                    }`}
                    title="Click to enter removal mode, then click on a citation to remove it"
                  >
                    <Trash2 size={16} />
                    Remove Citation
                  </button>
                  <button
                    onClick={() => {
                      // Normal refresh - uses cache if available (preserves citation positions)
                      fetchCodeViewContent(codeViewFormat, false, true);
                    }}
                    className="px-3 py-1 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
                    disabled={codeViewLoading}
                    title="Reload content (preserves inserted citations)"
                  >
                    {codeViewLoading ? "Refreshing..." : "Refresh"}
                  </button>
                  {/* <button
                    onClick={() => {
                      if (window.confirm('This will reload fresh from GraphDB and lose any citation line positions. Continue?')) {
                        fetchCodeViewContent(codeViewFormat, true);
                      }
                    }}
                    className="px-3 py-1 text-sm bg-orange-600 text-white rounded-md hover:bg-orange-700"
                    disabled={codeViewLoading}
                    title="Discard cache and reload fresh from GraphDB"
                  >
                    Sync from GraphDB
                  </button> */}
                </div>
                <div className="flex-1 overflow-hidden flex flex-col min-h-0">
                <div className="flex-1 min-h-0 flex flex-col overflow-hidden">
                  {citationInsertionMode && (
                    <div className="bg-blue-900 border-b-2 border-blue-600 p-3 text-blue-100 text-sm flex items-center gap-2">
                      <div className="flex-1">
                        <strong>📍 Citation Insertion Mode Active</strong>
                        <div className="text-xs mt-1">
                          Search for the location in your ontology where you want to insert the citation "
                          {pendingCitation?.title || "Citation"}", then click <strong>Insert Here</strong> on the
                          matching line.
                        </div>
                      </div>
                      <button
                        onClick={() => {
                          setCitationInsertionMode(false);
                          setPendingCitation(null);
                        }}
                        className="px-2 py-1 text-xs bg-blue-700 hover:bg-blue-600 rounded flex-shrink-0"
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                  {citationRemovalMode && (
                    <div className="bg-red-900 border-b-2 border-red-600 p-3 text-red-100 text-sm flex items-center gap-2">
                      <div className="flex-1">
                        <strong>🗑️ Citation Removal Mode Active</strong>
                        <div className="text-xs mt-1">
                          Citation lines are highlighted in <span className="bg-red-800 px-1 rounded">red</span>. Click
                          on any citation line to remove it. Search for "Zotero Citation" or "urn:citation" to find
                          citations.
                        </div>
                      </div>
                      <button
                        onClick={() => {
                          setCitationRemovalMode(false);
                        }}
                        className="px-2 py-1 text-xs bg-red-700 hover:bg-red-600 rounded flex-shrink-0"
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                  {!codeViewLoading && codeViewTruncation && !codeViewPage && (
                    <div className="flex items-center gap-2 px-4 py-2 bg-amber-500/10 border-b border-amber-400/30 text-amber-700 dark:text-amber-300 text-sm">
                      <AlertTriangle size={14} className="flex-shrink-0" />
                      <span>
                        This ontology is {(codeViewTruncation.totalChars / (1024 * 1024)).toFixed(0)} MB in this
                        format — too large to edit here. Showing a read-only preview of the first{" "}
                        {codeViewTruncation.previewLines.toLocaleString()} lines. Export the file to view or edit
                        the full content.
                      </span>
                      <button
                        onClick={downloadFullCodeViewFile}
                        className="ml-auto px-3 py-1 rounded-md bg-amber-600 text-white text-xs font-medium hover:bg-amber-700 flex-shrink-0"
                      >
                        Download full file
                      </button>
                    </div>
                  )}
                  {codeViewPage && (
                    <div className="flex items-center gap-3 px-4 py-2 bg-amber-500/10 border-b border-amber-400/30 text-amber-700 dark:text-amber-300 text-sm flex-wrap">
                      <AlertTriangle size={14} className="flex-shrink-0" />
                      <span>
                        {(codeViewPage.totalBytes / (1024 * 1024)).toFixed(0)} MB — read-only. Showing lines{" "}
                        {(codeViewPage.startLine + 1).toLocaleString()}–
                        {(codeViewPage.startLine + codeViewPage.lineCount).toLocaleString()} of{" "}
                        {codeViewPage.totalLines.toLocaleString()}. Export the file to edit it.
                      </span>
                      <div className="flex items-center gap-2 ml-auto flex-shrink-0">
                        <button
                          onClick={() => loadCodeViewPage(codeViewPage.startLine - CODE_VIEW_PAGE_LINES)}
                          disabled={codeViewLoading || codeViewPage.startLine <= 0}
                          className="px-2 py-1 rounded-md bg-amber-600 text-white text-xs font-medium hover:bg-amber-700 disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          ← Prev
                        </button>
                        <button
                          onClick={() => loadCodeViewPage(codeViewPage.startLine + codeViewPage.lineCount)}
                          disabled={
                            codeViewLoading ||
                            codeViewPage.startLine + codeViewPage.lineCount >= codeViewPage.totalLines
                          }
                          className="px-2 py-1 rounded-md bg-amber-600 text-white text-xs font-medium hover:bg-amber-700 disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          Next →
                        </button>
                        <input
                          type="number"
                          min={1}
                          max={codeViewPage.totalLines}
                          placeholder="Go to line…"
                          disabled={codeViewLoading}
                          className="w-28 px-2 py-1 rounded-md border border-amber-400/40 bg-transparent text-xs"
                          onKeyDown={(e: React.KeyboardEvent<HTMLInputElement>) => {
                            if (e.key !== "Enter") return;
                            const line = parseInt((e.target as HTMLInputElement).value, 10);
                            if (!Number.isFinite(line) || line < 1) return;
                            // Align the window so the requested line is at its top.
                            loadCodeViewPage(line - 1);
                          }}
                        />
                        <button
                          onClick={downloadFullCodeViewFile}
                          className="px-3 py-1 rounded-md bg-amber-600 text-white text-xs font-medium hover:bg-amber-700"
                        >
                          Download full file
                        </button>
                      </div>
                    </div>
                  )}
                  {codeViewLoading ? (
                    <div className="flex items-center justify-center h-64">
                      <div className="text-gray-500">Loading ontology content...</div>
                    </div>
                  ) : (
                    <CodeHighlighter
                      ref={codeHighlighterRef}
                      content={codeViewContent || "// No content available"}
                      format={codeViewFormat}
                      citationInsertionMode={citationInsertionMode}
                      citationRemovalMode={citationRemovalMode}
                      pendingCitation={pendingCitation}
                      onInsertCitationAt={handleInsertCitationAtLocation}
                      onRemoveCitationAt={handleRemoveCitationAtLocation}
                      onRequestZoteroCitation={() => setShowCitationPicker(true)}
                      onContentChange={handleCodeContentChange}
                      onSaveContent={handleSaveCodeContent}
                      syntaxError={codeViewSyntaxError}
                      readOnly={isViewOnlyMember || !!codeViewTruncation || !!codeViewPage}
                      canExport={subscription.canAccessFeature('hasExport') && !isViewOnlyMember}
                      onExportProAction={handleExportProAction}
                    />
                  )}
                </div>
                <LintProblemsPanel
                  issues={codeViewLintIssues}
                  onJumpToLine={(line) => codeHighlighterRef.current?.goToLine(line)}
                  onSaveAnyway={() => {
                    const pending = lastCodeViewSaveContentRef.current;
                    setCodeViewLintIssues([]);
                    void handleSaveCodeContent(pending, true);
                  }}
                  onDismiss={() => setCodeViewLintIssues([])}
                />
                </div>
              </div>
            </div>
          </div>
        );
      case "SPARQL": {
        // Use dynamically loaded SPARQL Query Plugin
        const sparqlPlugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === "sparql-query-plugin");
        const sparqlLoadingState = pluginLoadingStates["sparql-query-plugin"];

        if (sparqlPlugin?.component && projectId) {
          const SparqlPluginComponent = sparqlPlugin.component;
          return (
            <div className="h-full flex flex-col">
              {/* Desktop: non-blocking heads-up while Fuseki spins up — users can
                  keep writing queries; execution succeeds once the store is ready. */}
              {renderDesktopFusekiBanner()}
              <div className="flex-1 min-h-0">
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
              </div>
            </div>
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
        // Desktop: the plugin fetches on mount — hold it until Fuseki is synced,
        // otherwise the first load races the cold start into a blank canvas.
        if (desktopFusekiBlocked) return renderDesktopFusekiGate();
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
        // Desktop: fuzzy plugin issues SPARQL on load — wait for Fuseki sync.
        if (desktopFusekiBlocked) return renderDesktopFusekiGate();
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
            <div className="h-full flex flex-col">
              {/* Desktop: reasoner reads the triple store — surface sync progress
                  without blocking reasoner selection/config. */}
              {renderDesktopFusekiBanner()}
              <div className="flex-1 min-h-0">
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
              </div>
            </div>
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
          <div className="flex flex-col lg:flex-row h-full min-h-0 overflow-hidden">
            <div
              className="flex-1 flex flex-col border-r m-2 rounded shadow-sm overflow-hidden min-w-0 min-h-0"
              style={{ backgroundColor: "var(--bg)", borderColor: "var(--border)" }}
            >
              {isMetadataLoading && (
                <div
                  className="flex items-center gap-2 px-4 py-2 text-xs border-b shrink-0"
                  style={{ borderColor: "var(--border)", color: "var(--accent)" }}
                >
                  <Loader2 size={14} className="animate-spin flex-shrink-0" />
                  <span>Loading ontology metadata (IRI, annotations, imports)…</span>
                </div>
              )}
              {/* Ontology Header Section */}
              <div
                className="p-4 border-b"
                style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
              >
                <div className="flex justify-between items-start mb-4">
                  <h2 className="text-xs font-bold uppercase tracking-wider" style={{ color: "var(--text-primary)" }}>
                    Ontology header
                  </h2>
                  {!isViewOnlyMember && (
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
                  )}
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
                  {!isViewOnlyMember && (
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
                  )}
                </div>
                {ontologyAnnotations.length > 0 ? (
                  <div className="space-y-2">
                    {ontologyAnnotations.map((annotation, idx) => {
                        const key = `${annotation.propertyIri}-${annotation.value}-${idx}`;
                        const propertyIri = annotation.propertyIri || "";
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
                              {!isViewOnlyMember && (
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
                              )}
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
                          onClick={isViewOnlyMember ? handleViewOnlyAction : handleAddPrefixDialog}
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
                                        onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleEditPrefixDialog(p.prefix, p.namespace)}
                                        className="p-1 rounded text-[10px]"
                                        style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                        title={isViewOnlyMember ? "View-only: upgrade to edit" : "Edit"}
                                      >
                                        <Edit2 size={12} />
                                      </button>
                                      <button
                                        onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleDeletePrefix(p.prefix)}
                                        className="p-1 rounded text-[10px]"
                                        style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                        title={isViewOnlyMember ? "View-only: upgrade to edit" : "Delete"}
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
                            onClick={isViewOnlyMember ? handleViewOnlyAction : handleAddImportDialog}
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
                                        onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleEditImportDialog(iri)}
                                        className="p-1.5 rounded"
                                        style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                        title={isViewOnlyMember ? "View-only: upgrade to edit" : "Edit import"}
                                      >
                                        <Edit2 size={11} />
                                      </button>
                                      <button
                                        onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleRemoveImport(iri)}
                                        className="p-1.5 rounded"
                                        style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                        title={isViewOnlyMember ? "View-only: upgrade to edit" : "Remove import"}
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
                          onClick={isViewOnlyMember ? handleViewOnlyAction : () => {
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
                                      {axiom.subExpression || axiom.definition || "Anonymous class expression"}
                                    </div>
                                    {axiom.superClassIri && (
                                      <div
                                        className="text-[10px] font-mono break-all"
                                        style={{ color: "var(--text-tertiary)" }}
                                      >
                                        SubClassOf: {axiom.superClassIri}
                                      </div>
                                    )}
                                  </div>
                                  <div className="flex gap-1 flex-shrink-0">
                                    <button
                                      onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleEditAxiom(idx)}
                                      className="p-1 rounded"
                                      style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                                      title={isViewOnlyMember ? "View-only: upgrade to edit" : "Edit axiom"}
                                    >
                                      <Edit2 size={12} />
                                    </button>
                                    <button
                                      onClick={isViewOnlyMember ? handleViewOnlyAction : () => handleDeleteAxiom(idx)}
                                      className="p-1 rounded"
                                      style={{ backgroundColor: "var(--error-tint)", color: "var(--error)" }}
                                      title={isViewOnlyMember ? "View-only: upgrade to edit" : "Delete axiom"}
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
            <div className="w-full lg:w-80 flex-shrink-0 p-4 overflow-y-auto space-y-4 max-h-[40dvh] lg:max-h-none" style={{ backgroundColor: "var(--bg)" }}>
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
          <div className="flex flex-col md:flex-row h-full min-h-0 overflow-hidden">
            <aside className="w-full md:w-80 flex-shrink-0 bg-white border-r border-gray-200 flex flex-col max-h-[42dvh] md:max-h-none">
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
                  onSelectItem={(item) => {
                    const node = item as TreeNode;
                    setSelectedClassForIndividuals(node);
                    setSelectedItem(node);
                  }}
                  onToggleNode={toggleNode}
                  onAddItem={handleAddItem}
                  onDeleteItem={() =>
                    handleDeleteItem(selectedClassForIndividuals ?? undefined, "Classes")
                  }
                  onMakeSiblingsDisjoint={handleMakeSiblingsDisjoint}
                  onOpenPreferences={() => setEntityPreferencesDialogOpen(true)}
                  onRenameItem={handleRenameItem}
                  onChangeEntityIri={handleChangeEntityIri}
                  onMoveClass={handleMoveClass}
                  viewMode={hierarchyViewModes.Classes || "asserted"}
                  onViewModeChange={(mode) =>
                    setHierarchyViewModes((prev) => ({ ...prev, Classes: mode }))
                  }
                  displayMode={hierarchyDisplayMode}
                  onDisplayModeChange={setHierarchyDisplayMode}
                  displayAnnotationPropIri={hierarchyAnnotationPropIri}
                  onDisplayAnnotationPropChange={setHierarchyAnnotationPropIri}
                  customTemplate={hierarchyCustomTemplate}
                  onCustomTemplateChange={setHierarchyCustomTemplate}
                  annotationProperties={hierarchyAnnotationProperties}
                  annotationValues={hierarchyAnnotationValues}
                  importsScope={hierarchyImportsScope}
                  onImportsScopeChange={setHierarchyImportsScope}
                  isReasonerRunning={isReasonerRunning}
                  loadingNodes={loadingNodes}
                  isViewOnly={isViewOnlyMember}
                  onViewOnlyAction={handleViewOnlyAction}
                  isLoading={isEntitiesSectionLoading}
                  onLoadMoreTopLevel={topLevelTruncated ? handleLoadMoreTopLevel : undefined}
                  isLoadingMoreTopLevel={isLoadingMoreTopLevel}
                  topLevelTotal={topLevelTotal}
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
                        onClick={() => setCreateIndividualForClassOpen(true)}
                        className="px-2 py-1 text-xs bg-purple-600 text-white rounded hover:bg-purple-700"
                      >
                        + Add Individual
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
                                    markAsUnsaved();
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
                          <div className="bg-white border border-gray-200 rounded overflow-hidden">
                            <div className="flex bg-purple-50 border-b border-purple-200">
                              <button
                                onClick={() => setClassIndividualInfoTab("annotations")}
                                className={`px-3 py-1.5 text-[11px] font-semibold border-r border-purple-200 ${
                                  classIndividualInfoTab === "annotations"
                                    ? "bg-white text-purple-800"
                                    : "text-purple-600 hover:bg-purple-100"
                                }`}
                              >
                                Annotations
                              </button>
                              <button
                                onClick={() => setClassIndividualInfoTab("usage")}
                                className={`px-3 py-1.5 text-[11px] font-semibold ${
                                  classIndividualInfoTab === "usage"
                                    ? "bg-white text-purple-800"
                                    : "text-purple-600 hover:bg-purple-100"
                                }`}
                              >
                                Usage
                              </button>
                            </div>
                            <div className="min-h-[110px] max-h-[180px] overflow-y-auto p-2">
                              {classIndividualInfoTab === "annotations" ? (
                                selectedClassIndividualDetails.annotations &&
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
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-[11px] text-gray-400">No annotations</div>
                                )
                              ) : classIndividualUsageLoading ? (
                                <div className="text-[11px] text-gray-400">Loading usage...</div>
                              ) : classIndividualUsages.length > 0 ? (
                                <div className="space-y-1">
                                  {classIndividualUsages.map((usage, index) => (
                                    <div key={index} className="text-[11px] text-gray-600 bg-gray-50 rounded px-2 py-1">
                                      <span className="font-semibold text-purple-700 uppercase mr-2">
                                        {usage.type || "usage"}
                                      </span>
                                      <span className="font-mono">
                                        {usage.subjectLabel || usage.subject || usage.context || JSON.stringify(usage)}
                                      </span>
                                    </div>
                                  ))}
                                </div>
                              ) : (
                                <div className="text-[11px] text-gray-400">No usage found</div>
                              )}
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
                                          markAsUnsaved();
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
                            <div className="font-semibold text-gray-700 mb-1">Same / Different individuals</div>
                            <div className="space-y-2">
                              <div>
                                <div className="flex items-center justify-between">
                                  <div className="text-[10px] uppercase text-gray-500 font-semibold">Same Individual As</div>
                                  <button
                                    onClick={() => openClassIndividualSameDiffDialog("same")}
                                    className="px-2 py-0.5 text-[10px] bg-purple-100 text-purple-700 rounded hover:bg-purple-200"
                                  >
                                    Add
                                  </button>
                                </div>
                                {selectedClassIndividualDetails.sameIndividualAs?.length ? (
                                  <div className="space-y-1">
                                    {selectedClassIndividualDetails.sameIndividualAs.map((iri) => (
                                      <div key={iri} className="group flex items-center justify-between text-[11px] text-gray-600">
                                        <span className="truncate">{getLocalName(iri) || iri}</span>
                                        <button
                                          onClick={() => deleteClassIndividualSameDifferent("same", iri)}
                                          className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                                        >
                                          Remove
                                        </button>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-[11px] text-gray-400">No same individual assertions</div>
                                )}
                              </div>
                              <div>
                                <div className="flex items-center justify-between">
                                  <div className="text-[10px] uppercase text-gray-500 font-semibold">Different Individuals</div>
                                  <button
                                    onClick={() => openClassIndividualSameDiffDialog("different")}
                                    className="px-2 py-0.5 text-[10px] bg-purple-100 text-purple-700 rounded hover:bg-purple-200"
                                  >
                                    Add
                                  </button>
                                </div>
                                {selectedClassIndividualDetails.differentIndividualFrom?.length ? (
                                  <div className="space-y-1">
                                    {selectedClassIndividualDetails.differentIndividualFrom.map((iri) => (
                                      <div key={iri} className="group flex items-center justify-between text-[11px] text-gray-600">
                                        <span className="truncate">{getLocalName(iri) || iri}</span>
                                        <button
                                          onClick={() => deleteClassIndividualSameDifferent("different", iri)}
                                          className="opacity-0 group-hover:opacity-100 px-2 py-0.5 text-[10px] bg-red-100 text-red-700 rounded hover:bg-red-200"
                                        >
                                          Remove
                                        </button>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-[11px] text-gray-400">No different individual assertions</div>
                                )}
                              </div>
                            </div>
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
          <div className="h-full flex flex-col">
            {/* Desktop: DL queries execute against the triple store — surface sync
                progress without blocking query authoring. */}
            {renderDesktopFusekiBanner()}
            <div className="flex-1 min-h-0">
          <DLQueryPanel
            projectId={projectId || ""}
              classHierarchy={classHierarchy}
              expandedClassNodeIds={expandedNodes}
              onToggleClassNode={toggleNode}
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
            </div>
          </div>
        );
      default:
        return <div className="p-6 text-gray-400">Select a tab</div>;
    }
  };
  // #endregion

  // #region Selector Handlers
  const handleOpenClassSelector = (target: "domain" | "range", editingItem?: string) => {
    setSelectorTarget(target);
    setSelectorEditingItem(editingItem || null);

    // For Data Property ranges, show the datatype selector instead of class expression
    if (target === "range" && (selectedItem as any)?.type === "DatatypeProperty") {
      setIsDataPropertyRangeDialogOpen(true);
      return;
    }

    // Restrict tabs based on what type of value is being edited
    if (editingItem) {
      const isPlainIri = editingItem.startsWith('http://') || editingItem.startsWith('https://') || editingItem.startsWith('urn:');
      if (isPlainIri) {
        setSelectorAllowedTabs(['hierarchy', 'dataRestriction', 'classExpression']);
        setSelectorInitialTab('hierarchy');
      } else {
        // Restriction (blank node _: or Manchester expression)
        setSelectorAllowedTabs(['objectRestriction', 'dataRestriction', 'classExpression']);
        setSelectorInitialTab('objectRestriction');
      }
    } else {
      setSelectorAllowedTabs(['hierarchy', 'objectRestriction', 'dataRestriction', 'classExpression']);
      setSelectorInitialTab('hierarchy');
    }

    setIsClassExpressionDialogOpen(true);
  };

  // Handler for Data Property Range selection (datatypes)
  const handleDataPropertyRangeConfirm = async (datatypeIri: string) => {
    if (!selectedItem || !projectId) return;

    try {
      // Get display label for the datatype with proper prefix
      const getDisplayLabel = (iri: string): string => {
        if (iri.includes("XMLSchema#")) {
          return "xsd:" + iri.split("#").pop();
        } else if (iri.includes("rdf-syntax-ns#")) {
          return "rdf:" + iri.split("#").pop();
        } else if (iri.includes("rdf-schema#") || iri.includes("2000/01/rdf-schema#")) {
          return "rdfs:" + iri.split("#").pop();
        } else if (iri.includes("owl#")) {
          return "owl:" + iri.split("#").pop();
        }
        return iri.split("#").pop() || iri;
      };

      const displayLabel = getDisplayLabel(datatypeIri);

      await ontologyMutationService.addPropertyRange(
        projectId,
        selectedItem.id,
        datatypeIri,
        user?.email || "anonymous",
        user?.username || "Anonymous",
      );
      updateItemInState({ ...selectedItem, ranges: [...((selectedItem as Property).ranges || []), displayLabel] });
    } catch (error) {
      console.error("Failed to add data property range", error);
    } finally {
      setIsDataPropertyRangeDialogOpen(false);
      setSelectorTarget(null);
    }
  };

  const handleOpenPropertySelector = (target: "subProperty" | "inverse" | "disjoint" | "equivalent", editingItem?: string) => {
    setSelectorTarget(target);
    setSelectorEditingItem(editingItem || null);
    setIsObjectPropertyExpressionDialogOpen(true);
  };

  const handleManchesterConfirm = async (expression: string, restrictionData?: any) => {
    if (!selectedItem || !projectId || !selectorTarget) return;
    const target = selectorTarget as "domain" | "range";
    const editing = selectorEditingItem;
    const isDataProperty = (selectedItem as any)?.type === "DatatypeProperty";
    const relationType = target === "domain" ? "Domain" : "Range";
    const userId = user?.email || "anonymous";
    const username = user?.username || "Anonymous";
    const useManchesterApi =
      !restrictionData &&
      (isManchesterClassExpression(expression) ||
        (editing != null && isManchesterClassExpression(editing)));

    try {
      if (useManchesterApi) {
        if (editing) {
          if (isManchesterClassExpression(editing)) {
            await expressionService.deletePropertyExpressionAxiom(
              projectId, selectedItem.id, relationType, editing, isDataProperty, userId, username,
            );
          } else if (isSimpleOntologyIri(editing)) {
            if (target === "domain") {
              await ontologyMutationService.deletePropertyDomain(projectId, selectedItem.id, editing, userId, username);
            } else {
              await ontologyMutationService.deletePropertyRange(projectId, selectedItem.id, editing, userId, username);
            }
          }
        }
        await expressionService.addPropertyExpressionAxiom(
          projectId, selectedItem.id, relationType, expression, isDataProperty, userId, username,
        );
        const prop = selectedItem as Property;
        if (editing) {
          if (target === "domain") {
            updateItemInState({ ...selectedItem, domains: (prop.domains || []).map((d) => (d === editing ? expression : d)) });
          } else {
            updateItemInState({ ...selectedItem, ranges: (prop.ranges || []).map((r) => (r === editing ? expression : r)) });
          }
        } else if (target === "domain") {
          updateItemInState({ ...selectedItem, domains: [...(prop.domains || []), expression] });
        } else {
          updateItemInState({ ...selectedItem, ranges: [...(prop.ranges || []), expression] });
        }
      } else if (editing) {
        await ontologyMutationService.editRelation(projectId, {
          operation: 'edit',
          entityIri: selectedItem.id,
          relationshipType: target,
          oldTargetIri: editing,
          targetIri: expression,
          userId,
          username,
        });
        const prop = selectedItem as Property;
        if (target === "domain") {
          updateItemInState({ ...selectedItem, domains: (prop.domains || []).map((d) => (d === editing ? expression : d)) });
        } else {
          updateItemInState({ ...selectedItem, ranges: (prop.ranges || []).map((r) => (r === editing ? expression : r)) });
        }
      } else if (target === "domain") {
        await ontologyMutationService.addPropertyDomain(projectId, selectedItem.id, expression, userId, username, restrictionData);
        updateItemInState({ ...selectedItem, domains: [...((selectedItem as Property).domains || []), expression] });
      } else {
        await ontologyMutationService.addPropertyRange(projectId, selectedItem.id, expression, userId, username, restrictionData);
        updateItemInState({ ...selectedItem, ranges: [...((selectedItem as Property).ranges || []), expression] });
      }
    } catch (error) {
      console.error(`Failed to ${editing ? 'replace' : 'add'} ${selectorTarget}`, error);
      notificationService.error("Property axiom", `Failed to ${editing ? 'update' : 'add'} ${target}`);
    } finally {
      setIsClassExpressionDialogOpen(false);
      setSelectorTarget(null);
      setSelectorEditingItem(null);
    }
  };

  // Handler for property selector (subProperty/inverse/disjoint/equivalent)
  const handlePropertySelected = async (expression: string) => {
    if (!selectedItem || !projectId || !selectorTarget) return;
    const target = selectorTarget as "subProperty" | "inverse" | "disjoint" | "equivalent";
    const editing = selectorEditingItem;
    const prop = selectedItem as Property;

    try {
      if (editing) {
        // Replace: single server-side call — delete old + add new atomically
        await ontologyMutationService.editRelation(projectId, {
          operation: 'edit',
          entityIri: selectedItem.id,
          relationshipType: target,
          oldTargetIri: editing,
          targetIri: expression,
          userId: user?.email || "anonymous",
          username: user?.username || "Anonymous",
        });
        const replace = (arr: string[] | undefined) => (arr || []).map(v => v === editing ? expression : v);
        if (target === "subProperty")  updateItemInState({ ...selectedItem, superProperties: replace(prop.superProperties) });
        if (target === "inverse")      updateItemInState({ ...selectedItem, inverseProperties: replace(prop.inverseProperties) });
        if (target === "disjoint")     updateItemInState({ ...selectedItem, disjointProperties: replace(prop.disjointProperties) });
        if (target === "equivalent")   updateItemInState({ ...selectedItem, equivalentProperties: replace(prop.equivalentProperties as string[] | undefined) });
      } else {
        switch (target) {
          case "subProperty":
            await ontologyMutationService.addSubPropertyOf(projectId, selectedItem.id, expression, user?.email || "anonymous", user?.username || "Anonymous");
            updateItemInState({ ...selectedItem, superProperties: [...(prop.superProperties || []), expression] });
            break;
          case "inverse":
            await ontologyMutationService.addInverseProperty(projectId, selectedItem.id, expression, user?.email || "anonymous", user?.username || "Anonymous");
            updateItemInState({ ...selectedItem, inverseProperties: [...(prop.inverseProperties || []), expression] });
            break;
          case "disjoint":
            await ontologyMutationService.addDisjointProperty(projectId, selectedItem.id, expression, user?.email || "anonymous", user?.username || "Anonymous");
            updateItemInState({ ...selectedItem, disjointProperties: [...(prop.disjointProperties || []), expression] });
            break;
          case "equivalent":
            await ontologyMutationService.addEquivalentProperty(projectId, selectedItem.id, expression, user?.email || "anonymous", user?.username || "Anonymous");
            updateItemInState({ ...selectedItem, equivalentProperties: [...(prop.equivalentProperties as string[] || []), expression] });
            break;
        }
      }
    } catch (error) {
      console.error(`Failed to ${editing ? 'replace' : 'add'} ${selectorTarget}`, error);
    } finally {
      setIsObjectPropertyExpressionDialogOpen(false);
      setIsPropertyExpressionDialogOpen(false);
      setSelectorTarget(null);
      setSelectorEditingItem(null);
    }
  };

  // Handler for the new ObjectPropertyExpressionDialog with inverse support
  const handleObjectPropertySelected = async (expression: string, isInverse: boolean) => {
    if (!selectedItem || !projectId || !selectorTarget) return;
    const target = selectorTarget as "subProperty" | "inverse" | "disjoint" | "equivalent";
    const editing = selectorEditingItem;

    try {
      const finalExpression = isInverse ? `inverse(${expression})` : expression;

      if (editing) {
        // Replace: single server-side call — delete old + add new atomically
        await ontologyMutationService.editRelation(projectId, {
          operation: 'edit',
          entityIri: selectedItem.id,
          relationshipType: target,
          oldTargetIri: editing,
          targetIri: finalExpression,
          userId: user?.email || "anonymous",
          username: user?.username || "Anonymous",
        });
        const replace = (arr: string[] | undefined) =>
          (arr || []).map(v => v === editing ? finalExpression : v);
        const prop = selectedItem as Property;
        if (target === "subProperty")  updateItemInState({ ...selectedItem, superProperties: replace(prop.superProperties) });
        if (target === "inverse")      updateItemInState({ ...selectedItem, inverseProperties: replace(prop.inverseProperties) });
        if (target === "disjoint")     updateItemInState({ ...selectedItem, disjointProperties: replace(prop.disjointProperties) });
        if (target === "equivalent")   updateItemInState({ ...selectedItem, equivalentProperties: replace(prop.equivalentProperties as string[] | undefined) });
      } else {
        switch (target) {
          case "subProperty":
            await ontologyMutationService.addSubPropertyOf(
              projectId, selectedItem.id, expression,
              user?.email || "anonymous", user?.username || "Anonymous",
            );
            updateItemInState({
              ...selectedItem,
              superProperties: [...((selectedItem as Property).superProperties || []), expression],
            });
            break;
          case "inverse":
            await ontologyMutationService.addInverseProperty(
              projectId, selectedItem.id, expression,
              user?.email || "anonymous", user?.username || "Anonymous",
            );
            updateItemInState({
              ...selectedItem,
              inverseProperties: [...((selectedItem as Property).inverseProperties || []), expression],
            });
            break;
          case "disjoint":
            await ontologyMutationService.addDisjointProperty(
              projectId, selectedItem.id, finalExpression,
              user?.email || "anonymous", user?.username || "Anonymous",
            );
            updateItemInState({
              ...selectedItem,
              disjointProperties: [...((selectedItem as Property).disjointProperties || []), finalExpression],
            });
            break;
          case "equivalent": {
            const existing = (selectedItem as Property).equivalentProperties || [];
            await ontologyMutationService.addEquivalentProperty(
              projectId, selectedItem.id, finalExpression,
              user?.email || "anonymous", user?.username || "Anonymous",
            );
            updateItemInState({ ...selectedItem, equivalentProperties: [...existing as string[], finalExpression] });
            break;
          }
        }
      }
    } catch (error) {
      console.error(`Failed to ${editing ? 'replace' : 'add'} ${target}`, error);
    } finally {
      setIsObjectPropertyExpressionDialogOpen(false);
      setSelectorTarget(null);
      setSelectorEditingItem(null);
    }
  };

  // Handlers for Annotation Property Description Dialogs (Protégé-style)
  const [annotationEditingItem, setAnnotationEditingItem] = useState<{ rel: 'domain'|'range'|'subProperty'; iri: string } | null>(null);

  const handleOpenAnnotationDomainDialog = (editingItem?: string) => {
    if (entitiesTab === "AnnotationProperties") {
      setAnnotationEditingItem(editingItem ? { rel: 'domain', iri: editingItem } : null);
      setIsAnnotationDomainDialogOpen(true);
    }
  };

  const handleOpenAnnotationRangeDialog = (editingItem?: string) => {
    if (entitiesTab === "AnnotationProperties") {
      setAnnotationEditingItem(editingItem ? { rel: 'range', iri: editingItem } : null);
      setIsAnnotationRangeDialogOpen(true);
    }
  };

  const handleOpenAnnotationSuperpropertyDialog = (editingItem?: string) => {
    if (entitiesTab === "AnnotationProperties") {
      setAnnotationEditingItem(editingItem ? { rel: 'subProperty', iri: editingItem } : null);
      setIsAnnotationSuperpropertyDialogOpen(true);
    }
  };

  const handleAnnotationDomainConfirm = async (domainIri: string) => {
    if (!selectedItem || !projectId) return;
    const editing = annotationEditingItem?.rel === 'domain' ? annotationEditingItem.iri : null;
    try {
      if (editing) {
        await ontologyMutationService.editRelation(projectId, { operation: 'edit', entityIri: selectedItem.id, relationshipType: 'domain', oldTargetIri: editing, targetIri: domainIri, userId: user?.email || "anonymous", username: user?.username || "Anonymous" });
        const extendedItem = selectedItem as AnnotationProperty & { domains?: string[] };
        updateItemInState({ ...selectedItem, domains: (extendedItem.domains || []).map(d => d === editing ? domainIri : d) });
      } else {
        await ontologyMutationService.addPropertyDomain(projectId, selectedItem.id, domainIri, user?.email || "anonymous", user?.username || "Anonymous");
        const extendedItem = selectedItem as AnnotationProperty & { domains?: string[] };
        updateItemInState({ ...selectedItem, domains: [...(extendedItem.domains || []), domainIri] });
      }
    } catch (error) {
      console.error("Failed to add/replace annotation property domain", error);
    } finally {
      setIsAnnotationDomainDialogOpen(false);
      setAnnotationEditingItem(null);
    }
  };

  const handleAnnotationRangeConfirm = async (rangeIri: string) => {
    if (!selectedItem || !projectId) return;
    const editing = annotationEditingItem?.rel === 'range' ? annotationEditingItem.iri : null;
    try {
      if (editing) {
        await ontologyMutationService.editRelation(projectId, { operation: 'edit', entityIri: selectedItem.id, relationshipType: 'range', oldTargetIri: editing, targetIri: rangeIri, userId: user?.email || "anonymous", username: user?.username || "Anonymous" });
        const extendedItem = selectedItem as AnnotationProperty & { ranges?: string[] };
        updateItemInState({ ...selectedItem, ranges: (extendedItem.ranges || []).map(r => r === editing ? rangeIri : r) });
      } else {
        await ontologyMutationService.addPropertyRange(projectId, selectedItem.id, rangeIri, user?.email || "anonymous", user?.username || "Anonymous");
        const extendedItem = selectedItem as AnnotationProperty & { ranges?: string[] };
        updateItemInState({ ...selectedItem, ranges: [...(extendedItem.ranges || []), rangeIri] });
      }
    } catch (error) {
      console.error("Failed to add/replace annotation property range", error);
    } finally {
      setIsAnnotationRangeDialogOpen(false);
      setAnnotationEditingItem(null);
    }
  };

  const handleAnnotationSuperpropertyConfirm = async (superpropertyIri: string) => {
    if (!selectedItem || !projectId) return;
    const editing = annotationEditingItem?.rel === 'subProperty' ? annotationEditingItem.iri : null;
    try {
      if (editing) {
        await ontologyMutationService.editRelation(projectId, { operation: 'edit', entityIri: selectedItem.id, relationshipType: 'subProperty', oldTargetIri: editing, targetIri: superpropertyIri, userId: user?.email || "anonymous", username: user?.username || "Anonymous" });
        const extendedItem = selectedItem as AnnotationProperty & { superProperties?: string[] };
        updateItemInState({ ...selectedItem, superProperties: (extendedItem.superProperties || []).map(p => p === editing ? superpropertyIri : p) });
      } else {
        await ontologyMutationService.addSubPropertyOf(projectId, selectedItem.id, superpropertyIri, user?.email || "anonymous", user?.username || "Anonymous");
        const extendedItem = selectedItem as AnnotationProperty & { superProperties?: string[] };
        updateItemInState({ ...selectedItem, superProperties: [...(extendedItem.superProperties || []), superpropertyIri] });
      }
    } catch (error) {
      console.error("Failed to add/replace annotation property superproperty", error);
    } finally {
      setIsAnnotationSuperpropertyDialogOpen(false);
      setAnnotationEditingItem(null);
    }
  };

  // #endregion

  // #region Main Render
  // Define apiBaseUrl for plugin usage - use deployment-aware fallback
  const apiBaseUrl = getBaseUrl();

  const ALL_MAIN_TABS: Record<string, { label: string; icon: React.ElementType }> = {
    ActiveOntology: { label: "Active ontology", icon: FileText },
    Entities: { label: "Entities", icon: List },
    Graph: { label: "Graph", icon: Share2 },
    IndividualsByClass: { label: "Individuals by class", icon: Eye },
    DLQuery: { label: "DL Query", icon: Code },
    CodeView: { label: "Code View", icon: Code },
    SPARQL: { label: "SPARQL Query", icon: DatabaseZap },
    WebVOWL: { label: "WebVOWL", icon: Network },
    SWRL: { label: "SWRL Rules", icon: Code },
    Fuzzy: { label: "Fuzzy Ontology", icon: Sparkles },
    Changes: { label: "Change Assistant", icon: GitBranch },
    Reasoner: { label: "Reasoner", icon: Zap },
  };

  // Don't show welcome screen - just render empty editor if no project loaded
  // User can click the file selector in the header to browse projects

  return (
    <>
      <LoadingDialog
        isOpen={isInitialLoading || showLoadingChoice || isExpectingFileReady || !!loadFailure}
        projectName={loadingProjectName || undefined}
        loadingStatusMessage={loadingStatusMessage || undefined}
        progress={backgroundImportProgress}
        queuePosition={queuePosition}
        totalInQueue={totalInQueue}
        estimatedWaitTimeMs={estimatedWaitTimeMs}
        inImportQueue={inImportQueue}
        readyToBrowse={importReadyToBrowse}
        failed={!!loadFailure}
        failureMessage={loadFailure?.message}
        onRetry={handleLoadRetry}
        onOpenAnotherFile={handleLoadOpenAnother}
        onBrowseNow={() => {
          setImportReadyToBrowse(false);
          setLoadFailure(null);
          setIsInitialLoading(false);
          setShowLoadingChoice(false);
          setIsExpectingFileReady(false);
          setBackgroundImportActive(false);
          setShowProjectSelector(false);
          if (projectId) {
            void fetchData(projectId);
          }
        }}
      />
      <CreateIndividualModal
        isOpen={isCreateIndividualModalOpen}
        onClose={() => setCreateIndividualModalOpen(false)}
        onCreate={handleAddIndividual}
      />
      <CreateIndividualModal
        isOpen={isCreateIndividualForClassOpen}
        onClose={() => setCreateIndividualForClassOpen(false)}
        onCreate={async (name: string) => {
          if (!projectId || !selectedClassForIndividuals) return;
          try {
            await ontologyMutationService.addIndividual(projectId, name, selectedClassForIndividuals.id);
            await loadClassInstances();
            setCreateIndividualForClassOpen(false);
          } catch (error) {
            console.error("[Dashboard] Failed to create individual:", error);
            notificationService.error("Create Failed", "Could not create individual.");
          }
        }}
      />
      <AddClassDialog
        isOpen={isAddClassDialogOpen}
        onClose={() => setAddClassDialogOpen(false)}
        onCreate={handleCreateClass}
        type={addClassType}
        parentLabel={classParentLabel}
        syncMode={syncMode}
      />
      <AddObjectPropertyDialog
        isOpen={isAddPropertyDialogOpen}
        onClose={() => setAddPropertyDialogOpen(false)}
        onCreate={
          entitiesTab === "ObjectProperties"
            ? handleCreateObjectProperty
            : entitiesTab === "DataProperties"
              ? handleCreateDataProperty
              : handleCreateAnnotationProperty
        }
        type={addPropertyType}
        parentLabel={propertyParentLabel}
        propertyType={
          entitiesTab === "ObjectProperties" ? "object" : entitiesTab === "DataProperties" ? "data" : "annotation"
        }
      />
      <AddDatatypeDialog
        isOpen={isAddDatatypeDialogOpen}
        onClose={() => setAddDatatypeDialogOpen(false)}
        onCreate={handleCreateDatatype}
      />
      <AddAnnotationDialog
        isOpen={isAddAnnotationDialogOpen}
        onClose={() => setAddAnnotationDialogOpen(false)}
        onAdd={handleAnnotationDialogAdd}
        availableProperties={annotationProperties}
        entities={{
          classes: classHierarchy,
          objectProperties: objectProperties,
          dataProperties: dataProperties,
          individuals: individuals,
        }}
        onCreateProperty={handleDialogCreateAnnotationProperty}
        onRefreshProperties={handleRefreshAnnotationProperties}
        ontologyNamespace={metadata?.ontologyIRI ? `${metadata.ontologyIRI}#` : undefined}
      />
      <AddAnnotationDialog
        isOpen={isEditAnnotationDialogOpen}
        onClose={() => {
          setEditAnnotationDialogOpen(false);
          setEditAnnotationData(null);
        }}
        onAdd={(propertyIri, newValue, datatype, lang) => {
          if (editAnnotationData) {
            handleAnnotationDialogEdit(
              propertyIri,
              editAnnotationData.currentValue,
              newValue,
              datatype,
              lang,
              editAnnotationData.originalPropertyIri || editAnnotationData.propertyIri,
              editAnnotationData.language,
              editAnnotationData.datatype,
            );
          }
          setEditAnnotationDialogOpen(false);
          setEditAnnotationData(null);
        }}
        availableProperties={annotationProperties}
        entities={{
          classes: classHierarchy,
          objectProperties: objectProperties,
          dataProperties: dataProperties,
          individuals: individuals,
        }}
        editMode={true}
        initialProperty={editAnnotationData?.propertyIri || ""}
        initialValue={editAnnotationData?.currentValue || ""}
        initialLang={editAnnotationData?.language || ""}
        initialDatatype={editAnnotationData?.datatype || ""}
        onCreateProperty={handleDialogCreateAnnotationProperty}
        onRefreshProperties={handleRefreshAnnotationProperties}
        ontologyNamespace={metadata?.ontologyIRI ? `${metadata.ontologyIRI}#` : undefined}
      />
      <AddImportDialog
        isOpen={showImportDialog}
        onClose={() => setShowImportDialog(false)}
        onAdd={async (importIri) => {
          if (!projectId) return;
          await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, { importIri, ...draftBodyFields() });
          const importsRes = await apiClient.get<any>(withDraftScope(`/api/ontology/metadata/${projectId}/imports`));
          const importsPayload = importsRes?.data?.data ?? importsRes?.data ?? importsRes;
          const importsData = Array.isArray(importsPayload) ? importsPayload : [];
          setOntologyImports(importsData);
          showNotification("Import added successfully", "info");
        }}
      />
      <GCIEditorDialog
        isOpen={isGCIEditorDialogOpen}
        onClose={() => {
          setGCIEditorDialogOpen(false);
          setEditGCIData(null);
        }}
        onSave={handleSaveGCI}
        initialSubClass={editGCIData?.subClass}
        initialSuperClass={editGCIData?.superClass}
        editMode={!!editGCIData}
        availableClasses={(() => {
          const extractClasses = (nodes: TreeNode[]): Array<{ id: string; label: string }> => {
            const classes: Array<{ id: string; label: string }> = [];
            const traverse = (node: TreeNode) => {
              classes.push({ id: node.id, label: node.label });
              if (node.children) {
                node.children.forEach(traverse);
              }
            };
            nodes.forEach(traverse);
            return classes;
          };
          return extractClasses(classHierarchy);
        })()}
        projectId={projectId}
      />
      <EditOntologyIRIDialog
        isOpen={isEditOntologyIRIDialogOpen}
        onClose={() => setEditOntologyIRIDialogOpen(false)}
        onSave={handleSaveOntologyIRIs}
        initialOntologyIri={(metadata as any)?.ontologyIRI || ""}
        initialVersionIri={(metadata as any)?.versionIRI || ""}
      />
      <EditEntityIRIDialog
        isOpen={isEditEntityIRIDialogOpen}
        onClose={() => {
          setIsEditEntityIRIDialogOpen(false);
          setEditEntityIRITarget(null);
        }}
        onSave={handleSaveEntityIri}
        currentIri={editEntityIRITarget?.id || ""}
        entityLabel={editEntityIRITarget?.label}
      />
      <GCIEditorDialog
        isOpen={axiomDialogOpen}
        onClose={() => {
          setAxiomDialogOpen(false);
          setEditingAxiomIndex(null);
          setAxiomDraft({ definition: "", superClassIri: "" });
        }}
        onSave={async (subClass, superClass) => {
          if (editingAxiomIndex !== null) {
            await handleUpdateAxiom(subClass, superClass);
          } else {
            await handleAddAxiom(subClass, superClass);
          }
        }}
        initialSubClass={axiomDraft.definition}
        initialSuperClass={axiomDraft.superClassIri}
        editMode={editingAxiomIndex !== null}
        availableClasses={(() => {
          const extractClasses = (nodes: TreeNode[]): Array<{ id: string; label: string }> => {
            const classes: Array<{ id: string; label: string }> = [];
            const traverse = (node: TreeNode) => {
              classes.push({ id: node.id, label: node.label });
              if (node.children) {
                node.children.forEach(traverse);
              }
            };
            nodes.forEach(traverse);
            return classes;
          };
          return extractClasses(classHierarchy);
        })()}
        projectId={projectId}
      />
      <AddAnnotationDialog
        isOpen={isOntologyAnnotationDialogOpen}
        onClose={() => {
          setIsOntologyAnnotationDialogOpen(false);
          setOntologyAnnotationEditTarget(null);
        }}
        onAdd={(propertyIri, value, datatype, lang) => {
          if (ontologyAnnotationEditTarget) {
            handleUpdateOntologyAnnotation(
              propertyIri,
              ontologyAnnotationEditTarget.value,
              value,
              ontologyAnnotationEditTarget.datatype,
              datatype,
              lang,
            );
          } else {
            handleAddOntologyAnnotation(propertyIri, value, datatype, lang);
          }
          setIsOntologyAnnotationDialogOpen(false);
          setOntologyAnnotationEditTarget(null);
        }}
        availableProperties={annotationProperties}
        editMode={!!ontologyAnnotationEditTarget}
        initialProperty={ontologyAnnotationEditTarget?.propertyIri || ""}
        initialValue={ontologyAnnotationEditTarget?.value || ""}
        initialDatatype={shortenDatatype(ontologyAnnotationEditTarget?.datatype)}
        onCreateProperty={handleDialogCreateAnnotationProperty}
        onRefreshProperties={handleRefreshAnnotationProperties}
        ontologyNamespace={metadata?.ontologyIRI ? `${metadata.ontologyIRI}#` : undefined}
      />
      <AddAnnotationDialog
        isOpen={isQuickNoteDialogOpen}
        onClose={() => {
          setQuickNoteDialogOpen(false);
          setQuickEditNoteItem(null);
        }}
        onAdd={async (propertyIri, value) => {
          if (!projectId || !quickEditNoteItem) return;
          try {
            const annotations = (quickEditNoteItem as any).annotations || {};
            const existingValue = annotations[propertyIri];
            if (existingValue) {
              await ontologyMutationService.updateAnnotation(
                projectId,
                quickEditNoteItem.id,
                propertyIri,
                value,
                user?.email || "anonymous",
                user?.username || "Anonymous",
                String(existingValue),
              );
            } else {
              await ontologyMutationService.addAnnotation(
                projectId,
                quickEditNoteItem.id,
                propertyIri,
                value,
                user?.email || "anonymous",
                user?.username || "Anonymous",
              );
            }
            updateItemInState({
              ...quickEditNoteItem,
              annotations: { ...annotations, [propertyIri]: value },
            } as SelectableItem);
          } catch (error) {
            console.error("[Dashboard] Failed to save quick note:", error);
            notificationService.error("Quick Note Failed", "Could not save note.");
          } finally {
            setQuickNoteDialogOpen(false);
            setQuickEditNoteItem(null);
          }
        }}
        availableProperties={annotationProperties}
        editMode={true}
        initialProperty={"http://www.w3.org/2000/01/rdf-schema#comment"}
        initialValue={
          quickEditNoteItem && (quickEditNoteItem as any).annotations
            ? String((quickEditNoteItem as any).annotations["http://www.w3.org/2000/01/rdf-schema#comment"] || "")
            : ""
        }
      />
      <AddAnnotationDialog
        isOpen={isClassIndividualAnnotationDialogOpen}
        onClose={() => setClassIndividualAnnotationDialogOpen(false)}
        onAdd={async (propertyIri, value) => {
          if (!projectId || !selectedClassIndividualDetails) return;
          try {
            await ontologyMutationService.addAnnotation(
              projectId,
              selectedClassIndividualDetails.id,
              propertyIri,
              value,
            );
            markAsUnsaved();
            await refreshSelectedClassIndividualDetails();
          } catch (error) {
            console.error("[Dashboard] Failed to add annotation:", error);
            notificationService.error("Annotation Failed", "Could not add annotation.");
          }
        }}
        availableProperties={annotationProperties}
      />
      <ClassSelectorDialog
        isOpen={isClassIndividualTypeDialogOpen}
        onClose={() => setClassIndividualTypeDialogOpen(false)}
        onSelect={async (node) => {
          if (!projectId || !selectedClassIndividualDetails) return;
          try {
            await ontologyMutationService.addClassAssertion(projectId, selectedClassIndividualDetails.id, node.id);
            markAsUnsaved();
            if (selectedClassForIndividuals?.id === node.id) {
              await loadClassInstances();
            }
            await refreshSelectedClassIndividualDetails();
          } catch (error) {
            console.error("[Dashboard] Failed to add type assertion:", error);
            notificationService.error("Type Failed", "Could not add type assertion.");
          } finally {
            setClassIndividualTypeDialogOpen(false);
          }
        }}
        classHierarchy={classHierarchy}
        projectId={projectId || undefined}
        onToggleNode={toggleNode}
        externalExpandedNodes={expandedNodes}
        title="Add type"
        onAddClass={handleAddClassInline}
        onDeleteClass={() => handleDeleteItem()}
        metadata={metadata}
      />
      {classIndividualSameDiffDialog && (
        <IndividualSelectorDialog
          isOpen={true}
          onClose={() => setClassIndividualSameDiffDialog(null)}
          title={
            classIndividualSameDiffDialog.mode === "same"
              ? `Same Individual As: ${selectedClassIndividualDetails?.label || ""}`
              : `Different Individuals: ${selectedClassIndividualDetails?.label || ""}`
          }
          individuals={classIndividualCandidateIndividuals}
          projectId={projectId || undefined}
          excludeIndividualIds={[
            selectedClassIndividualDetails?.id || "",
            ...(classIndividualSameDiffDialog.mode === "same"
              ? selectedClassIndividualDetails?.sameIndividualAs || []
              : selectedClassIndividualDetails?.differentIndividualFrom || []),
          ].filter(Boolean)}
          minSelection={1}
          onConfirm={async (selectedIndividuals) => {
            if (!projectId || !selectedClassIndividualDetails) return;
            try {
              for (const individual of selectedIndividuals) {
                if (classIndividualSameDiffDialog.mode === "same") {
                  await ontologyMutationService.addSameIndividual(
                    projectId,
                    selectedClassIndividualDetails.id,
                    individual.id,
                  );
                } else {
                  await ontologyMutationService.addDifferentIndividual(
                    projectId,
                    selectedClassIndividualDetails.id,
                    individual.id,
                  );
                }
              }
              await refreshSelectedClassIndividualDetails();
            } catch (error) {
              console.error("[Dashboard] Failed to add same/different individual assertion:", error);
              notificationService.error("Add Failed", "Could not add same/different individual assertion.");
            } finally {
              setClassIndividualSameDiffDialog(null);
            }
          }}
        />
      )}
      <PropertyAssertionDialog
        isOpen={isClassIndividualPropertyDialogOpen}
        title={classIndividualPropertyIsObject ? "Add object property assertion" : "Add data property assertion"}
        isObjectProperty={classIndividualPropertyIsObject}
        objectPropertiesTree={objectPropertyHierarchy}
        dataPropertiesTree={dataPropertyHierarchy}
        onConfirm={async (data) => {
          if (!projectId || !selectedClassIndividualDetails) return;
          try {
            if (data.isObjectProperty) {
              const propertyIri = resolvePropertyIriByLabel(data.propertyLabel, objectProperties);
              const targetIri = resolveIndividualIriByLabel(data.targetLabel);
              if (!propertyIri || !targetIri) {
                notificationService.error("Add Failed", "Property or target individual not found.");
                return;
              }
              await ontologyMutationService.addObjectPropertyAssertion(
                projectId,
                selectedClassIndividualDetails.id,
                propertyIri,
                targetIri,
              );
            } else {
              const propertyIri = resolvePropertyIriByLabel(data.propertyLabel, dataProperties);
              if (!propertyIri) {
                notificationService.error("Add Failed", "Data property not found.");
                return;
              }
              await ontologyMutationService.addDataPropertyAssertion(
                projectId,
                selectedClassIndividualDetails.id,
                propertyIri,
                data.targetLabel,
              );
            }
            await refreshSelectedClassIndividualDetails();
          } catch (error) {
            console.error("[Dashboard] Failed to add property assertion:", error);
            notificationService.error("Add Failed", "Could not add property assertion.");
          } finally {
            setClassIndividualPropertyDialogOpen(false);
          }
        }}
        onCancel={() => setClassIndividualPropertyDialogOpen(false)}
      />
      <OpenFileDialog
        isOpen={showOpenDialog}
        onClose={() => {
          console.log(
            "[Dashboard] Closing OpenFileDialog. myFiles:",
            myFiles.length,
            "sharedFiles:",
            sharedFiles.length,
            "projectFiles:",
            projectFiles.length,
          );
          setShowOpenDialog(false);
        }}
        myFiles={myFiles}
        sharedFiles={sharedFiles}
        currentProjectId={projectId}
        currentFileId={activeFileId}
        currentFileName={activeFileName}
        onDeleteFile={user?.workspaceId ? undefined : handleDeleteFile}
        onSwitchFile={handleSwitchFile}
        parentProjectId={initialProjectId}
        onLoadProjectFile={handleLoadProjectFile}
        projectFiles={projectFiles}
        importMode={importMode}
        partitionStrategy={partitionStrategy}
        onImportModeChange={setImportMode}
        onPartitionStrategyChange={setPartitionStrategy}
        isWorkspaceMode={!!user?.workspaceId}
        onRefresh={
          initialProjectId
            ? () => {
                console.log("[Dashboard] 🔄 Refresh triggered from OpenFileDialog");
                fetchProjectFiles(initialProjectId);
              }
            : undefined
        }
        onCreateNewFile={() => { autoLoadNewFileRef.current = true; }}
        isPlanExpired={openFileIsPlanExpired}
      />
      <DuplicateFileDialog
        isOpen={duplicatePrompt.isOpen}
        fileName={duplicatePrompt.fileName}
        detail={duplicatePrompt.detail}
        copyName={duplicateCopyName}
        onCopyNameChange={setDuplicateCopyName}
        onOpenExisting={() => sendDuplicatePromptResponse("open_existing")}
        onReplace={() => sendDuplicatePromptResponse("replace")}
        onCreateCopy={handleDuplicateCreateCopy}
        onCancel={handleDuplicatePromptCancel}
        allowOpenExisting={duplicatePrompt.allowOpenExisting}
        error={duplicateCopyError}
        isSubmitting={duplicateCopySubmitting}
      />
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        onClose={() => setConfirmDialog((prev) => ({ ...prev, isOpen: false }))}
        onConfirm={confirmDialog.onConfirm}
        onCancel={confirmDialog.onCancel}
        title={confirmDialog.title}
        message={confirmDialog.message}
        confirmLabel={confirmDialog.confirmLabel}
        cancelLabel={confirmDialog.cancelLabel}
      />
      <DeleteClassDialog
        isOpen={deleteClassDialog.isOpen}
        onClose={() => setDeleteClassDialog((prev) => ({ ...prev, isOpen: false }))}
        label={deleteClassDialog.label}
        fetchDescendants={() =>
          projectId
            ? ontologyMutationService.getDescendants(projectId, deleteClassDialog.iri)
            : Promise.resolve({ iris: [], truncated: false })
        }
        onConfirm={(withDescendants, descendantIris) => {
          void performDeleteClasses(withDescendants ? [deleteClassDialog.iri, ...descendantIris] : [deleteClassDialog.iri]);
        }}
      />
      <SaveErrorDialog
        isOpen={!!codeViewSaveError}
        error={codeViewSaveError || ""}
        onClose={() => setCodeViewSaveError(null)}
        onRetry={() => {
          setCodeViewSaveError(null);
          void handleSaveCodeContent(lastCodeViewSaveContentRef.current);
        }}
      />
      {publishConflictDialog.isOpen && (() => {
        const conflicts = publishConflictDialog.conflicts;
        const resolvedCount = conflicts.filter((c) => conflictResolutions[c.entityIRI]).length;
        const allResolved = conflicts.length > 0 && resolvedCount === conflicts.length;
        return (
        <div className="fixed inset-0 bg-black bg-opacity-60 flex items-center justify-center z-[9999] p-4">
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-3xl max-h-[90vh] flex flex-col" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center gap-3 px-6 py-4 border-b border-gray-200">
              <div className="flex-shrink-0 w-9 h-9 rounded-full bg-amber-100 flex items-center justify-center">
                <svg className="w-5 h-5 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-base font-semibold text-gray-900">{publishConflictDialog.title}</h3>
                <p className="text-xs text-gray-500 mt-0.5">{publishConflictDialog.message}</p>
              </div>
              {conflicts.length > 0 && (
                <span className="text-xs font-medium px-2 py-1 rounded-full bg-gray-100 text-gray-600 whitespace-nowrap">
                  {resolvedCount}/{conflicts.length} resolved
                </span>
              )}
            </div>

            {/* Conflict list */}
            <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
              {conflicts.length === 0 ? (
                <p className="text-sm text-gray-500 text-center py-8">No per-entity conflicts detected. You can merge or force publish.</p>
              ) : conflicts.map((c) => {
                const label = c.entityLabel || c.entityIRI.split(/[#/]/).pop() || c.entityIRI;
                const chosen = conflictResolutions[c.entityIRI];
                return (
                  <div key={c.entityIRI} className={`border rounded-lg overflow-hidden transition-colors ${chosen ? "border-green-300 bg-green-50/30" : "border-gray-200"}`}>
                    {/* Entity header */}
                    <div className="flex items-center gap-2 px-4 py-2.5 bg-gray-50 border-b border-gray-200">
                      <svg className="w-4 h-4 text-gray-400 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z" />
                      </svg>
                      <span className="text-sm font-medium text-gray-800 flex-1 truncate" title={c.entityIRI}>{label}</span>
                      {c.changedBy && <span className="text-xs text-gray-400">changed by {c.changedBy}</span>}
                      {chosen && (
                        <span className="text-xs font-medium text-green-700 bg-green-100 px-2 py-0.5 rounded-full">
                          {chosen === "KEEP_TARGET" ? "Keeping theirs" : chosen === "KEEP_SOURCE" ? "Keeping mine" : "Keeping both"}
                        </span>
                      )}
                    </div>
                    {/* Left / Right axiom comparison */}
                    <div className="grid grid-cols-2 divide-x divide-gray-200">
                      <div className="p-3">
                        <div className="text-xs font-semibold text-blue-700 mb-1.5 flex items-center gap-1">
                          <span className="w-2 h-2 rounded-full bg-blue-500 inline-block"></span>
                          Public (theirs)
                        </div>
                        <pre className="text-xs text-gray-700 whitespace-pre-wrap break-all font-mono leading-relaxed min-h-[40px]">
                          {c.mainAxioms || <span className="text-gray-400 italic">No axioms</span>}
                        </pre>
                      </div>
                      <div className="p-3">
                        <div className="text-xs font-semibold text-purple-700 mb-1.5 flex items-center gap-1">
                          <span className="w-2 h-2 rounded-full bg-purple-500 inline-block"></span>
                          Your draft
                        </div>
                        <pre className="text-xs text-gray-700 whitespace-pre-wrap break-all font-mono leading-relaxed min-h-[40px]">
                          {c.yourAxioms || <span className="text-gray-400 italic">No axioms</span>}
                        </pre>
                      </div>
                    </div>
                    {/* Resolution buttons */}
                    <div className="flex items-center gap-2 px-4 py-2.5 bg-gray-50 border-t border-gray-200">
                      <span className="text-xs text-gray-500 mr-1">Use:</span>
                      {(["KEEP_TARGET", "KEEP_SOURCE", "MERGE"] as const).map((action) => {
                        const labels: Record<string, string> = { KEEP_TARGET: "Keep Theirs", KEEP_SOURCE: "Keep Mine", MERGE: "Keep Both" };
                        const colors: Record<string, string> = {
                          KEEP_TARGET: chosen === action ? "bg-blue-600 text-white border-blue-600" : "bg-white text-blue-700 border-blue-300 hover:bg-blue-50",
                          KEEP_SOURCE: chosen === action ? "bg-purple-600 text-white border-purple-600" : "bg-white text-purple-700 border-purple-300 hover:bg-purple-50",
                          MERGE: chosen === action ? "bg-green-600 text-white border-green-600" : "bg-white text-green-700 border-green-300 hover:bg-green-50",
                        };
                        return (
                          <button
                            key={action}
                            onClick={() => setConflictResolutions((prev) => ({ ...prev, [c.entityIRI]: action }))}
                            className={`px-3 py-1 text-xs font-medium rounded border transition-colors ${colors[action]}`}
                          >
                            {labels[action]}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Footer */}
            <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-gray-200 bg-gray-50 rounded-b-xl">
              <button
                onClick={publishConflictDialog.onForce}
                className="px-3 py-1.5 text-xs font-medium text-amber-700 bg-amber-50 border border-amber-200 rounded-md hover:bg-amber-100"
              >
                Overwrite (force publish)
              </button>
              <div className="flex gap-3">
                <button
                  onClick={() => setPublishConflictDialog((prev) => ({ ...prev, isOpen: false }))}
                  className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  disabled={conflicts.length > 0 && !allResolved}
                  onClick={() => {
                    setPublishConflictDialog((prev) => ({ ...prev, isOpen: false }));
                    const resolutions: Record<string, { action: string }> = {};
                    Object.entries(conflictResolutions).forEach(([iri, action]) => { resolutions[iri] = { action }; });
                    void handleSave({ merge: true, resolutions: Object.keys(resolutions).length > 0 ? resolutions : undefined });
                  }}
                  className={`px-4 py-2 text-sm font-medium rounded-md transition-colors ${
                    conflicts.length > 0 && !allResolved
                      ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                      : "bg-purple-600 text-white hover:bg-purple-700"
                  }`}
                >
                  {allResolved || conflicts.length === 0 ? "Apply & Publish" : `Resolve ${conflicts.length - resolvedCount} more…`}
                </button>
              </div>
            </div>
          </div>
        </div>
        );
      })()}
      {/* Dedicated Unsaved Changes Warning Dialog */}
      {unsavedChangesDialog.isOpen && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[9999]">
          <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center gap-3 mb-4">
              <div className="flex-shrink-0 w-10 h-10 rounded-full bg-yellow-100 flex items-center justify-center">
                <svg className="w-5 h-5 text-yellow-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
              </div>
              <h3 className="text-lg font-semibold text-gray-900">Unsaved Changes</h3>
            </div>
            <p className="text-sm text-gray-600 mb-6">
              You have unsaved changes. Are you sure you want to leave? Your changes will be lost.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setUnsavedChangesDialog((prev) => ({ ...prev, isOpen: false }))}
                className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
              >
                Stay
              </button>
              <button
                onClick={unsavedChangesDialog.onLeave}
                className="px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-md hover:bg-red-700"
              >
                Leave
              </button>
            </div>
          </div>
        </div>
      )}
      <ConfirmDialog
        isOpen={deleteFileDialog.isOpen}
        onClose={() => setDeleteFileDialog({ isOpen: false, projectId: "", fileName: "" })}
        onConfirm={confirmDeleteFile}
        onCancel={() => setDeleteFileDialog({ isOpen: false, projectId: "", fileName: "" })}
        title="Delete File"
        message={`Are you sure you want to delete "${deleteFileDialog.fileName}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
      />
      <KeyboardShortcutsDialog
        isOpen={isKeyboardShortcutsDialogOpen}
        onClose={() => setKeyboardShortcutsDialogOpen(false)}
      />
      <EntityPreferencesDialog
        isOpen={isEntityPreferencesDialogOpen}
        onClose={() => setEntityPreferencesDialogOpen(false)}
        preferences={preferences}
        onSave={updatePreferences}
      />
      <ReasonerExplanationModal
        isOpen={explanationState.open}
        onClose={() => setExplanationState((prev) => ({ ...prev, open: false }))}
        data={explanationState.data}
        loading={explanationState.loading}
        error={explanationState.error}
      />
      <ReasonerSettingsDialog
        isOpen={isReasonerSettingsOpen}
        selectedReasoner={selectedReasoner}
        isSynced={isReasonerSynced}
        onSelectReasoner={handleSelectReasoner}
        onToggleSync={toggleReasonerSync}
        onClose={() => setIsReasonerSettingsOpen(false)}
      />

      {/* Full-height flex column — children control their own scroll via overflow-y-auto */}
      <div className="h-full bg-gray-50 flex flex-col text-sm overflow-y-auto min-h-0">
        {/* Persistent background import progress banner */}
        {backgroundImportActive && (
          <div className="flex flex-wrap items-center gap-2 px-3 sm:px-4 py-1.5 bg-blue-50 border-b border-blue-200 text-blue-800 text-xs z-40 shrink-0 min-w-0">
            <Loader2 size={14} className="animate-spin text-blue-600 flex-shrink-0" />
            <span className="font-medium min-w-0 flex-1 truncate sm:whitespace-normal sm:overflow-visible">
              Loading "{loadingProjectName}" in the background
              {loadingStatusMessage ? ` — ${loadingStatusMessage}` : "..."}
              {inImportQueue && queuePosition !== undefined && queuePosition > 0
                ? ` (queue #${queuePosition}${totalInQueue ? `, ${totalInQueue} waiting` : ""}${estimatedWaitTimeMs ? `, est. ${formatQueueWait(estimatedWaitTimeMs)}` : ""})`
                : ""}
            </span>
            {backgroundImportProgress !== undefined && backgroundImportProgress > 0 && (
              <>
                <div className="w-32 h-1.5 bg-blue-200 rounded-full overflow-hidden ml-2">
                  <div
                    className="h-full bg-blue-600 transition-all duration-300 rounded-full"
                    style={{ width: `${backgroundImportProgress}%` }}
                  />
                </div>
                <span className="text-blue-600 font-semibold">{Math.round(backgroundImportProgress)}%</span>
              </>
            )}
          </div>
        )}
        <TopMenuBar
          fileList={listOfFiles}
          myFiles={myFiles}
          sharedFiles={sharedFiles}
          currentProjectId={projectId}
          onShareFile={(fileId) => {
            setShareFileId(fileId);
            setIsShareDialogOpen(true);
          }}
          onSave={handleSave}
          onSwitchFile={handleSwitchFile}
          hasUnsavedChanges={hasUnsavedChanges}
          isSaving={isSaving}
          draftCount={draftCount}
          conflictStatus={publishConflictStatus}
          onOpenDialog={() => setShowOpenDialog(true)}
          onOpenPluginMarketplace={() => setShowPluginMarketplace(true)}
          hasPluginUpdates={hasPluginUpdates}
          onOpenHistory={() => setIsHistoryPanelOpen(true)}
          onReportIssue={() => setIsReportIssueModalOpen(true)}
          onOpenUserGuide={() => setIsUserGuideOpen(true)}
          onOpenReleaseNotes={() => setIsReleaseNotesOpen(true)}
          hierarchyDisplayMode={hierarchyDisplayMode}
          onHierarchyDisplayModeChange={setHierarchyDisplayMode}
          hierarchyImportsScope={hierarchyImportsScope}
          onHierarchyImportsScopeChange={setHierarchyImportsScope}
          hierarchyAnnotationProperties={hierarchyAnnotationProperties}
          hierarchyAnnotationPropIri={hierarchyAnnotationPropIri}
          onHierarchyAnnotationPropChange={setHierarchyAnnotationPropIri}
          hierarchyCustomTemplate={hierarchyCustomTemplate}
          onHierarchyCustomTemplateChange={setHierarchyCustomTemplate}
          onOpenMergeWizard={async () => {
            setMergeWizardOpen(true);
            // Fetch files from the current project to show in merge wizard
            if (projectId && !initialProjectId) {
              // Only fetch if not in admin flow (admin flow already has projectFiles loaded)
              try {
                console.log("[Dashboard] 📂 Fetching project files for merge wizard:", projectId);
                await fetchProjectFiles(projectId);
              } catch (error) {
                console.warn("[Dashboard] ⚠️ Could not fetch project files:", error);
              }
            }
          }}
          syncMode={syncMode}
          requireDraftForMembers={requireDraftForMembers}
          isProjectOwner={isProjectOwner}
          isDraftEditorRole={isProjectDraftEditorRole}
          autoDraftStatus={autoDraftStatus}
          onSwitchToDraftMode={handleSwitchToDraftMode}
          onToggleRequireDraftForMembers={() => {
            if (!projectId || !isProjectOwner) return;
            const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;
            const newValue = !requireDraftForMembers;
            draftTrackingService.setRequireDraftForMembers(projectId, effectiveUserId, newValue)
              .then(() => {
                setRequireDraftForMembers(newValue);
                notificationService.success(
                  newValue ? "Draft Mode Required" : "Public Editing Allowed",
                  newValue
                    ? "Members must now use Draft Mode before editing."
                    : "Members can now edit in Public (Live) mode."
                );
              })
              .catch(() => {
                notificationService.error("Settings Error", "Could not update draft settings.");
              });
          }}
          onToggleSyncMode={() => {
            const effectiveUserId = resolveMutationActor(user?.userId || user?.email, user?.username).userId;

            if (syncMode === "public") {
              if (!projectId) return;
              // Clear any draft-required block before starting the copy.
              ontologyMutationService.setDraftRequired(false);
              startDraftCopySession(projectId, effectiveUserId, {
                showModal: true,
                onReady: () => {
                  setSyncMode('private');
                  ontologyMutationService.setRealTimeSync(false);
                  localStorage.setItem(`ontocode_sync_mode_${projectId}`, 'private');
                  userPreferencesService.saveSyncMode(projectId, 'private');
                  notificationService.info("Draft Mode Active", "Editing your private draft — changes won't affect others until you publish.");
                  // The backend now scopes reads to the draft graph for this user — the
                  // already-loaded tree was fetched under public scope, so refresh it.
                  fetchData(projectId, false);
                },
              });
            } else {
              // Free, unconditional switch — no forced/gated pull dialog. Merging public
              // changes into a draft, or publishing a draft, are explicit actions the user
              // takes via the dedicated "Pull" and "PRs" toolbar buttons, not a side effect
              // of just changing which view you're looking at. The draft itself is untouched
              // either way — switching to Public never discards anything.
              setSyncMode('public');
              ontologyMutationService.setRealTimeSync(true);
              if (projectId) {
                localStorage.setItem(`ontocode_sync_mode_${projectId}`, 'public');
                userPreferencesService.saveSyncMode(projectId, 'public');
                // Reads now scope back to the public graph — refresh so the tree drops
                // any draft-only entities the user was just looking at.
                fetchData(projectId, false);
              }
              notificationService.info("Public View", "Your draft is preserved — toggle back to Draft Mode to resume editing.");
              if (requireDraftForMembers && !isProjectOwner) {
                ontologyMutationService.setDraftRequired(true, () => setShowProPromptType('draftRequired'));
              }
            }
          }}
          isReasonerRunning={isReasonerRunning}
          isReasonerLoading={isReasonerLoading}
          isReasonerSynced={isReasonerSynced}
          selectedReasoner={selectedReasoner}
          onStartReasoner={startReasoner}
          onStopReasoner={stopReasoner}
          onToggleReasonerSync={toggleReasonerSync}
          onSelectReasoner={handleSelectReasoner}
          onCheckConsistency={checkConsistency}
          onExplainInconsistency={explainInconsistency}
          onOpenReasonerSettings={() => setIsReasonerSettingsOpen(true)}
          isConsistencyLoading={isConsistencyLoading}
          onGoToProjectDashboard={onGoToProjectDashboard}
          onGoToWorkspace={onGoToWorkspace}
          onOpenThemeSettings={() => setShowThemeSettings(true)}
          subscription={subscription}
          onExportProAction={handleExportProAction}
          isViewOnly={isViewOnlyMember}
        />

        <div className="bg-white border-b border-gray-200 flex-shrink-0 min-w-0 overflow-hidden">
          <div className="flex flex-col px-2 sm:px-4 py-1.5 gap-2 min-w-0">
            <div className="custom-scrollbar flex items-center flex-nowrap gap-x-1 flex-1 min-w-0 overflow-x-auto">
              {visibleMainTabs.map((tabId) => {
                const tab = ALL_MAIN_TABS[tabId];
                if (!tab) return null;
                return (
                  <button
                    key={tabId}
                    className={`flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium border-b-2 whitespace-nowrap ${mainTab === tabId ? "text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent"}`}
                    onClick={() => setMainTab(tabId)}
                  >
                    <tab.icon size={14} /> {tab.label}
                  </button>
                );
              })}
            </div>
            <div className="flex items-center gap-1 sm:gap-2 flex-shrink-0 flex-wrap justify-start min-w-0">
              {isCloudDeployment && projectId && (
                <button
                  onClick={() => {
                    console.log("[Dashboard] Collaboration button clicked", {
                      subscription,
                      deploymentType,
                      isCloudDeployment,
                    });

                    // Check for collaboration access using the standardized hook
                    if (isCloudDeployment && !subscription.canAccessFeature('hasAdvancedCollaboration')) {
                      showToast(
                        "Collaboration is only available in Pro and Enterprise plans. Upgrade to enable real-time collaboration.",
                        "warning",
                      );
                      return;
                    }
                    
                    setShowCollaborationPanel(!showCollaborationPanel);
                  }}
                  // disabled={isCloudDeployment && subscription.isFree}
                  className={`flex items-center gap-1.5 px-2 py-1 text-xs rounded transition-colors ${
                    isCloudDeployment && !subscription.canAccessFeature('hasAdvancedCollaboration')
                      ? "bg-gray-100 text-gray-400 cursor-not-allowed opacity-50"
                      : showCollaborationPanel
                        ? "bg-blue-600 text-white hover:bg-blue-700"
                        : isCurrentFileShared
                          ? "bg-green-100 text-green-700 hover:bg-green-200"
                          : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                  }`}
                  title={
                    isCloudDeployment && !subscription.canAccessFeature('hasAdvancedCollaboration')
                      ? "Collaboration is only available in Pro and Enterprise plans"
                      : `Toggle Collaboration Panel${hasMultipleActiveUsers ? ` (${activeUsersInProject.length} users)` : isCurrentFileShared ? " (Shared file)" : " (Enable sharing to collaborate)"}`
                  }
                >
                  <Users size={14} />
                  <span>Collaboration</span>
                  {isCloudDeployment && !subscription.canAccessFeature('hasAdvancedCollaboration') && (
                    <span className="bg-amber-500 text-white text-[10px] px-1 rounded">PRO</span>
                  )}
                  {hasMultipleActiveUsers && (
                    <span className="bg-blue-500 text-white text-[10px] px-1.5 py-0.5 rounded-full font-semibold">
                      {activeUsersInProject.length}
                    </span>
                  )}
                  {isCurrentFileShared && !hasMultipleActiveUsers && (
                    <span className="bg-green-500 text-white text-[10px] px-1.5 py-0.5 rounded-full">✓</span>
                  )}
                </button>
              )}
              {/* {projectId && (
                <button
                  onClick={handleOpenProjectSelector}
                  className="flex items-center gap-1.5 text-xs text-gray-600 hover:text-gray-800 hover:bg-gray-100 p-2 rounded-md"
                  title="Switch Project"
                >
                  <Database size={14} />
                  <span className="max-w-[200px] truncate">{projectId}</span>
                  {hasUnsavedChanges && (
                    <span className="text-orange-600 ml-1" title="Unsaved changes">●</span>
                  )}
                  {isSaving && (
                    <Loader2 size={12} className="animate-spin ml-1 text-blue-600" />
                  )}
                </button>
              )} */}
              <span className="text-xs text-gray-600 hidden md:inline truncate max-w-[12rem] lg:max-w-none">
                Welcome, {user?.username || "Guest"}
                {user?.workspaceName && (
                  <span className="ml-2 px-2 py-0.5 bg-purple-100 text-purple-700 rounded text-[10px] font-medium">
                    {user.workspaceName}
                  </span>
                )}
              </span>
              {isProjectViewerRole && (
                <span
                  className="flex items-center gap-1 px-2 py-0.5 bg-blue-50 text-blue-600 border border-blue-200 rounded text-[10px] font-semibold select-none"
                  title="You are viewing the published version of this ontology. Contact the project owner for edit access."
                >
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
                  </svg>
                  Public View
                </span>
              )}
              {/* Pull from Public — only visible when in draft mode */}
              {syncMode === 'private' && projectId && (
                <button
                  onClick={() => setShowPullPreview(true)}
                  className="flex items-center gap-1 text-xs px-2 py-1 rounded border transition-colors"
                  style={{ borderColor: "var(--color-border)" }}
                  title="Preview and pull latest public version into your draft"
                >
                  <Download size={12} />
                  <span className="hidden sm:inline">Pull</span>
                </button>
              )}
              {/* PR button — opens DraftPRPanel for everyone (reviewers and draft editors) */}
              {showPRButton && (
                <button
                  onClick={() => {
                    setShowDraftPRPanel(true);
                    refreshOpenPRCount();
                  }}
                  className="relative flex items-center gap-1 text-xs px-2 py-1 rounded border transition-colors"
                  style={{ borderColor: "var(--color-border)" }}
                  title={canReviewPR ? "Review pull requests from contributors" : "Raise a pull request for your draft changes"}
                >
                  <GitPullRequest size={12} />
                  <span className="hidden sm:inline">PRs</span>
                  {openPRCount > 0 && (
                    <span className="absolute -top-1 -right-1 flex items-center justify-center w-4 h-4 text-[9px] font-bold rounded-full bg-blue-600 text-white">
                      {openPRCount > 9 ? "9+" : openPRCount}
                    </span>
                  )}
                </button>
              )}
              <button
                onClick={() => setShowThemeSettings(true)}
                className="ontocode-icon-hover-accent cursor-pointer disabled:cursor-not-allowed flex items-center gap-1.5 text-xs p-2 rounded-md"
                title="Theme Settings"
              >
                <Palette size={14} />
              </button>
              {onBackToProjects && (
                <button
                  onClick={handleBackToProjects}
                  className="flex items-center gap-1.5 text-xs text-blue-600 hover:text-blue-700 hover:bg-blue-50 p-2 rounded-md cursor-pointer"
                  title="Back to Projects"
                >
                  <GitBranch size={14} />
                  Projects
                </button>
              )}
              {/* Desktop download icon — hidden when already in the desktop app */}
              {!isDesktop() && (
                <a
                  href="/desktop"
                  onClick={(e) => { e.preventDefault(); window.dispatchEvent(new CustomEvent('navigate-desktop-download')); }}
                  className="flex items-center gap-1.5 text-xs text-purple-600 hover:text-purple-700 hover:bg-purple-50 p-2 rounded-md cursor-pointer"
                  title="Download OntoCode Desktop"
                >
                  <Monitor size={14} />
                  <span className="hidden sm:inline">Desktop</span>
                </a>
              )}
              {!isDesktop() && (
                <button
                  onClick={() => logout()}
                  className="flex items-center gap-1.5 text-xs text-red-500 hover:text-red-700 hover:bg-red-50 p-2 rounded-md cursor-pointer"
                >
                  <LogOut size={14} />
                  Logout
                </button>
              )}
            </div>
          </div>
        </div>

        {mainTab === "Entities" && (
          <div className="bg-gray-100 border-b border-gray-200 px-4 flex-shrink-0">
            <div className="flex items-center flex-wrap gap-1">
              {entitiesTabs.map((tab) => (
                <button
                  key={tab.id}
                  title={tab.label}
                  className={`flex items-center gap-2 px-3 py-1 text-xs font-medium border-t-2 mt-px whitespace-nowrap flex-shrink-0 ${entitiesTab === tab.id ? "bg-white text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent hover:bg-gray-200 rounded-t"}`}
                  onClick={() => {
                    setEntitiesTab(tab.id);
                    setSelectedItem(null);
                  }}
                >
                  <tab.icon size={14} />
                  <span>{tab.label}</span>
                  <TabCountBadge
                    loading={
                      (tab.id === "Classes" && isHierarchyLoading) ||
                      ((tab.id === "ObjectProperties" || tab.id === "DataProperties") &&
                        isPropertiesLoading) ||
                      (tab.id === "Individuals" && isIndividualsLoading) ||
                      (tab.id === "AnnotationProperties" && isAnnotationPropertiesLoading) ||
                      (tab.id === "Datatypes" && isDatatypesLoading)
                    }
                    count={tab.count || 0}
                  />
                </button>
              ))}
            </div>
          </div>
        )}

        {sectionBarMounted && sectionBarLabels.length > 0 && (
          <SectionLoadingBar
            sections={sectionBarLabels}
            open={showSectionLoadingBar && sectionBarSections.length > 0}
          />
        )}

        {/* Mobile: stack hierarchy above details, page scrolls if both need more room than fits.
            Desktop: side-by-side row with a guaranteed min height; page scrolls if the window is too short to fit it. */}
        <main className="flex flex-1 flex-col overflow-y-auto md:flex-row min-h-0">
          {mainTab === "Entities" ? (
            <>
              {/* Hierarchy panel — guaranteed min height on mobile so the tree stays usable
                  even with the search/filter chrome above it; fixed height on desktop. */}
              <div className="w-full md:w-auto flex-shrink-0 flex flex-col min-h-[75dvh] max-h-[80dvh] md:min-h-[600px] md:max-h-none md:h-full overflow-hidden">
                <EntityHierarchy
                  entitiesTab={entitiesTab}
                  filteredData={filteredData}
                  selectedItem={selectedItem}
                  expandedNodes={expandedNodes}
                  searchQuery={searchQuery}
                  onSearchQueryChange={setSearchQuery}
                  searchOptions={searchOptions}
                  onSearchOptionsChange={setSearchOptions}
                  onSelectItem={setSelectedItem}
                  onToggleNode={toggleNode}
                  onAddItem={handleAddItem}
                  onDeleteItem={handleDeleteItem}
                  onMakeSiblingsDisjoint={handleMakeSiblingsDisjoint}
                  onOpenPreferences={() => setEntityPreferencesDialogOpen(true)}
                  onRenameItem={handleRenameItem}
                  onChangeEntityIri={handleChangeEntityIri}
                  onMoveClass={entitiesTab === "Classes" ? handleMoveClass : undefined}
                  onQuickSetParent={(item) => {
                    setQuickEditParentItem(item);
                    if (entitiesTab === "Classes") {
                      setQuickParentDialogOpen(true);
                    } else if (entitiesTab === "ObjectProperties" || entitiesTab === "DataProperties") {
                      setQuickPropertyParentDialogOpen(true);
                    }
                  }}
                  onQuickAddNote={(item) => {
                    setQuickEditNoteItem(item);
                    setQuickNoteDialogOpen(true);
                  }}
                  viewMode={currentHierarchyViewMode}
                  onViewModeChange={setCurrentHierarchyViewMode}
                  displayMode={hierarchyDisplayMode}
                  onDisplayModeChange={setHierarchyDisplayMode}
                  displayAnnotationPropIri={hierarchyAnnotationPropIri}
                  onDisplayAnnotationPropChange={setHierarchyAnnotationPropIri}
                  customTemplate={hierarchyCustomTemplate}
                  onCustomTemplateChange={setHierarchyCustomTemplate}
                  annotationProperties={hierarchyAnnotationProperties}
                  annotationValues={hierarchyAnnotationValues}
                  importsScope={hierarchyImportsScope}
                  onImportsScopeChange={setHierarchyImportsScope}
                  isReasonerRunning={isReasonerRunning}
                  loadingNodes={loadingNodes}
                  isViewOnly={isViewOnlyMember}
                  onViewOnlyAction={handleViewOnlyAction}
                  isLoading={isEntitiesSectionLoading}
                />
              </div>

              {/* Details panel — guaranteed min height on mobile (stacked below hierarchy),
                  scrolls within itself on desktop */}
              <section className="flex-1 min-w-0 min-h-[90dvh] md:min-h-[600px] overflow-hidden p-2 bg-slate-200 flex flex-col">
                <div className="flex-1 min-w-0 min-h-0 overflow-y-auto flex flex-col">
                  <DetailsPanel
                    selectedItem={selectedItem}
                    entitiesTab={entitiesTab}
                    activeTheme={activeTheme}
                    projectId={projectId}
                    viewMode={currentHierarchyViewMode}
                    onUpdate={updateItemInState}
                    onAddAnnotation={handleAddAnnotation}
                    onEditAnnotation={handleEditAnnotation}
                    onDeleteAnnotation={handleDeleteAnnotation}
                    onAddDomainClick={() => handleOpenClassSelector("domain")}
                    onAddRangeClick={() => handleOpenClassSelector("range")}
                    onAddSubPropertyClick={() => handleOpenPropertySelector("subProperty")}
                    onAddInverseClick={() => handleOpenPropertySelector("inverse")}
                    onAddDisjointClick={() => handleOpenPropertySelector("disjoint")}
                    onAddEquivalentClick={() => handleOpenPropertySelector("equivalent")}
                    onAddAnnotationDomainClick={() => handleOpenAnnotationDomainDialog()}
                    onAddAnnotationRangeClick={() => handleOpenAnnotationRangeDialog()}
                    onAddAnnotationSuperpropertyClick={() => handleOpenAnnotationSuperpropertyDialog()}
                    classHierarchy={classHierarchy}
                    objectProperties={objectProperties}
                    dataProperties={dataProperties}
                    expandedNodes={expandedNodes}
                    onToggleNode={toggleNode}
                    onAddClass={(type) => handleAddItem(type)}
                    onAddClassInline={handleAddClassInline}
                    onDeleteClass={() => handleDeleteItem()}
                    onRefreshClasses={refreshClassHierarchy}
                    onAddObjectProperty={handleAddObjectProperty}
                    onAddDataProperty={handleAddDataProperty}
                    metadata={metadata}
                    objectPropertyHierarchy={objectPropertyHierarchy}
                    dataPropertyHierarchy={dataPropertyHierarchy}
                    individuals={individuals}
                    setIndividuals={setIndividuals}
                    markAsUnsaved={markAsUnsaved}
                    isViewOnly={isViewOnlyMember}
                    onViewOnlyAction={handleViewOnlyAction}
                    isReasonerRunning={isReasonerRunning}
                    selectedReasoner={selectedReasoner}
                  />
                </div>
              </section>
            </>
          ) : (
            <section className="flex-1 overflow-y-auto bg-white">{renderMainContent()}</section>
          )}
        </main>
      </div>

      {/* Class Selector Dialog */}
      {/* Class Expression Dialog for Domain/Range */}
      <ClassExpressionDialog
        isOpen={isClassExpressionDialogOpen}
        onClose={() => {
          setIsClassExpressionDialogOpen(false);
          setSelectorTarget(null);
        }}
        onConfirm={handleManchesterConfirm}
        classHierarchy={classHierarchy}
        objectProperties={objectProperties}
        dataProperties={dataProperties}
        objectPropertiesTree={objectPropertyHierarchy}
        dataPropertiesTree={dataPropertyHierarchy}
        title={`Add ${selectorTarget === "domain" ? "Domain" : "Range"} Class Expression`}
        allowedTabs={selectorAllowedTabs}
        initialTab={selectorInitialTab}
        expandedNodes={expandedNodes}
        onToggleNode={toggleNode}
        onAddClass={(type) => handleAddItem(type)}
        onDeleteClass={() => handleDeleteItem()}
        onAddDataProperty={(type) => handleAddItem(type)}
        onDeleteProperty={() => handleDeleteItem()}
        onRefreshClasses={refreshClassHierarchy}
        metadata={metadata}
      />

      {/* View-Only / Draft-Required Prompt */}
      {showProPromptType && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999]" onClick={() => setShowProPromptType(null)}>
          <div
            className="relative bg-white rounded-2xl shadow-2xl w-[420px] max-w-[92vw] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Top accent bar */}
            <div className={`h-1.5 w-full bg-gradient-to-r ${
              showProPromptType === 'draftRequired'
                ? 'from-purple-500 via-violet-500 to-indigo-500'
                : 'from-violet-500 via-purple-500 to-indigo-500'
            }`} />

            {/* Header */}
            <div className="px-6 pt-5 pb-4 flex items-start gap-4">
              <div className={`flex-shrink-0 w-11 h-11 rounded-xl flex items-center justify-center ${
                showProPromptType === 'draftRequired'
                  ? 'bg-purple-50 border border-purple-200'
                  : 'bg-amber-50 border border-amber-200'
              }`}>
                {showProPromptType === 'draftRequired' ? (
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-purple-500">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                ) : (
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-amber-500">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                    <circle cx="12" cy="12" r="3"/>
                    <line x1="2" y1="2" x2="22" y2="22"/>
                  </svg>
                )}
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-[15px] font-semibold text-gray-900 leading-tight">
                  {showProPromptType === 'export' ? 'Pro Feature'
                    : showProPromptType === 'draftRequired' ? 'Draft Mode Required'
                    : 'View-Only Access'}
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">
                  {showProPromptType === 'draftRequired'
                    ? 'This project requires Draft Mode for editing'
                    : showProPromptType === 'viewer'
                    ? 'You are a viewer on this project'
                    : <>Your account is on the <span className="font-medium text-gray-500">Free plan</span></>}
                </p>
              </div>
              <button
                onClick={() => setShowProPromptType(null)}
                className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100 -mt-1 -mr-1"
                aria-label="Close"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>

            {/* Body */}
            <div className="px-6 pb-5">
              <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3.5 mb-4 text-sm text-gray-600 leading-relaxed">
                {showProPromptType === 'draftRequired' ? (
                  <>The project owner has configured this project so members must work in <span className="font-medium text-gray-800">Draft Mode</span>. You can browse and explore the shared ontology now — start your private copy to make edits.</>
                ) : showProPromptType === 'export' ? (
                  <>Ontology export is a <span className="font-medium text-gray-800">premium feature</span>. To unlock this and other advanced tools, upgrade to a <span className="font-medium text-gray-800">Pro plan / Enterprise plan</span>.</>
                ) : showProPromptType === 'viewer' ? (
                  <>You can <span className="font-medium text-gray-800">browse and explore</span> this ontology, but editing is restricted to <span className="font-medium text-gray-800">editors and above</span>.</>
                ) : (
                  <>You can <span className="font-medium text-gray-800">browse and explore</span> this ontology, but editing is restricted to the <span className="font-medium text-gray-800">workspace owner</span> on the Free plan.</>
                )}
              </div>

              <div className="flex items-start gap-2.5 text-sm text-gray-600">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5 text-violet-500">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                </svg>
                {showProPromptType === 'draftRequired' ? (
                  <span>Your edits will be saved to a <span className="font-medium text-gray-800">private copy</span> and won't affect others until you publish.</span>
                ) : showProPromptType === 'viewer' ? (
                  <span>Contact the <span className="font-medium text-gray-800">project owner</span> to request edit permissions.</span>
                ) : (
                  <span>Ask your <span className="font-medium text-gray-800">workspace owner</span> to upgrade to Pro to unlock {showProPromptType === 'export' ? 'exporting' : 'editing for all members'}.</span>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 pb-5 flex justify-end gap-2">
              {showProPromptType === 'draftRequired' && (
                <button
                  onClick={handleSwitchToDraftMode}
                  className="px-5 py-2 text-sm font-medium rounded-lg bg-purple-600 text-white hover:bg-purple-700 transition-colors"
                >
                  Switch to Draft Mode
                </button>
              )}
              <button
                onClick={() => setShowProPromptType(null)}
                className="px-5 py-2 text-sm font-medium rounded-lg bg-gray-900 text-white hover:bg-gray-700 transition-colors"
              >
                {showProPromptType === 'draftRequired' ? 'Stay in View Mode' : 'Got it'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Import Dialog */}
      {showImportDialog && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-[500px]">
            <h3 className="text-lg font-semibold mb-4">Add Ontology Import</h3>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const formData = new FormData(e.currentTarget);
                const importIRI = formData.get("importIRI") as string;

                if (!projectId || !importIRI) return;

                try {
                  await apiClient.post(`/api/ontology/metadata/${projectId}/imports`, { importIri: importIRI, ...draftBodyFields() });

                  // Refresh all metadata related state sequentially
                  const metadataRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}`));
                  const annotationsRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}/annotations`));
                  const importsRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}/imports`));
                  const gciRes = await apiClient.get(withDraftScope(`/api/ontology/metadata/${projectId}/gci`));

                  // Extract data with fallbacks (API returns {success, data: [...]})
                  const annotationsPayload = annotationsRes?.data?.data ?? annotationsRes?.data ?? annotationsRes;
                  const annotationsData = Array.isArray(annotationsPayload) ? annotationsPayload : [];
                  const importsPayload2 = importsRes?.data?.data ?? importsRes?.data ?? importsRes;
                  const importsData = Array.isArray(importsPayload2) ? importsPayload2 : [];
                  const gciPayload = gciRes?.data?.data ?? gciRes?.data ?? gciRes;
                  const gciData = Array.isArray(gciPayload) ? gciPayload : [];

                  const updatedMetadata = {
                    ...(metadataRes.data || metadataRes),
                    annotations: annotationsData,
                  };

                  setMetadata(updatedMetadata);
                  setOntologyImports(importsData);
                  setGeneralClassAxioms(gciData);
                  setShowImportDialog(false);
                } catch (err) {
                  console.error("Failed to add import:", err);
                }
              }}
            >
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium mb-1">Ontology IRI to Import</label>
                  <input
                    type="url"
                    name="importIRI"
                    placeholder="https://example.com/ontology.owl"
                    className="w-full px-3 py-2 border rounded text-sm"
                    required
                  />
                  <div className="text-xs text-gray-500 mt-1">Enter the full IRI of the ontology to import</div>
                </div>
              </div>
              <div className="flex justify-end gap-2 mt-6">
                <button
                  type="button"
                  onClick={() => setShowImportDialog(false)}
                  className="px-4 py-2 border rounded hover:bg-gray-50 text-sm"
                >
                  Cancel
                </button>
                <button type="submit" className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 text-sm">
                  Add Import
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Class Selector Dialog - kept for other uses if needed */}
      <ClassSelectorDialog
        isOpen={isClassSelectorOpen}
        onClose={() => {
          setIsClassSelectorOpen(false);
          setSelectorTarget(null);
        }}
        onSelect={(node) => {
          setIsClassSelectorOpen(false);
          setSelectorTarget(null);
        }}
        classHierarchy={classHierarchy}
        projectId={projectId || undefined}
        onToggleNode={toggleNode}
        externalExpandedNodes={expandedNodes}
        title="Select Class"
        onAddClass={handleAddClassInline}
        onDeleteClass={() => handleDeleteItem()}
        metadata={metadata}
      />
      <ClassSelectorDialog
        isOpen={isQuickParentDialogOpen}
        onClose={() => {
          setQuickParentDialogOpen(false);
          setQuickEditParentItem(null);
        }}
        onSelect={async (node) => {
          if (!projectId || !quickEditParentItem) return;
          try {
            await ontologyMutationService.addSubClassOf(
              projectId,
              quickEditParentItem.id,
              node.id,
              user?.email || "anonymous",
              user?.username || "Anonymous",
            );
            await refreshClassHierarchy();
          } catch (error) {
            console.error("[Dashboard] Failed to set parent class:", error);
            notificationService.error("Parent Failed", "Could not set parent class.");
          } finally {
            setQuickParentDialogOpen(false);
            setQuickEditParentItem(null);
          }
        }}
        classHierarchy={classHierarchy}
        projectId={projectId || undefined}
        onToggleNode={toggleNode}
        externalExpandedNodes={expandedNodes}
        title="Set parent class"
        onAddClass={handleAddClassInline}
        onDeleteClass={() => handleDeleteItem()}
        metadata={metadata}
      />

      {/* Property Expression Dialog */}
      <PropertyExpressionDialog
        isOpen={isPropertyExpressionDialogOpen}
        onClose={() => {
          setIsPropertyExpressionDialogOpen(false);
          setSelectorTarget(null);
        }}
        onConfirm={handlePropertySelected}
        propertyHierarchy={objectPropertyHierarchy}
        propertyType={(selectedItem as any)?.type === "DatatypeProperty" ? "data" : "object"}
        title={`Select ${selectorTarget ? selectorTarget.charAt(0).toUpperCase() + selectorTarget.slice(1) : "Property"}`}
      />
      <PropertyExpressionDialog
        isOpen={isQuickPropertyParentDialogOpen}
        onClose={() => {
          setQuickPropertyParentDialogOpen(false);
          setQuickEditParentItem(null);
        }}
        onConfirm={async (expression) => {
          if (!projectId || !quickEditParentItem) return;
          try {
            await ontologyMutationService.addSubPropertyOf(
              projectId,
              quickEditParentItem.id,
              expression,
              user?.email || "anonymous",
              user?.username || "Anonymous",
            );
            await refreshProperties();
          } catch (error) {
            console.error("[Dashboard] Failed to set parent property:", error);
            notificationService.error("Parent Failed", "Could not set parent property.");
          } finally {
            setQuickPropertyParentDialogOpen(false);
            setQuickEditParentItem(null);
          }
        }}
        propertyHierarchy={
          (quickEditParentItem as any)?.type === "DatatypeProperty" ? dataPropertyHierarchy : objectPropertyHierarchy
        }
        propertyType={(quickEditParentItem as any)?.type === "DatatypeProperty" ? "data" : "object"}
        title="Set parent property"
      />

      {/* Object Property Expression Dialog - Protégé-style with inverse checkbox */}
      <ObjectPropertyExpressionDialog
        isOpen={isObjectPropertyExpressionDialogOpen}
        onClose={() => {
          setIsObjectPropertyExpressionDialogOpen(false);
          setSelectorTarget(null);
        }}
        onConfirm={handleObjectPropertySelected}
        objectPropertyHierarchy={
          (selectedItem as any)?.type === "DatatypeProperty" ? dataPropertyHierarchy : objectPropertyHierarchy
        }
        title={
          selectedItem ? `'${(selectedItem as Property).label || selectedItem.id.split("#").pop()}'` : "Select Property"
        }
        projectId={projectId || undefined}
        onRefresh={refreshProperties}
        showInverseOption={selectorTarget !== "subProperty" && (selectedItem as any)?.type !== "DatatypeProperty"}
        propertyType={(selectedItem as any)?.type === "DatatypeProperty" ? "data" : "object"}
      />

      {/* Annotation Property Domain Dialog (Protégé-style) */}
      <AnnotationPropertyDomainDialog
        isOpen={isAnnotationDomainDialogOpen}
        onClose={() => setIsAnnotationDomainDialogOpen(false)}
        onConfirm={handleAnnotationDomainConfirm}
        classHierarchy={classHierarchy}
        projectId={projectId || undefined}
        onToggleNode={toggleNode}
        externalExpandedNodes={expandedNodes}
        title="Domain (intersection)"
        selectedDomains={(selectedItem as AnnotationProperty & { domains?: string[] })?.domains}
      />

      {/* Annotation Property Range Dialog (Protégé-style) */}
      <AnnotationPropertyRangeDialog
        isOpen={isAnnotationRangeDialogOpen}
        onClose={() => setIsAnnotationRangeDialogOpen(false)}
        onConfirm={handleAnnotationRangeConfirm}
        datatypes={datatypes}
        title="Range (intersection)"
        selectedRanges={(selectedItem as AnnotationProperty & { ranges?: string[] })?.ranges}
      />

      {/* Annotation Property Superproperty Dialog (Protégé-style) */}
      <AnnotationPropertySuperpropertyDialog
        isOpen={isAnnotationSuperpropertyDialogOpen}
        onClose={() => setIsAnnotationSuperpropertyDialogOpen(false)}
        onConfirm={handleAnnotationSuperpropertyConfirm}
        annotationPropertyHierarchy={annotationProperties.map((ap) => ({
          id: ap.id,
          label: ap.label,
          type: "AnnotationProperty" as const,
          children: [],
          hasChildren: false,
        }))}
        currentPropertyId={selectedItem?.id}
        title="Superproperties"
        selectedSuperproperties={(selectedItem as AnnotationProperty & { superProperties?: string[] })?.superProperties}
      />

      {/* Data Property Range Dialog (Protégé-style - shows datatypes) */}
      <DataPropertyRangeDialog
        isOpen={isDataPropertyRangeDialogOpen}
        onClose={() => {
          setIsDataPropertyRangeDialogOpen(false);
          setSelectorTarget(null);
        }}
        onConfirm={handleDataPropertyRangeConfirm}
        datatypes={datatypes}
        title={
          selectedItem ? `'${(selectedItem as Property).label || selectedItem.id.split("#").pop()}'` : "Select Range"
        }
        selectedRanges={(selectedItem as Property)?.ranges}
      />

      {/* Project Selector Modal */}
      {showProjectSelector && (
        <ProjectSelector
          projects={availableProjects}
          onSelectProject={handleProjectSelection}
          onClose={() => setShowProjectSelector(false)}
          importStatus={projectImportStatuses}
        />
      )}

      {/* Collaboration Panel - Toggle visibility manually */}
      {showCollaborationPanel && <CollaborationPanel ref={collaborationPanelRef} projectId={projectId || undefined} />}

      {/* Share Dialog */}
      {shareFileId && (
        <ShareDialog
          isOpen={isShareDialogOpen}
          onClose={() => {
            setIsShareDialogOpen(false);
            setShareFileId(null);
          }}
          projectId={shareFileId}
          userEmail={user?.email || ""}
        />
      )}

      {/* Merge Wizard */}
      <MergeWizard
        isOpen={isMergeWizardOpen}
        onClose={() => setMergeWizardOpen(false)}
        projectId={projectId || ""}
        projectTitle={activeFileName || myFiles.find((f) => f.projectId === projectId)?.filename || "Unknown"}
        initialProjectId={initialProjectId || undefined}
        isViewOnly={isViewOnlyMember}
        onProAction={handleExportProAction}
        availableFiles={
          // Show files from the current project, not all user's projects
          projectFiles.length > 0
            ? projectFiles.map((f: any) => ({
                id: f.id,
                filename: f.filename,
              }))
            : // Fallback: show only the current file if projectFiles not loaded
              [
                {
                  id: projectId || "",
                  filename: activeFileName || "Current File",
                },
              ]
        }
        onMergeComplete={async (targetProjectId: string, isNewFile?: boolean) => {
          try {
            console.log("[Dashboard] 🔄 Merge complete - targetProjectId:", targetProjectId, "isNewFile:", isNewFile);

            if (isNewFile) {
              // "Save as new file" — MergeWizard already uploaded the file to
              // the auth service. Just refresh the project file list so the
              // new file shows up. Current loaded file is NOT affected.
              console.log("[Dashboard] ✅ New file merge — refreshing project file list only");
              if (initialProjectId) {
                await fetchProjectFiles(initialProjectId);
              }
              await fetchProjects();
              notificationService.success("Merge Complete", "Merged ontology saved as a new file in your project!");
              return;
            }

            // "Merge into current/existing file" — poll and refresh as before
            console.log("[Dashboard] Current projectId:", projectId);

            // If merge was to current file, refresh it completely
            if (targetProjectId === projectId) {
              console.log("[Dashboard] ✅ Refreshing current file data after merge");

              // Show loading screen during the wait
              setIsInitialLoading(true);
              notificationService.info("Processing Merge", "Waiting for merged data to finish importing…");

              // Clear ALL current state to ensure fully fresh data
              setClassHierarchy([]);
              setObjectProperties([]);
              setDataProperties([]);
              setObjectPropertyHierarchy([]);
              setDataPropertyHierarchy([]);
              setAnnotationProperties([]);
              setIndividuals([]);
              setDatatypes([]);
              setMetadata(null);
              setSelectedItem(null);
              setClassInstanceCounts({});

              // Wait for the merge's re-import to fully complete — including the
              // async class-hierarchy rebuild, not just the GraphDB bulk load.
              // Reuses the same gate the normal file-open path uses (rather than
              // a bespoke COMPLETED-only poll) so merge can't race ahead of a
              // hierarchy that's still warming: that race is why classes could
              // come back wrong in the live editor while the merged file on
              // disk (read by the unrelated download path) was always correct.
              console.log("[Dashboard] ⏳ Waiting for merge re-import (including hierarchy rebuild) to complete...");
              const mergeWaitResult = await waitForProcessingComplete(targetProjectId);

              if (!mergeWaitResult.ready && mergeWaitResult.status === "ERROR") {
                console.error("[Dashboard] ❌ Import failed during merge re-import");
                notificationService.error("Import Failed", mergeWaitResult.error || "The merged file failed to import.");
                setIsInitialLoading(false);
                return;
              }

              if (!mergeWaitResult.ready) {
                console.warn("[Dashboard] ⚠️ Timed out waiting for import/hierarchy to complete, attempting to fetch anyway");
                notificationService.warning(
                  "Import Taking Long",
                  mergeWaitResult.error || "Import is taking longer than expected. Attempting to load current data…",
                );
              } else {
                console.log("[Dashboard] ✅ GraphDB import and hierarchy rebuild completed!");
              }

              console.log("[Dashboard] 🔄 Starting data fetch with force refresh...");

              // Reload all ontology data:
              // - If hierarchy was ready: forceRefresh busts caches (wait already done).
              // - If still warming after timeout: leave forceRefresh off and set
              //   isExpectingFileReady so fetchData re-enters waitForProcessingComplete /
              //   hierarchy retry instead of painting an empty tree.
              try {
                const hierarchyReady = !!mergeWaitResult.ready;
                if (!hierarchyReady) {
                  setIsExpectingFileReady(true);
                }
                await fetchData(targetProjectId, true, undefined, hierarchyReady);
                console.log("[Dashboard] ✅ Data fetch completed successfully");
              } catch (fetchError) {
                console.error("[Dashboard] ❌ Failed to fetch data after merge:", fetchError);
                notificationService.error("Refresh Failed", "Could not load merged data. Please refresh manually.");
                setIsInitialLoading(false);
                setMergeWizardOpen(false);
                return;
              }

              console.log("[Dashboard] 📊 Data refresh complete");
              notificationService.success("Merge Complete", "Your ontology has been updated with the merged data!");

              // Rebuild the full class hierarchy (re-expand previously expanded
              // nodes).  fetchData only sets a flat 1-level tree under owl:Thing;
              // refreshClassHierarchy reloads children for all expanded nodes so
              // the user sees the complete tree without manually re-expanding.
              try {
                await refreshClassHierarchy();
              } catch (_) {
                // Non-critical — the flat tree from fetchData is still visible
              }
            } else {
              // Merge was to a different existing file
              console.log("[Dashboard] ⚠️ Merge targeted a different existing file:", targetProjectId);

              // Same hierarchy-aware wait as the "merge into current file" branch
              // above — plain COMPLETED status doesn't guarantee the class
              // hierarchy rebuild has finished.
              const mergeWaitResult2 = await waitForProcessingComplete(targetProjectId);
              const importCompleted = mergeWaitResult2.ready;

              // FIX: If merge target is actually the currently opened file (projectId), refresh the data
              // This handles the case where merge into existing file merged into the same file that's open
              if (importCompleted && targetProjectId === projectId) {
                console.log("[Dashboard] ✅ Merge target is current file — refreshing loaded data");
                try {
                  // Clear state and reload all data
                  setIsInitialLoading(true);
                  setClassHierarchy([]);
                  setObjectProperties([]);
                  setDataProperties([]);
                  setObjectPropertyHierarchy([]);
                  setDataPropertyHierarchy([]);
                  setAnnotationProperties([]);
                  setIndividuals([]);
                  setDatatypes([]);
                  setMetadata(null);
                  setSelectedItem(null);
                  setClassInstanceCounts({});

                  await fetchData(targetProjectId, true, undefined, true);
                  console.log("[Dashboard] ✅ Data fetch completed successfully after merge");

                  try {
                    await refreshClassHierarchy();
                  } catch (_) {
                    // Non-critical
                  }

                  notificationService.success("Merge Complete", "Your ontology has been updated with the merged data!");
                } catch (fetchError) {
                  console.error("[Dashboard] ❌ Failed to fetch data after merge:", fetchError);
                  notificationService.error("Refresh Failed", "Could not load merged data. Please refresh manually.");
                  setIsInitialLoading(false);
                }
              } else {
                // Different file — just notify user to open it
                notificationService.success(
                  "Merge Complete",
                  importCompleted
                    ? "Ontology merged into the selected file. Open that file to view the changes."
                    : "Merge completed but import is taking longer than expected. Refresh the file list to see the changes.",
                );
              }
            }

            // Also refresh the projects list
            await fetchProjects();
          } catch (error) {
            console.warn("[Dashboard] Failed to refresh data after merge:", error);
            setIsInitialLoading(false);
            notificationService.error(
              "Refresh Failed",
              "Failed to refresh ontology data after merge. Please reload manually.",
            );
          }
        }}
      />

      {/* Report Issue Modal */}
      {isReportIssueModalOpen && (
        <ReportIssueModal
          projectName={projectId || undefined}
          projectId={projectId || undefined}
          ontologyFilePath={activeFileName || undefined}
          onClose={() => setIsReportIssueModalOpen(false)}
        />
      )}

      <ReleaseNotesModal
        isOpen={isReleaseNotesOpen}
        onClose={() => {
          setIsReleaseNotesOpen(false);
          if (appVersion) {
            localStorage.setItem("ontocode_release_notes_seen", appVersion);
          }
        }}
      />

      {/* User Guide Modal - only in cloud mode */}
      {isCloudDeployment && <UserGuideModal isOpen={isUserGuideOpen} onClose={() => setIsUserGuideOpen(false)} />}

      <DraftCopyModal
        phase={draftCopyPhase}
        tripleCount={draftCopyTripleCount}
        onCancel={() => {
          if (draftCopyPollRef.current) clearInterval(draftCopyPollRef.current);
          setDraftCopyPhase('idle');
          // Revert to public mode — the copy either failed or was blocked, so there is no
          // usable draft graph. Staying in private with no copy would cause 409 on every edit.
          if (draftCopyPhase === 'failed' || draftCopyPhase === 'import-blocked') {
            setSyncMode('public');
            ontologyMutationService.setRealTimeSync(true);
            if (projectId) {
              localStorage.setItem(`ontocode_sync_mode_${projectId}`, 'public');
              userPreferencesService.saveSyncMode(projectId, 'public');
            }
            // Re-arm the draft-required block so the member stays in view-only mode.
            if (requireDraftForMembers && !isProjectOwner) {
              ontologyMutationService.setDraftRequired(true, () => setShowProPromptType('draftRequired'));
            }
          }
        }}
      />

      {/* Draft Pull Requests Panel */}
      {projectId && (
        <DraftPRPanel
          isOpen={showDraftPRPanel}
          onClose={() => setShowDraftPRPanel(false)}
          projectId={projectId}
          userId={resolveMutationActor(user?.userId || user?.email, user?.username).userId}
          username={user?.username || user?.email || ""}
          canReview={canReviewPR}
          canRaisePR={canRaisePR}
          draftCount={draftCount}
          onPRApproved={() => {
            refreshOpenPRCount();
            if (projectId) fetchData(projectId, false);
            notificationService.success("PR Approved", "The draft changes have been merged into the public ontology.");
          }}
        />
      )}

      {/* Pull Preview Dialog */}
      {projectId && (
        <PullPreviewDialog
          isOpen={showPullPreview}
          onClose={() => setShowPullPreview(false)}
          onConfirm={handlePullComplete}
          projectId={projectId}
          userId={resolveMutationActor(user?.userId || user?.email, user?.username).userId}
        />
      )}

      {/* Toast Notifications */}
      <div className="fixed top-4 right-4 z-[9999] space-y-2">
        {collaboration.state.notifications.map((notification) => (
          <ToastNotification
            key={notification.id}
            toasts={[{ id: notification.id, type: notification.type, message: notification.message, username: notification.username, color: notification.userColor }]}
            onDismiss={() => collaboration.removeNotification(notification.id)}
          />
        ))}
      </div>

      {/* Import Progress Toast - Removed per user request */}

      {/* Plugin Marketplace */}
      <PluginMarketplace
        isOpen={showPluginMarketplace}
        onClose={() => setShowPluginMarketplace(false)}
        onInstall={handleInstallPlugin}
        onUninstall={handleUninstallPlugin}
        installedPlugins={installedPlugins}
      />

      {/* Queue Status Indicator */}
      <QueueStatusIndicator
        projectId={projectId || pendingImportProjectId || ""}
        visible={(showQueueStatus || isExpectingFileReady) && !!(projectId || pendingImportProjectId)}
      />

      {/* Global Queue Stats */}
      <GlobalQueueStats visible={true} />

      {/* Theme Settings */}
      <ThemeSettings isOpen={showThemeSettings} onClose={() => setShowThemeSettings(false)} />

      {/* Pull Requests modal */}
      {projectId && (
        <PRsModal
          projectId={projectId}
          currentUserId={user?.userId || user?.email || ""}
          currentUsername={user?.username || ""}
          isOwner={isProjectOwner}
          isOpen={showPRsModal}
          onClose={() => setShowPRsModal(false)}
          onCountChange={setPendingPRCount}
        />
      )}

      {/* History Panel */}
      {projectId && (
        <HistoryPanel projectId={projectId} isOpen={isHistoryPanelOpen} onClose={() => setIsHistoryPanelOpen(false)} />
      )}

      {/* Prefix Dialog */}
      {isPrefixDialogOpen && (
        <div className="fixed inset-0 z-[70] bg-black/50 flex items-center justify-center">
          <div
            className="bg-white rounded-lg shadow-xl max-w-md w-full mx-4"
            style={{ backgroundColor: "var(--surface-1)", border: "1px solid var(--border)" }}
          >
            <div
              className="px-5 py-3 border-b flex items-center justify-between"
              style={{ borderColor: "var(--border)" }}
            >
              <h3 className="text-sm font-semibold" style={{ color: "var(--text-primary)" }}>
                {prefixDialogData.isEdit ? "Edit Prefix" : "Add Prefix"}
              </h3>
              <button
                onClick={() => setIsPrefixDialogOpen(false)}
                className="text-xs"
                style={{ color: "var(--text-secondary)" }}
              >
                <X size={16} />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: "var(--text-primary)" }}>
                  Prefix
                </label>
                <input
                  type="text"
                  value={prefixDialogData.prefix}
                  onChange={(e) => setPrefixDialogData({ ...prefixDialogData, prefix: e.target.value })}
                  placeholder="owl"
                  className="w-full px-3 py-2 text-sm border rounded"
                  style={{ backgroundColor: "var(--bg)", color: "var(--text-primary)", borderColor: "var(--border)" }}
                />
              </div>
              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: "var(--text-primary)" }}>
                  Namespace IRI
                </label>
                <input
                  type="text"
                  value={prefixDialogData.namespace}
                  onChange={(e) => setPrefixDialogData({ ...prefixDialogData, namespace: e.target.value })}
                  placeholder="http://www.w3.org/2002/07/owl#"
                  className="w-full px-3 py-2 text-sm border rounded"
                  style={{ backgroundColor: "var(--bg)", color: "var(--text-primary)", borderColor: "var(--border)" }}
                />
              </div>
              <div className="flex justify-end gap-2 pt-2">
                <button
                  onClick={() => setIsPrefixDialogOpen(false)}
                  className="px-3 py-1.5 text-xs rounded"
                  style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                >
                  Cancel
                </button>
                <button
                  onClick={() => {
                    if (!prefixDialogData.prefix || !prefixDialogData.namespace) {
                      notificationService.error("Validation Error", "Both prefix and namespace are required.");
                      return;
                    }
                    handleSavePrefix(
                      prefixDialogData.prefix,
                      prefixDialogData.namespace,
                      prefixDialogData.isEdit,
                      prefixDialogData.originalPrefix,
                    );
                  }}
                  className="px-3 py-1.5 text-xs rounded"
                  style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                >
                  {prefixDialogData.isEdit ? "Update Prefix" : "Add Prefix"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Import Dialog - Protégé Style */}
      {isImportDialogOpen && (
        <div className="fixed inset-0 z-[70] bg-black/50 flex items-center justify-center">
          <div
            className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4"
            style={{ backgroundColor: "var(--surface-1)", border: "1px solid var(--border)" }}
          >
            <div
              className="px-5 py-3 border-b flex items-center justify-between"
              style={{ borderColor: "var(--border)", backgroundColor: "var(--surface-2)" }}
            >
              <div className="flex items-center gap-2">
                <Download size={16} style={{ color: "var(--accent)" }} />
                <h3 className="text-sm font-semibold" style={{ color: "var(--text-primary)" }}>
                  {importDialogData.isEdit ? "Edit Ontology Import" : "Import Ontology"}
                </h3>
              </div>
              <button
                onClick={() => setIsImportDialogOpen(false)}
                className="text-xs hover:opacity-70"
                style={{ color: "var(--text-secondary)" }}
              >
                <X size={16} />
              </button>
            </div>
            <div className="p-5 space-y-4">
              {/* Import IRI Section */}
              <div>
                <label className="block text-xs font-medium mb-2" style={{ color: "var(--text-primary)" }}>
                  Import IRI or File Path
                </label>
                <div
                  className="text-[11px] mb-3 p-2 rounded flex items-start gap-2"
                  style={{ backgroundColor: "var(--surface-2)", color: "var(--text-secondary)" }}
                >
                  <Info size={12} className="flex-shrink-0 mt-0.5" style={{ color: "var(--accent)" }} />
                  <span>
                    Specify the IRI of the ontology to import. This can be a web URL (http/https) or a local file path.
                  </span>
                </div>

                {/* IRI Input */}
                <div className="space-y-3">
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={importDialogData.iri}
                      onChange={(e) => setImportDialogData({ ...importDialogData, iri: e.target.value })}
                      placeholder="Enter ontology IRI or file path (e.g., C:\\ontologies\\import.owl)"
                      className="flex-1 px-3 py-2.5 text-sm border rounded font-mono"
                      style={{
                        backgroundColor: "var(--bg)",
                        color: "var(--text-primary)",
                        borderColor: "var(--border)",
                      }}
                      autoFocus
                    />
                  </div>

                  {/* File Picker */}
                  <div className="flex items-center gap-2">
                    <div className="flex-1 h-px" style={{ backgroundColor: "var(--border)" }}></div>
                    <span className="text-[10px]" style={{ color: "var(--text-tertiary)" }}>
                      OR
                    </span>
                    <div className="flex-1 h-px" style={{ backgroundColor: "var(--border)" }}></div>
                  </div>

                  <label
                    className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed rounded cursor-pointer transition-all"
                    style={{ borderColor: "var(--border)", color: "var(--text-secondary)" }}
                    onMouseEnter={(e) => (e.currentTarget.style.borderColor = "var(--accent)")}
                    onMouseLeave={(e) => (e.currentTarget.style.borderColor = "var(--border)")}
                  >
                    <FileCode size={18} />
                    <div className="text-xs">
                      <div className="font-medium" style={{ color: "var(--text-primary)" }}>
                        Browse for local ontology file
                      </div>
                      <div className="text-[10px] mt-0.5" style={{ color: "var(--text-tertiary)" }}>
                        Supports .owl, .rdf, .ttl, .n3, .nt files and .zip ontology packages
                      </div>
                    </div>
                    <input
                      type="file"
                      accept=".owl,.rdf,.xml,.ttl,.n3,.nt,.jsonld,.zip"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) {
                          // Use exact file path without file:/// protocol
                          const filePath = (file as any).path || file.name;
                          setImportDialogData({ ...importDialogData, iri: filePath });
                        }
                      }}
                    />
                  </label>
                </div>
              </div>

              {/* Common Ontology IRIs */}
              <div>
                <div className="text-xs font-medium mb-2" style={{ color: "var(--text-primary)" }}>
                  Common Ontology Libraries
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {[
                    { label: "OWL", iri: "http://www.w3.org/2002/07/owl#" },
                    { label: "RDFS", iri: "http://www.w3.org/2000/01/rdf-schema#" },
                    { label: "Dublin Core", iri: "http://purl.org/dc/elements/1.1/" },
                    { label: "FOAF", iri: "http://xmlns.com/foaf/0.1/" },
                    { label: "SKOS", iri: "http://www.w3.org/2004/02/skos/core#" },
                    { label: "Schema.org", iri: "http://schema.org/" },
                  ].map((ontology) => (
                    <button
                      key={ontology.iri}
                      onClick={() => setImportDialogData({ ...importDialogData, iri: ontology.iri })}
                      className="px-3 py-2 text-[11px] rounded text-left border transition-colors"
                      style={{
                        backgroundColor:
                          importDialogData.iri === ontology.iri ? "var(--accent-tint)" : "var(--surface-2)",
                        color: importDialogData.iri === ontology.iri ? "var(--accent)" : "var(--text-primary)",
                        borderColor: importDialogData.iri === ontology.iri ? "var(--accent)" : "var(--border)",
                      }}
                    >
                      <div className="font-medium">{ontology.label}</div>
                      <div className="text-[9px] font-mono mt-0.5 opacity-70 truncate">{ontology.iri}</div>
                    </button>
                  ))}
                </div>
              </div>

              {/* Actions */}
              <div className="flex justify-between items-center pt-3 border-t" style={{ borderColor: "var(--border)" }}>
                <div className="text-[10px]" style={{ color: "var(--text-tertiary)" }}>
                  <Info size={10} className="inline mr-1" />
                  The imported ontology will be added to your imports list
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setIsImportDialogOpen(false)}
                    className="px-4 py-2 text-xs rounded transition-colors"
                    style={{ backgroundColor: "var(--surface-3)", color: "var(--text-primary)" }}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={() => {
                      if (!importDialogData.iri.trim()) {
                        notificationService.error("Validation Error", "Import IRI is required.");
                        return;
                      }
                      handleSaveImport(importDialogData.iri, importDialogData.isEdit, importDialogData.originalIri);
                    }}
                    className="px-4 py-2 text-xs rounded transition-colors flex items-center gap-1.5"
                    style={{ backgroundColor: "var(--accent)", color: "var(--on-accent)" }}
                  >
                    <Download size={12} />
                    {importDialogData.isEdit ? "Update Import" : "Import Ontology"}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Collaborative Cursors - Show cursors of all active users */}
      <CollaborativeCursors cursors={collaboratorCursors} />

      {/* Citation Picker Dialog */}
      <CitationPickerDialog
        isOpen={showCitationPicker}
        onClose={() => setShowCitationPicker(false)}
        onSelectCitation={handleCitationSelection}
        format={codeViewFormat === "turtle" ? "turtle" : codeViewFormat === "jsonld" ? "jsonld" : "rdfxml"}
      />

      {/* Manual Citation Dialog */}
      <ManualCitationDialog
        isOpen={showManualCitationDialog}
        onClose={() => {
          setShowManualCitationDialog(false);
          setPendingCitation(null);
        }}
        onSubmit={handleManualCitationSubmit}
      />

      {/* Confirm Dialog for destructive actions */}
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        title={confirmDialog.title}
        message={confirmDialog.message}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={confirmDialog.onConfirm}
        onClose={() => {
          confirmDialog.onCancel?.();
          setConfirmDialog({ ...confirmDialog, isOpen: false });
        }}
      />
    </>
  );
};

export default Dashboard;
