// src/Dashboard.tsx
import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight, ChevronDown, Settings, Search, FileText, Eye, Database, Tag, Share2, List, Code, Loader2, Package, Check, Trash2, PlusCircle, User, Type, GitBranch, Binary, LogOut, Play, DatabaseZap, Upload, FolderOpen, Sparkles, Clock, Users
} from "lucide-react";
import apiClient from "../services/apiClient";
import ontologyMutationService from "../services/ontologyMutationService";
import { draftTrackingService } from "../services/draftTrackingService";
import { notificationService } from "../services/notificationService";
import { syncService } from "../services/syncService";
import type { TreeNode, Property, Individual, OntologyMetadata, SelectableItem, AnnotationProperty, Datatype } from '../types';
import { useAuth } from '../custom-hook/useAuth';
import { useCollaboration } from '../contexts/CollaborationContext';
import EntityHierarchy from './EntityHierarchy';
import ClassEditor from './details/ClassEditor';
import PropertyEditor from './details/PropertyEditor';
import IndividualEditor from './details/IndividualEditor';
import DatatypeEditor from './details/DatatypeEditor';
import { Panel, AnnotationsDisplay } from './details/common';
import SparqlQueryEditor from './SparqlQueryEditor';
import { ProjectSelector } from './ProjectSelector';
import CollaborationPanel, { CollaborationPanelRef } from './CollaborationPanel';
import HistoryPanel from './HistoryPanel';
import ToastNotification from './ToastNotification';
import ShareDialog from './ShareDialog';
// ImportProgressToast removed per user request
import { QueueStatusIndicator, GlobalQueueStats } from './QueueStatusIndicator';
import {
  ClassSelectorDialog,
  CreateIndividualModal,
  AddAnnotationDialog,
  AddClassDialog,
  AddObjectPropertyDialog,
  ClassExpressionDialog,
  PropertyExpressionDialog,
  AddDatatypeDialog,
  KeyboardShortcutsDialog,
  EntityPreferencesDialog
} from './dialogs';
import { useKeyboardShortcuts, DEFAULT_SHORTCUTS, KeyboardShortcut } from '../hooks/useKeyboardShortcuts';
import { useEntityPreferences } from '../contexts/EntityPreferencesContext';
import { CodeHighlighter } from './CodeHighlighter';
import { PluginMarketplace } from './PluginMarketplace';
import { pluginLoader } from '../services/pluginLoader';

type TopLevelClass = TreeNode & { hasChildren: boolean };

type FileInfo = {
  id: string;
  filename: string;
  contentType?: string | null;
  length: number;
  uploadDate: string; // ISO
  projectId?: string | null;
  size?: number;
  permission?: 'view' | 'edit';
  sharedBy?: string;
  ownerEmail?: string;
};

const findParentNode = (nodes: any[], targetId: string, parent: any | null = null): any | null => {
  for (const node of nodes) {
    if (node.id === targetId) return parent;
    if (node.children && node.children.length) {
      const found = findParentNode(node.children, targetId, node);
      if (found) return found;
    }
  }
  return null;
};

// #region Helper Components

const LoadingDialog = ({ isOpen, message }: { isOpen: boolean; message?: string }) => {
  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl p-8 max-w-sm w-full mx-4">
        <div className="flex flex-col items-center">
          <Loader2 size={48} className="text-purple-600 animate-spin mb-4" />
          <h3 className="text-lg font-semibold text-gray-900 mb-2">{message || "Loading Ontology"}</h3>
          <p className="text-sm text-gray-500 text-center">Please wait while we process your ontology data...</p>
        </div>
      </div>
    </div>
  );
};

const LoadingChoiceDialog = ({ 
  isOpen, 
  projectName, 
  loadingStatusMessage,
  onWait, 
  onContinue 
}: { 
  isOpen: boolean; 
  projectName: string;
  loadingStatusMessage?: string;
  onWait: () => void; 
  onContinue: () => void;
}) => {
  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60]">
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4">
        <div className="flex items-start mb-4">
          <div className="flex-shrink-0 w-10 h-10 rounded-full bg-purple-100 flex items-center justify-center mr-3">
            <Loader2 size={20} className="text-purple-600 animate-spin" />
          </div>
          <div className="flex-1">
            <h3 className="text-lg font-semibold text-gray-900 mb-1">Loading Ontology</h3>
            <p className="text-sm text-gray-600 mb-1">
              "{projectName}" is loading in the background...
            </p>
            {loadingStatusMessage && (
              <p className="text-xs text-purple-600 font-medium mt-2">
                {loadingStatusMessage}
              </p>
            )}
          </div>
        </div>
        
        <div className="bg-gray-50 rounded-lg p-4 mb-4">
          <p className="text-sm text-gray-700 mb-3">
            <strong>What would you like to do?</strong>
          </p>
          <ul className="text-sm text-gray-600 space-y-2">
            <li className="flex items-start">
              <span className="text-purple-600 mr-2">•</span>
              <span><strong>Wait:</strong> Stay on this screen until loading completes</span>
            </li>
            <li className="flex items-start">
              <span className="text-purple-600 mr-2">•</span>
              <span><strong>Continue:</strong> Work on other files, you'll get a notification when ready</span>
            </li>
          </ul>
          {loadingStatusMessage.includes('several minutes') && (
            <div className="mt-3 p-2 bg-yellow-50 border border-yellow-200 rounded text-xs text-yellow-800">
              <strong>Large file detected:</strong> Processing may take 2-5 minutes. We recommend clicking "Continue Working" to avoid waiting.
            </div>
          )}
        </div>
        
        <div className="flex gap-3">
          <button
            onClick={onWait}
            className="flex-1 px-4 py-2.5 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors font-medium flex items-center justify-center gap-2"
          >
            <Loader2 size={16} className="animate-spin" />
            Wait for Loading
          </button>
          <button
            onClick={onContinue}
            className="flex-1 px-4 py-2.5 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors font-medium"
          >
            Continue Working
          </button>
        </div>
      </div>
    </div>
  );
};

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
  onOpenDialog,
  onOpenPluginMarketplace,
  onOpenHistory,
  syncMode,
  onToggleSyncMode,
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
  onOpenDialog: () => void;
  onOpenPluginMarketplace: () => void;
  onOpenHistory: () => void;
  syncMode: 'private' | 'public';
  onToggleSyncMode: () => void;
}) => {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [searchFile, setSearchFile] = useState("");
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const onSearchFileChange = (value: string) => {
    setSearchFile(value);

    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    timeoutRef.current = setTimeout(async () => {
      setIsLoading(true);
      try {
        // Build URL with proper query parameters
        const url = `/api/ontology/files?search=${encodeURIComponent(value)}&caseSensitive=true`;
        const response = await apiClient.get<{
          data: any; files: FileInfo[] 
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
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [fileList]);

  const displayedFiles = searchFile ? files : fileList;
  const menuItems = ['File', 'Edit', 'View', 'Reasoner', 'Tools', 'Window', 'Help'];

  return (
    <header ref={menuRef} className="bg-gray-200 text-gray-800 text-xs flex items-center px-2 relative border-b border-gray-300 h-8 flex-shrink-0">
      <div className="flex items-center gap-1 p-2 mr-2">
        <Package size={16} className="text-purple-600" />
      </div>
      <div className="flex items-center">
        {menuItems.map((item) => (
          <div key={item} className="relative">
            <button
              onClick={() => {
                setOpenMenu(openMenu === item ? null : item);
              }}
              className="px-3 py-1 hover:bg-gray-300 rounded-sm"
            >
              {item}
            </button>
            {openMenu === item && (
              <div className={`absolute left-0 mt-1 ${item === 'File' ? 'w-[360px]' : 'w-48'} bg-white border border-gray-300 rounded-lg shadow-xl z-20 overflow-hidden`}>
                {item === "View" ? (
                  <div className="py-1">
                    <button
                      onClick={() => {
                        onOpenPluginMarketplace();
                        setOpenMenu(null);
                      }}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 flex items-center gap-2"
                    >
                      <Package size={14} />
                      Plugin Marketplace
                    </button>
                  </div>
                ) : item === "Window" ? (
                  <div className="py-1">
                    <div className="px-3 py-1 text-gray-400 text-xs">Appearance</div>
                  </div>
                ) : item === "Reasoner" ? (
                  <div className="py-1">
                    <div className="px-3 py-1 text-gray-400 text-xs">No options</div>
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
                      Save {draftCount && draftCount > 0 ? `(${draftCount})` : ''}
                      {hasUnsavedChanges && <span className="text-orange-600 text-lg leading-none">•</span>}
                    </button>
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (window.vscode && currentProjectId) {
                          window.vscode.postMessage({
                            type: "downloadOntology",
                            url: `/api/ontology/export/${currentProjectId}`,
                            filename: `${currentProjectId}.owl`
                          });
                        } else if (window.vscode) {
                          window.vscode.postMessage({
                            type: 'error',
                            value: 'No ontology loaded. Please open a file first.'
                          });
                        }
                        setOpenMenu(null);
                      }}
                      disabled={!currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      Download
                    </button>
                    <div className="border-t border-gray-100 my-1" />
                    <button
                      onClick={(e) => {
                        e.preventDefault();
                        if (currentProjectId) {
                          onShareFile(currentProjectId);
                        } else if (window.vscode) {
                          window.vscode.postMessage({
                            type: 'error',
                            value: 'No ontology loaded. Please open a file first.'
                          });
                        }
                        setOpenMenu(null);
                      }}
                      disabled={!currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      Share
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

      <div className="flex items-center ml-auto mr-4 gap-2">
        <span className={`text-xs font-medium ${syncMode === 'public' ? 'text-green-600' : 'text-gray-500'}`}>
          {syncMode === 'public' ? 'Public (Live)' : 'Private (Draft)'}
        </span>
        <button
          onClick={onToggleSyncMode}
          className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 ${
            syncMode === 'public' ? 'bg-green-500' : 'bg-gray-300'
          }`}
          title={syncMode === 'public' ? "Switch to Private Draft Mode" : "Switch to Public Live Mode"}
        >
          <span
            className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
              syncMode === 'public' ? 'translate-x-5' : 'translate-x-1'
            }`}
          />
        </button>
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
  onSwitchFile
}: {
  isOpen: boolean;
  onClose: () => void;
  myFiles: FileInfo[];
  sharedFiles: FileInfo[];
  currentProjectId: string | null;
  onSwitchFile: (projectId: string) => void;
}) => {
  const [searchQuery, setSearchQuery] = useState("");

  if (!isOpen) return null;

  const allFiles = [...myFiles, ...sharedFiles];
  const filteredFiles = searchQuery
    ? allFiles.filter(f => f.filename.toLowerCase().includes(searchQuery.toLowerCase()))
    : allFiles;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-md mx-4 max-h-[70vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="p-4 border-b border-gray-200">
          <div className="relative">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Search files..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              autoFocus
              className="w-full pl-10 pr-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
            />
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          {myFiles.length > 0 && (
            <div className="p-3">
              <div className="flex items-center gap-2 px-2 py-1.5 mb-2">
                <User size={14} className="text-purple-600" />
                <span className="text-xs font-semibold text-purple-800">My Files ({myFiles.filter(f => !searchQuery || f.filename.toLowerCase().includes(searchQuery.toLowerCase())).length})</span>
              </div>
              <div className="space-y-0.5">
                {myFiles.filter(f => !searchQuery || f.filename.toLowerCase().includes(searchQuery.toLowerCase())).map((file) => {
                  const fileProjectId = file.filename.slice(0, -4);
                  const isActive = fileProjectId === currentProjectId;
                  return (
                    <div
                      key={file.id}
                      onClick={() => {
                        if (!isActive) {
                          onSwitchFile(fileProjectId);
                        }
                        onClose();
                      }}
                      className={`flex items-center gap-3 p-2 px-3 rounded-md cursor-pointer transition-all ${
                        isActive
                          ? 'bg-purple-50 border border-purple-300'
                          : 'hover:bg-gray-50 border border-transparent'
                      }`}
                    >
                      <FileText size={18} className="text-purple-500" />
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
                    </div>
                  );
                })}
              </div>
            </div>
          )}
          <div className="p-3 border-t border-gray-100">
            <div className="flex items-center gap-2 px-2 py-1.5 mb-2">
              <Share2 size={14} className="text-blue-600" />
              <span className="text-xs font-semibold text-blue-800">Shared With Me ({sharedFiles.filter(f => !searchQuery || f.filename.toLowerCase().includes(searchQuery.toLowerCase())).length})</span>
            </div>
            {sharedFiles.length > 0 ? (
              <div className="space-y-0.5">
                {sharedFiles.filter(f => !searchQuery || f.filename.toLowerCase().includes(searchQuery.toLowerCase())).map((file) => {
                  const fileProjectId = file.filename.slice(0, -4);
                  const isActive = fileProjectId === currentProjectId;
                  return (
                    <div
                      key={file.id}
                      onClick={() => {
                        if (!isActive) {
                          onSwitchFile(fileProjectId);
                        }
                        onClose();
                      }}
                      className={`flex items-center gap-3 p-2 px-3 rounded-md cursor-pointer transition-all ${
                        isActive
                          ? 'bg-blue-50 border border-blue-300'
                          : 'hover:bg-gray-50 border border-transparent'
                      }`}
                    >
                      <FileText size={18} className="text-blue-500" />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium text-gray-900 truncate">{file.filename}</span>
                          {isActive && (
                            <span className="px-1.5 py-0.5 bg-green-100 text-green-700 text-[10px] font-semibold rounded">
                              ACTIVE
                            </span>
                          )}
                        </div>
                        {file.sharedBy && (
                          <div className="text-[10px] text-gray-500 mt-0.5">
                            Shared by {file.sharedBy}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-4 text-gray-400">
                <Share2 size={20} className="mb-1.5 opacity-50" />
                <p className="text-[10px]">No shared files</p>
              </div>
            )}
          </div>
          {filteredFiles.length === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <Search size={40} className="mb-3 opacity-30" />
              <p className="text-base font-medium text-gray-600 mb-1">No ontology files found</p>
              <p className="text-xs text-gray-500 max-w-xs text-center">
                {searchQuery 
                  ? `No files match "${searchQuery}". Try a different search.`
                  : 'Upload an .owl or .rdf file to get started.'}
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

const ConfirmDialog = ({
  isOpen,
  onClose,
  onConfirm,
  onCancel,
  title,
  message
}: {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  onCancel?: () => void;
  title: string;
  message: string;
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">{title}</h3>
        <p className="text-sm text-gray-700 mb-6">{message}</p>
        <div className="flex justify-end gap-3">
          <button 
            onClick={() => {
              if (onCancel) {
                onCancel();
              }
              onClose();
            }} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            {onCancel ? 'Discard' : 'Cancel'}
          </button>
          <button 
            onClick={() => {
              onConfirm();
              onClose();
            }} 
            className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
          >
            {onCancel ? 'Save' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
};

// Dialog components moved to separate files

// #endregion

// #region Details Panel
const DetailsPanel = ({ 
  selectedItem, 
  entitiesTab, 
  activeTheme, 
  projectId, 
  onUpdate, 
  onAddAnnotation,
  onEditAnnotation, 
  onDeleteAnnotation,
  onAddDomainClick,
  onAddRangeClick,
  onAddSubPropertyClick,
  onAddInverseClick,
  onAddDisjointClick,
  onAddEquivalentClick,
  classHierarchy,
  objectProperties,
  expandedNodes,
  onToggleNode,
  onAddClass,
  onAddClassInline,
  onDeleteClass,
  onRefreshClasses,
  onAddObjectProperty,
  onAddDataProperty,
  dataPropertyHierarchy,
  objectPropertyHierarchy,
  dataProperties,
  metadata,
  individuals,
  setIndividuals,
  markAsUnsaved
}: {
  selectedItem: SelectableItem | null;
  entitiesTab: string;
  activeTheme?: string;
  projectId: string | null;
  onUpdate: (item: SelectableItem) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
  onAddSubPropertyClick?: () => void;
  onAddInverseClick?: () => void;
  onAddDisjointClick?: () => void;
  onAddEquivalentClick?: () => void;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  onAddClass?: (type: 'subclass' | 'sibling') => void;
  onAddClassInline?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteClass?: () => void;
  onRefreshClasses?: () => Promise<void>;
  onAddObjectProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onAddDataProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  dataPropertyHierarchy: TreeNode[];
  objectPropertyHierarchy: TreeNode[];
  dataProperties: Property[];
  metadata?: { ontologyIRI?: string } | null;
  individuals: Individual[];
  setIndividuals: React.Dispatch<React.SetStateAction<Individual[]>>;
  markAsUnsaved: () => void;
}) => {
  if (!selectedItem) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-center text-gray-400 p-4">
        <Package size={48} className="mb-4 text-gray-300" />
        <h3 className="text-lg font-semibold text-gray-600">Ontology Editor</h3>
        <p className="text-sm">Select an entity from the hierarchy panel on the left to view its details and make edits.</p>
      </div>
    );
  }

  const sharedProps = {
    onAddAnnotation,
    onEditAnnotation,
    onDeleteAnnotation,
    activeTheme,
    projectId: projectId || '',
    // userId: user?.email || 'anonymous',
    // username: user?.username || 'Anonymous'
  };

  switch (entitiesTab) {
    case 'Classes':
      return <ClassEditor
        item={selectedItem as TreeNode}
        onUpdate={onUpdate}
        classHierarchy={classHierarchy}
        expandedNodes={expandedNodes}
        onToggleNode={onToggleNode}
        onAddClass={onAddClass}
        onAddClassInline={onAddClassInline}
        onDeleteClass={onDeleteClass}
        onRefreshClasses={onRefreshClasses}
        onAddObjectProperty={onAddObjectProperty}
        onAddDataProperty={onAddDataProperty}
        onDeleteProperty={() => {}}
        metadata={metadata ?? undefined}
        objectPropertyHierarchy={objectPropertyHierarchy}
        dataPropertyHierarchy={dataPropertyHierarchy}
        objectProperties={objectProperties}
        dataProperties={dataProperties}
        individuals={individuals}
        onAddIndividual={async (name: string, classIri: string) => {
          const id = `${metadata?.ontologyIRI || 'http://example.org/ontology'}#${name.replace(/\s+/g, '_')}`;
          await ontologyMutationService.createIndividual(projectId || '', id, name, classIri);
          const newIndividual: Individual = {
            id,
            iri: id,
            label: name,
            annotations: { 'rdfs:label': name },
            types: [classIri]
          };
          setIndividuals(prev => [...prev, newIndividual]);
          markAsUnsaved();
        }}
        onDeleteIndividual={async (id: string) => {
          await ontologyMutationService.deleteIndividual(projectId || '', id);
          setIndividuals(prev => prev.filter(ind => ind.id !== id));
          markAsUnsaved();
        }}
        onRefreshIndividuals={() => {
          // Reload individuals from backend
          if (projectId) {
            apiClient.get<any>(`/api/ontology/individuals/${projectId}`)
              .then(res => {
                setIndividuals(Array.isArray(res?.data) ? res.data : 
                              Array.isArray(res?.individuals) ? res.individuals : []);
              })
              .catch(err => console.error('Failed to refresh individuals:', err));
          }
        }}
        {...sharedProps}
      />;
    case 'ObjectProperties':
    case 'DataProperties':
      return <PropertyEditor 
        item={selectedItem as Property} 
        onUpdate={onUpdate} 
        {...sharedProps} 
        onAddDomainClick={onAddDomainClick}
        onAddRangeClick={onAddRangeClick}
        onAddSubPropertyClick={onAddSubPropertyClick}
        onAddInverseClick={onAddInverseClick}
        onAddDisjointClick={onAddDisjointClick}
        onAddEquivalentClick={onAddEquivalentClick}
        objectProperties={objectProperties}
      />;
    case 'Individuals':
      return <IndividualEditor item={selectedItem as Individual} onUpdate={onUpdate} {...sharedProps} />;
    case 'AnnotationProperties': {
      const item = selectedItem as AnnotationProperty;
      return (
        <div className="flex-1 flex flex-col gap-2">
          <Panel title={`Annotations: ${item.label}`} {...sharedProps}><AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} /></Panel>
        </div>
      );
    }
    case 'Datatypes':
      return <DatatypeEditor item={selectedItem as Datatype} onUpdate={onUpdate} {...sharedProps} />;
    default:
      return <div className="bg-white rounded-lg border p-4"><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation} /></div>;
  }
};
// #endregion

// Helper function to show notifications
const showNotification = (message: string, type: 'info' | 'error' | 'warning' = 'info') => {
  console.log(`[${type.toUpperCase()}]`, message);
  if (window.vscode) {
    window.vscode.postMessage({
      type: 'notification',
      level: type,
      message: message
    });
  }
};

const Dashboard = () => {
  // #region State
  const { user, logout } = useAuth();
  const collaboration = useCollaboration();
  const [projectId, setProjectId] = useState<string | null>(null);
  const [availableProjects, setAvailableProjects] = useState<any[]>([]);
  const [showProjectSelector, setShowProjectSelector] = useState(false);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [mainTab, setMainTab] = useState("Entities");
  const [entitiesTab, setEntitiesTab] = useState("Classes");
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [isInitialLoading, setIsInitialLoading] = useState(false);
  const [hasFetchedProjects, setHasFetchedProjects] = useState(false);
  const [hasUserSelectedFile, setHasUserSelectedFile] = useState(false);
  const hasUserSelectedFileRef = useRef(false);
  const [isExpectingFileReady, setIsExpectingFileReady] = useState(false); // Don't auto-load if expecting upload
  const pendingImportProjectIdRef = useRef<string | null>(null); // Track which project is being imported (using ref for persistence)
  const [showLoadingChoice, setShowLoadingChoice] = useState(false);
  const [loadingProjectName, setLoadingProjectName] = useState("");
  const [loadingStatusMessage, setLoadingStatusMessage] = useState<string>(""); // Track import progress message
  const loadingPromiseRef = useRef<Promise<void> | null>(null);
  const userLoadingChoice = useRef<'wait' | 'continue' | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [draftCount, setDraftCount] = useState(0);
  const [isSaving, setIsSaving] = useState(false);
  const [syncMode, setSyncMode] = useState<'private' | 'public'>('private');
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Import status state - Removed (ImportProgressToast removed per user request)
  // Track import status for all projects (for ProjectSelector)
  const [projectImportStatuses, setProjectImportStatuses] = useState<{ [projectId: string]: { type: string; status: string; progress?: number } }>({});
  // Queue status visibility
  const [showQueueStatus, setShowQueueStatus] = useState(false);
  const collaborationPanelRef = useRef<CollaborationPanelRef>(null);
  const [showOpenDialog, setShowOpenDialog] = useState(false);
  const [activeOntologySubTab, setActiveOntologySubTab] = useState('prefixes');
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  const [isAddAnnotationDialogOpen, setAddAnnotationDialogOpen] = useState(false);
  const [isEditAnnotationDialogOpen, setEditAnnotationDialogOpen] = useState(false);
  const [editAnnotationData, setEditAnnotationData] = useState<{propertyIri: string, currentValue: string, entityId: string} | null>(null);
  const [isAddClassDialogOpen, setAddClassDialogOpen] = useState(false);
  const [addClassType, setAddClassType] = useState<'subclass' | 'sibling'>('subclass');
  const [classParentLabel, setClassParentLabel] = useState('owl:Thing');
  const [isAddPropertyDialogOpen, setAddPropertyDialogOpen] = useState(false);
  const [addPropertyType, setAddPropertyType] = useState<'subproperty' | 'sibling' | 'root'>('root');
  const [propertyParentLabel, setPropertyParentLabel] = useState('owl:topObjectProperty');
  const [isAddDatatypeDialogOpen, setAddDatatypeDialogOpen] = useState(false);
  const [isKeyboardShortcutsDialogOpen, setKeyboardShortcutsDialogOpen] = useState(false);
  const [isEntityPreferencesDialogOpen, setEntityPreferencesDialogOpen] = useState(false);

  useEffect(() => {
    hasUserSelectedFileRef.current = hasUserSelectedFile;
  }, [hasUserSelectedFile]);

  // Entity Preferences
  const { preferences, updatePreferences } = useEntityPreferences();

  // Selector Dialog State
  const [isClassSelectorOpen, setIsClassSelectorOpen] = useState(false);
  const [isPropertyExpressionDialogOpen, setIsPropertyExpressionDialogOpen] = useState(false);
  const [isClassExpressionDialogOpen, setIsClassExpressionDialogOpen] = useState(false);
  const [selectorTarget, setSelectorTarget] = useState<'domain' | 'range' | 'subProperty' | 'inverse' | 'disjoint' | 'equivalent' | null>(null);
  
  // Confirm dialog state
  const [confirmDialog, setConfirmDialog] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
    onCancel?: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: () => {},
    onCancel: undefined
  });

  const [selectedClassForIndividuals, setSelectedClassForIndividuals] = useState<TreeNode | null>(null);
  const [dlQuery, setDlQuery] = useState('Pizza and hasTopping some MozzarellaTopping');
  const [dlQueryResults, setDlQueryResults] = useState<string[] | null>(null);
  const [isDlQueryLoading, setIsDlQueryLoading] = useState(false);

  const [classHierarchy, setClassHierarchy] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<any[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<any[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<AnnotationProperty[]>([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<Datatype[]>([]);

  const [listOfFiles, setListOfFiles] = useState<FileInfo[]>([]);
  const [myFiles, setMyFiles] = useState<FileInfo[]>([]);
  const [sharedFiles, setSharedFiles] = useState<FileInfo[]>([]);
  const [isShareDialogOpen, setIsShareDialogOpen] = useState(false);
  const [shareFileId, setShareFileId] = useState<string | null>(null);
  const [isCurrentFileShared, setIsCurrentFileShared] = useState(false);
  const [isHistoryPanelOpen, setIsHistoryPanelOpen] = useState(false);
  const [showCollaborationPanel, setShowCollaborationPanel] = useState(false);

  const [visibleMainTabs, setVisibleMainTabs] = useState(['ActiveOntology', 'Entities', 'IndividualsByClass', 'DLQuery', 'CodeView', 'SPARQL']);
  const [showPluginMarketplace, setShowPluginMarketplace] = useState(false);
  const [installedPlugins, setInstalledPlugins] = useState<Set<string>>(new Set());
  
  // Code View states
  const [codeViewFormat, setCodeViewFormat] = useState<'turtle' | 'rdfxml' | 'ntriples' | 'owl'>('turtle');
  const [codeViewContent, setCodeViewContent] = useState<string>('');
  const [codeViewLoading, setCodeViewLoading] = useState(false);

  // Calculate active users count for current project
  const activeUsersInProject = Array.from(collaboration.state.activeUsers.values()).filter(
    user => user.projectId === projectId
  );
  const hasMultipleActiveUsers = activeUsersInProject.length > 1;
  // #endregion

  // #region Data Fetching and Initialization
  // Plugin marketplace handlers
  const handleInstallPlugin = useCallback(async (pluginId: string) => {
    try {
      // Use pluginLoader to install and load the plugin
      await pluginLoader.installPlugin(pluginId);
      await pluginLoader.loadPlugin(pluginId);
      
      // Only update state if installation and loading succeeded
      setInstalledPlugins(prev => new Set([...prev, pluginId]));
      
      // Map plugin IDs to their corresponding tab IDs and add to visible tabs
      const pluginToTabMap: Record<string, string> = {
        'swrl-editor-plugin': 'SWRL',
        'graph-view-plugin': 'Graph',
        'fuzzy-ontology-plugin': 'Fuzzy',
        'change-assistant-plugin': 'Changes'
      };
      
      const tabId = pluginToTabMap[pluginId];
      if (tabId) {
        setVisibleMainTabs(prev => {
          if (!prev.includes(tabId)) {
            return [...prev, tabId];
          }
          return prev;
        });
      }
      
      console.log(`[Dashboard] Plugin ${pluginId} installed and loaded`);
      notificationService.success('Plugin Installed', `${pluginId} has been installed successfully`);
    } catch (error) {
      console.error(`[Dashboard] Failed to install plugin ${pluginId}:`, error);
      notificationService.error('Plugin Installation Failed', `Failed to install ${pluginId}. ${error instanceof Error ? error.message : 'Please check console for details'}`);
      
      // Make sure to uninstall if loading failed
      try {
        await pluginLoader.uninstallPlugin(pluginId);
      } catch (uninstallError) {
        console.error('Failed to cleanup after failed installation:', uninstallError);
      }
      
      throw error;
    }
  }, []);

  const handleUninstallPlugin = useCallback(async (pluginId: string) => {
    try {
      await pluginLoader.uninstallPlugin(pluginId);
      
      setInstalledPlugins(prev => {
        const newSet = new Set(prev);
        newSet.delete(pluginId);
        return newSet;
      });
      
      // Map plugin IDs to internal plugin IDs and deactivate
      // Remove the corresponding tab from visible tabs
      const pluginToTabMap: Record<string, string> = {
        'swrl-editor-plugin': 'SWRL',
        'graph-view-plugin': 'Graph',
        'fuzzy-ontology-plugin': 'Fuzzy',
        'change-assistant-plugin': 'Changes'
      };
      
      const tabId = pluginToTabMap[pluginId];
      if (tabId) {
        setVisibleMainTabs(prev => prev.filter(t => t !== tabId));
        // Switch to Entities tab if the current tab is being removed
        setMainTab(current => current === tabId ? 'Entities' : current);
      }
      
      console.log(`[Dashboard] Plugin ${pluginId} uninstalled`);
    } catch (error) {
      console.error(`[Dashboard] Failed to uninstall plugin ${pluginId}:`, error);
      throw error;
    }
  }, []);

  // Check status once (no polling - rely on WebSocket notifications)
  const waitForProcessingComplete = useCallback(async (currentProjectId: string): Promise<boolean> => {
    try {
      const statusRes = await apiClient.get<any>(`/api/ontology/status/${currentProjectId}`);
      const status = statusRes?.data?.status || statusRes?.status;

      console.log(`[Dashboard] Project ${currentProjectId} status:`, status);

      if (status === 'COMPLETED') {
        return true;
      }

      if (status === 'ERROR') {
        console.error('[Dashboard] Project processing failed');
        return false;
      }

      // If PROCESSING, WebSocket will notify when complete
      console.log('[Dashboard] File is processing, waiting for WebSocket notification...');
      return true; // Don't block - let WebSocket handle it
    } catch (error) {
      console.error('[Dashboard] Error checking project status:', error);
      return true; // Don't block on error
    }
  }, []);

  const fetchData = useCallback(async (currentProjectId: string, waitForCompletion = false) => {
    // Don't block UI - let user continue working
    setSelectedItem(null);
    setSearchQuery("");
    
    // Show loading indicator if user chose to wait
    if (waitForCompletion) {
      setIsInitialLoading(true);
    }
    
    // Notify user that loading has started
    console.log(`Loading ontology "${currentProjectId}"...`);
    console.log('[Dashboard] 🔄 Fetching data for project:', currentProjectId);
    console.log('[Dashboard] 📊 Collaboration status:', collaboration.state.connected);
    
    // Request collaboration status when loading a new file
    if (window.vscode) {
      window.vscode.postMessage({ type: 'requestCollaborationStatus' });
    }

    try {
      // Wait for processing to complete before fetching data
      console.log('[Dashboard] Waiting for file processing to complete...');
      const isReady = await waitForProcessingComplete(currentProjectId);
      
      if (!isReady) {
        notificationService.error('Loading Failed', 'File is still processing. Please wait a moment and try again.');
        setIsInitialLoading(false);
        return;
      }
      
      console.log('[Dashboard] File processing complete, fetching ontology data...');
      console.log('[Dashboard] 📡 Loading data from GraphDB database for:', currentProjectId);
      
      // Fetch data in background
      const dataFetchPromise = Promise.all([
        apiClient.get<any>(`/api/ontology/metadata/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/classes/top-level/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/properties/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/individuals/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/annotation-properties/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/datatypes/${currentProjectId}`),
      ]);
      
      // Allow UI to be responsive immediately if not waiting
      if (!waitForCompletion) {
        setTimeout(() => {
          setIsInitialLoading(false);
        }, 500);
      }
      
      // Continue loading in background
      const [metadataRes, topLevelRes, propertiesRes, individualsRes, annotationPropsRes, datatypesRes] = await dataFetchPromise;
      
      console.log('[Dashboard] ✅ Data loaded from GraphDB database successfully!');
      console.log('[Dashboard] 📊 This data includes all saved changes from the database');
      
      // Handle metadata response - backend returns {success: true, data: {counts: {...}, prefixes: [...], ontologyIRI: "...", ...}}
      console.log("Metadata response:", metadataRes);
      const metadataData = metadataRes?.data || metadataRes;
      // Keep all metadata fields from backend (axiom counts, ontologyIRI, etc.)
      const transformedMetadata = {
        ...metadataData,
        // Also add flat structure for backward compatibility
        classCount: metadataData?.classCount || metadataData?.counts?.classes || 0,
        objectPropertyCount: metadataData?.objectPropertyCount || metadataData?.counts?.objectProperties || 0,
        dataPropertyCount: metadataData?.dataPropertyCount || metadataData?.counts?.dataProperties || 0,
        individualCount: metadataData?.individualCount || metadataData?.counts?.individuals || 0,
        annotationPropertyCount: metadataData?.annotationPropertyCount || metadataData?.counts?.annotationProperties || 0,
        tripleCount: metadataData?.tripleCount || metadataData?.counts?.triples || 0,
        prefixes: metadataData?.prefixes || []
      };
      console.log("Transformed metadata:", transformedMetadata);
      setMetadata(transformedMetadata);

      // Handle classes response - backend returns {success: true, classes: [...]}
      console.log("=== CLASSES RESPONSE DEBUG ===");
      console.log("Raw topLevelRes:", topLevelRes);
      console.log("topLevelRes type:", typeof topLevelRes);
      console.log("topLevelRes keys:", Object.keys(topLevelRes || {}));
      
      // The response structure is: topLevelRes = {success: true, classes: [...]}
      // But apiClient might wrap it in a data field, so check both
      let classes: any[] = [];
      
      if (Array.isArray(topLevelRes?.classes)) {
        classes = topLevelRes.classes;
        console.log("Found classes in topLevelRes.classes");
      } else if (Array.isArray(topLevelRes?.data?.classes)) {
        classes = topLevelRes.data.classes;
        console.log("Found classes in topLevelRes.data.classes");
      } else if (Array.isArray(topLevelRes?.data)) {
        classes = topLevelRes.data;
        console.log("Found classes in topLevelRes.data (array)");
      } else if (Array.isArray(topLevelRes)) {
        classes = topLevelRes;
        console.log("topLevelRes itself is an array");
      } else {
        console.error("Could not find classes array in response structure!");
        console.error("Available keys:", Object.keys(topLevelRes || {}));
        if (topLevelRes?.data) {
          console.error("Data keys:", Object.keys(topLevelRes.data || {}));
        }
      }
      
      console.log("Extracted classes array length:", classes.length);
      console.log("First 3 classes:", classes.slice(0, 3));
      console.log("=== END CLASSES DEBUG ===");
      
      // Nest all top-level classes under owl:Thing
      const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
        ...c,
        children: [],
        hasChildren: c.hasChildren,
        subClassOfAxioms: [{ id: 'sub1', type: 'SubClassOf', definition: 'Thing' }]
      }));
      
      console.log("=== OWL:THING HIERARCHY DEBUG ===");
      console.log("topLevelNodes count:", topLevelNodes.length);
      console.log("First 3 topLevelNodes:", topLevelNodes.slice(0, 3));
      
      // Always include owl:Thing at the root with pre-loaded children
      const owlThingNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#Thing",
        label: "owl:Thing",
        children: topLevelNodes,
        hasChildren: topLevelNodes.length > 0,
        annotations: {}
      };
      
      console.log("owlThingNode created with children count:", owlThingNode.children?.length);
      console.log("Setting classHierarchy with owl:Thing");
      console.log("=== END OWL:THING DEBUG ===");
      
      setClassHierarchy([owlThingNode]);

      // Handle properties response
      console.log("=== PROPERTIES RESPONSE DEBUG ===");
      console.log("Properties response:", propertiesRes);
      const allProps = Array.isArray(propertiesRes?.data) ? propertiesRes.data : 
                       Array.isArray(propertiesRes?.properties) ? propertiesRes.properties : 
                       Array.isArray(propertiesRes) ? propertiesRes : [];
      console.log("All props after extraction:", allProps);
      console.log("All props length:", allProps.length);
      const opList = allProps.filter((p: Property) => p.type === "ObjectProperty");
      console.log("Object Properties filtered (opList):", opList);
      console.log("Object Properties count:", opList.length);
      setObjectProperties(opList);
      console.log("=== END PROPERTIES DEBUG ===");

      // Build Object Property Hierarchy
      const opMap = new Map<string, any>();
      // Create nodes
      opList.forEach((p: Property) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topObjectProperty: any = {
        id: 'http://www.w3.org/2002/07/owl#topObjectProperty',
        label: 'owl:topObjectProperty',
        type: 'ObjectProperty' as const,
        children: [] as any[],
        hasChildren: false,
        annotations: {}
      };
      
      // If topObjectProperty is not in the list (it usually isn't), we use our created one.
      // If it IS in the list, we should use that one but ensure it's the root.
      // Typically backend doesn't return built-in top properties in the list of user properties.
      
      opList.forEach((p: Property) => {
        const node = opMap.get(p.id);
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach(superId => {
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
          // If has super properties but none found in map (e.g. external), add to top?
          // Or if it has super properties, it shouldn't be at top level unless explicitly under top.
          // If we didn't add it to any parent, and it's not explicitly under top, what to do?
          // For now, if not added to any known parent, add to topObjectProperty as fallback
          if (!added) {
             topObjectProperty.children.push(node);
             topObjectProperty.hasChildren = true;
          }
        } else {
          // No super properties -> child of topObjectProperty
          topObjectProperty.children.push(node);
          topObjectProperty.hasChildren = true;
        }
      });
      
      setObjectPropertyHierarchy([topObjectProperty]);

      const dpList = allProps.filter((p: Property) => p.type === "DatatypeProperty");
      console.log("Data Properties filtered (dpList):", dpList);
      console.log("Data Properties count:", dpList.length);
      console.log("All property types:", allProps.map((p: Property) => ({ id: p.id, type: p.type })));
      setDataProperties(dpList);

      // Build Data Property Hierarchy
      const dpMap = new Map<string, any>();
      dpList.forEach((p: Property) => {
        dpMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topDataProperty: any = {
        id: 'http://www.w3.org/2002/07/owl#topDataProperty',
        label: 'owl:topDataProperty',
        type: 'DatatypeProperty',
        children: [] as any[],
        hasChildren: false,
        annotations: {}
      };

      dpList.forEach((p: Property) => {
        const node = dpMap.get(p.id);
        if (p.superProperties && p.superProperties.length > 0) {
          let added = false;
          p.superProperties.forEach(superId => {
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

      // Handle other responses with fallbacks
      setIndividuals(Array.isArray(individualsRes?.data) ? individualsRes.data : 
                    Array.isArray(individualsRes?.individuals) ? individualsRes.individuals : []);
      setAnnotationProperties(Array.isArray(annotationPropsRes?.data) ? annotationPropsRes.data :
                              Array.isArray(annotationPropsRes?.annotationProperties) ? annotationPropsRes.annotationProperties : []);
      setDatatypes(Array.isArray(datatypesRes?.data) ? datatypesRes.data :
                  Array.isArray(datatypesRes?.datatypes) ? datatypesRes.datatypes : []);
      
      // Fetch files list separately (not in parallel to avoid blocking main data load)
      try {
        const userEmail = user?.email || '';
        const filesRes = await apiClient.get<any>(`/api/projects?userEmail=${encodeURIComponent(userEmail)}`);
        
        let myProjectsList: any[] = [];
        let sharedProjectsList: any[] = [];
        
        if (filesRes.myFiles && filesRes.sharedFiles) {
          // New format with separate lists
          myProjectsList = Array.isArray(filesRes.myFiles) ? filesRes.myFiles : [];
          sharedProjectsList = Array.isArray(filesRes.sharedFiles) ? filesRes.sharedFiles : [];
          
          setMyFiles(myProjectsList.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0,
            ownerEmail: p.ownerEmail
          })));
          
          setSharedFiles(sharedProjectsList.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0,
            sharedBy: p.sharedBy,
            ownerEmail: p.ownerEmail,
            permission: p.permission || 'view'
          })));
          
          console.log('[Dashboard] 📂 Loaded shared files:', sharedProjectsList.length);
          console.log('[Dashboard] 🤝 Collaboration features available for shared editing');
          
          // Combined list for backward compatibility
          setListOfFiles([...myProjectsList, ...sharedProjectsList].map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0
          })));
        } else {
          // Old format (backward compatibility)
          const projects = Array.isArray(filesRes?.projects) ? filesRes.projects : [];
          myProjectsList = projects;
          setListOfFiles(projects.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0
          })));
          setMyFiles(projects.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0
          })));
          setSharedFiles([]);
        }
        
        // Check if current file is shared (for real-time collaboration)
        // Use the freshly fetched data, not state variables
        // A file is shared if:
        // 1. It's in sharedFiles list (shared WITH me by someone else)
        // 2. It's in myFiles and has sharedWith array (shared BY me with others)
        const isSharedWithMe = sharedProjectsList.some((f: any) => f.id === currentProjectId);
        const isSharedByMe = myProjectsList.some((f: any) => f.id === currentProjectId && f.sharedWith && f.sharedWith.length > 0);
        const isShared = isSharedWithMe || isSharedByMe;
        setIsCurrentFileShared(isShared);
        
        console.log('[Dashboard] 📊 File shared status:', isShared, 'for project:', currentProjectId);
        console.log('[Dashboard] 📥 Shared WITH me:', isSharedWithMe);
        console.log('[Dashboard] 📤 Shared BY me:', isSharedByMe);
        console.log('[Dashboard] 📋 Shared files list:', sharedProjectsList.map((f: any) => f.id));
        console.log('[Dashboard] 📋 My files list:', myProjectsList.map((f: any) => f.id));
        
        // Configure mutation service based on whether file is shared
        ontologyMutationService.setRealTimeSync(isShared);
        setSyncMode(isShared ? 'public' : 'private');
        
        // Only start monitoring for shared files (real-time collaboration)
        if (isShared) {
          console.log('[Dashboard] 📤 File is shared - enabling real-time collaboration');
          
          // Start monitoring for changes from other users
          const handleDataChanged = async (changedProjectId: string) => {
            console.log('[Dashboard] 🔄 Change detected from another user! Refreshing data...');
            notificationService.info('New Changes Available', 'Another user saved changes. Refreshing data...');
            
            // Refresh data and restart monitoring for another 30 seconds
            await fetchData(changedProjectId, false);
            console.log('[Dashboard] ✅ Refresh complete, monitoring restarted');
          };
          
          try {
            const timestampData = await apiClient.get<{ updatedAt: string }>(`/api/ontology/metadata/${currentProjectId}/timestamp`);
            if (timestampData && timestampData.updatedAt) {
              const currentTimestamp = new Date(timestampData.updatedAt).getTime();
              syncService.startMonitoring(currentProjectId, handleDataChanged, currentTimestamp);
              console.log('[Dashboard] 🔍 Started monitoring for changes (30 seconds)');
            }
          } catch (error) {
            console.warn('[Dashboard] Could not start change monitoring:', error);
          }
        } else {
          console.log('[Dashboard] 📝 File is private - using draft mode (click Save to apply changes)');
        }
      } catch (fileError) {
        console.error("Failed to fetch files:", fileError);
        setListOfFiles([]);
        setMyFiles([]);
        setSharedFiles([]);
      }
      
      // Stop any previous monitoring for this project
      syncService.stopMonitoring(currentProjectId);
      
      // Notify user that ontology is fully loaded
      notificationService.success(
        'Ontology Loaded',
        `"${currentProjectId}" is ready! Found ${classes.length} classes, ${allProps.length} properties.`
      );
    } catch (error) {
      console.error("Failed to fetch data:", error);
      
      // Notify user of the error
      notificationService.error(
        'Loading Failed',
        `Failed to load ontology "${currentProjectId}". Please try again.`
      );
    } finally {
      setIsInitialLoading(false);
    }
  }, []); // waitForProcessingComplete doesn't depend on state/props, stable reference

  // Update real-time sync status based on collaboration state
  useEffect(() => {
    if (!projectId) return;
    
    const activeUsersInProject = Array.from(collaboration.state.activeUsers.values())
        .filter(u => u.projectId === projectId && u.userId !== user?.id);
        
    if (activeUsersInProject.length > 0) {
        console.log('[Dashboard] 👥 Collaborators detected, enabling real-time sync');
        ontologyMutationService.setRealTimeSync(true);
        setSyncMode('public');
    }
  }, [projectId, collaboration.state.activeUsers, user?.id]);

  const fetchProjects = useCallback(async () => {
    try {
      const userEmail = user?.email || '';
      const response = await apiClient.get<{ success: boolean; projects?: any[]; myFiles?: any[]; sharedFiles?: any[] }>(`/api/projects?userEmail=${encodeURIComponent(userEmail)}`);
      
      setHasFetchedProjects(true);
      
      if (response.success) {
        // Handle new format with myFiles and sharedFiles
        if (response.myFiles && response.sharedFiles) {
          const allProjects = [...(response.myFiles || []), ...(response.sharedFiles || [])];
          setAvailableProjects(allProjects);
          setMyFiles(response.myFiles || []);
          setSharedFiles(response.sharedFiles || []);
          console.log('[Dashboard] Fetched', response.myFiles.length, 'myFiles and', response.sharedFiles.length, 'sharedFiles');
          
          // Only auto-load the first file if:
          // 1. No projectId is set
          // 2. User hasn't manually clicked on any file
          // 3. Not expecting a fileReady message from upload
          // 4. This is NOT the initial mount (hasFetchedProjects was false before this call)
          const shouldAutoLoad = !projectId && !hasUserSelectedFileRef.current && !isExpectingFileReady && hasFetchedProjects;
          
          if (shouldAutoLoad && response.myFiles.length > 0) {
            const firstProject = response.myFiles[0];
            console.log('[Dashboard] Auto-loading first project:', firstProject.id);
            setProjectId(firstProject.id);
            fetchData(firstProject.id);
          } else if (!projectId && response.myFiles.length === 0 && response.sharedFiles.length === 0) {
            // No files at all - show empty state
            console.log('[Dashboard] No files found, showing empty state');
            setIsInitialLoading(false);
          }
        } else if (response.projects) {
          // Backward compatibility with old format
          setAvailableProjects(response.projects);
          // Assume all projects are "myFiles" if no sharedBy field
          setMyFiles(response.projects.filter((p: any) => !p.sharedBy));
          setSharedFiles(response.projects.filter((p: any) => p.sharedBy));
          
          const shouldAutoLoad = !projectId && !hasUserSelectedFileRef.current && !isExpectingFileReady && hasFetchedProjects;
          
          if (shouldAutoLoad && response.projects.length > 0) {
            const firstProject = response.projects[0];
            console.log('[Dashboard] Auto-loading first project:', firstProject.id);
            setProjectId(firstProject.id);
            fetchData(firstProject.id);
          } else if (!projectId && response.projects.length === 0) {
            // No files at all - show empty state
            console.log('[Dashboard] No files found, showing empty state');
            setIsInitialLoading(false);
          }
        }
      }
    } catch (error) {
      console.error("Failed to fetch projects:", error);
      setIsInitialLoading(false);
    }
  }, [projectId, fetchData, user, isExpectingFileReady]);

  const handleProjectSelection = useCallback((selectedProjectId: string) => {
    setHasUserSelectedFile(true); // Mark that user has manually selected a file
    setProjectId(selectedProjectId);
    setShowProjectSelector(false);
    fetchData(selectedProjectId);
  }, [fetchData]);

  const handleOpenProjectSelector = useCallback(() => {
    // Fetch projects when user opens the selector
    fetchProjects();
    setShowProjectSelector(true);
  }, [fetchProjects]);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = classHierarchy[0].id;
      const childCount = classHierarchy[0].children?.length || 0;
      console.log('[Dashboard] Class hierarchy loaded, owl:Thing has', childCount, 'top-level children');
      
      // Auto-expand owl:Thing when it has children (preserve other expanded nodes)
      if (childCount > 0 && !expandedNodes.includes(owlThingId)) {
        console.log('[Dashboard] Auto-expanding owl:Thing (preserving existing expanded nodes)');
        console.log('[DEBUG] useEffect[classHierarchy] triggering setExpandedNodes');
        setExpandedNodes(prev => prev.includes(owlThingId) ? prev : [...prev, owlThingId]);
      }
    }
  }, [classHierarchy]);

  // Fetch projects list on mount (but don't auto-load a file)
  // This populates the file selector dropdown when user clicks it
  useEffect(() => {
    console.log('[Dashboard] Initial mount - fetching projects list');
    fetchProjects();
  }, [fetchProjects]);
  
  // Update collaboration context when projectId changes
  useEffect(() => {
    if (collaboration?.setCurrentProject) {
      collaboration.setCurrentProject(projectId);
    }
  }, [projectId, collaboration]);

  // Track if component is mounted to prevent race conditions
  const isMountedRef = useRef(false);
  
  // Send 'webviewReady' to extension when mounted
  useEffect(() => {
    isMountedRef.current = true;
    if (window.vscode) {
      window.vscode.postMessage({ type: 'webviewReady' });
    }
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // Handle loading choice dialog actions
  const handleWaitForLoading = useCallback(() => {
    userLoadingChoice.current = 'wait';
    // Keep dialog open, show waiting state
    console.log('[Dashboard] Wait for Loading clicked - keeping dialog open');
    // Dialog will be closed by IMPORT_COMPLETED handler when data loads
  }, []);

  const handleContinueWorking = useCallback(() => {
    userLoadingChoice.current = 'continue';
    setShowLoadingChoice(false);
    console.log('[Dashboard] Continue Working clicked - closing dialog, will auto-load when import completes');
    // Keep isExpectingFileReady=true so IMPORT_COMPLETED will auto-load
    // Reset choice after a short delay
    setTimeout(() => {
      userLoadingChoice.current = null;
    }, 100);
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      // Ignore messages until component is fully mounted
      if (!isMountedRef.current) {
        console.log('[Dashboard] Ignoring message before mount:', event.data.type);
        return;
      }
      
      const message = event.data;
      console.log('[Dashboard] Received message:', message.type, message);
      switch (message.type) {
        case "showLoading":
          console.log('[Dashboard] showLoading received - file upload starting for project:', message.projectId);
          setHasUserSelectedFile(true);
          hasUserSelectedFileRef.current = true;
          pendingImportProjectIdRef.current = message.projectId; // Track which project is being imported
          console.log('[Dashboard] Set pendingImportProjectIdRef.current to:', pendingImportProjectIdRef.current);
          setIsExpectingFileReady(true);
          // Show loading dialog immediately
          setShowLoadingChoice(true);
          setLoadingProjectName(message.projectId || 'Processing file upload...');
          // Don't fetch projects yet - wait for upload to complete
          break;
        case "fileReady":
        case "fileLoaded":
          // Show loading choice dialog
          console.log('[Dashboard] Loading project:', message.projectId);
          // Don't clear isExpectingFileReady here - let IMPORT_COMPLETED handler do it
          setHasUserSelectedFile(true);
          hasUserSelectedFileRef.current = true;
          setProjectId(message.projectId);
          setSelectedItem(null);
          setLoadingProjectName(message.projectId);
          userLoadingChoice.current = null; // Reset choice for new loading
          setShowLoadingChoice(true);

          // Start loading in background and store the promise
          loadingPromiseRef.current = fetchData(message.projectId, false)
            .then(() => {
              console.log('[Dashboard] Loading completed for:', message.projectId);
              // Close loading dialog immediately on success
              setShowLoadingChoice(false);
              setShowQueueStatus(false);
              // Refresh projects list
              setTimeout(() => fetchProjects(), 300);
              // Dialog will auto-close via importStatusUpdate message when IMPORT_COMPLETED
            })
            .catch((error) => {
              console.error('[Dashboard] Failed to load ontology:', error);
              notificationService.error('Load Failed', `Could not load "${message.projectId}". The file may still be processing.`);
              setShowLoadingChoice(false);
              // Dialog will auto-close via importStatusUpdate message when IMPORT_FAILED
            });
          break;
        case "loadingFailed":
          setIsInitialLoading(false);
          console.error("Loading failed:", message.error);
          notificationService.error('Loading Failed', message.error);
          break;
        case "switchView":
          // Switch to SWRL view (now handled via plugins)
          if (message.view === 'swrl') {
            setMainTab('SWRL');
          }
          break;
        case "importStatusUpdate":
          // Handle import status updates from WebSocket
          console.log('[Dashboard] Import status update:', message.status);
          console.log('[Dashboard] Current projectId:', projectId);
          console.log('[Dashboard] Message projectId:', message.status.projectId);
          console.log('[Dashboard] Status type:', message.status.type);

          // Update project-specific import status for ProjectSelector
          if (message.status.projectId) {
            setProjectImportStatuses(prev => ({
              ...prev,
              [message.status.projectId]: {
                type: message.status.type,
                status: message.status.status,
                progress: message.status.progress
              }
            }));
            
            // Update loading status message for user feedback
            if (message.status.type === 'IMPORT_PROGRESS' && message.status.metadata?.message) {
              setLoadingStatusMessage(message.status.metadata.message);
            } else if (message.status.type === 'IMPORT_PROGRESS' && message.status.metadata?.stage) {
              const stage = message.status.metadata.stage;
              const stageMessages: Record<string, string> = {
                'parsing': 'Parsing ontology file...',
                'graphdb-loading': 'Loading data into GraphDB (this may take several minutes for large files)...',
                'graphdb-load-complete': 'GraphDB load complete, computing metadata...',
                'computing-metadata': 'Computing ontology statistics...'
              };
              setLoadingStatusMessage(stageMessages[stage] || 'Processing...');
            }
          }

          // Handle import completion
          if (message.status.type === 'IMPORT_COMPLETED') {
            console.log('[Dashboard] ✅ IMPORT_COMPLETED for project:', message.status.projectId);
            console.log('[Dashboard] User choice:', userLoadingChoice.current);
            console.log('[Dashboard] Current projectId:', projectId);
            console.log('[Dashboard] pendingImportProjectIdRef.current:', pendingImportProjectIdRef.current);
            console.log('[Dashboard] isExpectingFileReady:', isExpectingFileReady);
            
            const isCurrentProject = message.status.projectId === projectId;
            const isPendingImport = message.status.projectId === pendingImportProjectIdRef.current;
            const userChoice = userLoadingChoice.current;
            
            // Only auto-load if:
            // 1. This is the current project being viewed, OR
            // 2. This matches the pendingImportProjectId (new upload)
            if (isCurrentProject || isPendingImport) {
              console.log('[Dashboard] Should auto-load:', isPendingImport ? 'pending import' : 'current project');
              
              // Set projectId for new uploads
              if (isPendingImport && !projectId) {
                console.log('[Dashboard] Setting projectId to:', message.status.projectId);
                setProjectId(message.status.projectId);
                setLoadingProjectName(message.status.projectId);
              }
              
              // Clear pending import tracking
              pendingImportProjectIdRef.current = null;
              console.log('[Dashboard] Cleared pendingImportProjectIdRef');
              setIsExpectingFileReady(false);
              
              // Fetch the data
              console.log('[Dashboard] Fetching data for:', message.status.projectId);
              fetchData(message.status.projectId, false)
                .then(() => {
                  console.log('[Dashboard] ✅ Data loaded successfully');
                  console.log('[Dashboard] Closing dialogs...');
                  // Close all dialogs after successful load
                  setShowLoadingChoice(false);
                  setShowQueueStatus(false);
                  setShowProjectSelector(false);
                  setIsInitialLoading(false);
                  // Reset user choice
                  userLoadingChoice.current = null;
                })
                .catch((error) => {
                  console.error('[Dashboard] ❌ Failed to fetch data:', error);
                  setShowLoadingChoice(false);
                  setIsInitialLoading(false);
                  notificationService.error('Load Failed', 'Failed to load ontology data');
                });
              
              // Refresh projects list
              setTimeout(() => fetchProjects(), 500);
            } else {
              console.log('[Dashboard] Import completed for different project - not auto-loading');
            }
          }

          // If import failed, close loading choice dialog
          if (message.status.type === 'IMPORT_FAILED' && message.status.projectId === projectId) {
            console.log('[Dashboard] Import failed');
            setTimeout(() => {
              setShowLoadingChoice(false);
              setShowQueueStatus(false);
            }, 2000);
          }

          // Show queue status when import starts
          if (message.status.type === 'IMPORT_STARTED' && message.status.projectId === projectId) {
            setShowQueueStatus(true);
          }

          // Clear project-specific status after completion/failure
          if (message.status.projectId && (message.status.type === 'IMPORT_COMPLETED' || message.status.type === 'IMPORT_FAILED')) {
            setTimeout(() => {
              setProjectImportStatuses(prev => {
                const updated = { ...prev };
                delete updated[message.status.projectId];
                return updated;
              });
            }, message.status.type === 'IMPORT_COMPLETED' ? 3000 : 10000);
          }
          break;
      }
    };
    window.addEventListener("message", handleMessage);

    return () => {
      window.removeEventListener("message", handleMessage);
    };
  }, [visibleMainTabs, fetchData]);
  
  const loadChildren = useCallback(async (nodeId: string) => {
    if (!projectId) return;
    try {
      console.log(`Loading children for node: ${nodeId}`);
      const response = await apiClient.get<any>(`/api/ontology/classes/children/${projectId}?parentIri=${encodeURIComponent(nodeId)}`);
      console.log('Children response:', response);
      
      // Extract array from response - handle both direct array and wrapped responses
      const children = Array.isArray(response) ? response : 
                      Array.isArray(response?.data) ? response.data : 
                      Array.isArray(response?.classes) ? response.classes : [];
      console.log('Extracted children:', children);

      const updateTree = (nodes: TreeNode[]): TreeNode[] =>
        nodes.map((n: TreeNode) => {
          if (n.id === nodeId) {
            return {
              ...n,
              children: children.map((c: TopLevelClass) => ({
                ...c,
                children: c.hasChildren ? undefined : undefined, // Use undefined for consistency
                hasChildren: c.hasChildren,
                subClassOfAxioms: [{ id: nodeId, type: 'SubClassOf', definition: n.label }]
              }))
            };
          }
          if (n.children) {
            return { ...n, children: updateTree(n.children) };
          }
          return n;
        });

      setClassHierarchy(prevHierarchy => updateTree(prevHierarchy));
    } catch (error) {
      console.error(`Failed to load children for ${nodeId}`, error);
    }
  }, [projectId]);

  const updateItemInState = useCallback((updatedItem: SelectableItem, markUnsaved: boolean = true) => {
    console.log('[DEBUG] updateItemInState called for item:', updatedItem.id, 'markUnsaved:', markUnsaved);
    console.log('[CHANGE TRACKING] Entity updated:', {
      entityId: updatedItem.id,
      entityLabel: updatedItem.label,
      entityType: entitiesTab,
      modifiedBy: user?.username || 'anonymous',
      timestamp: new Date().toISOString()
    });

    const updateRecursively = (items: SelectableItem[]): SelectableItem[] => {
      return items.map(item => {
        if (item.id === updatedItem.id) {
          // Preserve children from the existing item if the new item doesn't have them populated
          // The updatedItem from details endpoint usually doesn't have the full children tree
          const existingChildren = (item as TreeNode).children;
          const newChildren = (updatedItem as TreeNode).children;
          
          return { 
            ...updatedItem, 
            children: newChildren && newChildren.length > 0 ? newChildren : existingChildren 
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
    setSelectedItem(prev => {
      if (prev?.id === updatedItem.id) {
        console.log('[Dashboard] Updating selected item in state (ID match)');
        return updatedItem;
      }
      return prev;
    });

    switch (entitiesTab) {
      case 'Classes':
        setClassHierarchy(prev => updateRecursively(prev) as TreeNode[]);
        break;
      case 'ObjectProperties':
        setObjectProperties(prev => prev.map(p => p.id === updatedItem.id ? updatedItem as Property : p));
        break;
      case 'DataProperties':
        setDataProperties(prev => prev.map(p => p.id === updatedItem.id ? updatedItem as Property : p));
        break;
      case 'AnnotationProperties':
        setAnnotationProperties(prev => prev.map(p => p.id === updatedItem.id ? updatedItem as AnnotationProperty : p));
        break;
      case 'Individuals':
        setIndividuals(prev => prev.map(i => i.id === updatedItem.id ? updatedItem as Individual : i));
        break;
      case 'Datatypes':
        setDatatypes(prev => prev.map(d => d.id === updatedItem.id ? updatedItem as Datatype : d));
        break;
    }

    // Mark as unsaved to enable Save button (only if markUnsaved is true)
    if (markUnsaved) {
      setHasUnsavedChanges(true);
    }
  }, [entitiesTab, user]);

  const refreshClassHierarchy = useCallback(async () => {
    if (!projectId) return;
    try {
      const topLevelRes = await apiClient.get<any>(`/api/ontology/classes/top-level/${projectId}`);
      
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
        subClassOfAxioms: [{ id: 'sub1', type: 'SubClassOf', definition: 'Thing' }]
      }));

      const owlThingNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#Thing",
        label: "owl:Thing",
        children: topLevelNodes,
        hasChildren: topLevelNodes.length > 0,
        annotations: {}
      };

      setClassHierarchy([owlThingNode]);
      console.log('[Dashboard] ✅ Class hierarchy refreshed via refreshClassHierarchy');
      
      // Re-load children for all previously expanded nodes to preserve tree state
      // We need to reload children in order (parent before child) to maintain tree structure
      const currentExpandedNodes = expandedNodes.filter(id => id !== "http://www.w3.org/2002/07/owl#Thing");
      for (const nodeId of currentExpandedNodes) {
        try {
          await loadChildren(nodeId);
        } catch (err) {
          // Node might not exist anymore after refresh, ignore error
          console.log(`[Dashboard] Could not reload children for ${nodeId}:`, err);
        }
      }
    } catch (error) {
      console.error('[Dashboard] Failed to refresh class hierarchy:', error);
    }
  }, [projectId, expandedNodes, loadChildren]);

  // Handle remote edits from collaborative users in real-time
  useEffect(() => {
    const handleRemoteEdit = (event: Event) => {
      const customEvent = event as CustomEvent;
      const edit = customEvent.detail;
      
      console.log('[Dashboard] 🔄 Handling remote edit event:', edit);
      
      // Immediately reload the affected data based on edit type
      if (!projectId) {
        console.warn('[Dashboard] No project ID, cannot apply remote edit');
        return;
      }
      
      // Check if this is an edit made by the current user - skip refresh since we already updated local state
      const editUserId = (edit as any).userId || (edit as any).user?.id || (edit as any).user;
      const currentUserId = user?.email || user?.id;
      if (editUserId && currentUserId && editUserId === currentUserId) {
        console.log('[Dashboard] ⏭️ Skipping refresh - edit was made by current user');
        return;
      }
      
      // Map edit type to which data needs refreshing
      switch (edit.type) {
        case 'CLASS_ADDED':
          console.log('[Dashboard] 📚 Class added by another user, refreshing hierarchy');
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

        case 'CLASS_DELETED':
          console.log('[Dashboard] 🗑️ Class deleted, refreshing hierarchy');
          if ((edit as any).parent) {
            const parentId = (edit as any).parent;
            console.log(`[Dashboard] Refreshing children of parent: ${parentId}`);
            loadChildren(parentId);
          } else {
             // Fallback to full refresh
             refreshClassHierarchy();
          }
          break;

        case 'CLASS_MODIFIED':
        case 'CLASS_RENAMED':
          console.log('[Dashboard] ✏️ Class modified/renamed:', edit);
          // For modification, we can just fetch details and update state
          // This preserves the tree structure
          const classId = (edit as any).iri || (edit as any).id;
          if (classId) {
             console.log(`[Dashboard] Fetching details for modified class: ${classId}`);
             // Add delay to ensure backend is ready
             setTimeout(() => {
               apiClient.get(`/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(classId)}`)
                .then(response => {
                  const newData = response.data || response;
                  // Ensure ID is present
                  if (!newData.id && newData.iri) {
                    newData.id = newData.iri;
                  }
                  console.log('[Dashboard] Received updated class data:', newData);
                  updateItemInState(newData);
                  console.log('[Dashboard] ✅ Class updated in state');
                })
                .catch(error => console.error('[Dashboard] Failed to refresh class details:', error));
             }, 200);
          } else {
             // Fallback
             console.warn('[Dashboard] No class ID in edit event, falling back to full refresh');
             refreshClassHierarchy();
          }
          break;
          
        case 'ANNOTATION_ADDED':
        case 'ANNOTATION_MODIFIED':
        case 'ANNOTATION_DELETED':
          console.log('[Dashboard] 📝 Refreshing annotation due to annotation edit:', edit);
          
          // Add a small delay to ensure backend consistency
          setTimeout(() => {
            // Trigger refresh of current selected item to show updated annotations
            if (selectedItem) {
              const entityId = selectedItem.id || selectedItem.iri;
              // Check if the edit is relevant to the selected item (optional optimization, but good for correctness)
              // The edit object usually has 'subject' or 'iri'
              const editSubject = (edit as any).subject || (edit as any).iri || (edit as any).id;
              
              if (editSubject && editSubject !== entityId) {
                console.log(`[Dashboard] Edit subject (${editSubject}) does not match selected item (${entityId}), but refreshing anyway to be safe`);
              }

              console.log(`[Dashboard] Refreshing selected item: ${entityId}`);
              
              // Use the appropriate endpoint based on entity type to ensure we get full details (including annotations)
              let url = `/api/ontology/class/${projectId}/${encodeURIComponent(entityId)}`;
              if (entitiesTab === 'Classes') {
                url = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(entityId)}`;
              }
              
              apiClient.get(url)
                .then(response => {
                  const newData = response.data || response;
                  // Ensure ID is present (map IRI to ID if needed)
                  if (!newData.id && newData.iri) {
                    newData.id = newData.iri;
                  }
                  
                  console.log('[Dashboard] Received updated entity data:', newData);
                  // Update both selected item and the item in the state/tree
                  updateItemInState(newData);
                  console.log('[Dashboard] ✅ Selected item refreshed with new annotations');
                })
                .catch(error => console.error('[Dashboard] Failed to refresh selected item:', error));
            } else {
              console.log('[Dashboard] No item selected, skipping annotation refresh');
            }
          }, 200); // 200ms delay
          break;
          
        case 'PROPERTY_ADDED':
        case 'PROPERTY_MODIFIED':
        case 'PROPERTY_DELETED':
          console.log('[Dashboard] 🔗 Refreshing properties due to property edit');
          // Trigger refresh of properties
          apiClient.get(`/api/ontology/object-properties/${projectId}`)
            .then(response => {
              setObjectProperties(response.data || []);
              console.log('[Dashboard] ✅ Object properties refreshed');
            })
            .catch(error => console.error('[Dashboard] Failed to refresh properties:', error));
          break;
          
        case 'INDIVIDUAL_ADDED':
        case 'INDIVIDUAL_MODIFIED':
        case 'INDIVIDUAL_DELETED':
          console.log('[Dashboard] 👤 Refreshing individuals due to individual edit');
          // Trigger refresh of individuals
          apiClient.get(`/api/ontology/individuals/${projectId}`)
            .then(response => {
              setIndividuals(response.data || []);
              console.log('[Dashboard] ✅ Individuals refreshed');
            })
            .catch(error => console.error('[Dashboard] Failed to refresh individuals:', error));
          break;
        
        // Handle SPARQL updates - need full refresh since we don't know what changed
        case 'SPARQL_UPDATE':
          console.log('[Dashboard] 📊 SPARQL update detected, refreshing all data');
          showNotification(`${(edit as any).username || 'Someone'} executed a SPARQL update. Refreshing...`, 'info');
          // Full refresh since SPARQL can change anything
          fetchData(projectId, false);
          break;
        
        // Handle change reverts - need full refresh
        case 'CHANGE_REVERTED':
          console.log('[Dashboard] ⏪ Change reverted, refreshing all data');
          showNotification(`${(edit as any).username || 'Someone'} reverted a change. Refreshing...`, 'info');
          // Full refresh to get the reverted state
          fetchData(projectId, false);
          break;
        
        // Handle project saved by another user
        case 'PROJECT_SAVED':
          console.log('[Dashboard] 💾 Project saved by another user');
          showNotification(`${(edit as any).username || 'Someone'} saved the project with ${(edit as any).appliedChanges || 0} changes`, 'info');
          // Refresh to get the latest saved state
          fetchData(projectId, false);
          break;
        
        // Handle disjoint axiom changes
        case 'DISJOINT_ADDED':
        case 'DISJOINT_REMOVED':
          console.log('[Dashboard] 🔗 Disjoint axiom changed, refreshing class hierarchy');
          refreshClassHierarchy();
          break;
          
        default:
          console.log('[Dashboard] 🔄 Generic remote edit, refreshing metadata');
          // Generic refresh for other edit types
          apiClient.get(`/api/ontology/metadata/${projectId}`)
            .then(response => {
              setMetadata(response.data);
              console.log('[Dashboard] ✅ Metadata refreshed');
            })
            .catch(error => console.error('[Dashboard] Failed to refresh metadata:', error));
      }

      // Refresh collaboration panel changes list to show the new edit immediately
      if (collaborationPanelRef.current) {
        console.log('[Dashboard] 🔄 Refreshing collaboration panel changes');
        collaborationPanelRef.current.refreshChanges();
      }
    };
    
    // Listen for remoteEditReceived events
    window.addEventListener('remoteEditReceived', handleRemoteEdit as EventListener);
    console.log('[Dashboard] 🎧 Registered listener for remote edits');
    
    return () => {
      window.removeEventListener('remoteEditReceived', handleRemoteEdit as EventListener);
      console.log('[Dashboard] 🎧 Unregistered listener for remote edits');
    };
  }, [projectId, selectedItem, entitiesTab, updateItemInState, refreshClassHierarchy, loadChildren, fetchData, showNotification]);

  // Handle rollback events from Change Assistant plugin - refresh data
  useEffect(() => {
    const handleRollback = (event: Event) => {
      const customEvent = event as CustomEvent;
      const detail = customEvent.detail;
      console.log('[Dashboard] 🔄 Rollback event received:', detail);
      
      if (!projectId || detail?.projectId !== projectId) {
        return;
      }
      
      const rollbackUser = detail.username || 'Someone';
      const originalAuthor = detail.originalAuthor || 'Unknown';
      const oldValue = detail.oldValue;
      const newValue = detail.newValue;
      
      // Build notification message with value changes if available
      let message = `${rollbackUser} rolled back change by ${originalAuthor}`;
      if (oldValue && newValue) {
        message += ` (from "${oldValue}" back to "${newValue}")`;
      } else if (newValue) {
        message += ` (restored to "${newValue}")`;
      }
      message += '. Refreshing data...';
      
      showNotification(message, 'info');
      
      // Check if this is a rollback of an "added" change (which means deleting the entity)
      const isAddedRollback = detail.action && detail.action.toLowerCase() === 'added';
      
      if (isAddedRollback) {
        // Entity was deleted by rollback - remove it from UI
        console.log('[Dashboard] 🗑️ Rollback of added change - removing entity from UI:', detail.entityIRI);
        
        // Clear selection if this was the selected item
        if (selectedItem?.id === detail.entityIRI) {
          setSelectedItem(null);
        }
        
        // Remove from class hierarchy
        if (entitiesTab === 'Classes' || detail.changeType?.toLowerCase().includes('class')) {
          setClassHierarchy(prevHierarchy => {
            const removeNodeFromTree = (nodes: TreeNode[]): TreeNode[] => {
              return nodes
                .filter(node => node.id !== detail.entityIRI)
                .map(node => ({
                  ...node,
                  children: node.children ? removeNodeFromTree(node.children) : []
                }));
            };
            return removeNodeFromTree(prevHierarchy);
          });
        }
        
        // Remove from properties lists
        if (detail.changeType?.toLowerCase().includes('objectproperty')) {
          setObjectProperties(prev => prev.filter(p => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes('dataproperty')) {
          setDataProperties(prev => prev.filter(p => p.id !== detail.entityIRI));
        } else if (detail.changeType?.toLowerCase().includes('annotationproperty')) {
          setAnnotationProperties(prev => prev.filter(p => p.id !== detail.entityIRI));
        }
        
        // Remove from individuals list
        if (detail.changeType?.toLowerCase().includes('individual')) {
          setIndividuals(prev => prev.filter(i => i.id !== detail.entityIRI));
        }
        
        return; // Don't try to fetch the deleted entity
      }
      
      // Refresh the data after rollback with longer delay to ensure GraphDB has processed
      setTimeout(() => {
        // If we have the entity IRI, refresh its details first
        if (detail?.entityIRI) {
          console.log('[Dashboard] 🔄 Refreshing entity details after rollback for:', detail.entityIRI);
          console.log('[Dashboard] 🔄 Entity type from event:', detail.entityType, 'Current tab:', entitiesTab);
          
          // Determine the correct API endpoint based on current tab first, then entity type
          // Annotation changes should use the entity's actual type (class, property, individual)
          const entityType = detail.entityType ? detail.entityType.toLowerCase() : '';
          let apiEndpoint = '';
          
          
          // For annotation changes, we need to refresh the entity that has the annotation
          // The entityIRI is the entity whose annotation was changed
          // Use entitiesTab as primary indicator since that's what the user is viewing
          if (entitiesTab === 'Classes' || entityType.includes('class') || entityType.includes('annotation')) {
            // For annotation changes on classes, fetch class details
            apiEndpoint = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === 'ObjectProperties' || entityType.includes('objectproperty') || entityType.includes('object_property')) {
            apiEndpoint = `/api/ontology/${projectId}/object-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === 'DataProperties' || entityType.includes('dataproperty') || entityType.includes('data_property')) {
            apiEndpoint = `/api/ontology/${projectId}/data-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === 'AnnotationProperties') {
            apiEndpoint = `/api/ontology/${projectId}/annotation-properties/${encodeURIComponent(detail.entityIRI)}`;
          } else if (entitiesTab === 'Individuals' || entityType.includes('individual')) {
            apiEndpoint = `/api/ontology/${projectId}/individuals/${encodeURIComponent(detail.entityIRI)}`;
          } else {
            // Fallback: try to determine by the entityIRI or default to class details
            console.log('[Dashboard] 🔄 No specific tab match, using current entitiesTab:', entitiesTab);
            // Default to class details as most common case
            apiEndpoint = `/api/ontology/classes/details/${projectId}?classIri=${encodeURIComponent(detail.entityIRI)}`;
          }
          
          if (apiEndpoint) {
            apiClient.get(apiEndpoint)
              .then(response => {
                const newData = response.data || response;
                if (!newData.id && newData.iri) {
                  newData.id = newData.iri;
                }
                console.log('[Dashboard] ✅ Refreshed entity after rollback:', newData);
                console.log('[Dashboard] 📝 Updated label:', newData.label);
                
                // Update the entity in the appropriate list (don't mark as unsaved - rollback is already in DB)
                updateItemInState(newData, false);
                
                // If this is the selected item, update it to show new values immediately
                if (selectedItem?.id === detail.entityIRI) {
                  setSelectedItem(newData);
                }
                
                // For annotation changes, just update the node in place without refreshing hierarchy
                // This prevents the tree from collapsing
                const isAnnotationChange = entityType.includes('annotation') || 
                                          (oldValue && newValue); // Has old/new values = annotation change
                
                if (isAnnotationChange && (entitiesTab === 'Classes' || entityType.includes('class'))) {
                  console.log('[Dashboard] 📝 Soft refresh: updating class node annotations in place');
                  // Update the class hierarchy node without reloading the tree
                  setClassHierarchy(prevHierarchy => {
                    const updateNodeInTree = (nodes: TreeNode[]): TreeNode[] => {
                      return nodes.map(node => {
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
                  if (entitiesTab === 'Classes' || entityType.includes('class')) {
                    refreshClassHierarchy();
                  } else if (entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties') {
                    refreshProperties();
                  } else if (entitiesTab === 'Individuals') {
                    // Refresh individuals list
                    fetchData();
                  }
                }
              })
              .catch(error => {
                console.error('[Dashboard] Failed to refresh entity after rollback:', error);
                // If specific entity fetch fails, try a full data refresh
                console.log('[Dashboard] Attempting full data refresh after rollback error');
                fetchData();
              });
          } else {
            // No specific endpoint, do a full refresh
            console.log('[Dashboard] No API endpoint matched, doing full refresh');
            fetchData();
          }
        }
      }, 1500); // Increased delay to ensure GraphDB fully processes the rollback
    };
    
    window.addEventListener('ontologyRollback', handleRollback as EventListener);
    console.log('[Dashboard] 🎧 Registered listener for rollback events');
    
    return () => {
      window.removeEventListener('ontologyRollback', handleRollback as EventListener);
    };
  }, [projectId, selectedItem, entitiesTab, refreshClassHierarchy, updateItemInState, showNotification, fetchData]);

  // Handle reconnection after WebSocket disconnect - refresh data to sync
  useEffect(() => {
    const handleReconnection = (event: Event) => {
      console.log('[Dashboard] 🔄 Collaboration reconnected, refreshing data...');
      if (projectId) {
        showNotification('Reconnected! Refreshing data...', 'info');
        // Give server a moment to be ready
        setTimeout(() => {
          fetchData(projectId, false);
        }, 500);
      }
    };
    
    window.addEventListener('collaborationReconnected', handleReconnection as EventListener);
    
    return () => {
      window.removeEventListener('collaborationReconnected', handleReconnection as EventListener);
    };
  }, [projectId, fetchData, showNotification]);

  useEffect(() => {
    // Initialize notification service to show toasts via collaboration context
    notificationService.onToast((options) => {
      collaboration.addNotification({
        type: options.type,
        message: `${options.title}: ${options.message}`,
        userId: 'system',
        username: 'System',
        userColor: '#6366f1',
        timestamp: Date.now()
      });
    });
    
    // Request notification permission for web browsers
    if (typeof window !== 'undefined' && !window.vscode) {
      notificationService.requestPermission();
    }
  }, [collaboration]);

  // Memoize the source data based on active tab to avoid unnecessary re-filtering
  const sourceData = React.useMemo(() => {
    switch (entitiesTab) {
      case "Classes": return classHierarchy;
      case "ObjectProperties": return objectPropertyHierarchy;
      case "DataProperties": return dataPropertyHierarchy;
      case "AnnotationProperties": return annotationProperties;
      case "Individuals": return individuals;
      case "Datatypes": return datatypes;
      default: return [];
    }
  }, [entitiesTab, classHierarchy, objectPropertyHierarchy, dataPropertyHierarchy, annotationProperties, individuals, datatypes]);

  // Filter data based on search query
  const filteredData = React.useMemo(() => {
    if (!searchQuery) return sourceData;

    const lowercasedQuery = searchQuery.toLowerCase();
    const filterRecursively = (items: SelectableItem[]): SelectableItem[] => {
      const results: SelectableItem[] = [];
      for (const item of items) {
        let matches = item.label?.toLowerCase().includes(lowercasedQuery);
        // Check for children in both TreeNode and our extended Property objects
        const children = (item as any).children;
        if (children) {
          const childResults = filterRecursively(children);
          if (childResults.length > 0) {
            results.push({ ...item, children: childResults } as any);
            matches = true;
          }
        }
        if (matches && !results.find(r => r.id === item.id)) {
          results.push(item);
        }
      }
      return results;
    };
    return filterRecursively(sourceData);
  }, [searchQuery, sourceData]);

  useEffect(() => {
    // Load previously installed plugins from localStorage
    const loadInstalledPlugins = async () => {
      try {
        pluginLoader.loadFromStorage();
        const installed = pluginLoader.getInstalledPlugins();
        
        // Update state with installed plugin IDs
        const pluginIds = installed.map(p => p.id);
        setInstalledPlugins(new Set(pluginIds));
        
        // Map plugin IDs to tab IDs and show tabs for installed plugins
        const pluginToTabMap: Record<string, string> = {
          'swrl-editor-plugin': 'SWRL',
          'graph-view-plugin': 'Graph',
          'fuzzy-ontology-plugin': 'Fuzzy',
          'change-assistant-plugin': 'Changes'
        };
        
        const tabsToShow = pluginIds
          .map(id => pluginToTabMap[id])
          .filter(Boolean);
        
        if (tabsToShow.length > 0) {
          setVisibleMainTabs(prev => {
            const newTabs = [...prev];
            tabsToShow.forEach(tab => {
              if (!newTabs.includes(tab)) {
                newTabs.push(tab);
              }
            });
            return newTabs;
          });
        }
        
        // Auto-load installed plugins
        for (const plugin of installed) {
          try {
            await pluginLoader.loadPlugin(plugin.id);
            console.log(`[Dashboard] Auto-loaded plugin: ${plugin.id}`);
          } catch (error) {
            console.warn(`[Dashboard] Failed to auto-load plugin ${plugin.id}:`, error);
          }
        }
      } catch (error) {
        console.error('[Dashboard] Failed to load installed plugins:', error);
      }
    };
    
    loadInstalledPlugins();
  }, [projectId]);

  // #endregion

  // #region Event Handlers


  const toggleNode = useCallback(async (nodeId: string) => {
    console.log('[DEBUG] toggleNode called for nodeId:', nodeId);
    if (expandedNodes.includes(nodeId)) {
      setExpandedNodes(prev => prev.filter(id => id !== nodeId));
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
      const node = findNode(classHierarchy, nodeId);
      
      setExpandedNodes(prev => [...prev, nodeId]);
      
      if (node && node.hasChildren && (!node.children || node.children.length === 0)) {
        console.log(`Node ${nodeId} needs children loaded`);
        await loadChildren(nodeId);
      }
    }
  }, [expandedNodes, classHierarchy, loadChildren]);

  // Expose a safe global for bundles/minified code paths that still reference toggleNode
  useEffect(() => {
    (window as any).toggleNode = toggleNode;
    return () => {
      if ((window as any).toggleNode === toggleNode) {
        delete (window as any).toggleNode;
      }
    };
  }, [toggleNode]);



  // Update draft count
  const updateDraftCount = useCallback(async () => {
    if (!projectId) return;
    try {
      console.log('[Dashboard] Updating draft count for project:', projectId);
      const stats = await draftTrackingService.getDraftStats(projectId);
      console.log('[Dashboard] Draft stats received:', stats);
      setDraftCount(stats.unappliedDrafts);
      setHasUnsavedChanges(stats.unappliedDrafts > 0);
    } catch (error) {
      console.error('[Dashboard] Failed to update draft count:', error);
      // Don't show error notification - just log it
      // The user can still work, we'll try again later
    }
  }, [projectId]);

  // Mark as unsaved (called after mutations)
  const markAsUnsaved = useCallback(() => {
    console.log('[DEBUG] markAsUnsaved called');
    setHasUnsavedChanges(true);
    // Update draft count after a short delay
    setTimeout(() => updateDraftCount(), 500);
  }, [updateDraftCount]);

  // Save changes to backend (applies drafts to GraphDB)
  const handleSave = useCallback(async () => {
    console.log('[DEBUG] handleSave called');
    if (!projectId || isSaving) return;

    try {
      setIsSaving(true);
      console.log('[Dashboard] 💾 Saving changes to backend...');
      
      // Notify sync service about local save to avoid triggering refresh for current user
      syncService.notifyLocalSave(projectId);
      
      // Save will apply all drafts to GraphDB and export
      const startTime = Date.now();
      const saveUrl = `/api/ontology/save/${projectId}?userId=${user?.id || 'anonymous'}&username=${encodeURIComponent(user?.username || 'Anonymous')}`;
      console.log('[Dashboard] 📤 Save URL:', saveUrl);
      const response = await apiClient.post(saveUrl);
      const duration = Date.now() - startTime;
      
      console.log(`[Dashboard] Save response received after ${duration}ms:`, response);
      
      // Handle both direct response and response.data (VS Code proxy vs direct HTTP)
      const data = response.data || response;
      
      if (data && data.success) {
        setHasUnsavedChanges(false);
        setDraftCount(0);
        
        console.log('[Dashboard] ✅ Changes saved to GraphDB database!');
        console.log('[Dashboard] 📊 Applied drafts:', data.appliedDrafts || 0);
        console.log('[Dashboard] 📝 History recorded in database');
        
        notificationService.success('Saved to Database', 
          `${data.appliedDrafts || 0} change${(data.appliedDrafts || 0) !== 1 ? 's' : ''} saved to GraphDB and history recorded.`);
        console.log('[Dashboard] Save complete:', data);
        
        // Refresh the current file to show saved changes
        console.log('[Dashboard] 🔄 Refreshing current file after save...');
        await fetchData(projectId, false);
        
        // Monitoring is automatically restarted by fetchData
        
        // Refresh collaboration panel to show recent changes
        collaborationPanelRef.current?.refreshChanges();
      } else {
        const errorMsg = (data && data.error) || 'Save failed - no response from server';
        console.error('[Dashboard] Save response was invalid:', response);
        throw new Error(errorMsg);
      }
    } catch (error) {
      console.error('[Dashboard] Save failed with error:', error);
      const errorMessage = error instanceof Error ? error.message : 'Could not save changes. Please try again.';
      notificationService.error('Save Failed', errorMessage);
    } finally {
      setIsSaving(false);
    }
  }, [projectId, isSaving, user?.id, user?.username]);

  // Switch to a different file (with unsaved changes check)
  const handleSwitchFile = useCallback((newProjectId: string) => {
    const switchFile = async () => {
      console.log('[Dashboard] 🔄 Switching to file:', newProjectId);
      console.log('[Dashboard] 🧹 Clearing current state for:', projectId);
      
      // Clear all current state
      setClassHierarchy([]);
      setObjectProperties([]);
      setDataProperties([]);
      setAnnotationProperties([]);
      setIndividuals([]);
      setSelectedItem(null);
      setSearchQuery('');
      
      if (window.vscode) {
        window.vscode.postMessage({
          type: "fileLoaded",
          projectId: newProjectId,
        });
      }
      setHasUnsavedChanges(false);
      setDraftCount(0);
      
      console.log('[Dashboard] ✅ State cleared, loading new file:', newProjectId);
    };

    // If no unsaved changes or draft count is 0, switch directly
    if (!hasUnsavedChanges || draftCount === 0) {
      console.log('[Dashboard] No unsaved changes, switching directly');
      switchFile();
      return;
    }

    // Show confirmation dialog only if there are actual unsaved changes
    setConfirmDialog({
      isOpen: true,
      title: 'Unsaved Changes',
      message: `You have ${draftCount} unsaved change${draftCount !== 1 ? 's' : ''} in "${projectId}". Do you want to save before switching?`,
      onConfirm: async () => {
        await handleSave();
        switchFile();
      },
      onCancel: async () => {
        // Discard drafts
        if (projectId) {
          try {
            await draftTrackingService.discardDrafts(projectId);
            console.log('[Dashboard] Discarded drafts');
          } catch (error) {
            console.error('[Dashboard] Failed to discard drafts:', error);
          }
        }
        switchFile();
      }
    });
  }, [hasUnsavedChanges, draftCount, projectId, handleSave]);

  // Create Property from Class Expression Dialog
  const handleCreatePropertyFromDialog = useCallback(() => {
    setEntitiesTab('ObjectProperties');
    setSelectedItem(null);
    setAddPropertyType('root');
    setPropertyParentLabel('owl:topObjectProperty');
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
            console.log('[Dashboard] Found draft, restoring...', draft);
            // Could show a dialog asking if user wants to restore draft
            setHasUnsavedChanges(true);
          } else {
            localStorage.removeItem(draftKey);
          }
        } catch (e) {
          console.error('[Dashboard] Failed to parse draft:', e);
          localStorage.removeItem(draftKey);
        }
      }
    }
  }, [projectId]);

  // Keyboard shortcut for Save (Ctrl+S)


  const handleAddAnnotation = useCallback(async () => {
    if (!selectedItem || !projectId) return;
    setAddAnnotationDialogOpen(true);
  }, [selectedItem, projectId]);

  const handleAnnotationDialogAdd = useCallback(async (propertyIri: string, value: string, datatype?: string) => {
    if (!selectedItem || !projectId) return;

    try {
      // Call backend API
      await ontologyMutationService.addAnnotation(projectId, selectedItem.id, propertyIri, value, user?.email || 'anonymous', user?.username || 'Anonymous');
      
      // Update local state
      const updatedAnnotations = { ...selectedItem.annotations, [propertyIri]: value };
      const updatedItem = { ...selectedItem, annotations: updatedAnnotations };
      updateItemInState(updatedItem);
      markAsUnsaved();
      showNotification('Annotation added successfully!', 'info');
    } catch (error) {
      console.error('Failed to add annotation:', error);
      showNotification('Failed to add annotation. See console for details.', 'error');
    }
  }, [selectedItem, updateItemInState, projectId]);

  const handleEditAnnotation = useCallback(async (propertyIri: string, currentValue: string) => {
    if (!selectedItem || !projectId) return;
    
    // Open dialog with current value pre-filled
    setEditAnnotationData({ propertyIri, currentValue, entityId: selectedItem.id });
    setEditAnnotationDialogOpen(true);
  }, [selectedItem, projectId]);

  const handleAnnotationDialogEdit = useCallback(async (propertyIri: string, oldValue: string, newValue: string) => {
    console.log('[Dashboard] handleAnnotationDialogEdit called with:', propertyIri, oldValue, newValue);
    if (!selectedItem || !projectId) return;

    try {
      // Update annotation atomically (single operation instead of delete + add)
      // Pass oldValue so change tracking can record both old and new values
      await ontologyMutationService.updateAnnotation(projectId, selectedItem.id, propertyIri, newValue, user?.email || 'anonymous', user?.username || 'Anonymous', oldValue);
      
      // Update local state
      const updatedAnnotations = { ...selectedItem.annotations, [propertyIri]: newValue };
      const updatedItem = { ...selectedItem, annotations: updatedAnnotations };
      updateItemInState(updatedItem);
      markAsUnsaved();
      showNotification('Annotation updated successfully!', 'info');
    } catch (error) {
      console.error('Failed to update annotation:', error);
      showNotification('Failed to update annotation. See console for details.', 'error');
    }
  }, [selectedItem, updateItemInState, projectId, user]);

  const handleDeleteAnnotation = useCallback(async (key: string) => {
    if (!selectedItem || !selectedItem.annotations || !projectId) return;
    
    // Show confirm dialog instead of using confirm()
    setConfirmDialog({
      isOpen: true,
      title: 'Delete Annotation',
      message: `Are you sure you want to delete the annotation "${key}"?`,
      onConfirm: async () => {
        try {
          const value = selectedItem.annotations[key];
          // Call backend API
          await ontologyMutationService.deleteAnnotation(projectId, selectedItem.id, key, value, user?.email || 'anonymous', user?.username || 'Anonymous');
          
          // Update local state
          const remainingAnnotations = { ...selectedItem.annotations };
          delete remainingAnnotations[key];
          const updatedItem = { ...selectedItem, annotations: remainingAnnotations };
          updateItemInState(updatedItem);
          markAsUnsaved();
          showNotification('Annotation deleted successfully!', 'info');
        } catch (error) {
          console.error('Failed to delete annotation:', error);
          showNotification('Failed to delete annotation. See console for details.', 'error');
        }
      }
    });
  }, [selectedItem, updateItemInState, projectId]);

  
  // Function to refresh only properties (not classes) to avoid closing dialogs
  const refreshProperties = useCallback(async () => {
    if (!projectId) return;
    
    try {
      console.log('[refreshProperties] Starting property refresh...');
      const propertiesRes = await apiClient.get<any>(`/api/ontology/properties/${projectId}`);
      
      const allProps = Array.isArray(propertiesRes?.data) 
        ? propertiesRes.data 
        : Array.isArray(propertiesRes?.properties) 
        ? propertiesRes.properties 
        : Array.isArray(propertiesRes) 
        ? propertiesRes 
        : [];
      
      console.log('[refreshProperties] Total properties fetched:', allProps.length);
      
      const opList = allProps.filter((p: any) => p.type === 'ObjectProperty');
      console.log('[refreshProperties] Object properties:', opList.length);
      setObjectProperties(opList);
       const objectRes = await apiClient.get(`/api/ontology/object-properties/${projectId}`);
    setObjectProperties(objectRes.data || []);
    const dataRes = await apiClient.get(`/api/ontology/data-properties/${projectId}`);
    setDataProperties(dataRes.data || []);
    console.log('[Dashboard] ✅ Properties refreshed');
      // Build object property hierarchy
      const opMap = new Map<string, TreeNode>();
      opList.forEach((p: any) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });
      
      const topOpNode: TreeNode = {
        id: 'http://www.w3.org/2002/07/owl#topObjectProperty',
        label: 'owl:topObjectProperty',
        type: 'ObjectProperty',
        children: [],
        hasChildren: false,
        annotations: {}
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
      
      console.log('[refreshProperties] Built object property hierarchy with', topOpNode.children?.length, 'top-level properties');
      // Create a new array to ensure React detects the change
      setObjectPropertyHierarchy([{ ...topOpNode, children: [...(topOpNode.children || [])] }]);
      
      // Build data property hierarchy
      const dpList = allProps.filter((p: any) => p.type === 'DatatypeProperty');
      console.log('[refreshProperties] Data properties:', dpList.length);
      setDataProperties(dpList);
      
      const dpMap = new Map<string, TreeNode>();
      dpList.forEach((p: any) => {
        dpMap.set(p.id, { ...p, children: [], hasChildren: false });
      });
      
      const topDpNode: TreeNode = {
        id: 'http://www.w3.org/2002/07/owl#topDataProperty',
        label: 'owl:topDataProperty',
        type: 'DatatypeProperty',
        children: [],
        hasChildren: false,
        annotations: {}
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
      
      console.log('[refreshProperties] Built data property hierarchy with', topDpNode.children?.length, 'top-level properties');
      // Create a new array to ensure React detects the change
      setDataPropertyHierarchy([{ ...topDpNode, children: [...(topDpNode.children || [])] }]);
      console.log('[refreshProperties] Property refresh complete');
    } catch (error) {
      console.error('Failed to refresh properties:', error);
    }
  }, [projectId]);

  // Handler for creating object properties with name parameter
  const handleAddObjectProperty = useCallback(async (
    type: 'subclass' | 'sibling',
    parentId?: string,
    name?: string
  ) => {
    if (!projectId) return;

    try {
      console.log('[handleAddObjectProperty] Creating property:', name, 'type:', type, 'parentId:', parentId);
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const cleanName = (name || 'NewObjectProperty').replace(/\s+/g, '_');
      const newIri = `${baseIri}${baseIri.endsWith('#') || baseIri.endsWith('/') ? '' : '#'}${cleanName}`;

      let parentIri = 'http://www.w3.org/2002/07/owl#topObjectProperty';
      
      if (parentId) {
        if (type === 'subclass') {
          parentIri = parentId;
        } else if (type === 'sibling') {
          const parent = findParentNode(objectPropertyHierarchy, parentId);
          if (parent) parentIri = parent.id;
        }
      }

      console.log('[handleAddObjectProperty] Creating with IRI:', newIri, 'parent:', parentIri);
      await ontologyMutationService.createObjectProperty(
        projectId,
        newIri,
        name || 'NewObjectProperty',
        parentIri,
        user?.email || 'anonymous',
        user?.username || 'Anonymous'
      );

      console.log('[handleAddObjectProperty] Property created, refreshing...');
      // Add a small delay to ensure backend has processed the property
      await new Promise(resolve => setTimeout(resolve, 300));
      await refreshProperties();
      console.log('[handleAddObjectProperty] Refresh complete');
      showNotification(`Object property "${name}" created successfully!`);
    } catch (error) {
      console.error('Failed to create object property:', error);
      showNotification('Failed to create object property. See console for details.', 'error');
      throw error;
    }
  }, [projectId, metadata, objectPropertyHierarchy, user, refreshProperties, showNotification]);


  // Handler for creating data properties with name parameter
  const handleAddDataProperty = useCallback(async (
    type: 'subclass' | 'sibling',
    parentId?: string,
    name?: string
  ) => {
    if (!projectId) return;

    try {
      console.log('[handleAddDataProperty] Creating property:', name, 'type:', type, 'parentId:', parentId);
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const cleanName = (name || 'NewDataProperty').replace(/\s+/g, '_');
      const newIri = `${baseIri}${baseIri.endsWith('#') || baseIri.endsWith('/') ? '' : '#'}${cleanName}`;

      let parentIri = 'http://www.w3.org/2002/07/owl#topDataProperty';
      
      if (parentId) {
        if (type === 'subclass') {
          parentIri = parentId;
        } else if (type === 'sibling') {
          const parent = findParentNode(dataPropertyHierarchy, parentId);
          if (parent) parentIri = parent.id;
        }
      }

      console.log('[handleAddDataProperty] Creating with IRI:', newIri, 'parent:', parentIri);
      await ontologyMutationService.createDataProperty(
        projectId,
        newIri,
        name || 'NewDataProperty',
        parentIri,
        user?.email || 'anonymous',
        user?.username || 'Anonymous'
      );

      console.log('[handleAddDataProperty] Property created, refreshing...');
      // Add a small delay to ensure backend has processed the property
      await new Promise(resolve => setTimeout(resolve, 300));
      await refreshProperties();
      console.log('[handleAddDataProperty] Refresh complete');
      showNotification(`Data property "${name}" created successfully!`);
    } catch (error) {
      console.error('Failed to create data property:', error);
      showNotification('Failed to create data property. See console for details.', 'error');
      throw error;
    }
  }, [projectId, metadata, dataPropertyHierarchy, user, refreshProperties, showNotification]);

  // Handler for creating classes with name parameter (for inline creation in dialogs)
  const handleAddClassInline = useCallback(async (
    type: 'subclass' | 'sibling',
    parentId?: string,
    name?: string
  ) => {
    if (!projectId) return;

    try {
      console.log('[handleAddClassInline] Creating class:', name, 'type:', type, 'parentId:', parentId);
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const cleanName = (name || 'NewClass').replace(/\s+/g, '_');
      const newIri = `${baseIri}${baseIri.endsWith('#') || baseIri.endsWith('/') ? '' : '#'}${cleanName}`;

      let parentIri = 'http://www.w3.org/2002/07/owl#Thing';
      
      if (parentId) {
        if (type === 'subclass') {
          parentIri = parentId;
        } else if (type === 'sibling') {
          const parent = findParentNode(classHierarchy, parentId);
          if (parent) parentIri = parent.id;
        }
      }

      console.log('[handleAddClassInline] Creating with IRI:', newIri, 'parent:', parentIri);
      await ontologyMutationService.createClass(
        projectId,
        newIri,
        name || 'NewClass',
        parentIri,
        user?.email || 'anonymous',
        user?.username || 'Anonymous'
      );

      console.log('[handleAddClassInline] Class created, refreshing...');
      // Add a small delay to ensure backend has processed the class
      await new Promise(resolve => setTimeout(resolve, 300));
      
      // Ensure parent node is in expanded nodes before refresh
      if (parentIri && !expandedNodes.includes(parentIri)) {
        setExpandedNodes(prev => [...prev, parentIri]);
      }
      
      await refreshClassHierarchy();
      
      // Re-expand the parent node after refresh to ensure it stays open
      if (parentIri && !expandedNodes.includes(parentIri)) {
        setExpandedNodes(prev => [...prev, parentIri]);
      }
      
      console.log('[handleAddClassInline] Refresh complete');
      showNotification(`Class "${name}" created successfully!`);
    } catch (error) {
      console.error('Failed to create class:', error);
      showNotification('Failed to create class. See console for details.', 'error');
      throw error;
    }
  }, [projectId, metadata, classHierarchy, user, refreshClassHierarchy, showNotification, expandedNodes]);

  const handleAddItem = useCallback(async (type: 'subclass' | 'sibling' | 'individual') => {
    if (!projectId) return;
    
    if (type === 'individual') {
      setCreateIndividualModalOpen(true);
      return;
    }

    if (entitiesTab === 'ObjectProperties') {
      if (!selectedItem) {
        showNotification('Select an object property first.', 'warning');
        return;
      }

      // Prevent creating sibling of top-level object property
      if (type === 'sibling') {
        const parent = findParentNode(objectPropertyHierarchy, selectedItem.id);
        const isTopLevel = !parent ||
                          selectedItem.id.includes('topObjectProperty') ||
                          selectedItem.label === 'owl:topObjectProperty';

        if (isTopLevel) {
          showNotification('Cannot create sibling of top-level object property. Please create a subproperty instead.', 'warning');
          return;
        }
      }

      const parentLabel = type === 'subclass'
        ? selectedItem.label
        : (findParentNode(objectPropertyHierarchy, selectedItem.id)?.label || 'owl:topObjectProperty');

      setAddPropertyType(type === 'subclass' ? 'subproperty' : 'sibling');
      setPropertyParentLabel(parentLabel);
      setAddPropertyDialogOpen(true);
      return;
    }

    if (entitiesTab === 'DataProperties') {
      if (!selectedItem) {
        showNotification('Select a data property first.', 'warning');
        return;
      }

      // Prevent creating sibling of top-level data property
      if (type === 'sibling') {
        const parent = findParentNode(dataPropertyHierarchy, selectedItem.id);
        const isTopLevel = !parent ||
                          selectedItem.id.includes('topDataProperty') ||
                          selectedItem.label === 'owl:topDataProperty';

        if (isTopLevel) {
          showNotification('Cannot create sibling of top-level data property. Please create a subproperty instead.', 'warning');
          return;
        }
      }

      const parentLabel = type === 'subclass'
        ? selectedItem.label
        : (findParentNode(dataPropertyHierarchy, selectedItem.id)?.label || 'owl:topDataProperty');

      setAddPropertyType(type === 'subclass' ? 'subproperty' : 'sibling');
      setPropertyParentLabel(parentLabel);
      setAddPropertyDialogOpen(true);
      return;
    }

    if (entitiesTab === 'AnnotationProperties') {
      setAddPropertyType('root');
      setPropertyParentLabel('Annotation Property');
      setAddPropertyDialogOpen(true);
      return;
    }

    if (entitiesTab === 'Datatypes') {
      setAddDatatypeDialogOpen(true);
      return;
    }

    if ((type === 'subclass' || type === 'sibling') && !selectedItem) {
      showNotification("Please select a class first.", 'warning');
      return;
    }

    if (entitiesTab !== 'Classes') {
      showNotification('This action is available only for classes right now.', 'warning');
      return;
    }

    // Prevent creating sibling of top-level class
    if (type === 'sibling') {
      const parent = findParentNode(classHierarchy, selectedItem.id);
      const isTopLevel = !parent ||
                        selectedItem.id.includes('Thing') ||
                        selectedItem.label === 'owl:Thing';

      if (isTopLevel) {
        showNotification('Cannot create sibling of owl:Thing. Please create a subclass instead.', 'warning');
        return;
      }
    }

    // For parent label, we'll compute it from state accessor
    let parentLabel = selectedItem.label;
    if (type === 'sibling') {
      // Use functional update to get parent label without dependency
      setClassHierarchy(currentHierarchy => {
        const parent = findParentNode(currentHierarchy, selectedItem.id);
        parentLabel = parent?.label || 'owl:Thing';
        return currentHierarchy; // No change
      });
    }

    setAddClassType(type);
    setClassParentLabel(parentLabel);
    setAddClassDialogOpen(true);
  }, [projectId, entitiesTab, selectedItem, showNotification]);

  const handleCreateClass = useCallback(async (name: string) => {
    if (!projectId || !selectedItem) return;

    const type = addClassType;

    try {
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const newIri = `${baseIri}#${name.replace(/\s+/g, '_')}`;
      
      // Determine parent IRI based on type
      let parentIri = 'http://www.w3.org/2002/07/owl#Thing';
      
      if (entitiesTab === 'Classes') {
          if (type === 'subclass' && selectedItem?.id) {
            parentIri = selectedItem.id;
          } else if (type === 'sibling' && selectedItem?.id) {
            // Use functional update to find parent without dependency
            let foundParentIri = 'http://www.w3.org/2002/07/owl#Thing';
            setClassHierarchy(currentHierarchy => {
              const findParent = (nodes: TreeNode[], targetId: string, parent: TreeNode | null = null): TreeNode | null => {
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
              foundParentIri = parent?.id || 'http://www.w3.org/2002/07/owl#Thing';
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
            user?.email || 'anonymous',
            user?.username || 'Anonymous'
          );

          // Update local state
          const newNode: TreeNode = {
            id: newIri,
            label: name,
            children: undefined,
            hasChildren: false,
            annotations: { 'rdfs:label': name }
          };

          setExpandedNodes(prev => {
            if (type === 'subclass' && selectedItem?.id && !prev.includes(selectedItem.id)) {
              return [...prev, selectedItem.id];
            }
            return prev;
          });

          setClassHierarchy(prev => {
            const addNodeRecursively = (nodes: TreeNode[]): TreeNode[] => {
              return nodes.map(node => {
                if (type === 'subclass' && node.id === selectedItem?.id) {
                  const children = node.children ? [...node.children, newNode] : [newNode];
                  return { ...node, children, hasChildren: true };
                }
                if (type === 'sibling' && node.children?.some((child: TreeNode) => child.id === selectedItem?.id)) {
                  return { ...node, children: [...(node.children || []), newNode] };
                }
                if (node.children) {
                  return { ...node, children: addNodeRecursively(node.children) };
                }
                return node;
              });
            };

            // If adding sibling at root level
            if (type === 'sibling' && prev.some(node => node.id === selectedItem.id)) {
              return [...prev, newNode];
            } else {
              return addNodeRecursively(prev);
            }
          });
          markAsUnsaved();
      } else if (entitiesTab === 'ObjectProperties') {
          // Handle Object Property Creation
          parentIri = 'http://www.w3.org/2002/07/owl#topObjectProperty';
          if (type === 'subclass' && selectedItem?.id) {
              parentIri = selectedItem.id;
          } else if (type === 'sibling' && selectedItem?.id) {
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
              parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#topObjectProperty';
          }
          
          await ontologyMutationService.createObjectProperty(projectId, newIri, name, parentIri, user?.email || 'anonymous', user?.username || 'Anonymous');
          
          const newProp: Property & TreeNode = {
              id: newIri,
              label: name,
              type: 'ObjectProperty' as const,
              annotations: { 'rdfs:label': name },
              children: [],
              hasChildren: false
          };
          
          setObjectProperties(prev => [...prev, newProp]);

          // Update Hierarchy
          const addNodeRecursively = (nodes: any[]): any[] => {
            return nodes.map(node => {
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
          
          setObjectPropertyHierarchy(prev => addNodeRecursively(prev));
          
          if (parentIri && !expandedNodes.includes(parentIri)) {
             setExpandedNodes(prev => [...prev, parentIri]);
          }
      }

      showNotification(`${entitiesTab === 'Classes' ? 'Class' : 'Property'} created successfully!`, 'info');
      setAddClassDialogOpen(false);
    } catch (error) {
      console.error('Failed to create entity:', error);
      showNotification('Failed to create entity. See console for details.', 'error');
    }
  }, [projectId, selectedItem, addClassType, entitiesTab, metadata, markAsUnsaved]);

  const handleCreateObjectProperty = useCallback(async (name: string) => {
    if (!projectId) return;

    const type = addPropertyType;

    try {
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const newIri = `${baseIri}#${name.replace(/\s+/g, '_')}`;
      
      let parentIri = 'http://www.w3.org/2002/07/owl#topObjectProperty';
        if (type === 'subproperty' && selectedItem?.id) {
          parentIri = selectedItem.id;
        } else if (type === 'sibling' && selectedItem?.id) {
          const parent = findParentNode(objectPropertyHierarchy, selectedItem.id);
          parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#topObjectProperty';
        }
      
      await ontologyMutationService.createObjectProperty(projectId, newIri, name, parentIri);
      
      const newProp: Property & TreeNode = {
          id: newIri,
          label: name,
          type: 'ObjectProperty',
          annotations: { 'rdfs:label': name },
          children: [],
          hasChildren: false
      };
      
      setObjectProperties(prev => [...prev, newProp]);

      const addNodeRecursively = (nodes: any[]): any[] => {
        return nodes.map(node => {
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
      
      setObjectPropertyHierarchy(prev => addNodeRecursively(prev));
      
      if (parentIri && !expandedNodes.includes(parentIri)) {
         setExpandedNodes(prev => [...prev, parentIri]);
      }

      markAsUnsaved();
      showNotification('Property created successfully!', 'info');
      setAddPropertyDialogOpen(false);
      setPropertyParentLabel('owl:topObjectProperty');
    } catch (error) {
      console.error('Failed to create property:', error);
      showNotification('Failed to create property. See console for details.', 'error');
    }
  }, [projectId, selectedItem, addPropertyType, objectPropertyHierarchy, expandedNodes, metadata, markAsUnsaved]);

  const handleCreateDataProperty = useCallback(async (name: string) => {
    if (!projectId) return;

    const type = addPropertyType;

    try {
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const newIri = `${baseIri}#${name.replace(/\s+/g, '_')}`;
      
      let parentIri = 'http://www.w3.org/2002/07/owl#topDataProperty';
        if (type === 'subproperty' && selectedItem?.id) {
          parentIri = selectedItem.id;
        } else if (type === 'sibling' && selectedItem?.id) {
          const parent = findParentNode(dataPropertyHierarchy, selectedItem.id);
          parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#topDataProperty';
        }
      
      await ontologyMutationService.createDataProperty(projectId, newIri, name, parentIri);
      
      const newProp: Property & TreeNode = {
          id: newIri,
          label: name,
          type: 'DatatypeProperty' as const,
          annotations: { 'rdfs:label': name },
          children: [],
          hasChildren: false
      };
      
      setDataProperties(prev => [...prev, newProp]);

      const addNodeRecursively = (nodes: any[]): any[] => {
        return nodes.map(node => {
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
      
      setDataPropertyHierarchy(prev => addNodeRecursively(prev));
      
      if (parentIri && !expandedNodes.includes(parentIri)) {
         setExpandedNodes(prev => [...prev, parentIri]);
      }

      markAsUnsaved();
      showNotification('Data property created successfully!', 'info');
      setAddPropertyDialogOpen(false);
      setPropertyParentLabel('owl:topDataProperty');
    } catch (error) {
      console.error('Failed to create data property:', error);
      showNotification('Failed to create data property. See console for details.', 'error');
    }
  }, [projectId, selectedItem, addPropertyType, dataPropertyHierarchy, expandedNodes, metadata, markAsUnsaved]);

  const handleCreateDatatype = useCallback(async (name: string) => {
    if (!projectId) return;

    try {
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const newIri = `${baseIri}#${name.replace(/\s+/g, '_')}`;

      await ontologyMutationService.createDatatype(projectId, newIri, name, user?.email || 'anonymous', user?.username || 'Anonymous');

      const newDatatype: Datatype = {
        id: newIri,
        label: name,
        annotations: { 'rdfs:label': name }
      };

      setDatatypes(prev => [...prev, newDatatype]);

      markAsUnsaved();
      showNotification('Datatype created successfully!', 'info');
      setAddDatatypeDialogOpen(false);
    } catch (error) {
      console.error('Failed to create datatype:', error);
      showNotification('Failed to create datatype. See console for details.', 'error');
    }
  }, [projectId, metadata, markAsUnsaved, showNotification]);

  const handleCreateAnnotationProperty = useCallback(async (name: string) => {
    if (!projectId) return;

    try {
      const baseIri = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
      const newIri = `${baseIri}#${name.replace(/\s+/g, '_')}`;

      await ontologyMutationService.createAnnotationProperty(projectId, newIri, name);

      const newProp: AnnotationProperty = {
        id: newIri,
        label: name,
        annotations: { 'rdfs:label': name }
      };

      setAnnotationProperties(prev => [...prev, newProp]);

      markAsUnsaved();
      showNotification('Annotation property created successfully!', 'info');
      setAddPropertyDialogOpen(false);
    } catch (error) {
      console.error('Failed to create annotation property:', error);
      showNotification('Failed to create annotation property. See console for details.', 'error');
    }
  }, [projectId, metadata, markAsUnsaved, showNotification]);

  const handleAddIndividual = useCallback(async (name: string) => {
    if (!projectId) {
      showNotification('No project loaded.', 'error');
      return;
    }
    
    const base = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
    const id = `${base}#${name.replace(/\s+/g, '_')}`;
    
    // Determine the class IRI - use selected class if available, otherwise owl:Thing
    const classIri = (entitiesTab === 'Classes' && selectedItem?.id) 
      ? selectedItem.id 
      : 'http://www.w3.org/2002/07/owl#Thing';
    
    try {
      // Call the mutation service to persist the individual
      await ontologyMutationService.createIndividual(projectId, id, name, classIri);
      
      // Update local state
      const newIndividual: Individual = {
        id,
        iri: id,
        label: name,
        annotations: { 'rdfs:label': name },
        types: [classIri]
      };
      setIndividuals(prev => [...prev, newIndividual]);
      
      markAsUnsaved();
      showNotification(`Individual "${name}" created successfully!`, 'info');
    } catch (error) {
      console.error('Failed to create individual:', error);
      showNotification('Failed to create individual. See console for details.', 'error');
    }
  }, [projectId, metadata, entitiesTab, selectedItem, markAsUnsaved, showNotification]);

  const handleMakeSiblingsDisjoint = useCallback(async () => {
    console.log('[DEBUG] handleMakeSiblingsDisjoint called');
    if (!projectId || !selectedItem || entitiesTab !== 'Classes') return;

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

    const siblings = findSiblings(classHierarchy, selectedItem.id);

    if (siblings.length === 0) {
      showNotification('No siblings found for the selected class.', 'info');
      return;
    }

    // Show confirmation dialog
    setConfirmDialog({
      isOpen: true,
      title: 'Make Siblings Disjoint',
      message: `This will make ${siblings.length + 1} sibling classes pairwise disjoint. Continue?`,
      onConfirm: async () => {
        try {
          // Include the selected class itself in the disjoint set
          const allClasses = [selectedItem as TreeNode, ...siblings];
          const classIds = allClasses.map(c => c.id);

          // Call backend to create pairwise disjoint axioms
          await ontologyMutationService.makeSiblingsDisjoint(projectId, classIds, user?.email || 'anonymous', user?.username || 'Anonymous');

          showNotification(`Successfully made ${classIds.length} classes pairwise disjoint.`, 'info');

          // Optionally refresh the selected item to show updated axioms
          if (selectedItem) {
            const updated = { ...selectedItem, disjointClassesAxioms: [] };
            updateItemInState(updated);
          }
        } catch (error) {
          console.error('Failed to make siblings disjoint:', error);
          showNotification('Failed to make siblings disjoint. See console for details.', 'error');
        }
      }
    });
  }, [projectId, selectedItem, entitiesTab, classHierarchy, updateItemInState]);

  const handleDeleteItem = useCallback(async (itemOverride?: SelectableItem, tabOverride?: typeof entitiesTab) => {
    const item = itemOverride || selectedItem;
    const activeTab = tabOverride || entitiesTab;
    if (!item || !projectId) return;
    
    // Validate item has a valid IRI
    if (!item.id) {
      console.error('[DELETE] Item has no IRI:', item);
      showNotification('Cannot delete: item has no valid IRI', 'error');
      return;
    }
    
    // Show confirm dialog instead of using confirm()
    setConfirmDialog({
      isOpen: true,
      title: 'Delete Item',
      message: `Are you sure you want to delete "${item.label}"? This action cannot be undone.`,
      onConfirm: async () => {
        try {
          console.log('[DELETE] Deleting item:', { id: item.id, label: item.label, tab: activeTab });
          
          // Call backend API based on entity type
          switch (activeTab) {
            case 'Classes':
              await ontologyMutationService.deleteClass(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
            case 'Individuals':
              await ontologyMutationService.deleteIndividual(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
            case 'ObjectProperties':
              await ontologyMutationService.deleteObjectProperty(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
            case 'DataProperties':
              await ontologyMutationService.deleteDataProperty(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
            case 'AnnotationProperties':
              await ontologyMutationService.deleteAnnotationProperty(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
            case 'Datatypes':
              await ontologyMutationService.deleteDatatype(projectId, item.id, user?.email || 'anonymous', user?.username || 'Anonymous');
              break;
          }

          // Update local state
          switch (activeTab) {
            case 'Classes': {
              const removeNodeRecursively = (nodes: TreeNode[], id: string): TreeNode[] =>
                nodes
                  .filter(node => node.id !== id)
                  .map(node => node.children ? { ...node, children: removeNodeRecursively(node.children, id) } : node);
              setClassHierarchy(prev => removeNodeRecursively(prev, item.id));
              break;
            }
            case 'Individuals':
              setIndividuals(prev => prev.filter(ind => ind.id !== item.id));
              break;
            case 'ObjectProperties': {
              setObjectProperties(prev => prev.filter(p => p.id !== item.id));
              const removeOpRecursively = (nodes: any[], id: string): any[] =>
                nodes
                  .filter(node => node.id !== id)
                  .map(node => node.children ? { ...node, children: removeOpRecursively(node.children, id) } : node);
              setObjectPropertyHierarchy(prev => removeOpRecursively(prev, item.id));
              break;
            }
            case 'DataProperties': {
              setDataProperties(prev => prev.filter(p => p.id !== item.id));
              const removeDpRecursively = (nodes: any[], id: string): any[] =>
                nodes
                  .filter(node => node.id !== id)
                  .map(node => node.children ? { ...node, children: removeDpRecursively(node.children, id) } : node);
              setDataPropertyHierarchy(prev => removeDpRecursively(prev, item.id));
              break;
            }
            case 'AnnotationProperties':
              setAnnotationProperties(prev => prev.filter(p => p.id !== item.id));
              break;
            case 'Datatypes':
              setDatatypes(prev => prev.filter(d => d.id !== item.id));
              break;
          }
          setSelectedItem(prev => (prev?.id === item.id ? null : prev));
          showNotification(`"${item.label}" deleted successfully!`, 'info');
        } catch (error) {
          console.error('Failed to delete item:', error);
          showNotification('Failed to delete item. See console for details.', 'error');
        }
      }
    });
  }, [selectedItem, entitiesTab, projectId]);

  const handleRenameItem = useCallback(async (itemId: string, newLabel: string) => {
    console.log('[DEBUG] handleRenameItem called for itemId:', itemId, 'newLabel:', newLabel);
    if (!projectId || !newLabel.trim()) return;

    try {
      // Update the label via backend
      // We'll use the itemId directly rather than searching for the item
      // The backend knows the entity type from the IRI

      // Try to update via class label endpoint first (works for classes)
      try {
        await ontologyMutationService.updateClassLabel(projectId, itemId, newLabel, user?.email || 'anonymous', user?.username || 'Anonymous');
      } catch (classError) {
        // If class update fails, try annotation-based update (for other entity types)
        // Note: We need to get the current label - we'll use selectedItem if it matches
        const currentLabel = selectedItem?.id === itemId ? selectedItem.label : 'Unknown';
        await ontologyMutationService.deleteAnnotation(projectId, itemId, 'http://www.w3.org/2000/01/rdf-schema#label', currentLabel, user?.email || 'anonymous', user?.username || 'Anonymous');
        await ontologyMutationService.addAnnotation(projectId, itemId, 'http://www.w3.org/2000/01/rdf-schema#label', newLabel, user?.email || 'anonymous', user?.username || 'Anonymous');
      }

      // Update local state by creating a minimal updated item
      const updatedItem = {
        ...(selectedItem || { id: itemId, label: newLabel }),
        label: newLabel
      } as SelectableItem;
      updateItemInState(updatedItem);

      showNotification(`Renamed to "${newLabel}"`, 'info');
    } catch (error) {
      console.error('Failed to rename item:', error);
      showNotification('Failed to rename item. See console for details.', 'error');
    }
  }, [projectId, selectedItem, updateItemInState]);

  const handleGraphNodeClick = useCallback((nodeId: string) => {
    console.log('[DEBUG] handleGraphNodeClick called for nodeId:', nodeId);
    const flatten = (nodes: TreeNode[]): TreeNode[] =>
      nodes.flatMap(n => [n, ...(n.children ? flatten(n.children) : [])]);

    const allItems: SelectableItem[] = [
      ...flatten(classHierarchy),
      ...individuals,
    ];
    const item = allItems.find((i: SelectableItem) => i.id === nodeId);
    if (item) {
      let tab = 'Classes';
      if ('types' in item) tab = 'Individuals';

      setEntitiesTab(tab);
      setSelectedItem(item);
      setMainTab('Entities');
    }
  }, [classHierarchy, individuals]);

  const findClassNodeById = useCallback((targetId: string): TreeNode | null => {
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
  }, [classHierarchy]);

  useEffect(() => {
    const handleGraphAddClass = (event: Event) => {
      const custom = event as CustomEvent<{
        action: 'subclass' | 'sibling';
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

      const targetNode = findClassNodeById(detail.targetNodeId);
      if (!targetNode) {
        showNotification('Selected class not found in hierarchy. Please refresh the graph and try again.', 'warning');
        return;
      }

      setMainTab('Entities');
      setEntitiesTab('Classes');
      setSelectedItem(targetNode);

      if (detail.action === 'sibling') {
        const parent = detail.parentId
          ? findClassNodeById(detail.parentId)
          : findParentNode(classHierarchy, targetNode.id);
        setClassParentLabel(parent?.label || detail.parentLabel || 'owl:Thing');
        setAddClassType('sibling');
      } else {
        setClassParentLabel(targetNode.label);
        setAddClassType('subclass');
      }

      setAddClassDialogOpen(true);
    };

    window.addEventListener('graph-view:add-class', handleGraphAddClass as EventListener);
    return () => window.removeEventListener('graph-view:add-class', handleGraphAddClass as EventListener);
  }, [classHierarchy, findClassNodeById, projectId, showNotification]);

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
        showNotification(`Class "${detail.nodeLabel || detail.nodeId}" not found in hierarchy.`, 'warning');
        return;
      }

      setMainTab('Entities');
      setEntitiesTab('Classes');
      setSelectedItem(targetNode);
      handleDeleteItem(targetNode, 'Classes');
    };

    window.addEventListener('graph-view:delete-class', handleGraphDelete as EventListener);
    return () => window.removeEventListener('graph-view:delete-class', handleGraphDelete as EventListener);
  }, [findClassNodeById, handleDeleteItem, projectId, showNotification]);

  useEffect(() => {
    const handleShowCollaboration = (event: Event) => {
      const custom = event as CustomEvent<{ projectId?: string }>;
      const detail = custom.detail;
      if (detail?.projectId && projectId && detail.projectId !== projectId) {
        return;
      }
      setShowCollaborationPanel(true);
    };

    window.addEventListener('graph-view:show-collaboration', handleShowCollaboration as EventListener);
    return () => window.removeEventListener('graph-view:show-collaboration', handleShowCollaboration as EventListener);
  }, [projectId]);

  // Keyboard shortcuts (Protégé-style) - must be after handleAddItem and handleDeleteItem
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Ignore if user is typing in input/textarea
      const target = e.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) {
        return;
      }

      // Only handle shortcuts when Entities tab is active
      if (mainTab !== 'Entities') return;

      // F2 - Rename (works for all entity types)
      if (e.key === 'F2' && selectedItem) {
        e.preventDefault();
        // Trigger rename by posting message to EntityHierarchy
        // We'll use a custom event since we can't directly access EntityHierarchy's state
        const renameEvent = new CustomEvent('triggerRename', { detail: { itemId: selectedItem.id } });
        window.dispatchEvent(renameEvent);
        return;
      }

      // Other shortcuts only for Classes tab
      if (entitiesTab !== 'Classes') return;

      // Ctrl+\ or Cmd+\ - Add Subclass
      if ((e.ctrlKey || e.metaKey) && e.key === '\\') {
        e.preventDefault();
        handleAddItem('subclass');
      }
      // Ctrl+/ or Cmd+/ - Add Sibling
      else if ((e.ctrlKey || e.metaKey) && e.key === '/') {
        e.preventDefault();
        handleAddItem('sibling');
      }
      // Ctrl+Backspace or Cmd+Backspace - Delete
      else if ((e.ctrlKey || e.metaKey) && e.key === 'Backspace') {
        e.preventDefault();
        handleDeleteItem();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [mainTab, entitiesTab, handleAddItem, handleDeleteItem, selectedItem]);

  const handleExecuteDlQuery = () => {
    setIsDlQueryLoading(true);
    setDlQueryResults(null);
    setTimeout(() => {
      if (dlQuery.toLowerCase().includes('pizza')) {
        const pizzaResults = individuals.filter(i => i.label.toLowerCase().includes('pizza')).map(i => i.label);
        setDlQueryResults(pizzaResults.length > 0 ? pizzaResults : ['MargheritaPizza', 'AmericanHotPizza', 'SohoPizza']);
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
      await apiClient.post(`/api/ontology/${projectId}/dl/add`, { expression: dlQuery });
      console.log("DL expression submitted to backend.");
    } catch (e) {
      // Keep app stable even if the endpoint doesn't exist.
      console.warn("DL add endpoint not available; skipping.");
    }
  }, [projectId, dlQuery]);
  // #endregion

  // #region Render Methods
  const fetchCodeViewContent = useCallback(async (format: 'turtle' | 'rdfxml' | 'ntriples' | 'owl') => {
    if (!projectId) return;
    setCodeViewLoading(true);
    try {
      const response = await apiClient.get<{ success: boolean; content: string; format: string }>(
        `/api/ontology/${projectId}/content`,
        { format }
      );
      if (response.success) {
        setCodeViewContent(response.content);
        setCodeViewFormat(format);
      }
    } catch (error) {
      console.error('Failed to fetch code view content:', error);
      setCodeViewContent('// Error loading ontology content');
    } finally {
      setCodeViewLoading(false);
    }
  }, [projectId]);

  // Cleanup sync service when switching projects
  useEffect(() => {
    return () => {
      if (projectId) {
        syncService.stopMonitoring(projectId);
        console.log('[Dashboard] Stopped monitoring for project:', projectId);
      }
    };
  }, [projectId]);

  // Load code view content when switching to CodeView tab
  useEffect(() => {
    if (mainTab === 'CodeView' && projectId && !codeViewContent) {
      fetchCodeViewContent(codeViewFormat);
    }
  }, [mainTab, projectId, codeViewContent, codeViewFormat, fetchCodeViewContent]);

  const renderMainContent = () => {
    switch (mainTab) {
      case 'CodeView':
        return (
          <div className="flex h-full bg-gray-100">
            <div className="flex-1 flex flex-col bg-white">
              <div className="p-4 border-b border-gray-200">
                <h2 className="text-lg font-semibold">OWL/RDF Code View</h2>
                <p className="text-sm text-gray-600 mt-1">View the ontology in different serialization formats</p>
              </div>
              <div className="flex-1 flex flex-col overflow-hidden p-4">
                <div className="mb-4 flex gap-2 flex-shrink-0">
                  <button
                    onClick={() => fetchCodeViewContent('turtle')}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === 'turtle'
                        ? 'bg-purple-600 text-white hover:bg-purple-700'
                        : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                    }`}
                  >
                    Turtle
                  </button>
                  <button
                    onClick={() => fetchCodeViewContent('rdfxml')}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === 'rdfxml'
                        ? 'bg-purple-600 text-white hover:bg-purple-700'
                        : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                    }`}
                  >
                    RDF/XML
                  </button>
                  <button
                    onClick={() => fetchCodeViewContent('ntriples')}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === 'ntriples'
                        ? 'bg-purple-600 text-white hover:bg-purple-700'
                        : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                    }`}
                  >
                    N-Triples
                  </button>
                  <button
                    onClick={() => fetchCodeViewContent('owl')}
                    className={`px-3 py-1 text-sm rounded-md ${
                      codeViewFormat === 'owl'
                        ? 'bg-purple-600 text-white hover:bg-purple-700'
                        : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                    }`}
                  >
                    OWL/XML
                  </button>
                  <button
                    onClick={() => fetchCodeViewContent(codeViewFormat)}
                    className="ml-auto px-3 py-1 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
                    disabled={codeViewLoading}
                  >
                    {codeViewLoading ? 'Refreshing...' : 'Refresh'}
                  </button>
                </div>
                <div className="flex-1 overflow-hidden">
                  {codeViewLoading ? (
                    <div className="flex items-center justify-center h-64">
                      <div className="text-gray-500">Loading ontology content...</div>
                    </div>
                  ) : (
                    <CodeHighlighter
                      content={codeViewContent || '// No content available'}
                      format={codeViewFormat}
                    />
                  )}
                </div>
              </div>
            </div>
          </div>
        );
      case 'SPARQL':
        return <SparqlQueryEditor projectId={projectId!} prefixes={(metadata as any)?.prefixes || []} />;
      case 'Graph': {
        // Use dynamically loaded Graph View Plugin
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === 'graph-view-plugin');
        
        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} onNodeClick={handleGraphNodeClick} />;
        }
        
        return <div className="p-4">Install Advanced Graph View Plugin v2.0 from the Marketplace.</div>;
      }
      case 'SWRL': {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === 'swrl-editor-plugin');
        
        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }
        return <div className="p-4">Install SWRL Editor Plugin from the Marketplace.</div>;
      }
      case 'Fuzzy': {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === 'fuzzy-ontology-plugin');
        
        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }
        return <div className="p-4">Install Fuzzy Ontology Plugin from the Marketplace.</div>;
      }
      case 'Changes': {
        const plugin = pluginLoader.getInstalledPlugins().find((p: any) => p.id === 'change-assistant-plugin');
        
        if (plugin?.component && projectId) {
          const PluginComponent = plugin.component;
          return <PluginComponent projectId={projectId} />;
        }
        return <div className="p-4">Install Change Assistant Plugin from the Marketplace.</div>;
      }
      case 'ActiveOntology':
        return (
          <div className="flex h-full bg-gray-100">
            <div className="flex-1 flex flex-col bg-white border-r border-gray-200">
              <div className="p-4 border-b border-gray-200">
                <h2 className="text-xs text-gray-500 mb-2">Ontology header</h2>
                <div className="space-y-2">
                  <div>
                    <div className="text-xs font-semibold">Ontology IRI</div>
                    <a href={(metadata as any)?.ontologyIRI || "#"} className="text-blue-600 hover:underline text-xs break-all">{(metadata as any)?.ontologyIRI || "Not specified"}</a>
                  </div>
                  <div>
                    <div className="text-xs font-semibold">Ontology Version IRI</div>
                    <div className="text-xs text-gray-700 break-all">{(metadata as any)?.versionIRI || "Not specified"}</div>
                  </div>
                </div>
              </div>
              <div className="flex-1 overflow-y-auto p-4">
                <h3 className="text-xs font-semibold text-gray-700 mb-3">Annotations</h3>
                {(metadata as any)?.annotations && Object.keys((metadata as any).annotations).length > 0 ? (
                  <div className="space-y-2">
                    {Object.entries((metadata as any).annotations).map(([key, value]: [string, any]) => {
                      const propertyLabel = key.includes('#') ? key.split('#').pop() : 
                                          key.includes('/') ? key.split('/').pop() : key;
                      return (
                        <div key={key} className="border border-gray-200 rounded-md hover:border-blue-300 transition-colors">
                          <div className="bg-gradient-to-r from-purple-50 to-gray-50 px-3 py-2 border-b border-gray-200">
                            <div className="text-xs font-semibold text-purple-900">{propertyLabel}</div>
                            <div className="text-[10px] text-gray-400 font-mono truncate" title={key}>{key}</div>
                          </div>
                          <div className="px-3 py-2 bg-white text-xs text-gray-700">{value?.toString() || ''}</div>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="text-xs text-gray-400 italic p-2 bg-gray-50 border border-gray-200 rounded">No annotations</div>
                )}
              </div>
              <div className="border-t border-gray-200">
                <div className="flex bg-gray-100 text-xs border-b border-gray-200">
                  {['prefixes', 'imports', 'axioms'].map(t => (
                    <button key={t} onClick={() => setActiveOntologySubTab(t)}
                      className={`px-3 py-1.5 font-medium border-r border-gray-200 capitalize ${activeOntologySubTab === t ? 'bg-white text-gray-900' : 'text-gray-500 hover:bg-gray-200'}`}>
                      {t === 'imports' ? 'Ontology imports' : t === 'prefixes' ? 'Ontology Prefixes' : 'General class axioms'}
                    </button>
                  ))}
                </div>
                <div className="bg-white p-2 min-h-24 text-sm">
                  {activeOntologySubTab === 'prefixes' ? (
                    <div className="max-h-48 overflow-y-auto border border-gray-200 rounded">
                      <table className="w-full text-left text-xs">
                        <thead className="bg-gray-50 sticky top-0">
                          <tr className="border-b">
                            <th className="p-1.5 font-semibold">Prefix</th>
                            <th className="p-1.5 font-semibold">Namespace</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(metadata as any)?.prefixes?.map((p: { prefix: string; namespace: string }) => (
                            <tr key={p.prefix} className="border-b hover:bg-gray-50">
                              <td className="p-1.5 font-mono">{p.prefix}</td>
                              <td className="p-1.5 text-blue-700 break-all">{p.namespace}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="text-gray-400 italic">Content for {activeOntologySubTab}</div>
                  )}
                </div>
              </div>
            </div>
            <div className="w-80 bg-white p-4 overflow-y-auto space-y-4">
              {[
                {
                  title: 'Ontology metrics', data: {
                    Axiom: (metadata as any)?.axiomCount,
                    'Logical axiom': (metadata as any)?.logicalAxiomCount,
                    'Declaration axiom': (metadata as any)?.declarationAxiomCount,
                    'Class': (metadata as any)?.classCount,
                    'Object property': (metadata as any)?.objectPropertyCount,
                    'Data property': (metadata as any)?.dataPropertyCount,
                    'Individual': (metadata as any)?.individualCount,
                    'Annotation Property': annotationProperties.length
                  }
                },
                {
                  title: 'Class axioms', data: {
                    SubClassOf: (metadata as any)?.subClassOfAxiomCount,
                    EquivalentClasses: (metadata as any)?.equivalentClassesAxiomCount,
                    DisjointClasses: (metadata as any)?.disjointClassesAxiomCount,
                    'GCI count': (metadata as any)?.gciCount,
                    'Hidden GCI Count': (metadata as any)?.hiddenGciCount
                  }
                },
                {
                  title: 'Object property axioms', data: {
                    SubObjectPropertyOf: (metadata as any)?.subObjectPropertyOfAxiomCount,
                    InverseObjectProperties: (metadata as any)?.inverseObjectPropertiesAxiomCount
                  }
                }
              ].map(metricSection => (
               
                <div key={metricSection.title}>
                  <h3 className="font-semibold text-sm mb-2 border-b pb-1">{metricSection.title}</h3>
                  <div className="space-y-1 text-xs">
                    {Object.entries(metricSection.data).map(([key, value]) => (value ?? null) !== null && (
                      <div key={key} className="flex justify-between items-center">
                        <span className="text-gray-600">{key}</span>
                        <span className="font-medium bg-gray-100 px-1.5 py-0.5 rounded">{Number(value).toLocaleString()}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      case 'IndividualsByClass': {
        const individualsForSelectedClass = selectedClassForIndividuals ? individuals.filter(ind => ind.types?.includes(selectedClassForIndividuals.id)) : [];
        return (
          <div className="flex h-full">
            <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
              <div className="p-2 border-b text-sm font-semibold text-gray-700">Class hierarchy</div>
              <div className="flex-1 overflow-y-auto p-1">
                <EntityHierarchy
                  entitiesTab="Classes"
                  filteredData={classHierarchy}
                  selectedItem={selectedClassForIndividuals}
                  expandedNodes={expandedNodes}
                  searchQuery=""
                  onSearchQueryChange={() => { /* no-op for this view */ }}
                  onSelectItem={(item) => setSelectedClassForIndividuals(item as TreeNode)}
                  onToggleNode={toggleNode}
                  onAddItem={() => { /* not used here */ }}
                  onDeleteItem={() => { /* not used here */ }}
                />
              </div>
            </aside>
            <main className="flex-1 p-2 bg-gray-50">
              <div className="border bg-white h-full flex flex-col">
                <div className="flex text-xs border-b flex-shrink-0">
                  <button className="px-3 py-1.5 bg-white border-r font-semibold">Direct instances</button>
                  <button className="px-3 py-1.5 bg-gray-100 text-gray-500 hover:bg-gray-200">Individuals (inferred)</button>
                </div>
                {selectedClassForIndividuals ? (
                  <div className="p-1 flex-1 overflow-y-auto">
                    {individualsForSelectedClass.length > 0 ? (
                      individualsForSelectedClass.map(ind => (
                        <div key={ind.id} className="flex items-center p-1.5 text-xs hover:bg-gray-100 rounded">
                          <User size={12} className="mr-2 text-purple-600" />
                          {ind.label}
                        </div>
                      ))
                    ) : (
                      <div className="p-4 text-sm text-gray-400 italic flex items-center justify-center h-full">
                        No instances found for {selectedClassForIndividuals.label}.
                      </div>
                    )}
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
      case 'DLQuery':
        return (
          <div className="flex h-full">
            <main className="flex-1 flex flex-col p-2 bg-gray-50">
              <div className="border bg-white p-2">
                <h3 className="text-xs font-semibold mb-2">Query (class expression)</h3>
                <textarea value={dlQuery} onChange={e => setDlQuery(e.target.value)} className="w-full h-24 border p-1 font-mono text-sm focus:ring-1 focus:ring-purple-500 text-black"></textarea>
                <div className="flex gap-2 mt-2">
                  <button onClick={handleExecuteDlQuery} disabled={isDlQueryLoading} className="px-3 py-1 bg-purple-600 text-white text-xs rounded hover:bg-purple-700 disabled:bg-purple-300 flex items-center gap-2">
                    {isDlQueryLoading ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
                    Execute
                  </button>
                  <button onClick={handleAddDlToOntology} className="px-3 py-1 bg-gray-200 text-xs rounded hover:bg-gray-300">Add to ontology</button>
                </div>
              </div>
              <div className="border bg-white p-2 mt-2 flex-1">
                <h3 className="text-xs font-semibold mb-2">Query results</h3>
                {isDlQueryLoading ? (
                  <div className="flex items-center justify-center h-full text-gray-500 text-sm">
                    <Loader2 size={20} className="animate-spin mr-2" />
                    Executing query...
                  </div>
                ) : dlQueryResults ? (
                  dlQueryResults.length > 0 ? (
                    <div className="overflow-y-auto h-full">
                      {dlQueryResults.map(res => <div key={res} className="p-1 text-sm">{res}</div>)}
                    </div>
                  ) : (
                    <div className="p-2 text-sm text-gray-400 italic h-full">No results found.</div>
                  )
                ) : (
                  <div className="p-2 text-sm text-gray-400 italic h-full">Query results will appear here.</div>
                )}
              </div>
            </main>
            <aside className="w-64 bg-white border-l p-2 space-y-4">
              <div>
                <h3 className="text-xs font-semibold mb-1">Query for</h3>
                <div className="space-y-1 text-xs">
                  {['Direct superclasses', 'Superclasses', 'Equivalent classes', 'Direct subclasses', 'Subclasses', 'Instances'].map(item => (
                    <label key={item} className="flex items-center gap-2">
                      <input type="checkbox" defaultChecked={item === 'Subclasses'} /> {item}
                    </label>
                  ))}
                </div>
              </div>
              <div>
                <h3 className="text-xs font-semibold mb-1">Result filters</h3>
                <input type="text" placeholder="Name contains" className="w-full border px-2 py-1 text-xs" />
              </div>
            </aside>
          </div>
        );
      default:
        return <div className="p-6 text-gray-400">Select a tab</div>;
    }
  };
  // #endregion

  // #region Selector Handlers
  const handleOpenClassSelector = (target: 'domain' | 'range') => {
    setSelectorTarget(target);
    setIsClassExpressionDialogOpen(true);
  };

  const handleOpenPropertySelector = (target: 'subProperty' | 'inverse' | 'disjoint' | 'equivalent') => {
    setSelectorTarget(target);
    setIsPropertyExpressionDialogOpen(true);
  };

  const handleManchesterConfirm = async (expression: string, _restrictionData?: unknown) => {
    if (!selectedItem || !projectId || !selectorTarget) return;

    try {
      switch (selectorTarget) {
        case 'domain':
          await ontologyMutationService.addPropertyDomain(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
          updateItemInState({ ...selectedItem, domains: [...((selectedItem as Property).domains || []), expression] });
          break;
        case 'range':
          await ontologyMutationService.addPropertyRange(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
          updateItemInState({ ...selectedItem, ranges: [...((selectedItem as Property).ranges || []), expression] });
          break;
      }
    } catch (error) {
      console.error(`Failed to add ${selectorTarget}`, error);
    } finally {
      setIsClassSelectorOpen(false);
      setSelectorTarget(null);
    }
  };

  const handlePropertySelected = async (expression: string) => {
    if (!selectedItem || !projectId || !selectorTarget) return;

    try {
      switch (selectorTarget) {
        case 'subProperty':
          await ontologyMutationService.addSubPropertyOf(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
          updateItemInState({ ...selectedItem, superProperties: [...((selectedItem as Property).superProperties || []), expression] });
          break;
        case 'inverse':
          await ontologyMutationService.addInverseProperty(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
          updateItemInState({ ...selectedItem, inverseProperties: [...((selectedItem as Property).inverseProperties || []), expression] });
          break;
        case 'disjoint':
          await ontologyMutationService.addDisjointProperty(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
          updateItemInState({ ...selectedItem, disjointProperties: [...((selectedItem as Property).disjointProperties || []), expression] });
          break;
        case 'equivalent': {
           const existing = (selectedItem as Property).equivalentProperties || [];
           await ontologyMutationService.addEquivalentProperty(projectId, selectedItem.id, expression, user?.email || 'anonymous', user?.username || 'Anonymous');
           updateItemInState({ ...selectedItem, equivalentProperties: [...existing, expression] });
           break;
        }
      }
    } catch (error) {
      console.error(`Failed to add ${selectorTarget}`, error);
    } finally {
      setIsPropertyExpressionDialogOpen(false);
      setSelectorTarget(null);
    }
  };
  // #endregion

  // #region Main Render
  const ALL_MAIN_TABS: Record<string, { label: string, icon: React.ElementType }> = {
    ActiveOntology: { label: "Active ontology", icon: FileText },
    Entities: { label: "Entities", icon: List },
    Graph: { label: "Graph", icon: Share2 },
    IndividualsByClass: { label: "Individuals by class", icon: Eye },
    DLQuery: { label: "DL Query", icon: Code },
    CodeView: { label: "Code View", icon: Code },
    SPARQL: { label: "SPARQL Query", icon: DatabaseZap },
    SWRL: { label: "SWRL Rules", icon: Code },
    Fuzzy: { label: "Fuzzy Ontology", icon: Sparkles },
    Changes: { label: "Change Assistant", icon: GitBranch },
  };

  const entitiesTabs = [
    { id: "Classes", label: "Classes", icon: Package, count: (metadata as any)?.classCount, theme: 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]' },
    { id: "ObjectProperties", label: "Object properties", icon: Share2, count: (metadata as any)?.objectPropertyCount, theme: 'bg-gradient-to-b from-blue-300 to-blue-500 text-white border-blue-600' },
    { id: "DataProperties", label: "Data properties", icon: Database, count: (metadata as any)?.dataPropertyCount, theme: 'bg-gradient-to-b from-green-300 to-green-500 text-white border-green-600' },
    { id: "AnnotationProperties", label: "Annotation properties", icon: Tag, count: annotationProperties.length, theme: 'bg-gradient-to-b from-orange-300 to-orange-500 text-white border-orange-600' },
    { id: "Datatypes", label: "Datatypes", icon: Settings, count: datatypes.length || 0, theme: 'bg-gradient-to-b from-red-300 to-red-500 text-white border-red-600' },
    { id: "Individuals", label: "Individuals", icon: Eye, count: (metadata as any)?.individualCount, theme: 'bg-gradient-to-b from-purple-300 to-purple-500 text-white border-purple-600' },
  ];
  const activeTheme = entitiesTabs.find(t => t.id === entitiesTab)?.theme;

  // Don't show welcome screen - just render empty editor if no project loaded
  // User can click the file selector in the header to browse projects

  return (
    <>
      <LoadingDialog isOpen={isInitialLoading} />
      <CreateIndividualModal isOpen={isCreateIndividualModalOpen} onClose={() => setCreateIndividualModalOpen(false)} onCreate={handleAddIndividual} />
      <AddClassDialog
        isOpen={isAddClassDialogOpen}
        onClose={() => setAddClassDialogOpen(false)}
        onCreate={handleCreateClass}
        type={addClassType}
        parentLabel={classParentLabel}
      />
      <AddObjectPropertyDialog
        isOpen={isAddPropertyDialogOpen}
        onClose={() => setAddPropertyDialogOpen(false)}
        onCreate={entitiesTab === 'ObjectProperties' ? handleCreateObjectProperty : 
                  entitiesTab === 'DataProperties' ? handleCreateDataProperty : 
                  handleCreateAnnotationProperty}
        type={addPropertyType}
        parentLabel={propertyParentLabel}
        propertyType={entitiesTab === 'ObjectProperties' ? 'object' : 
                     entitiesTab === 'DataProperties' ? 'data' : 'annotation'}
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
      />
      <AddAnnotationDialog 
        isOpen={isEditAnnotationDialogOpen} 
        onClose={() => {
          setEditAnnotationDialogOpen(false);
          setEditAnnotationData(null);
        }}
        onAdd={(propertyIri, newValue) => {
          if (editAnnotationData) {
            handleAnnotationDialogEdit(propertyIri, editAnnotationData.currentValue, newValue);
          }
          setEditAnnotationDialogOpen(false);
          setEditAnnotationData(null);
        }}
        availableProperties={annotationProperties}
        editMode={true}
        initialProperty={editAnnotationData?.propertyIri || ''}
        initialValue={editAnnotationData?.currentValue || ''}
      />
      <OpenFileDialog
        isOpen={showOpenDialog}
        onClose={() => setShowOpenDialog(false)}
        myFiles={myFiles}
        sharedFiles={sharedFiles}
        currentProjectId={projectId}
        onSwitchFile={handleSwitchFile}
      />
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        onClose={() => setConfirmDialog(prev => ({ ...prev, isOpen: false }))}
        onConfirm={confirmDialog.onConfirm}
        onCancel={confirmDialog.onCancel}
        title={confirmDialog.title}
        message={confirmDialog.message}
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

      <div className="h-screen bg-gray-50 flex flex-col text-sm max-h-screen">
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
          onOpenDialog={() => setShowOpenDialog(true)}
          onOpenPluginMarketplace={() => setShowPluginMarketplace(true)}
          onOpenHistory={() => setIsHistoryPanelOpen(true)}
          syncMode={syncMode}
          onToggleSyncMode={() => {
            const newMode = syncMode === 'public' ? 'private' : 'public';
            setSyncMode(newMode);
            ontologyMutationService.setRealTimeSync(newMode === 'public');
            if (newMode === 'public') {
              notificationService.success('Live Mode Enabled', 'Changes will be broadcast immediately.');
            } else {
              notificationService.info('Draft Mode Enabled', 'Changes will be saved locally until you save.');
            }
          }}
        />

        <div className="bg-white border-b border-gray-200 flex-shrink-0">
          <div className="flex items-center justify-between px-4 h-10">
      <div className="flex items-center flex-nowrap overflow-x-auto no-scrollbar gap-1">
              {visibleMainTabs.map((tabId) => {
                const tab = ALL_MAIN_TABS[tabId];
                if (!tab) return null;
                return (
                <button
                  key={tabId}
                  className={`flex items-center gap-2 px-3 h-full text-xs font-medium border-b-2 -mb-px whitespace-nowrap flex-shrink-0 ${mainTab === tabId ? "text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent"}`}
                  onClick={() => setMainTab(tabId)}
                >
                    <tab.icon size={14} /> {tab.label}
                  </button>
                );
              })}
            </div>
            <div className="flex items-center gap-4">
              {projectId && (
                <button
                  onClick={() => setShowCollaborationPanel(!showCollaborationPanel)}
                  className={`flex items-center gap-1.5 px-2 py-1 text-xs rounded transition-colors ${
                    showCollaborationPanel
                      ? 'bg-blue-600 text-white hover:bg-blue-700'
                      : isCurrentFileShared 
                        ? 'bg-green-100 text-green-700 hover:bg-green-200'
                        : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                  }`}
                  title={`Toggle Collaboration Panel${hasMultipleActiveUsers ? ` (${activeUsersInProject.length} users)` : isCurrentFileShared ? ' (Shared file)' : ' (Enable sharing to collaborate)'}`}
                >
                  <Users size={14} />
                  <span>Collaboration</span>
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
              {projectId && (
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
              )}
              <span className="text-xs text-gray-600">Welcome, {user?.username || 'Guest'}</span>
              <button onClick={logout} className="flex items-center gap-1.5 text-xs text-red-500 hover:text-red-700 hover:bg-red-50 p-2 rounded-md">
                <LogOut size={14} />
                Logout
              </button>
            </div>
          </div>
        </div>

        {mainTab === 'Entities' && (
          <div className="bg-gray-100 border-b border-gray-200 px-4 flex-shrink-0">
            <div className="flex items-center flex-nowrap overflow-x-auto no-scrollbar gap-1">
              {entitiesTabs.map((tab) => (
                <button
                  key={tab.id}
                  title={tab.label}
                  className={`flex items-center gap-2 px-3 py-1 text-xs font-medium border-t-2 mt-px whitespace-nowrap flex-shrink-0 ${entitiesTab === tab.id ? "bg-white text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent hover:bg-gray-200 rounded-t"}`}
                  onClick={() => { setEntitiesTab(tab.id); setSelectedItem(null); }}
                >
                  <tab.icon size={14} />
                  <span>{tab.label}</span>
                  <span className="bg-gray-200 text-gray-700 px-1.5 py-0.5 rounded-sm font-bold">{tab.count || 0}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        <main className="flex flex-1 overflow-hidden">
          {mainTab === "Entities" ? (
            <>
              <EntityHierarchy
                entitiesTab={entitiesTab}
                filteredData={filteredData}
                selectedItem={selectedItem}
                expandedNodes={expandedNodes}
                searchQuery={searchQuery}
                onSearchQueryChange={setSearchQuery}
                onSelectItem={setSelectedItem}
                onToggleNode={toggleNode}
                onAddItem={handleAddItem}
                onDeleteItem={handleDeleteItem}
                onMakeSiblingsDisjoint={handleMakeSiblingsDisjoint}
                onOpenPreferences={() => setEntityPreferencesDialogOpen(true)}
                onRenameItem={handleRenameItem}
              />

              <section className="flex-1 overflow-hidden p-2 bg-slate-200 flex flex-col">
                <div className="flex-1 overflow-hidden flex flex-col">
                  <DetailsPanel
                    selectedItem={selectedItem}
                    entitiesTab={entitiesTab}
                    activeTheme={activeTheme}
                    projectId={projectId}
                    onUpdate={updateItemInState}
                    onAddAnnotation={handleAddAnnotation}
                    onEditAnnotation={handleEditAnnotation}
                    onDeleteAnnotation={handleDeleteAnnotation}
                    onAddDomainClick={() => handleOpenClassSelector('domain')}
                    onAddRangeClick={() => handleOpenClassSelector('range')}
                    onAddSubPropertyClick={() => handleOpenPropertySelector('subProperty')}
                    onAddInverseClick={() => handleOpenPropertySelector('inverse')}
                    onAddDisjointClick={() => handleOpenPropertySelector('disjoint')}
                    onAddEquivalentClick={() => handleOpenPropertySelector('equivalent')}
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
                  />
                </div>
              </section>
            </>
          ) : (
            <section className="flex-1 overflow-y-auto bg-white">
              {renderMainContent()}
            </section>
          )}
        </main>
      </div>

      {/* Class Selector Dialog */}
      {/* Class Expression Dialog for Domain/Range with 4 tabs */}
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
        title={`Add ${selectorTarget === 'domain' ? 'Domain' : 'Range'} Class Expression`}
        expandedNodes={expandedNodes}
        onToggleNode={toggleNode}
        onAddClass={(type) => handleAddItem(type)}
        onDeleteClass={() => handleDeleteItem()}
        onAddProperty={(type) => handleAddItem(type)}
        onDeleteProperty={() => handleDeleteItem()}
        onRefreshClasses={refreshClassHierarchy}
        metadata={metadata}
      />

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

      {/* Property Expression Dialog */}
      <PropertyExpressionDialog
        isOpen={isPropertyExpressionDialogOpen}
        onClose={() => {
          setIsPropertyExpressionDialogOpen(false);
          setSelectorTarget(null);
        }}
        onConfirm={handlePropertySelected}
        propertyHierarchy={objectPropertyHierarchy}
        propertyType={selectedItem?.type === 'DataProperty' ? 'data' : 'object'}
        title={`Select ${selectorTarget ? selectorTarget.charAt(0).toUpperCase() + selectorTarget.slice(1) : 'Property'}`}
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

      {/* Loading Choice Dialog */}
      <LoadingChoiceDialog
        isOpen={showLoadingChoice}
        projectName={loadingProjectName}
        loadingStatusMessage={loadingStatusMessage}
        onWait={handleWaitForLoading}
        onContinue={handleContinueWorking}
      />

      {/* Collaboration Panel - Toggle visibility manually */}
      {showCollaborationPanel && (
        <CollaborationPanel ref={collaborationPanelRef} projectId={projectId || undefined} />
      )}

      {/* Share Dialog */}
      {shareFileId && (
        <ShareDialog
          isOpen={isShareDialogOpen}
          onClose={() => {
            setIsShareDialogOpen(false);
            setShareFileId(null);
          }}
          projectId={shareFileId}
          userEmail={user?.email || ''}
        />
      )}

      {/* Toast Notifications */}
      <div className="fixed top-4 right-4 z-[9999] space-y-2">
        {collaboration.state.notifications.map(notification => (
          <ToastNotification
            key={notification.id}
            type={notification.type}
            message={notification.message}
            username={notification.username}
            userColor={notification.userColor}
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
        projectId={projectId || ''}
        visible={showQueueStatus && !!projectId}
      />

      {/* Global Queue Stats */}
      <GlobalQueueStats visible={true} />

      {/* History Panel */}
      {projectId && (
        <HistoryPanel
          projectId={projectId}
          isOpen={isHistoryPanelOpen}
          onClose={() => setIsHistoryPanelOpen(false)}
        />
      )}
    </>
  );
};

export default Dashboard;
