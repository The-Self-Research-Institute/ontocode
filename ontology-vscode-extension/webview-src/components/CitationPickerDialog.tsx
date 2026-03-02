// CitationPickerDialog.tsx
import React, { useState, useEffect } from 'react';
import { X, Search, BookOpen, User, Calendar, ExternalLink, Plus } from 'lucide-react';

interface CitationItem {
  key: string;
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
        const title = citation.title?.toLowerCase() || '';
        const authors = citation.creators?.map(c => 
          `${c.firstName} ${c.lastName}`.toLowerCase()
        ).join(' ') || '';
        const year = citation.date || '';
        
        return title.includes(query) || authors.includes(query) || year.includes(query);
      });
      setFilteredCitations(filtered);
    }
  }, [searchQuery, citations]);

  const loadCitations = () => {
    setLoading(true);
    setError(null);

    // Request citations from extension via postMessage
    if (window.vscode) {
      window.vscode.postMessage({
        type: 'requestZoteroLibrary'
      });
    }

    // Listen for response
    const messageHandler = (event: MessageEvent) => {
      const message = event.data;
      
      if (message.type === 'zoteroLibraryData') {
        setCitations(message.items || []);
        setFilteredCitations(message.items || []);
        setLoading(false);
      } else if (message.type === 'zoteroLibraryError') {
        setError(message.error || 'Failed to load Zotero library');
        setLoading(false);
      }
    };

    window.addEventListener('message', messageHandler);
    
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

  const handleSelectCitation = (citation: CitationItem) => {
    onSelectCitation(citation);
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
          <button
            onClick={onClose}
            className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {/* Manual Entry Option */}
        <div className="p-4 border-b border-gray-200 bg-blue-50">
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
        </div>

        {/* Search Bar */}
        <div className="p-4 border-b border-gray-200">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={18} />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by title, author, or year..."
              className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
            />
          </div>
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
                <p className="text-red-600">{error}</p>
                <button
                  onClick={loadCitations}
                  className="mt-4 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                >
                  Retry
                </button>
              </div>
            </div>
          )}

          {!loading && !error && filteredCitations.length === 0 && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <BookOpen className="mx-auto text-gray-400 mb-2" size={48} />
                <p className="text-gray-600">
                  {searchQuery ? 'No citations found matching your search' : 'No citations available'}
                </p>
                {!searchQuery && (
                  <p className="text-sm text-gray-500 mt-2">
                    Make sure Sci2Code extension is installed and connected to Zotero
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
                const year = extractYear(citation.date);
console.log('Rendering citation:', { title: citation.title, authors, year },citation);
                return (
                  <div
                    key={citation.key}
                    onClick={() => handleSelectCitation(citation)}
                    className="p-4 border border-gray-200 rounded-lg hover:border-purple-500 hover:shadow-md cursor-pointer transition-all bg-white"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1">
                        <h3 className="font-semibold text-gray-800 mb-2">
                          {citation?.data.title}
                        </h3>
                        <div className="flex flex-wrap gap-4 text-sm text-gray-600">
                          <div className="flex items-center gap-1">
                            <User size={14} />
                            <span>{authors}</span>
                          </div>
                          {year && (
                            <div className="flex items-center gap-1">
                              <Calendar size={14} />
                              <span>{year}</span>
                            </div>
                          )}
                          {citation.itemType && (
                            <div className="px-2 py-0.5 bg-purple-100 text-purple-700 rounded text-xs">
                              {citation.itemType}
                            </div>
                          )}
                        </div>
                        {citation.publicationTitle && (
                          <p className="text-xs text-gray-500 mt-1 italic">
                            {citation.publicationTitle}
                          </p>
                        )}
                        {citation.doi && (
                          <div className="flex items-center gap-1 text-xs text-blue-600 mt-1">
                            <ExternalLink size={12} />
                            <span>DOI: {citation.doi}</span>
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
          <div className="flex items-center justify-between text-xs text-gray-600">
            <span>
              {filteredCitations.length} {filteredCitations.length === 1 ? 'citation' : 'citations'}
              {searchQuery && ` matching "${searchQuery}"`}
            </span>
            <span className="text-gray-500">Format: {format.toUpperCase()}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CitationPickerDialog;
