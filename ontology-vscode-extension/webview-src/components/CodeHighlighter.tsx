import React, { useMemo, useState, useEffect, useRef, useCallback, useImperativeHandle } from "react";
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

declare global {
  interface Window {
    vscode?: {
      postMessage: (message: any) => void;
    };
  }
}

interface CodeHighlighterProps {
  content: string;
  format: "turtle" | "rdfxml" | "ntriples" | "owlxml" | "manchester" | "functional" | "jsonld";
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

  canExport?: boolean;

  onExportProAction?: () => void;
}

export interface CodeHighlighterHandle {
  goToLine: (lineNumber: number) => void;
}

const MAX_LINES_INITIAL = 500; // Show first 500 lines initially
const CHUNK_SIZE = 200; // Process 200 lines at a time
const SEARCH_DEBOUNCE_MS = 400; // Debounce search input

const GUTTER_LINE_HEIGHT = 22.4; // 14px font-size * 1.6 line-height
const GUTTER_PADDING_TOP = 16; // matches the textarea's own padding-top
const GUTTER_OVERSCAN_ROWS = 30; // rendered above/below the viewport so fast scrolls don't flash blank
const FOLD_RECOMPUTE_DEBOUNCE_MS = 300; // defer the O(n) bracket-matching scan while the user is actively typing
const CONTEXT_LINES = 500; // Lines to show above and below selection
const MAX_SEARCH_LINES = 10000; // Limit search to prevent hanging on huge files
const SEARCH_CHUNK_SIZE = 100; // Process 100 lines per chunk for search
const SEARCH_CHUNK_DELAY = 8; // 8ms delay between search chunks

export const CodeHighlighter = React.forwardRef<CodeHighlighterHandle, CodeHighlighterProps>(({
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
  canExport = true,
  onExportProAction,
}, forwardedRef) => {
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

  const [editScrollTop, setEditScrollTop] = useState(0);
  const [editViewportHeight, setEditViewportHeight] = useState(0);
  const scrollRafRef = useRef<number | null>(null);

  const errorLineNumbers = useMemo(() => new Set(parseErrorLines(syntaxError || "")), [syntaxError]);

  const navigateToLine = useCallback((lineNumber: number) => {
    if (lineNumber < 1) return;
    const zeroIdx = lineNumber - 1;

    if (isEditMode && textareaRef.current) {
      const lines = currentContent.split("\n");

      if (lineNumber > displayedLines) setDisplayedLines(lineNumber + 50);
      const offset = lines.slice(0, zeroIdx).reduce((acc, l) => acc + l.length + 1, 0);
      const ta = textareaRef.current;
      ta.focus();
      ta.setSelectionRange(offset, offset + (lines[zeroIdx]?.length ?? 0));

      ta.scrollTop = Math.max(0, zeroIdx * GUTTER_LINE_HEIGHT - ta.clientHeight / 2);

      setEditScrollTop(ta.scrollTop);
    } else {

      handleLineClick(zeroIdx);
    }
    setShowErrorDialog(false);
  }, [isEditMode, currentContent, displayedLines]);

  useImperativeHandle(forwardedRef, () => ({
    goToLine: navigateToLine,
  }), [navigateToLine]);

  const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const searchCancelRef = useRef<boolean>(false);

  const totalLines = useMemo(() => {
    return content ? content.split(/\r?\n/).length : 0;
  }, [content]);

  useEffect(() => {
    setCurrentContent(content);
  }, [content]);

  const handleContentEdit = (newContent: string) => {
    if (readOnly || !onContentChange) return;

    setCurrentContent(newContent);
    setHasUnsavedChanges(true);

    onContentChange(newContent);
  };

  const handleSaveChanges = () => {

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
    if (!canExport) {
      onExportProAction?.();
      return;
    }

    const extensionMap: Record<typeof format, string> = {
      turtle: "ttl",
      rdfxml: "rdf",
      ntriples: "nt",
      owlxml: "owl",
      manchester: "omn",
      functional: "ofn",
      jsonld: "jsonld",
    };

    const extension = extensionMap[format] || "txt";
    const filename = `ontology_${new Date().toISOString().replace(/[:.]/g, "-")}.${extension}`;

    if (window.vscode) {
      window.vscode.postMessage({
        type: "downloadFile",
        content: currentContent,
        filename: filename,
        format: format,
      });
    } else {

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
      } catch (error) {
        console.error("[CodeHighlighter] Download failed:", error);
        alert("Download failed: " + (error instanceof Error ? error.message : "Unknown error"));
      }
    }
  };

  const handleAddDoi = () => {

    if (readOnly) {
      console.warn("[CodeHighlighter] Cannot add DOI - component is readOnly");
      return;
    }

    if (!onContentChange) {
      console.warn("[CodeHighlighter] Cannot add DOI - onContentChange callback not provided");
      return;
    }

    setShowAddDoiDialog(true);
  };

  const handleAddDoiConfirm = () => {

    if (!doiInputValue.trim()) {
      console.warn("[CodeHighlighter] DOI input is empty");
      return;
    }

    const norm = normalizeDoiUtil(doiInputValue);
    if (!isValidDoiFormat(norm)) {
      console.warn("[CodeHighlighter] DOI appears malformed:", doiInputValue);
      setDoiInputError('DOI looks malformed');

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

    const lines = currentContent.split(/\r?\n/);

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

    lines.push("", doiLine);
    const newContent = lines.join("\n");

    handleContentEdit(newContent);

    setShowAddDoiDialog(false);
    setDoiInputValue("");
  };

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

      let processed = 0;

      const processChunk = () => {

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

          searchTimeout = setTimeout(() => {
            animationFrameId = requestAnimationFrame(processChunk);
          }, SEARCH_CHUNK_DELAY);
        } else {

          setSearchResults(matches);
          setCurrentMatchIndex(0);
          setIsSearching(false);
          setSearchProgress(100);
          setShowSearchPanel(matches.length > 0);

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

  useEffect(() => {
    if (searchResults.length > 0 && editorRef.current) {
      const lineNumber = searchResults[currentMatchIndex];

      if (lineNumber >= displayedLines) {
        setDisplayedLines(Math.min(lineNumber + 50, totalLines));
      }

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

  useEffect(() => {
    if (citationInsertionMode || citationRemovalMode) {
      setShowSearchPanel(true);

      if (isEditMode) {
        setIsEditMode(false);
      }
    }
  }, [citationInsertionMode, citationRemovalMode, isEditMode]);

  const extractDOI = (line: string): string | null => {
    if (!line) return null;

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

  const detectCitationLine = (line: string, format: string): boolean => {
    if (!line) return false;

    if (/urn:citation:/i.test(line)) {
      return true;
    }

    if (/Zotero Citation/i.test(line) || /###\s*Zotero Citation/i.test(line) || /<!--\s*Zotero Citation/i.test(line)) {
      return true;
    }

    if (
      /<urn:citation:[^>]+>/i.test(line) ||
      /IRI="urn:citation:/i.test(line) ||
      /rdf:about="urn:citation:/i.test(line)
    ) {
      return true;
    }

    return false;
  };

  const editLineCount = useMemo(() => {
    return currentContent ? currentContent.split("\n").length : 1;
  }, [currentContent]);

  const [debouncedFoldContent, setDebouncedFoldContent] = useState(currentContent);
  const foldDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (currentContent.length <= 500_000) {
      setDebouncedFoldContent(currentContent);
      return;
    }
    if (foldDebounceRef.current) clearTimeout(foldDebounceRef.current);
    foldDebounceRef.current = setTimeout(() => {
      setDebouncedFoldContent(currentContent);
    }, FOLD_RECOMPUTE_DEBOUNCE_MS);
    return () => {
      if (foldDebounceRef.current) clearTimeout(foldDebounceRef.current);
    };
  }, [currentContent]);

  const foldableRanges = useMemo(() => {
    const lines = debouncedFoldContent.split("\n");
    const ranges: Map<number, number> = new Map(); // startLine -> endLine (0-indexed)

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
  }, [debouncedFoldContent, format]);

  useEffect(() => {
    setCollapsedRanges(new Map());
  }, [content]);

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

  const handleTextareaFocus = useCallback(() => {
    if (collapsedRanges.size > 0) {
      setCollapsedRanges(new Map());
    }
  }, [collapsedRanges]);

  const visualRowToLineIndex = useMemo(() => {
    if (collapsedRanges.size === 0) return null;
    const lines = debouncedFoldContent.split("\n");
    const mapping: number[] = [];
    let skipUntil = -1;
    for (let i = 0; i < lines.length; i++) {
      if (i <= skipUntil) continue;
      mapping.push(i);
      const foldEnd = collapsedRanges.get(i);
      if (foldEnd !== undefined) skipUntil = foldEnd;
    }
    return mapping;
  }, [debouncedFoldContent, collapsedRanges]);

  const editTotalVisualRows = visualRowToLineIndex ? visualRowToLineIndex.length : editLineCount;

  const { gutterStartRow, gutterEndRow } = useMemo(() => {
    const viewportPx = editViewportHeight || 400;
    const visibleRowCount = Math.ceil(viewportPx / GUTTER_LINE_HEIGHT);
    const firstRow = Math.max(0, Math.floor(editScrollTop / GUTTER_LINE_HEIGHT) - GUTTER_OVERSCAN_ROWS);
    const lastRow = Math.min(editTotalVisualRows - 1, firstRow + visibleRowCount + GUTTER_OVERSCAN_ROWS * 2);
    return { gutterStartRow: firstRow, gutterEndRow: Math.max(firstRow, lastRow) };
  }, [editScrollTop, editViewportHeight, editTotalVisualRows]);

  const lineNumberGutterItems = useMemo(() => {
    const items: React.ReactNode[] = [];
    for (let row = gutterStartRow; row <= gutterEndRow; row++) {
      const lineIdx = visualRowToLineIndex ? visualRowToLineIndex[row] : row;
      if (lineIdx === undefined) break;
      const isErrLine = errorLineNumbers.has(lineIdx + 1);
      items.push(
        <div
          key={lineIdx}
          style={{
            position: "absolute",
            top: `${GUTTER_PADDING_TOP + row * GUTTER_LINE_HEIGHT - editScrollTop}px`,
            left: 0,
            right: 0,
            paddingRight: "4px",
            height: `${GUTTER_LINE_HEIGHT}px`,
            lineHeight: "1.6",
            color: isErrLine ? "#f87171" : undefined,
            fontWeight: isErrLine ? "bold" : undefined,
          }}
          title={isErrLine ? "Syntax error on this line" : undefined}
        >
          {isErrLine ? "⚠" : ""}{lineIdx + 1}
        </div>,
      );
    }
    return items;
  }, [gutterStartRow, gutterEndRow, visualRowToLineIndex, errorLineNumbers, editScrollTop]);

  const foldGutterItems = useMemo(() => {
    const items: React.ReactNode[] = [];
    for (let row = gutterStartRow; row <= gutterEndRow; row++) {
      const lineIdx = visualRowToLineIndex ? visualRowToLineIndex[row] : row;
      if (lineIdx === undefined) break;
      const isFoldable = foldableRanges.has(lineIdx);
      const isCollapsed = collapsedRanges.has(lineIdx);
      items.push(
        <div
          key={lineIdx}
          style={{
            position: "absolute",
            top: `${GUTTER_PADDING_TOP + row * GUTTER_LINE_HEIGHT - editScrollTop}px`,
            left: 0,
            right: 0,
            height: `${GUTTER_LINE_HEIGHT}px`,
            cursor: isFoldable ? "pointer" : "default",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
          onClick={isFoldable ? () => toggleFold(lineIdx) : undefined}
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
  }, [gutterStartRow, gutterEndRow, visualRowToLineIndex, foldableRanges, collapsedRanges, toggleFold, editScrollTop]);

  const skipHighlighting = isEditMode && !citationInsertionMode && !citationRemovalMode;

  const highlightedContent = useMemo(() => {
    if (!content || skipHighlighting) return "";

    const lines = content.split(/\r?\n/);
    const linesToShow = lines.slice(0, displayedLines);

    const citationBlockLines = new Set<number>();
    if (citationRemovalMode) {
      const isXmlFmt = format === "rdfxml" || format === "owlxml";
      const isTurtleFmt = format === "turtle" || format === "ntriples";
      const isManchesterFmt = format === "manchester";
      const isFunctionalFmt = format === "functional";

      for (let i = 0; i < linesToShow.length; i++) {
        const line = linesToShow[i];
        const citationUriMatch = line.match(/urn:citation:([a-zA-Z0-9]+)/i);

        if (citationUriMatch) {
          const citationId = citationUriMatch[1];

          let blockStart = i;
          for (let k = i - 1; k >= Math.max(0, i - 15); k--) {
            const prev = linesToShow[k].trim();
            if (prev.includes("Zotero Citation") || prev.startsWith("###") || prev.startsWith("<!--")) {
              blockStart = k;

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

          for (let k = blockStart; k <= blockEnd; k++) {
            citationBlockLines.add(k);
          }
        }
      }
    }

    const numberedLines: string[] = [];

    const doiLines = linesToShow.filter((l) => l.includes("bibo:doi") || l.includes("dc:identifier"));
    if (doiLines.length > 0) {
    }

    let skipUntilLine = -1;

    for (let index = 0; index < linesToShow.length; index++) {

      if (index <= skipUntilLine) continue;

      const line = linesToShow[index];
      const lineNumber = index + 1;

      const isFoldable = foldableRanges.has(index);
      const isCollapsed = collapsedRanges.has(index);
      const foldEndLine = isCollapsed ? collapsedRanges.get(index)! : isFoldable ? foldableRanges.get(index)! : -1;

      if (isCollapsed) {
        skipUntilLine = foldEndLine;
      }

      let processedLine = "";

      if (!line.trim()) {
        processedLine = "&nbsp;";
      } else {

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

        if (debouncedSearchQuery && debouncedSearchQuery.length >= 1) {
          const query = escapeRegex(debouncedSearchQuery);
          const flags = caseSensitive ? "g" : "gi";
          const regex = new RegExp(query, flags);

          const parts = processedLine.split(/(<[^>]+>)/g);

          processedLine = parts
            .map((part) => {

              if (part.startsWith("<") && part.endsWith(">")) {
                return part;
              }

              return part.replace(regex, (match) => {
                return `<mark style="background-color:#f59e0b;color:#000;padding:0 2px;border-radius:2px">${match}</mark>`;
              });
            })
            .join("");
        }
      }

      if (isCollapsed) {
        const foldedCount = foldEndLine - index;
        processedLine += `<span style="color:#569cd6;background:#264f78;padding:1px 6px;border-radius:3px;margin-left:8px;font-size:11px;cursor:pointer" class="fold-marker" data-fold-line="${index}"> \u22EF ${foldedCount} lines </span>`;
      }

      const isCitationLine = citationRemovalMode ? citationBlockLines.has(index) : detectCitationLine(line, format);

      const doi = extractDOI(line);
      const hasDOI = doi !== null;

      let lineStyle = "";

      if (citationRemovalMode && isCitationLine) {
        lineStyle = "background-color:#7f1d1d"; // Dark red for citation lines
      }

      if (hasDOI && !citationRemovalMode) {
        lineStyle = "background-color:#1a3a2a"; // Dark green tint for DOI lines
      }

      const isErrorLine = errorLineNumbers.has(lineNumber);
      const lineNumberColor =
        isErrorLine ? "#f87171" :
        citationRemovalMode && isCitationLine ? "#ef4444" :
        hasDOI ? "#10b981" : "#a1a1aa";
      const lineNumberWeight = hasDOI || isErrorLine ? "bold" : "600";
      const lineNumberSize = "13px";

      const citationModeCursor = citationInsertionMode || citationRemovalMode ? "cursor:pointer;" : "";
      const citationModeHoverStyle =
        citationInsertionMode || citationRemovalMode ? ";transition:background-color 0.2s" : "";

      let lineNumberTitle = `Line ${lineNumber}`;
      if (citationInsertionMode) {
        lineNumberTitle = "Click to insert citation here";
      } else if (citationRemovalMode && isCitationLine) {
        lineNumberTitle = "Click to remove this citation";
      } else if (hasDOI) {
        lineNumberTitle = `DOI: ${doi}`;
      }

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
    skipHighlighting,
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

    const startLine = Math.max(0, lineIndex - CONTEXT_LINES);
    const endLine = Math.min(totalLines, lineIndex + CONTEXT_LINES + 1);

    if (endLine > displayedLines) {
      setDisplayedLines(endLine);
    }

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

  const handleCodeInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    if (readOnly || !onContentChange) return;

    const newContent = e.target.value;
    handleContentEdit(newContent);
    updateCursorPosition(e.target);
  };

  const updateCursorPosition = (textarea: HTMLTextAreaElement) => {
    const text = textarea.value;
    const cursorPos = textarea.selectionStart;

    const textBeforeCursor = text.substring(0, cursorPos);
    const lines = textBeforeCursor.split("\n");
    const line = lines.length;
    const column = lines[lines.length - 1].length + 1;

    setCursorPosition({ line, column });
  };

  const handleCursorMove = (e: React.SyntheticEvent<HTMLTextAreaElement>) => {
    updateCursorPosition(e.currentTarget);
  };

  const toggleEditMode = () => {
    if (readOnly) return;
    const entering = !isEditMode;
    setIsEditMode(entering);
    if (entering) {
      setTimeout(() => {
        const ta = textareaRef.current;
        if (ta) {
          ta.focus();

          updateCursorPosition(ta);
        }
      }, 50);
    }
  };

  const handleTextareaScroll = (e: React.UIEvent<HTMLTextAreaElement>) => {
    const scrollTop = e.currentTarget.scrollTop;
    if (scrollRafRef.current !== null) cancelAnimationFrame(scrollRafRef.current);
    scrollRafRef.current = requestAnimationFrame(() => {
      scrollRafRef.current = null;
      setEditScrollTop(scrollTop);
    });
  };

  useEffect(() => {
    const ta = textareaRef.current;
    if (!isEditMode || !ta) return;
    const updateHeight = () => setEditViewportHeight(ta.clientHeight);
    updateHeight();
    const ro = new ResizeObserver(updateHeight);
    ro.observe(ta);
    return () => ro.disconnect();
  }, [isEditMode]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (readOnly) return;

    if ((e.ctrlKey || e.metaKey) && e.key === "s") {
      e.preventDefault();
      if (hasUnsavedChanges && onSaveContent) {
        handleSaveChanges();
      }
      return;
    }

    if (e.key === "Tab") {
      e.preventDefault();
      const textarea = e.currentTarget;
      const start = textarea.selectionStart;
      const end = textarea.selectionEnd;
      const value = textarea.value;

      const newValue = value.substring(0, start) + "    " + value.substring(end);
      textarea.value = newValue;

      textarea.selectionStart = textarea.selectionEnd = start + 4;

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
    if (!canExport) {
      onExportProAction?.();
      return;
    }
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

    if (isEditMode) return;

    const codeElement = editorRef.current;
    if (!codeElement) {
      console.warn("[CodeHighlighter] editorRef.current is null, cannot attach click handler");
      return;
    }

    const handleMouseDown = (e: MouseEvent) => {
      const target = e.target as HTMLElement;

      if (target.tagName === "A" || target.closest("a")) {
        return; // Allow the link to function normally
      }

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

      const lineElement = target.closest("[data-line-idx]") as HTMLElement | null;

      if (citationInsertionMode && lineElement) {
        const lineIndexAttr = lineElement.getAttribute("data-line-idx");
        if (lineIndexAttr !== null) {
          e.preventDefault();
          const lineIndex = parseInt(lineIndexAttr);
          onInsertCitationAt?.(lineIndex);
          return;
        }
      }

      if (citationRemovalMode && lineElement) {
        const lineIndexAttr = lineElement.getAttribute("data-line-idx");
        if (lineIndexAttr !== null) {
          e.preventDefault();
          const lineIndex = parseInt(lineIndexAttr);

          const parentDiv = lineElement.closest(".code-line");
          const isCitationLine = parentDiv?.getAttribute("data-is-citation") === "true";

          onRemoveCitationAt?.(lineIndex);
          return;
        }
      }
    };

    codeElement.addEventListener("mousedown", handleMouseDown);

    return () => {
      codeElement.removeEventListener("mousedown", handleMouseDown);
    };
  }, [citationInsertionMode, citationRemovalMode, onInsertCitationAt, onRemoveCitationAt, isEditMode, toggleFold]);

  const hasMore = displayedLines < totalLines;

  return (
    <div className="h-full flex flex-col" style={{ minWidth: 0, maxWidth: "100%", overflow: "hidden" }}>
      {}
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
            className={`px-2 py-1 text-xs rounded ${
              canExport
                ? "bg-gray-700 hover:bg-gray-600 text-white"
                : "bg-gray-800 text-gray-400 cursor-not-allowed opacity-70"
            }`}
            title={canExport ? "Copy entire code" : "Available on Professional and Enterprise plans"}
            aria-disabled={!canExport}
          >
            Copy All
            {!canExport && <span className="ml-1 text-[10px] uppercase tracking-wider">Pro</span>}
          </button>
          <button
            onClick={handleDownload}
            className={`px-2 py-1 text-xs rounded flex items-center gap-1 ${
              canExport
                ? "bg-gray-700 hover:bg-gray-600 text-white"
                : "bg-gray-800 text-gray-400 cursor-not-allowed opacity-70"
            }`}
            title={canExport ? "Download ontology file" : "Available on Professional and Enterprise plans"}
            aria-disabled={!canExport}
          >
            <Download className="w-3 h-3" />
            Download
            {!canExport && <span className="ml-1 text-[10px] uppercase tracking-wider">Pro</span>}
          </button>
        </div>

        {}
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

      {}
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

      {}
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

          <>
            <div className="flex h-full rounded-lg border-2 border-blue-500 overflow-hidden bg-[#1e1e1e]">
              {}
              <div
                ref={lineNumberGutterRef}
                className="bg-[#1e1e1e] select-none overflow-hidden flex-shrink-0"
                style={{
                  width: "55px",
                  minWidth: "55px",
                  fontFamily: 'Consolas, "Courier New", monospace',
                  fontSize: "14px",
                  lineHeight: "1.6",
                  color: "#858585",
                  textAlign: "right",
                  borderRight: "none",
                  position: "relative",
                }}
              >
                {lineNumberGutterItems}
              </div>
              {}
              <div
                ref={foldGutterRef}
                className="bg-[#1e1e1e] select-none overflow-hidden flex-shrink-0"
                style={{
                  width: "20px",
                  minWidth: "20px",
                  fontFamily: 'Consolas, "Courier New", monospace',
                  fontSize: "10px",
                  lineHeight: "1.6",
                  color: "#858585",
                  textAlign: "center",
                  borderRight: "1px solid #333",
                  position: "relative",
                }}
              >
                {foldGutterItems}
              </div>
          {}
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

                wrap="off"
                style={{
                  whiteSpace: "pre",
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
            {}
            <div
              className="absolute bottom-2 right-2 bg-gray-900 border border-gray-600 rounded px-3 py-1 text-xs font-mono text-gray-300 shadow-lg pointer-events-none"
              style={{ zIndex: 10 }}
            >
              Ln {cursorPosition.line}, Col {cursorPosition.column}
            </div>
          </>
        ) : (

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
            {}
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

      {}
      {showErrorDialog && syntaxError && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ backgroundColor: "rgba(0,0,0,0.6)" }}
          onClick={(e) => { if (e.target === e.currentTarget && e.button === 0) setShowErrorDialog(false); }}
        >
          <div className="bg-gray-900 border border-red-700 rounded-lg shadow-2xl w-full max-w-lg mx-4 flex flex-col max-h-[70vh]">
            {}
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

            {}
            <div className="px-4 py-3 bg-red-950 border-b border-red-900 flex-shrink-0">
              <pre className="text-red-200 text-xs whitespace-pre-wrap break-all font-mono leading-relaxed max-h-28 overflow-y-auto">
                {syntaxError}
              </pre>
            </div>

            {}
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
});

CodeHighlighter.displayName = "CodeHighlighter";

function highlightTurtleLine(line: string): string {

  if (line.includes("bibo:doi") || line.includes("dc:identifier")) {
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

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">bibo</span>`)}:${store(`<span style="color:#dcdcaa">doi</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI'),2000);" title="Click to open DOI: ${doiUrl}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier\s+)&quot;doi:(.+?)&quot;/gi, (_match, property, doiValue) => {

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

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL

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

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      const openTag = `${store(`<span style="color:#808080">&lt;</span>`)}${store(`<span style="color:#569cd6">dc</span>`)}:${store(`<span style="color:#4ec9b0">identifier</span>`)}${store(`<span style="color:#808080">&gt;</span>`)}`;
      const closeTag = `${store(`<span style="color:#808080">&lt;/</span>`)}${store(`<span style="color:#569cd6">dc</span>`)}:${store(`<span style="color:#4ec9b0">identifier</span>`)}${store(`<span style="color:#808080">&gt;</span>`)}`;
      return `${openTag}${store(`<span style="color:#ce9178">doi:</span>`)}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${closeTag}`;
    })
    .replace(/(bibo:doi)=(&quot;)([^&quot;]+)(&quot;)/gi, (_match, attr, openQuote, doiValue, closeQuote) => {

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

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightOWLXMLLine(line: string): string {

  if (line.includes("bibo") || line.includes("doi") || line.includes("identifier") || line.includes("Literal")) {
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

  let result = escaped
    .replace(
      /(&lt;Literal(?:\s+[^&gt;]*)?)&gt;(doi:)?(\d+\.\d+\/[^&lt;]+)(&lt;\/Literal&gt;)/gi,
      (_match, openTag, doiPrefix, doiValue, closeTag) => {
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

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightNTriplesLine(line: string): string {

  if (line.includes("bibo") || line.includes("doi") || line.includes("identifier")) {
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
    // Handle Turtle-style prefixed names (which sometimes appear in N-Triples files)
    .replace(/(bibo:doi)\s+&quot;(.+?)&quot;/gi, (_match, predicate, doiValue) => {

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier)\s+&quot;doi:(.+?)&quot;/gi, (_match, predicate, doiValue) => {

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;doi:${displayValue}&quot;</a>`)}`;
    })
    // Handle true N-Triples format with full URIs (complete triple: subject predicate object)
    .replace(/(&lt;.+?&gt;)\s+(&lt;.+?bibo\/doi&gt;)\s+&quot;(.+?)&quot;/g, (_match, subject, predicate, doiValue) => {

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(
      /(&lt;.+?&gt;)\s+(&lt;.+?\/identifier&gt;)\s+&quot;doi:(.+?)&quot;/g,
      (_match, subject, predicate, doiValue) => {

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

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightOWLLine(line: string): string {

  if (
    line.includes("bibo") ||
    line.includes("doi") ||
    line.includes("identifier") ||
    line.includes("AnnotationAssertion")
  ) {
  }

  let escaped = escapeHtml(line);
  const MARKER = "\u0000"; // Null character as marker
  const replacements: string[] = [];
  let counter = 0;

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
        const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
        const displayValue = doiUrl; // Show full URL
        return `${store(`<span style="color:#dcdcaa">${funcOpen}</span>`)}${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}${store(`<span style="color:#dcdcaa">)</span>`)}`;
      },
    )
    // Functional syntax: AnnotationAssertion(<http://.../identifier> <subject> "doi:value")
    .replace(
      /(AnnotationAssertion\()(&lt;.+?\/identifier&gt;)\s+(&lt;.+?&gt;)\s+&quot;doi:(.+?)&quot;\)/gi,
      (_match, funcOpen, predicate, subject, doiValue) => {
        const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue}`;
        const displayValue = doiUrl; // Show full URL
        return `${store(`<span style="color:#dcdcaa">${funcOpen}</span>`)}${store(`<span style="color:#4ec9b0">${predicate}</span>`)} ${store(`<span style="color:#4ec9b0">${subject}</span>`)} ${store(`<span style="color:#ce9178">&quot;doi:</span>`)}${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">${displayValue}</a>`)}${store(`<span style="color:#ce9178">&quot;</span>`)}${store(`<span style="color:#dcdcaa">)</span>`)}`;
      },
    )
    // Manchester/Functional syntax DOI handling (prefixed names)
    .replace(/(bibo:doi\s+)&quot;(.+?)&quot;/gi, (_match, property, doiValue) => {

      const doiUrl = doiValue.startsWith("http") ? doiValue : `https://doi.org/${doiValue.replace(/^doi:/, "")}`;
      const displayValue = doiUrl; // Show full URL
      return `${store(`<span style="color:#9cdcfe">bibo</span>`)}:${store(`<span style="color:#dcdcaa">doi</span>`)} ${store(`<a href="${doiUrl}" target="_blank" rel="noopener noreferrer" style="color:#00d4ff !important;background-color:rgba(0,212,255,0.15);padding:2px 6px;border-radius:3px;text-decoration:underline !important;cursor:pointer !important;font-weight:700;border:1px solid rgba(0,212,255,0.3);pointer-events:auto;user-select:text" onmouseover="this.style.backgroundColor='rgba(0,212,255,0.25)';this.style.borderColor='rgba(0,212,255,0.5)'" onmouseout="this.style.backgroundColor='rgba(0,212,255,0.15)';this.style.borderColor='rgba(0,212,255,0.3)'" oncontextmenu="event.preventDefault();navigator.clipboard.writeText('${doiUrl}');this.setAttribute('title','Link copied!');setTimeout(()=>this.setAttribute('title','Click to open DOI: ${displayValue}'),2000);" title="Click to open DOI: ${displayValue}">&quot;${displayValue}&quot;</a>`)}`;
    })
    .replace(/(dc:identifier\s+)&quot;doi:(.+?)&quot;/gi, (_match, property, doiValue) => {

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

  for (let i = 0; i < counter; i++) {
    result = result.replace(`${MARKER}${i}${MARKER}`, replacements[i]);
  }

  return result;
}

function highlightTurtle(lines: string[]): string {
  const result: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (line.trimStart().startsWith("#")) {
      result.push(`<span style="color:#6a9955">${escapeHtml(line)}</span>`);
      continue;
    }

    if (!line.trim()) {
      result.push(line);
      continue;
    }

    let escaped = escapeHtml(line);

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

    if (line.trimStart().startsWith("#")) {
      result.push(`<span style="color:#6a9955">${escapeHtml(line)}</span>`);
      continue;
    }

    if (!line.trim()) {
      result.push(line);
      continue;
    }

    let escaped = escapeHtml(line);

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
