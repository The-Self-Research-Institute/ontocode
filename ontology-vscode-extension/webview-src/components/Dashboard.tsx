// src/Dashboard.tsx
import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight, ChevronDown, Settings, Search, FileText, Eye, Database, Tag, Share2, List, Code, Loader2, Package, Check, Trash2, PlusCircle, User, Type, GitBranch, Binary, LogOut, Play, DatabaseZap,
  Download
} from "lucide-react";
import apiClient from "../services/apiClient";
import ontologyMutationService from "../services/ontologyMutationService";
import { pluginManager } from '../plugins/PluginSystem';
import { SWRLPlugin, ReasoningPlugin } from '../plugins/PluginRegistry';
import type { TreeNode, Property, Individual, OntologyMetadata, SelectableItem, AnnotationProperty, Datatype } from '../types';
import { useAuth } from '../custom-hook/useAuth';
import EntityHierarchy from './EntityHierarchy';
import ClassEditor from './details/ClassEditor';
import PropertyEditor from './details/PropertyEditor';
import IndividualEditor from './details/IndividualEditor';
import { Panel, AnnotationsDisplay } from './details/common';
import SparqlQueryEditor from './SparqlQueryEditor';
import { ProjectSelector } from './ProjectSelector';

type TopLevelClass = TreeNode & { hasChildren: boolean };

type FileInfo = {
  id: string;
  filename: string;
  contentType?: string | null;
  length: number;
  uploadDate: string; // ISO
  projectId?: string | null;
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

const TopMenuBar = ({
  onToggleSwrlTab,
  isSwrlVisible,
  onToggleGraphTab,
  isGraphVisible,
  fileList,
  currentProjectId,
}: {
  onToggleSwrlTab: () => void;
  isSwrlVisible: boolean;
  onToggleGraphTab: () => void;
  isGraphVisible: boolean;
  fileList: FileInfo[];
  currentProjectId: string | null;
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
        const response = await apiClient.get<{ files: FileInfo[] }>(url);
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
  const menuItems = ['File', 'Edit', 'View', 'Reasoner', 'Tools', 'Window', 'Download', 'Help'];

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
                } else {
                  setOpenMenu(openMenu === item ? null : item);
                }
              }}
              className="px-3 py-1 hover:bg-gray-300 rounded-sm"
            >
              {item}
            </button>
            {openMenu === item && (
              <div className={`absolute left-0 mt-1 ${item === 'File' ? 'w-96' : 'w-100'} bg-white border border-gray-300 rounded-md shadow-lg z-20`}>
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
                  <div className="p-3 space-y-1">
                    <div className="p-2 border-b border-gray-200 flex-shrink-0">
                      <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                        <input
                          type="text"
                          placeholder={`Search Files...`}
                          value={searchFile}
                          onChange={(e) => onSearchFileChange(e.target.value)}
                          className="w-full pl-8 pr-3 py-1.5 border border-gray-300 rounded-md focus:ring-1 focus:ring-purple-500 text-sm bg-white"
                        />
                      </div>
                    </div>
                    {isLoading && (
                      <div className="px-3 py-1 text-gray-500 text-xs flex items-center gap-2">
                        <Loader2 size={14} className="animate-spin" /> Searching...
                      </div>
                    )}
                    {displayedFiles?.length > 0
                      ? displayedFiles.map((file) => (
                          <div 
                            className="flex justify-between items-center gap-2 hover:bg-blue-50 px-2 py-1 rounded cursor-pointer transition-colors" 
                            key={file.id}
                            onClick={() => {
                              if (window.vscode) {
                                window.vscode.postMessage({
                                  type: "fileLoaded",
                                  projectId: file.filename.slice(0, -4),
                                });
                                setOpenMenu(null);
                              }
                            }}
                          >
                            <span
                              className="truncate min-w-0 flex-1 text-black"
                              title={`Click to load: ${file.filename}`}
                            >
                              {file.filename}
                            </span>
                          </div>
                        ))
                      : !isLoading && <div className="px-3 py-1 text-black">No Files</div>}
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

const AddClassDialog = ({ 
  isOpen, 
  onClose, 
  onCreate,
  type 
}: { 
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
  type: 'subclass' | 'sibling';
}) => {
  const [name, setName] = useState('');
  
  if (!isOpen) return null;

  const handleCreate = () => {
    if (name.trim()) {
      onCreate(name.trim());
      setName('');
      onClose();
    } else {
      showNotification("Class name cannot be empty.", 'warning');
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleCreate();
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">
          Create New {type === 'subclass' ? 'Subclass' : 'Sibling Class'}
        </h3>
        <div className="space-y-4 text-sm">
          <div>
            <label className="font-medium text-black block mb-2">Class Name</label>
            <input 
              type="text" 
              value={name} 
              onChange={e => setName(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="Enter class name" 
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              autoFocus
            />
          </div>
          <div>
            <label className="font-medium text-black block mb-2">IRI</label>
            <input 
              type="text" 
              disabled 
              value="(auto-generated from ontology IRI + class name)" 
              className="w-full px-3 py-2 border border-gray-200 bg-gray-50 rounded-md text-gray-500 text-xs" 
            />
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button 
            onClick={onClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={handleCreate} 
            className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            Create
          </button>
        </div>
      </div>
    </div>
  );
};

const CreateIndividualModal = ({ isOpen, onClose, onCreate }: { isOpen: boolean, onClose: () => void, onCreate: (name: string) => void }) => {
  const [name, setName] = useState('');
  if (!isOpen) return null;

  const handleCreate = () => {
    if (name.trim()) {
      onCreate(name.trim());
      setName('');
      onClose();
    } else {
      showNotification("Name cannot be empty.", 'warning');
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Create a new Named Individual</h3>
        <div className="space-y-4 text-sm">
          <div>
            <label className="font-medium text-gray-700">Name</label>
            <input type="text" value={name} onChange={e => setName(e.target.value)} placeholder="Short name or full IRI" className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500" />
          </div>
          <div>
            <label className="font-medium text-gray-700">IRI</label>
            <input type="text" disabled value="(auto-generated)" className="mt-1 w-full px-3 py-2 border border-gray-200 bg-gray-50 rounded-md text-gray-500" />
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm bg-gray-200 text-gray-800 rounded-md hover:bg-gray-300">Cancel</button>
          <button onClick={handleCreate} className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700">OK</button>
        </div>
      </div>
    </div>
  );
};

const AddAnnotationDialog = ({ 
  isOpen, 
  onClose, 
  onAdd, 
  availableProperties 
}: { 
  isOpen: boolean;
  onClose: () => void;
  onAdd: (propertyIri: string, value: string, datatype?: string) => void;
  availableProperties: AnnotationProperty[];
}) => {
  const [selectedProperty, setSelectedProperty] = useState('');
  const [customProperty, setCustomProperty] = useState('');
  const [value, setValue] = useState('');
  const [datatype, setDatatype] = useState('xsd:string');
  const [searchQuery, setSearchQuery] = useState('');
  const [useCustom, setUseCustom] = useState(false);

  if (!isOpen) return null;

  // Common annotation properties
  const commonProperties = [
    { iri: 'http://www.w3.org/2000/01/rdf-schema#label', label: 'rdfs:label' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#comment', label: 'rdfs:comment' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#seeAlso', label: 'rdfs:seeAlso' },
    { iri: 'http://www.w3.org/2000/01/rdf-schema#isDefinedBy', label: 'rdfs:isDefinedBy' },
  ];

  // Merge with available properties from ontology
  const allProperties = [
    ...commonProperties,
    ...availableProperties.map(p => ({ iri: p.id, label: p.label || p.id.split('#').pop() || p.id }))
  ];

  // Filter properties based on search
  const filteredProperties = allProperties.filter(p => 
    p.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.iri.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const handleAdd = () => {
    const propertyIri = useCustom ? customProperty : selectedProperty;
    if (!propertyIri.trim() || !value.trim()) {
      showNotification("Property and value are required.", 'warning');
      return;
    }
    onAdd(propertyIri, value, datatype);
    // Reset form
    setSelectedProperty('');
    setCustomProperty('');
    setValue('');
    setDatatype('xsd:string');
    setSearchQuery('');
    setUseCustom(false);
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-2xl w-full mx-4 max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">Add Annotation</h3>
        
        <div className="space-y-4 text-sm">
          {/* Property Selection */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <label className="font-medium text-black">Annotation Property</label>
              <label className="flex items-center gap-1 text-xs text-black">
                <input 
                  type="checkbox" 
                  checked={useCustom} 
                  onChange={(e) => setUseCustom(e.target.checked)}
                  className="w-3 h-3"
                />
                Custom IRI
              </label>
            </div>
            
            {useCustom ? (
              <input
                type="text"
                value={customProperty}
                onChange={e => setCustomProperty(e.target.value)}
                placeholder="http://example.com/ontology#customProperty"
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              />
            ) : (
              <>
                <div className="relative mb-2">
                  <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                  <input
                    type="text"
                    placeholder="Search properties..."
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    className="w-full pl-8 pr-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
                  />
                </div>
                <select
                  value={selectedProperty}
                  onChange={e => setSelectedProperty(e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 max-h-40 text-black"
                  size={Math.min(filteredProperties.length + 1, 8)}
                >
                  <option value="" className="text-black">-- Select a property --</option>
                  {filteredProperties.map(prop => (
                    <option key={prop.iri} value={prop.iri} title={prop.iri} className="text-black">
                      {prop.label}
                    </option>
                  ))}
                </select>
              </>
            )}
          </div>

          {/* Value Input */}
          <div>
            <label className="font-medium text-black block mb-2">Value</label>
            <textarea
              value={value}
              onChange={e => setValue(e.target.value)}
              placeholder="Enter annotation value..."
              rows={4}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
            />
          </div>

          {/* Datatype Selection */}
          <div>
            <label className="font-medium text-black block mb-2">Datatype</label>
            <select
              value={datatype}
              onChange={e => setDatatype(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
            >
              <option value="xsd:string" className="text-black">xsd:string</option>
              <option value="xsd:integer" className="text-black">xsd:integer</option>
              <option value="xsd:decimal" className="text-black">xsd:decimal</option>
              <option value="xsd:boolean" className="text-black">xsd:boolean</option>
              <option value="xsd:date" className="text-black">xsd:date</option>
              <option value="xsd:dateTime" className="text-black">xsd:dateTime</option>
              <option value="xsd:anyURI" className="text-black">xsd:anyURI</option>
              <option value="" className="text-black">Plain literal (no datatype)</option>
            </select>
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button 
            onClick={onClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={handleAdd} 
            className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            Add
          </button>
        </div>
      </div>
    </div>
  );
};

// #endregion

// #region Details Panel
const DetailsPanel = ({ selectedItem, entitiesTab, activeTheme, projectId, onUpdate, onAddAnnotation, onDeleteAnnotation }: {
  selectedItem: SelectableItem | null;
  entitiesTab: string;
  activeTheme?: string;
  projectId: string | null;
  onUpdate: (item: SelectableItem) => void;
  onAddAnnotation: () => void;
  onDeleteAnnotation: (key: string) => void;
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
        {...sharedProps}
      />;
    case 'ObjectProperties':
    case 'DataProperties':
      return <PropertyEditor item={selectedItem as Property} onUpdate={onUpdate} {...sharedProps} />;
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
  const [activeOntologySubTab, setActiveOntologySubTab] = useState('prefixes');
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  const [isAddAnnotationDialogOpen, setAddAnnotationDialogOpen] = useState(false);
  const [isAddClassDialogOpen, setAddClassDialogOpen] = useState(false);
  const [addClassType, setAddClassType] = useState<'subclass' | 'sibling'>('subclass');
  
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
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<AnnotationProperty[]>([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<Datatype[]>([]);

  const [filteredData, setFilteredData] = useState<SelectableItem[]>([]);
  const [listOfFiles, setListOfFiles] = useState<FileInfo[]>([]);

  const [visibleMainTabs, setVisibleMainTabs] = useState(['ActiveOntology', 'Entities', 'IndividualsByClass', 'DLQuery', 'SPARQL']);
  // #endregion

  // #region Data Fetching and Initialization
  const toggleSwrlTab = useCallback(() => {
    setVisibleMainTabs(prev => prev.includes('SWRL') ? prev.filter(t => t !== 'SWRL') : [...prev, 'SWRL']);
  }, []);

  const toggleGraphTab = useCallback(() => {
    setVisibleMainTabs(prev => prev.includes('Graph') ? prev.filter(t => t !== 'Graph') : [...prev, 'Graph']);
  }, []);

  const fetchData = useCallback(async (currentProjectId: string) => {
    setIsInitialLoading(true);
    setSelectedItem(null);
    setSearchQuery("");

    try {
      const [metadataRes, topLevelRes, propertiesRes, individualsRes, annotationPropsRes, datatypesRes, filesRes] = await Promise.all([
        apiClient.get<any>(`/api/ontology/metadata/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/classes/top-level/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/properties/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/individuals/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/annotation-properties/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/datatypes/${currentProjectId}`),
        apiClient.get<any>(`/api/ontology/files`),
      ]);
      
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
      console.log("Classes response:", topLevelRes);
      console.log("Classes response keys:", Object.keys(topLevelRes || {}));
      console.log("topLevelRes?.classes:", topLevelRes?.classes);
      console.log("topLevelRes?.data?.classes:", topLevelRes?.data?.classes);
      console.log("topLevelRes?.data:", topLevelRes?.data);
      const classes = Array.isArray(topLevelRes?.classes) ? topLevelRes.classes :
                     Array.isArray(topLevelRes?.data?.classes) ? topLevelRes.data.classes :
                     Array.isArray(topLevelRes?.data) ? topLevelRes.data : [];
      console.log("Extracted classes:", classes);
      console.log("Extracted classes length:", classes.length);
      const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
        ...c,
        children: c.hasChildren ? undefined : undefined, // Use undefined to trigger lazy loading
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

      // Handle properties response
      console.log("Properties response:", propertiesRes);
      const allProps = Array.isArray(propertiesRes?.data) ? propertiesRes.data : 
                       Array.isArray(propertiesRes?.properties) ? propertiesRes.properties : 
                       Array.isArray(propertiesRes) ? propertiesRes : [];
      console.log("All props after extraction:", allProps);
      setObjectProperties(allProps.filter((p: Property) => p.type === "ObjectProperty"));
      setDataProperties(allProps.filter((p: Property) => p.type === "DatatypeProperty"));

      // Handle other responses with fallbacks
      setIndividuals(Array.isArray(individualsRes?.data) ? individualsRes.data : 
                    Array.isArray(individualsRes?.individuals) ? individualsRes.individuals : []);
      setAnnotationProperties(Array.isArray(annotationPropsRes?.data) ? annotationPropsRes.data :
                              Array.isArray(annotationPropsRes?.annotationProperties) ? annotationPropsRes.annotationProperties : []);
      setDatatypes(Array.isArray(datatypesRes?.data) ? datatypesRes.data :
                  Array.isArray(datatypesRes?.datatypes) ? datatypesRes.datatypes : []);
      setListOfFiles(Array.isArray(filesRes?.files) ? filesRes.files :
                    Array.isArray(filesRes?.data?.files) ? filesRes.data.files : []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
    } finally {
      setIsInitialLoading(false);
    }
  }, []);

  const fetchProjects = useCallback(async () => {
    try {
      const response = await apiClient.get<{ success: boolean; projects: any[] }>('/api/projects');
      if (response.success && response.projects) {
        setAvailableProjects(response.projects);
        
        // If no project selected and projects exist, show selector
        if (!projectId && response.projects.length > 0) {
          setShowProjectSelector(true);
        }
      }
    } catch (error) {
      console.error("Failed to fetch projects:", error);
    }
  }, [projectId]);

  const handleProjectSelection = useCallback((selectedProjectId: string) => {
    setProjectId(selectedProjectId);
    setShowProjectSelector(false);
    fetchData(selectedProjectId);
  }, [fetchData]);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
      const owlThingId = classHierarchy[0].id;
      console.log('[Dashboard] Class hierarchy loaded, owl:Thing has', classHierarchy[0].children?.length || 0, 'top-level children');
      if (!expandedNodes.includes(owlThingId)) {
        console.log('[Dashboard] Auto-expanding owl:Thing');
        setExpandedNodes(prev => [...prev, owlThingId]);
      }
    }
  }, [classHierarchy, expandedNodes]);

  // Fetch projects on mount when no projectId is set
  useEffect(() => {
    if (!projectId) {
      fetchProjects();
    }
  }, [projectId, fetchProjects]);

  const pollProcessingStatus = useCallback((projectIdToPoll: string) => {
    setIsInitialLoading(true);

    const intervalId = setInterval(async () => {
      try {
        const response = await apiClient.get(`/api/ontology/status/${projectIdToPoll}`);
        // Backend wraps response in {success: true, data: {status: "COMPLETED", ...}}
        const statusData = response.data?.data || response.data || response;
        
        console.log('[Dashboard] Poll status response:', statusData);

        if (statusData?.status === 'COMPLETED') {
          console.log('[Dashboard] Processing complete, loading data...');
          clearInterval(intervalId);
          await fetchData(projectIdToPoll);
        } else if (statusData?.status === 'ERROR') {
          console.log('[Dashboard] Processing error:', statusData.statusMessage);
          clearInterval(intervalId);
          setIsInitialLoading(false);
          showNotification(`Processing failed: ${statusData.statusMessage}`, 'error');
        } else {
          console.log('[Dashboard] Still processing, status:', statusData?.status);
        }
      } catch (error) {
        console.error('[Dashboard] Failed to poll for status:', error);
        clearInterval(intervalId);
        setIsInitialLoading(false);
        showNotification('Failed to check processing status. Please try reloading.', 'error');
      }
    }, 2000);

    return () => clearInterval(intervalId);
  }, [fetchData, setIsInitialLoading]);

  // Send 'webviewReady' to extension when mounted
  useEffect(() => {
    if (window.vscode) {
      window.vscode.postMessage({ type: 'webviewReady' });
    }
  }, []);

  useEffect(() => {
    let cleanupPolling = () => { };
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      console.log('[Dashboard] Received message:', message.type, message);
      switch (message.type) {
        case "showLoading":
          setIsInitialLoading(true);
          break;
        case "fileReady":
          // Always load the new file, regardless of current state
          console.log('[Dashboard] Loading new project:', message.projectId);
          setProjectId(message.projectId);
          setSelectedItem(null); // Clear selection
          cleanupPolling = pollProcessingStatus(message.projectId);
          break;
        case "fileLoaded":
          // Handle file selection from File menu
          console.log('[Dashboard] File selected from menu:', message.projectId);
          const newProjectId = message.projectId;
          setProjectId(newProjectId);
          setSelectedItem(null); // Clear selection
          setIsInitialLoading(true);
          // Directly fetch data if already processed, otherwise poll
          fetchData(newProjectId).catch(() => {
            console.log('[Dashboard] Fetch failed, starting polling for:', newProjectId);
            cleanupPolling = pollProcessingStatus(newProjectId);
          });
          break;
        case "loadingFailed":
          setIsInitialLoading(false);
          console.error("Loading failed:", message.error);
          showNotification(`Loading failed: ${message.error}`, 'error');
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
      cleanupPolling();
    };
  }, [pollProcessingStatus, toggleSwrlTab, visibleMainTabs, fetchData]);

  useEffect(() => {
    let sourceData: SelectableItem[] = [];
    switch (entitiesTab) {
      case "Classes": sourceData = classHierarchy; break;
      case "ObjectProperties": sourceData = objectProperties; break;
      case "DataProperties": sourceData = dataProperties; break;
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
          const treeNode = item as TreeNode;
          if (treeNode.children) {
            const childResults = filterRecursively(treeNode.children);
            if (childResults.length > 0) {
              results.push({ ...item, children: childResults as any });
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

  }, [searchQuery, entitiesTab, classHierarchy, objectProperties, dataProperties, annotationProperties, individuals, datatypes]);

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
      console.log(node, 'here')
      // Load children if node is expandable but children are not loaded yet (undefined or empty)
      if (node && node.hasChildren && (!node.children || node.children.length === 0)) {
        console.log(`Node ${nodeId} needs children loaded`);
        await loadChildren(nodeId);
      }
      setExpandedNodes(prev => [...prev, nodeId]);
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
      showNotification("Please select a class first.", 'warning');
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

      setClassHierarchy(prev => addNodeRecursively(prev));
      showNotification(`Class "${name}" created successfully!`, 'info');
    } catch (error) {
      console.error('Failed to create class:', error);
      showNotification('Failed to create class. See console for details.', 'error');
    }
  }, [selectedItem, expandedNodes, metadata, projectId, classHierarchy, addClassType]);

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
                <h3 className="text-xs font-semibold text-gray-700 mb-2">Annotations</h3>
                <AnnotationsDisplay annotations={(metadata as any)?.annotations} onDelete={() => showNotification('Cannot delete ontology annotation here.', 'warning')} />
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
          currentProjectId={projectId}
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
              />

              <section className="flex-1 overflow-y-auto p-2 bg-slate-200">
                <DetailsPanel
                  selectedItem={selectedItem}
                  entitiesTab={entitiesTab}
                  activeTheme={activeTheme}
                  projectId={projectId}
                  onUpdate={updateItemInState}
                  onAddAnnotation={handleAddAnnotation}
                  onDeleteAnnotation={handleDeleteAnnotation}
                />
              </section>
            </>
          ) : (
            <section className="flex-1 overflow-y-auto bg-white">
              {renderMainContent()}
            </section>
          )}
        </main>
      </div>

      {/* Project Selector Modal */}
      {showProjectSelector && (
        <ProjectSelector
          projects={availableProjects}
          onSelectProject={handleProjectSelection}
          onClose={() => setShowProjectSelector(false)}
        />
      )}
    </>
  );
};

export default Dashboard;
