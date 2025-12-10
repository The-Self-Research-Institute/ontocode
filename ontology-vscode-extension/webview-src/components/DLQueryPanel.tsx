/**
 * DL Query Panel - Enhanced Description Logic Query Interface
 * 
 * Based on Protege's DL Query Tab functionality:
 * - Manchester OWL Syntax for class expressions
 * - Query for subclasses, superclasses, equivalent classes, instances
 * - Syntax highlighting and auto-completion hints
 * - Example queries for common patterns
 * 
 * References:
 * - https://protegewiki.stanford.edu/wiki/DLQueryTab
 * - https://oboacademy.github.io/obook/tutorial/basic-dl-query/
 */

import React, { useState, useCallback, useMemo } from 'react';
import { 
  Play, Plus, Loader2, AlertCircle, CheckCircle, 
  ChevronDown, ChevronRight, HelpCircle, BookOpen,
  Copy, Check, Info, Layers, Users, ArrowUp, ArrowDown,
  Equal, Sparkles, Search
} from 'lucide-react';

// Types
interface DLQueryResult {
  type: 'class' | 'individual';
  iri: string;
  label: string;
  description?: string;
}

interface DLQueryResponse {
  success: boolean;
  query: string;
  queryType: string[];
  results: {
    superclasses?: DLQueryResult[];
    directSuperclasses?: DLQueryResult[];
    subclasses?: DLQueryResult[];
    directSubclasses?: DLQueryResult[];
    equivalentClasses?: DLQueryResult[];
    instances?: DLQueryResult[];
    directInstances?: DLQueryResult[];
  };
  executionTime: number;
  error?: string;
}

interface OntologyMetrics {
  classCount?: number;
  objectPropertyCount?: number;
  dataPropertyCount?: number;
  individualCount?: number;
}

interface DLQueryPanelProps {
  projectId: string;
  classes: { id: string; label: string }[];
  objectProperties: { id: string; label: string }[];
  dataProperties: { id: string; label: string }[];
  individuals: { id: string; label: string }[];
  metrics?: OntologyMetrics;
  apiClient: {
    post: <T>(url: string, data: any) => Promise<T>;
    get: <T>(url: string) => Promise<T>;
  };
  onAddToOntology?: (expression: string, className: string) => void;
  showNotification?: (message: string, type: 'success' | 'error' | 'info') => void;
}

// Manchester OWL Syntax Keywords
const MANCHESTER_KEYWORDS = [
  'and', 'or', 'not', 'some', 'only', 'value', 'Self',
  'min', 'max', 'exactly', 'that', 'inverse'
];

// Generate example queries dynamically based on available entities
const generateExampleQueries = (
  classes: { id: string; label: string }[],
  objectProperties: { id: string; label: string }[],
  dataProperties: { id: string; label: string }[]
) => {
  // Filter to only use names without spaces (to avoid parsing issues)
  // Names with spaces require special handling that may not work across all backends
  const safeClasses = classes
    .filter(c => !c.id.includes('owl#Thing') && !/[\s]/.test(c.label));
  
  const safeObjProps = objectProperties
    .filter(p => !p.id.includes('owl#top') && !/[\s]/.test(p.label));
  
  const safeDataProps = dataProperties
    .filter(p => !p.id.includes('owl#top') && !/[\s]/.test(p.label));

  const classNames = safeClasses.slice(0, 5).map(c => c.label);
  const objPropNames = safeObjProps.slice(0, 3).map(p => p.label);
  const dataPropNames = safeDataProps.slice(0, 3).map(p => p.label);

  const cls1 = classNames[0] || 'Thing';
  const cls2 = classNames[1] || classNames[0] || 'Thing';
  const cls3 = classNames[2] || classNames[0] || 'Thing';
  const objProp = objPropNames[0];
  const dataProp = dataPropNames[0];

  // Build queries, only including property-based queries if we have safe properties
  const basicQueries = [
    { name: 'Simple class', expression: cls1, description: `Find all subclasses/instances of ${cls1}` },
    { name: 'Union (or)', expression: `${cls1} or ${cls2}`, description: `Either ${cls1} or ${cls2}` },
    { name: 'Complement (not)', expression: `not ${cls1}`, description: `Everything that is not a ${cls1}` },
  ];

  const existentialQueries = objProp ? [
    { name: 'Has some relation', expression: `${objProp} some ${cls2}`, description: `Things with ${objProp} to some ${cls2}` },
  ] : [];
  
  if (dataProp) {
    existentialQueries.push(
      { name: 'Has some data', expression: `${dataProp} some xsd:integer`, description: `Things with some ${dataProp} value` }
    );
  }

  const universalQueries = objProp ? [
    { name: 'Only restriction', expression: `${objProp} only ${cls1}`, description: `Things where all ${objProp} are ${cls1}` },
    { name: 'Intersection + only', expression: `${cls1} and ${objProp} only ${cls2}`, description: `${cls1} with ${objProp} only to ${cls2}` },
  ] : [];

  const cardinalityQueries = objProp ? [
    { name: 'Minimum', expression: `${objProp} min 1`, description: 'Things with at least 1 relation' },
    { name: 'Maximum', expression: `${objProp} max 3`, description: 'Things with at most 3 relations' },
    { name: 'Exactly', expression: `${objProp} exactly 2`, description: 'Things with exactly 2 relations' },
    { name: 'Qualified cardinality', expression: `${objProp} min 1 ${cls1}`, description: `At least 1 ${objProp} to ${cls1}` },
  ] : [];

  const complexQueries = [
    { name: 'Intersection (and)', expression: `${cls1} and ${cls2}`, description: `Things that are both ${cls1} and ${cls2}` },
  ];
  
  if (objProp) {
    complexQueries.push(
      { name: 'Nested restriction', expression: `${cls1} and ${objProp} some ${cls2}`, description: `${cls1} with ${objProp} to some ${cls2}` },
      { name: 'Inverse property', expression: `inverse(${objProp}) some ${cls1}`, description: `Things that are ${objProp} target of some ${cls1}` }
    );
  }

  const result = [
    { category: 'Basic Class Queries', queries: basicQueries },
  ];

  if (existentialQueries.length > 0) {
    result.push({ category: 'Existential Restrictions (some)', queries: existentialQueries });
  }
  if (universalQueries.length > 0) {
    result.push({ category: 'Universal Restrictions (only)', queries: universalQueries });
  }
  if (cardinalityQueries.length > 0) {
    result.push({ category: 'Cardinality Restrictions', queries: cardinalityQueries });
  }
  result.push({ category: 'Complex Expressions', queries: complexQueries });

  return result;
};

// Query type options (matching Protege's checkboxes)
const QUERY_TYPES = [
  { id: 'directSuperclasses', label: 'Direct superclasses', icon: ArrowUp, description: 'Immediate parent classes' },
  { id: 'superclasses', label: 'Superclasses', icon: ArrowUp, description: 'All ancestor classes' },
  { id: 'equivalentClasses', label: 'Equivalent classes', icon: Equal, description: 'Logically equivalent classes' },
  { id: 'directSubclasses', label: 'Direct subclasses', icon: ArrowDown, description: 'Immediate child classes' },
  { id: 'subclasses', label: 'Subclasses', icon: ArrowDown, description: 'All descendant classes' },
  { id: 'instances', label: 'Instances', icon: Users, description: 'All individuals of this class' },
  { id: 'directInstances', label: 'Direct instances', icon: Users, description: 'Direct individuals (not inherited)' },
];

export const DLQueryPanel: React.FC<DLQueryPanelProps> = ({
  projectId,
  classes,
  objectProperties,
  dataProperties,
  individuals,
  metrics,
  apiClient,
  onAddToOntology,
  showNotification
}) => {
  // Generate dynamic example queries based on actual ontology entities
  const exampleQueries = useMemo(
    () => generateExampleQueries(classes, objectProperties, dataProperties),
    [classes, objectProperties, dataProperties]
  );
  
  // Set initial query based on first available class
  const initialQuery = useMemo(() => {
    const firstClass = classes.find(c => !c.id.includes('owl#Thing'));
    return firstClass?.label || 'Thing';
  }, [classes]);
  
  // State
  const [query, setQuery] = useState(initialQuery);
  const [isLoading, setIsLoading] = useState(false);
  const [results, setResults] = useState<DLQueryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedTypes, setSelectedTypes] = useState<string[]>(['subclasses', 'instances']);
  const [showExamples, setShowExamples] = useState(true);
  const [showSyntaxHelp, setShowSyntaxHelp] = useState(false);
  const [nameFilter, setNameFilter] = useState('');
  const [copied, setCopied] = useState(false);
  const [newClassName, setNewClassName] = useState('');
  const [showAddDialog, setShowAddDialog] = useState(false);

  // Autocomplete suggestions based on current input
  const suggestions = useMemo(() => {
    const words = query.split(/\s+/);
    const lastWord = words[words.length - 1]?.toLowerCase() || '';
    
    if (lastWord.length < 2) return [];
    
    const allSuggestions: { type: string; value: string; label: string }[] = [];
    
    // Add class suggestions
    classes.forEach(c => {
      if (c.label.toLowerCase().includes(lastWord)) {
        allSuggestions.push({ type: 'class', value: c.label, label: `${c.label} (Class)` });
      }
    });
    
    // Add property suggestions
    objectProperties.forEach(p => {
      if (p.label.toLowerCase().includes(lastWord)) {
        allSuggestions.push({ type: 'property', value: p.label, label: `${p.label} (Object Property)` });
      }
    });
    
    dataProperties.forEach(p => {
      if (p.label.toLowerCase().includes(lastWord)) {
        allSuggestions.push({ type: 'property', value: p.label, label: `${p.label} (Data Property)` });
      }
    });
    
    // Add keyword suggestions
    MANCHESTER_KEYWORDS.forEach(kw => {
      if (kw.toLowerCase().startsWith(lastWord)) {
        allSuggestions.push({ type: 'keyword', value: kw, label: `${kw} (keyword)` });
      }
    });
    
    return allSuggestions.slice(0, 10);
  }, [query, classes, objectProperties, dataProperties]);

  // Execute query
  const handleExecuteQuery = useCallback(async () => {
    if (!query.trim()) {
      setError('Please enter a class expression');
      return;
    }
    
    if (selectedTypes.length === 0) {
      setError('Please select at least one query type');
      return;
    }
    
    setIsLoading(true);
    setError(null);
    setResults(null);
    
    try {
      const response = await apiClient.post<DLQueryResponse>(
        `/api/ontology/${projectId}/dl-query`,
        { 
          expression: query,
          queryTypes: selectedTypes
        }
      );
      
      setResults(response);
      
      if (!response.success && response.error) {
        setError(response.error);
      }
    } catch (err: any) {
      // Fallback: simulate results for demo purposes if backend not available
      console.warn('DL Query API not available, using simulated results');
      
      // Simple pattern matching for demo
      const simulatedResults: DLQueryResponse = {
        success: true,
        query: query,
        queryType: selectedTypes,
        results: {},
        executionTime: 150
      };
      
      // Find matching classes/individuals based on query
      const queryLower = query.toLowerCase();
      
      if (selectedTypes.includes('subclasses') || selectedTypes.includes('directSubclasses')) {
        const matchingClasses = classes
          .filter(c => {
            const label = c.label.toLowerCase();
            // Simple heuristic: if query mentions a class, show related classes
            return queryLower.includes(label) || label.includes(queryLower.split(' ')[0]);
          })
          .slice(0, 10)
          .map(c => ({ type: 'class' as const, iri: c.id, label: c.label }));
        
        if (selectedTypes.includes('subclasses')) {
          simulatedResults.results.subclasses = matchingClasses;
        }
        if (selectedTypes.includes('directSubclasses')) {
          simulatedResults.results.directSubclasses = matchingClasses.slice(0, 5);
        }
      }
      
      if (selectedTypes.includes('instances') || selectedTypes.includes('directInstances')) {
        const matchingIndividuals = individuals
          .filter(i => {
            const label = i.label.toLowerCase();
            return queryLower.split(' ').some(word => label.includes(word) || word.includes(label.substring(0, 3)));
          })
          .slice(0, 15)
          .map(i => ({ type: 'individual' as const, iri: i.id, label: i.label }));
        
        if (selectedTypes.includes('instances')) {
          simulatedResults.results.instances = matchingIndividuals;
        }
        if (selectedTypes.includes('directInstances')) {
          simulatedResults.results.directInstances = matchingIndividuals.slice(0, 8);
        }
      }
      
      setResults(simulatedResults);
      setError('Note: Using simulated results. Backend DL Query endpoint not available.');
    } finally {
      setIsLoading(false);
    }
  }, [query, selectedTypes, projectId, apiClient, classes, individuals]);

  // Toggle query type selection
  const toggleQueryType = (typeId: string) => {
    setSelectedTypes(prev => 
      prev.includes(typeId) 
        ? prev.filter(t => t !== typeId)
        : [...prev, typeId]
    );
  };

  // Load example query
  const loadExample = (expression: string) => {
    setQuery(expression);
    setResults(null);
    setError(null);
  };

  // Copy results to clipboard
  const copyResults = () => {
    if (!results) return;
    
    const text = Object.entries(results.results)
      .map(([type, items]) => {
        if (!items || items.length === 0) return '';
        return `${type}:\n${items.map(i => `  - ${i.label}`).join('\n')}`;
      })
      .filter(Boolean)
      .join('\n\n');
    
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // Add query as defined class to ontology
  const handleAddToOntology = () => {
    if (!newClassName.trim()) {
      showNotification?.('Please enter a class name', 'error');
      return;
    }
    onAddToOntology?.(query, newClassName);
    setShowAddDialog(false);
    setNewClassName('');
    showNotification?.(`Created class "${newClassName}" with definition: ${query}`, 'success');
  };

  // Filter results by name
  const filterResults = (items: DLQueryResult[] | undefined) => {
    if (!items) return [];
    if (!nameFilter.trim()) return items;
    return items.filter(item => 
      item.label.toLowerCase().includes(nameFilter.toLowerCase())
    );
  };

  // Count total results
  const totalResults = useMemo(() => {
    if (!results?.results) return 0;
    return Object.values(results.results).reduce((sum, items) => sum + (items?.length || 0), 0);
  }, [results]);

  return (
    <div className="flex h-full bg-gray-50">
      {/* Main Query Area */}
      <main className="flex-1 flex flex-col p-3 overflow-hidden">
        {/* Query Input Section */}
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm mb-3">
          <div className="p-3 border-b border-gray-100 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Layers size={18} className="text-purple-600" />
              <h3 className="font-semibold text-gray-800">DL Query</h3>
              <span className="text-xs text-gray-500">(Manchester OWL Syntax)</span>
            </div>
            <button
              onClick={() => setShowSyntaxHelp(!showSyntaxHelp)}
              className="text-xs text-purple-600 hover:text-purple-800 flex items-center gap-1"
            >
              <HelpCircle size={14} />
              Syntax Help
            </button>
          </div>
          
          {/* Syntax Help Panel */}
          {showSyntaxHelp && (
            <div className="p-3 bg-purple-50 border-b border-purple-100 text-xs">
              <h4 className="font-semibold text-purple-800 mb-2">Manchester OWL Syntax Quick Reference</h4>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="font-medium text-purple-700">Logical Operators:</p>
                  <ul className="text-purple-600 ml-2">
                    <li><code className="bg-purple-100 px-1 rounded">and</code> - Intersection</li>
                    <li><code className="bg-purple-100 px-1 rounded">or</code> - Union</li>
                    <li><code className="bg-purple-100 px-1 rounded">not</code> - Complement</li>
                  </ul>
                </div>
                <div>
                  <p className="font-medium text-purple-700">Restrictions:</p>
                  <ul className="text-purple-600 ml-2">
                    <li><code className="bg-purple-100 px-1 rounded">some</code> - Existential (∃)</li>
                    <li><code className="bg-purple-100 px-1 rounded">only</code> - Universal (∀)</li>
                    <li><code className="bg-purple-100 px-1 rounded">value</code> - Has value</li>
                  </ul>
                </div>
                <div>
                  <p className="font-medium text-purple-700">Cardinality:</p>
                  <ul className="text-purple-600 ml-2">
                    <li><code className="bg-purple-100 px-1 rounded">min N</code> - At least N</li>
                    <li><code className="bg-purple-100 px-1 rounded">max N</code> - At most N</li>
                    <li><code className="bg-purple-100 px-1 rounded">exactly N</code> - Exactly N</li>
                  </ul>
                </div>
                <div>
                  <p className="font-medium text-purple-700">Examples:</p>
                  <ul className="text-purple-600 ml-2">
                    <li><code className="bg-purple-100 px-1 rounded">Person and hasAge some integer</code></li>
                    <li><code className="bg-purple-100 px-1 rounded">hasChild min 2 Woman</code></li>
                  </ul>
                </div>
              </div>
            </div>
          )}
          
          {/* Query Textarea */}
          <div className="p-3">
            <textarea
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Enter Manchester OWL class expression...&#10;Examples: Person, hasChild some Man, Pizza and hasTopping some MozzarellaTopping"
              className="w-full h-24 border border-gray-200 rounded-lg p-3 font-mono text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none text-gray-900"
              onKeyDown={e => {
                if (e.key === 'Enter' && e.ctrlKey) {
                  e.preventDefault();
                  handleExecuteQuery();
                }
              }}
            />
            
            {/* Action Buttons */}
            <div className="flex items-center gap-2 mt-2">
              <button
                onClick={handleExecuteQuery}
                disabled={isLoading || !query.trim()}
                className="px-4 py-2 bg-purple-600 text-white text-sm font-medium rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed flex items-center gap-2 transition-colors"
              >
                {isLoading ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : (
                  <Play size={16} />
                )}
                Execute (Ctrl+Enter)
              </button>
              
              <button
                onClick={() => setShowAddDialog(true)}
                disabled={!query.trim()}
                className="px-3 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200 disabled:opacity-50 flex items-center gap-2 transition-colors"
              >
                <Plus size={16} />
                Add to Ontology
              </button>
              
              {results && (
                <button
                  onClick={copyResults}
                  className="px-3 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200 flex items-center gap-2 transition-colors ml-auto"
                >
                  {copied ? <Check size={16} className="text-green-600" /> : <Copy size={16} />}
                  {copied ? 'Copied!' : 'Copy Results'}
                </button>
              )}
            </div>
          </div>
        </div>
        
        {/* Error Message */}
        {error && (
          <div className="mb-3 p-3 bg-red-50 border border-red-200 rounded-lg flex items-start gap-2">
            <AlertCircle size={18} className="text-red-600 mt-0.5 flex-shrink-0" />
            <div>
              <p className="text-sm text-red-800 font-medium">Query Error</p>
              <p className="text-xs text-red-600 mt-1">{error}</p>
            </div>
          </div>
        )}
        
        {/* Results Section */}
        <div className="flex-1 bg-white rounded-lg border border-gray-200 shadow-sm overflow-hidden flex flex-col">
          <div className="p-3 border-b border-gray-100 flex items-center justify-between bg-gray-50">
            <div className="flex items-center gap-2">
              <h3 className="font-semibold text-gray-800">Query Results</h3>
              {results && (
                <span className="text-xs bg-purple-100 text-purple-700 px-2 py-0.5 rounded-full">
                  {totalResults} result{totalResults !== 1 ? 's' : ''} in {results.executionTime}ms
                </span>
              )}
            </div>
            
            {/* Results filter */}
            <div className="flex items-center gap-2">
              <Search size={14} className="text-gray-400" />
              <input
                type="text"
                value={nameFilter}
                onChange={e => setNameFilter(e.target.value)}
                placeholder="Filter results..."
                className="text-xs border border-gray-200 rounded px-2 py-1 w-40 focus:ring-1 focus:ring-purple-500"
              />
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto p-3">
            {isLoading ? (
              <div className="flex items-center justify-center h-full text-gray-500">
                <Loader2 size={24} className="animate-spin mr-2" />
                <span>Executing query...</span>
              </div>
            ) : results ? (
              <div className="space-y-4">
                {Object.entries(results.results).map(([type, items]) => {
                  const filteredItems = filterResults(items);
                  if (!filteredItems || filteredItems.length === 0) return null;
                  
                  const typeConfig = QUERY_TYPES.find(t => t.id === type);
                  const Icon = typeConfig?.icon || Layers;
                  
                  return (
                    <div key={type} className="border border-gray-100 rounded-lg overflow-hidden">
                      <div className="px-3 py-2 bg-gray-50 flex items-center gap-2 border-b border-gray-100">
                        <Icon size={14} className="text-purple-600" />
                        <span className="font-medium text-sm text-gray-700 capitalize">
                          {typeConfig?.label || type.replace(/([A-Z])/g, ' $1').trim()}
                        </span>
                        <span className="text-xs text-gray-500">({filteredItems.length})</span>
                      </div>
                      <div className="p-2 max-h-48 overflow-y-auto">
                        {filteredItems.map((item, idx) => (
                          <div
                            key={`${item.iri}-${idx}`}
                            className="px-2 py-1.5 text-sm hover:bg-purple-50 rounded cursor-pointer flex items-center gap-2"
                            title={item.iri}
                          >
                            {item.type === 'class' ? (
                              <div className="w-3 h-3 rounded-full bg-amber-400 border border-amber-500" />
                            ) : (
                              <div className="w-3 h-3 rounded-sm bg-purple-400 border border-purple-500" />
                            )}
                            <span className="text-gray-800">{item.label}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  );
                })}
                
                {totalResults === 0 && (
                  <div className="text-center py-8 text-gray-500">
                    <Info size={32} className="mx-auto mb-2 opacity-50" />
                    <p className="text-sm">No results found for this query.</p>
                    <p className="text-xs mt-1">Try a different class expression or query type.</p>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center h-full text-gray-400">
                <Sparkles size={32} className="mb-2 opacity-50" />
                <p className="text-sm">Enter a class expression and click Execute</p>
                <p className="text-xs mt-1">Results will appear here</p>
              </div>
            )}
          </div>
        </div>
      </main>
      
      {/* Right Sidebar - Query Options & Examples */}
      <aside className="w-72 bg-white border-l border-gray-200 flex flex-col overflow-hidden">
        {/* Query Type Selection */}
        <div className="p-3 border-b border-gray-100">
          <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide mb-2">Query For</h3>
          <div className="space-y-1">
            {QUERY_TYPES.map(type => (
              <label
                key={type.id}
                className="flex items-center gap-2 p-1.5 rounded hover:bg-gray-50 cursor-pointer group"
                title={type.description}
              >
                <input
                  type="checkbox"
                  checked={selectedTypes.includes(type.id)}
                  onChange={() => toggleQueryType(type.id)}
                  className="rounded text-purple-600 focus:ring-purple-500"
                />
                <type.icon size={14} className="text-gray-400 group-hover:text-purple-500" />
                <span className="text-xs text-gray-700">{type.label}</span>
              </label>
            ))}
          </div>
        </div>
        
        {/* Example Queries */}
        <div className="flex-1 overflow-y-auto">
          <div 
            className="p-3 border-b border-gray-100 flex items-center justify-between cursor-pointer hover:bg-gray-50"
            onClick={() => setShowExamples(!showExamples)}
          >
            <div className="flex items-center gap-2">
              <BookOpen size={14} className="text-purple-600" />
              <h3 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">Example Queries</h3>
            </div>
            {showExamples ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </div>
          
          {showExamples && (
            <div className="p-2">
              {exampleQueries.map(category => (
                <div key={category.category} className="mb-3">
                  <h4 className="text-xs font-medium text-gray-500 px-2 mb-1">{category.category}</h4>
                  {category.queries.map(ex => (
                    <button
                      key={ex.name}
                      onClick={() => loadExample(ex.expression)}
                      className="w-full text-left px-2 py-1.5 text-xs rounded hover:bg-purple-50 group"
                      title={ex.description}
                    >
                      <div className="font-medium text-gray-700 group-hover:text-purple-700">{ex.name}</div>
                      <div className="text-gray-400 font-mono text-[10px] truncate">{ex.expression}</div>
                    </button>
                  ))}
                </div>
              ))}
            </div>
          )}
        </div>
        
        {/* Ontology Statistics */}
        <div className="p-3 border-t border-gray-100 bg-gray-50">
          <h3 className="text-xs font-semibold text-gray-600 mb-2">Available Entities</h3>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div className="bg-white rounded p-2 border">
              <div className="font-bold text-amber-600">{metrics?.classCount ?? classes.length}</div>
              <div className="text-gray-500">Classes</div>
            </div>
            <div className="bg-white rounded p-2 border">
              <div className="font-bold text-blue-600">{metrics?.objectPropertyCount ?? objectProperties.length}</div>
              <div className="text-gray-500">Obj Props</div>
            </div>
            <div className="bg-white rounded p-2 border">
              <div className="font-bold text-green-600">{metrics?.dataPropertyCount ?? dataProperties.length}</div>
              <div className="text-gray-500">Data Props</div>
            </div>
            <div className="bg-white rounded p-2 border">
              <div className="font-bold text-purple-600">{metrics?.individualCount ?? individuals.length}</div>
              <div className="text-gray-500">Individuals</div>
            </div>
          </div>
        </div>
      </aside>
      
      {/* Add to Ontology Dialog */}
      {showAddDialog && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-96 overflow-hidden">
            <div className="p-4 border-b border-gray-200">
              <h3 className="font-semibold text-gray-800">Add Class to Ontology</h3>
              <p className="text-xs text-gray-500 mt-1">Create a defined class from this DL query expression</p>
            </div>
            <div className="p-4">
              <label className="block text-sm font-medium text-gray-700 mb-1">Class Name</label>
              <input
                type="text"
                value={newClassName}
                onChange={e => setNewClassName(e.target.value)}
                placeholder="e.g., AdultPerson"
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                autoFocus
              />
              <div className="mt-3 p-2 bg-gray-50 rounded-lg">
                <label className="block text-xs font-medium text-gray-500 mb-1">Definition</label>
                <code className="text-xs text-purple-700 break-all">{query}</code>
              </div>
            </div>
            <div className="p-4 bg-gray-50 border-t border-gray-200 flex justify-end gap-2">
              <button
                onClick={() => setShowAddDialog(false)}
                className="px-4 py-2 text-sm text-gray-700 hover:bg-gray-200 rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleAddToOntology}
                disabled={!newClassName.trim()}
                className="px-4 py-2 text-sm bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 transition-colors"
              >
                Create Class
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DLQueryPanel;
