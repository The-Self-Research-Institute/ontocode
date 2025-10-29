import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight, ChevronDown, Plus, Settings, Search, FileText, Eye, Database, Tag, Share2, List, Code, Loader2, Package, Check, Trash2, PlusCircle, User, Box, Type, GitBranch, Binary, LogOut
} from "lucide-react";
import apiClient from "../services/apiClient";
import { pluginManager } from '../plugins/PluginSystem';
import { SWRLPlugin, ReasoningPlugin } from '../plugins/PluginRegistry';
import type { TreeNode, Property, Individual, AnnotationProperty, Datatype, OntologyMetadata, ClassUsage, AxiomUsage, SelectableItem } from '../types';
import { useAuth } from '../custom-hook/useAuth';

type TopLevelClass = TreeNode & { hasChildren: boolean };

// #region Helper Components

const AnnotationValue = ({ value }: { value: string }) => {
  let cleanedValue = value.toString();
  if (cleanedValue.startsWith('"')) cleanedValue = cleanedValue.substring(1);
  if (cleanedValue.endsWith('"^^xsd:string') || cleanedValue.endsWith('"')) {
    cleanedValue = cleanedValue.replace(/"\^\^xsd:string$/, "").replace(/"$/, "");
  }
  return <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-wrap">{cleanedValue}</p>;
};

const AnnotationsDisplay = ({ annotations, onDelete }: { annotations?: Record<string, string>, onDelete: (key: string) => void }) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return (
        <div className="p-2 text-xs text-gray-400 italic">No annotations</div>
    );
  }
  return (
    <div className="space-y-1 p-1">
      {Object.entries(annotations).map(([key, value]) => (
         <div key={key} className="group flex items-start p-1.5 rounded hover:bg-slate-100">
            <div className="w-1/3 text-xs font-medium text-gray-600 pr-2 break-words">{key}</div>
            <div className="w-2/3 text-xs text-gray-800 break-words flex justify-between items-start">
              <AnnotationValue value={value} />
              <button onClick={() => onDelete(key)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200 ml-2">
                <Trash2 size={12} className="text-red-600" />
              </button>
            </div>
        </div>
      ))}
    </div>
  );
};

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

const AxiomDisplay = ({
  description,
  onClassClick,
  projectId,
}: {
  description: string;
  onClassClick: (iri: string) => void;
  projectId: string;
}) => {
  const handleClassNameClick = async (className: string) => {
    if (!projectId) return;
    try {
      const response = await apiClient.get(`/api/ontology/classes/search/${projectId}`, {
        params: { query: className },
      });
      if (response.data && response.data.length > 0) {
        onClassClick(response.data[0].id);
      }
    } catch (error) {
      console.error("Failed to find class:", error);
    }
  };

  const parseAndStyleAxiom = (text: string) => {
    const keywords = {
      SubClassOf: "text-blue-600 font-semibold",
      EquivalentTo: "text-blue-600 font-semibold",
      DisjointWith: "text-blue-600 font-semibold",
      some: "text-pink-600 font-semibold",
      only: "text-pink-600 font-semibold",
      and: "text-pink-600 font-semibold",
      or: "text-pink-600 font-semibold",
      not: "text-pink-600 font-semibold",
      min: "text-pink-600 font-semibold",
      max: "text-pink-600 font-semibold",
      exactly: "text-pink-600 font-semibold",
    };
    const propertyPattern = /(has part|part of|overlaps|develops from|located in|has role|bearer of|inheres in|realized in|participates in|contains|contained in|hasTopping)/gi;
    const words = text.split(/(\s+)/);

    return (
      <span>
        {words.map((word, idx) => {
          const trimmedWord = word.trim().replace(/[()]/g, '');
          if (keywords[trimmedWord as keyof typeof keywords]) {
            return <span key={idx} className={keywords[trimmedWord as keyof typeof keywords]}>{word}</span>;
          }
          if (trimmedWord.match(propertyPattern)) {
            return <span key={idx} className="text-purple-600 font-medium">{word}</span>;
          }
          const isClassName = trimmedWord.length > 0 && !keywords[trimmedWord as keyof typeof keywords];
          if (isClassName && trimmedWord !== "") {
            return <span key={idx} className="text-gray-900 hover:text-blue-600 hover:underline cursor-pointer" onClick={() => handleClassNameClick(trimmedWord)}>{word}</span>;
          }
          return <span key={idx} className="text-gray-700">{word}</span>;
        })}
      </span>
    );
  };

  return parseAndStyleAxiom(description);
};


const TopMenuBar = ({ onToggleSwrlTab, isSwrlVisible, onToggleGraphTab, isGraphVisible }: { onToggleSwrlTab: () => void, isSwrlVisible: boolean, onToggleGraphTab: () => void, isGraphVisible: boolean }) => {
    const [openMenu, setOpenMenu] = useState<string | null>(null);
    const menuRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
                setOpenMenu(null);
            }
        };
        document.addEventListener("mousedown", handleClickOutside);
        return () => document.removeEventListener("mousedown", handleClickOutside);
    }, []);

    const menuItems = ['File', 'Edit', 'View', 'Reasoner', 'Tools', 'Window', 'Help'];
    
    return (
        <header ref={menuRef} className="bg-gray-200 text-gray-800 text-xs flex items-center px-2 relative border-b border-gray-300 h-8 flex-shrink-0">
            <div className="flex items-center gap-1 p-2 mr-2">
                <Package size={16} className="text-purple-600"/>
            </div>
            <div className="flex items-center">
                {menuItems.map(item => (
                    <div key={item} className="relative">
                        <button onClick={() => setOpenMenu(openMenu === item ? null : item)} className="px-3 py-1 hover:bg-gray-300 rounded-sm">{item}</button>
                        {openMenu === item && (
                            <div className="absolute left-0 mt-1 w-48 bg-white border border-gray-300 rounded-md shadow-lg z-20">
                                {item === 'Window' ? (
                                    <div className="py-1">
                                        <div className="px-3 py-1 text-gray-400 text-xs">Tabs</div>
                                        <a href="#" onClick={(e) => { e.preventDefault(); onToggleSwrlTab(); setOpenMenu(null); }} className="flex justify-between items-center px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100">
                                            SWRL Tab {isSwrlVisible && <Check size={14} className="text-purple-600"/>}
                                        </a>
                                    </div>
                                ) : item === 'Reasoner' ? (
                                    <div className="py-1">
                                         <a href="#" onClick={(e) => { e.preventDefault(); onToggleGraphTab(); setOpenMenu(null); }} className="flex justify-between items-center px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100">
                                            Graph View {isGraphVisible && <Check size={14} className="text-purple-600"/>}
                                        </a>
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

const Panel = ({ title, children, actions, defaultOpen = true, themeColor }: { title: string, children?: React.ReactNode, actions?: React.ReactNode, defaultOpen?: boolean, themeColor?: string }) => {
    const [isOpen, setIsOpen] = useState(defaultOpen);
    const themeClasses = themeColor || 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]';
    return (
        <div className={`border bg-white rounded-sm flex flex-col ${themeColor?.split(' ')[2] || 'border-[#D6C9AD]'}`}>
            <div className={`text-xs font-semibold p-1.5 flex items-center justify-between border-b ${themeClasses}`}>
                <div className="flex items-center">
                    <button onClick={() => setIsOpen(!isOpen)} className="mr-1 p-0.5 rounded hover:bg-black/10">
                        {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                    <span>{title}</span>
                </div>
                <div className="flex items-center gap-1">{actions}</div>
            </div>
            <div className={`transition-all duration-300 ease-in-out overflow-hidden ${isOpen ? 'max-h-[1000px]' : 'max-h-0'}`}>
                {isOpen && <div className="bg-white overflow-y-auto">{children}</div>}
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

// #region Details Panel Components

const ClassDetails = ({ item, onAddAnnotation, onDeleteAnnotation, classUsage, loadingUsage, projectId, handleNavigateToClass }: { item: TreeNode, onAddAnnotation: () => void, onDeleteAnnotation: (key: string) => void, classUsage: ClassUsage | null, loadingUsage: boolean, projectId: string, handleNavigateToClass: (iri: string) => void }) => (
    <div className="flex gap-2 h-full">
        <div className="flex-1 flex flex-col gap-2">
            <Panel title={`Annotations: ${item.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>}>
                <AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} />
            </Panel>
            <Panel title={`Description: ${item.label}`} defaultOpen={true}>
                 <div className="space-y-1 p-1">
                     {['Equivalent To', 'SubClass Of', 'Disjoint With'].map(axiom => (
                         <div className="border border-gray-200 rounded-sm" key={axiom}>
                             <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center">
                                 <span>{axiom}</span>
                                 <button className="p-0.5 hover:bg-gray-300 rounded"><Plus size={14}/></button>
                             </div>
                             <div className="p-1.5">
                                <button className="text-xs text-gray-400 italic hover:text-purple-600 hover:underline">
                                  None
                                </button>
                             </div>
                         </div>
                     ))}
                 </div>
            </Panel>
             <Panel title={`Usage: ${item.label}`} defaultOpen={true}>
                <div className="p-1">
                    {loadingUsage ? (
                         <div className="flex items-center justify-center py-4">
                            <Loader2 size={16} className="animate-spin text-purple-600 mr-2" />
                            <span className="text-xs text-gray-500">Loading usage...</span>
                        </div>
                    ) : classUsage && classUsage.totalUsages > 0 ? (
                        <div className="space-y-2">
                             {Object.entries(
                                classUsage.usages.reduce((acc: Record<string, AxiomUsage[]>, usage: AxiomUsage) => {
                                  if (!acc[usage.category]) acc[usage.category] = [];
                                  acc[usage.category].push(usage);
                                  return acc;
                                }, {} as Record<string, AxiomUsage[]>)
                              ).map(([category, usages]) => (
                                <div key={category} className="border border-gray-200 rounded-sm">
                                  <div className="p-1 text-xs bg-gray-100 border-b font-semibold">{category}</div>
                                  <div className="space-y-1 p-1">
                                    {usages.map((usage: AxiomUsage, idx: number) => (
                                      <div key={idx} className="bg-gray-50 p-2 rounded-sm text-xs font-mono">
                                        <AxiomDisplay
                                            description={usage.description}
                                            projectId={projectId!}
                                            onClassClick={handleNavigateToClass}
                                        />
                                      </div>
                                    ))}
                                  </div>
                                </div>
                              ))}
                        </div>
                    ) : (
                        <div className="text-center py-4 text-xs text-gray-400 italic">
                            No usages found for this class.
                        </div>
                    )}
                </div>
            </Panel>
        </div>
        <div className="w-1/3 flex flex-col gap-2">
             <Panel title="Property assertions" defaultOpen={true}>
                <div className="p-1 space-y-1">
                    {['Object property assertions', 'Data property assertions', 'Negative property assertions'].map(p => (
                        <div key={p} className="p-1 text-xs bg-gray-100 border rounded-sm flex justify-between items-center"><span>{p}</span><button className="p-0.5 hover:bg-gray-300 rounded"><Plus size={14}/></button></div>
                    ))}
                </div>
             </Panel>
        </div>
    </div>
);

const PropertyDetails = ({ item, entitiesTab, activeTheme, onAddAnnotation, onDeleteAnnotation }: { item: Property, entitiesTab: string, activeTheme?: string, onAddAnnotation: () => void, onDeleteAnnotation: (key: string) => void }) => {
    const characteristics = entitiesTab === 'ObjectProperties' ? ['Functional', 'Inverse functional', 'Transitive', 'Symmetric', 'Asymmetric', 'Reflexive', 'Irreflexive'] : ['Functional'];
    const descriptionItems = entitiesTab === 'ObjectProperties' ? ['SubProperty Of', 'Inverse Of', 'Domains', 'Ranges', 'Disjoint With'] : ['SubProperty Of', 'Domains', 'Ranges', 'Disjoint With'];
    return (
         <div className="flex gap-2 h-full">
            <div className="w-1/3 flex flex-col gap-2">
                <Panel title={`Annotations: ${item.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>} themeColor={activeTheme}><AnnotationsDisplay annotations={item.annotations} onDelete={onDeleteAnnotation} /></Panel>
                <Panel title="Characteristics" themeColor={activeTheme}>
                   <div className="p-2 space-y-1.5 text-xs">
                     {characteristics.map(char => (
                        <label key={char} className="flex items-center gap-2">
                            <input type="checkbox" defaultChecked={item.characteristics?.includes(char)}/> {char}
                         </label>
                     ))}
                   </div>
                </Panel>
            </div>
            <div className="flex-1 flex flex-col gap-2">
                 <Panel title={`Description: ${item.label}`} defaultOpen={true} themeColor={activeTheme}>
                     <div className="space-y-1 p-1">
                        {descriptionItems.map(descItem => (
                             <div className="border border-gray-200 rounded-sm" key={descItem}>
                                 <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center"><span>{descItem}</span><button className="p-0.5 hover:bg-gray-300 rounded"><Plus size={14}/></button></div>
                                 <div className="p-1.5 text-xs text-gray-400 italic">None</div>
                             </div>
                        ))}
                     </div>
                 </Panel>
            </div>
        </div>
    );
};

const DetailsPanel = ({ selectedItem, entitiesTab, activeTheme, onAddAnnotation, onDeleteAnnotation, ...props }: { selectedItem: SelectableItem | null, entitiesTab: string, activeTheme?: string, onAddAnnotation: () => void, onDeleteAnnotation: (key: string) => void, classUsage: ClassUsage | null, loadingUsage: boolean, projectId: string, handleNavigateToClass: (iri: string) => void }) => {
    if (!selectedItem) {
        return (
            <div className="h-full flex flex-col items-center justify-center text-center text-gray-400 p-4">
              <Package size={48} className="mb-4 text-gray-300"/>
              <h3 className="text-lg font-semibold text-gray-600">Ontology Editor</h3>
              <p className="text-sm">Select an entity from the hierarchy panel on the left to view its details and make edits.</p>
            </div>
        );
    }
    
    switch (entitiesTab) {
        case 'Classes':
             return <ClassDetails 
                item={selectedItem as TreeNode} 
                onAddAnnotation={onAddAnnotation} 
                onDeleteAnnotation={onDeleteAnnotation} 
                {...props}
             />;
        case 'ObjectProperties':
        case 'DataProperties':
            return <PropertyDetails item={selectedItem as Property} entitiesTab={entitiesTab} activeTheme={activeTheme} onAddAnnotation={onAddAnnotation} onDeleteAnnotation={onDeleteAnnotation} />;
        case 'AnnotationProperties': {
            const annProp = selectedItem as AnnotationProperty;
            return (
                 <div className="flex-1 flex flex-col gap-2">
                     <Panel title={`Annotations: ${annProp.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>} themeColor={activeTheme}><AnnotationsDisplay annotations={annProp.annotations} onDelete={onDeleteAnnotation} /></Panel>
                     <Panel title={`Description: ${annProp.label}`} defaultOpen={true} themeColor={activeTheme}>
                         <div className="space-y-1 p-1">
                            {['Domains', 'Ranges', 'SuperProperties'].map(item => (
                                <div className="border border-gray-200 rounded-sm" key={item}>
                                    <div className="p-1 text-xs bg-gray-100 border-b flex justify-between items-center"><span>{item}</span><button className="p-0.5 hover:bg-gray-300 rounded"><Plus size={14}/></button></div>
                                    <div className="p-1.5 text-xs text-gray-400 italic">None</div>
                                </div>
                            ))}
                         </div>
                     </Panel>
                 </div>
            );
        }
        case 'Datatypes':
            return <Panel title={`Annotations: ${selectedItem.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>} themeColor={activeTheme}><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} /></Panel>;
        case 'Individuals': {
             const ind = selectedItem as Individual;
             return <Panel title={`Annotations: ${ind.label}`} actions={<button onClick={onAddAnnotation} className="p-0.5 hover:bg-black/20 rounded-full"><Plus size={14}/></button>} themeColor={activeTheme}><AnnotationsDisplay annotations={ind.annotations} onDelete={onDeleteAnnotation} /></Panel>;
        }
        default:
             return <div className="bg-white rounded-lg border p-4"><AnnotationsDisplay annotations={selectedItem.annotations} onDelete={onDeleteAnnotation} /></div>;
    }
}
// #endregion

const Dashboard = () => {
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
  const [activeOntologySubTab, setActiveOntologySubTab] = useState('imports');
  const [isCreateIndividualModalOpen, setCreateIndividualModalOpen] = useState(false);
  const [classUsage, setClassUsage] = useState<ClassUsage | null>(null);
  const [loadingUsage, setLoadingUsage] = useState(false);

  const [classHierarchy, setClassHierarchy] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<AnnotationProperty[]>([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<Datatype[]>([]);
  
  const [filteredData, setFilteredData] = useState<SelectableItem[]>([]);
  
  const [visibleMainTabs, setVisibleMainTabs] = useState(['ActiveOntology', 'Entities', 'IndividualsByClass', 'DLQuery']);
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
        const [metadataRes, topLevelRes, propertiesRes, individualsRes, annotationPropsRes, datatypesRes] = await Promise.all([
            apiClient.get(`/api/ontology/metadata/${currentProjectId}`),
            apiClient.get(`/api/ontology/classes/top-level/${currentProjectId}`),
            apiClient.get(`/api/ontology/properties/${currentProjectId}`),
            apiClient.get(`/api/ontology/individuals/${currentProjectId}`),
            apiClient.get(`/api/ontology/annotation-properties/${currentProjectId}`),
            apiClient.get(`/api/ontology/datatypes/${currentProjectId}`),
        ]);

        setMetadata(metadataRes.data);
        const { classes } = topLevelRes.data;
        const topLevelNodes: TreeNode[] = classes.map((c: TopLevelClass) => ({ ...c, children: c.hasChildren ? [] : null }));
        const owlThingNode: TreeNode = { id: "http://www.w3.org/2002/07/owl#Thing", label: "owl:Thing", children: topLevelNodes, annotations: {} };
        setClassHierarchy([owlThingNode]);

        const allProps = propertiesRes.data.data || [];
        setObjectProperties(allProps.filter((p: Property) => p.type === 'ObjectProperty'));
        setDataProperties(allProps.filter((p: Property) => p.type === 'DataProperty'));
        
        setIndividuals(individualsRes.data.data || []);
        setAnnotationProperties(annotationPropsRes.data.data || []);
        setDatatypes(datatypesRes.data.data || []);
        
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

  const pollProcessingStatus = useCallback(async (projectIdToPoll: string) => {
      setIsInitialLoading(true);
      setTimeout(() => {
          fetchData(projectIdToPoll);
      }, 1000);
  }, [fetchData]);

  useEffect(() => {
    const timer = setTimeout(() => {
        if (!projectId) {
            if (window.vscode) {
                // The VS Code extension will send 'fileReady' to trigger loading
            } else {
                // Fallback for development in browser
                console.log("Not in VSCode, loading mock data.");
                setProjectId('pizza-ontology'); 
                pollProcessingStatus('pizza-ontology');
            }
        }
    }, 500);

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      switch (message.type) {
        case "showLoading": setIsInitialLoading(true); break;
        case "fileReady": setProjectId(message.projectId); pollProcessingStatus(message.projectId); break;
        case "loadingFailed": setIsInitialLoading(false); break;
        case "switchView": if (message.view === 'swrl' && !visibleMainTabs.includes('SWRL')) { toggleSwrlTab(); } setMainTab('SWRL'); break;
      }
    };
    window.addEventListener("message", handleMessage);
    return () => {
        clearTimeout(timer);
        window.removeEventListener("message", handleMessage)
    };
  }, [pollProcessingStatus, projectId, toggleSwrlTab, visibleMainTabs]);
  
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
        const results = sourceData.filter(item => item.label?.toLowerCase().includes(lowercasedQuery));
        setFilteredData(results);
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
  
    const fetchClassUsage = useCallback(async (classIri: string) => {
        if (!projectId) return;
        setLoadingUsage(true);
        try {
            const response = await apiClient.get(`/api/ontology/classes/usage/${projectId}`, {
                params: { classIri },
            });
            setClassUsage(response.data);
        } catch (error) {
            console.error("Failed to load class usage:", error);
            setClassUsage(null);
        } finally {
            setLoadingUsage(false);
        }
    }, [projectId]);

    useEffect(() => {
        if (selectedItem && 'children' in selectedItem && entitiesTab === 'Classes') {
            fetchClassUsage(selectedItem.id);
        } else {
            setClassUsage(null);
        }
    }, [selectedItem, entitiesTab, fetchClassUsage]);

  // #endregion

  // #region Event Handlers
  const loadChildren = useCallback(async (nodeId: string) => {
      if (!projectId) return;
      const { data } = await apiClient.get(`/api/ontology/classes/children/${projectId}`, { params: { parentIri: nodeId } });
      const children = data.children;
      
      const updateTree = (nodes: TreeNode[]): TreeNode[] => {
        return nodes.map((n: TreeNode) => {
            if (n.id === nodeId) {
                return { ...n, children: children.map((c: TopLevelClass) => ({ ...c, children: c.hasChildren ? [] : null })) };
            }
            if (n.children) {
                return { ...n, children: updateTree(n.children) };
            }
            return n;
        });
      };
      setClassHierarchy(prevHierarchy => updateTree(prevHierarchy));
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

    const expandPathToClass = useCallback(async () => {
        if (!projectId) return;
        // This is a simplified implementation. A real one would need an API to get the full path.
        if (!expandedNodes.includes('http://www.w3.org/2002/07/owl#Thing')) {
            setExpandedNodes(prev => [...prev, 'http://www.w3.org/2002/07/owl#Thing']);
        }
    }, [projectId, expandedNodes]);

    const handleNavigateToClass = useCallback(async (classIri: string) => {
        if (!projectId) return;
        setEntitiesTab('Classes');
        try {
            await expandPathToClass();
            const response = await apiClient.get(`/api/ontology/classes/search/${projectId}`, {
                params: { query: classIri }
            });
            if (response.data && response.data.length > 0) {
                const foundClass = response.data.find((c: TreeNode) => c.id === classIri) || response.data[0];
                const enrichedClass = { ...foundClass, children: (foundClass as TopLevelClass).hasChildren ? [] : null};
                setSelectedItem(enrichedClass);
                setTimeout(() => {
                    const element = document.querySelector(`[data-class-id="${classIri}"]`);
                    if (element) {
                        element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                }, 100);
            }
        } catch (error) {
            console.error('Failed to navigate to class:', error);
        }
    }, [projectId, expandPathToClass]);

  const updateItemInState = useCallback((updatedItem: SelectableItem) => {
      const updateTree = (nodes: TreeNode[], itemToUpdate: SelectableItem): TreeNode[] => {
          return nodes.map(node => {
              if (node.id === itemToUpdate.id) return itemToUpdate as TreeNode;
              if (node.children) return { ...node, children: updateTree(node.children, itemToUpdate) };
              return node;
          });
      };
      
      if (selectedItem?.id === updatedItem.id) {
          setSelectedItem(updatedItem);
      }

      switch(entitiesTab) {
          case 'Classes':
              setClassHierarchy(prev => updateTree(prev, updatedItem));
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
      if (!confirm(`Are you sure you want to delete the annotation "${key}"?`)) return;

      const remainingAnnotations = { ...selectedItem.annotations };
      delete remainingAnnotations[key];
      const updatedItem = { ...selectedItem, annotations: remainingAnnotations };
      updateItemInState(updatedItem);
  }, [selectedItem, updateItemInState]);

  const handleAddIndividual = useCallback((name: string) => {
    const newIndividual: Individual = {
        id: `http://example.com/pizza#${name.replace(/\s+/g, '_')}`,
        iri: `http://example.com/pizza#${name.replace(/\s+/g, '_')}`,
        label: name,
        annotations: { 'rdfs:label': name },
        types: []
    };
    setIndividuals(prev => [...prev, newIndividual]);
  }, []);

  const handleDeleteItem = useCallback(() => {
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

  const handleAddClass = useCallback((mode: 'subclass' | 'sibling') => {
      if ((mode === 'subclass' || mode === 'sibling') && !selectedItem) {
          alert("Please select a class first.");
          return;
      }
      const name = prompt("Enter new class name:");
      if (!name) return;
      
      const newNode: TreeNode = {
          id: `http://example.com/pizza#${name.replace(/\s+/g, '_')}`,
          label: name,
          children: [],
          annotations: { 'rdfs:label': name }
      };
      
      // Perform side-effect before setting state that uses the pure function
      if (mode === 'subclass' && selectedItem?.id && !expandedNodes.includes(selectedItem.id)) {
          setExpandedNodes(prev => [...prev, selectedItem.id]);
      }

      const addNodeRecursively = (nodes: TreeNode[]): TreeNode[] => {
          return nodes.map(node => {
              if (mode === 'subclass' && node.id === selectedItem?.id) {
                  return { ...node, children: [...(node.children || []), newNode] };
              }
              if (mode === 'sibling' && node.children?.some((child: TreeNode) => child.id === selectedItem?.id)) {
                  return { ...node, children: [...node.children, newNode] };
              }
              if (node.children) {
                  return { ...node, children: addNodeRecursively(node.children) };
              }
              return node;
          });
      };
      
      setClassHierarchy(prev => addNodeRecursively(prev));
  }, [selectedItem, expandedNodes]);
    
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
  // #endregion

  // #region Render Methods
  const renderItem = (item: SelectableItem, level = 0): React.JSX.Element => {
    const isSelected = selectedItem?.id === item.id;
    const isTreeNode = 'children' in item && item.children !== undefined;
    const isExpanded = isTreeNode && expandedNodes.includes(item.id);

    let Icon, iconClasses;
    let itemType = 'Classes';
    if('type' in item && item.type) {
        if(item.type === 'ObjectProperty') itemType = 'ObjectProperties';
        else if(item.type === 'DataProperty') itemType = 'DataProperties';
    } else if ('types' in item) {
        itemType = 'Individuals';
    } else if (entitiesTab === 'AnnotationProperties') {
        itemType = 'AnnotationProperties';
    } else if (entitiesTab === 'Datatypes') {
        itemType = 'Datatypes';
    }

    switch (itemType) {
        case 'Classes': Icon = Box; iconClasses = 'bg-amber-400 border-amber-600'; break;
        case 'ObjectProperties': Icon = GitBranch; iconClasses = 'bg-blue-400 border-blue-600'; break;
        case 'DataProperties': Icon = Database; iconClasses = 'bg-green-400 border-green-600'; break;
        case 'AnnotationProperties': Icon = Tag; iconClasses = 'bg-orange-400 border-orange-600'; break;
        case 'Individuals': Icon = User; iconClasses = 'bg-purple-400 border-purple-600'; break;
        case 'Datatypes': Icon = Type; iconClasses = 'bg-red-400 border-red-600'; break;
        default: Icon = Box; iconClasses = 'bg-gray-400 border-gray-600';
    }

    return (
      <div key={item.id}>
        <div 
          data-class-id={item.id}
          className={`flex items-center px-2 py-0.5 rounded cursor-pointer ${isSelected ? "bg-blue-200" : "hover:bg-slate-100"}`}
          style={{ paddingLeft: `${level * 16 + 4}px` }}
          onClick={() => setSelectedItem(item)}
        >
          {isTreeNode ? (
            <button className="p-0.5 mr-1" onClick={(e) => { e.stopPropagation(); toggleNode(item.id); }}>
              {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          ) : <span className="w-5" />}
           <div title={itemType.slice(0, -1)} className={`w-3.5 h-3.5 rounded-sm border ${iconClasses} mr-2 flex-shrink-0 flex items-center justify-center`}>
              <Icon size={10} className="text-white"/>
           </div>
          <span className={`text-xs ${isSelected ? "font-semibold" : ""}`}>{item.label}</span>
        </div>
        {isExpanded && (item as TreeNode).children?.map((child: TreeNode) => renderItem(child, level + 1))}
      </div>
    );
  };
  
  const renderMainContent = () => {
    switch (mainTab) {
        case 'Graph': {
            const reasoningPlugin = pluginManager.getPlugin('reasoning-graph');
            if (reasoningPlugin && pluginManager.isPluginActive('reasoning-graph') && projectId) {
                const PluginComponent = reasoningPlugin.component;
                return <PluginComponent projectId={projectId} onNodeClick={handleGraphNodeClick} context={pluginManager.context!} />;
            }
            return <div className="p-4">Enable the Graph View from the Reasoner menu.</div>;
        }
        case 'SWRL': {
            const swrlPlugin = pluginManager.getPlugin('swrl-tab');
            if (swrlPlugin && pluginManager.isPluginActive('swrl-tab') && pluginManager.context) {
                const PluginComponent = swrlPlugin.component;
                return <PluginComponent projectId={projectId!} context={pluginManager.context} />;
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
                             <AnnotationsDisplay annotations={metadata?.annotations} onDelete={() => alert('Cannot delete ontology annotation here.')} />
                         </div>
                         <div className="border-t border-gray-200">
                             <div className="flex bg-gray-100 text-xs border-b border-gray-200">
                                 {['imports', 'prefixes', 'axioms'].map(t => (
                                     <button key={t} onClick={() => setActiveOntologySubTab(t)}
                                         className={`px-3 py-1.5 font-medium border-r border-gray-200 capitalize ${activeOntologySubTab === t ? 'bg-white text-gray-900' : 'text-gray-500 hover:bg-gray-200'}`}>
                                         {t === 'imports' ? 'Ontology imports' : t === 'prefixes' ? 'Ontology Prefixes' : 'General class axioms'}
                                     </button>
                                 ))}
                             </div>
                             <div className="bg-white p-4 min-h-24 text-sm text-gray-400 italic">Content for {activeOntologySubTab}</div>
                         </div>
                     </div>
                     <div className="w-80 bg-white p-4 overflow-y-auto space-y-4">
                         {[
                             { title: 'Ontology metrics', data: { Axiom: metadata?.axiomCount, 'Logical axiom': metadata?.logicalAxiomCount, 'Declaration axiom': metadata?.declarationAxiomCount, 'Class': metadata?.classCount, 'Object property': metadata?.objectPropertyCount, 'Data property': metadata?.dataPropertyCount, 'Individual': metadata?.individualCount, 'Annotation Property': annotationProperties.length } },
                             { title: 'Class axioms', data: { SubClassOf: metadata?.subClassOfAxiomCount, EquivalentClasses: metadata?.equivalentClassesAxiomCount, DisjointClasses: metadata?.disjointClassesAxiomCount } },
                             { title: 'Object property axioms', data: { SubObjectPropertyOf: metadata?.subObjectPropertyOfAxiomCount, InverseObjectProperties: metadata?.inverseObjectPropertiesAxiomCount } }
                         ].map(section => (
                             <div key={section.title}>
                                 <h3 className="font-semibold text-sm mb-2 border-b pb-1">{section.title}</h3>
                                 <div className="space-y-1 text-xs">
                                     {Object.entries(section.data).map(([key, value]) => value != null && (
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
            return (
                <div className="flex h-full">
                    <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
                         <div className="p-2 border-b text-sm font-semibold text-gray-700">Class hierarchy</div>
                         <div className="flex-1 overflow-y-auto p-1">{classHierarchy.map(node => renderItem(node))}</div>
                    </aside>
                    <main className="flex-1 p-2 bg-gray-50">
                        <div className="border bg-white h-full">
                           <div className="flex text-xs border-b">
                             <button className="px-3 py-1.5 bg-white border-r font-semibold">Direct instances</button>
                             <button className="px-3 py-1.5 bg-gray-100 text-gray-500 hover:bg-gray-200">Individuals (inferred)</button>
                           </div>
                           <div className="p-4 text-sm text-gray-400 italic flex items-center justify-center h-full">
                             Select a class to view its instances.
                           </div>
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
                             <textarea className="w-full h-24 border p-1 font-mono text-sm focus:ring-1 focus:ring-purple-500" defaultValue="Pizza and hasTopping some MozzarellaTopping"></textarea>
                             <div className="flex gap-2 mt-2">
                                 <button className="px-3 py-1 bg-gray-200 text-xs rounded hover:bg-gray-300">Execute</button>
                                 <button className="px-3 py-1 bg-gray-200 text-xs rounded hover:bg-gray-300">Add to ontology</button>
                             </div>
                        </div>
                         <div className="border bg-white p-2 mt-2 flex-1">
                            <h3 className="text-xs font-semibold mb-2">Query results</h3>
                             <div className="p-2 text-sm text-gray-400 italic h-full">No results.</div>
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
        <TopMenuBar onToggleSwrlTab={toggleSwrlTab} isSwrlVisible={visibleMainTabs.includes('SWRL')} onToggleGraphTab={toggleGraphTab} isGraphVisible={visibleMainTabs.includes('Graph')} />
        
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
              <aside className="w-80 bg-white border-r border-gray-200 flex flex-col">
                <div className={`${activeTheme} text-xs font-semibold p-1.5 flex items-center justify-between border-b`}>
                  <span>{entitiesTabs.find(t => t.id === entitiesTab)?.label} hierarchy</span>
                   <div className="flex items-center gap-1">
                        {entitiesTab === 'Classes' && (
                           <>
                           <button title="Add subclass" disabled={!selectedItem} onClick={() => handleAddClass('subclass')} className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30">
                                <PlusCircle size={14} />
                           </button>
                           <button title="Add sibling class" disabled={!selectedItem} onClick={() => handleAddClass('sibling')} className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30">
                                <Binary size={14} />
                           </button>
                           </>
                        )}
                        {entitiesTab === 'Individuals' && (
                             <button title="Add individual" onClick={() => setCreateIndividualModalOpen(true)} className="p-0.5 hover:bg-black/20 rounded">
                                <PlusCircle size={14} />
                           </button>
                        )}
                       <button title="Delete selected entity" disabled={!selectedItem} onClick={handleDeleteItem} className="p-0.5 hover:bg-black/20 rounded disabled:opacity-30">
                          <Trash2 size={14} />
                       </button>
                  </div>
                </div>
                <div className="p-2 border-b border-gray-200 flex-shrink-0">
                    <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                        <input type="text" placeholder={`Search ${entitiesTab.toLowerCase()}...`} value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                            className="w-full pl-8 pr-3 py-1.5 border border-gray-300 rounded-md focus:ring-1 focus:ring-purple-500 text-sm bg-white" />
                    </div>
                </div>
                <div className="flex-1 overflow-y-auto p-1">
                  {filteredData.length > 0 ? filteredData.map(node => renderItem(node)) : 
                    (entitiesTab === 'Individuals' ? (
                       <div className="p-4 text-center text-gray-400">
                         <p className="mb-2">No individuals created yet.</p>
                         <button onClick={() => setCreateIndividualModalOpen(true)} className="text-sm text-purple-600 hover:underline">Create a new Individual</button>
                       </div>
                    ) : <div className="p-4 text-center text-gray-400">No items found.</div>)
                  }
                </div>
              </aside>

              <section className="flex-1 overflow-y-auto p-2 bg-slate-200">
                <DetailsPanel 
                  selectedItem={selectedItem} 
                  entitiesTab={entitiesTab} 
                  activeTheme={activeTheme} 
                  onAddAnnotation={handleAddAnnotation} 
                  onDeleteAnnotation={handleDeleteAnnotation}
                  classUsage={classUsage}
                  loadingUsage={loadingUsage}
                  projectId={projectId!}
                  handleNavigateToClass={handleNavigateToClass}
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