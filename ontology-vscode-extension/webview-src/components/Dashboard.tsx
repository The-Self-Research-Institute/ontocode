import React, { useState, useEffect, useCallback } from 'react';
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
  OntologyPrefix, ProjectStatus, ValidationResult, OntologyStatistics 
} from '../types';

type TabType = 'Entities' | 'SPARQL' | 'Validation' | 'Statistics' | string;
type EntitiesTabType = 'Classes' | 'ObjectProperties' | 'DataProperties' | 'AnnotationProperties' | 'Individuals' | 'Datatypes';

const Dashboard: React.FC = () => {
  const [projectId, setProjectId] = useState<string>('');
  const [activeTab, setActiveTab] = useState<TabType>('Entities');
  const [entitiesTab, setEntitiesTab] = useState<EntitiesTabType>('Classes');
  
  // Project state
  const [projectStatus, setProjectStatus] = useState<ProjectStatus | null>(null);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [prefixes, setPrefixes] = useState<OntologyPrefix[]>([]);
  const [statistics, setStatistics] = useState<OntologyStatistics | null>(null);
  
  // Entity data
  const [classes, setClasses] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<Property[]>([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<string[]>([]);
  
  // UI state
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  
  // Active plugins
  const [activePlugins, setActivePlugins] = useState<string[]>([]);

  // Initialize plugin system
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
    };
  }, []);

  // Update plugin context when project changes
  useEffect(() => {
    if (projectId) {
      pluginManager.setContext({ projectId, ontology: metadata });
    }
  }, [projectId, metadata]);

  // Load project data
  const loadProject = useCallback(async (pid: string) => {
    setIsLoading(true);
    try {
      // Load project status
      const statusRes = await apiClient.get<{ data: ProjectStatus }>(`/api/ontology/status/${pid}`);
      setProjectStatus(statusRes.data.data);
      
      if (statusRes.data.data.status !== 'COMPLETED') {
        // Poll for completion
        setTimeout(() => loadProject(pid), 2000);
        return;
      }
      
      // Load all data in parallel
      const [metadataRes, prefixesRes, statsRes, classesRes, objPropsRes, dataPropsRes, annPropsRes, individualsRes, datatypesRes] = 
        await Promise.all([
          apiClient.get<{ data: OntologyMetadata }>(`/api/ontology/metadata/${pid}`),
          apiClient.get<{ data: OntologyPrefix[] }>(`/api/ontology/namespaces/${pid}`),
          apiClient.get<{ data: OntologyStatistics }>(`/api/ontology/statistics/${pid}`),
          apiClient.get<{ classes: TreeNode[] }>(`/api/ontology/all-classes?projectId=${pid}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${pid}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${pid}`),
          apiClient.get<{ data: Property[] }>(`/api/ontology/annotation-properties/${pid}`),
          apiClient.get<{ data: Individual[] }>(`/api/ontology/individuals/${pid}`),
          apiClient.get<{ data: string[] }>(`/api/ontology/datatypes/${pid}`)
        ]);
      
      setMetadata(metadataRes.data.data);
      setPrefixes(prefixesRes.data.data);
      setStatistics(statsRes.data.data);
      setClasses(classesRes.data.classes);
      
      // Filter properties by type
      const allProps = objPropsRes.data.data;
      setObjectProperties(allProps.filter(p => p.type === 'ObjectProperty'));
      setDataProperties(dataPropsRes.data.data.filter(p => p.type === 'DatatypeProperty'));
      setAnnotationProperties(annPropsRes.data.data);
      
      setIndividuals(individualsRes.data.data);
      setDatatypes(datatypesRes.data.data);
      
    } catch (error) {
      console.error('Failed to load project:', error);
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Handle file upload
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

  // Handle export
  const handleExport = async () => {
    if (!projectId) return;
    
    try {
      const response = await apiClient.get<Blob>(`/api/ontology/export/${projectId}`);
      const blob = new Blob([response.data]);
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

  // Handle save
  const handleSave = async () => {
    if (!projectId || !selectedItem) return;
    
    setIsSaving(true);
    try {
      if ('children' in selectedItem) {
        // Class
        await apiClient.put(`/api/ontology/classes/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          parentIri: selectedItem.parent
        });
      } else if ('type' in selectedItem && (selectedItem.type === 'ObjectProperty' || selectedItem.type === 'DatatypeProperty')) {
        // Property
        await apiClient.put(`/api/ontology/properties/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          domains: selectedItem.domains,
          ranges: selectedItem.ranges
        });
      } else if ('types' in selectedItem) {
        // Individual
        await apiClient.put(`/api/ontology/individuals/${projectId}`, {
          iri: selectedItem.id,
          label: selectedItem.label,
          types: selectedItem.types
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

  // Handle validation
  const handleValidate = async () => {
    if (!projectId) return;
    
    setIsLoading(true);
    try {
      const response = await apiClient.post<{ validation: ValidationResult }>(
        `/api/ontology/validate/${projectId}`
      );
      setValidationResult(response.data.validation);
      setActiveTab('Validation');
    } catch (error) {
      console.error('Validation failed:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // Handle reasoner execution
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

  // Handle search using Neo4j fast search
  const handleSearch = async (query: string) => {
    if (!projectId || !query.trim()) {
      setSearchQuery('');
      return;
    }
    
    setSearchQuery(query);
    
    try {
      const response = await apiClient.get<SelectableItem[]>(
        `/api/graph/${projectId}/search?query=${encodeURIComponent(query)}`
      );
      
      // Update the current tab data with search results
      switch (entitiesTab) {
        case 'Classes':
          setClasses(response.data as TreeNode[]);
          break;
        // Add other cases as needed
      }
    } catch (error) {
      console.error('Search failed:', error);
    }
  };

  // Get filtered data based on current tab and search
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
        data = datatypes.map(dt => ({ id: dt, label: dt.split('#').pop() || dt }));
        break;
    }
    
    if (searchQuery && !data.length) {
      // If search is active and we have no data, it means search returned empty
      return [];
    }
    
    return data;
  };

  // Handle entity selection
  const handleSelectItem = (item: SelectableItem) => {
    setSelectedItem(item);
  };

  // Handle node expansion (load children from Neo4j)
  const handleToggleNode = async (nodeId: string) => {
    const index = expandedNodes.indexOf(nodeId);
    
    if (index > -1) {
      setExpandedNodes(expandedNodes.filter(id => id !== nodeId));
    } else {
      setExpandedNodes([...expandedNodes, nodeId]);
      
      // Load children from Neo4j if not already loaded
      if (entitiesTab === 'Classes') {
        try {
          const response = await apiClient.get<TreeNode[]>(
            `/api/graph/${projectId}/hierarchy/children?parentIri=${encodeURIComponent(nodeId)}`
          );
          
          // Update the tree with children
          const updateTreeWithChildren = (nodes: TreeNode[]): TreeNode[] => {
            return nodes.map(node => {
              if (node.id === nodeId) {
                return { ...node, children: response.data };
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

  // Handle adding items
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
          parentIri: selectedItem.parent
        });
      } else if (type === 'individual') {
        const classIri = selectedItem ? selectedItem.id : 'owl:Thing';
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

  // Handle deleting items
  const handleDeleteItem = async () => {
    if (!projectId || !selectedItem) return;
    
    if (!confirm(`Delete "${selectedItem.label}"?`)) return;
    
    try {
      if ('children' in selectedItem) {
        await apiClient.delete(`/api/ontology/classes/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      } else if ('types' in selectedItem) {
        await apiClient.delete(`/api/ontology/individuals/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      } else if ('type' in selectedItem) {
        await apiClient.delete(`/api/ontology/properties/${projectId}?iri=${encodeURIComponent(selectedItem.id)}`);
      }
      
      setSelectedItem(null);
      await loadProject(projectId);
    } catch (error) {
      console.error('Failed to delete item:', error);
      alert('Failed to delete item');
    }
  };

  // Render entity editor
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
    
    if ('children' in selectedItem) {
      return (
        <ClassEditor
          item={selectedItem}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    } else if ('type' in selectedItem && (selectedItem.type === 'ObjectProperty' || selectedItem.type === 'DatatypeProperty')) {
      return (
        <PropertyEditor
          item={selectedItem}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    } else if ('types' in selectedItem) {
      return (
        <IndividualEditor
          item={selectedItem}
          onUpdate={setSelectedItem}
          onAddAnnotation={() => {}}
          onDeleteAnnotation={() => {}}
        />
      );
    }
    
    return null;
  };

  // Render main content based on active tab
  const renderContent = () => {
    if (activeTab === 'Entities') {
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
    } else if (activeTab === 'SPARQL') {
      return <SparqlQueryEditor projectId={projectId} prefixes={prefixes} />;
    } else if (activeTab === 'Validation') {
      return <ValidationPanel projectId={projectId} validationResult={validationResult} onValidate={handleValidate} />;
    } else if (activeTab === 'Statistics') {
      return <StatisticsPanel projectId={projectId} statistics={statistics} />;
    } else {
      // Plugin tabs
      const plugin = pluginManager.getPlugin(activeTab);
      if (plugin?.component) {
        const PluginComponent = plugin.component;
        return <PluginComponent projectId={projectId} onNodeClick={handleSelectItem} />;
      }
    }
    
    return null;
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
      {/* Header */}
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

      {/* Main Tabs */}
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

      {/* Entity Sub-Tabs */}
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

      {/* Main Content */}
      <div className="flex-1 overflow-hidden">
        {isLoading && !projectId ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-purple-600 mx-auto mb-4" />
              <p className="text-gray-600">Loading ontology...</p>
              {projectStatus && (
                <p className="text-sm text-gray-500 mt-2">{projectStatus.statusMessage}</p>
              )}
            </div>
          </div>
        ) : !projectId ? (
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
        ) : (
          renderContent()
        )}
      </div>
    </div>
  );
};

export default Dashboard;