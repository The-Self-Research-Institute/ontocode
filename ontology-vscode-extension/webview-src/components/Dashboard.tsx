// src/Dashboard.tsx
import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight, ChevronDown, Settings, Search, FileText, Eye, Database, Tag, Share2, List, Code, Loader2, Package, Check, Trash2, PlusCircle, User, Type, GitBranch, Binary, LogOut, Play, DatabaseZap,
  Download
} from "lucide-react";
import apiClient from "../services/apiClient";
import ontologyMutationService from "../services/ontologyMutationService";
import { notificationService } from "../services/notificationService";
import { pluginManager } from '../plugins/PluginSystem';
import { SWRLPlugin, ReasoningPlugin } from '../plugins/PluginRegistry';
import type { TreeNode, Property, Individual, OntologyMetadata, SelectableItem, AnnotationProperty, Datatype } from '../types';
import { useAuth } from '../custom-hook/useAuth';
import { useCollaboration } from '../contexts/CollaborationContext';
import EntityHierarchy from './EntityHierarchy';
import ClassEditor from './details/ClassEditor';
import PropertyEditor from './details/PropertyEditor';
import IndividualEditor from './details/IndividualEditor';
import { Panel, AnnotationsDisplay } from './details/common';
import SparqlQueryEditor from './SparqlQueryEditor';
import { ProjectSelector } from './ProjectSelector';
import CollaborationPanel from './CollaborationPanel';
import ToastNotification from './ToastNotification';
import ShareDialog from './ShareDialog';
import { 
  ClassSelectorDialog, 
  PropertySelectorDialog, 
  CreateIndividualModal, 
  AddAnnotationDialog, 
  AddClassDialog 
} from './dialogs';

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
  onWait, 
  onContinue 
}: { 
  isOpen: boolean; 
  projectName: string;
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
            <p className="text-sm text-gray-600">
              "{projectName}" is loading in the background...
            </p>
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
  onToggleSwrlTab,
  isSwrlVisible,
  onToggleGraphTab,
  isGraphVisible,
  fileList,
  myFiles,
  sharedFiles,
  currentProjectId,
  onShareFile,
  onSave,
  onSwitchFile,
  hasUnsavedChanges,
  isSaving,
  onOpenDialog,
}: {
  onToggleSwrlTab: () => void;
  isSwrlVisible: boolean;
  onToggleGraphTab: () => void;
  isGraphVisible: boolean;
  fileList: FileInfo[];
  myFiles: FileInfo[];
  sharedFiles: FileInfo[];
  currentProjectId: string | null;
  onShareFile: (fileId: string) => void;
  onSave: () => Promise<void>;
  onSwitchFile: (projectId: string) => void;
  hasUnsavedChanges: boolean;
  isSaving: boolean;
  onOpenDialog: () => void;
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
  const menuItems = ['File', 'Edit', 'View', 'Reasoner', 'Tools', 'Window', 'Download', 'Help', 'Share'];

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
                if (item === "Download") {
                  console.log('[TopMenuBar] Download clicked, projectId:', currentProjectId);
                  if (window.vscode && currentProjectId) {
                    window.vscode.postMessage({ 
                      type: "downloadOntology",
                      url: `/api/ontology/export/${currentProjectId}`,
                      filename: `${currentProjectId}.owl`
                    });
                  } else if (!currentProjectId) {
                    console.warn('[TopMenuBar] No project loaded to download');
                    // Show user-friendly message
                    if (window.vscode) {
                      window.vscode.postMessage({
                        type: 'error',
                        value: 'No ontology loaded. Please open an ontology file first.'
                      });
                    }
                  }
                } else if (item === "Share") {
                  // Check if current file is owned by user
                  const currentFile = myFiles.find(f => f.id === currentProjectId);
                  if (currentProjectId && currentFile) {
                    onShareFile(currentProjectId);
                    setOpenMenu(null);
                  } else if (!currentProjectId) {
                    if (window.vscode) {
                      window.vscode.postMessage({
                        type: 'error',
                        value: 'No ontology loaded. Please open a file first.'
                      });
                    }
                  } else {
                    if (window.vscode) {
                      window.vscode.postMessage({
                        type: 'error',
                        value: 'You can only share files you own. This file is shared with you.'
                      });
                    }
                  }
                } else {
                  setOpenMenu(openMenu === item ? null : item);
                }
              }}
              className="px-3 py-1 hover:bg-gray-300 rounded-sm"
            >
              {item}
            </button>
            {openMenu === item && (
              <div className={`absolute left-0 mt-1 ${item === 'File' ? 'w-[360px]' : 'w-48'} bg-white border border-gray-300 rounded-lg shadow-xl z-20 overflow-hidden`}>
                {item === "Window" ? (
                  <div className="py-1">
                    <div className="px-3 py-1 text-gray-400 text-xs">Tabs</div>
                    <a
                      href="#"
                      onClick={(e) => {
                        e.preventDefault();
                        onToggleSwrlTab();
                        setOpenMenu(null);
                      }}
                      className="flex justify-between items-center px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100"
                    >
                      SWRL Tab {isSwrlVisible && <Check size={14} className="text-purple-600" />}
                    </a>
                  </div>
                ) : item === "Reasoner" ? (
                  <div className="py-1">
                    <a
                      href="#"
                      onClick={(e) => {
                        e.preventDefault();
                        onToggleGraphTab();
                        setOpenMenu(null);
                      }}
                      className="flex justify-between items-center px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100"
                    >
                      Graph View {isGraphVisible && <Check size={14} className="text-purple-600" />}
                    </a>
                  </div>
                ) : item === "File" ? (
                  <div className="flex flex-col py-1">
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
                    <button
                      onClick={async (e) => {
                        e.preventDefault();
                        await onSave();
                        setOpenMenu(null);
                      }}
                      disabled={!hasUnsavedChanges || isSaving || !currentProjectId}
                      className="w-full text-left px-4 py-2 text-xs hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                    >
                      Save
                      {hasUnsavedChanges && <span className="text-orange-600 text-lg leading-none">•</span>}
                    </button>
                  </div>
                ) : (
                  <div className="p-2 text-xs text-gray-400">No actions available</div>
                )}
              </div>
            )}
          </div>
        ))}
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
              <Search size={32} className="mb-2 opacity-50" />
              <p className="text-sm">No files found</p>
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
  title,
  message
}: {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
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
            onClick={onClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={() => {
              onConfirm();
              onClose();
            }} 
            className="px-4 py-2 text-sm bg-red-600 text-white rounded-md hover:bg-red-700"
          >
            Delete
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
  onDeleteAnnotation,
  onAddDomainClick,
  onAddRangeClick,
  onAddSubPropertyClick,
  onAddInverseClick,
  onAddDisjointClick,
  onAddEquivalentClick,
  classHierarchy,
  objectProperties
}: {
  selectedItem: SelectableItem | null;
  entitiesTab: string;
  activeTheme?: string;
  projectId: string | null;
  onUpdate: (item: SelectableItem) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
  onAddSubPropertyClick?: () => void;
  onAddInverseClick?: () => void;
  onAddDisjointClick?: () => void;
  onAddEquivalentClick?: () => void;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
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
    onDeleteAnnotation,
    activeTheme,
    projectId: projectId || ''
  };

  switch (entitiesTab) {
    case 'Classes':
      return <ClassEditor
        item={selectedItem as TreeNode}
        onUpdate={onUpdate}
        classHierarchy={classHierarchy}
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
          <Panel title={`Annotations: ${item.label}`} {...sharedProps}><AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} /></Panel>
        </div>
      );
    }
    case 'Datatypes':
      return <Panel title={`Annotations: ${selectedItem.label}`} {...sharedProps}><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} /></Panel>;
    default:
      return <div className="bg-white rounded-lg border p-4"><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} /></div>;
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
  const [showLoadingChoice, setShowLoadingChoice] = useState(false);
  const [loadingProjectName, setLoadingProjectName] = useState("");
  const loadingPromiseRef = useRef<Promise<void> | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [showOpenDialog, setShowOpenDialog] = useState(false);
  const [activeOntologySubTab, setActiveOntologySubTab] = useState('prefixes');
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  const [isAddAnnotationDialogOpen, setAddAnnotationDialogOpen] = useState(false);
  const [isAddClassDialogOpen, setAddClassDialogOpen] = useState(false);
  const [addClassType, setAddClassType] = useState<'subclass' | 'sibling'>('subclass');
  
  // Selector Dialog State
  const [isClassSelectorOpen, setIsClassSelectorOpen] = useState(false);
  const [isPropertySelectorOpen, setIsPropertySelectorOpen] = useState(false);
  const [selectorTarget, setSelectorTarget] = useState<'domain' | 'range' | 'subProperty' | 'inverse' | 'disjoint' | 'equivalent' | null>(null);
  
  // Confirm dialog state
  const [confirmDialog, setConfirmDialog] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: () => {}
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

  const [filteredData, setFilteredData] = useState<SelectableItem[]>([]);
  const [listOfFiles, setListOfFiles] = useState<FileInfo[]>([]);
  const [myFiles, setMyFiles] = useState<FileInfo[]>([]);
  const [sharedFiles, setSharedFiles] = useState<FileInfo[]>([]);
  const [isShareDialogOpen, setIsShareDialogOpen] = useState(false);
  const [shareFileId, setShareFileId] = useState<string | null>(null);

  const [visibleMainTabs, setVisibleMainTabs] = useState(['ActiveOntology', 'Entities', 'IndividualsByClass', 'DLQuery', 'SPARQL']);
  // #endregion

  // #region Data Fetching and Initialization
  const toggleSwrlTab = useCallback(() => {
    setVisibleMainTabs(prev => prev.includes('SWRL') ? prev.filter(t => t !== 'SWRL') : [...prev, 'SWRL']);
  }, []);

  const toggleGraphTab = useCallback(() => {
    setVisibleMainTabs(prev => prev.includes('Graph') ? prev.filter(t => t !== 'Graph') : [...prev, 'Graph']);
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
      console.log("Properties response:", propertiesRes);
      const allProps = Array.isArray(propertiesRes?.data) ? propertiesRes.data : 
                       Array.isArray(propertiesRes?.properties) ? propertiesRes.properties : 
                       Array.isArray(propertiesRes) ? propertiesRes : [];
      console.log("All props after extraction:", allProps);
      const opList = allProps.filter((p: Property) => p.type === "ObjectProperty");
      setObjectProperties(opList);

      // Build Object Property Hierarchy
      const opMap = new Map<string, any>();
      // Create nodes
      opList.forEach((p: Property) => {
        opMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topObjectProperty = {
        id: 'http://www.w3.org/2002/07/owl#topObjectProperty',
        label: 'owl:topObjectProperty',
        type: 'ObjectProperty',
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
      setDataProperties(dpList);

      // Build Data Property Hierarchy
      const dpMap = new Map<string, any>();
      dpList.forEach((p: Property) => {
        dpMap.set(p.id, { ...p, children: [], hasChildren: false });
      });

      const topDataProperty = {
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
        
        if (filesRes.myFiles && filesRes.sharedFiles) {
          // New format with separate lists
          const myProjects = Array.isArray(filesRes.myFiles) ? filesRes.myFiles : [];
          const sharedProjects = Array.isArray(filesRes.sharedFiles) ? filesRes.sharedFiles : [];
          
          setMyFiles(myProjects.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0,
            ownerEmail: p.ownerEmail
          })));
          
          setSharedFiles(sharedProjects.map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0,
            sharedBy: p.sharedBy,
            ownerEmail: p.ownerEmail,
            permission: p.permission || 'view'
          })));
          
          console.log('[Dashboard] 📂 Loaded shared files:', sharedProjects.length);
          console.log('[Dashboard] 🤝 Collaboration features available for shared editing');
          
          // Combined list for backward compatibility
          setListOfFiles([...myProjects, ...sharedProjects].map((p: any) => ({
            id: p.id,
            filename: p.filename || p.name || p.id,
            contentType: 'application/rdf+xml',
            uploadDate: p.updatedAt || new Date().toISOString(),
            length: 0
          })));
        } else {
          // Old format (backward compatibility)
          const projects = Array.isArray(filesRes?.projects) ? filesRes.projects : [];
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
      } catch (fileError) {
        console.error("Failed to fetch files:", fileError);
        setListOfFiles([]);
        setMyFiles([]);
        setSharedFiles([]);
      }
      
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
  }, []);

  const fetchProjects = useCallback(async () => {
    try {
      const userEmail = user?.email || '';
      const response = await apiClient.get<{ success: boolean; projects?: any[]; myFiles?: any[]; sharedFiles?: any[] }>(`/api/projects?userEmail=${encodeURIComponent(userEmail)}`);
      
      if (response.success) {
        // Handle new format with myFiles and sharedFiles
        if (response.myFiles && response.sharedFiles) {
          const allProjects = [...(response.myFiles || []), ...(response.sharedFiles || [])];
          setAvailableProjects(allProjects);
          
          // If no project selected and projects exist, auto-load the first one from myFiles
          if (!projectId && response.myFiles.length > 0) {
            const firstProject = response.myFiles[0];
            console.log('[Dashboard] Auto-loading first project:', firstProject.id);
            setProjectId(firstProject.id);
            fetchData(firstProject.id);
          }
        } else if (response.projects) {
          // Backward compatibility with old format
          setAvailableProjects(response.projects);
          
          if (!projectId && response.projects.length > 0) {
            const firstProject = response.projects[0];
            console.log('[Dashboard] Auto-loading first project:', firstProject.id);
            setProjectId(firstProject.id);
            fetchData(firstProject.id);
          }
        }
      }
    } catch (error) {
      console.error("Failed to fetch projects:", error);
    }
  }, [projectId, fetchData, user]);

  const handleProjectSelection = useCallback((selectedProjectId: string) => {
    setProjectId(selectedProjectId);
    setShowProjectSelector(false);
    fetchData(selectedProjectId);
  }, [fetchData]);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = classHierarchy[0].id;
      const childCount = classHierarchy[0].children?.length || 0;
      console.log('[Dashboard] Class hierarchy loaded, owl:Thing has', childCount, 'top-level children');
      
      // Always auto-expand owl:Thing when it has children
      if (childCount > 0 && !expandedNodes.includes(owlThingId)) {
        console.log('[Dashboard] Auto-expanding owl:Thing');
        setExpandedNodes([owlThingId]);
      }
    }
  }, [classHierarchy]);

  // Fetch projects on mount when no projectId is set
  useEffect(() => {
    if (!projectId) {
      fetchProjects();
    }
  }, [projectId, fetchProjects]);

  // Send 'webviewReady' to extension when mounted
  useEffect(() => {
    if (window.vscode) {
      window.vscode.postMessage({ type: 'webviewReady' });
    }
  }, []);

  // Handle loading choice dialog actions
  const handleWaitForLoading = useCallback(() => {
    setShowLoadingChoice(false);
    setIsInitialLoading(true);
    // Wait for the loading promise to complete
    if (loadingPromiseRef.current) {
      loadingPromiseRef.current.finally(() => {
        setIsInitialLoading(false);
      });
    }
  }, []);

  const handleContinueWorking = useCallback(() => {
    setShowLoadingChoice(false);
    // User chose to continue, they'll get a notification when done
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      console.log('[Dashboard] Received message:', message.type, message);
      switch (message.type) {
        case "showLoading":
          setIsInitialLoading(true);
          break;
        case "fileReady":
        case "fileLoaded":
          // Show loading choice dialog
          console.log('[Dashboard] Loading project:', message.projectId);
          setProjectId(message.projectId);
          setSelectedItem(null);
          setLoadingProjectName(message.projectId);
          setShowLoadingChoice(true);
          
          // Start loading in background and store the promise
          loadingPromiseRef.current = fetchData(message.projectId, false)
            .then(() => {
              console.log('[Dashboard] Loading completed for:', message.projectId);
              setShowLoadingChoice(false);
            })
            .catch((error) => {
              console.error('[Dashboard] Failed to load ontology:', error);
              notificationService.error('Load Failed', `Could not load "${message.projectId}". The file may still be processing.`);
              setShowLoadingChoice(false);
            });
          break;
        case "loadingFailed":
          setIsInitialLoading(false);
          console.error("Loading failed:", message.error);
          notificationService.error('Loading Failed', message.error);
          break;
        case "switchView":
          if (message.view === 'swrl' && !visibleMainTabs.includes('SWRL')) {
            toggleSwrlTab();
          }
          setMainTab('SWRL');
          break;
      }
    };
    window.addEventListener("message", handleMessage);

    return () => {
      window.removeEventListener("message", handleMessage);
    };
  }, [toggleSwrlTab, visibleMainTabs, fetchData]);

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

  useEffect(() => {
    let sourceData: SelectableItem[] = [];
    switch (entitiesTab) {
      case "Classes": sourceData = classHierarchy; break;
      case "ObjectProperties": sourceData = objectPropertyHierarchy; break;
      case "DataProperties": sourceData = dataPropertyHierarchy; break;
      case "AnnotationProperties": sourceData = annotationProperties; break;
      case "Individuals": sourceData = individuals; break;
      case "Datatypes": sourceData = datatypes; break;
    }

    if (searchQuery) {
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
      setFilteredData(filterRecursively(sourceData));
    } else {
      setFilteredData(sourceData);
    }

  }, [searchQuery, entitiesTab, classHierarchy, objectProperties, objectPropertyHierarchy, dataProperties, dataPropertyHierarchy, annotationProperties, individuals, datatypes]);

  useEffect(() => {
    pluginManager.registerPlugin(SWRLPlugin);
    pluginManager.registerPlugin(ReasoningPlugin);
    if (projectId) {
      const context = {
        projectId,
        apiClient,
        notificationService: {
          success: (message: string) => console.log('✅', message),
          error: (message: string) => console.error('❌', message),
          info: (message: string) => console.info('ℹ️', message)
        }
      };
      pluginManager.setContext(context);
      pluginManager.activatePlugin('swrl-tab');
      pluginManager.activatePlugin('reasoning-graph');
    }
  }, [projectId]);

  // #endregion

  // #region Event Handlers
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

  const toggleNode = useCallback(async (nodeId: string) => {
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

  const updateItemInState = useCallback((updatedItem: SelectableItem) => {
    const updateRecursively = (items: SelectableItem[]): SelectableItem[] => {
      return items.map(item => {
        if (item.id === updatedItem.id) return updatedItem;
        const treeNode = item as TreeNode;
        if (treeNode.children) {
          return { ...item, children: updateRecursively(treeNode.children) };
        }
        return item;
      });
    };

    if (selectedItem?.id === updatedItem.id) {
      setSelectedItem(updatedItem);
    }

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
  }, [entitiesTab, selectedItem]);

  // Draft auto-save: Mark changes as unsaved
  const markAsUnsaved = useCallback(() => {
    setHasUnsavedChanges(true);
    
    // Clear existing timer
    if (draftTimerRef.current) {
      clearTimeout(draftTimerRef.current);
    }
    
    // Auto-save draft to localStorage after 2 seconds of inactivity
    draftTimerRef.current = setTimeout(() => {
      if (projectId) {
        console.log('[Dashboard] Auto-saving draft to localStorage...');
        const draft = {
          projectId,
          classHierarchy,
          objectProperties,
          dataProperties,
          annotationProperties,
          individuals,
          datatypes,
          metadata,
          timestamp: Date.now()
        };
        localStorage.setItem(`draft_${projectId}`, JSON.stringify(draft));
        console.log('[Dashboard] Draft saved to localStorage');
      }
    }, 2000);
  }, [projectId, classHierarchy, objectProperties, dataProperties, annotationProperties, individuals, datatypes, metadata]);

  // Save changes to backend
  const handleSave = useCallback(async () => {
    if (!projectId || isSaving) return;
    
    try {
      setIsSaving(true);
      console.log('[Dashboard] Saving changes to backend...');
      
      // Export current ontology state
      const response = await apiClient.post(`/api/ontology/save/${projectId}`, {
        classHierarchy,
        objectProperties,
        dataProperties,
        annotationProperties,
        individuals,
        datatypes,
        metadata
      });
      
      setHasUnsavedChanges(false);
      
      // Clear draft from localStorage
      localStorage.removeItem(`draft_${projectId}`);
      
      notificationService.success('Saved', `Ontology "${projectId}" saved successfully`);
      console.log('[Dashboard] Save complete');
    } catch (error) {
      console.error('[Dashboard] Save failed:', error);
      notificationService.error('Save Failed', 'Could not save changes. Please try again.');
    } finally {
      setIsSaving(false);
    }
  }, [projectId, isSaving, classHierarchy, objectProperties, dataProperties, annotationProperties, individuals, datatypes, metadata]);

  // Switch to a different file (with unsaved changes check)
  const handleSwitchFile = useCallback((newProjectId: string) => {
    const switchFile = () => {
      console.log('[Dashboard] Switching to file:', newProjectId);
      if (window.vscode) {
        window.vscode.postMessage({
          type: "fileLoaded",
          projectId: newProjectId,
        });
      }
      setHasUnsavedChanges(false);
      localStorage.removeItem(`draft_${projectId}`);
    };

    if (hasUnsavedChanges) {
      // Show confirmation dialog
      setConfirmDialog({
        isOpen: true,
        title: 'Unsaved Changes',
        message: `You have unsaved changes in "${projectId}". Do you want to save before switching?`,
        onConfirm: async () => {
          await handleSave();
          switchFile();
        }
      });
    } else {
      switchFile();
    }
  }, [hasUnsavedChanges, projectId, handleSave]);

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
      await ontologyMutationService.addAnnotation(projectId, selectedItem.id, propertyIri, value);
      
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
          await ontologyMutationService.deleteAnnotation(projectId, selectedItem.id, key, value);
          
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

  const handleAddItem = useCallback(async (type: 'subclass' | 'sibling' | 'individual') => {
    if (!projectId) return;
    
    if (type === 'individual') {
      setCreateIndividualModalOpen(true);
      return;
    }

    if ((type === 'subclass' || type === 'sibling') && !selectedItem) {
      showNotification("Please select an item first.", 'warning');
      return;
    }
    
    // Open dialog instead of using prompt
    setAddClassType(type);
    setAddClassDialogOpen(true);
  }, [projectId, selectedItem]);

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
            // Find parent of selected item
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
            const parent = findParent(classHierarchy, selectedItem.id);
            parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#Thing';
          }

          // Call backend API
          await ontologyMutationService.createClass(projectId, newIri, name, parentIri);

          // Update local state
          const newNode: TreeNode = {
            id: newIri,
            label: name,
            children: undefined,
            hasChildren: false,
            annotations: { 'rdfs:label': name }
          };

          if (type === 'subclass' && selectedItem?.id && !expandedNodes.includes(selectedItem.id)) {
            setExpandedNodes(prev => [...prev, selectedItem.id!]);
          }

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
          if (type === 'sibling' && classHierarchy.some(node => node.id === selectedItem.id)) {
             setClassHierarchy(prev => [...prev, newNode]);
          } else {
             setClassHierarchy(prev => addNodeRecursively(prev));
          }
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
          
          await ontologyMutationService.createObjectProperty(projectId, newIri, name, parentIri);
          
          const newProp: any = {
              id: newIri,
              label: name,
              type: 'ObjectProperty',
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
  }, [projectId, selectedItem, addClassType, entitiesTab, classHierarchy, expandedNodes, metadata]);

  const handleAddIndividual = useCallback((name: string) => {
    const base = (metadata as any)?.ontologyIRI || 'http://example.com/onto';
    const id = `${base}#${name.replace(/\s+/g, '_')}`;
    const newIndividual: Individual = {
      id,
      iri: id,
      label: name,
      annotations: { 'rdfs:label': name },
      types: []
    };
    setIndividuals(prev => [...prev, newIndividual]);
  }, [metadata]);

  const handleMakeSiblingsDisjoint = useCallback(async () => {
    if (!projectId || !selectedItem || entitiesTab !== 'Classes') return;
    
    // Find siblings of selected class
    const findSiblings = (nodes: TreeNode[], targetId: string, parent: TreeNode | null = null): TreeNode[] => {
      for (const node of nodes) {
        if (node.id === targetId && parent && parent.children) {
          // Return all children of parent except the target
          return parent.children.filter((child: TreeNode) => child.id !== targetId);
        }
        if (node.children) {
          const siblings = findSiblings(node.children, targetId, node);
          if (siblings.length > 0) return siblings;
        }
      }
      return [];
    };
    
    const siblings = findSiblings(classHierarchy, selectedItem.id);
    
    if (siblings.length === 0) {
      showNotification('No siblings found for the selected class.', 'info');
      return;
    }
    
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
          await ontologyMutationService.makeSiblingsDisjoint(projectId, classIds);
          
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

  const handleDeleteItem = useCallback(async () => {
    if (!selectedItem || !projectId) return;
    
    // Show confirm dialog instead of using confirm()
    setConfirmDialog({
      isOpen: true,
      title: 'Delete Item',
      message: `Are you sure you want to delete "${selectedItem.label}"? This action cannot be undone.`,
      onConfirm: async () => {
        try {
          // Call backend API based on entity type
          switch (entitiesTab) {
            case 'Classes':
              await ontologyMutationService.deleteClass(projectId, selectedItem.id);
              break;
            case 'Individuals':
              await ontologyMutationService.deleteIndividual(projectId, selectedItem.id);
              break;
            case 'ObjectProperties':
              await ontologyMutationService.deleteObjectProperty(projectId, selectedItem.id);
              break;
            // Add other entity types as needed
          }

          // Update local state
          switch (entitiesTab) {
            case 'Classes': {
              const removeNodeRecursively = (nodes: TreeNode[], id: string): TreeNode[] =>
                nodes
                  .filter(node => node.id !== id)
                  .map(node => node.children ? { ...node, children: removeNodeRecursively(node.children, id) } : node);
              setClassHierarchy(prev => removeNodeRecursively(prev, selectedItem.id));
              break;
            }
            case 'Individuals':
              setIndividuals(prev => prev.filter(ind => ind.id !== selectedItem.id));
              break;
            case 'ObjectProperties':
              setObjectProperties(prev => prev.filter(p => p.id !== selectedItem.id));
              const removeOpRecursively = (nodes: any[], id: string): any[] =>
                nodes
                  .filter(node => node.id !== id)
                  .map(node => node.children ? { ...node, children: removeOpRecursively(node.children, id) } : node);
              setObjectPropertyHierarchy(prev => removeOpRecursively(prev, selectedItem.id));
              break;
            case 'DataProperties':
              setDataProperties(prev => prev.filter(p => p.id !== selectedItem.id));
              break;
        case 'AnnotationProperties':
          setAnnotationProperties(prev => prev.filter(p => p.id !== selectedItem.id));
          break;
            case 'Datatypes':
              setDatatypes(prev => prev.filter(d => d.id !== selectedItem.id));
              break;
          }
          setSelectedItem(null);
          showNotification(`"${selectedItem.label}" deleted successfully!`, 'info');
        } catch (error) {
          console.error('Failed to delete item:', error);
          showNotification('Failed to delete item. See console for details.', 'error');
        }
      }
    });
  }, [selectedItem, entitiesTab, projectId]);

  const handleGraphNodeClick = useCallback((nodeId: string) => {
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

  // Keyboard shortcuts (Protégé-style) - must be after handleAddItem and handleDeleteItem
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Only handle shortcuts when Entities tab is active and Classes tab is selected
      if (mainTab !== 'Entities' || entitiesTab !== 'Classes') return;
      
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
  }, [mainTab, entitiesTab, handleAddItem, handleDeleteItem]);

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
  const renderMainContent = () => {
    switch (mainTab) {
      case 'SPARQL':
        return <SparqlQueryEditor projectId={projectId!} prefixes={(metadata as any)?.prefixes || []} />;
      case 'Graph': {
        const reasoningPlugin = pluginManager.getPlugin('reasoning-graph');
        if (reasoningPlugin && pluginManager.isPluginActive('reasoning-graph') && projectId) {
          const PluginComponent = reasoningPlugin.component;
          return <PluginComponent projectId={projectId} onNodeClick={handleGraphNodeClick} context={pluginManager.getContext()!} />;
        }
        return <div className="p-4">Enable the Graph View from the Reasoner menu.</div>;
      }
      case 'SWRL': {
        const swrlPlugin = pluginManager.getPlugin('swrl-tab');
        if (swrlPlugin && pluginManager.isPluginActive('swrl-tab') && pluginManager.getContext()) {
          const PluginComponent = swrlPlugin.component;
          return <PluginComponent projectId={projectId!} context={pluginManager.getContext()} />;
        }
        return <div className="p-4">Enable the SWRL tab from the Window menu.</div>;
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
                    <table className="w-full text-left text-xs">
                      <thead>
                        <tr className="border-b">
                          <th className="p-1.5 font-semibold">Prefix</th>
                          <th className="p-1.5 font-semibold">Namespace</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(metadata as any)?.prefixes?.map((p: { prefix: string; namespace: string }) => (
                          <tr key={p.prefix} className="border-b hover:bg-gray-50">
                            <td className="p-1.5 font-mono">{p.prefix}</td>
                            <td className="p-1.5 text-blue-700">{p.namespace}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
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
                <textarea value={dlQuery} onChange={e => setDlQuery(e.target.value)} className="w-full h-24 border p-1 font-mono text-sm focus:ring-1 focus:ring-purple-500"></textarea>
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
    setIsClassSelectorOpen(true);
  };

  const handleOpenPropertySelector = (target: 'subProperty' | 'inverse' | 'disjoint' | 'equivalent') => {
    setSelectorTarget(target);
    setIsPropertySelectorOpen(true);
  };

  const handleClassSelected = async (node: TreeNode) => {
    if (!selectedItem || !projectId || !selectorTarget) return;

    try {
      switch (selectorTarget) {
        case 'domain':
          await ontologyMutationService.addPropertyDomain(projectId, selectedItem.id, node.id);
          updateItemInState({ ...selectedItem, domains: [...((selectedItem as Property).domains || []), node.id] });
          break;
        case 'range':
          await ontologyMutationService.addPropertyRange(projectId, selectedItem.id, node.id);
          updateItemInState({ ...selectedItem, ranges: [...((selectedItem as Property).ranges || []), node.id] });
          break;
      }
    } catch (error) {
      console.error(`Failed to add ${selectorTarget}`, error);
    } finally {
      setIsClassSelectorOpen(false);
      setSelectorTarget(null);
    }
  };

  const handlePropertySelected = async (node: TreeNode) => {
    if (!selectedItem || !projectId || !selectorTarget) return;

    try {
      switch (selectorTarget) {
        case 'subProperty':
          await ontologyMutationService.addSubPropertyOf(projectId, selectedItem.id, node.id);
          updateItemInState({ ...selectedItem, superProperties: [...((selectedItem as Property).superProperties || []), node.id] });
          break;
        case 'inverse':
          await ontologyMutationService.addInverseProperty(projectId, selectedItem.id, node.id);
          updateItemInState({ ...selectedItem, inverseProperties: [...((selectedItem as Property).inverseProperties || []), node.id] });
          break;
        case 'disjoint':
          await ontologyMutationService.addDisjointProperty(projectId, selectedItem.id, node.id);
          updateItemInState({ ...selectedItem, disjointProperties: [...((selectedItem as Property).disjointProperties || []), node.id] });
          break;
        case 'equivalent':
           await ontologyMutationService.addEquivalentProperty(projectId, selectedItem.id, node.id);
           // update state if we had a field for it
           break;
      }
    } catch (error) {
      console.error(`Failed to add ${selectorTarget}`, error);
    } finally {
      setIsPropertySelectorOpen(false);
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
    SPARQL: { label: "SPARQL Query", icon: DatabaseZap },
    SWRL: { label: "SWRL Rules", icon: Code },
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

  if (!projectId) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <div className="text-center p-8">
          <div className="w-20 h-20 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6">
            <FileText size={40} className="text-white" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-3">Welcome to OntoCode</h2>
          <div className="flex items-center justify-center gap-2 text-purple-600">
            <Loader2 size={20} className="animate-spin" />
            <p className="text-sm">Waiting for an ontology file to be opened...</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <>
      <LoadingDialog isOpen={isInitialLoading} />
      <CreateIndividualModal isOpen={isCreateIndividualModalOpen} onClose={() => setCreateIndividualModalOpen(false)} onCreate={handleAddIndividual} />
      <AddClassDialog 
        isOpen={isAddClassDialogOpen} 
        onClose={() => setAddClassDialogOpen(false)} 
        onCreate={handleCreateClass}
        type={addClassType}
      />
      <AddAnnotationDialog 
        isOpen={isAddAnnotationDialogOpen} 
        onClose={() => setAddAnnotationDialogOpen(false)} 
        onAdd={handleAnnotationDialogAdd}
        availableProperties={annotationProperties}
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
        title={confirmDialog.title}
        message={confirmDialog.message}
      />

      <div className="h-screen bg-gray-50 flex flex-col text-sm max-h-screen">
        <TopMenuBar
          onToggleSwrlTab={toggleSwrlTab}
          isSwrlVisible={visibleMainTabs.includes('SWRL')}
          onToggleGraphTab={toggleGraphTab}
          isGraphVisible={visibleMainTabs.includes('Graph')}
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
          onOpenDialog={() => setShowOpenDialog(true)}
        />

        <div className="bg-white border-b border-gray-200 flex-shrink-0">
          <div className="flex items-center justify-between px-4 h-10">
            <div className="flex items-center">
              {visibleMainTabs.map((tabId) => {
                const tab = ALL_MAIN_TABS[tabId];
                if (!tab) return null;
                return (
                  <button
                    key={tabId}
                    className={`flex items-center gap-2 px-3 h-full text-xs font-medium border-b-2 -mb-px ${mainTab === tabId ? "text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent"}`}
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
                  onClick={() => setShowProjectSelector(true)}
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
            <div className="flex items-center">
              {entitiesTabs.map((tab) => (
                <button
                  key={tab.id}
                  title={tab.label}
                  className={`flex items-center gap-2 px-3 py-1 text-xs font-medium border-t-2 mt-px ${entitiesTab === tab.id ? "bg-white text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent hover:bg-gray-200 rounded-t"}`}
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
                    onDeleteAnnotation={handleDeleteAnnotation}
                    onAddDomainClick={() => handleOpenClassSelector('domain')}
                    onAddRangeClick={() => handleOpenClassSelector('range')}
                    onAddSubPropertyClick={() => handleOpenPropertySelector('subProperty')}
                    onAddInverseClick={() => handleOpenPropertySelector('inverse')}
                    onAddDisjointClick={() => handleOpenPropertySelector('disjoint')}
                    onAddEquivalentClick={() => handleOpenPropertySelector('equivalent')}
                    classHierarchy={classHierarchy}
                    objectProperties={objectProperties}
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
      <ClassSelectorDialog
        isOpen={isClassSelectorOpen}
        onClose={() => {
          setIsClassSelectorOpen(false);
          setSelectorTarget(null);
        }}
        onSelect={handleClassSelected}
        classHierarchy={classHierarchy}
        title={`Select ${selectorTarget === 'domain' ? 'Domain' : 'Range'} Class`}
      />

      {/* Property Selector Dialog */}
      <PropertySelectorDialog
        isOpen={isPropertySelectorOpen}
        onClose={() => {
          setIsPropertySelectorOpen(false);
          setSelectorTarget(null);
        }}
        onSelect={handlePropertySelected}
        propertyHierarchy={objectPropertyHierarchy}
        title={`Select ${selectorTarget ? selectorTarget.charAt(0).toUpperCase() + selectorTarget.slice(1) : 'Property'}`}
      />

      {/* Project Selector Modal */}
      {showProjectSelector && (
        <ProjectSelector
          projects={availableProjects}
          onSelectProject={handleProjectSelection}
          onClose={() => setShowProjectSelector(false)}
        />
      )}

      {/* Loading Choice Dialog */}
      <LoadingChoiceDialog
        isOpen={showLoadingChoice}
        projectName={loadingProjectName}
        onWait={handleWaitForLoading}
        onContinue={handleContinueWorking}
      />

      {/* Collaboration Panel */}
      <CollaborationPanel />

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
    </>
  );
};

export default Dashboard;
