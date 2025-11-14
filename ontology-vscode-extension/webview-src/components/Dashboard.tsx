import React, { useState, useEffect, useCallback, useRef } from 'react';
import { 
  Upload, Download, Save, Settings, Play, CheckCircle, AlertTriangle, 
  FileText, Search, BarChart3, Loader2, Package, GitBranch, Database, 
  Tag, User, Type, Code, Share2, Shield, Box
} from 'lucide-react';
import EntityHierarchy from './EntityHierarchy';
import ClassEditor from './details/ClassEditor';
import PropertyEditor from './details/PropertyEditor';
import IndividualEditor from './details/IndividualEditor';
import SparqlQueryEditor from './SparqlQueryEditor';
import SWRLEditor from './SWRLEditor';
import ReasoningVisualizer from './ReasoningVisualizer';
import ValidationPanel from './ValidationPanel';
import StatisticsPanel from './StatisticsPanel';
import apiClient from '../services/apiClient';
import { pluginManager } from '../plugins/PluginSystem';
import { SWRLPlugin, ReasoningPlugin } from '../plugins/PluginRegistry';
import type { 
  TreeNode, Property, Individual, SelectableItem, OntologyMetadata, 
  OntologyPrefix, ProjectStatus, ValidationResult, OntologyStatistics, 
  OntologyClassNode
} from '../types';

type TabType = 'Entities' | 'SPARQL' | 'Validation' | 'Statistics' | string;
type EntitiesTabType = 'Classes' | 'ObjectProperties' | 'DataProperties' | 'AnnotationProperties' | 'Individuals' | 'Datatypes';

const Dashboard: React.FC = () => {
  const [projectId, setProjectId] = useState<string>('');
  const [activeTab, setActiveTab] = useState<TabType>('Entities');
  const [entitiesTab, setEntitiesTab] = useState<EntitiesTabType>('Classes');
  
  const [projectStatus, setProjectStatus] = useState<ProjectStatus | null>(null);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [prefixes, setPrefixes] = useState<OntologyPrefix[]>([]);
  const [statistics, setStatistics] = useState<OntologyStatistics | null>(null);
  
  const [classes, setClasses] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<Property[]>([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<string[]>([]);
  
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  
  const [activePlugins, setActivePlugins] = useState<string[]>([]);
  
  const pollIntervalRef = useRef<number | null>(null);

  useEffect(() => {
    pluginManager.registerPlugins([SWRLPlugin, ReasoningPlugin]);
    
    pluginManager.on('plugin-activated', (plugin: any) => {
      setActivePlugins(prev => [...prev, plugin.id]);
    });
    
    pluginManager.on('plugin-deactivated', (plugin: any) => {
      setActivePlugins(prev => prev.filter(id => id !== plugin.id));
    });
    
    return () => {
      pluginManager.clearAll();
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (projectId) {
      pluginManager.setContext({ projectId, ontology: metadata });
    }
  }, [projectId, metadata]);

  const loadProject = useCallback(async (pid: string) => {
    setIsLoading(true);
    try {
      const statusRes = await apiClient.get<{ data: ProjectStatus }>(`/api/ontology/status/${pid}`);
      setProjectStatus(statusRes.data);
      
      if (statusRes.data.status !== 'COMPLETED') {
        pollIntervalRef.current = window.setTimeout(() => loadProject(pid), 2000);
        return;
      }
      
      if (pollIntervalRef.current) {
        clearTimeout(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
      
      const [metadataRes, prefixesRes, statsRes, classesRes, objPropsRes, dataPropsRes, annPropsRes, individualsRes, datatypesRes] = 
        await Promise.all([
          apiClient.get<{ data: OntologyMetadata }>(`/api/ontology/metadata/${pid}`),
          apiClient.get<{ data: OntologyPrefix[] }>(`/api/ontology/namespaces/${pid}`),
          apiClient.get<{ data: OntologyStatistics }>(`/api/ontology/statistics/${pid}`),
          apiClient.get<{ classes: TreeNode[] }>(`/api/ontology/all-classes?projectId=${pid}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${pid}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${pid}`), // This seems redundant, but keeping as is
          apiClient.get<{ data: Property[] }>(`/api/ontology/annotation-properties/${pid}`),
          apiClient.get<{ data: Individual[] }>(`/api/ontology/individuals/${pid}`),
          apiClient.get<{ data: string[] }>(`/api/ontology/datatypes/${pid}`)
        ]);
      
      setMetadata(metadataRes.data);
      setPrefixes(prefixesRes.data);
      setStatistics(statsRes.data);
      setClasses(classesRes.classes);
      
      const allProps = objPropsRes.data;
      setObjectProperties(allProps.filter(p => p.type === 'ObjectProperty'));
      setDataProperties(dataPropsRes.data.filter(p => p.type === 'DatatypeProperty'));
      setAnnotationProperties(annPropsRes.data);
      
      setIndividuals(individualsRes.data);
      setDatatypes(datatypesRes.data);
      
    } catch (error) {
      console.error('Failed to load project:', error);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (window.vscode) {
      window.vscode.postMessage({ type: 'webviewReady' });
    }

    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (!message) return;

      switch (message.type) {
        case 'fileReady':
          console.log("Flow: Received 'fileReady', starting to poll.", message.projectId);
          setProjectId(message.projectId);
          setIsLoading(true);
          
          if (pollIntervalRef.current) {
             clearInterval(pollIntervalRef.current);
          }
          pollIntervalRef.current = window.setInterval(() => {
            loadProject(message.projectId); // This component's poll logic calls loadProject directly
          }, 2000);
          break;
        
        case 'showLogin':
          console.log("Flow: Received 'showLogin'. UI should show login form.");
          break;
        
        case 'loadingFailed':
          setIsLoading(false);
          console.error("Flow: Received 'loadingFailed'", message.error);
          break;
      }
    };
    
    window.addEventListener('message', handleMessage);
    return () => {
      window.removeEventListener('message', handleMessage);
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, [loadProject]); // Added loadProject as dependency

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    
    setIsLoading(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      
      const newProjectId = `project-${Date.now()}`;
      const response = await apiClient.post<{ projectId: string }>(
        `/api/ontology/upload/${newProjectId}`,
        formData
      );
      
      setProjectId(newProjectId);
      await loadProject(newProjectId);
      
    } catch (error) {
      console.error('Upload failed:', error);
      alert('Failed to upload ontology file');
    } finally {
      setIsLoading(false);
    }
  };

  const handleExport = async () => {
    if (!projectId) return;
    
    try {
      const response = await apiClient.get<Blob>(`/api/ontology/export/${projectId}`);
      const blob = new Blob([response]);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${metadata?.ontologyIRI?.split('/').pop() || 'ontology'}.owl`;
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Export failed:', error);
      alert('Failed to export ontology');
    }
  };

  const handleSave = async () => {
    if (!projectId || !selectedItem) return;
    
    setIsSaving(true);
    try {
      if ('children' in selectedItem) {
        await apiClient.put(`/api/ontology/classes/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          parentIri: (selectedItem as TreeNode).parent
        });
      } else if ('type' in selectedItem && (selectedItem.type === 'ObjectProperty' || selectedItem.type === 'DatatypeProperty')) {
        await apiClient.put(`/api/ontology/properties/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          domains: (selectedItem as Property).domains,
          ranges: (selectedItem as Property).ranges
        });
      } else if ('types' in selectedItem) {
        await apiClient.put(`/api/ontology/individuals/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          types: (selectedItem as Individual).types
        });
      }
      
      console.log('Saved successfully');
    } catch (error) {
      console.error('Save failed:', error);
      alert('Failed to save changes');
    } finally {
      setIsSaving(false);
    }
  };

  const handleValidate = async () => {
    if (!projectId) return;
    
    setIsLoading(true);
    try {
      const response = await apiClient.post<{ validation: ValidationResult }>(
        `/api/ontology/validate/${projectId}`
      );
      setValidationResult(response.validation);
      setActiveTab('Validation');
    } catch (error) {
      console.error('Validation failed:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleRunReasoner = async () => {
    if (!projectId) return;
    
    setIsLoading(true);
    try {
      await apiClient.post(`/api/ontology/${projectId}/reasoner/run`);
      await loadProject(projectId);
      console.log('Reasoner executed successfully');
    } catch (error) {
      console.error('Reasoner failed:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleSearch = async (query: string) => {
    if (!projectId || !query.trim()) {
      setSearchQuery('');
      // Note: This does not reload the original tree, it just clears the search query.
      // The original data is still in the 'classes' state, etc.
      // We rely on the filter logic below.
      return;
    }
    
    setSearchQuery(query);
    
    try {
      const response = await apiClient.get<SelectableItem[]>(
        `/api/graph/${projectId}/search?query=${encodeURIComponent(query)}`
      );
      
      // We assume the search endpoint returns results appropriate for the current tab.
      // This implementation sets the main data array to the search results.
      switch (entitiesTab) {
        case 'Classes':
          setClasses(response as TreeNode[]);
          break;
        // Add other cases as needed
      }
    } catch (error) {
      console.error('Search failed:', error);
    }
  };

  const getFilteredData = (): SelectableItem[] => {
    let data: SelectableItem[] = [];
    
    switch (entitiesTab) {
      case 'Classes':
        data = classes;
        break;
      case 'ObjectProperties':
        data = objectProperties;
        break;
      case 'DataProperties':
        data = dataProperties;
        break;
      case 'AnnotationProperties':
        data = annotationProperties;
        break;
      case 'Individuals':
        data = individuals;
        break;
      case 'Datatypes':
        data = datatypes.map(dt => ({ id: dt, label: dt.split('#').pop() || dt, annotations: {} }));
        break;
    }
    
    // This logic is slightly different from the previous file.
    // If search is active, we assume 'data' already *is* the search results
    // from handleSearch. If no search is active, we just return the full data.
    if (searchQuery && data.length === 0) {
      return [];
    }
    
    return data;
  };

  const handleSelectItem = (item: SelectableItem) => {
    setSelectedItem(item);
  };

  const handleToggleNode = async (nodeId: string) => {
    const index = expandedNodes.indexOf(nodeId);
    
    if (index > -1) {
      setExpandedNodes(expandedNodes.filter(id => id !== nodeId));
    } else {
      setExpandedNodes([...expandedNodes, nodeId]);
      
      if (entitiesTab === 'Classes') {
        try {
          const response = await apiClient.get<TreeNode[]>(
            `/api/graph/${projectId}/hierarchy/children?parentIri=${encodeURIComponent(nodeId)}`
          );
          
          const updateTreeWithChildren = (nodes: TreeNode[]): TreeNode[] => {
            return nodes.map(node => {
              if (node.id === nodeId) {
                return { ...node, children: response };
              }
              if (node.children) {
                return { ...node, children: updateTreeWithChildren(node.children) };
              }
              return node;
            });
          };
          
          setClasses(updateTreeWithChildren(classes));
        } catch (error) {
          console.error('Failed to load children:', error);
        }
      }
    }
  };

  const handleAddItem = async (type: 'subclass' | 'sibling' | 'individual') => {
    if (!projectId) return;
    
    const label = prompt(`Enter ${type} name:`);
    if (!label) return;
    
    const iri = `:${label.replace(/\s+/g, '')}`;
    
    try {
      if (type === 'subclass' && selectedItem) {
        await apiClient.post(`/api/ontology/classes/${projectId}`, {
          iri,
          label,
          parentIri: selectedItem.id
        });
      } else if (type === 'sibling' && selectedItem && 'parent' in selectedItem) {
        await apiClient.post(`/api/ontology/classes/${projectId}`, {
          iri,
          label,
          parentIri: (selectedItem as TreeNode).parent
        });
      } else if (type === 'individual') {
        const classIri = (selectedItem && 'children' in selectedItem) ? selectedItem.id : 'http://www.w3.org/2002/07/owl#Thing';
        await apiClient.post(`/api/ontology/individuals/${projectId}`, {
          iri,
          label,
          types: [classIri]
        });
      }
      
      await loadProject(projectId);
    } catch (error) {
      console.error('Failed to add item:', error);
      alert('Failed to create item');
    }
  };

  const handleDeleteItem = async () => {
    if (!projectId || !selectedItem) return;
    
    if (!confirm(`Delete "${selectedItem.label}"?`)) return;
    
    try {
      if (entitiesTab === 'Classes') {
        await apiClient.delete(`/api/ontology/classes/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      } else if (entitiesTab === 'Individuals') {
        await apiClient.delete(`/api/ontology/individuals/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      } else if (entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties' || entitiesTab === 'AnnotationProperties') {
        await apiClient.delete(`/api/ontology/properties/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      }
      
      setSelectedItem(null);
      await loadProject(projectId);
    } catch (error) {
      console.error('Failed to delete item:', error);
      alert('Failed to delete item');
    }
  };

  const renderEditor = () => {
    if (!selectedItem) {
      return (
        <div className="flex items-center justify-center h-full text-gray-400">
          <div className="text-center">
            <Box size={48} className="mx-auto mb-4 opacity-20" />
            <p>Select an entity to edit</p>
          </div>
        </div>
      );
    }
    
    if (entitiesTab === 'Classes') {
      return (
        <ClassEditor
          item={selectedItem as TreeNode}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    } else if (entitiesTab === 'ObjectProperties' || entitiesTab === 'DataProperties') {
      return (
        <PropertyEditor
          item={selectedItem as Property}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    } else if (entitiesTab === 'Individuals') {
      return (
        <IndividualEditor
          item={selectedItem as Individual}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    }
    
    return null;
  };

  const renderContent = () => {
    if (isLoading && !projectId) {
      return (
        <div className="flex items-center justify-center h-full">
          <div className="text-center">
            <Loader2 size={48} className="animate-spin text-purple-600 mx-auto mb-4" />
            <p className="text-gray-600">Loading ontology...</p>
            {projectStatus && (
              <p className="text-sm text-gray-500 mt-2">{projectStatus.statusMessage}</p>
            )}
          </div>
        </div>
      );
    } else if (!projectId) {
      return (
        <div className="flex items-center justify-center h-full">
          <div className="text-center">
            <Package size={64} className="mx-auto mb-4 text-gray-300" />
            <h2 className="text-xl font-semibold text-gray-700 mb-2">No Ontology Loaded</h2>
            <p className="text-gray-500 mb-6">Upload an OWL file to get started</p>
            <label className="inline-flex items-center gap-2 px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 cursor-pointer transition-colors">
              <Upload size={20} />
              <span>Upload Ontology</span>
              <input
                type="file"
                accept=".owl,.rdf,.xml"
                onChange={handleFileUpload}
                className="hidden"
              />
            </label>
          </div>
        </div>
      );
    } else {
      switch(activeTab) {
        case 'Entities':
          return (
            <div className="flex h-full">
              <EntityHierarchy
                entitiesTab={entitiesTab}
                filteredData={getFilteredData()}
                selectedItem={selectedItem}
                expandedNodes={expandedNodes}
                searchQuery={searchQuery}
                onSearchQueryChange={handleSearch}
                onSelectItem={handleSelectItem}
                onToggleNode={handleToggleNode}
                onAddItem={handleAddItem}
                onDeleteItem={handleDeleteItem}
              />
              <div className="flex-1 p-4 overflow-y-auto bg-gray-50">
                {renderEditor()}
              </div>
            </div>
          );
        case 'SPARQL':
          return <SparqlQueryEditor projectId={projectId} prefixes={prefixes} />;
        case 'Validation':
          return <ValidationPanel projectId={projectId} validationResult={validationResult} onValidate={handleValidate} />;
        case 'Statistics':
          return <StatisticsPanel projectId={projectId} statistics={statistics} />;
        default:
          const plugin = pluginManager.getPlugin(activeTab);
          if (plugin?.component) {
            const PluginComponent = plugin.component;
            return <PluginComponent projectId={projectId} onNodeClick={handleSelectItem} />;
          }
          return null;
      }
    }
  };

  const entitiesTabsConfig = [
    { id: 'Classes', label: 'Classes', icon: Package },
    { id: 'ObjectProperties', label: 'Object Properties', icon: GitBranch },
    { id: 'DataProperties', label: 'Data Properties', icon: Database },
    { id: 'AnnotationProperties', label: 'Annotations', icon: Tag },
    { id: 'Individuals', label: 'Individuals', icon: User },
    { id: 'Datatypes', label: 'Datatypes', icon: Type },
  ];

  return (
    <div className="h-screen flex flex-col bg-gray-100">
      <header className="bg-gradient-to-r from-purple-600 to-indigo-600 text-white p-4 shadow-lg">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <h1 className="text-2xl font-bold">OntoCode</h1>
            {metadata && (
              <div className="text-sm opacity-90">
                <span>{metadata.ontologyIRI?.split('/').pop() || 'Untitled Ontology'}</span>
                {projectStatus && (
                  <span className="ml-3 px-2 py-1 bg-white/20 rounded text-xs">
                    {projectStatus.status}
                  </span>
                )}
              </div>
            )}
          </div>
          
          <div className="flex items-center gap-2">
            <label className="flex items-center gap-2 px-4 py-2 bg-white text-purple-600 rounded-lg hover:bg-purple-50 cursor-pointer transition-colors">
              <Upload size={18} />
              <span className="text-sm font-medium">Upload</span>
              <input
                type="file"
                accept=".owl,.rdf,.xml"
                onChange={handleFileUpload}
                className="hidden"
              />
            </label>
            
            <button
              onClick={handleExport}
              disabled={!projectId}
              className="flex items-center gap-2 px-4 py-2 bg-white text-purple-600 rounded-lg hover:bg-purple-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Download size={18} />
              <span className="text-sm font-medium">Export</span>
            </button>
            
            <button
              onClick={handleSave}
              disabled={!selectedItem || isSaving}
              className="flex items-center gap-2 px-4 py-2 bg-white text-purple-600 rounded-lg hover:bg-purple-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {isSaving ? <Loader2 size={18} className="animate-spin" /> : <Save size={18} />}
              <span className="text-sm font-medium">Save</span>
            </button>
            
            <button
              onClick={handleValidate}
              disabled={!projectId || isLoading}
              className="flex items-center gap-2 px-4 py-2 bg-white text-purple-600 rounded-lg hover:bg-purple-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Shield size={18} />
              <span className="text-sm font-medium">Validate</span>
            </button>
            
            <button
              onClick={handleRunReasoner}
              disabled={!projectId || isLoading}
              className="flex items-center gap-2 px-4 py-2 bg-white text-purple-600 rounded-lg hover:bg-purple-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {isLoading ? <Loader2 size={18} className="animate-spin" /> : <Play size={18} />}
              <span className="text-sm font-medium">Reason</span>
            </button>
          </div>
        </div>
      </header>

      <div className="bg-white border-b border-gray-200 flex items-center px-4">
        <button
          onClick={() => setActiveTab('Entities')}
          className={`px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'Entities'
              ? 'border-purple-600 text-purple-600'
              : 'border-transparent text-gray-600 hover:text-gray-900'
          }`}
        >
          <FileText size={16} className="inline mr-2" />
          Entities
        </button>
        
        <button
          onClick={() => setActiveTab('SPARQL')}
          className={`px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'SPARQL'
              ? 'border-purple-600 text-purple-600'
              : 'border-transparent text-gray-600 hover:text-gray-900'
          }`}
        >
          <Search size={16} className="inline mr-2" />
          SPARQL
        </button>
        
        {pluginManager.getAllPlugins().map(plugin => (
          <button
            key={plugin.id}
            onClick={() => {
              pluginManager.activatePlugin(plugin.id);
              setActiveTab(plugin.id);
            }}
            className={`px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
              activeTab === plugin.id
                ? 'border-purple-600 text-purple-600'
                : 'border-transparent text-gray-600 hover:text-gray-900'
            }`}
          >
            <plugin.icon size={16} className="inline mr-2" />
            {plugin.name}
          </button>
        ))}
        
        <button
          onClick={() => setActiveTab('Validation')}
          className={`px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'Validation'
              ? 'border-purple-600 text-purple-600'
              : 'border-transparent text-gray-600 hover:text-gray-900'
          }`}
        >
          {validationResult?.isValid ? (
            <CheckCircle size={16} className="inline mr-2 text-green-600" />
          ) : validationResult ? (
            <AlertTriangle size={16} className="inline mr-2 text-yellow-600" />
          ) : (
            <Shield size={16} className="inline mr-2" />
          )}
          Validation
        </button>
        
        <button
          onClick={() => setActiveTab('Statistics')}
          className={`px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'Statistics'
              ? 'border-purple-600 text-purple-600'
              : 'border-transparent text-gray-600 hover:text-gray-900'
          }`}
        >
          <BarChart3 size={16} className="inline mr-2" />
          Statistics
        </button>
      </div>

      {activeTab === 'Entities' && (
        <div className="bg-gray-50 border-b border-gray-200 flex items-center px-4 gap-1">
          {entitiesTabsConfig.map(tab => (
            <button
              key={tab.id}
              onClick={() => setEntitiesTab(tab.id as EntitiesTabType)}
              className={`px-3 py-2 text-xs font-medium rounded-t transition-colors flex items-center gap-2 ${
                entitiesTab === tab.id
                  ? 'bg-white text-purple-600 shadow-sm'
                  : 'text-gray-600 hover:bg-white/50'
              }`}
            >
              <tab.icon size={14} />
              {tab.label}
            </button>
          ))}
        </div>
      )}

      <div className="flex-1 overflow-hidden">
        {renderContent()}
      </div>
    </div>
  );
};

export default Dashboard;