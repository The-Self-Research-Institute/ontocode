import React, { useState, useCallback } from 'react';
import { ChevronRight, ChevronDown, Play } from 'lucide-react';

interface ClassNode {
  id: string;
  iri: string;
  label: string;
  hasChildren?: boolean;
  children?: ClassNode[];
}

interface DLQueryResult {
  classes: ClassNode[];
  queryType: string;
}

interface DLQueryPanelProps {
  projectId: string;
  executeDLQuery: (projectId: string, query: string, queryType: string) => Promise<DLQueryResult>;
}

export const DLQueryPanel: React.FC<DLQueryPanelProps> = ({
  projectId,
  executeDLQuery
}) => {
  const [queryExpression, setQueryExpression] = useState('');
  const [queryType, setQueryType] = useState<string>('subclasses');
  const [results, setResults] = useState<ClassNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [isExecuting, setIsExecuting] = useState(false);
  const [showOwlThing, setShowOwlThing] = useState(true);
  const [showOwlNothing, setShowOwlNothing] = useState(true);

  const handleExecute = useCallback(async () => {
    if (!queryExpression.trim()) {
      return;
    }

    setIsExecuting(true);
    try {
      const result = await executeDLQuery(projectId, queryExpression, queryType);
      setResults(result.classes);
    } catch (error) {
      console.error('DL Query execution failed:', error);
      setResults([]);
    } finally {
      setIsExecuting(false);
    }
  }, [projectId, queryExpression, queryType, executeDLQuery]);

  const toggleExpand = useCallback((nodeId: string) => {
    setExpandedNodes(prev =>
      prev.includes(nodeId) ? prev.filter(id => id !== nodeId) : [...prev, nodeId]
    );
  }, []);

  const renderClassNode = (node: ClassNode, level: number = 0): React.ReactNode => {
    const isExpanded = expandedNodes.includes(node.id);
    const hasChildren = node.hasChildren || (node.children && node.children.length > 0);

    return (
      <div key={node.id}>
        <div
          className="flex items-center py-1 px-2 hover:bg-gray-100"
          style={{ paddingLeft: `${level * 20 + 8}px` }}
        >
          {hasChildren ? (
            <button
              onClick={() => toggleExpand(node.id)}
              className="mr-1 p-0 hover:bg-gray-200 rounded"
            >
              {isExpanded ? (
                <ChevronDown className="w-4 h-4" />
              ) : (
                <ChevronRight className="w-4 h-4" />
              )}
            </button>
          ) : (
            <span className="w-5" />
          )}
          <span className="flex items-center gap-2">
            <span className="w-3 h-3 bg-yellow-500 rounded-full flex-shrink-0" />
            <span className="text-sm font-semibold">
              {node.label || node.iri.split(/[/#]/).pop()}
            </span>
          </span>
        </div>
        {isExpanded && node.children && node.children.map(child => renderClassNode(child, level + 1))}
      </div>
    );
  };

  return (
    <div className="flex h-full">
      {/* Left side - Class hierarchy */}
      <div className="w-1/2 border-r border-gray-300 flex flex-col">
        <div className="text-xs text-gray-600 p-2 border-b bg-yellow-50 font-semibold">
          Class hierarchy: Heart Rate
        </div>
        <div className="flex-1 overflow-auto bg-white">
          {results.length === 0 ? (
            <div className="p-4 text-sm text-gray-500 text-center">
              Execute a query to see results
            </div>
          ) : (
            <div className="py-1">
              {results.map(node => renderClassNode(node))}
            </div>
          )}
        </div>
      </div>

      {/* Right side - Query interface */}
      <div className="w-1/2 flex flex-col bg-white">
        {/* Query header */}
        <div className="text-xs text-gray-600 p-2 border-b bg-yellow-50 font-semibold flex items-center justify-between">
          <span>DL query</span>
          <button className="text-gray-500 hover:text-gray-700">
            <span className="text-lg">⚙</span>
          </button>
        </div>

        {/* Query input */}
        <div className="p-3 border-b">
          <div className="mb-2">
            <select
              value={queryType}
              onChange={(e) => setQueryType(e.target.value)}
              className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
            >
              <option value="subclasses">Subclasses</option>
              <option value="superclasses">Superclasses</option>
              <option value="equivalentClasses">Equivalent classes</option>
              <option value="directSubclasses">Direct subclasses</option>
              <option value="directSuperclasses">Direct superclasses</option>
              <option value="instances">Instances</option>
            </select>
          </div>
          <div className="mb-2">
            <textarea
              value={queryExpression}
              onChange={(e) => setQueryExpression(e.target.value)}
              placeholder="Enter class expression..."
              className="w-full px-2 py-1 border border-gray-300 rounded text-sm font-mono"
              rows={3}
            />
          </div>
          <div className="flex gap-2">
            <button
              onClick={handleExecute}
              disabled={isExecuting || !queryExpression.trim()}
              className="flex items-center gap-1 px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:bg-gray-400 disabled:cursor-not-allowed text-sm"
            >
              <Play className="w-3 h-3" />
              Execute
            </button>
            <button
              onClick={() => {
                setQueryExpression('');
                setResults([]);
              }}
              className="px-3 py-1 bg-gray-200 text-gray-700 rounded hover:bg-gray-300 text-sm"
            >
              Add to ontology
            </button>
          </div>
        </div>

        {/* Query results section */}
        <div className="flex-1 overflow-auto p-3">
          <div className="mb-3">
            <h3 className="text-sm font-semibold mb-2">Query results</h3>
            <div className="text-xs text-gray-600">
              {results.length > 0 ? (
                <span>{results.length} result(s) found</span>
              ) : (
                <span>No results</span>
              )}
            </div>
          </div>
        </div>

        {/* Query options */}
        <div className="border-t p-3">
          <h3 className="text-sm font-semibold mb-2">Query for</h3>
          <div className="space-y-1 text-xs">
            <label className="flex items-center gap-2">
              <input type="checkbox" className="rounded" />
              <span>Direct superclasses</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" className="rounded" />
              <span>Superclasses</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" className="rounded" />
              <span>Equivalent classes</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" className="rounded" />
              <span>Direct subclasses</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={queryType === 'subclasses'} onChange={() => {}} className="rounded" />
              <span>Subclasses</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" className="rounded" />
              <span>Instances</span>
            </label>
          </div>

          <h3 className="text-sm font-semibold mt-3 mb-2">Result filters</h3>
          <div className="space-y-1 text-xs">
            <div className="mb-1">
              <label className="block mb-1">Name contains</label>
              <input
                type="text"
                className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
                placeholder="Filter by name..."
              />
            </div>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={showOwlThing} onChange={(e) => setShowOwlThing(e.target.checked)} className="rounded" />
              <span>Display owl:Thing</span>
              <span className="text-gray-500">(in superclass results)</span>
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={showOwlNothing} onChange={(e) => setShowOwlNothing(e.target.checked)} className="rounded" />
              <span>Display owl:Nothing</span>
              <span className="text-gray-500">(in subclass results)</span>
            </label>
          </div>
        </div>
      </div>
    </div>
  );
};
