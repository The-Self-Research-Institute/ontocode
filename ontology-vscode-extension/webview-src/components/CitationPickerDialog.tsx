// CitationPickerDialog.tsx
import React, { useRef, useState, useEffect, useLayoutEffect, useCallback } from 'react';
import { normalizeDoi as normalizeDoiUtil, isValidDoiFormat } from "../utils/doi";
import {
  X,
  Search,
  BookOpen,
  User,
  Calendar,
  ExternalLink,
  Plus,
  ChevronDown,
  ChevronRight,
  AlertCircle,
  Settings,
  Loader2,
} from "lucide-react";
import { TreeNode } from "@/types";
import ZoteroSettingsDialog from "./ZoteroSettingsDialog";
import { loadCitationLibraryCache, saveCitationLibraryCache } from "../services/citationLibraryCache";

function mergeFreshFirstPage<T extends { key: string }>(networkPage: T[], priorFull: T[]): T[] {
  const netKeys = new Set(networkPage.map((x) => x.key));
  return [...networkPage, ...priorFull.filter((c) => !netKeys.has(c.key))];
}

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
  onSelectCitation: (citation: CitationItem | "manual") => void;
  format: "turtle" | "rdfxml";
}

const CitationPickerDialog: React.FC<CitationPickerDialogProps> = ({ isOpen, onClose, onSelectCitation, format }) => {
  const [citations, setCitations] = useState<CitationItem[]>([]);
  const [filteredCitations, setFilteredCitations] = useState<CitationItem[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDoiPrompt, setShowDoiPrompt] = useState(false);
  const [selectedCitation, setSelectedCitation] = useState<CitationItem | null>(null);
  const [manualDoi, setManualDoi] = useState("");
  const [manualDoiError, setManualDoiError] = useState<string | null>(null);
  const [showZoteroSettings, setShowZoteroSettings] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [showDoiWarning, setShowDoiWarning] = useState(false);
  /** Server-reported library size when available */
  const [totalEstimated, setTotalEstimated] = useState<number | null>(null);
  const [openedFromDisk, setOpenedFromDisk] = useState(false);

  const listRef = useRef<HTMLDivElement | null>(null);
  const requestingMoreRef = useRef(false);
  const citationsRef = useRef<CitationItem[]>([]);
  const saveCacheTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Shared with first-page handler — always await same promise so merge order is deterministic */
  const cacheLoadPromiseRef = useRef<Promise<Awaited<ReturnType<typeof loadCitationLibraryCache>>> | null>(null);
  /** After opening the modal, first library request fires immediately; later query changes debounce */
  const skipNextLibraryFetchDebounceRef = useRef(true);
  /** Ignore paging events from older extension sessions after a newer `requestZoteroLibrary` */
  const maxFirstPageSessionRef = useRef(0);
  const activePagingSessionRef = useRef<number | null>(null);
  const sessionBrowseModeRef = useRef<Map<number, "full" | "search">>(new Map());
  /** Browse mode applied to the next issued request (`full` vs Zotero `q=` scope) — snapshotted onto the session id in the first response */
  const pendingBrowseModeRef = useRef<"full" | "search">("full");
  const totalEstimatedRef = useRef<number | null>(null);
  const loadingRef = useRef(false);
  const hasMoreRef = useRef(false);

  useEffect(() => {
    citationsRef.current = citations;
  }, [citations]);

  useEffect(() => {
    loadingRef.current = loading;
  }, [loading]);

  useEffect(() => {
    totalEstimatedRef.current = totalEstimated;
  }, [totalEstimated]);

  useEffect(() => {
    hasMoreRef.current = hasMore;
  }, [hasMore]);

  useEffect(() => {
    try {
      if (searchQuery !== undefined) sessionStorage.setItem("citationPicker.searchQuery", searchQuery);
    } catch {
      /* ignore */
    }
  }, [searchQuery]);

  useEffect(() => {
    return () => {
      try {
        sessionStorage.setItem("citationPicker.searchQuery", searchQuery || "");
      } catch {
        /* ignore */
      }
    };
  }, [searchQuery]);

  useEffect(() => {
    const handle = window.setTimeout(() => setDebouncedSearch(searchQuery), 300);
    return () => window.clearTimeout(handle);
  }, [searchQuery]);

  useEffect(() => {
    if (debouncedSearch.trim() === "") {
      setFilteredCitations(citations);
    } else {
      const query = debouncedSearch.toLowerCase();
      const filtered = citations.filter((citation) => {
        const title = citation.data?.title?.toLowerCase() || "";
        const authors =
          citation.data?.creators?.map((c) => `${c.firstName} ${c.lastName}`.toLowerCase()).join(" ") || "";
        const year = citation.data?.date || "";
        const doiStr = citation.data?.doi?.toLowerCase() || "";
        return (
          title.includes(query) || authors.includes(query) || year.includes(query) || doiStr.includes(query)
        );
      });
      setFilteredCitations(filtered);
    }
  }, [debouncedSearch, citations]);

  const requestMoreFire = useCallback(() => {
    if (!window.vscode) return;
    if (requestingMoreRef.current) return;
    if (!hasMoreRef.current) return;
    requestingMoreRef.current = true;
    setLoadingMore(true);
    window.vscode.postMessage({ type: "requestZoteroLibraryMore" });
  }, []);

  /** Gentle background paging so results become complete (full library or all Zotero `q=` hits) */
  useEffect(() => {
    if (!isOpen || !hasMore || loadingMore) return;
    const stagger = searchQuery.trim() ? 70 : openedFromDisk ? 160 : 100;
    const t = window.setTimeout(() => {
      if (!requestingMoreRef.current) requestMoreFire();
    }, stagger);
    return () => window.clearTimeout(t);
  }, [isOpen, hasMore, loadingMore, citations.length, openedFromDisk, searchQuery, requestMoreFire]);

  const scheduleSaveCacheToDisk = useCallback(() => {
    const list = citationsRef.current;
    if (!list.length) return;
    if (saveCacheTimerRef.current) window.clearTimeout(saveCacheTimerRef.current);
    saveCacheTimerRef.current = window.setTimeout(() => {
      const snapshot = citationsRef.current;
      const te = totalEstimatedRef.current;
      const total =
        typeof te === "number" && Number.isFinite(te) && te > 0 ? te : Math.max(snapshot.length, 1);
      void saveCitationLibraryCache({
        items: snapshot.slice(),
        totalResults: Math.max(total, snapshot.length),
        updatedAt: Date.now(),
      });
      saveCacheTimerRef.current = null;
    }, 600);
  }, []);

  const reloadZotero = useCallback(() => {
    if (!window.vscode) return;
    setError(null);
    setLoadingMore(false);
    setHasMore(false);
    requestingMoreRef.current = false;
    cacheLoadPromiseRef.current = loadCitationLibraryCache();
    pendingBrowseModeRef.current = searchQuery.trim() ? "search" : "full";
    setLoading(true);
    setOpenedFromDisk(false);
    const q = searchQuery.trim();
    window.vscode.postMessage({ type: "requestZoteroLibrary", searchQuery: q || undefined });
  }, [searchQuery]);

  useEffect(() => {
    if (!isOpen) {
      skipNextLibraryFetchDebounceRef.current = true;
    }
  }, [isOpen]);

  /** Restore search text before passive effects — avoids an extra stale full-library fetch on open */
  useLayoutEffect(() => {
    if (!isOpen) return;
    try {
      const stored = sessionStorage.getItem("citationPicker.searchQuery") || "";
      if (stored !== searchQuery) setSearchQuery(stored);
    } catch {
      /* ignore */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reopen only: compare against current `searchQuery` without subscribing to every keystroke
  }, [isOpen]);

  /** Refetch whenever the typed query changes: Zotero `q=` (search) vs full-library paging */
  useEffect(() => {
    if (!isOpen || !window.vscode) return;

    const ms = skipNextLibraryFetchDebounceRef.current
      ? (() => {
          skipNextLibraryFetchDebounceRef.current = false;
          return 0;
        })()
      : 420;

    const t = window.setTimeout(() => {
      const qTrim = searchQuery.trim();
      pendingBrowseModeRef.current = qTrim ? "search" : "full";
      requestingMoreRef.current = false;
      setLoadingMore(false);
      window.vscode!.postMessage({ type: "requestZoteroLibrary", searchQuery: qTrim || undefined });
    }, ms);

    return () => window.clearTimeout(t);
  }, [isOpen, searchQuery]);

  useEffect(() => {
    if (!isOpen) return;

    maxFirstPageSessionRef.current = 0;
    activePagingSessionRef.current = null;
    sessionBrowseModeRef.current.clear();

    cacheLoadPromiseRef.current = loadCitationLibraryCache();

    let firstResponseDone = false;
    const staleTimeout = window.setTimeout(() => {
      if (!firstResponseDone && loadingRef.current) {
        setError("Request timed out. Please try again.");
        setLoading(false);
      }
    }, 15000);

    const messageHandler = (event: MessageEvent) => {
      const message = event.data;

      if (message.type === "zoteroLibraryData") {
        const sidRaw = typeof message.librarySessionId === "number" ? message.librarySessionId : null;
        if (sidRaw !== null && sidRaw < maxFirstPageSessionRef.current) return;

        firstResponseDone = true;
        const items = message.items || [];
        const fromApiTotal =
          typeof message.totalResults === "number" && message.totalResults > 0
            ? Math.floor(message.totalResults)
            : undefined;

        void (async () => {
          if (sidRaw !== null && sidRaw < maxFirstPageSessionRef.current) return;

          const cached = cacheLoadPromiseRef.current ? await cacheLoadPromiseRef.current : null;
          const priorCached = cached?.items && Array.isArray(cached.items) ? (cached.items as CitationItem[]) : [];
          const browseMode = pendingBrowseModeRef.current;

          if (sidRaw !== null) {
            sessionBrowseModeRef.current.set(sidRaw, browseMode);
            maxFirstPageSessionRef.current = Math.max(maxFirstPageSessionRef.current, sidRaw);
            activePagingSessionRef.current = sidRaw;
          }

          const merged =
            browseMode === "search"
              ? (items as CitationItem[])
              : priorCached.length > 0
                ? (mergeFreshFirstPage(items as CitationItem[], priorCached) as CitationItem[])
                : (items as CitationItem[]);

          let nextTotal =
            browseMode === "search"
              ? Math.max(fromApiTotal ?? merged.length, merged.length)
              : Math.max(fromApiTotal ?? 0, cached?.totalResults ?? 0, merged.length);

          setTotalEstimated(Number.isFinite(nextTotal) ? nextTotal : merged.length);

          setCitations(merged);
          setLoading(false);
          setHasMore(!!message.hasMore);
          setLoadingMore(false);
          requestingMoreRef.current = false;

          setOpenedFromDisk(browseMode !== "search" && priorCached.length > 0);
        })();

        window.clearTimeout(staleTimeout);
      } else if (message.type === "zoteroLibraryDataAppend") {
        const sidRaw = typeof message.librarySessionId === "number" ? message.librarySessionId : null;
        if (sidRaw !== null && sidRaw !== activePagingSessionRef.current) return;

        firstResponseDone = true;
        const items = message.items || [];
        const browseMode =
          sidRaw !== null ? sessionBrowseModeRef.current.get(sidRaw) ?? "full" : "full";

        if (typeof message.totalResults === "number" && message.totalResults > 0) {
          setTotalEstimated(Math.floor(message.totalResults));
        }

        setCitations((prev) => {
          const seen = new Set(prev.map((c) => c.key));
          const added = items.filter((c: CitationItem) => !seen.has(c.key));
          return [...prev, ...added];
        });
        setHasMore(!!message.hasMore);
        setLoading(false);
        setLoadingMore(false);
        requestingMoreRef.current = false;

        if (browseMode === "full") scheduleSaveCacheToDisk();
      } else if (message.type === "zoteroLibraryDataComplete") {
        const sidC = typeof message.librarySessionId === "number" ? message.librarySessionId : null;
        if (sidC !== null && sidC !== activePagingSessionRef.current) return;

        setHasMore(false);
        setLoadingMore(false);
        requestingMoreRef.current = false;

        const browseMode =
          sidC !== null ? sessionBrowseModeRef.current.get(sidC) ?? "full" : "full";

        if (browseMode === "full") {
          queueMicrotask(() => {
            const list = citationsRef.current;
            const te = totalEstimatedRef.current;
            void saveCitationLibraryCache({
              items: list.slice(),
              totalResults: Math.max(
                list.length,
                typeof te === "number" && te > 0 ? te : list.length
              ),
              updatedAt: Date.now(),
            });
          });
        }
        if (sidC !== null) sessionBrowseModeRef.current.delete(sidC);
      } else if (message.type === "zoteroLibraryError") {
        const sidE = typeof message.librarySessionId === "number" ? message.librarySessionId : null;
        if (sidE !== null && sidE < maxFirstPageSessionRef.current) return;

        firstResponseDone = true;
        setError(message.error || "Failed to load Zotero library");
        setLoading(false);
        setHasMore(false);
        setLoadingMore(false);
        requestingMoreRef.current = false;
        window.clearTimeout(staleTimeout);
      }
    };

    window.addEventListener("message", messageHandler);

    void (async () => {
      const cached = cacheLoadPromiseRef.current ? await cacheLoadPromiseRef.current : null;
      if (!cached?.items?.length) {
        setCitations([]);
        setLoading(true);
        setOpenedFromDisk(false);
      } else {
        const arr = cached.items as CitationItem[];
        const tr = cached.totalResults > 0 ? cached.totalResults : arr.length;
        setCitations(arr);
        setTotalEstimated(tr);
        setLoading(false);
        setOpenedFromDisk(true);
        setHasMore(tr > arr.length);
      }
    })();

    return () => {
      window.clearTimeout(staleTimeout);
      window.removeEventListener("message", messageHandler);
      requestingMoreRef.current = false;
    };
  }, [isOpen, scheduleSaveCacheToDisk]);

  const onListScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    // Trigger when within ~2 screens of the bottom for smooth paging.
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    if (distanceToBottom < el.clientHeight * 2) {
      requestMoreFire();
    }
  }, [requestMoreFire]);

  const extractYear = (dateStr: string): string => {
    if (!dateStr) return "";
    const match = dateStr.match(/\d{4}/);
    return match ? match[0] : "";
  };

  const normalizeDoiUrl = (doi: string): string => {
    if (!doi) return "";
    // If DOI already has http:// or https://, use it as-is
    if (doi.startsWith("http://") || doi.startsWith("https://")) {
      return doi;
    }
    // Otherwise, prepend https://doi.org/
    return `https://doi.org/${doi}`;
  };

  // Try to extract a normalized DOI from several citation fields (doi, url, extra),
  // with a regex fallback for values embedded in longer strings.
  const extractDoiFromCitation = (citation: CitationItem): string => {
    const candidates = [
      citation.data?.doi,
      citation.data?.url,
      // Zotero sometimes stores metadata in the `extra` field like 'DOI: 10.1234/xyz'
      // so include it as a candidate.
      // @ts-ignore
      citation.data?.extra,
    ].filter(Boolean) as string[];

    const DOI_EXTRACT_RE = /10\.\d{4,9}\/[\w.\-;()\/:@,]+/i;

    for (const cand of candidates) {
      const norm = normalizeDoiUtil(cand);
      if (norm && isValidDoiFormat(norm)) return norm;

      const m = String(cand).match(DOI_EXTRACT_RE);
      if (m && m[0]) {
        const norm2 = normalizeDoiUtil(m[0]);
        if (isValidDoiFormat(norm2)) return norm2;
      }
    }

    return "";
  };

  const handleSelectCitation = (citation: CitationItem) => {
    // Check if DOI is present and looks valid after normalization
    const norm = extractDoiFromCitation(citation);
    if (!norm || !isValidDoiFormat(norm)) {
      // Show prompt to add DOI when missing or malformed
      setSelectedCitation(citation);
      setShowDoiWarning(true);
      setShowDoiPrompt(true);
      return;
    }

    // DOI exists and appears valid — normalize stored value and proceed
    const updatedCitation = {
      ...citation,
      data: {
        ...citation.data,
        doi: norm,
      },
    };
    onSelectCitation(updatedCitation);
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
      const norm = normalizeDoiUtil(manualDoi.trim());
      // If malformed, keep showing error (should be prevented by UI check)
      if (!isValidDoiFormat(norm)) return;
      // Add normalized DOI to citation data
      const updatedCitation = {
        ...selectedCitation,
        data: {
          ...selectedCitation.data,
          doi: norm,
        },
      };
      onSelectCitation(updatedCitation);
      setShowDoiPrompt(false);
      setShowDoiWarning(false);
      setSelectedCitation(null);
      setManualDoi("");
      setManualDoiError(null);
      onClose();
    }
  };

  const handleManualEntry = () => {
    onSelectCitation("manual");
  };

  /** True while more pages are expected or a page request is in flight — list/search are not complete yet */
  const librarySyncPending = !error && (hasMore || loadingMore);
  const hasTypedSearch = searchQuery.trim().length > 0;

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-4xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex items-center gap-2 flex-wrap">
            <BookOpen className="text-purple-600" size={24} />
            <h2 className="text-xl font-bold text-gray-800">Insert Citation</h2>
            {librarySyncPending && (
              <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 text-amber-900 text-xs font-medium px-2.5 py-1 border border-amber-200/80">
                <Loader2 size={12} className={loadingMore ? "animate-spin" : ""} />
                {hasTypedSearch ? "Searching Zotero" : "Syncing library"}
              </span>
            )}
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
              onClick={() => {
                onClose();
                setSearchQuery("");
              }}
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
              placeholder="Search (local + Zotero). Empty = browse whole library."
              className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              autoFocus
            />
          </div>
          {debouncedSearch.trim() && (
            <div className="mt-2 space-y-1">
              <p className="text-xs text-gray-600">
                Found {filteredCitations.length} {filteredCitations.length === 1 ? "match" : "matches"} for &quot;
                {debouncedSearch.trim()}
                &quot;
                {librarySyncPending &&
                  (hasTypedSearch ? (
                    <span className="text-amber-800">
                      {" "}
                      (Zotero is still paging through every item that matches this query)
                    </span>
                  ) : (
                    <span className="text-amber-800">
                      {" "}
                      (only among items downloaded so far — more may appear when syncing finishes)
                    </span>
                  ))}
              </p>
            </div>
          )}
        </div>

        {librarySyncPending && (
          <div
            className="px-4 py-3 flex items-start gap-3 border-b border-amber-100 bg-amber-50/90"
            role="status"
            aria-live="polite"
          >
            <Loader2
              size={20}
              className={`mt-0.5 flex-shrink-0 text-amber-700 ${loadingMore ? "animate-spin" : "opacity-80"}`}
              aria-hidden
            />
            <div className="min-w-0 text-sm leading-snug">
              <p className="font-semibold text-amber-950">
                {hasTypedSearch
                  ? "Still working — loading every Zotero hit for your search"
                  : "Still working — your full Zotero library is not loaded yet"}
              </p>
              <p className="text-xs text-amber-900/90 mt-1">
                {hasTypedSearch ? (
                  <>
                    Zotero receives this text as a remote quick search. We then page through every matching item and apply
                    your local filters (authors, year, DOI, etc.).{" "}
                    <span className="font-medium tabular-nums">{citations.length}</span>
                    {typeof totalEstimated === "number" && totalEstimated > citations.length
                      ? (
                        <>
                          {" "}
                          loaded of about <span className="font-medium tabular-nums">{totalEstimated}</span>
                        </>
                      )
                      : null}
                    .
                  </>
                ) : (
                  <>
                    {loadingMore
                      ? "Fetching the next batch from Zotero right now."
                      : "Waiting to fetch the next batch (this happens in the background)."}
                    {" "}
                    You can keep browsing; the list shows what is downloaded so far (
                    <span className="font-medium tabular-nums">{citations.length}</span>
                    {typeof totalEstimated === "number" && totalEstimated > citations.length
                      ? (
                        <>
                          {" "}
                          of about <span className="font-medium tabular-nums">{totalEstimated}</span>
                        </>
                      )
                      : null}
                    ).
                  </>
                )}
              </p>
            </div>
          </div>
        )}

        {/* Citation List */}
        <div
          ref={listRef}
          onScroll={onListScroll}
          className="flex-1 overflow-y-auto p-4"
        >
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
                <p className="text-red-600">
                  {error === "ZOTERO_NOT_CONFIGURED" ? "Zotero is not configured yet." : error}
                </p>
                {error === "ZOTERO_NOT_CONFIGURED" ? (
                  <button
                    onClick={() => setShowZoteroSettings(true)}
                    className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 inline-flex items-center gap-2"
                  >
                    <Settings size={16} /> Configure Zotero
                  </button>
                ) : (
                  <button
                    onClick={reloadZotero}
                    className="mt-4 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                  >
                    Retry
                  </button>
                )}
              </div>
            </div>
          )}

          {!loading && !error && filteredCitations.length === 0 && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <BookOpen className="mx-auto text-gray-400 mb-3" size={48} />
                <p className="text-gray-600 font-medium">
                  {searchQuery ? "No citations found matching your search" : "No citations available"}
                </p>
                {searchQuery && (
                  <p className="text-sm text-gray-500 mt-2">
                    Try different keywords
                    {librarySyncPending && (
                      <span className="block text-amber-800 mt-1.5">
                        Your library is still syncing — matches can show up as more items finish loading.
                      </span>
                    )}
                  </p>
                )}
                {!searchQuery && (
                  <p className="text-sm text-gray-500 mt-2">
                    Make sure Zotero is running and the extension is connected
                    {librarySyncPending && (
                      <span className="block text-amber-800 mt-1.5">Items are still downloading from Zotero.</span>
                    )}
                  </p>
                )}
              </div>
            </div>
          )}

          {!loading && !error && filteredCitations.length > 0 && (
            <div className="space-y-3">
              {filteredCitations.map((citation) => {
                const authors =
                  citation?.data?.creators?.map((c) => `${c.firstName} ${c.lastName}`.trim()).join(", ") ||
                  "Unknown author";
                const year = extractYear(citation.data.date);
                return (
                  <div
                    key={citation.key}
                    onClick={() => handleSelectCitation(citation)}
                    className="p-4 border border-gray-200 rounded-lg hover:border-purple-500 hover:shadow-md cursor-pointer transition-all bg-white hover:bg-purple-50"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-gray-800 mb-2 line-clamp-2">{citation.data.title}</h3>
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
                          <p className="text-xs text-gray-500 italic line-clamp-1">{citation.data.publicationTitle}</p>
                        )}
                        {(() => {
                          const normDoi = extractDoiFromCitation(citation);
                          const doiValid = !!normDoi && isValidDoiFormat(normDoi);
                          if (doiValid) {
                            return (
                              <a
                                href={normalizeDoiUrl(normDoi)}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 mt-1 hover:underline"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <ExternalLink size={12} />
                                <span className="truncate">DOI: {normDoi}</span>
                              </a>
                            );
                          }
                          return (
                            <div className="flex items-center gap-1 text-xs text-yellow-600 mt-1">
                              <AlertCircle size={12} />
                              <span>No DOI - will prompt to add</span>
                            </div>
                          );
                        })()}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Bottom loader (visible at end of list) */}
          {!loading && !error && (loadingMore || hasMore) && (
            <div className="flex items-center justify-center py-6">
              <div className="flex items-center gap-2 text-sm text-gray-600">
                <Loader2 size={18} className={loadingMore ? "animate-spin" : ""} />
                <span>
                  {loadingMore
                    ? "Adding more citations from Zotero…"
                    : "Scroll toward the bottom to load another batch"}
                </span>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 bg-gray-50">
          {librarySyncPending && (
            <p className="text-xs text-amber-900 font-medium mb-2 flex items-center gap-2">
              <Loader2 size={14} className={loadingMore ? "animate-spin text-amber-700" : "text-amber-600"} />
              {hasTypedSearch
                ? "Zotero search in progress — not every matching item is downloaded yet."
                : "Library sync in progress — results are not complete until this finishes."}
            </p>
          )}
          <div className="flex items-center justify-between text-xs text-gray-600 mb-2">
            <span className="font-medium">
              {filteredCitations.length} {filteredCitations.length === 1 ? "citation" : "citations"}
              {!searchQuery && citations.length > 0 && ` of ${citations.length}`}
              {librarySyncPending && citations.length > 0 && (
                <span className="text-amber-800 font-normal">
                  {hasTypedSearch ? " (partial matches — paging)" : " (partial list)"}
                </span>
              )}
            </span>
            <span className="text-gray-500 font-medium">Format: {format.toUpperCase()}</span>
          </div>
          {filteredCitations.length > 0 && (
            <p className="text-xs text-gray-600 text-center pt-2 border-t border-gray-300">
              <span className="font-medium">Click a citation</span> to select and insert it
            </p>
          )}
        </div>
      </div>

      {/* DOI Prompt Dialog */}
      {showDoiPrompt && selectedCitation && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-70"
          onClick={() => setShowDoiPrompt(false)}
        >
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
                <p className="text-sm text-gray-600">Would you like to add a DOI manually or proceed without it?</p>
              </div>
            </div>

            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">Add DOI (optional):</label>
              <input
                type="text"
                value={manualDoi}
                onChange={(e) => {
                  const v = e.target.value;
                  setManualDoi(v);
                  const norm = normalizeDoiUtil(v);
                  if (v.trim() && !isValidDoiFormat(norm)) {
                    setManualDoiError("DOI looks malformed");
                  } else {
                    setManualDoiError(null);
                  }
                }}
                onKeyPress={(e) => {
                  if (e.key === "Enter" && manualDoi.trim() && !manualDoiError) {
                    handleAddDoiAndConfirm();
                  }
                }}
                placeholder="10.1234/example"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
              {manualDoiError && <div className="text-sm text-red-600 mt-1">{manualDoiError}</div>}
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
                  disabled={!!manualDoiError}
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
            reloadZotero();
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
