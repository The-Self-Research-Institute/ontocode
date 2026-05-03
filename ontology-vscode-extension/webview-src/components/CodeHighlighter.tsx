import React, { useMemo, useState, useEffect, useRef, useCallback } from "react";
import { normalizeDoi as normalizeDoiUtil, isValidDoiFormat } from '../utils/doi';
import {
  Search,
  X,
  ChevronDown,
  ChevronUp,
  WrapText,
  Plus,
  Trash2,
  BookOpen,
  Save,
  Link,
  Download,
  Edit2,
  AlertTriangle,
  ArrowRight,
} from "lucide-react";

/** Extract all line numbers mentioned in a parser error string (1-based). */
function parseErrorLines(errorStr: string): number[] {
  if (!errorStr) return [];
  const found = new Set<number>();
  const patterns = [
    /\bline[:\s]+(\d+)/gi,
    /at line (\d+)/gi,
    /\[(\d+),\s*\d+\]/g,
    /line (\d+),/gi,
    /\brow[:\s]+(\d+)/gi,
  ];
  for (const re of patterns) {
    let m: RegExpExecArray | null;
    while ((m = re.exec(errorStr)) !== null) {
      const n = parseInt(m[1], 10);
      if (n > 0) found.add(n);
    }
  }
  return Array.from(found);
}

// Declare vscode API
declare global {
  interface Window {
    vscode?: {
      postMessage: (message: any) => void;
    };
  }
}

interface CodeHighlighterProps {
  content: string;
  format: "turtle" | "rdfxml" | "ntriples" | "owlxml" | "manchester" | "functional";
  citationInsertionMode?: boolean;
  citationRemovalMode?: boolean;
  pendingCitation?: any;
  onInsertCitationAt?: (lineNumber: number) => void;
  onRemoveCitationAt?: (lineNumber: number) => void;
  onRequestZoteroCitation?: () => void;
  onContentChange?: (newContent: string) => void;
  readOnly?: boolean;
  onSaveContent?: (content: string) => void;
  syntaxError?: string | null;
}

const MAX_LINES_INITIAL = 500; // Show first 500 lines initially
const CHUNK_SIZE = 200; // Process 200 lines at a time
const SEARCH_DEBOUNCE_MS = 400; // Debounce search input
const CONTEXT_LINES = 500; // Lines to show above and below selection
const MAX_SEARCH_LINES = 10000; // Limit search to prevent hanging on huge files
const SEARCH_CHUNK_SIZE = 100; // Process 100 lines per chunk for search
const SEARCH_CHUNK_DELAY = 8; // 8ms delay between search chunks

export const CodeHighlighter: React.FC<CodeHighlighterProps> = ({
  content,
  format,
  citationInsertionMode = false,
  citationRemovalMode = false,
  pendingCitation,
  onInsertCitationAt,
  onRemoveCitationAt,
  onRequestZoteroCitation,
  onContentChange,
  readOnly = false,
  onSaveContent,
  syntaxError,
}) => {
  const [displayedLines, setDisplayedLines] = useState(MAX_LINES_INITIAL);
  const [isProcessing, setIsProcessing] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedSearchQuery, setDebouncedSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<number[]>([]);
  const [currentMatchIndex, setCurrentMatchIndex] = useState(0);
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const [searchProgress, setSearchProgress] = useState(0);
  const [jumpToLine, setJumpToLine] = useState("");
  const [showSearchPanel, setShowSearchPanel] = useState(false);
  const [wordWrap, setWordWrap] = useState(false);
  const [editedContent, setEditedContent] = useState<Map<number, string>>(new Map());
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [showAddDoiDialog, setShowAddDoiDialog] = useState(false);
  const [doiInputValue, setDoiInputValue] = useState("");
  const [doiInputError, setDoiInputError] = useState<string | null>(null);
  const [currentContent, setCurrentContent] = useState(content);
  const [isEditMode, setIsEditMode] = useState(false);
  const [cursorPosition, setCursorPosition] = useState({ line: 1, column: 1 });
  const [collapsedRanges, setCollapsedRanges] = useState<Map<number, number>>(new Map()); // startLineIdx -> endLineIdx (0-based)
  const [showErrorDialog, setShowErrorDialog] = useState(false);
  const editorRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lineNumberGutterRef = useRef<HTMLDivElement>(null);
  const foldGutterRef = useRef<HTMLDivElement>(null);

  // Derived: error line numbers (1-based) from the syntaxError prop
  const errorLineNumbers = useMemo(() => new Set(parseErrorLines(syntaxError || "")), [syntaxError]);

  /** Navigate to a 1-based line number in whichever mode is active. */
  const navigateToLine = useCallback((lineNumber: number) => {
    if (lineNumber < 1) return;
    const zeroIdx = lineNumber - 1;

    if (isEditMode && textareaRef.current) {
      const lines = currentContent.split("\n");
      // Ensure lines up to target are loaded
      if (lineNumber > displayedLines) setDisplayedLines(lineNumber + 50);
      const offset = lines.slice(0, zeroIdx).reduce((acc, l) => acc + l.length + 1, 0);
      const ta = textareaRef.current;
      ta.focus();
      ta.setSelectionRange(offset, offset + (lines[zeroIdx]?.length ?? 0));
      // Scroll textarea to that line
      const lineHeight = 22.4; // matches style lineHeight 1.6 * 14px
      ta.scrollTop = Math.max(0, zeroIdx * lineHeight - ta.clientHeight / 2);
      if (lineNumberGutterRef.current) lineNumberGutterRef.current.scrollTop = ta.scrollTop;
      if (foldGutterRef.current) foldGutterRef.current.scrollTop = ta.scrollTop;
    } else {
      // View mode: use existing handleLineClick
      handleLineClick(zeroIdx);
    }
    setShowErrorDialog(false);
  }, [isEditMode, currentContent, displayedLines]);

  const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const searchCancelRef = useRef<boolean>(false);

  const totalLines = useMemo(() => {
    return content ? content.split(/\r?\n/).length : 0;
  }, [content]);

  // Sync currentContent with content prop
  useEffect(() => {
    setCurrentContent(content);
  }, [content]);

  // Handle line content edit
  const handleContentEdit = (newContent: string) => {
    if (readOnly || !onContentChange) return;

    setCurrentContent(newContent);
    setHasUnsavedChanges(true);

    // Notify parent of content change
    onContentChange(newContent);
  };

  const handleSaveChanges = () => {
    // Block save when syntax errors are already known client-side
    if (syntaxError && errorLineNumbers.size > 0) {
      setShowErrorDialog(true);
      return;
    }
    if (onSaveContent) {
      onSaveContent(currentContent);
      setHasUnsavedChanges(false);
    }
  };

  const handleDownload = () => {
    console.log("[CodeHighlighter] Download initiated - Format:", format, "Content length:", currentContent?.length);

    // Determine file extension based on format
    const extensionMap: Record<typeof format, string> = {
      turtle: "ttl",
      rdfxml: "rdf",
      ntriples: "nt",
      owlxml: "owl",
      manchester: "omn",
      functional: "ofn",
    };

    const extension = extensionMap[format] || "txt";
    const filename = `ontology_${new Date().toISOString().replace(/[:.]/g, "-")}.${extension}`;

    console.log("[CodeHighlighter] Creating file:", filename);

    // Use VS Code API to download file (works in webview)
    if (window.vscode) {
      window.vscode.postMessage({
        type: "downloadFile",
        content: currentContent,
        filename: filename,
        format: format,
      });
      console.log("[CodeHighlighter] Download request sent to extension:", filename);
    } else {
      // Fallback to direct blob download (for browser testing)
      try {
        const blob = new Blob([currentContent], { type: "text/plain;charset=utf-8" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
        console.log("[CodeHighlighter] Downloaded file via blob:", filename, "Size:", blob.size, "bytes");
      } catch (error) {
        console.error("[CodeHighlighter] Download failed:", error);
        alert("Download failed: " + (error instanceof Error ? error.message : "Unknown error"));
      }
    }
  };

  const handleAddDoi = () => {
    console.log("[CodeHighlighter] Add DOI button clicked");
    console.log("[CodeHighlighter] readOnly:", readOnly, "onContentChange:", !!onContentChange);

    if (readOnly) {
      console.warn("[CodeHighlighter] Cannot add DOI - component is readOnly");
      return;
    }

    if (!onContentChange) {
      console.warn("[CodeHighlighter] Cannot add DOI - onContentChange callback not provided");
      return;
    }

    console.log("[CodeHighlighter] Opening Add DOI dialog");
    setShowAddDoiDialog(true);
  };

  const handleAddDoiConfirm = () => {
    console.log("[CodeHighlighter] Add DOI confirm clicked");
    console.log("[CodeHighlighter] DOI input value:", doiInputValue);
    console.log("[CodeHighlighter] Current format:", format);

    if (!doiInputValue.trim()) {
      console.warn("[CodeHighlighter] DOI input is empty");
      return;
    }

    // Validate DOI format before inserting
    const norm = normalizeDoiUtil(doiInputValue);
    if (!isValidDoiFormat(norm)) {
      console.warn("[CodeHighlighter] DOI appears malformed:", doiInputValue);
      setDoiInputError('DOI looks malformed');
      // Provide feedback to the user in the webview
      try {
        alert('The DOI you entered looks malformed. Please check the value and try again.');
      } catch (_) {}
      return;
    }
    setDoiInputError(null);

    if (!onContentChange) {
      console.warn("[CodeHighlighter] Cannot add DOI - onContentChange callback not provided");
      return;
    }

    // Get cursor position or add to end
    const lines = currentContent.split(/\r?\n/);
    console.log("[CodeHighlighter] Current content has", lines.length, "lines");

    // Add DOI to the end of the file
    const indent = "    ";
    let doiLine = "";
    if (format === "turtle") {
      doiLine = `${indent}bibo:doi "${doiInputValue}" ;`;
    } else if (format === "rdfxml") {
      doiLine = `${indent}<bibo:doi>${doiInputValue}</bibo:doi>`;
    } else if (format === "ntriples") {
      doiLine = `<http://purl.org/ontology/bibo/doi> "${doiInputValue}" .`;
    } else {
      doiLine = `${indent}bibo:doi "${doiInputValue}" ;`;
    }

    console.log("[CodeHighlighter] Adding DOI line:", doiLine);

    lines.push("", doiLine);
    const newContent = lines.join("\n");

    console.log("[CodeHighlighter] New content has", newContent.split(/\r?\n/).length, "lines");
    handleContentEdit(newContent);

    setShowAddDoiDialog(false);
    setDoiInputValue("");
    console.log("[CodeHighlighter] DOI added successfully");
  };

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
    if (searchResults.length > 0 && editorRef.current) {
      const lineNumber = searchResults[currentMatchIndex];
      // Ensure the line is loaded
      if (lineNumber >= displayedLines) {
        setDisplayedLines(Math.min(lineNumber + 50, totalLines));
      }

      // Scroll to the line
      setTimeout(() => {
        const codeElement = editorRef.current;
        if (codeElement) {
          const lineHeight = 20; // Approximate line height in pixels
          const scrollPosition = lineNumber * lineHeight;
          codeElement.scrollTop = scrollPosition - 100; // Offset for visibility
        }
      }, 100);
    }
  }, [currentMatchIndex, searchResults, displayedLines, totalLines]);

  // Auto-show search panel when in citation insertion mode
  useEffect(() => {
    if (citationInsertionMode || citationRemovalMode) {
      setShowSearchPanel(true);
      // Exit edit mode when citation mode is active
      if (isEditMode) {
        setIsEditMode(false);
      }
    }
  }, [citationInsertionMode, citationRemovalMode, isEditMode]);

  // Helper function to detect and extract DOI from a line
  const extractDOI = (line: string): string | null => {
    if (!line) return null;

    // Match DOI in different formats
    const doiPatterns = [
      /bibo:doi\s+"([^"]+)"/i, // Turtle: bibo:doi "10.1234/..."
      /dc:identifier\s+"doi:([^"]+)"/i, // Turtle: dc:identifier "doi:10.1234/..."
      /<bibo:doi>([^<]+)<\/bibo:doi>/i, // RDF/XML: <bibo:doi>10.1234/...</bibo:doi>
      /bibo:doi="([^"]+)"/i, // RDF/XML attribute: bibo:doi="10.1234/..."
    ];

    for (const pattern of doiPatterns) {
      const match = line.match(pattern);
      if (match && match[1]) {
        return match[1].trim();
      }
    }

    return null;
  };

  // Helper function to detect if a line is part of a citation block
  const detectCitationLine = (line: string, format: string): boolean => {
    if (!line) return false;

    // First check for the primary citation marker - urn:citation:
    if (/urn:citation:/i.test(line)) {
      return true;
    }

    // Check for citation comment markers
    if (/Zotero Citation/i.test(line) || /###\s*Zotero Citation/i.test(line) || /<!--\s*Zotero Citation/i.test(line)) {
      return true;
    }

    // Check for lines containing citation URIs in different formats
    if (
      /<urn:citation:[^>]+>/i.test(line) ||
      /IRI="urn:citation:/i.test(line) ||
      /rdf:about="urn:citation:/i.test(line)
    ) {
      return true;
    }

    // For context detection, we return false by default
    // The detection relies on finding the actual urn:citation marker
    // This prevents over-highlighting of non-citation lines
    return false;
  };

  // Line count for edit mode gutter
  const editLineCount = useMemo(() => {
    return currentContent ? currentContent.split("\n").length : 1;
  }, [currentContent]);

  // Detect foldable ranges from content (bracket matching + XML tag matching)
  const foldableRanges = useMemo(() => {
    const lines = currentContent.split("\n");
    const ranges: Map<number, number> = new Map(); // startLine -> endLine (0-indexed)

    // Bracket matching for { }, [ ], ( )
    const bracketMatch: Record<string, string> = { "{": "}", "[": "]", "(": ")" };
    const openBrackets = new Set(Object.keys(bracketMatch));
    const closeBrackets = new Set(Object.values(bracketMatch));
    const closeToOpen: Record<string, string> = { "}": "{", "]": "[", ")": "(" };

    interface StackEntry {
      char: string;
      line: number;
    }
    const stack: StackEntry[] = [];

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      let inString = false;
      let stringChar = "";
      for (let j = 0; j < line.length; j++) {
        const ch = line[j];
        if (inString) {
          if (ch === stringChar && line[j - 1] !== "\\") inString = false;
          continue;
        }
        if (ch === '"' || ch === "'") {
          inString = true;
          stringChar = ch;
          continue;
        }
        if (ch === "#") break; // Comment in Turtle
        if (openBrackets.has(ch)) {
          stack.push({ char: ch, line: i });
        } else if (closeBrackets.has(ch)) {
          for (let k = stack.length - 1; k >= 0; k--) {
            if (stack[k].char === closeToOpen[ch]) {
              const startLine = stack[k].line;
              stack.splice(k, 1);
              if (i > startLine) {
                if (!ranges.has(startLine) || ranges.get(startLine)! < i) {
                  ranges.set(startLine, i);
                }
              }
              break;
            }
          }
        }
      }
    }

    // XML tag matching for rdfxml/owlxml formats
    if (format === "rdfxml" || format === "owlxml") {
      const tagStack: { tag: string; line: number }[] = [];
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (line.match(/^<[^/].*\/>$/)) continue; // self-closing
        const closeMatch = line.match(/<\/([\w:-]+)\s*>/);
        const openMatch = line.match(/^<([\w:-]+)[\s>]/);
        if (closeMatch) {
          for (let k = tagStack.length - 1; k >= 0; k--) {
            if (tagStack[k].tag === closeMatch[1]) {
              const startLine = tagStack[k].line;
              tagStack.splice(k, 1);
              if (i > startLine) {
                if (!ranges.has(startLine) || ranges.get(startLine)! < i) {
                  ranges.set(startLine, i);
                }
              }
              break;
            }
          }
        } else if (openMatch && !line.includes("/>")) {
          tagStack.push({ tag: openMatch[1], line: i });
        }
      }
    }

    return ranges;
  }, [currentContent, format]);

  // Clear collapsed ranges when content changes externally
  useEffect(() => {
    setCollapsedRanges(new Map());
  }, [content]);

  // Toggle fold for a given start line
  const toggleFold = useCallback(
    (startLine: number) => {
      setCollapsedRanges((prev) => {
        const next = new Map(prev);
        if (next.has(startLine)) {
          next.delete(startLine);
        } else {
          const endLine = foldableRanges.get(startLine);
          if (endLine !== undefined) {
            next.set(startLine, endLine);
          }
        }
        return next;
      });
    },
    [foldableRanges],
  );

  // Compute display content for edit mode textarea with folds applied
  const editDisplayContent = useMemo(() => {
    if (collapsedRanges.size === 0) return currentContent;
    const lines = currentContent.split("\n");
    const result: string[] = [];
    let skipUntil = -1;
    for (let i = 0; i < lines.length; i++) {
      if (i <= skipUntil) continue;
      const foldEnd = collapsedRanges.get(i);
      if (foldEnd !== undefined) {
        result.push(lines[i] + `  \u22EF`); // ⋯ fold marker
        skipUntil = foldEnd;
      } else {
        result.push(lines[i]);
      }
    }
    return result.join("\n");
  }, [currentContent, collapsedRanges]);

  // Auto-unfold all when user focuses the textarea to edit
  const handleTextareaFocus = useCallback(() => {
    if (collapsedRanges.size > 0) {
      setCollapsedRanges(new Map());
    }
  }, [collapsedRanges]);

  const highlightedContent = useMemo(() => {
    if (!content) return "";

    console.log("🎨 useMemo: Re-rendering highlighted content", {
      contentLength: content.length,
      format,
      hasDOI: content.includes("bibo:doi") || content.includes("dc:identifier"),
      doiCount: (content.match(/bibo:doi/g) || []).length,
    });

    const lines = content.split(/\r?\n/);
    const linesToShow = lines.slice(0, displayedLines);

    // Build a map of citation blocks when in removal mode for better highlighting
    const citationBlockLines = new Set<number>();
    if (citationRemovalMode) {
      const isXmlFmt = format === "rdfxml" || format === "owlxml";
      const isTurtleFmt = format === "turtle" || format === "ntriples";
      const isManchesterFmt = format === "manchester";
      const isFunctionalFmt = format === "functional";

      // Find all citation URIs and detect their full block boundaries
      for (let i = 0; i < linesToShow.length; i++) {
        const line = linesToShow[i];
        const citationUriMatch = line.match(/urn:citation:([a-zA-Z0-9]+)/i);

        if (citationUriMatch) {
          const citationId = citationUriMatch[1];

          // Find block start (search backwards for comment or opening tag)
          let blockStart = i;
          for (let k = i - 1; k >= Math.max(0, i - 15); k--) {
            const prev = linesToShow[k].trim();
            if (prev.includes("Zotero Citation") || prev.startsWith("###") || prev.startsWith("<!--")) {
              blockStart = k;
              // Include blank lines before comment
              for (let b = k - 1; b >= Math.max(0, k - 2); b--) {
                if (linesToShow[b].trim() === "") blockStart = b;
                else break;
              }
              break;
            }
            if (
              isXmlFmt &&
              (prev.startsWith("<Declaration>") ||
                prev.startsWith("<owl:NamedIndividual") ||
                prev.startsWith("<ClassAssertion>"))
            ) {
              blockStart = k;
            }
            if (prev !== "" && !prev.includes("urn:citation:") && !isXmlFmt) break;
          }

          // Find block end (search forwards for closing statement)
          let blockEnd = i;
          for (let k = i; k < Math.min(linesToShow.length, i + 50); k++) {
            const trimmed = linesToShow[k].trim();
            if (isXmlFmt) {
              if (
                trimmed === "</owl:NamedIndividual>" ||
                trimmed === "</Declaration>" ||
                trimmed === "</ClassAssertion>" ||
                trimmed === "</AnnotationAssertion>"
              ) {
                blockEnd = k;
                if (k + 1 < linesToShow.length && linesToShow[k + 1].trim() === "") blockEnd = k + 1;
                break;
              }
            } else if (isTurtleFmt) {
              if (trimmed.endsWith(".") && !trimmed.startsWith("@") && !trimmed.startsWith("#")) {
                blockEnd = k;
                if (k + 1 < linesToShow.length && linesToShow[k + 1].trim() === "") blockEnd = k + 1;
                break;
              }
            } else if (isManchesterFmt) {
              if (
                trimmed === "" ||
                (k > i && trimmed.match(/^(Class|Individual|ObjectProperty|DataProperty|AnnotationProperty|Datatype):/))
              ) {
                blockEnd = trimmed === "" ? k : k - 1;
                break;
              }
            } else if (isFunctionalFmt) {
              if (trimmed === "") {
                blockEnd = k;
                break;
              }
            }
          }

          // Mark the entire block
          for (let k = blockStart; k <= blockEnd; k++) {
            citationBlockLines.add(k);
          }
        }
      }
    }

    // Pre-allocate array for better performance
    const numberedLines: string[] = [];

    // Debug: Check if we have any DOI lines to process
    const doiLines = linesToShow.filter((l) => l.includes("bibo:doi") || l.includes("dc:identifier"));
    if (doiLines.length > 0) {
      console.log("📊 Rendering content with DOI lines:", {
        format,
        count: doiLines.length,
        samples: doiLines.slice(0, 2),
      });
    }

    let skipUntilLine = -1;

    for (let index = 0; index < linesToShow.length; index++) {
      // Skip folded lines (but not the fold start line itself)
      if (index <= skipUntilLine) continue;

      const line = linesToShow[index];
      const lineNumber = index + 1;

      const isFoldable = foldableRanges.has(index);
      const isCollapsed = collapsedRanges.has(index);
      const foldEndLine = isCollapsed ? collapsedRanges.get(index)! : isFoldable ? foldableRanges.get(index)! : -1;

      // If collapsed, skip the folded lines for subsequent iterations
      if (isCollapsed) {
        skipUntilLine = foldEndLine;
      }

      let processedLine = "";

      // Fast path for empty lines
      if (!line.trim()) {
        processedLine = "&nbsp;";
      } else {
        // Apply syntax highlighting based on format
        switch (format) {
          case "turtle":
            processedLine = highlightTurtleLine(line);
            break;
          case "rdfxml":
            processedLine = highlightRDFXMLLine(line);
            break;
          case "ntriples":
            processedLine = highlightNTriplesLine(line);
            break;
          case "owlxml":
            processedLine = highlightOWLXMLLine(line);
            break;
          case "manchester":
          case "functional":
            processedLine = highlightOWLLine(line);
            break;
          default:
            processedLine = escapeHtml(line);
        }

        // Highlight search matches only when actively searching
        if (debouncedSearchQuery && debouncedSearchQuery.length >= 1) {
          const query = escapeRegex(debouncedSearchQuery);
          const flags = caseSensitive ? "g" : "gi";
          const regex = new RegExp(query, flags);

          // Split by HTML tags to safely highlight only text content
          const parts = processedLine.split(/(<[^>]+>)/g);

          processedLine = parts
            .map((part) => {
              // If it's a tag, return as is
              if (part.startsWith("<") && part.endsWith(">")) {
                return part;
              }
              // Otherwise highlight matches in text
              return part.replace(regex, (match) => {
                return `<mark style="background-color:#f59e0b;color:#000;padding:0 2px;border-radius:2px">${match}</mark>`;
              });
            })
            .join("");
        }
      }

      // If collapsed, append fold summary to the line content
      if (isCollapsed) {
        const foldedCount = foldEndLine - index;
        processedLine += `<span style="color:#569cd6;background:#264f78;padding:1px 6px;border-radius:3px;margin-left:8px;font-size:11px;cursor:pointer" class="fold-marker" data-fold-line="${index}"> \u22EF ${foldedCount} lines </span>`;
      }

      // Detect if this line is part of a citation block
      // When in removal mode, use the pre-calculated citation block map
      const isCitationLine = citationRemovalMode ? citationBlockLines.has(index) : detectCitationLine(line, format);

      // Extract DOI if present in this line
      const doi = extractDOI(line);
      const hasDOI = doi !== null;

      let lineStyle = "";
      // Highlight citation lines in removal mode
      if (citationRemovalMode && isCitationLine) {
        lineStyle = "background-color:#7f1d1d"; // Dark red for citation lines
      }
      // Add subtle highlight for DOI lines
      if (hasDOI && !citationRemovalMode) {
        lineStyle = "background-color:#1a3a2a"; // Dark green tint for DOI lines
      }

      // Enhanced line number visibility - brighter colors and larger font
      const isErrorLine = errorLineNumbers.has(lineNumber);
      const lineNumberColor =
        isErrorLine ? "#f87171" :
        citationRemovalMode && isCitationLine ? "#ef4444" :
        hasDOI ? "#10b981" : "#a1a1aa";
      const lineNumberWeight = hasDOI || isErrorLine ? "bold" : "600";
      const lineNumberSize = "13px";

      // Add hover highlight when in citation insertion or removal mode
      const citationModeCursor = citationInsertionMode || citationRemovalMode ? "cursor:pointer;" : "";
      const citationModeHoverStyle =
        citationInsertionMode || citationRemovalMode ? ";transition:background-color 0.2s" : "";

      // Build title text based on mode
      let lineNumberTitle = `Line ${lineNumber}`;
      if (citationInsertionMode) {
        lineNumberTitle = "Click to insert citation here";
      } else if (citationRemovalMode && isCitationLine) {
        lineNumberTitle = "Click to remove this citation";
      } else if (hasDOI) {
        lineNumberTitle = `DOI: ${doi}`;
      }

      // Fold indicator: ▶ (collapsed) or ▼ (expanded) for foldable lines
      const foldIndicatorHtml = isFoldable
        ? `<span style="color:${isCollapsed ? "#c5c5c5" : "#858585"};font-size:8px;user-select:none;width:16px;min-width:16px;text-align:center;flex-shrink:0;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;transition:transform 0.15s ease;transform:rotate(${isCollapsed ? "0deg" : "90deg"})" class="fold-indicator" data-fold-line="${index}" title="${isCollapsed ? "Unfold" : "Fold"}">&#9654;</span>`
        : `<span style="width:16px;min-width:16px;flex-shrink:0;display:inline-block"></span>`;

      const errorLineStyle = isErrorLine ? "background-color:rgba(239,68,68,0.12);border-left:2px solid #f87171;" : "";
      const combinedLineStyle = [lineStyle, errorLineStyle].filter(Boolean).join(";");
      const lineNumberDisplay = isErrorLine ? `⚠${lineNumber}` : `${lineNumber}`;

      numberedLines.push(
        `<div class="code-line${isCitationLine ? " citation-line" : ""}${hasDOI ? " doi-line" : ""}${isErrorLine ? " error-line" : ""}" data-line="${index}" data-line-idx="${index}" data-is-citation="${isCitationLine}" data-has-doi="${hasDOI}" data-doi="${hasDOI ? doi : ""}" style="${combinedLineStyle};display:flex;align-items:center;min-height:20px;line-height:20px;padding:0;margin:0${citationModeHoverStyle}">` +
          `<span style="color:${lineNumberColor};font-weight:${lineNumberWeight};font-size:${lineNumberSize};user-select:none;width:55px;min-width:55px;text-align:right;padding-right:4px;flex-shrink:0;cursor:${citationInsertionMode || citationRemovalMode ? "pointer" : "default"};opacity:0.9" class="line-number" data-line-idx="${index}" title="${isErrorLine ? "Syntax error on this line — click to navigate" : lineNumberTitle}">${lineNumberDisplay}</span>` +
          foldIndicatorHtml +
          `<span style="color:#d4d4d4;white-space:${wordWrap ? "pre-wrap" : "pre"};overflow-wrap:${wordWrap ? "anywhere" : "normal"};word-break:${wordWrap ? "break-word" : "normal"};flex:1;min-width:0;user-select:text;${citationModeCursor}" class="line-content" data-line-idx="${index}">${processedLine}</span>` +
          `</div>`,
      );
    }

    return numberedLines.join("");
  }, [
    content,
    format,
    displayedLines,
    debouncedSearchQuery,
    caseSensitive,
    wordWrap,
    citationInsertionMode,
    citationRemovalMode,
    readOnly,
    foldableRanges,
    collapsedRanges,
    errorLineNumbers,
  ]);

  const loadMore = () => {
    if (isProcessing || displayedLines >= totalLines) return;
    setIsProcessing(true);
    setTimeout(() => {
      setDisplayedLines((prev) => Math.min(prev + CHUNK_SIZE, totalLines));
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
    setSearchQuery("");
    setSearchResults([]);
    setCurrentMatchIndex(0);
    setShowSearchPanel(false);
    setIsSearching(false);
    setSearchProgress(0);
  };

  const handleLineClick = (lineIndex: number) => {
    // Load context around the clicked line (500 above and 500 below)
    const startLine = Math.max(0, lineIndex - CONTEXT_LINES);
    const endLine = Math.min(totalLines, lineIndex + CONTEXT_LINES + 1);

    if (endLine > displayedLines) {
      setDisplayedLines(endLine);
    }

    // Scroll to line
    setTimeout(() => {
      const codeElement = editorRef.current;
      if (codeElement) {
        const lineElements = codeElement.querySelectorAll(".code-line");
        const selectedElement = lineElements[lineIndex];
        if (selectedElement) {
          selectedElement.scrollIntoView({ behavior: "smooth", block: "center" });
        }
      }
    }, 100);
  };

  const handleSearchResultClick = (lineIndex: number) => {
    handleLineClick(lineIndex);
    setCurrentMatchIndex(searchResults.indexOf(lineIndex));
    setShowSearchPanel(false);
  };

  // Handle content edits via event delegation
  const handleCodeInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    if (readOnly || !onContentChange) return;

    const newContent = e.target.value;
    handleContentEdit(newContent);
    updateCursorPosition(e.target);
  };

  // Update cursor position
  const updateCursorPosition = (textarea: HTMLTextAreaElement) => {
    const text = textarea.value;
    const cursorPos = textarea.selectionStart;

    // Calculate line and column
    const textBeforeCursor = text.substring(0, cursorPos);
    const lines = textBeforeCursor.split("\n");
    const line = lines.length;
    const column = lines[lines.length - 1].length + 1;

    setCursorPosition({ line, column });
  };

  // Handle cursor position changes (clicks, arrow keys, mouse-up after drag, etc.)
  const handleCursorMove = (e: React.SyntheticEvent<HTMLTextAreaElement>) => {
    updateCursorPosition(e.currentTarget);
  };

  // Toggle edit mode
  const toggleEditMode = () => {
    if (readOnly) return;
    const entering = !isEditMode;
    setIsEditMode(entering);
    if (entering) {
      setTimeout(() => {
        const ta = textareaRef.current;
        if (ta) {
          ta.focus();
          // Initialise cursor to start so position bar shows something meaningful
          updateCursorPosition(ta);
        }
      }, 50);
    }
  };

  // Sync line number gutter scroll with textarea scroll
  const handleTextareaScroll = (e: React.UIEvent<HTMLTextAreaElement>) => {
    if (lineNumberGutterRef.current) {
      lineNumberGutterRef.current.scrollTop = e.currentTarget.scrollTop;
    }
    if (foldGutterRef.current) {
      foldGutterRef.current.scrollTop = e.currentTarget.scrollTop;
    }
  };

  // Handle keyboard shortcuts and editor features
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (readOnly) return;

    // Ctrl+S or Cmd+S to save
    if ((e.ctrlKey || e.metaKey) && e.key === "s") {
      e.preventDefault();
      if (hasUnsavedChanges && onSaveContent) {
        handleSaveChanges();
      }
      return;
    }

    // Tab key for indentation
    if (e.key === "Tab") {
      e.preventDefault();
      const textarea = e.currentTarget;
      const start = textarea.selectionStart;
      const end = textarea.selectionEnd;
      const value = textarea.value;

      // Insert 4 spaces
      const newValue = value.substring(0, start) + "    " + value.substring(end);
      textarea.value = newValue;

      // Move cursor after the inserted spaces
      textarea.selectionStart = textarea.selectionEnd = start + 4;

      // Trigger change event
      handleContentEdit(newValue);
      return;
    }
  };

  const handleJumpToLine = () => {
    const lineNum = parseInt(jumpToLine);
    if (isNaN(lineNum) || lineNum < 1 || lineNum > totalLines) {
      return;
    }
    handleLineClick(lineNum - 1); // Convert to 0-indexed
    setJumpToLine("");
  };

  const handleJumpKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      handleJumpToLine();
    }
  };

  const handleCopyAll = async () => {
    try {
      await navigator.clipboard.writeText(content);
    } catch (err) {
      console.error("Failed to copy:", err);
    }
  };

  useEffect(() => {
    setDisplayedLines(MAX_LINES_INITIAL);
    clearSearch();
  }, [content, format]);

  useEffect(() => {
    // Only attach event listener when in view mode (editorRef is rendered)
    if (isEditMode) return;

    const codeElement = editorRef.current;
    if (!codeElement) {
      console.warn("[CodeHighlighter] editorRef.current is null, cannot attach click handler");
      return;
    }

    const handleMouseDown = (e: MouseEvent) => {
      const target = e.target as HTMLElement;

      // Allow DOI links to work - don't interfere with anchor tag clicks
      if (target.tagName === "A" || target.closest("a")) {
        console.log("[CodeHighlighter] DOI link clicked, allowing default behavior");
        return; // Allow the link to function normally
      }

      // Handle fold indicator or fold marker click
      if (target.classList.contains("fold-indicator") || target.classList.contains("fold-marker")) {
        const foldLineAttr = target.getAttribute("data-fold-line");
        if (foldLineAttr !== null) {
          e.preventDefault();
          e.stopPropagation();
          const foldLine = parseInt(foldLineAttr);
          toggleFold(foldLine);
          return;
        }
      }

      // Find the nearest element with a line index (handles clicks on nested syntax-highlighted spans)
      const lineElement = target.closest("[data-line-idx]") as HTMLElement | null;

      // Handle citation insertion mode click on line number
      if (citationInsertionMode && lineElement) {
        const lineIndexAttr = lineElement.getAttribute("data-line-idx");
        if (lineIndexAttr !== null) {
          e.preventDefault();
          const lineIndex = parseInt(lineIndexAttr);
          console.log("[CodeHighlighter] Citation insertion click detected at line index:", lineIndex);
          onInsertCitationAt?.(lineIndex);
          return;
        }
      }

      // Handle citation removal mode click on code content
      if (citationRemovalMode && lineElement) {
        const lineIndexAttr = lineElement.getAttribute("data-line-idx");
        if (lineIndexAttr !== null) {
          e.preventDefault();
          const lineIndex = parseInt(lineIndexAttr);
          // Check if the parent element has the citation-line class
          const parentDiv = lineElement.closest(".code-line");
          const isCitationLine = parentDiv?.getAttribute("data-is-citation") === "true";

          console.log("[CodeHighlighter] Citation removal click detected at line index:", lineIndex);
          console.log("[CodeHighlighter] Is citation line:", isCitationLine);

          // Call the removal handler - it will search for citation URI in nearby lines
          onRemoveCitationAt?.(lineIndex);
          return;
        }
      }
    };

    console.log("[CodeHighlighter] Attaching mousedown listener for citation mode");
    codeElement.addEventListener("mousedown", handleMouseDown);

    return () => {
      console.log("[CodeHighlighter] Removing mousedown listener");
      codeElement.removeEventListener("mousedown", handleMouseDown);
    };
  }, [citationInsertionMode, citationRemovalMode, onInsertCitationAt, onRemoveCitationAt, isEditMode, toggleFold]);

  const hasMore = displayedLines < totalLines;

  return (
    <div className="h-full flex flex-col" style={{ minWidth: 0, maxWidth: "100%", overflow: "hidden" }}>
      {/* Search Bar */}
      <div className="bg-gray-800 border-b border-gray-700 p-2 flex items-center gap-2">
        <div className="flex-1 relative">
          <Search className="absolute left-2 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                if (e.shiftKey) {
                  previousMatch();
                } else {
                  nextMatch();
                }
              }
            }}
            placeholder="Search in code..."
            className="w-full pl-8 pr-8 py-1.5 text-sm bg-gray-700 text-white border border-gray-600 rounded focus:outline-none focus:border-purple-500 code-search-input"
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
              {searchResults.length} {searchResults.length === 1 ? "match" : "matches"}
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
            caseSensitive ? "bg-purple-600 text-white" : "bg-gray-700 text-gray-300 hover:bg-gray-600"
          }`}
          title="Case sensitive"
        >
          Aa
        </button>

        <button
          onClick={() => setWordWrap(!wordWrap)}
          className={`p-1 rounded ${
            wordWrap ? "bg-purple-600 text-white" : "bg-gray-700 text-gray-300 hover:bg-gray-600"
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
          <button
            onClick={handleCopyAll}
            className="px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded"
            title="Copy entire code"
          >
            Copy All
          </button>
          <button
            onClick={handleDownload}
            className="px-2 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded flex items-center gap-1"
            title="Download ontology file"
          >
            <Download className="w-3 h-3" />
            Download
          </button>
        </div>

        {/* Editor Tools */}
        <div className="flex items-center gap-1 border-l border-gray-600 pl-2">
          {!readOnly && !citationInsertionMode && !citationRemovalMode && (
            <button
              onClick={toggleEditMode}
              className={`px-2 py-1 text-xs rounded flex items-center gap-1 ${
                isEditMode ? "bg-purple-600 hover:bg-purple-700 text-white" : "bg-gray-700 hover:bg-gray-600 text-white"
              }`}
              title={isEditMode ? "Switch to view mode" : "Switch to edit mode"}
            >
              <Edit2 className="w-3 h-3" />
              {isEditMode ? "View" : "Edit"}
            </button>
          )}
          {hasUnsavedChanges && onSaveContent && (
            <button
              onClick={handleSaveChanges}
              className="px-2 py-1 text-xs bg-green-600 hover:bg-green-700 text-white rounded flex items-center gap-1 animate-pulse"
              title="Save changes (Ctrl+S)"
            >
              <Save className="w-3 h-3" />
              Save
            </button>
          )}
        </div>
      </div>

      {/* Syntax Error Indicator Bar */}
      {syntaxError && (
        <div className="flex items-center gap-2 bg-red-950 border-b border-red-700 px-3 py-1.5">
          <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
          <span className="text-red-300 text-xs font-semibold flex-1 truncate">
            {errorLineNumbers.size > 0
              ? `${errorLineNumbers.size} syntax error${errorLineNumbers.size > 1 ? "s" : ""} — lines: ${(Array.from(errorLineNumbers) as number[]).sort((a, b) => a - b).join(", ")}`
              : "Syntax error — fix before saving"}
          </span>
          <button
            onClick={() => setShowErrorDialog(true)}
            className="flex items-center gap-1 text-xs text-red-300 hover:text-white bg-red-800 hover:bg-red-700 px-2 py-0.5 rounded transition-colors flex-shrink-0"
          >
            <span>Show Errors</span>
            <ArrowRight className="w-3 h-3" />
          </button>
        </div>
      )}

      {/* Search Results Panel */}
      {showSearchPanel && searchResults.length > 0 && (
        <div className="bg-gray-800 border-b border-gray-700 max-h-64 overflow-auto">
          <div className="p-2">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-semibold text-gray-300">
                {citationInsertionMode
                  ? "📍 Search Results - Click Line to Insert"
                  : citationRemovalMode
                    ? "🗑️ Search Results - Click Citation Line to Remove"
                    : "Search Results"}
              </span>
              <button onClick={() => setShowSearchPanel(false)} className="text-gray-400 hover:text-white">
                <X className="w-3 h-3" />
              </button>
            </div>
            <div className="space-y-1">
              {searchResults.map((lineIndex, idx) => {
                const lines = content.split(/\r?\n/);
                const lineContent = lines[lineIndex] || "";
                const preview = lineContent.length > 80 ? lineContent.substring(0, 80) + "..." : lineContent;
                const isCurrentMatch = idx === currentMatchIndex;

                return (
                  <div
                    key={lineIndex}
                    onClick={() => handleSearchResultClick(lineIndex)}
                    className={`px-2 py-1 text-xs rounded cursor-pointer ${
                      isCurrentMatch ? "bg-purple-600 text-white" : "bg-gray-700 text-gray-300 hover:bg-gray-600"
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-gray-400 font-mono" style={{ minWidth: "50px" }}>
                        Line {lineIndex + 1}
                      </span>
                      <span className="truncate font-mono" style={{ fontSize: "11px" }}>
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

      <div
        className="flex-1"
        style={{ minWidth: 0, overflow: "auto", maxWidth: "98vw", width: "100vw", position: "relative" }}
      >
        {isEditMode && !citationInsertionMode && !citationRemovalMode ? (
          // Edit mode: VS Code-style editor with line numbers and fold indicators
          <>
            <div className="flex h-full rounded-lg border-2 border-blue-500 overflow-hidden bg-[#1e1e1e]">
              {/* Line number gutter */}
              <div
                ref={lineNumberGutterRef}
                className="bg-[#1e1e1e] select-none overflow-hidden flex-shrink-0"
                style={{
                  width: "55px",
                  minWidth: "55px",
                  fontFamily: 'Consolas, "Courier New", monospace',
                  fontSize: "14px",
                  lineHeight: "1.6",
                  paddingTop: "16px",
                  paddingBottom: "40px",
                  color: "#858585",
                  textAlign: "right",
                  borderRight: "none",
                }}
              >
                {(() => {
                  const items: React.ReactNode[] = [];
                  const lines = currentContent.split("\n");
                  let skipUntil = -1;
                  for (let i = 0; i < lines.length; i++) {
                    if (i <= skipUntil) continue;
                    const isCollapsed = collapsedRanges.has(i);
                    if (isCollapsed) {
                      skipUntil = collapsedRanges.get(i)!;
                    }
                    const isErrLine = errorLineNumbers.has(i + 1);
                    items.push(
                      <div
                        key={i}
                        style={{
                          paddingRight: "4px",
                          minHeight: "22.4px",
                          lineHeight: "1.6",
                          color: isErrLine ? "#f87171" : undefined,
                          fontWeight: isErrLine ? "bold" : undefined,
                        }}
                        title={isErrLine ? "Syntax error on this line" : undefined}
                      >
                        {isErrLine ? "⚠" : ""}{i + 1}
                      </div>,
                    );
                  }
                  return items;
                })()}
              </div>
              {/* Fold indicator gutter */}
              <div
                ref={foldGutterRef}
                className="bg-[#1e1e1e] select-none overflow-hidden flex-shrink-0"
                style={{
                  width: "20px",
                  minWidth: "20px",
                  fontFamily: 'Consolas, "Courier New", monospace',
                  fontSize: "10px",
                  lineHeight: "1.6",
                  paddingTop: "16px",
                  paddingBottom: "40px",
                  color: "#858585",
                  textAlign: "center",
                  borderRight: "1px solid #333",
                }}
              >
                {(() => {
                  const items: React.ReactNode[] = [];
                  const lines = currentContent.split("\n");
                  let skipUntil = -1;
                  for (let i = 0; i < lines.length; i++) {
                    if (i <= skipUntil) continue;
                    const isFoldable = foldableRanges.has(i);
                    const isCollapsed = collapsedRanges.has(i);
                    if (isCollapsed) {
                      skipUntil = collapsedRanges.get(i)!;
                    }
                    items.push(
                      <div
                        key={i}
                        style={{
                          minHeight: "22.4px",
                          lineHeight: "22.4px",
                          cursor: isFoldable ? "pointer" : "default",
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "center",
                        }}
                        onClick={isFoldable ? () => toggleFold(i) : undefined}
                        title={isFoldable ? (isCollapsed ? "Unfold" : "Fold") : undefined}
                      >
                        {isFoldable ? (
                          <span
                            style={{
                              color: isCollapsed ? "#c5c5c5" : "#858585",
                              fontSize: "8px",
                              transition: "transform 0.15s ease",
                              display: "inline-block",
                              transform: isCollapsed ? "rotate(0deg)" : "rotate(90deg)",
                            }}
                          >
                            &#9654;
                          </span>
                        ) : null}
                      </div>,
                    );
                  }
                  return items;
                })()}
              </div>
          {/* Textarea editor */}
              <textarea
                ref={textareaRef}
                value={editDisplayContent}
                onChange={handleCodeInput}
                onKeyDown={handleKeyDown}
                onClick={handleCursorMove}
                onKeyUp={handleCursorMove}
                onSelect={handleCursorMove}
                onMouseUp={handleCursorMove}
                onScroll={handleTextareaScroll}
                onFocus={(e) => {
                  handleTextareaFocus();
                  updateCursorPosition(e.currentTarget);
                }}
                className="code-editor-textarea flex-1 bg-[#1e1e1e] text-white font-mono text-sm resize-none focus:outline-none"
                style={{
                  lineHeight: "1.6",
                  tabSize: 4,
                  fontFamily: 'Consolas, "Courier New", monospace',
                  fontSize: "14px",
                  letterSpacing: "0.5px",
                  caretColor: "#e879f9",   /* bright magenta — clearly visible on dark bg */
                  padding: "16px 16px 40px 12px",
                  border: "none",
                  height: "100%",
                  width: "100%",
                  overflow: "auto",
                }}
                spellCheck={false}
              />
            </div>
            {/* Cursor Position Display — sits outside overflow-hidden wrapper */}
            <div
              className="absolute bottom-2 right-2 bg-gray-900 border border-gray-600 rounded px-3 py-1 text-xs font-mono text-gray-300 shadow-lg pointer-events-none"
              style={{ zIndex: 10 }}
            >
              Ln {cursorPosition.line}, Col {cursorPosition.column}
            </div>
          </>
        ) : (
          // View mode: Standard line view
          <div
            ref={editorRef}
            className="bg-[#1e1e1e] p-4 rounded-lg text-sm font-mono h-full border border-gray-700 code-editor overflow-auto"
            style={{
              lineHeight: "1.6",
              tabSize: 4,
              fontFamily: 'Consolas, "Courier New", monospace',
              fontSize: "14px",
              letterSpacing: "0.5px",
            }}
          >
            {/* Standard line-by-line view */}
            <div
              style={{
                whiteSpace: wordWrap ? "pre-wrap" : "pre",
                wordBreak: wordWrap ? "break-word" : "normal",
              }}
              dangerouslySetInnerHTML={{ __html: highlightedContent }}
            />
          </div>
        )}
      </div>
      {hasMore && (
        <div className="p-2 bg-gray-800 border-t border-gray-700 text-center">
          <button
            onClick={loadMore}
            disabled={isProcessing}
            className="px-4 py-2 bg-purple-600 text-white text-sm rounded hover:bg-purple-700 disabled:opacity-50"
          >
            {isProcessing ? "Loading..." : `Load More (${displayedLines} / ${totalLines} lines)`}
          </button>
        </div>
      )}

      {/* Syntax Error Dialog */}
      {showErrorDialog && syntaxError && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ backgroundColor: "rgba(0,0,0,0.6)" }}
          onClick={(e) => { if (e.target === e.currentTarget) setShowErrorDialog(false); }}
        >
          <div className="bg-gray-900 border border-red-700 rounded-lg shadow-2xl w-full max-w-lg mx-4 flex flex-col max-h-[70vh]">
            {/* Dialog header */}
            <div className="flex items-center gap-2 px-4 py-3 border-b border-red-800 flex-shrink-0">
              <AlertTriangle className="w-4 h-4 text-red-400" />
              <span className="text-red-300 font-semibold text-sm flex-1">Syntax Errors</span>
              <button
                onClick={() => setShowErrorDialog(false)}
                className="text-gray-400 hover:text-white transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Raw error message */}
            <div className="px-4 py-3 bg-red-950 border-b border-red-900 flex-shrink-0">
              <pre className="text-red-200 text-xs whitespace-pre-wrap break-all font-mono leading-relaxed max-h-28 overflow-y-auto">
                {syntaxError}
              </pre>
            </div>

            {/* Error line list */}
            {errorLineNumbers.size > 0 && (
              <div className="flex-1 overflow-y-auto px-4 py-3">
                <p className="text-gray-400 text-xs mb-2">
                  Click a line to navigate there:
                </p>
                <div className="space-y-1">
                  {Array.from(errorLineNumbers)
                    .sort((a: number, b: number) => a - b)
                    .map((lineNo: number) => {
                      const lines = currentContent.split("\n");
                      const lineText = lines[lineNo - 1] ?? "";
                      const preview = lineText.trim().slice(0, 80) + (lineText.trim().length > 80 ? "…" : "");
                      return (
                        <button
                          key={lineNo}
                          onClick={() => navigateToLine(lineNo)}
                          className="w-full flex items-center gap-3 px-3 py-2 rounded bg-gray-800 hover:bg-red-900 hover:border-red-700 border border-gray-700 transition-colors text-left group"
                        >
                          <span className="text-red-400 font-mono text-xs font-bold w-14 flex-shrink-0">
                            Line {lineNo}
                          </span>
                          <span className="text-gray-300 font-mono text-xs truncate flex-1">
                            {preview || <span className="text-gray-500 italic">empty line</span>}
                          </span>
                          <ArrowRight className="w-3.5 h-3.5 text-gray-500 group-hover:text-red-300 flex-shrink-0" />
                        </button>
                      );
                    })}
                </div>
              </div>
            )}

            <div className="px-4 py-3 border-t border-gray-800 flex-shrink-0">
              <button
                onClick={() => setShowErrorDialog(false)}
                className="w-full py-1.5 text-sm text-gray-400 hover:text-white transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// Line-by-line highlighting functions (no background colors, only text colors)
function highlightTurtleLine(line: string): string {
  // Debug logging for DOI detection
  if (line.includes("bibo:doi") || line.includes("dc:identifier")) {
    console.log("🐢 highlightTurtleLine called with DOI line:", { line, escaped: escapeHtml(line) });
  }

  if (line.trimStart().startsWith("#")) {
    return `<span style="color:#6a9955">${escapeHtml(line)}</span>`;
  }
  if (!line.trim()) return escapeHtml(line);

  let escaped = escapeHtml(line);
  const MARKER = "\u0000";
  const replacements: string[] = [];
  let counter = 0;

  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  let result = escaped
    .replace(
      /(@prefix|@base)(\s+)/g,
      (_match, keyword, space) => `${store(`<span style="color:#c586c0">${keyword}</span>`)}${space}`,
    )
    .replace(/(&lt;[^&gt;]+&gt;)/g, (match) => store(`<span style="color:#4ec9b0">${match}</span>`))
    .replace(
      /("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g,
      (_match, str, lang) =>
        `${store(`<span style="color:#ce9178">${str}</span>`)}${store(`<span style="color:#4fc1ff">${lang}</span>`)}`,
    )
    // Special highlighting for DOI values - make them clickable hyperlinks
    .replace(/(bibo:doi\s+)&quot;(.+?)&quot;/gi, (_match, property, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in Turtle:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">bibo</span>`)}:${store(`<span style="color:#dcdcaa">doi</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI'),2000);" title="Click to open DOI: ${doiUrl}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier\s+)&quot;doi:(.+?)&quot;/gi, (_match, property, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in dc:identifier:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">dc</span>`)}:${store(`<span style="color:#dcdcaa">identifier</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI'),2000);" title="Click to open DOI: ${doiUrl}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/("(?:[^"\\]|\\.)*")/g, (match) => store(`<span style="color:#ce9178">${match}</span>`))
    .replace(/(\^\^)/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`))
    .replace(
      /\b([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)\b/g,
      (_match, prefix, name) =>
        `${store(`<span style="color:#9cdcfe">${prefix}</span>`)}:${store(`<span style="color:#dcdcaa">${name}</span>`)}`,
    )
    .replace(/\b(a|true|false)\b/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/([;.,\[\]()])/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`));

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightRDFXMLLine(line: string): string {
  let escaped = escapeHtml(line);
  const MARKER = "\u0000"; // Null character as marker
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
    // Special highlighting for DOI elements - handle tags with or without xmlns attributes
    .replace(/(&lt;bibo:doi(?:\s+[^&gt;]*)?&gt;)([^&lt;]+)(&lt;\/bibo:doi&gt;)/gi, (_match, open, doiValue, close) => {
      console.log("🔗 DOI PATTERN MATCHED in RDF/XML bibo:doi:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      // Extract tag parts for proper highlighting
      const openTagMatch = open.match(/(&lt;)(bibo)(:)(doi)(\s+[^&gt;]*)?(&gt;)/i);
      if (openTagMatch) {
        const openTag = `${store(`<span style="color:#808080">&lt;</span>`)}${store(`<span style="color:#569cd6">bibo</span>`)}:${store(`<span style="color:#4ec9b0">doi</span>`)}${openTagMatch[5] || ""}${store(`<span style="color:#808080">&gt;</span>`)}`;
        const closeTag = `${store(`<span style="color:#808080">&lt;/</span>`)}${store(`<span style="color:#569cd6">bibo</span>`)}:${store(`<span style="color:#4ec9b0">doi</span>`)}${store(`<span style="color:#808080">&gt;</span>`)}`;
        return `${openTag}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${closeTag}`;
      }
      return _match; // Fallback if parsing fails
    })
    // Special handling for dc:identifier with doi: prefix
    .replace(/(&lt;dc:identifier&gt;)doi:([^&lt;]+)(&lt;\/dc:identifier&gt;)/gi, (_match, open, doiValue, close) => {
      console.log("🔗 DOI PATTERN MATCHED in RDF/XML dc:identifier:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      const openTag = `${store(`<span style="color:#808080">&lt;</span>`)}${store(`<span style="color:#569cd6">dc</span>`)}:${store(`<span style="color:#4ec9b0">identifier</span>`)}${store(`<span style="color:#808080">&gt;</span>`)}`;
      const closeTag = `${store(`<span style="color:#808080">&lt;/</span>`)}${store(`<span style="color:#569cd6">dc</span>`)}:${store(`<span style="color:#4ec9b0">identifier</span>`)}${store(`<span style="color:#808080">&gt;</span>`)}`;
      return `${openTag}${store(`<span style="color:#ce9178">doi:</span>`)}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${closeTag}`;
    })
    .replace(/(bibo:doi)=(&quot;)([^&quot;]+)(&quot;)/gi, (_match, attr, openQuote, doiValue, closeQuote) => {
      console.log("🔗 DOI PATTERN MATCHED in RDF/XML bibo:doi attribute:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">${attr}</span>`)}=${openQuote}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${closeQuote}`;
    })
    .replace(
      /=(&quot;[^&quot;]*&quot;)/g,
      (_match, value) => `=${store(`<span style="color:#ce9178">${value}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g,
      (_match, open, ns, name) =>
        `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)([a-zA-Z_][\w-]*)/g,
      (_match, open, name) => `${open}${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(\s)([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g,
      (_match, space, attr) => `${space}${store(`<span style="color:#9cdcfe">${attr}</span>`)}=`,
    )
    .replace(/(\/?&gt;)/g, (match) => store(`<span style="color:#808080">${match}</span>`));

  // Restore all stored replacements
  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightOWLXMLLine(line: string): string {
  // Debug logging for DOI detection
  if (line.includes("bibo") || line.includes("doi") || line.includes("identifier") || line.includes("Literal")) {
    console.log("🦉📄 highlightOWLXMLLine called:", { line, escaped: escapeHtml(line) });
  }

  if (line.trimStart().startsWith("<!--")) {
    return `<span style="color:#6a9955">${escapeHtml(line)}</span>`;
  }
  if (!line.trim()) return escapeHtml(line);

  let escaped = escapeHtml(line);
  const MARKER = "\u0000";
  const replacements: string[] = [];
  let counter = 0;

  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  // Process DOI patterns in OWL/XML format
  // Pattern 1: <Literal>doi:value</Literal> or <Literal>10.xxxx/...</Literal>
  let result = escaped
    .replace(
      /(&lt;Literal(?:\s+[^&gt;]*)?)&gt;(doi:)?(\d+\.\d+\/[^&lt;]+)(&lt;\/Literal&gt;)/gi,
      (_match, openTag, doiPrefix, doiValue, closeTag) => {
        console.log("🔗 DOI PATTERN MATCHED in OWL/XML Literal:", { doiPrefix, doiValue, fullMatch: _match });
        const fullDoiValue = (doiPrefix || "") + doiValue;
        const doiUrl = fullDoiValue.startsWith("http") ? fullDoiValue : `https://doi.org/${doiValue}`;
        const displayValue = doiUrl; // Show full URL
        const open = `${openTag}${store(`<span style="color:#808080">&gt;</span>`)}`;
        const close = store(`<span style="color:#808080">${closeTag}</span>`);
        const prefix = doiPrefix ? store(`<span style="color:#ce9178">${doiPrefix}</span>`) : "";
        return `${open}${prefix}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${close}`;
      },
    )
    // Pattern 2: Highlight AnnotationProperty IRI with bibo/doi
    .replace(
      /(&lt;AnnotationProperty\s+IRI=&quot;[^&quot;]*(?:bibo\/doi|identifier)[^&quot;]*&quot;\s*\/&gt;)/gi,
      (match) => {
        console.log("🔗 DOI AnnotationProperty detected in OWL/XML:", { match });
        return store(`<span style="color:#4ec9b0;font-weight:bold">${match}</span>`);
      },
    )
    // Generic XML highlighting
    .replace(/(&lt;\?xml[^?]*\?&gt;)/g, (match) => store(`<span style="color:#569cd6">${match}</span>`))
    .replace(/(&lt;!--.*?--&gt;)/g, (match) => store(`<span style="color:#6a9955">${match}</span>`))
    .replace(
      /=(&quot;[^&quot;]*&quot;)/g,
      (_match, value) => `=${store(`<span style="color:#ce9178">${value}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)([a-zA-Z_][\w-]*)/g,
      (_match, open, name) =>
        `${store(`<span style="color:#808080">${open}</span>`)}${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(\s)([a-zA-Z_][\w-]*)=/g,
      (_match, space, attr) => `${space}${store(`<span style="color:#9cdcfe">${attr}</span>`)}=`,
    )
    .replace(/(\/?&gt;)/g, (match) => store(`<span style="color:#808080">${match}</span>`));

  // Restore all stored replacements
  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightNTriplesLine(line: string): string {
  // Debug logging for DOI detection
  if (line.includes("bibo") || line.includes("doi") || line.includes("identifier")) {
    console.log("📊 highlightNTriplesLine called:", { line, escaped: escapeHtml(line) });
  }

  if (line.trimStart().startsWith("#")) {
    return `<span style="color:#6a9955">${escapeHtml(line)}</span>`;
  }
  if (!line.trim()) return escapeHtml(line);

  let escaped = escapeHtml(line);
  const MARKER = "\u0000";
  const replacements: string[] = [];
  let counter = 0;

  const store = (replacement: string) => {
    const marker = `${MARKER}${counter}${MARKER}`;
    replacements[counter] = replacement;
    counter++;
    return marker;
  };

  // Process DOI predicates first, before generic colorizing
  let result = escaped
    // Handle Turtle-style prefixed names (which sometimes appear in N-Triples files)
    .replace(/(bibo:doi)\s+&quot;(.+?)&quot;/gi, (_match, predicate, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in N-Triples (Turtle style) bibo:doi:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier)\s+&quot;doi:(.+?)&quot;/gi, (_match, predicate, doiValue) => {
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;doi:${displayValue}&quot;</a>`)}`;
    })
    // Handle true N-Triples format with full URIs (complete triple: subject predicate object)
    .replace(/(&lt;.+?&gt;)\s+(&lt;.+?bibo\/doi&gt;)\s+&quot;(.+?)&quot;/g, (_match, subject, predicate, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in N-Triples (URI style) bibo:doi:", {
        subject,
        predicate,
        doiValue,
        fullMatch: _match,
      });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(
      /(&lt;.+?&gt;)\s+(&lt;.+?\/identifier&gt;)\s+&quot;doi:(.+?)&quot;/g,
      (_match, subject, predicate, doiValue) => {
        console.log("🔗 DOI PATTERN MATCHED in N-Triples (URI style) dc:identifier:", {
          subject,
          predicate,
          doiValue,
          fullMatch: _match,
        });
        // Handle both raw DOI and full URL
        const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
        const displayValue = doiUrl; // Show full URL
        return `${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;doi:${displayValue}&quot;</a>`)}`;
      },
    )
    .replace(/(&lt;[^&gt;]+&gt;)/g, (match) => store(`<span style="color:#4ec9b0">${match}</span>`))
    .replace(
      /("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g,
      (_match, str, lang) =>
        `${store(`<span style="color:#ce9178">${str}</span>`)}${store(`<span style="color:#4fc1ff">${lang}</span>`)}`,
    )
    .replace(/("(?:[^"\\]|\\.)*")/g, (match) => store(`<span style="color:#ce9178">${match}</span>`))
    .replace(/(\^\^)/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`))
    .replace(/(\s\.\s*$)/g, (match) => store(`<span style="color:#d4d4d4">${match}</span>`));

  // Restore all stored replacements
  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightOWLLine(line: string): string {
  // Debug logging for DOI detection
  if (
    line.includes("bibo") ||
    line.includes("doi") ||
    line.includes("identifier") ||
    line.includes("AnnotationAssertion")
  ) {
    console.log("🦉 highlightOWLLine called (Manchester/Functional):", { line, escaped: escapeHtml(line) });
  }

  let escaped = escapeHtml(line);
  const MARKER = "\u0000"; // Null character as marker
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
    // Functional syntax: AnnotationAssertion(<http://.../bibo/doi> <subject> "value")
    .replace(
      /(AnnotationAssertion\()(&lt;.+?bibo\/doi&gt;)\s+(&lt;.+?&gt;)\s+&quot;(.+?)&quot;\)/gi,
      (_match, funcOpen, predicate, subject, doiValue) => {
        console.log("🔗 DOI PATTERN MATCHED in Functional syntax bibo/doi:", {
          predicate,
          subject,
          doiValue,
          fullMatch: _match,
        });
        const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
        const displayValue = doiUrl; // Show full URL
        return `${store(`<span style="color:#dcdcaa">${funcOpen}</span>`)}${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}${store(`<span style="color:#dcdcaa">)</span>`)}`;
      },
    )
    // Functional syntax: AnnotationAssertion(<http://.../identifier> <subject> "doi:value")
    .replace(
      /(AnnotationAssertion\()(&lt;.+?\/identifier&gt;)\s+(&lt;.+?&gt;)\s+&quot;doi:(.+?)&quot;\)/gi,
      (_match, funcOpen, predicate, subject, doiValue) => {
        console.log("🔗 DOI PATTERN MATCHED in Functional syntax identifier:", {
          predicate,
          subject,
          doiValue,
          fullMatch: _match,
        });
        const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
        const displayValue = doiUrl; // Show full URL
        return `${store(`<span style="color:#dcdcaa">${funcOpen}</span>`)}${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<span style="color:#ce9178">&quot;doi:</span>`)}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${store(`<span style="color:#ce9178">&quot;</span>`)}${store(`<span style="color:#dcdcaa">)</span>`)}`;
      },
    )
    // Manchester/Functional syntax DOI handling (prefixed names)
    .replace(/(bibo:doi\s+)&quot;(.+?)&quot;/gi, (_match, property, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in Manchester/Functional bibo:doi:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">bibo</span>`)}:${store(`<span style="color:#dcdcaa">doi</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier\s+)&quot;doi:(.+?)&quot;/gi, (_match, property, doiValue) => {
      console.log("🔗 DOI PATTERN MATCHED in Manchester/Functional dc:identifier:", { doiValue, fullMatch: _match });
      // Handle both raw DOI and full URL
      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">dc</span>`)}:${store(`<span style="color:#dcdcaa">identifier</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;doi:${displayValue}&quot;</a>`)}`;
    })
    .replace(
      /=(&quot;[^&quot;]*&quot;)/g,
      (_match, value) => `=${store(`<span style="color:#ce9178">${value}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)((owl|rdf|rdfs|xsd|dc|dcterms):([a-zA-Z_][\w-]*))/g,
      (_match, open, _full, ns, name) =>
        `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g,
      (_match, open, ns, name) =>
        `${open}${store(`<span style="color:#569cd6">${ns}</span>`)}:${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(&lt;\/?)([a-zA-Z_][\w-]*)/g,
      (_match, open, name) => `${open}${store(`<span style="color:#4ec9b0">${name}</span>`)}`,
    )
    .replace(
      /(\s)([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g,
      (_match, space, attr) => `${space}${store(`<span style="color:#9cdcfe">${attr}</span>`)}=`,
    )
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
    if (line.trimStart().startsWith("#")) {
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
      .replace(
        /("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g,
        '<span style="color:#ce9178">$1</span><span style="color:#4fc1ff">$2</span>',
      )
      // Literals without language tags
      .replace(/("(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
      // Datatype indicators
      .replace(/(\^\^)/g, '<span style="color:#d4d4d4">$1</span>')
      // Prefixed names
      .replace(
        /\b([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)\b/g,
        '<span style="color:#9cdcfe">$1</span>:<span style="color:#dcdcaa">$2</span>',
      )
      // Keywords
      .replace(/\b(a|true|false)\b/g, '<span style="color:#569cd6">$1</span>')
      // Punctuation
      .replace(/([;.,\[\]()])/g, '<span style="color:#d4d4d4">$1</span>');

    result.push(escaped);
  }

  return result.join("\n");
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
      .replace(
        /(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g,
        '$1<span style="color:#569cd6">$2</span>:<span style="color:#4ec9b0">$3</span>',
      )
      // Opening/closing tags without namespace
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, '$1<span style="color:#4ec9b0">$2</span>')
      // Attribute names
      .replace(/\s([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, ' <span style="color:#9cdcfe">$1</span>=')
      // Tag closing
      .replace(/(\/?&gt;)/g, '<span style="color:#808080">$1</span>');

    result.push(escaped);
  }

  return result.join("\n");
}

function highlightNTriples(lines: string[]): string {
  const result: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Comments - fast path
    if (line.trimStart().startsWith("#")) {
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
      .replace(
        /("(?:[^"\\]|\\.)*")(@[a-z]{2}(?:-[A-Z]{2})?)\b/g,
        '<span style="color:#ce9178">$1</span><span style="color:#4fc1ff">$2</span>',
      )
      // Literals without language tags
      .replace(/("(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
      // Datatype indicators
      .replace(/(\^\^)/g, '<span style="color:#d4d4d4">$1</span>')
      // Triple terminator
      .replace(/(\s\.\s*$)/g, '<span style="color:#d4d4d4">$1</span>');

    result.push(escaped);
  }

  return result.join("\n");
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
      .replace(
        /(&lt;\/?)((owl|rdf|rdfs|xsd|dc|dcterms):([a-zA-Z_][\w-]*))/g,
        '$1<span style="color:#569cd6">$3</span>:<span style="color:#4ec9b0">$4</span>',
      )
      // Other namespaced elements
      .replace(
        /(&lt;\/?)([a-zA-Z_][\w-]*):([a-zA-Z_][\w-]*)/g,
        '$1<span style="color:#569cd6">$2</span>:<span style="color:#4ec9b0">$3</span>',
      )
      // Opening/closing tags without namespace
      .replace(/(&lt;\/?)([a-zA-Z_][\w-]*)/g, '$1<span style="color:#4ec9b0">$2</span>')
      // Attribute names (with or without namespace)
      .replace(/\s([a-zA-Z_][\w-]*(?::[a-zA-Z_][\w-]*)?)=/g, ' <span style="color:#9cdcfe">$1</span>=')
      // Tag closing
      .replace(/(\/?&gt;)/g, '<span style="color:#808080">$1</span>');

    result.push(escaped);
  }

  return result.join("\n");
}

// Fast HTML escaping using a map for better performance
const htmlEscapeMap: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (char) => htmlEscapeMap[char]);
}

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
