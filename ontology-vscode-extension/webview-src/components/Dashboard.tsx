import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight, ChevronDown, Settings, Search, FileText, Eye, Database, Tag, Share2, List, Code, Loader2, Package, Check, Trash2, PlusCircle, User, Type, GitBranch, Binary, LogOut, Play, DatabaseZap,
  Download
} from "lucide-react";
import apiClient from "../services/apiClient";
import { pluginManager } from '../plugins/PluginSystem';
import { SWRLPlugin, ReasoningPlugin } from '../plugins/PluginRegistry';
import type { TreeNode, Property, Individual, AnnotationProperty, Datatype, OntologyMetadata, ClassUsage, SelectableItem, OntologyPrefix } from '../types';
import { useAuth } from '../custom-hook/useAuth';
import EntityHierarchy from './EntityHierarchy';
import ClassEditor from './details/ClassEditor';
import PropertyEditor from './details/PropertyEditor';
import IndividualEditor from './details/IndividualEditor';
import { Panel, AnnotationsDisplay } from './details/common';
import SparqlQueryEditor from './SparqlQueryEditor';


type TopLevelClass = TreeNode & { hasChildren: boolean };
type FileInfo = {
  id: string;
  filename: string;
  contentType?: string | null;
  length: number;
  uploadDate: string; // ISO
  projectId?: string | null;
};

type FilesListResponse = {
  success: boolean;
  count: number;
  files: FileInfo[];
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
  projectId,
  handleSaveToDatabase,
  serializeOntology
}: {
  onToggleSwrlTab: () => void;
  isSwrlVisible: boolean;
  onToggleGraphTab: () => void;
  isGraphVisible: boolean;
  fileList: FilesListResponse[];
  projectId: string;
  handleSaveToDatabase: () => void;
  serializeOntology: () => string;
}) => {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [searchFile, setSearchFile] = useState("");
  const [files, setFiles] = useState<FilesListResponse[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const onSearchFileChange = (value: string) => {
    setSearchFile(value);

    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    if (!value.trim()) {
      setFiles(fileList);
      setIsLoading(false);
      return;
    }

    timeoutRef.current = setTimeout(async () => {
      setIsLoading(true);
      try {
        const { data } = await apiClient.get<{ files: FilesListResponse[] }>(`/api/ontology/files`, {
          params: { search: searchFile, caseSensitive: false },
        });

        setFiles(data.files);
      } catch (error) {
        console.error("Failed to fetch data:", error);
      } finally {
        setIsLoading(false);
      }
    }, 1000);
  };

  useEffect(() => {
      if (files.length === 0 && fileList.length > 0) {
        setFiles(fileList);
      }
      
      const handleClickOutside = (event: MouseEvent) => {
          if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
            setOpenMenu(null);
          }
      };
      document.addEventListener("mousedown", handleClickOutside);
      return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [fileList]);

  const displayedFiles = (searchFile ? files : fileList).sort((a, b) => {
    const aCreateDate = a.createDate || a.uploadDate;
    const bCreateDate = b.createDate || b.uploadDate;
    return new Date(bCreateDate).getTime() - new Date(aCreateDate).getTime();
  });

  const menuItems = ['File', 'Edit', 'View', 'Reasoner', 'Tools', 'Window', 'Help', 'Save', 'Download'];

  const downloadFile = (file: FileInfo) => {
    if (window.vscode) {
      window.vscode.postMessage({
        type: "downloadOntology",
        url: `/api/ontology/files/${file.id}/download`,
        filename: `${file.filename}-${file.id}`,
      });
    }
  };

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
                  const currentFile = displayedFiles.find(f => f.projectId === projectId);
                  downloadFile(currentFile);
                } else if (item === "Save") {
                  handleSaveToDatabase();
                  if (window.vscode) {
                    const updatedOntologyString = serializeOntology();
                    window.vscode.postMessage({ type: "triggerFileUpload", content: updatedOntologyString });
                  }
                } else {
                  setOpenMenu(openMenu === item ? null : item);
                }
              }}
              className={`px-3 py-1 hover:bg-gray-300 rounded-sm ${item === "Save" ? "text-green-700 font-bold" : ""}`}
            >
              {item}
            </button>
            {openMenu === item && (
              <div className="absolute left-0 mt-1 bg-white border border-gray-300 rounded-md shadow-lg z-20">
                {item === "Window" ? (
                  <div className="py-1 min-w-[150px]">
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
                  <div className="py-1 min-w-[150px]">
                    <a
                      href="#"
                      onClick={(e) => {
                        e.preventDefault();
                        onToggleGraphTab();
                        setOpenMenu(null);
                      }}
                      className="flex justify-between items-center px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100 whitespace-nowrap"
                    >
                      Graph View {isGraphVisible && <Check size={14} className="text-purple-600" />}
                    </a>
                  </div>
                ) : item === "File" ? (
                  <div className="space-y-1 min-w-[270px]">
                    <div className="p-3 border-b border-gray-200 flex-shrink-0">
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
                    <div className="max-h-80 overflow-y-auto">
                    {isLoading && (
                      <div className="px-3 py-1.5 text-gray-500 text-xs flex items-center gap-2 bg-blue-50 border-b border-blue-100">
                        <Loader2 size={14} className="animate-spin" /> Searching...
                      </div>
                    )}
                    {displayedFiles?.length > 0
                      ? displayedFiles.map((file) => (
                          <div className={`px-3 py-2 flex justify-between items-center gap-2 ${projectId === file.filename.slice(0,-4) ? "bg-purple-600 text-white p-4" : ""}`} key={file.id}>
                            <span
                              className="truncate min-w-0 cursor-pointer hover:underline"
                              title={`${file.filename}`}
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
                              {file.filename}
                            </span>
                          </div>
                        ))
                      : !isLoading && <div className="px-3 py-1 text-gray-500">No Files</div>
                      }
                      </div>
                  </div>
                ) : (
                  <div className="p-2 text-xs text-gray-400 min-w-[150px]">No actions available</div>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
    </header>
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
            alert("Name cannot be empty.");
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

// #endregion

// #region Details Panel
const DetailsPanel = ({ selectedItem, entitiesTab, activeTheme, onUpdate, onAddAnnotation, onDeleteAnnotation,onEditAnnotation }: {
    selectedItem: SelectableItem | null;
    entitiesTab: string;
    activeTheme?: string;
    onUpdate: (item: SelectableItem) => void;
    onAddAnnotation: () => void;
    onDeleteAnnotation: (key: string) => void;
    onEditAnnotation: (key: string, value: string) => void;
}) => {
    if (!selectedItem) {
        return (
            <div className="h-full flex flex-col items-center justify-center text-center text-gray-400 p-4">
              <Package size={48} className="mb-4 text-gray-300"/>
              <h3 className="text-lg font-semibold text-gray-600">Ontology Editor</h3>
              <p className="text-sm">Select an entity from the hierarchy panel on the left to view its details and make edits.</p>
            </div>
        );
    }
    
    const sharedProps = {
        onAddAnnotation,
        onDeleteAnnotation,
        onEditAnnotation,
        activeTheme
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
                     <Panel title={`Annotations: ${item.label}`} {...sharedProps}><AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation}/></Panel>
                 </div>
            );
        }
        case 'Datatypes':
            return <Panel title={`Annotations: ${selectedItem.label}`} {...sharedProps}><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation}/></Panel>;
        default:
             return <div className="bg-white rounded-lg border p-4"><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} onEdit={onEditAnnotation}/></div>;
    }
}
// #endregion

const Dashboard = () => {
    // Serialize the main ontology state as JSON (safe default for now)
    // You can replace this with RDF/XML, Turtle, etc. if needed
    function serializeOntology() {
      try {
        return JSON.stringify({
          classes: classHierarchy,
          objectProperties,
          dataProperties,
          annotationProperties,
          individuals,
          datatypes,
          metadata
        }, null, 2);
      } catch (e) {
        return '';
      }
    }

  // #region State
  const { user, logout } = useAuth();
  const [projectId, setProjectId] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [mainTab, setMainTab] = useState("Entities");
  const [entitiesTab, setEntitiesTab] = useState("Classes");
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [isInitialLoading, setIsInitialLoading] = useState(false);
  const [activeOntologySubTab, setActiveOntologySubTab] = useState('prefixes');
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  
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
  
  // Save changes to database - updates the complete ontology file
  const handleSaveToDatabase = useCallback(async () => {
    console.log('[Dashboard] ========== SAVE BUTTON CLICKED ==========');
    console.log('[Dashboard] projectId:', projectId);
    
    if (!projectId) {
      alert('No project loaded');
      console.log('[Dashboard] Save aborted - no projectId');
      return;
    }

    try {
      console.log('[Dashboard] Starting full ontology save to database...');

      // Extract all classes with their complete data
      const classUpdates: Array<{ 
        iri: string; 
        label?: string;
        annotations: Record<string, string>;
        subClasses?: string[];
      }> = [];
      
      const extractClasses = (nodes: TreeNode[], parentIri?: string) => {
        for (const node of nodes) {
          // Skip built-in OWL classes
          if (node.id && !node.id.includes('owl#Thing') && !node.id.includes('rdf-syntax-ns#')) {
            const classData: any = {
              iri: node.id,
              label: node.label,
              annotations: node.annotations || {}
            };
            
            // Add parent relationship
            if (parentIri) {
              classData.parentIri = parentIri;
            }
            
            // Add subclasses if they exist
            if (node.children && node.children.length > 0) {
              classData.subClasses = node.children
                .filter(child => !child.id.includes('owl#Thing') && !child.id.includes('rdf-syntax-ns#'))
                .map(child => child.id);
            }
            
            classUpdates.push(classData);
          }
          
          if (node.children && node.children.length > 0) {
            extractClasses(node.children, node.id);
          }
        }
      };
      
      extractClasses(classHierarchy);

      // Prepare complete ontology update payload
      const ontologyUpdate = {
        classes: classUpdates,
        objectProperties: objectProperties.map(prop => ({
          iri: prop.iri,
          label: prop.label,
          annotations: prop.annotations || {},
          domain: prop.domain,
          range: prop.range
        })),
        dataProperties: dataProperties.map(prop => ({
          iri: prop.iri,
          label: prop.label,
          annotations: prop.annotations || {},
          domain: prop.domain,
          range: prop.range
        })),
        annotationProperties: annotationProperties.map(prop => ({
          iri: prop.iri,
          label: prop.label,
          annotations: prop.annotations || {}
        })),
        individuals: individuals.map(ind => ({
          iri: ind.iri,
          label: ind.label,
          types: ind.types,
          annotations: ind.annotations || {}
        })),
        datatypes: datatypes.map(dt => ({
          iri: dt.iri,
          label: dt.label
        }))
      };

      console.log(`[Dashboard] Saving complete ontology: ${classUpdates} classes, ${objectProperties.length} object properties, ${dataProperties.length} data properties, ${individuals.length} individuals, ${annotationProperties.length} annotation properties`);
      console.log('[Dashboard] Sample annotation properties:', annotationProperties.slice(0, 2).map(ap => ({ iri: ap.iri, annotations: ap.annotations })));

      // Send to API to update the ontology file in database
      console.log('[Dashboard] Sending PUT request to /api/ontology/update/' + projectId);
      const response = await apiClient.put<{ success: boolean; updated: number; message: string }>(
        `/api/ontology/update/${projectId}`, 
        ontologyUpdate
      );
      
      console.log('[Dashboard] Response received:', response.data);
      
      if (response.data.success) {
        console.log(`Successfully saved ontology to database:\n- ${classUpdates.length} classes\n- ${objectProperties.length} object properties\n- ${dataProperties.length} data properties\n- ${individuals.length} individuals\n- ${annotationProperties.length} annotation properties`);
        
        // Trigger download and save to local file
        console.log('[Dashboard] Triggering downloadAndSaveToLocal');
        window.vscode.postMessage({ 
          type: 'downloadAndSaveToLocal', 
          projectId: projectId 
        });
      } else {
        console.log(`Save completed with warnings: ${response.data.message}`);
      }
    } catch (error: any) {
      console.error('Save to database failed:', error);
      console.error('Error details:', {
        message: error.message,
        response: error.response?.data,
        status: error.response?.status
      });
      
      // Fallback to classes-only update if full update endpoint doesn't exist
      if (error.response?.status === 404) {
        console.log('[Dashboard] Full update endpoint not found, falling back to classes-only update');
        try {
          const classUpdates: Array<{ iri: string; annotations: Record<string, string> }> = [];
          
          const extractClasses = (nodes: TreeNode[]) => {
            for (const node of nodes) {
              if (node.id && !node.id.includes('owl#Thing') && !node.id.includes('rdf-syntax-ns#')) {
                if (node.annotations && Object.keys(node.annotations).length > 0) {
                  classUpdates.push({
                    iri: node.id,
                    annotations: node.annotations
                  });
                }
              }
              if (node.children && node.children.length > 0) {
                extractClasses(node.children);
              }
            }
          };
          
          extractClasses(classHierarchy);
          
          const response = await apiClient.put<{ success: boolean; updated: number; message: string }>(
            `/api/ontology/classes/${projectId}`, 
            classUpdates
          );
          
          if (response.data.success) {
            console.log(`Successfully saved ${response.data.updated} class annotation(s) to database`);
          }
        } catch (fallbackError: any) {
          console.log(`Save failed: ${fallbackError.response?.data?.error || fallbackError.message}`);
        }
      } else {
        console.log(`Save failed: ${error.response?.data?.error || error.message}`);
      }
    }
  }, [projectId, classHierarchy, objectProperties, dataProperties, annotationProperties, individuals, datatypes]);

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
          apiClient.get<OntologyMetadata>(`/api/ontology/metadata/${currentProjectId}`),
          apiClient.get<{ classes: TopLevelClass[] }>(`/api/ontology/classes/top-level/${currentProjectId}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${currentProjectId}`),
          apiClient.get<{ data: Individual[] }>(`/api/ontology/individuals/${currentProjectId}`),
          apiClient.get<{ data: AnnotationProperty[] }>(`/api/ontology/annotation-properties/${currentProjectId}`),
          apiClient.get<{ data: Datatype[] }>(`/api/ontology/datatypes/${currentProjectId}`),
          apiClient.get<{ files: FileInfo[] }>(`/api/ontology/files`),
        ]);
        console.log([metadataRes, topLevelRes, propertiesRes, individualsRes, annotationPropsRes, datatypesRes]);
        const ontologyDoc = ((metadataRes.data) as any).data || metadataRes.data;
        setMetadata(ontologyDoc.metadata || ontologyDoc);
        const { classes } = topLevelRes.data;
        const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({
             ...c,
             children: c.hasChildren ? [] : null,
             hasChildren: c.hasChildren,
             subClassOfAxioms: [{ id: 'sub1', type: 'SubClassOf', definition: 'Thing' }]
        }));
        const owlThingNode: TreeNode = {
             id: "http://www.w3.org/2002/07/owl#Thing",
             label: "owl:Thing",
             children: topLevelNodes,
             annotations: {}
        };
        setClassHierarchy([owlThingNode]);

        const allProps = propertiesRes.data.data || [];
        setObjectProperties(allProps.filter((p: Property) => p.type === "ObjectProperty"));
        setDataProperties(allProps.filter((p: Property) => p.type === "DataProperty"));

        setIndividuals(individualsRes.data.data || []);
        setAnnotationProperties(annotationPropsRes.data.data || []);
        setDatatypes(datatypesRes.data.data || []);
        setListOfFiles(filesRes.data.files || []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
    } finally {
      setIsInitialLoading(false);
    }
  }, []);

  useEffect(() => {
    if (classHierarchy.length > 0 && classHierarchy[0].id === "http://www.w3.org/2002/07/owl#Thing") {
        const owlThingId = classHierarchy[0].id;
        if (!expandedNodes.includes(owlThingId)) {
            setExpandedNodes(prev => [...prev, owlThingId]);
        }
    }
  }, [classHierarchy, expandedNodes]);

  const pollProcessingStatus = useCallback((projectIdToPoll: string) => {
    setIsInitialLoading(true);

    const intervalId = setInterval(async () => {
      try {
        const response = await apiClient.get(`/api/ontology/status/${projectIdToPoll}`);
        
        const statusData = ((response.data) as any)?.data;
        console.log('Poll Status:', statusData?.status);

        if (statusData?.status === 'COMPLETED') {
          clearInterval(intervalId);
          console.log('Processing complete! Fetching all ontology data.');
          await fetchData(projectIdToPoll);
        } else if (statusData?.status === 'ERROR') {
          clearInterval(intervalId);
          setIsInitialLoading(false);
          console.error('Backend processing failed:', statusData.statusMessage);
        } else if (statusData?.status === 'PROCESSING') {
          console.warn('Processing');
        } else if (statusData?.status === 'NOT_FOUND') {
          console.warn('Polling... project not found yet.');
        }

      } catch (error) {
        clearInterval(intervalId);
        setIsInitialLoading(false);
        console.error('Failed to poll for status:', error);
      }
    }, 2000);

    return () => clearInterval(intervalId);

  }, [fetchData, setIsInitialLoading]);

  // FIX: Add a new useEffect to send the 'webviewReady' message on mount
  useEffect(() => {
    if (window.vscode) {
      console.log("React component mounted. Sending 'webviewReady'");
      window.vscode.postMessage({ type: 'webviewReady' });
    }
  }, []); // Empty array ensures this runs only once on mount

  useEffect(() => {
    let cleanupPolling = () => {}; 
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      console.log("Message received from extension:", message); // Better logging
      switch (message.type) {
        case "showLoading": 
          setIsInitialLoading(true); 
          break;
        case "fileReady": 
          console.log("FileReady received, setting projectId:", message.projectId);
          setProjectId(message.projectId); 
          cleanupPolling = pollProcessingStatus(message.projectId); 
          break;
        case "loadingFailed": 
          setIsInitialLoading(false); 
          console.error("Loading failed:", message.error);
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
  }, [pollProcessingStatus, toggleSwrlTab, visibleMainTabs]); // Removed projectId from dependencies

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
                        results.push({ ...item, children: childResults });
                        matches = true; // Also include parent if a child matches
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
      const context = { projectId, apiClient, notificationService: {
          success: (message: string) => console.log('✅', message),
          error: (message: string) => console.error('❌', message),
          info: (message: string) => console.info('ℹ️', message)
      }};
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
        const { data } = await apiClient.get<TopLevelClass[]>(`/api/ontology/classes/children/${projectId}`, { params: { parentIri: nodeId } });
        
        const children = data; 
        
        const updateTree = (nodes: TreeNode[]): TreeNode[] => {
            return nodes.map((n: TreeNode) => {
                if (n.id === nodeId) {
                    return { ...n, children: children.map((c: TopLevelClass) => ({ 
                        ...c, 
                        children: c.hasChildren ? [] : null,
                        hasChildren: c.hasChildren 
                    })) };
                }
                if (n.children) {
                    return { ...n, children: updateTree(n.children) };
                }
                return n;
            });
        };
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
        if (node && node.children && node.children.length === 0) {
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

      switch(entitiesTab) {
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
  
  const handleAddAnnotation = useCallback(() => {
      if (!selectedItem) return;
      const key = prompt("Enter annotation property IRI:", "rdfs:comment");
      if (!key) return;
      const value = prompt(`Enter value for ${key}:`);
      if (value === null) return;

      const updatedAnnotations = { ...selectedItem.annotations, [key]: value };
      const updatedItem = { ...selectedItem, annotations: updatedAnnotations };
      updateItemInState(updatedItem);
  }, [selectedItem, updateItemInState]);
  
  const handleDeleteAnnotation = useCallback((key: string) => {
      if (!selectedItem || !selectedItem.annotations) return;
      // if (!window.confirm(`Are you sure you want to delete the annotation "${key}"?`)) return;

      const remainingAnnotations = { ...selectedItem.annotations };
      delete remainingAnnotations[key];
      const updatedItem = { ...selectedItem, annotations: remainingAnnotations };
      updateItemInState(updatedItem);
  }, [selectedItem, updateItemInState]);

  const handleEditAnnotation = useCallback(async (key: string, value: string) => {
    if (!selectedItem || !projectId) return;
    // Update local state optimistically
    const updatedAnnotations = { ...selectedItem.annotations, [key]: value };
    const updatedItem = { ...selectedItem, annotations: updatedAnnotations };
    updateItemInState(updatedItem);

    // Only send to backend for AnnotationProperties
    if (entitiesTab === 'AnnotationProperties') {
      try {
        await apiClient.put(`/api/ontology/annotation-properties/${projectId}`, {
          id: selectedItem.id,
          iri: selectedItem.iri,
          label: selectedItem.label,
          annotations: updatedAnnotations
        });
      } catch (e) {
        // Optionally show error/rollback
        console.error('Failed to update annotation property', e);
      }
    }
  }, [selectedItem, projectId, entitiesTab, updateItemInState]);

  const handleAddItem = useCallback(async (type: 'subclass' | 'sibling' | 'individual') => {
      if(type === 'individual') {
          setCreateIndividualModalOpen(true);
          return;
      }
      
      if ((type === 'subclass' || type === 'sibling') && !selectedItem) {
          alert("Please select a class first.");
          return;
      }
      const name = prompt("Enter new class name:");
      if (!name) return;
      
      const newNode: TreeNode = {
          id: `${metadata?.ontologyIRI || 'http://example.com/pizza'}#${name.replace(/\s+/g, '_')}`,
          label: name,
          children: [],
          annotations: { 'rdfs:label': name }
      };
      
      if (type === 'subclass' && selectedItem?.id && !expandedNodes.includes(selectedItem.id)) {
          setExpandedNodes(prev => [...prev, selectedItem.id]);
      }

      const addNodeRecursively = (nodes: TreeNode[]): TreeNode[] => {
          return nodes.map(node => {
              if (type === 'subclass' && node.id === selectedItem?.id) {
                  const children = node.children === null ? [newNode] : [...node.children, newNode];
                  return { ...node, children };
              }
              if (type === 'sibling' && node.children?.some((child: TreeNode) => child.id === selectedItem?.id)) {
                  return { ...node, children: [...node.children, newNode] };
              }
              if (node.children) {
                  return { ...node, children: addNodeRecursively(node.children) };
              }
              return node;
          });
      };
      
      setClassHierarchy(prev => addNodeRecursively(prev));

  }, [selectedItem, expandedNodes, metadata]);

   const handleAddIndividual = useCallback((name: string) => {
        const newIndividual: Individual = {
            id: `${metadata?.ontologyIRI || 'http://example.com/pizza'}#${name.replace(/\s+/g, '_')}`,
            iri: `${metadata?.ontologyIRI || 'http://example.com/pizza'}#${name.replace(/\s+/g, '_')}`,
            label: name,
            annotations: { 'rdfs:label': name },
            types: []
        };
        setIndividuals(prev => [...prev, newIndividual]);
  }, [metadata]);


  const handleDeleteItem = useCallback(() => {
    console.log(selectedItem,"selectedItem")

    if (!selectedItem) return;
    if (!confirm(`Are you sure you want to delete "${selectedItem.label}"? This action cannot be undone.`)) return;
    switch (entitiesTab) {
        case 'Classes': {
             const removeNodeRecursively = (nodes: TreeNode[], id: string): TreeNode[] => {
                 return nodes
                     .filter(node => node.id !== id)
                     .map(node => {
                         if (node.children) {
                             return { ...node, children: removeNodeRecursively(node.children, id) };
                         }
                         return node;
                     });
             };
             setClassHierarchy(prev => removeNodeRecursively(prev, selectedItem.id));
             break;
        }
        case 'Individuals':
            setIndividuals(prev => prev.filter(ind => ind.id !== selectedItem.id));
            break;
        default:
            alert(`Deletion for ${entitiesTab} not implemented yet.`);
            return;
    }
    setSelectedItem(null);
  }, [selectedItem, entitiesTab]);

  const handleGraphNodeClick = useCallback((nodeId: string) => {
    const allItems: SelectableItem[] = [
        ...classHierarchy.flatMap(function recur(n: TreeNode): TreeNode[] { return [n, ...(n.children || []).flatMap(recur)] }),
        ...individuals,
    ];
    const item = allItems.find((i: SelectableItem) => i.id === nodeId);
    if(item) {
        let tab = 'Classes';
        if('types' in item) tab = 'Individuals';
        
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
  // #endregion

  // #region Render Methods
  
  const renderMainContent = () => {
    switch (mainTab) {
        case 'SPARQL':
            return <SparqlQueryEditor projectId={projectId!} prefixes={metadata?.prefixes || []} />;
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
                                     <a href={metadata?.ontologyIRI || "#"} className="text-blue-600 hover:underline text-xs break-all">{metadata?.ontologyIRI || "Not specified"}</a>
                                 </div>
                                 <div>
                                     <div className="text-xs font-semibold">Ontology Version IRI</div>
                                     <div className="text-xs text-gray-700 break-all">{metadata?.versionIRI || "Not specified"}</div>
                                 </div>
                             </div>
                         </div>
                         <div className="flex-1 overflow-y-auto p-4">
                             <h3 className="text-xs font-semibold text-gray-700 mb-2">Annotations</h3>
                             <AnnotationsDisplay annotations={metadata?.annotations} onDelete={() => console.log('Cannot delete ontology annotation here.')}   onEdit={()=>console.log('Cannot edit ontology annotation here.')}
 />
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
                                            {metadata?.prefixes?.map(p => (
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
                             { title: 'Ontology metrics', data: { Axiom: metadata?.axiomCount, 'Logical axiom': metadata?.logicalAxiomCount, 'Declaration axiom': metadata?.declarationAxiomCount, 'Class': metadata?.classCount, 'Object property': metadata?.objectPropertyCount, 'Data property': metadata?.dataPropertyCount, 'Individual': metadata?.individualCount, 'Annotation Property': annotationProperties.length } },
                             { title: 'Class axioms', data: { 
                                SubClassOf: metadata?.subClassOfAxiomCount, 
                                EquivalentClasses: metadata?.equivalentClassesAxiomCount, 
                                DisjointClasses: metadata?.disjointClassesAxiomCount,
                                'GCI count': metadata?.gciCount,
                                'Hidden GCI Count': metadata?.hiddenGciCount
                            } },
                             { title: 'Object property axioms', data: { SubObjectPropertyOf: metadata?.subObjectPropertyOfAxiomCount, InverseObjectProperties: metadata?.inverseObjectPropertiesAxiomCount } }
                         ].map(metricSection => (
                             <div key={metricSection.title}>
                                 <h3 className="font-semibold text-sm mb-2 border-b pb-1">{metricSection.title}</h3>
                                 <div className="space-y-1 text-xs">
                                     {Object.entries(metricSection.data).map(([key, value]) => value != null && (
                                         <div key={key} className="flex justify-between items-center">
                                             <span className="text-gray-600">{key}</span>
                                             <span className="font-medium bg-gray-100 px-1.5 py-0.5 rounded">{value.toLocaleString()}</span>
                                         </div>
                                     ))}
                                 </div>
                             </div>
                         ))}
                     </div>
                 </div>
             );
        case 'IndividualsByClass':
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
                                onSearchQueryChange={() => {}}
                                onSelectItem={(item) => setSelectedClassForIndividuals(item as TreeNode)}
                                onToggleNode={toggleNode}
                                onAddItem={() => {}}
                                onDeleteItem={() => {}}
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
                                                <User size={12} className="mr-2 text-purple-600"/>
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
        case 'DLQuery':
            return (
                <div className="flex h-full">
                    <main className="flex-1 flex flex-col p-2 bg-gray-50">
                        <div className="border bg-white p-2">
                             <h3 className="text-xs font-semibold mb-2">Query (class expression)</h3>
                             <textarea value={dlQuery} onChange={e => setDlQuery(e.target.value)} className="w-full h-24 border p-1 font-mono text-sm focus:ring-1 focus:ring-purple-500"></textarea>
                             <div className="flex gap-2 mt-2">
                                 <button onClick={handleExecuteDlQuery} disabled={isDlQueryLoading} className="px-3 py-1 bg-purple-600 text-white text-xs rounded hover:bg-purple-700 disabled:bg-purple-300 flex items-center gap-2">
                                    {isDlQueryLoading ? <Loader2 size={14} className="animate-spin" /> : <Play size={14}/>}
                                    Execute
                                 </button>
                                 <button className="px-3 py-1 bg-gray-200 text-xs rounded hover:bg-gray-300">Add to ontology</button>
                             </div>
                        </div>
                         <div className="border bg-white p-2 mt-2 flex-1">
                           <h3 className="text-xs font-semibold mb-2">Query results</h3>
                           {isDlQueryLoading ? (
                                <div className="flex items-center justify-center h-full text-gray-500 text-sm">
                                    <Loader2 size={20} className="animate-spin mr-2"/>
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
                                         <input type="checkbox" defaultChecked={item === 'Subclasses'}/> {item}
                                     </label>
                                 ))}
                             </div>
                        </div>
                         <div>
                             <h3 className="text-xs font-semibold mb-1">Result filters</h3>
                             <input type="text" placeholder="Name contains" className="w-full border px-2 py-1 text-xs"/>
                        </div>
                    </aside>
                </div>
            );
        default:
            return <div className="p-6 text-gray-400">Select a tab</div>;
    }
  }
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
      { id: "Classes", label: "Classes", icon: Package, count: metadata?.classCount, theme: 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]' },
      { id: "ObjectProperties", label: "Object properties", icon: Share2, count: metadata?.objectPropertyCount, theme: 'bg-gradient-to-b from-blue-300 to-blue-500 text-white border-blue-600' },
      { id: "DataProperties", label: "Data properties", icon: Database, count: metadata?.dataPropertyCount, theme: 'bg-gradient-to-b from-green-300 to-green-500 text-white border-green-600' },
      { id: "AnnotationProperties", label: "Annotation properties", icon: Tag, count: annotationProperties.length, theme: 'bg-gradient-to-b from-orange-300 to-orange-500 text-white border-orange-600' },
      { id: "Datatypes", label: "Datatypes", icon: Settings, count: datatypes.length || 0, theme: 'bg-gradient-to-b from-red-300 to-red-500 text-white border-red-600' },
      { id: "Individuals", label: "Individuals", icon: Eye, count: metadata?.individualCount, theme: 'bg-gradient-to-b from-purple-300 to-purple-500 text-white border-purple-600' },
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

      <div className="h-screen bg-gray-50 flex flex-col text-sm max-h-screen">
        <TopMenuBar 
          onToggleSwrlTab={toggleSwrlTab}
          isSwrlVisible={visibleMainTabs.includes('SWRL')}
          onToggleGraphTab={toggleGraphTab}
          isGraphVisible={visibleMainTabs.includes('Graph')}
          fileList={listOfFiles}
          projectId={projectId}
          handleSaveToDatabase={handleSaveToDatabase}
          serializeOntology={serializeOntology}
        />
        
        <div className="bg-white border-b border-gray-200 flex-shrink-0">
            <div className="flex items-center justify-between px-4 h-10">
                <div className="flex items-center">
                    {visibleMainTabs.map((tabId) => {
                        const tab = ALL_MAIN_TABS[tabId];
                        if (!tab) return null;
                        return (
                            <button key={tabId}
                                className={`flex items-center gap-2 px-3 h-full text-xs font-medium border-b-2 -mb-px ${mainTab === tabId ? "text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent"}`}
                                onClick={() => setMainTab(tabId)}>
                                <tab.icon size={14} /> {tab.label}
                            </button>
                        )
                    })}
                </div>
                 <div className="flex items-center gap-4">
                    <span className="font-medium text-xs text-gray-600">{projectId}</span>
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
                        <button key={tab.id} title={tab.label}
                            className={`flex items-center gap-2 px-3 py-1 text-xs font-medium border-t-2 mt-px ${entitiesTab === tab.id ? "bg-white text-purple-600 border-purple-600" : "text-gray-500 hover:text-gray-800 border-transparent hover:bg-gray-200 rounded-t"}`}
                            onClick={() => { setEntitiesTab(tab.id); setSelectedItem(null); }}>
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
                  onUpdate={updateItemInState}
                  onAddAnnotation={handleAddAnnotation} 
                  onDeleteAnnotation={handleDeleteAnnotation}
                  onEditAnnotation={handleEditAnnotation}
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
    </>
  );
};

export default Dashboard;