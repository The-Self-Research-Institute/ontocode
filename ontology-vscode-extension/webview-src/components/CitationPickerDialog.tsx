// CitationPickerDialog.tsx
import React, { useState, useEffect } from 'react';
import { X, Search, BookOpen, User, Calendar, ExternalLink, Plus, ChevronDown, ChevronRight, AlertCircle, Settings } from 'lucide-react';
import { TreeNode } from '@/types';
import ZoteroSettingsDialog from './ZoteroSettingsDialog';

interface CitationItem {
  key: string;
  data: {
    title: string;
    creators: Array<{ firstName: string; lastName: string; creatorType: string }>;
    date: string;
    doi?: string;
    url?: string;
    itemType: string;
    abstractNote?: string;
    publicationTitle?: string;
    volume?: string;
    issue?: string;
    pages?: string;
    publisher?: string;
    tags?: Array<{ tag: string }>;
  };
}

interface CitationPickerDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectCitation: (citation: CitationItem | 'manual') => void;
  format: 'turtle' | 'rdfxml';
}

const CitationPickerDialog: React.FC<CitationPickerDialogProps> = ({ 
  isOpen, 
  onClose, 
  onSelectCitation,
  format
}) => {
  const [citations, setCitations] = useState<CitationItem[]>([]);
  const [filteredCitations, setFilteredCitations] = useState<CitationItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDoiPrompt, setShowDoiPrompt] = useState(false);
  const [selectedCitation, setSelectedCitation] = useState<CitationItem | null>(null);
  const [manualDoi, setManualDoi] = useState('');
  const [showZoteroSettings, setShowZoteroSettings] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadedCount, setLoadedCount] = useState(0);
  const [showDoiWarning, setShowDoiWarning] = useState(false);

  useEffect(() => {
    if (isOpen) {
      loadCitations();
    }
  }, [isOpen]);

  useEffect(() => {
    if (searchQuery.trim() === '') {
      setFilteredCitations(citations);
    } else {
      const query = searchQuery.toLowerCase();
      const filtered = citations.filter(citation => {
        const title = citation.data?.title?.toLowerCase() || '';
        const authors = citation.data?.creators?.map(c => 
          `${c.firstName} ${c.lastName}`.toLowerCase()
        ).join(' ') || '';
        const year = citation.data?.date || '';
        
        return title.includes(query) || authors.includes(query) || year.includes(query);
      });
      setFilteredCitations(filtered);
    }
  }, [searchQuery, citations]);

  const loadCitations = () => {
    setLoading(true);
    setLoadingMore(false);
    setLoadedCount(0);
    setError(null);

    // Listen for response
    const messageHandler = (event: MessageEvent) => {
      const message = event.data;

      if (message.type === 'zoteroLibraryData') {
        const items = message.items || [];
        setCitations(items);
        setFilteredCitations(items);
        setLoadedCount(items.length);
        setLoading(false);
        setLoadingMore(!!message.hasMore);
      } else if (message.type === 'zoteroLibraryDataAppend') {
        const items = message.items || [];
        setCitations(prev => [...prev, ...items]);
        setLoadedCount(prev => prev + items.length);
        setLoadingMore(!!message.hasMore);
      } else if (message.type === 'zoteroLibraryDataComplete') {
        setLoadingMore(false);
      } else if (message.type === 'zoteroLibraryError') {
        setError(message.error || 'Failed to load Zotero library');
        setLoading(false);
        setLoadingMore(false);
      }
    };

    window.addEventListener('message', messageHandler);

    // Request citations from extension via postMessage
    if (window.vscode) {
      window.vscode.postMessage({
        type: 'requestZoteroLibrary'
      });
    }
    
    // Cleanup listener after 10 seconds or when component unmounts
    const timeout = setTimeout(() => {
      window.removeEventListener('message', messageHandler);
      if (loading) {
        setError('Request timed out. Please try again.');
        setLoading(false);
      }
    }, 10000);

    return () => {
      clearTimeout(timeout);
      window.removeEventListener('message', messageHandler);
    };
  };

  const extractYear = (dateStr: string): string => {
    if (!dateStr) return '';
    const match = dateStr.match(/\d{4}/);
    return match ? match[0] : '';
  };

  const normalizeDoiUrl = (doi: string): string => {
    if (!doi) return '';
    // If DOI already has http:// or https://, use it as-is
    if (doi.startsWith('http://') || doi.startsWith('https://')) {
      return doi;
    }
    // Otherwise, prepend https://doi.org/
    return `https://doi.org/${doi}`;
  };

  const handleSelectCitation = (citation: CitationItem) => {
    // Check if DOI is missing
    if (!citation.data.doi) {
      setSelectedCitation(citation);
      setShowDoiWarning(true);
      setShowDoiPrompt(true);
      return;
    }
    
    // DOI exists, proceed with selection
    onSelectCitation(citation);
    onClose();
  };
  
  const handleConfirmWithoutDoi = () => {
    if (selectedCitation) {
      onSelectCitation(selectedCitation);
      setShowDoiPrompt(false);
      setShowDoiWarning(false);
      setSelectedCitation(null);
      onClose();
    }
  };
  
  const handleAddDoiAndConfirm = () => {
    if (selectedCitation && manualDoi.trim()) {
      // Add DOI to citation data
      const updatedCitation = {
        ...selectedCitation,
        data: {
          ...selectedCitation.data,
          doi: manualDoi.trim()
        }
      };
      onSelectCitation(updatedCitation);
      setShowDoiPrompt(false);
      setShowDoiWarning(false);
      setSelectedCitation(null);
      setManualDoi('');
      onClose();
    }
  };

  const handleManualEntry = () => {
    onSelectCitation('manual');
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-4xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex items-center gap-2">
            <BookOpen className="text-purple-600" size={24} />
            <h2 className="text-xl font-bold text-gray-800">Insert Citation</h2>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setShowZoteroSettings(true)}
              className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
              title="Zotero Settings"
            >
              <Settings size={20} className="text-gray-500" />
            </button>
            <button
              onClick={onClose}
              className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
            >
              <X size={20} className="text-gray-500" />
            </button>
          </div>
        </div>

        {/* Manual Entry Option */}
        {/* <div className="p-4 border-b border-gray-200 bg-blue-50">
          <button
            onClick={handleManualEntry}
            className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
          >
            <Plus size={18} />
            <span className="font-medium">Add Citation Manually</span>
          </button>
          <p className="text-xs text-gray-600 mt-2 text-center">
            Enter citation details directly without Zotero
          </p>
        </div> */}

        {/* Search Bar */}
        <div className="p-4 border-b border-gray-200 bg-gray-50">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={18} />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by title, author, or year (e.g., 'Smith 2023')"
              className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              autoFocus
            />
          </div>
          {searchQuery && (
            <p className="text-xs text-gray-600 mt-2">
              Found {filteredCitations.length} {filteredCitations.length === 1 ? 'result' : 'results'} for "{searchQuery}"
            </p>
          )}
        </div>

        {/* Citation List */}
        <div className="flex-1 overflow-y-auto p-4">
          {loading && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <div className="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-purple-600 mb-2"></div>
                <p className="text-gray-600">Loading Zotero library...</p>
              </div>
            </div>
          )}

          {error && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <div className="text-red-500 mb-2">⚠️</div>
                <p className="text-red-600">{error === 'ZOTERO_NOT_CONFIGURED' ? 'Zotero is not configured yet.' : error}</p>
                {error === 'ZOTERO_NOT_CONFIGURED' ? (
                  <button
                    onClick={() => setShowZoteroSettings(true)}
                    className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 inline-flex items-center gap-2"
                  >
                    <Settings size={16} /> Configure Zotero
                  </button>
                ) : (
                  <button
                    onClick={loadCitations}
                    className="mt-4 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                  >
                    Retry
                  </button>
                )}
              </div>
            </div>
          )}

          {loadingMore && !loading && !error && (
            <div className="flex items-center justify-center py-4">
              <div className="text-sm text-gray-600">
                Loading more citations in the background...
              </div>
            </div>
          )}

          {!loading && !error && filteredCitations.length === 0 && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <BookOpen className="mx-auto text-gray-400 mb-3" size={48} />
                <p className="text-gray-600 font-medium">
                  {searchQuery ? 'No citations found matching your search' : 'No citations available'}
                </p>
                {searchQuery && (
                  <p className="text-sm text-gray-500 mt-2">
                    Try searching with different keywords
                  </p>
                )}
                {!searchQuery && (
                  <p className="text-sm text-gray-500 mt-2">
                    Make sure Zotero is running and the extension is connected
                  </p>
                )}
              </div>
            </div>
          )}

          {!loading && !error && filteredCitations.length > 0 && (
            <div className="space-y-3">
              {filteredCitations.map((citation) => {
                const authors = citation?.data?.creators?.map(c => 
                  `${c.firstName} ${c.lastName}`.trim()
                ).join(', ') || 'Unknown author';
                const year = extractYear(citation.data.date);
                return (
                  <div
                    key={citation.key}
                    onClick={() => handleSelectCitation(citation)}
                    className="p-4 border border-gray-200 rounded-lg hover:border-purple-500 hover:shadow-md cursor-pointer transition-all bg-white hover:bg-purple-50"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-gray-800 mb-2 line-clamp-2">
                          {citation.data.title}
                        </h3>
                        <div className="flex flex-wrap gap-4 text-sm text-gray-600 mb-2">
                          <div className="flex items-center gap-1 min-w-0">
                            <User size={14} className="flex-shrink-0" />
                            <span className="truncate">{authors}</span>
                          </div>
                          {year && (
                            <div className="flex items-center gap-1 flex-shrink-0">
                              <Calendar size={14} />
                              <span>{year}</span>
                            </div>
                          )}
                          {citation.data.itemType && (
                            <div className="px-2 py-0.5 bg-purple-100 text-purple-700 rounded text-xs flex-shrink-0">
                              {citation.data.itemType}
                            </div>
                          )}
                        </div>
                        {citation.data.publicationTitle && (
                          <p className="text-xs text-gray-500 italic line-clamp-1">
                            {citation.data.publicationTitle}
                          </p>
                        )}
                        {citation.data.doi ? (
                          <a
                            href={normalizeDoiUrl(citation.data.doi)}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 mt-1 hover:underline"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <ExternalLink size={12} />
                            <span className="truncate">DOI: {citation.data.doi.replace(/^https?:\/\/(dx\.)?doi\.org\//, '')}</span>
                          </a>
                        ) : (
                          <div className="flex items-center gap-1 text-xs text-yellow-600 mt-1">
                            <AlertCircle size={12} />
                            <span>No DOI - will prompt to add</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 bg-gray-50">
          <div className="flex items-center justify-between text-xs text-gray-600 mb-2">
            <span className="font-medium">
              {filteredCitations.length} {filteredCitations.length === 1 ? 'citation' : 'citations'}
              {!searchQuery && citations.length > 0 && ` of ${citations.length}`}
              {loadingMore && ` · ${loadedCount} loaded so far`}
            </span>
            <span className="text-gray-500 font-medium">Format: {format.toUpperCase()}</span>
          </div>
          {filteredCitations.length > 0 && (
            <p className="text-xs text-gray-600 text-center pt-2 border-t border-gray-300">
              <span className="font-medium">💡 Click on any citation</span> to select and insert it
            </p>
          )}
        </div>
      </div>
      
      {/* DOI Prompt Dialog */}
      {showDoiPrompt && selectedCitation && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-70" onClick={() => setShowDoiPrompt(false)}>
          <div className="bg-white rounded-lg shadow-2xl p-6 max-w-lg w-full mx-4" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-start gap-3 mb-4">
              <div className="flex-shrink-0 w-10 h-10 bg-yellow-100 rounded-full flex items-center justify-center">
                <AlertCircle className="text-yellow-600" size={24} />
              </div>
              <div className="flex-1">
                <h3 className="text-lg font-bold text-gray-900 mb-1">DOI Missing</h3>
                <p className="text-sm text-gray-700 mb-2">
                  The selected citation <strong>"{selectedCitation.data.title}"</strong> does not have a DOI.
                </p>
                <p className="text-sm text-gray-600">
                  Would you like to add a DOI manually or proceed without it?
                </p>
              </div>
            </div>
            
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">
                Add DOI (optional):
              </label>
              <input
                type="text"
                value={manualDoi}
                onChange={(e) => setManualDoi(e.target.value)}
                onKeyPress={(e) => {
                  if (e.key === 'Enter' && manualDoi.trim()) {
                    handleAddDoiAndConfirm();
                  }
                }}
                placeholder="10.1234/example"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
              <p className="text-xs text-gray-500 mt-1">
                Enter the DOI (e.g., "10.1234/example") or leave blank to skip
              </p>
            </div>
            
            <div className="flex gap-2 justify-end">
              <button
                onClick={handleConfirmWithoutDoi}
                className="px-4 py-2 bg-gray-200 hover:bg-gray-300 text-gray-800 rounded-lg font-medium transition-colors"
              >
                Proceed Without DOI
              </button>
              {manualDoi.trim() && (
                <button
                  onClick={handleAddDoiAndConfirm}
                  className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-medium transition-colors"
                >
                  Add DOI & Insert
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {showZoteroSettings && (
        <ZoteroSettingsDialog
          isOpen={showZoteroSettings}
          onClose={() => {
            setShowZoteroSettings(false);
            loadCitations();
          }}
        />
      )}
    </div>
  );
};

export default CitationPickerDialog;

// Helper component for rendering class hierarchy tree
interface ClassTreeNodeProps {
  nodes: TreeNode[];
  expandedNodes: Set<string>;
  selectedClass: TreeNode | null;
  onToggleExpand: (nodeId: string) => void;
  onSelectClass: (node: TreeNode) => void;
  depth: number;
}

const ClassTreeNode: React.FC<ClassTreeNodeProps> = ({
  nodes,
  expandedNodes,
  selectedClass,
  onToggleExpand,
  onSelectClass,
  depth
}) => {
  return (
    <div className="space-y-1">
      {nodes.map((node) => (
        <div key={node.id}>
          <div
            className={`flex items-center gap-1 p-2 rounded cursor-pointer transition-colors ${
              selectedClass?.id === node.id
                ? 'bg-blue-200 text-blue-900'
                : 'hover:bg-blue-100 text-gray-700'
            }`}
            style={{ paddingLeft: `${depth * 16 + 8}px` }}
          >
            {node.children && node.children.length > 0 ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleExpand(node.id);
                }}
                className="p-0 hover:bg-blue-200 rounded"
              >
                {expandedNodes.has(node.id) ? (
                  <ChevronDown size={14} />
                ) : (
                  <ChevronRight size={14} />
                )}
              </button>
            ) : (
              <div className="w-4" />
            )}
            <button
              onClick={() => onSelectClass(node)}
              className="flex-1 text-left text-sm font-medium truncate"
            >
              {node.label}
            </button>
            {node.directInstanceCount !== undefined && node.directInstanceCount > 0 && (
              <span className="text-xs bg-blue-200 text-blue-900 px-2 py-0.5 rounded whitespace-nowrap ml-1">
                {node.directInstanceCount}
              </span>
            )}
          </div>

          {/* Render children if expanded */}
          {expandedNodes.has(node.id) && node.children && node.children.length > 0 && (
            <ClassTreeNode
              nodes={node.children}
              expandedNodes={expandedNodes}
              selectedClass={selectedClass}
              onToggleExpand={onToggleExpand}
              onSelectClass={onSelectClass}
              depth={depth + 1}
            />
          )}
        </div>
      ))}
    </div>
  );
};
