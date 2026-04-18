/* eslint-disable @typescript-eslint/no-explicit-any */
// src/Dashboard.tsx - Slim version using extracted hooks
import React from "react";
import {
  FileText,
  Eye,
  Share2,
  List,
  Code,
  Loader2,
  DatabaseZap,
  Network,
  Sparkles,
  GitBranch,
  Zap,
  LogOut,
  Palette,
  Users,
  Download,
  X,
  FileCode,
  Info,
} from "lucide-react";
import apiClient, { getBaseUrl } from "../services/apiClient";
import ontologyMutationService from "../services/ontologyMutationService";
import { notificationService } from "../services/notificationService";
import type {
  TreeNode,
  Property,
  SelectableItem,
  AnnotationProperty,
} from "../types";
import EntityHierarchy from "./EntityHierarchy";
import { ProjectSelector } from "./ProjectSelector";
import CollaborationPanel from "./CollaborationPanel";
import HistoryPanel from "./HistoryPanel";
import ToastNotification from "./ToastNotification";
import { CollaborativeCursors } from "./CollaborativeCursor";
import ShareDialog from "./ShareDialog";
import MergeWizard from "./MergeWizard";
import { ReportIssueModal } from "./ReportIssueModal";
import { UserGuideModal } from "./UserGuideModal";
import ThemeSettings from "./ThemeSettings";
import { QueueStatusIndicator, GlobalQueueStats } from "./QueueStatusIndicator";
import {
  ClassSelectorDialog,
  CreateIndividualModal,
  AddAnnotationDialog,
  AddClassDialog,
  AddObjectPropertyDialog,
  ClassExpressionDialog,
  PropertyExpressionDialog,
  ObjectPropertyExpressionDialog,
  AddDatatypeDialog,
  PropertyAssertionDialog,
  KeyboardShortcutsDialog,
  EntityPreferencesDialog,
  AnnotationPropertyDomainDialog,
  AnnotationPropertyRangeDialog,
  AnnotationPropertySuperpropertyDialog,
  DataPropertyRangeDialog,
  GCIEditorDialog,
  EditOntologyIRIDialog,
} from "./dialogs";
import { PluginMarketplace } from "./PluginMarketplace";
import {
  showNotification,
  LoadingDialog,
  LoadingChoiceDialog,
  ReasonerExplanationModal,
  ReasonerSettingsDialog,
  TopMenuBar,
  OpenFileDialog,
  ConfirmDialog,
  DuplicateFileDialog,
  DetailsPanel,
  MainContentRouter,
} from "./dashboard-parts";
import {
  useDashboardState,
  useDashboardInit,
  useDashboardHandlers,
  useDashboardSelectors,
} from "./dashboard-parts/hooks";

interface DashboardProps {
  onBackToProjects?: () => void;
  onFileSelected?: (fileId: string, fileName: string) => void;
  selectedFileId?: string;
  selectedFileName?: string;
  projectId?: string;
}

const Dashboard: React.FC<DashboardProps> = (props) => {
  const { onBackToProjects, projectId: initialProjectId } = props;

  // Initialize hooks
  const state = useDashboardState(props);
  const init = useDashboardInit(state);
  const handlers = useDashboardHandlers(state, init);
  const selectors = useDashboardSelectors(state, init);

  // Destructure state
  const {
    user, logout, collaboration, subscription, deploymentType, isCloudDeployment,
    showThemeSettings, setShowThemeSettings, showToast, fetchProjectFiles, shortenDatatype,
    updatePreferences, preferences,
    projectId, metadata, setMetadata, ontologyImports, setOntologyImports,
    generalClassAxioms, setGeneralClassAxioms,
    axiomDialogOpen, setAxiomDialogOpen, editingAxiomIndex, setEditingAxiomIndex,
    axiomDraft, setAxiomDraft,
    collaboratorCursors, collaborationPanelRef,
    isPrefixDialogOpen, setIsPrefixDialogOpen, prefixDialogData, setPrefixDialogData,
    isImportDialogOpen, setIsImportDialogOpen, importDialogData, setImportDialogData,
    isOntologyAnnotationDialogOpen, setIsOntologyAnnotationDialogOpen,
    ontologyAnnotationEditTarget, setOntologyAnnotationEditTarget,
    quickEditParentItem, setQuickEditParentItem, quickEditNoteItem, setQuickEditNoteItem,
    isQuickParentDialogOpen, setQuickParentDialogOpen,
    isQuickPropertyParentDialogOpen, setQuickPropertyParentDialogOpen,
    isQuickNoteDialogOpen, setQuickNoteDialogOpen,
    mainTab, setMainTab, entitiesTab, setEntitiesTab,
    selectedItem, setSelectedItem, expandedNodes,
    searchQuery, setSearchQuery, searchOptions, setSearchOptions,
    showImportDialog, setShowImportDialog, isInitialLoading, setIsInitialLoading,
    showLoadingChoice, loadingProjectName, loadingStatusMessage,
    hasUnsavedChanges, draftCount, isSaving, syncMode, setSyncMode,
    projectImportStatuses, showQueueStatus,
    showOpenDialog, setShowOpenDialog, importMode, setImportMode,
    partitionStrategy, setPartitionStrategy,
    isCreateIndividualModalOpen, setCreateIndividualModalOpen,
    isAddAnnotationDialogOpen, setAddAnnotationDialogOpen,
    isEditAnnotationDialogOpen, setEditAnnotationDialogOpen,
    isEditOntologyIRIDialogOpen, setEditOntologyIRIDialogOpen,
    isGCIEditorDialogOpen, setGCIEditorDialogOpen, editGCIData, setEditGCIData,
    editAnnotationData, setEditAnnotationData,
    isAddClassDialogOpen, setAddClassDialogOpen, addClassType, classParentLabel,
    isAddPropertyDialogOpen, setAddPropertyDialogOpen, addPropertyType, propertyParentLabel,
    isAddDatatypeDialogOpen, setAddDatatypeDialogOpen,
    isKeyboardShortcutsDialogOpen, setKeyboardShortcutsDialogOpen,
    isEntityPreferencesDialogOpen, setEntityPreferencesDialogOpen,
    isClassSelectorOpen, setIsClassSelectorOpen,
    isPropertyExpressionDialogOpen, setIsPropertyExpressionDialogOpen,
    isObjectPropertyExpressionDialogOpen, setIsObjectPropertyExpressionDialogOpen,
    isClassExpressionDialogOpen, setIsClassExpressionDialogOpen,
    selectorTarget, setSelectorTarget,
    isAnnotationDomainDialogOpen, setIsAnnotationDomainDialogOpen,
    isAnnotationRangeDialogOpen, setIsAnnotationRangeDialogOpen,
    isAnnotationSuperpropertyDialogOpen, setIsAnnotationSuperpropertyDialogOpen,
    isDataPropertyRangeDialogOpen, setIsDataPropertyRangeDialogOpen,
    confirmDialog, setConfirmDialog,
    unsavedChangesDialog, setUnsavedChangesDialog,
    duplicatePrompt, duplicateCopyName, setDuplicateCopyName,
    duplicateCopyError, duplicateCopySubmitting,
    selectedClassForIndividuals, selectedClassIndividualDetails,
    classIndividualPropertyIsObject,
    isClassIndividualAnnotationDialogOpen, setClassIndividualAnnotationDialogOpen,
    isClassIndividualTypeDialogOpen, setClassIndividualTypeDialogOpen,
    isClassIndividualPropertyDialogOpen, setClassIndividualPropertyDialogOpen,
    classHierarchy, setClassHierarchy, objectProperties, setObjectProperties,
    dataProperties, setDataProperties,
    objectPropertyHierarchy, setObjectPropertyHierarchy,
    dataPropertyHierarchy, setDataPropertyHierarchy,
    annotationProperties, setAnnotationProperties,
    individuals, setIndividuals, datatypes, setDatatypes,
    listOfFiles, projectFiles, myFiles, sharedFiles,
    activeFileId, activeFileName,
    deleteFileDialog, setDeleteFileDialog,
    isShareDialogOpen, setIsShareDialogOpen, shareFileId, setShareFileId,
    isCurrentFileShared, isMergeWizardOpen, setMergeWizardOpen,
    isHistoryPanelOpen, setIsHistoryPanelOpen,
    isReportIssueModalOpen, setIsReportIssueModalOpen,
    isUserGuideOpen, setIsUserGuideOpen,
    showCollaborationPanel, setShowCollaborationPanel,
    visibleMainTabs, showPluginMarketplace, setShowPluginMarketplace,
    installedPlugins,
    selectedReasoner, isReasonerRunning, isReasonerLoading,
    isReasonerSynced, isConsistencyLoading,
    explanationState, setExplanationState,
    isReasonerSettingsOpen, setIsReasonerSettingsOpen,
    currentHierarchyViewMode, setCurrentHierarchyViewMode,
    entitiesTabs, activeTheme, filteredData,
    activeUsersInProject, hasMultipleActiveUsers,
    showProjectSelector, setShowProjectSelector,
    startReasoner, stopReasoner, toggleReasonerSync,
    handleSelectReasoner, checkConsistency, explainInconsistency,
    classInstanceCounts, setClassInstanceCounts,
  } = state;

  // Destructure init
  const {
    handleInstallPlugin, handleUninstallPlugin,
    fetchData, fetchProjects,
    handleAddOntologyAnnotation, handleUpdateOntologyAnnotation,
    handleSaveImport, handleSavePrefix, handleDeletePrefix,
    handleAddAxiom, handleUpdateAxiom,
    loadClassInstances, refreshSelectedClassIndividualDetails,
    handleProjectSelection, handleDeleteFile, confirmDeleteFile,
    handleDuplicatePromptCancel, handleDuplicateCreateCopy,
    sendDuplicatePromptResponse,
    handleWaitForLoading, handleContinueWorking,
    updateItemInState, refreshClassHierarchy,
    resolvePropertyIriByLabel, resolveIndividualIriByLabel,
  } = init;

  // Destructure handlers
  const {
    toggleNode, markAsUnsaved, handleSave, handleSwitchFile,
    handleBackToProjects, handleLoadProjectFile,
    handleAnnotationDialogAdd, handleAnnotationDialogEdit,
    handleEditAnnotation, handleAddAnnotation, handleDeleteAnnotation,
    handleSaveOntologyIRIs, handleSaveGCI,
    refreshProperties,
    handleAddObjectProperty, handleAddDataProperty, handleAddClassInline,
    handleAddItem, handleCreateClass,
    handleCreateObjectProperty, handleCreateDataProperty, handleCreateDatatype,
    handleCreateAnnotationProperty, handleAddIndividual,
    handleMakeSiblingsDisjoint, handleDeleteItem, handleRenameItem,
  } = handlers;

  // Destructure selectors
  const {
    handleOpenClassSelector, handleDataPropertyRangeConfirm,
    handleOpenPropertySelector, handleManchesterConfirm,
    handlePropertySelected, handleObjectPropertySelected,
    handleOpenAnnotationDomainDialog, handleOpenAnnotationRangeDialog,
    handleOpenAnnotationSuperpropertyDialog,
    handleAnnotationDomainConfirm, handleAnnotationRangeConfirm,
    handleAnnotationSuperpropertyConfirm,
  } = selectors;

