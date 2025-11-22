import React, { useMemo, useState, useEffect, useRef } from 'react';
import { Search, X, ChevronDown, ChevronUp, WrapText } from 'lucide-react';

interface CodeHighlighterProps {
  content: string;
  format: 'turtle' | 'rdfxml' | 'ntriples' | 'owl';
}

const MAX_LINES_INITIAL = 500; // Show first 500 lines initially
const CHUNK_SIZE = 200; // Process 200 lines at a time
const SEARCH_DEBOUNCE_MS = 400; // Debounce search input
const CONTEXT_LINES = 500; // Lines to show above and below selection
const MAX_SEARCH_LINES = 10000; // Limit search to prevent hanging on huge files
const SEARCH_CHUNK_SIZE = 100; // Process 100 lines per chunk for search
const SEARCH_CHUNK_DELAY = 8; // 8ms delay between search chunks

export const CodeHighlighter: React.FC<CodeHighlighterProps> = ({ content, format }) => {
  const [displayedLines, setDisplayedLines] = useState(MAX_LINES_INITIAL);
  const [isProcessing, setIsProcessing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [debouncedSearchQuery, setDebouncedSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<number[]>([]);
  const [currentMatchIndex, setCurrentMatchIndex] = useState(0);
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const [searchProgress, setSearchProgress] = useState(0);
  const [selectedLine, setSelectedLine] = useState<number | null>(null);
  const [selectedLines, setSelectedLines] = useState<Set<number>>(new Set());
  const [jumpToLine, setJumpToLine] = useState('');
  const [showSearchPanel, setShowSearchPanel] = useState(false);
  const [wordWrap, setWordWrap] = useState(false);
  const isDragging = useRef(false);
  const dragStartLine = useRef<number | null>(null);
  const lastClickedLine = useRef<number | null>(null);
  
  const codeRef = useRef<HTMLPreElement>(null);
  const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const searchCancelRef = useRef<boolean>(false);

  const totalLines = useMemo(() => {
    return content ? content.split(/\r?\n/).length : 0;
  }, [content]);

  // Debounce search query
  useEffect(() => {
    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current);
    }

    searchTimeoutRef.current = setTimeout(() => {
      setDebouncedSearchQuery(searchQuery);
    }, SEARCH_DEBOUNCE_MS);

    return () => {
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current);
      }
    };
  }, [searchQuery]);

  // Search through content with chunked processing and progress (debounced)
  useEffect(() => {
    if (!debouncedSearchQuery || !content) {
      setSearchResults([]);
      setCurrentMatchIndex(0);
      setIsSearching(false);
      setSearchProgress(0);
      return;
    }

    setIsSearching(true);
    setSearchProgress(0);
    searchCancelRef.current = false;
    
    let searchTimeout: NodeJS.Timeout;
    let animationFrameId: number;
    
    const startSearch = () => {
      const lines = content.split(/\r?\n/);
      const matches: number[] = [];
      const query = caseSensitive ? debouncedSearchQuery : debouncedSearchQuery.toLowerCase();
      const maxLines = Math.min(lines.length, MAX_SEARCH_LINES);

      // Process in smaller chunks with progress updates
      let processed = 0;
      
      const processChunk = () => {
        // Check if search was cancelled
        if (searchCancelRef.current) {
          setIsSearching(false);
          setSearchProgress(0);
          return;
        }
        
        const end = Math.min(processed + SEARCH_CHUNK_SIZE, maxLines);
        
        for (let i = processed; i < end; i++) {
          const searchLine = caseSensitive ? lines[i] : lines[i].toLowerCase();
          if (searchLine.includes(query)) {
            matches.push(i);
          }
        }
        
        processed = end;
        const progress = Math.floor((processed / maxLines) * 100);
        setSearchProgress(progress);
        
        if (processed < maxLines) {
          // Continue processing with delay to prevent hanging
          searchTimeout = setTimeout(() => {
            animationFrameId = requestAnimationFrame(processChunk);
          }, SEARCH_CHUNK_DELAY);
        } else {
          // Done
          setSearchResults(matches);
          setCurrentMatchIndex(0);
          setIsSearching(false);
          setSearchProgress(100);
          setShowSearchPanel(matches.length > 0);
          
          // Clear progress after a delay
          setTimeout(() => setSearchProgress(0), 500);
        }
      };
      
      animationFrameId = requestAnimationFrame(processChunk);
    };
    
    searchTimeout = setTimeout(startSearch, 100);

    return () => {
      if (searchTimeout) clearTimeout(searchTimeout);
      if (animationFrameId) cancelAnimationFrame(animationFrameId);
      searchCancelRef.current = true;
    };
  }, [debouncedSearchQuery, content, caseSensitive]);

  // Scroll to current match
  useEffect(() => {
    if (searchResults.length > 0 && codeRef.current) {
      const lineNumber = searchResults[currentMatchIndex];
      // Ensure the line is loaded
      if (lineNumber >= displayedLines) {
        setDisplayedLines(Math.min(lineNumber + 50, totalLines));
      }
      
      // Scroll to the line
      setTimeout(() => {
        const codeElement = codeRef.current;
        if (codeElement) {
          const lineHeight = 20; // Approximate line height in pixels
          const scrollPosition = lineNumber * lineHeight;
          codeElement.scrollTop = scrollPosition - 100; // Offset for visibility
        }
      }, 100);
    }
  }, [currentMatchIndex, searchResults, displayedLines, totalLines]);

  const highlightedContent = useMemo(() => {
    if (!content) return '';

    const lines = content.split(/\r?\n/);
    const linesToShow = lines.slice(0, displayedLines);
    
    // Pre-allocate array for better performance
    const numberedLines: string[] = new Array(linesToShow.length);
    
    for (let index = 0; index < linesToShow.length; index++) {
      const line = linesToShow[index];
      const lineNumber = index + 1;
      
      let processedLine = '';
      
      // Fast path for empty lines
      if (!line.trim()) {
        processedLine = '&nbsp;';
      } else {
        // Apply syntax highlighting based on format
        switch (format) {
          case 'turtle':
            processedLine = highlightTurtleLine(line);
            break;
          case 'rdfxml':
            processedLine = highlightRDFXMLLine(line);
            break;
          case 'ntriples':
            processedLine = highlightNTriplesLine(line);
            break;
          case 'owl':
            processedLine = highlightOWLLine(line);
            break;
          default:
            processedLine = escapeHtml(line);
        }

        // Highlight search matches only when actively searching
        if (debouncedSearchQuery && debouncedSearchQuery.length >= 1) {
          const query = escapeRegex(debouncedSearchQuery);
          const flags = caseSensitive ? 'g' : 'gi';
          const regex = new RegExp(query, flags);
          
          // Split by HTML tags to safely highlight only text content
          const parts = processedLine.split(/(<[^>]+>)/g);
          
          processedLine = parts.map(part => {
            // If it's a tag, return as is
            if (part.startsWith('<') && part.endsWith('>')) {
              return part;
            }
            // Otherwise highlight matches in text
            return part.replace(regex, (match) => {
              return `<mark style="background-color:#f59e0b;color:#000;padding:0 2px;border-radius:2px">${match}</mark>`;
            });
          }).join('');
        }
      }

      const isLineSelected = selectedLines.has(index);
      const lineStyle = isLineSelected ? 'background-color:#264f78' : '';
      const lineNumberColor = isLineSelected ? '#4a9eff' : '#858585';
      const lineNumberWeight = isLineSelected ? 'bold' : 'normal';

      numberedLines[index] =
        `<div class="code-line" data-line="${index}" style="${lineStyle};display:flex;min-height:20px;line-height:20px;padding:0;margin:0">` +
        `<span style="color:${lineNumberColor};font-weight:${lineNumberWeight};user-select:none;width:50px;min-width:50px;text-align:right;padding-right:12px;flex-shrink:0;cursor:pointer" class="line-number" data-line-idx="${index}" title="Click to select/deselect line">${lineNumber}</span>` +
        `<span style="color:#d4d4d4;white-space:${wordWrap ? 'pre-wrap' : 'pre'};overflow-wrap:${wordWrap ? 'anywhere' : 'normal'};word-break:${wordWrap ? 'break-word' : 'normal'};flex:1;min-width:0;user-select:text;cursor:text" class="line-content">${processedLine}</span>` +
        `</div>`;
    }

    return numberedLines.join('');
  }, [content, format, displayedLines, debouncedSearchQuery, caseSensitive, selectedLines, wordWrap]);

  const loadMore = () => {
    if (isProcessing || displayedLines >= totalLines) return;
    setIsProcessing(true);
    setTimeout(() => {
      setDisplayedLines(prev => Math.min(prev + CHUNK_SIZE, totalLines));
      setIsProcessing(false);
    }, 0);
  };

  const nextMatch = () => {
    if (searchResults.length > 0) {
      setCurrentMatchIndex((prev) => (prev + 1) % searchResults.length);
    }
  };

  const previousMatch = () => {
    if (searchResults.length > 0) {
      setCurrentMatchIndex((prev) => (prev - 1 + searchResults.length) % searchResults.length);
    }
  };

  const clearSearch = () => {
    searchCancelRef.current = true;
    setSearchQuery('');
    setSearchResults([]);
    setCurrentMatchIndex(0);
    setShowSearchPanel(false);
    setIsSearching(false);
    setSearchProgress(0);
  };

  const handleLineClick = (lineIndex: number) => {
    setSelectedLine(lineIndex);
    
    // Load context around the selected line (500 above and 500 below)
    const startLine = Math.max(0, lineIndex - CONTEXT_LINES);
    const endLine = Math.min(totalLines, lineIndex + CONTEXT_LINES + 1);
    
    if (endLine > displayedLines) {
      setDisplayedLines(endLine);
    }
    
    // Scroll to selected line
    setTimeout(() => {
      const codeElement = codeRef.current;
      if (codeElement) {
        const lineElements = codeElement.querySelectorAll('.code-line');
        const selectedElement = lineElements[lineIndex];
        if (selectedElement) {
          selectedElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }
    }, 100);
  };

  const handleSearchResultClick = (lineIndex: number) => {
    handleLineClick(lineIndex);
    setCurrentMatchIndex(searchResults.indexOf(lineIndex));
    setShowSearchPanel(false);
  };

  const handleJumpToLine = () => {
    const lineNum = parseInt(jumpToLine);
    if (isNaN(lineNum) || lineNum < 1 || lineNum > totalLines) {
      return;
    }
    handleLineClick(lineNum - 1); // Convert to 0-indexed
    setJumpToLine('');
  };

  const handleJumpKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleJumpToLine();
    }
  };

  const handleCopyCode = async () => {
    if (selectedLines.size === 0) return;
    
    const lines = content.split(/\r?\n/);
    const sortedLineNumbers = Array.from(selectedLines).sort((a, b) => a - b);
    const selectedText = sortedLineNumbers.map(lineIndex => lines[lineIndex]).join('\n');
    
    try {
      await navigator.clipboard.writeText(selectedText);
      // Clear selection after copy
      setSelectedLines(new Set());
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  const handleCopyAll = async () => {
    try {
      await navigator.clipboard.writeText(content);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  const handleLineSelect = (lineIndex: number) => {
    setSelectedLines(prev => {
      const newSet = new Set(prev);
      if (newSet.has(lineIndex)) {
        newSet.delete(lineIndex);
      } else {
        newSet.add(lineIndex);
      }
      return newSet;
    });
  };

  const handleSelectAll = () => {
    const lines = content.split(/\r?\n/);
    const allLines = new Set(Array.from({ length: Math.min(displayedLines, lines.length) }, (_, i) => i));
    setSelectedLines(allLines);
  };

  const handleClearSelection = () => {
    setSelectedLines(new Set());
  };

  useEffect(() => {
    setDisplayedLines(MAX_LINES_INITIAL);
    clearSearch();
    setSelectedLine(null);
    setSelectedLines(new Set());
  }, [content, format]);

  useEffect(() => {
    const codeElement = codeRef.current;
    if (!codeElement) return;

    const handleMouseDown = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      if (target.classList.contains('line-number')) {
        e.preventDefault();
        const lineIndexAttr = target.getAttribute('data-line-idx');
        if (lineIndexAttr !== null) {
          const lineIndex = parseInt(lineIndexAttr);
          
          isDragging.current = true;
          dragStartLine.current = lineIndex;
          
          if (e.shiftKey && lastClickedLine.current !== null) {
            const start = Math.min(lastClickedLine.current, lineIndex);
            const end = Math.max(lastClickedLine.current, lineIndex);
            
            setSelectedLines(prev => {
              const newSet = new Set(prev);
              for (let i = start; i <= end; i++) {
                newSet.add(i);
              }
              return newSet;
            });
          } else {
            lastClickedLine.current = lineIndex;
            
            setSelectedLines(prev => {
              const newSet = e.ctrlKey || e.metaKey ? new Set(prev) : new Set();
              if (e.ctrlKey || e.metaKey) {
                 if (newSet.has(lineIndex)) newSet.delete(lineIndex);
                 else newSet.add(lineIndex);
              } else {
                 newSet.add(lineIndex);
              }
              return newSet;
            });
          }
        }
      }
    };

    const handleMouseMove = (e: MouseEvent) => {
      if (!isDragging.current || dragStartLine.current === null) return;
      
      const target = e.target as HTMLElement;
      if (target.classList.contains('line-number')) {
        const lineIndexAttr = target.getAttribute('data-line-idx');
        if (lineIndexAttr !== null) {
          const currentLine = parseInt(lineIndexAttr);
          
          const start = Math.min(dragStartLine.current, currentLine);
          const end = Math.max(dragStartLine.current, currentLine);
          
          setSelectedLines(prev => {
            const newSet = new Set(prev);
            for (let i = start; i <= end; i++) {
              newSet.add(i);
            }
            return newSet;
          });
        }
      }
    };

    const handleMouseUp = () => {
      isDragging.current = false;
      dragStartLine.current = null;
    };

    window.addEventListener('mouseup', handleMouseUp);
    codeElement.addEventListener('mousedown', handleMouseDown);
    codeElement.addEventListener('mousemove', handleMouseMove);
    
    return () => {
      window.removeEventListener('mouseup', handleMouseUp);
      codeElement.removeEventListener('mousedown', handleMouseDown);
      codeElement.removeEventListener('mousemove', handleMouseMove);
    };
  }, []);

  const hasMore = displayedLines < totalLines;

  return (
    <div className="h-full flex flex-col" style={{ minWidth: 0, maxWidth: '100%', overflow: 'hidden' }}>
      {/* Search Bar */}
      <div className="bg-gray-800 border-b border-gray-700 p-2 flex items-center gap-2">
        <div className="flex-1 relative">
          <Search className="absolute left-2 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                if (e.shiftKey) {
                  previousMatch();
                } else {
                  nextMatch();
                }
              }
            }}
            placeholder="Search in code..."
            className="w-full pl-8 pr-8 py-1.5 text-sm bg-gray-700 text-white border border-gray-600 rounded focus:outline-none focus:border-purple-500"
          />
          {searchQuery && (
            <button
              onClick={clearSearch}
              className="absolute right-2 top-1/2 transform -translate-y-1/2 text-gray-400 hover:text-white"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
        
        {isSearching && (
          <div className="flex items-center gap-2">
            <div className="w-32 bg-gray-700 rounded-full h-2 overflow-hidden">
              <div 
                className="bg-purple-600 h-full transition-all duration-300"
                style={{ width: `${searchProgress}%` }}
              />
            </div>
            <span className="text-xs text-gray-400">{searchProgress}%</span>
          </div>
        )}
        
        {!isSearching && searchResults.length > 0 && (
          <>
            <button
              onClick={() => setShowSearchPanel(!showSearchPanel)}
              className="text-xs text-gray-300 hover:text-white whitespace-nowrap"
            >
              {searchResults.length} {searchResults.length === 1 ? 'match' : 'matches'}
            </button>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-300 whitespace-nowrap">
                {currentMatchIndex + 1} of {searchResults.length}
              </span>
              <div className="flex gap-1">
                <button
                  onClick={previousMatch}
                  className="p-1 bg-gray-700 hover:bg-gray-600 rounded text-white"
                  title="Previous match"
                >
                  <ChevronUp className="w-4 h-4" />
                </button>
                <button
                  onClick={nextMatch}
                  className="p-1 bg-gray-700 hover:bg-gray-600 rounded text-white"
                  title="Next match"
                >
                  <ChevronDown className="w-4 h-4" />
                </button>
              </div>
            </div>
          </>
        )}
        
        <button
          onClick={() => setCaseSensitive(!caseSensitive)}
          className={`px-2 py-1 text-xs rounded ${
            caseSensitive 
              ? 'bg-purple-600 text-white' 
              : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
          }`}
          title="Case sensitive"
        >
          Aa
        </button>

        <button
          onClick={() => setWordWrap(!wordWrap)}
          className={`p-1 rounded ${
            wordWrap 
              ? 'bg-purple-600 text-white' 
              : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
          }`}
          title="Toggle Word Wrap"
        >
          <WrapText className="w-4 h-4" />
        </button>
        
        <div className="flex items-center gap-1 border-l border-gray-600 pl-2">
          <span className="text-xs text-gray-400">Go to:</span>
          <input
            type="number"
            value={jumpToLine}
            onChange={(e) => setJumpToLine(e.target.value)}
            onKeyPress={handleJumpKeyPress}
            placeholder="Line #"
            min="1"
            max={totalLines}
            className="w-20 px-2 py-1 text-xs bg-gray-700 text-white border border-gray-600 rounded focus:outline-none focus:border-purple-500"
          />
          <button
            onClick={handleJumpToLine}
            className="px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded"
            title="Jump to line"
          >
            Go
          </button>
        </div>
        
        <div className="flex items-center gap-1 border-l border-gray-600 pl-2">
          {selectedLines.size > 0 && (
            <>
              <span className="text-xs text-gray-400">{selectedLines.size} line{selectedLines.size !== 1 ? 's' : ''} selected</span>
              <button
                onClick={handleCopyCode}
                className="px-2 py-1 text-xs bg-green-600 hover:bg-green-700 text-white rounded"
                title={`Copy ${selectedLines.size} selected line${selectedLines.size !== 1 ? 's' : ''}`}
              >
                Copy Selected
              </button>
              <button
                onClick={handleClearSelection}
                className="px-2 py-1 text-xs bg-red-600 hover:bg-red-700 text-white rounded"
                title="Clear selection"
              >
                Clear
              </button>
            </>
          )}
          <button
            onClick={handleSelectAll}
            className="px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded"
            title="Select all visible lines"
          >
            Select All
          </button>
          <button
            onClick={handleCopyAll}
            className="px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded"
            title="Copy entire code"
          >
            Copy All
          </button>
        </div>
      </div>

      {/* Search Results Panel */}
      {showSearchPanel && searchResults.length > 0 && (
        <div className="bg-gray-800 border-b border-gray-700 max-h-64 overflow-auto">
          <div className="p-2">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-semibold text-gray-300">Search Results</span>
              <button
                onClick={() => setShowSearchPanel(false)}
                className="text-gray-400 hover:text-white"
              >
                <X className="w-3 h-3" />
              </button>
            </div>
            <div className="space-y-1">
              {searchResults.map((lineIndex, idx) => {
                const lines = content.split(/\r?\n/);
                const lineContent = lines[lineIndex] || '';
                const preview = lineContent.length > 80 ? lineContent.substring(0, 80) + '...' : lineContent;
                const isCurrentMatch = idx === currentMatchIndex;
                
                return (
                  <div
                    key={lineIndex}
                    onClick={() => handleSearchResultClick(lineIndex)}
                    className={`px-2 py-1 text-xs rounded cursor-pointer ${
                      isCurrentMatch
                        ? 'bg-purple-600 text-white'
                        : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-gray-400 font-mono" style={{minWidth: '50px'}}>
                        Line {lineIndex + 1}
                      </span>
                      <span className="truncate font-mono" style={{fontSize: '11px'}}>
                        {preview}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      <div className="flex-1" style={{ minWidth: 0, overflow: 'auto', maxWidth: '98vw', width: '100vw' }}>
        <pre
          ref={codeRef}
          className="bg-[#1e1e1e] p-4 rounded-lg text-sm font-mono h-full border border-gray-700"
          style={{
            lineHeight: '20px',
            tabSize: 4,
            whiteSpace: wordWrap ? 'pre-wrap' : 'pre',
            wordBreak: wordWrap ? 'break-word' : 'normal',
            overflowX: 'auto',
            overflowY: 'auto',
            width: '100%',
            maxWidth: '100vw',
            boxSizing: 'border-box',
            margin: 0
          }}
        >
        <div
          dangerouslySetInnerHTML={{ __html: highlightedContent }}
        />
      </pre>
      </div>
      {hasMore && (
        <div className="p-2 bg-gray-800 border-t border-gray-700 text-center">
          <button
            onClick={loadMore}
            disabled={isProcessing}
            className="px-4 py-2 bg-purple-600 text-white text-sm rounded hover:bg-purple-700 disabled:opacity-50"
          >
            {isProcessing 
              ? 'Loading...' 
              : `Load More (${displayedLines} / ${totalLines} lines)`
            }
          </button>
        </div>
      )}
    </div>
  );
};

// Line-by-line highlighting functions (no background colors, only text colors)
function highlightTurtleLine(line: string): string {
  if (line.trimStart().startsWith('#')) {
    return `<span style="color:#6a9955">${escapeHtml(line)}</span>`;
  }
  if (!line.trim()) return escapeHtml(line);

  let escaped = escapeHtml(line);
  const MARKER = '\u0000';
  const replacements: string[] = [];
  let counter = 0;

  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  let result = escaped
    .replace(/(@prefix|@base)(\s+)/g, (_match, keyword, space) => `${store(`<span style="color:#c586c0">${keyword}</span>`)}${space}`)
    .replace(/(&lt;[^&gt;]+&gt;)/g, (match) => store(`<span style="color:#4ec9b0">${match}</span>`))
    .replace(/("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g, (_match, str, lang) =>
      `${store(`<span style="color:#ce9178">${str}</span>`)}${store(`<span style="color:#4fc1ff">${lang}</span>`)}`)
    .replace(/("(?:[^"\\]|\\.)*")/g, (match) => store(`<span style="color:#ce9178">${match}</span>`))
    .replace(/(\^\^)/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`))
    .replace(/\b([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)\b/g, (_match, prefix, name) =>
      `${store(`<span style="color:#9cdcfe">${prefix}</span>`)}:${store(`<span style="color:#dcdcaa">${name}</span>`)}`)
    .replace(/\b(a|true|false)\b/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/([;.,\[\]()])/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`));

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightRDFXMLLine(line: string): string {
  let escaped = escapeHtml(line);
  const MARKER = '\u0000'; // Null character as marker
  const replacements: string[] = [];
  let counter = 0;

  // Function to store a replacement and return a marker
  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  let result = escaped
    .replace(/(&lt;\?xml[^?]*\?&gt;)/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/(&lt;!--.*?--&gt;)/g, (match) => store(`<span style="color:#6a9955">${match}</span>`))
    .replace(/=(&quot;[^&quot;]*&quot;)/g, (_match, value) => `=${store(`<span style="color:#ce9178">${value}</span>`)}`)
    .replace(/(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g, (_match, open, ns, name) =>
      `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`)
    .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, (_match, open, name) => `${open}${store(`<span style="color:#4ec9b0">${name}</span>`)}`)
    .replace(/(\s)([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, (_match, space, attr) =>
      `${space}${store(`<span style="color:#9cdcfe">${attr}</span>`)}=`)
    .replace(/(\/?&gt;)/g, (match) => store(`<span style="color:#808080">${match}</span>`));

  // Restore all stored replacements
  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightNTriplesLine(line: string): string {
  if (line.trimStart().startsWith('#')) {
    return `<span style="color:#6a9955">${escapeHtml(line)}</span>`;
  }
  if (!line.trim()) return escapeHtml(line);

  let escaped = escapeHtml(line);
  return escaped
    .replace(/(&lt;[^&gt;]+&gt;)/g, '<span style="color:#4ec9b0">$1</span>')
    .replace(/("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g, '<span style="color:#ce9178">$1</span><span style="color:#4fc1ff">$2</span>')
    .replace(/("(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
    .replace(/(\^\^)/g, '<span style="color:#d4d4d4">$1</span>')
    .replace(/(\s\.\s*$)/g, '<span style="color:#d4d4d4">$1</span>');
}

function highlightOWLLine(line: string): string {
  let escaped = escapeHtml(line);
  const MARKER = '\u0000'; // Null character as marker
  const replacements: string[] = [];
  let counter = 0;

  // Function to store a replacement and return a marker
  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  let result = escaped
    .replace(/(&lt;\?xml[^?]*\?&gt;)/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/(&lt;!--.*?--&gt;)/g, (match) => store(`<span style="color:#6a9955">${match}</span>`))
    .replace(/(&lt;!DOCTYPE[^&gt;]*&gt;)/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/=(&quot;[^&quot;]*&quot;)/g, (_match, value) => `=${store(`<span style="color:#ce9178">${value}</span>`)}`)
    .replace(/(&lt;\/?)((owl|rdf|rdfs|xsd|dc|dcterms):([a-zA-Z_][\w-]*))/g, (_match, open, _full, ns, name) =>
      `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`)
    .replace(/(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g, (_match, open, ns, name) =>
      `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`)
    .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, (_match, open, name) => `${open}${store(`<span style="color:#4ec9b0">${name}</span>`)}`)
    .replace(/(\s)([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, (_match, space, attr) =>
      `${space}${store(`<span style="color:#9cdcfe">${attr}</span>`)}=`)
    .replace(/(\/?&gt;)/g, (match) => store(`<span style="color:#808080">${match}</span>`));

  // Restore all stored replacements
  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightTurtle(lines: string[]): string {
  const result: string[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    
    // Comments - fast path
    if (line.trimStart().startsWith('#')) {
      result.push(`<span style="color:#6a9955">${escapeHtml(line)}</span>`);
      continue;
    }

    // Empty lines - fast path
    if (!line.trim()) {
      result.push(line);
      continue;
    }

    let escaped = escapeHtml(line);
    
    // Apply highlighting in order (most specific to least specific)
    escaped = escaped
      // Prefixes and base
      .replace(/(@prefix|@base)(\s+)/g, '<span style="color:#c586c0">$1</span>$2')
      // URIs in angle brackets
      .replace(/(&lt;[^&gt;]+&gt;)/g, '<span style="color:#4ec9b0">$1</span>')
      // Literals with language tags
      .replace(/("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g, '<span style="color:#ce9178">$1</span><span style="color:#4fc1ff">$2</span>')
      // Literals without language tags
      .replace(/("(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
      // Datatype indicators
      .replace(/(\^\^)/g, '<span style="color:#d4d4d4">$1</span>')
      // Prefixed names
      .replace(/\b([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)\b/g, '<span style="color:#9cdcfe">$1</span>:<span style="color:#dcdcaa">$2</span>')
      // Keywords
      .replace(/\b(a|true|false)\b/g, '<span style="color:#569cd6">$1</span>')
      // Punctuation
      .replace(/([;.,\[\]()])/g, '<span style="color:#d4d4d4">$1</span>');
    
    result.push(escaped);
  }
  
  return result.join('\n');
}

function highlightRDFXML(lines: string[]): string {
  const result: string[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let escaped = escapeHtml(line);
    
    // Apply highlighting
    escaped = escaped
      // XML Declaration
      .replace(/(&lt;\?xml[^?]*\?&gt;)/g, '<span style="color:#569cd6">$1</span>')
      // Comments
      .replace(/(&lt;!--.*?--&gt;)/g, '<span style="color:#6a9955">$1</span>')
      // Attribute values (before tags to avoid conflicts)
      .replace(/=(&quot;[^&quot;]*&quot;|"[^"]*")/g, '=<span style="color:#ce9178">$1</span>')
      // Opening/closing tags with namespace
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g, '$1<span style="color:#569cd6">$2</span>:<span style="color:#4ec9b0">$3</span>')
      // Opening/closing tags without namespace
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, '$1<span style="color:#4ec9b0">$2</span>')
      // Attribute names
      .replace(/\s([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, ' <span style="color:#9cdcfe">$1</span>=')
      // Tag closing
      .replace(/(\/?&gt;)/g, '<span style="color:#808080">$1</span>');
    
    result.push(escaped);
  }
  
  return result.join('\n');
}

function highlightNTriples(lines: string[]): string {
  const result: string[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    
    // Comments - fast path
    if (line.trimStart().startsWith('#')) {
      result.push(`<span style="color:#6a9955">${escapeHtml(line)}</span>`);
      continue;
    }

    // Empty lines - fast path
    if (!line.trim()) {
      result.push(line);
      continue;
    }

    let escaped = escapeHtml(line);
    
    // Apply highlighting
    escaped = escaped
      // URIs in angle brackets
      .replace(/(&lt;[^&gt;]+&gt;)/g, '<span style="color:#4ec9b0">$1</span>')
      // Literals with language tags
      .replace(/("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g, '<span style="color:#ce9178">$1</span><span style="color:#4fc1ff">$2</span>')
      // Literals without language tags
      .replace(/("(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
      // Datatype indicators
      .replace(/(\^\^)/g, '<span style="color:#d4d4d4">$1</span>')
      // Triple terminator
      .replace(/(\s\.\s*$)/g, '<span style="color:#d4d4d4">$1</span>');
    
    result.push(escaped);
  }
  
  return result.join('\n');
}

function highlightOWL(lines: string[]): string {
  const result: string[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let escaped = escapeHtml(line);
    
    // Apply OWL/XML highlighting (similar to RDF/XML but with OWL-specific elements)
    escaped = escaped
      // XML Declaration
      .replace(/(&lt;\?xml[^?]*\?&gt;)/g, '<span style="color:#569cd6">$1</span>')
      // Comments
      .replace(/(&lt;!--.*?--&gt;)/g, '<span style="color:#6a9955">$1</span>')
      // DOCTYPE
      .replace(/(&lt;!DOCTYPE[^&gt;]*&gt;)/g, '<span style="color:#569cd6">$1</span>')
      // Attribute values (before tags to avoid conflicts)
      .replace(/=(&quot;[^&quot;]*&quot;|"[^"]*")/g, '=<span style="color:#ce9178">$1</span>')
      // OWL and RDF elements with namespace
      .replace(/(&lt;\/?)((owl|rdf|rdfs|xsd|dc|dcterms):([a-zA-Z_][\w-]*))/g, '$1<span style="color:#569cd6">$3</span>:<span style="color:#4ec9b0">$4</span>')
      // Other namespaced elements
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g, '$1<span style="color:#569cd6">$2</span>:<span style="color:#4ec9b0">$3</span>')
      // Opening/closing tags without namespace
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, '$1<span style="color:#4ec9b0">$2</span>')
      // Attribute names (with or without namespace)
      .replace(/\s([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, ' <span style="color:#9cdcfe">$1</span>=')
      // Tag closing
      .replace(/(\/?&gt;)/g, '<span style="color:#808080">$1</span>');
    
    result.push(escaped);
  }
  
  return result.join('\n');
}

// Fast HTML escaping using a map for better performance
const htmlEscapeMap: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
};

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, char => htmlEscapeMap[char]);
}

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
