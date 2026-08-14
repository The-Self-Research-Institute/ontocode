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

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Plus, Trash2, Play, Save, Loader2, ChevronDown, ChevronRight, Download, Database, Code, Table, FileText, RefreshCw, Copy, Check } from 'lucide-react';
import type { SparqlQuery, SparqlQueryResult, OntologyPrefix, PluginContext, SparqlQueryEditorProps, SparqlBinding } from './types';

// SPARQL Keywords for basic syntax highlighting
const SPARQL_KEYWORDS = [
  'SELECT', 'CONSTRUCT', 'DESCRIBE', 'ASK', 'WHERE', 'FROM', 'NAMED',
  'PREFIX', 'BASE', 'OPTIONAL', 'UNION', 'FILTER', 'GRAPH', 'ORDER', 'BY',
  'LIMIT', 'OFFSET', 'DISTINCT', 'REDUCED', 'GROUP', 'HAVING', 'VALUES',
  'BIND', 'AS', 'SERVICE', 'MINUS', 'EXISTS', 'NOT', 'IN', 'INSERT', 'DELETE',
  'DATA', 'LOAD', 'CLEAR', 'CREATE', 'DROP', 'COPY', 'MOVE', 'ADD'
];

// SPARQL has no implicit prefixes — every query must declare each one it uses, or the
// backend's parser rejects it with "Unresolved prefixed name" even though the editor's
// own "Ontology Prefixes" panel shows them (that panel is informational display only,
// not something injected into the query sent to the server).
const COMMON_PREFIXES = `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
`;

// Sample queries for new users
const SAMPLE_QUERIES = [
  {
    name: 'List All Classes',
    query: `${COMMON_PREFIXES}SELECT DISTINCT ?class ?label WHERE {
  ?class a owl:Class .
  OPTIONAL { ?class rdfs:label ?label }
}
ORDER BY ?label
LIMIT 100`
  },
  {
    name: 'List All Properties',
    query: `${COMMON_PREFIXES}SELECT DISTINCT ?property ?type ?label WHERE {
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
    query: `${COMMON_PREFIXES}SELECT DISTINCT ?individual ?type ?label WHERE {
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
    query: `${COMMON_PREFIXES}SELECT ?subclass ?superclass ?subLabel ?superLabel WHERE {
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
  const mainAreaRef = useRef<HTMLDivElement>(null);
  
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
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [resultsHeight, setResultsHeight] = useState(400);
  const [isResizing, setIsResizing] = useState(false);
  const [activeTab, setActiveTab] = useState<'editor' | 'results'>('editor');
  const [selectedSample, setSelectedSample] = useState('');

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
    // Switching queries clears results (they belonged to whatever ran before) — jump back
    // to the editor tab so the user actually sees the newly-loaded query instead of being
    // left staring at a now-empty results tab with no visible way to run it.
    setActiveTab('editor');
  };

  const handleNewQuery = () => {
    setSelectedQuery(null);
    setQueryText('SELECT ?s ?p ?o WHERE {\n  ?s ?p ?o\n} LIMIT 10');
    setQueryName('New Query');
    setResults(null);
    setError(null);
    setActiveTab('editor');
  };

  const handleLoadSample = (sample: typeof SAMPLE_QUERIES[0]) => {
    setSelectedQuery(null);
    setQueryText(sample.query);
    setQueryName(sample.name);
    setResults(null);
    setError(null);
    // Switching samples clears results (they belonged to whatever ran before) — jump back
    // to the editor tab so the user actually sees the newly-loaded query instead of being
    // left staring at a now-empty results tab with no visible way to run it.
    setActiveTab('editor');
    // Reset dropdown after a brief delay to show the selection
    setTimeout(() => setSelectedSample(''), 300);
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
    setShowDeleteModal(true);
  };

  const confirmDelete = async () => {
    if (!selectedQuery) return;
    setShowDeleteModal(false);
    
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
      const response = await apiClient.post<any>(
        `/api/sparql/query/${projectId}`,
        { query: queryText }
      );
      // Backend returns { head: {vars:[]}, results: [{var: value,...}], executionTime }
      // results is a flat array of {var: stringValue} maps (not SPARQL JSON {bindings:[...]} format)
      if (response.error) {
        setError(response.error);
        return;
      }
      // Defensive: handle flat array, SPARQL JSON bindings wrapper, or JSON-string (double-encode)
      let resultsData = response.results;
      if (typeof resultsData === 'string') {
        try { resultsData = JSON.parse(resultsData); } catch { resultsData = []; }
      }
      const rawRows: Record<string, any>[] = Array.isArray(resultsData)
        ? resultsData
        : Array.isArray(resultsData?.bindings)
          ? resultsData.bindings
          : [];
      const transformedResults: SparqlQueryResult = {
        head: response.head,
        results: {
          bindings: rawRows.map((row: Record<string, any>) => {
            const binding: SparqlBinding = {};
            for (const [key, val] of Object.entries(row)) {
              binding[key] = {
                type: val?.type || (typeof val === 'string' && val.startsWith('http') ? 'uri' : 'literal'),
                value: (typeof val === 'string' ? val : val?.value) || '',
                datatype: val?.datatype,
                'xml:lang': val?.['xml:lang']
              };
            }
            return binding;
          })
        },
        executionTime: (response as any).executionTime
      };
      setResults(transformedResults);
      setActiveTab('results');
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
    
    try {
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
      const link = document.createElement('a');
      link.href = url;
      link.download = `${queryName.replace(/\s+/g, '_') || 'query-results'}.csv`;
      link.click();
      URL.revokeObjectURL(url);
      
      context.showNotification?.('CSV downloaded successfully', 'success');
    } catch (error) {
      console.error('CSV download failed:', error);
      context.showNotification?.('Failed to download CSV', 'error');
    }
  };

  const downloadJson = () => {
    if (!results) return;
    
    try {
      const json = JSON.stringify(results, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${queryName.replace(/\s+/g, '_') || 'query-results'}.json`;
      link.click();
      URL.revokeObjectURL(url);
      
      context.showNotification?.('JSON downloaded successfully', 'success');
    } catch (error) {
      console.error('JSON download failed:', error);
      context.showNotification?.('Failed to download JSON', 'error');
    }
  };

  // Resize handlers
  const handleMouseDown = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsResizing(true);
  };

  useEffect(() => {
    if (!isResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      e.preventDefault();
      const rect = mainAreaRef.current?.getBoundingClientRect();
      const bottom = rect?.bottom ?? window.innerHeight;
      const maxHeight = (rect?.height ?? window.innerHeight) * 0.8;
      const newHeight = bottom - e.clientY;
      // Allow resize between 200px and 80% of available space
      if (newHeight >= 200 && newHeight <= maxHeight) {
        setResultsHeight(newHeight);
      }
    };

    const handleMouseUp = () => {
      setIsResizing(false);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
    
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
  }, [isResizing]);

  // Generate prefix block for display
  const prefixBlock = prefixes.length > 0 
    ? prefixes.map(p => `PREFIX ${p.prefix}: <${p.namespace}>`).join('\n')
    : 'PREFIX owl: <http://www.w3.org/2002/07/owl#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>';

  return (
    <div className="h-full flex flex-col" style={{ backgroundColor: 'var(--bg)', color: 'var(--text-primary)' }}>
      {/* Header */}
      <header
        className="px-4 py-3 border-b flex items-center justify-between"
        style={{ backgroundColor: 'var(--surface-1)', borderColor: 'var(--border)' }}
      >
        <div className="flex items-center gap-3">
          <Database className="w-6 h-6 text-accent" />
          <div>
            <h1 className="text-lg font-bold text-primary">SPARQL Query Editor</h1>
            <p className="text-xs text-secondary">Execute SPARQL queries against your ontology</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={selectedSample}
            onChange={(e) => {
              const selectedValue = e.target.value;
              setSelectedSample(selectedValue); // Show selection in dropdown
              const sample = SAMPLE_QUERIES.find(s => s.name === selectedValue);
              if (sample) {
                handleLoadSample(sample);
              }
            }}
            className="text-sm rounded px-2 py-1.5 theme-input"
          >
            <option value="">Load sample query...</option>
            {SAMPLE_QUERIES.map((s, i) => (
              <option key={i} value={s.name}>{s.name}</option>
            ))}
          </select>
        </div>
      </header>

      <div ref={mainAreaRef} className="flex flex-1 overflow-hidden">
        {/* Sidebar - Saved Queries */}
        <aside className="w-72 bg-theme-surface border-r border-default flex flex-col">
          <div className="p-2 border-b border-default">
            <button
              onClick={handleNewQuery}
              className="w-full flex items-center justify-center gap-2 text-sm px-3 py-2 rounded-md transition-all"
              style={{ backgroundColor: '#8b5cf6', color: '#ffffff' }}
              onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = '#7c3aed')}
              onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = '#8b5cf6')}
            >
              <Plus size={16} /> New Query
            </button>
          </div>
          
          <div className="p-2 border-b border-default">
            <div className="text-xs font-semibold text-tertiary uppercase tracking-wider">
              Saved Queries ({queries.length})
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto">
            {queries.length === 0 ? (
              <div className="p-4 text-center text-sm text-tertiary">
                No saved queries yet
              </div>
            ) : (
              queries.map(query => (
                <div
                  key={query.id}
                  onClick={() => handleSelectQuery(query)}
                  style={{
                    borderLeftColor: selectedQuery?.id === query.id ? '#8b5cf6' : 'transparent',
                    backgroundColor: selectedQuery?.id === query.id ? 'var(--accent-tint)' : 'transparent'
                  }}
                  className="p-3 cursor-pointer border-b border-l-4 transition-all"
                  onMouseEnter={(e) => {
                    if (selectedQuery?.id !== query.id) {
                      e.currentTarget.style.backgroundColor = 'var(--hover-overlay)';
                    }
                  }}
                  onMouseLeave={(e) => {
                    if (selectedQuery?.id !== query.id) {
                      e.currentTarget.style.backgroundColor = 'transparent';
                    }
                  }}
                >
                  <div className="text-sm font-medium" style={{ color: selectedQuery?.id === query.id ? '#8b5cf6' : 'var(--text-primary)' }}>
                    {query.name}
                  </div>
                  <div className="text-xs text-tertiary truncate font-mono mt-1">
                    {query.queryText.substring(0, 50)}...
                  </div>
                </div>
              ))
            )}
          </div>
        </aside>

        {/* Main Editor Area */}
        <main className="flex-1 flex flex-col overflow-hidden min-h-0">
          {/* Tabs */}
          <div className="flex border-b" style={{ borderColor: 'var(--border)', backgroundColor: 'var(--surface-1)' }}>
            <button
              onClick={() => setActiveTab('editor')}
              className="px-4 py-3 text-sm font-medium border-b-2 transition-colors"
              style={{
                borderBottomColor: activeTab === 'editor' ? '#8b5cf6' : 'transparent',
                color: activeTab === 'editor' ? '#8b5cf6' : 'var(--text-secondary)',
                backgroundColor: activeTab === 'editor' ? 'var(--surface-2)' : 'transparent'
              }}
            >
              <Code size={16} className="inline mr-2" />
              Editor
            </button>
            <button
              onClick={() => setActiveTab('results')}
              className="px-4 py-3 text-sm font-medium border-b-2 transition-colors"
              style={{
                borderBottomColor: activeTab === 'results' ? '#8b5cf6' : 'transparent',
                color: activeTab === 'results' ? '#8b5cf6' : 'var(--text-secondary)',
                backgroundColor: activeTab === 'results' ? 'var(--surface-2)' : 'transparent'
              }}
            >
              <Table size={16} className="inline mr-2" />
              Results {results && `(${results.results.bindings.length})`}
            </button>
          </div>

          {/* Editor Tab Content */}
          {activeTab === 'editor' && (
          <div className="flex-1 min-h-0 overflow-auto p-4">
          {/* Query Editor Panel */}
          <div className="theme-panel rounded-lg p-4 space-y-3">
            {/* Query Name */}
            <input
              type="text"
              value={queryName}
              onChange={(e) => setQueryName(e.target.value)}
              className="w-full px-3 py-2 rounded-lg text-lg font-semibold theme-input"
              placeholder="Query Name"
            />

            {/* Prefixes Accordion */}
            <div className="border border-default rounded-lg overflow-hidden">
              <button
                onClick={() => setPrefixesVisible(!isPrefixesVisible)}
                className="w-full flex items-center justify-between p-2 text-xs font-medium text-secondary hover-overlay transition-colors"
                style={{ backgroundColor: 'var(--surface-2)' }}
              >
                <span className="flex items-center gap-2">
                  <Code size={14} />
                  Ontology Prefixes
                </span>
                {isPrefixesVisible ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              </button>
              {isPrefixesVisible && (
                <div className="p-2 border-t border-default max-h-24 overflow-y-auto" style={{ backgroundColor: 'var(--surface-1)' }}>
                  <pre className="text-xs text-primary font-mono whitespace-pre-wrap">
                    {prefixBlock}
                  </pre>
                </div>
              )}
            </div>

            {/* Query Text Area */}
            <textarea
              className="w-full h-48 p-3 font-mono text-sm rounded-lg theme-input resize-none"
              value={queryText}
              onChange={(e) => setQueryText(e.target.value)}
              placeholder="Enter SPARQL query..."
              spellCheck={false}
            />

            {/* Error Display */}
            {error && (
              <div
                className="p-3 border rounded-lg text-sm text-error"
                style={{ borderColor: 'var(--error)', backgroundColor: 'var(--surface-2)' }}
              >
                {error}
              </div>
            )}

            {/* Action Buttons */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <button
                  onClick={handleDeleteQuery}
                  disabled={!selectedQuery}
                  className="flex items-center gap-1 px-3 py-2 text-sm rounded-lg border border-default hover-overlay text-error disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  title={selectedQuery ? 'Delete query' : 'Select a query to delete'}
                >
                  <Trash2 size={16} />
                </button>
                <button
                  onClick={() => fetchQueries()}
                  className="flex items-center gap-1 px-3 py-2 text-sm rounded-lg btn-outline transition-colors"
                  title="Refresh saved queries"
                >
                  <RefreshCw size={16} />
                </button>
              </div>
              
              <div className="flex items-center gap-2">
                <button
                  onClick={handleSaveQuery}
                  disabled={isSaving}
                  className="flex items-center gap-2 px-4 py-2 text-sm rounded-lg btn-outline disabled:opacity-50 transition-colors"
                >
                  {isSaving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
                  Save
                </button>
                <button
                  onClick={handleExecuteQuery}
                  disabled={isLoading || !queryText.trim()}
                  className="flex items-center gap-2 px-4 py-2 text-sm rounded-lg disabled:opacity-50 disabled:cursor-not-allowed transition-all"
                  style={{ backgroundColor: '#8b5cf6', color: '#ffffff' }}
                  onMouseEnter={(e) => !isLoading && (e.currentTarget.style.backgroundColor = '#7c3aed')}
                  onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = '#8b5cf6')}
                >
                  {isLoading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
                  Execute
                </button>
              </div>
            </div>
          </div>
            </div>
          )}

          {/* Results Tab Content */}
          {activeTab === 'results' && (
          <div className="flex-1 min-h-0 overflow-hidden p-4 flex flex-col">
          {/* Results Panel */}
          <div className="theme-panel rounded-lg overflow-hidden flex flex-col" style={{ height: `${resultsHeight}px`, minHeight: '200px', maxHeight: '80%' }}>

            {/* Results Header */}
            <div className="flex items-center justify-between p-3 border-b border-default" style={{ backgroundColor: 'var(--surface-2)' }}>
              <div className="flex items-center gap-4">
                <span className="text-sm font-semibold text-primary flex items-center gap-2">
                  <Table size={16} />
                  Query Results
                </span>
                {results && (
                  <>
                    <span className="text-xs text-tertiary">
                    {results.results.bindings.length} rows
                  </span>
                    {results.executionTime !== undefined && (
                      <span
                        className="text-xs px-2 py-0.5 rounded-full font-medium"
                        style={{ backgroundColor: 'var(--success-tint)', color: 'var(--success)' }}
                      >
                        {results.executionTime}ms
                      </span>
                    )}
                  </>
                )}
              </div>
              
              {results && (
                <div className="flex items-center gap-2">
                  {/* Result format tabs */}
                  <div className="flex rounded-lg border border-default overflow-hidden">
                    <button
                      onClick={() => setActiveResultTab('table')}
                      className={`px-3 py-1 text-xs transition-colors ${
                        activeResultTab === 'table' ? 'btn-tonal' : 'text-secondary hover-overlay'
                      }`}
                      style={{ backgroundColor: activeResultTab === 'table' ? undefined : 'var(--surface-1)' }}
                    >
                      Table
                    </button>
                    <button
                      onClick={() => setActiveResultTab('json')}
                      className={`px-3 py-1 text-xs transition-colors ${
                        activeResultTab === 'json' ? 'btn-tonal' : 'text-secondary hover-overlay'
                      }`}
                      style={{ backgroundColor: activeResultTab === 'json' ? undefined : 'var(--surface-1)' }}
                    >
                      JSON
                    </button>
                  </div>
                  
                  <button
                    onClick={handleCopyResults}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-secondary hover-overlay rounded transition-colors"
                    title="Copy to clipboard"
                  >
                    {copied ? <Check size={14} style={{ color: 'var(--success)' }} /> : <Copy size={14} />}
                  </button>
                  <button
                    onClick={downloadCsv}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-secondary hover-overlay rounded transition-colors"
                    title="Download CSV"
                  >
                    <Download size={14} /> CSV
                  </button>
                  <button
                    onClick={downloadJson}
                    className="flex items-center gap-1 px-2 py-1 text-xs text-secondary hover-overlay rounded transition-colors"
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
                <div className="flex items-center justify-center h-64 text-secondary">
                  <Loader2 size={24} className="animate-spin mr-2" />
                  <span>Executing query...</span>
                </div>
              ) : results ? (
                activeResultTab === 'table' ? (
                  <table className="min-w-full text-sm">
                    <thead className="sticky top-0" style={{ backgroundColor: 'var(--surface-2)' }}>
                      <tr>
                        {results.head.vars.map((col) => (
                          <th key={col} className="p-2 text-left font-semibold text-secondary border-b border-default">
                            ?{col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y" style={{ borderColor: 'var(--divider)' }}>
                      {results.results.bindings.map((binding, rowIndex) => (
                        <tr key={rowIndex} className="hover-overlay">
                          {results.head.vars.map((col) => (
                            <td key={col} className="p-2 text-primary font-mono text-xs">
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
                  <pre className="p-4 text-xs font-mono text-primary overflow-auto">
                    {JSON.stringify(results, null, 2)}
                  </pre>
                )
              ) : (
                <div className="flex items-center justify-center h-64 text-tertiary">
                  <div className="text-center">
                    <Database size={48} className="mx-auto mb-4 opacity-50" />
                    <p>Query results will appear here</p>
                    <p className="text-xs mt-1">Write a SPARQL query and click Execute</p>
                  </div>
                </div>
              )}
            </div>
          </div>
          
          {/* Resize Handle */}
          <div 
            onMouseDown={handleMouseDown}
            className="h-2 cursor-row-resize hover:bg-purple-500/20 transition-colors flex items-center justify-center"
            style={{ borderTop: '1px solid var(--border)' }}
          >
            <div className="w-12 h-1 bg-gray-400 rounded-full"></div>
          </div>
          </div>
          )}
        </main>
      </div>

      {/* Delete Confirmation Modal */}
      {showDeleteModal && (
        <div
          className="fixed inset-0 flex items-center justify-center z-50 p-4"
          style={{ backgroundColor: 'var(--overlay)' }}
        >
          <div className="theme-panel rounded-lg p-6 max-w-md w-full shadow-xl max-h-[80vh] overflow-auto">
            <h3 className="text-lg font-semibold text-primary mb-2">Delete Query</h3>
            <p className="text-secondary mb-6">
              Are you sure you want to delete "{selectedQuery?.name}"? This action cannot be undone.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setShowDeleteModal(false)}
                className="px-4 py-2 text-sm rounded-lg btn-outline transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmDelete}
                className="px-4 py-2 text-sm rounded-lg hover-brightness transition-colors"
                style={{ backgroundColor: 'var(--error)', color: 'var(--on-error)' }}
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default SparqlQueryEditor;
