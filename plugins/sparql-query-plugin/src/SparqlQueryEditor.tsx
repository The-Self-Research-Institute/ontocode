/**
 * SPARQL Query Plugin - Main Component
 * 
 * Full-featured SPARQL query editor with:
 * - Query management (save, load, delete)
 * - Syntax highlighting placeholders
 * - Live query execution
 * - Results table with CSV export
 * - Prefix management
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Play, Save, Loader2, ChevronDown, ChevronRight, Download, Database, Code, Table, FileText, RefreshCw, Copy, Check } from 'lucide-react';
import type { SparqlQuery, SparqlQueryResult, OntologyPrefix, PluginContext, SparqlQueryEditorProps } from './types';

// SPARQL Keywords for basic syntax highlighting
const SPARQL_KEYWORDS = [
  'SELECT', 'CONSTRUCT', 'DESCRIBE', 'ASK', 'WHERE', 'FROM', 'NAMED',
  'PREFIX', 'BASE', 'OPTIONAL', 'UNION', 'FILTER', 'GRAPH', 'ORDER', 'BY',
  'LIMIT', 'OFFSET', 'DISTINCT', 'REDUCED', 'GROUP', 'HAVING', 'VALUES',
  'BIND', 'AS', 'SERVICE', 'MINUS', 'EXISTS', 'NOT', 'IN', 'INSERT', 'DELETE',
  'DATA', 'LOAD', 'CLEAR', 'CREATE', 'DROP', 'COPY', 'MOVE', 'ADD'
];

// Sample queries for new users
const SAMPLE_QUERIES = [
  {
    name: 'List All Classes',
    query: `SELECT DISTINCT ?class ?label WHERE {
  ?class a owl:Class .
  OPTIONAL { ?class rdfs:label ?label }
}
ORDER BY ?label
LIMIT 100`
  },
  {
    name: 'List All Properties',
    query: `SELECT DISTINCT ?property ?type ?label WHERE {
  { ?property a owl:ObjectProperty } UNION
  { ?property a owl:DatatypeProperty } UNION
  { ?property a owl:AnnotationProperty }
  BIND(IF(EXISTS { ?property a owl:ObjectProperty }, "Object",
       IF(EXISTS { ?property a owl:DatatypeProperty }, "Data", "Annotation")) AS ?type)
  OPTIONAL { ?property rdfs:label ?label }
}
ORDER BY ?type ?label
LIMIT 100`
  },
  {
    name: 'List All Individuals',
    query: `SELECT DISTINCT ?individual ?type ?label WHERE {
  ?individual a ?type .
  ?type a owl:Class .
  FILTER(?type != owl:Class && ?type != owl:NamedIndividual)
  OPTIONAL { ?individual rdfs:label ?label }
}
ORDER BY ?type ?label
LIMIT 100`
  },
  {
    name: 'Count Triples',
    query: `SELECT (COUNT(*) AS ?count) WHERE {
  ?s ?p ?o
}`
  },
  {
    name: 'SubClass Hierarchy',
    query: `SELECT ?subclass ?superclass ?subLabel ?superLabel WHERE {
  ?subclass rdfs:subClassOf ?superclass .
  FILTER(isIRI(?superclass))
  OPTIONAL { ?subclass rdfs:label ?subLabel }
  OPTIONAL { ?superclass rdfs:label ?superLabel }
}
ORDER BY ?superclass ?subclass
LIMIT 100`
  }
];

export const SparqlQueryEditor: React.FC<SparqlQueryEditorProps> = ({ 
  projectId, 
  prefixes = [],
  context 
}) => {
  const { apiClient } = context;
  
  // State
  const [queries, setQueries] = useState<SparqlQuery[]>([]);
  const [selectedQuery, setSelectedQuery] = useState<SparqlQuery | null>(null);
  const [queryText, setQueryText] = useState('SELECT ?s ?p ?o WHERE {\n  ?s ?p ?o\n} LIMIT 10');
  const [queryName, setQueryName] = useState('New Query');
  const [results, setResults] = useState<SparqlQueryResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isPrefixesVisible, setPrefixesVisible] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [activeResultTab, setActiveResultTab] = useState<'table' | 'json'>('table');

  // Fetch saved queries from backend
  const fetchQueries = useCallback(async () => {
    if (!projectId) return;
    try {
      const response = await apiClient.get<SparqlQuery[]>(`/api/sparql/${projectId}/queries`);
      setQueries(response || []);
    } catch (error) {
      console.error('Failed to fetch SPARQL queries:', error);
      // Silently fail - queries are optional
    }
  }, [projectId, apiClient]);

  useEffect(() => {
    fetchQueries();
  }, [fetchQueries]);

  // Handlers
  const handleSelectQuery = (query: SparqlQuery) => {
    setSelectedQuery(query);
    setQueryText(query.queryText);
    setQueryName(query.name);
    setResults(null);
    setError(null);
  };

  const handleNewQuery = () => {
    setSelectedQuery(null);
    setQueryText('SELECT ?s ?p ?o WHERE {\n  ?s ?p ?o\n} LIMIT 10');
    setQueryName('New Query');
    setResults(null);
    setError(null);
  };

  const handleLoadSample = (sample: typeof SAMPLE_QUERIES[0]) => {
    setSelectedQuery(null);
    setQueryText(sample.query);
    setQueryName(sample.name);
    setResults(null);
    setError(null);
  };

  const handleSaveQuery = async () => {
    if (!queryName.trim()) {
      setError('Please enter a query name');
      return;
    }
    
    setIsSaving(true);
    setError(null);
    
    try {
      const queryData = { name: queryName, queryText };
      
      if (selectedQuery) {
        // Update existing query
        const updatedQuery = await apiClient.put<SparqlQuery>(
          `/api/sparql/${projectId}/queries/${selectedQuery.id}`,
          queryData
        );
        setQueries(queries.map(q => q.id === updatedQuery.id ? updatedQuery : q));
        setSelectedQuery(updatedQuery);
        context.showNotification?.('Query saved successfully', 'success');
      } else {
        // Create new query
        const newQuery = await apiClient.post<SparqlQuery>(
          `/api/sparql/${projectId}/queries`,
          queryData
        );
        setQueries([...queries, newQuery]);
        setSelectedQuery(newQuery);
        context.showNotification?.('Query created successfully', 'success');
      }
    } catch (err) {
      setError('Failed to save query');
      console.error('Failed to save query:', err);
    } finally {
      setIsSaving(false);
    }
  };

  const handleDeleteQuery = async () => {
    if (!selectedQuery) return;
    
    if (!window.confirm(`Are you sure you want to delete "${selectedQuery.name}"?`)) {
      return;
    }
    
    try {
      await apiClient.delete(`/api/sparql/${projectId}/queries/${selectedQuery.id}`);
      setQueries(queries.filter(q => q.id !== selectedQuery.id));
      handleNewQuery();
      context.showNotification?.('Query deleted', 'info');
    } catch (error) {
      setError('Failed to delete query');
      console.error('Failed to delete query:', error);
    }
  };

  const handleExecuteQuery = async () => {
    setIsLoading(true);
    setResults(null);
    setError(null);
    
    try {
      const response = await apiClient.post<SparqlQueryResult>(
        `/api/sparql/query/${projectId}`,
        { query: queryText }
      );
      // Transform backend response to expected format
      const transformedResults: SparqlQueryResult = {
        head: response.head,
        results: {
          bindings: (response.results as any[]).map((row: Record<string, string>) => {
            const binding: Record<string, { value: string }> = {};
            for (const [key, value] of Object.entries(row)) {
              binding[key] = { value: value || '' };
            }
            return binding;
          })
        }
      };
      setResults(transformedResults);
    } catch (err: any) {
      setError(err?.message || 'Failed to execute query');
      console.error('Failed to execute SPARQL query:', err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleCopyResults = () => {
    if (!results) return;
    
    const text = results.results.bindings.map(b => 
      results.head.vars.map(v => b[v]?.value || '').join('\t')
    ).join('\n');
    
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const downloadCsv = () => {
    if (!results) return;
    
    const cols = results.head.vars;
    const escapeCsv = (val: string) => {
      if (val == null) return '""';
      const str = String(val);
      if (str.includes('"') || str.includes(',') || str.includes('\n')) {
        return `"${str.replace(/"/g, '""')}"`;
      }
      return str;
    };
    
    const header = cols.map(escapeCsv).join(',');
    const rows = results.results.bindings.map(b =>
      cols.map(c => escapeCsv(b[c]?.value ?? '')).join(',')
    );
    
    const csv = [header, ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${queryName.replace(/\s+/g, '_') || 'query-results'}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const downloadJson = () => {
    if (!results) return;
    
    const json = JSON.stringify(results, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${queryName.replace(/\s+/g, '_') || 'query-results'}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // Generate prefix block for display
  const prefixBlock = prefixes.length > 0 
    ? prefixes.map(p => `PREFIX ${p.prefix}: <${p.namespace}>`).join('\n')
    : 'PREFIX owl: <http://www.w3.org/2002/07/owl#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>';

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {/* Header */}
      <header className="bg-white px-4 py-3 border-b border-gray-200 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Database className="w-6 h-6 text-purple-600" />
          <div>
            <h1 className="text-lg font-bold text-gray-800">SPARQL Query Editor</h1>
            <p className="text-xs text-gray-500">Execute SPARQL queries against your ontology</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <select
            onChange={(e) => {
              const sample = SAMPLE_QUERIES.find(s => s.name === e.target.value);
              if (sample) handleLoadSample(sample);
              e.target.value = '';
            }}
            className="text-sm border border-gray-300 rounded px-2 py-1.5 bg-white text-gray-700"
            defaultValue=""
          >
            <option value="">📚 Load Sample Query...</option>
            {SAMPLE_QUERIES.map((s, i) => (
              <option key={i} value={s.name}>{s.name}</option>
            ))}
          </select>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar - Saved Queries */}
        <aside className="w-72 bg-white border-r border-gray-200 flex flex-col">
          <div className="p-2 border-b border-gray-200">
            <button
              onClick={handleNewQuery}
              className="w-full flex items-center justify-center gap-2 text-sm text-white bg-purple-600 hover:bg-purple-700 px-3 py-2 rounded-md transition-colors"
            >
              <Plus size={16} /> New Query
            </button>
          </div>
          
          <div className="p-2 border-b border-gray-200">
            <div className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
              Saved Queries ({queries.length})
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto">
            {queries.length === 0 ? (
              <div className="p-4 text-center text-sm text-gray-400">
                No saved queries yet
              </div>
            ) : (
              queries.map(query => (
                <div
                  key={query.id}
                  onClick={() => handleSelectQuery(query)}
                  className={`p-3 cursor-pointer border-b border-gray-100 transition-colors ${
                    selectedQuery?.id === query.id 
                      ? 'bg-purple-50 border-l-4 border-l-purple-500' 
                      : 'border-l-4 border-l-transparent hover:bg-gray-50'
                  }`}
                >
                  <div className={`text-sm font-medium ${
                    selectedQuery?.id === query.id ? 'text-purple-800' : 'text-gray-800'
                  }`}>
                    {query.name}
                  </div>
                  <div className="text-xs text-gray-500 truncate font-mono mt-1">
                    {query.queryText.substring(0, 50)}...
                  </div>
                </div>
              ))
            )}
          </div>
        </aside>

        {/* Main Editor Area */}
        <main className="flex-1 flex flex-col p-4 gap-4 overflow-hidden">
          {/* Query Editor Panel */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 space-y-3">
            {/* Query Name */}
            <input
              type="text"
              value={queryName}
              onChange={(e) => setQueryName(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-lg font-semibold focus:ring-2 focus:ring-purple-500 focus:outline-none text-gray-800"
              placeholder="Query Name"
            />

            {/* Prefixes Accordion */}
            <div className="border rounded-lg overflow-hidden">
              <button
                onClick={() => setPrefixesVisible(!isPrefixesVisible)}
                className="w-full flex items-center justify-between p-2 bg-gray-50 text-xs font-medium text-gray-600 hover:bg-gray-100"
              >
                <span className="flex items-center gap-2">
                  <Code size={14} />
                  Ontology Prefixes
                </span>
                {isPrefixesVisible ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              </button>
              {isPrefixesVisible && (
                <div className="p-2 border-t bg-white max-h-24 overflow-y-auto">
                  <pre className="text-xs text-gray-700 font-mono whitespace-pre-wrap">
                    {prefixBlock}
                  </pre>
                </div>
              )}
            </div>

            {/* Query Text Area */}
            <textarea
              className="w-full h-48 p-3 font-mono text-sm border border-gray-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:outline-none text-gray-800 resize-none"
              value={queryText}
              onChange={(e) => setQueryText(e.target.value)}
              placeholder="Enter SPARQL query..."
              spellCheck={false}
            />

            {/* Error Display */}
            {error && (
              <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
                {error}
              </div>
            )}

            {/* Action Buttons */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <button
                  onClick={handleDeleteQuery}
                  disabled={!selectedQuery}
                  className="flex items-center gap-1 px-3 py-2 text-sm bg-red-50 text-red-600 rounded-lg hover:bg-red-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                >
                  <Trash2 size={16} />
                </button>
                <button
                  onClick={() => fetchQueries()}
                  className="flex items-center gap-1 px-3 py-2 text-sm text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                  title="Refresh saved queries"
                >
                  <RefreshCw size={16} />
                </button>
              </div>
              
              <div className="flex items-center gap-2">
                <button
                  onClick={handleSaveQuery}
                  disabled={isSaving}
                  className="flex items-center gap-2 px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg disabled:opacity-50 transition-colors"
                >
                  {isSaving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
                  Save
                </button>
                <button
                  onClick={handleExecuteQuery}
                  disabled={isLoading || !queryText.trim()}
                  className="flex items-center gap-2 px-4 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed transition-colors"
                >
                  {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
                  Execute
                </button>
              </div>
            </div>
          </div>

          {/* Results Panel */}
          <div className="flex-1 bg-white border border-gray-200 rounded-lg overflow-hidden flex flex-col min-h-0">
            {/* Results Header */}
            <div className="flex items-center justify-between p-3 border-b bg-gray-50">
              <div className="flex items-center gap-4">
                <span className="text-sm font-semibold text-gray-700 flex items-center gap-2">
                  <Table size={16} />
                  Query Results
                </span>
                {results && (
                  <span className="text-xs text-gray-500">
                    {results.results.bindings.length} rows
                  </span>
                )}
              </div>
              
              {results && (
                <div className="flex items-center gap-2">
                  {/* Result format tabs */}
                  <div className="flex rounded-lg border border-gray-300 overflow-hidden">
                    <button
                      onClick={() => setActiveResultTab('table')}
                      className={`px-3 py-1 text-xs ${activeResultTab === 'table' ? 'bg-purple-100 text-purple-700' : 'bg-white text-gray-600 hover:bg-gray-50'}`}
                    >
                      Table
                    </button>
                    <button
                      onClick={() => setActiveResultTab('json')}
                      className={`px-3 py-1 text-xs ${activeResultTab === 'json' ? 'bg-purple-100 text-purple-700' : 'bg-white text-gray-600 hover:bg-gray-50'}`}
                    >
                      JSON
                    </button>
                  </div>
                  
                  <button
                    onClick={handleCopyResults}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 rounded"
                    title="Copy to clipboard"
                  >
                    {copied ? <Check size={14} className="text-green-600" /> : <Copy size={14} />}
                  </button>
                  <button
                    onClick={downloadCsv}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 rounded"
                    title="Download CSV"
                  >
                    <Download size={14} /> CSV
                  </button>
                  <button
                    onClick={downloadJson}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 rounded"
                    title="Download JSON"
                  >
                    <FileText size={14} /> JSON
                  </button>
                </div>
              )}
            </div>

            {/* Results Content */}
            <div className="flex-1 overflow-auto">
              {isLoading ? (
                <div className="flex items-center justify-center h-64 text-gray-500">
                  <Loader2 size={24} className="animate-spin mr-2" />
                  <span>Executing query...</span>
                </div>
              ) : results ? (
                activeResultTab === 'table' ? (
                  <table className="min-w-full text-sm">
                    <thead className="bg-gray-100 sticky top-0">
                      <tr>
                        {results.head.vars.map((col) => (
                          <th key={col} className="p-2 text-left font-semibold text-gray-600 border-b">
                            ?{col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {results.results.bindings.map((binding, rowIndex) => (
                        <tr key={rowIndex} className="hover:bg-gray-50">
                          {results.head.vars.map((col) => (
                            <td key={col} className="p-2 text-gray-700 font-mono text-xs">
                              <span title={binding[col]?.value}>
                                {binding[col]?.value?.split('#').pop() || binding[col]?.value?.split('/').pop() || binding[col]?.value || '-'}
                              </span>
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : (
                  <pre className="p-4 text-xs font-mono text-gray-700 overflow-auto">
                    {JSON.stringify(results, null, 2)}
                  </pre>
                )
              ) : (
                <div className="flex items-center justify-center h-64 text-gray-400">
                  <div className="text-center">
                    <Database size={48} className="mx-auto mb-4 opacity-50" />
                    <p>Query results will appear here</p>
                    <p className="text-xs mt-1">Write a SPARQL query and click Execute</p>
                  </div>
                </div>
              )}
            </div>
          </div>
        </main>
      </div>
    </div>
  );
};

export default SparqlQueryEditor;
