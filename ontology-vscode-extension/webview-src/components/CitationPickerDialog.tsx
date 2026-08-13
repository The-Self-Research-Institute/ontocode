
import React, { useRef, useState, useEffect, useLayoutEffect, useCallback, useMemo } from 'react';
import {
  normalizeDoi as normalizeDoiUtil,
  isValidDoiFormat,
  extractDoiFromZoteroData,
  toDoiUrl,
} from "../utils/doi";
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
  CheckCircle2,
  ShieldAlert,
  Settings,
  Loader2,
} from "lucide-react";
import { TreeNode } from "@/types";
import ZoteroSettingsDialog from "./ZoteroSettingsDialog";
import { loadCitationLibraryCache, saveCitationLibraryCache } from "../services/citationLibraryCache";
import {
  validateDoiOnline,
  type DoiValidationResult,
} from "../services/doiValidationService";

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

    DOI?: string;

    doi?: string;

    extra?: string;
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

type DoiCheckState =
  | { status: "idle" }
  | { status: "checking" }
  | { status: "ok"; result: DoiValidationResult }
  | { status: "warn"; result: DoiValidationResult }
  | { status: "fail"; result: DoiValidationResult };

interface CitationPickerDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectCitation: (citation: CitationItem | "manual") => void;
  format: "turtle" | "rdfxml" | "jsonld";
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
  const [manualDoiCheck, setManualDoiCheck] = useState<DoiCheckState>({ status: "idle" });
  const [showZoteroSettings, setShowZoteroSettings] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [showDoiWarning, setShowDoiWarning] = useState(false);

  const [totalEstimated, setTotalEstimated] = useState<number | null>(null);
  const [openedFromDisk, setOpenedFromDisk] = useState(false);

  const listRef = useRef<HTMLDivElement | null>(null);
  const requestingMoreRef = useRef(false);
  const citationsRef = useRef<CitationItem[]>([]);
  const saveCacheTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const cacheLoadPromiseRef = useRef<Promise<Awaited<ReturnType<typeof loadCitationLibraryCache>>> | null>(null);

  const skipNextLibraryFetchDebounceRef = useRef(true);

  const maxFirstPageSessionRef = useRef(0);
  const activePagingSessionRef = useRef<number | null>(null);
  const sessionBrowseModeRef = useRef<Map<number, "full" | "search">>(new Map());

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
    if (saveCacheTimerRef.current) clearTimeout(saveCacheTimerRef.current);
    saveCacheTimerRef.current = setTimeout(() => {
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
        setError(message.error || "Failed to load citation library");
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

    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    if (distanceToBottom < el.clientHeight * 2) {
      requestMoreFire();
    }
  }, [requestMoreFire]);

  const extractYear = useCallback((dateStr: string): string => {
    if (!dateStr) return "";
    const match = dateStr.match(/\d{4}/);
    return match ? match[0] : "";
  }, []);

  const extractDoiFromCitation = useCallback(
    (citation: CitationItem): string => extractDoiFromZoteroData(citation.data),
    []
  );

  const buildDoiHref = (doi: string): string => toDoiUrl(doi) || "#";

  const handleSelectCitation = async (citation: CitationItem) => {
    const norm = extractDoiFromCitation(citation);

    if (!norm) {

      setSelectedCitation(citation);
      setShowDoiWarning(true);
      setShowDoiPrompt(true);
      setManualDoiCheck({ status: "idle" });
      return;
    }

    setSelectedCitation(citation);
    setManualDoi(norm);
    setManualDoiError(null);
    setManualDoiCheck({ status: "checking" });

    const result = await validateDoiOnline({
      doi: norm,
      title: citation.data?.title,
      publicationTitle: citation.data?.publicationTitle,
      year: extractYear(citation.data?.date || ""),
    });

    if (result.valid && result.relevant) {

      const finalDoi = result.normalizedDoi || norm;
      onSelectCitation({
        ...citation,
        data: { ...citation.data, DOI: finalDoi, doi: finalDoi },
      });
      setManualDoiCheck({ status: "idle" });
      setSelectedCitation(null);
      onClose();
      return;
    }

    setManualDoiCheck({
      status: result.valid ? "warn" : "fail",
      result,
    });
    setShowDoiWarning(true);
    setShowDoiPrompt(true);
  };

  const closeDoiPrompt = () => {
    setShowDoiPrompt(false);
    setShowDoiWarning(false);
    setSelectedCitation(null);
    setManualDoi("");
    setManualDoiError(null);
    setManualDoiCheck({ status: "idle" });
  };

  const handleConfirmWithoutDoi = () => {
    if (!selectedCitation) return;
    onSelectCitation(selectedCitation);
    closeDoiPrompt();
    onClose();
  };

  const handleAddDoiAndConfirm = async () => {
    if (!selectedCitation) return;
    const norm = normalizeDoiUtil(manualDoi.trim());
    if (!isValidDoiFormat(norm)) {
      setManualDoiError("DOI looks malformed");
      return;
    }

    setManualDoiCheck({ status: "checking" });
    const result = await validateDoiOnline({
      doi: norm,
      title: selectedCitation.data?.title,
      publicationTitle: selectedCitation.data?.publicationTitle,
      year: extractYear(selectedCitation.data?.date || ""),
    });

    if (!result.valid) {
      setManualDoiCheck({ status: "fail", result });
      return; // Block insertion: the DOI does not resolve at doi.org.
    }

    if (!result.relevant) {

      if (manualDoiCheck.status !== "warn") {
        setManualDoiCheck({ status: "warn", result });
        return;
      }
    }

    const finalDoi = result.normalizedDoi || norm;
    onSelectCitation({
      ...selectedCitation,
      data: { ...selectedCitation.data, DOI: finalDoi, doi: finalDoi },
    });
    closeDoiPrompt();
    onClose();
  };

  useEffect(() => {
    if (!showDoiPrompt) return;
    const trimmed = manualDoi.trim();
    if (!trimmed) {
      setManualDoiCheck({ status: "idle" });
      return;
    }
    const norm = normalizeDoiUtil(trimmed);
    if (!isValidDoiFormat(norm)) {
      setManualDoiCheck({ status: "idle" });
      return;
    }

    let cancelled = false;
    setManualDoiCheck({ status: "checking" });
    const handle = window.setTimeout(async () => {
      const result = await validateDoiOnline({
        doi: norm,
        title: selectedCitation?.data?.title,
        publicationTitle: selectedCitation?.data?.publicationTitle,
        year: extractYear(selectedCitation?.data?.date || ""),
      });
      if (cancelled) return;
      if (!result.valid) {
        setManualDoiCheck({ status: "fail", result });
      } else if (!result.relevant) {
        setManualDoiCheck({ status: "warn", result });
      } else {
        setManualDoiCheck({ status: "ok", result });
      }
    }, 600);

    return () => {
      cancelled = true;
      window.clearTimeout(handle);
    };
  }, [manualDoi, showDoiPrompt, selectedCitation, extractYear]);

  const handleManualEntry = () => {
    onSelectCitation("manual");
  };

  const librarySyncPending = !error && (hasMore || loadingMore);
  const hasTypedSearch = searchQuery.trim().length > 0;

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-4xl max-h-[90vh] flex flex-col">
        {}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2 flex-wrap">
              <BookOpen className="text-purple-600" size={24} />
              <h2 className="text-xl font-bold text-gray-800">Insert Citation</h2>
              {librarySyncPending && (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 text-amber-900 text-xs font-medium px-2.5 py-1 border border-amber-200/80">
                  <Loader2 size={12} className={loadingMore ? "animate-spin" : ""} />
                  {hasTypedSearch ? "Searching library" : "Syncing library"}
                </span>
              )}
            </div>
            <span className="text-xs text-gray-400 pl-8">Powered by Sci2Code</span>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setShowZoteroSettings(true)}
              className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
              title="Citation library settings"
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

        {}
        {}

        {}
        <div className="p-4 border-b border-gray-200 bg-gray-50">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={18} />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search local cache and library. Empty = browse whole library."
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
                      (Still paging through every item that matches this query)
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
                  ? "Still working — loading every matching hit for your search"
                  : "Still working — your full citation library is not loaded yet"}
              </p>
              <p className="text-xs text-amber-900/90 mt-1">
                {hasTypedSearch ? (
                  <>
                    The library receives this text as a remote quick search. We then page through every matching item and apply
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
                      ? "Fetching the next batch from the library right now."
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

        {}
        <div
          ref={listRef}
          onScroll={onListScroll}
          className="flex-1 overflow-y-auto p-4"
        >
          {loading && !error && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <div className="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-purple-600 mb-2"></div>
                <p className="text-gray-600">Loading citation library...</p>
              </div>
            </div>
          )}

          {error === "ZOTERO_NOT_CONFIGURED" && (
            <ZoteroSettingsDialog isOpen embedded onClose={reloadZotero} />
          )}

          {error && error !== "ZOTERO_NOT_CONFIGURED" && (
            <div className="flex items-center justify-center py-12">
              <div className="text-center">
                <div className="text-red-500 mb-2">⚠️</div>
                <p className="text-red-600">{error}</p>
                <button
                  onClick={reloadZotero}
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
                    Make sure the citation library is configured and connected
                    {librarySyncPending && (
                      <span className="block text-amber-800 mt-1.5">Items are still downloading from the library.</span>
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
                const year = extractYear(citation?.data?.date || "");
                return (
                  <div
                    key={citation.key}
                    onClick={() => handleSelectCitation(citation)}
                    className="p-4 border border-gray-200 rounded-lg hover:border-purple-500 hover:shadow-md cursor-pointer transition-all bg-white hover:bg-purple-50"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1 min-w-0">
                        <h3 className="font-semibold text-gray-800 mb-2 line-clamp-2">{citation?.data?.title || "Untitled"}</h3>
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
                          if (normDoi) {
                            return (
                              <a
                                href={buildDoiHref(normDoi)}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 mt-1 hover:underline"
                                onClick={(e) => e.stopPropagation()}
                                title="Open at doi.org. Authoritative validation runs when you select this citation."
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

          {}
          {!loading && !error && (loadingMore || hasMore) && (
            <div className="flex items-center justify-center py-6">
              <div className="flex items-center gap-2 text-sm text-gray-600">
                <Loader2 size={18} className={loadingMore ? "animate-spin" : ""} />
                <span>
                  {loadingMore
                    ? "Adding more citations from the library…"
                    : "Scroll toward the bottom to load another batch"}
                </span>
              </div>
            </div>
          )}
        </div>

        {}
        <div className="p-4 border-t border-gray-200 bg-gray-50">
          {librarySyncPending && (
            <p className="text-xs text-amber-900 font-medium mb-2 flex items-center gap-2">
              <Loader2 size={14} className={loadingMore ? "animate-spin text-amber-700" : "text-amber-600"} />
              {hasTypedSearch
                ? "Library search in progress — not every matching item is downloaded yet."
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
            <span className="text-gray-500 font-medium">
              Format: {format === "jsonld" ? "JSON-LD" : format.toUpperCase()}
            </span>
          </div>
          {filteredCitations.length > 0 && (
            <p className="text-xs text-gray-600 text-center pt-2 border-t border-gray-300">
              <span className="font-medium">Click a citation</span> to select and insert it
            </p>
          )}
        </div>
      </div>

      {}
      {showDoiPrompt && selectedCitation && (
        <DoiPromptDialog
          citation={selectedCitation}
          manualDoi={manualDoi}
          manualDoiError={manualDoiError}
          checkState={manualDoiCheck}
          onClose={closeDoiPrompt}
          onChange={(value) => {
            setManualDoi(value);
            if (!value.trim()) {
              setManualDoiError(null);
              return;
            }
            const norm = normalizeDoiUtil(value);
            setManualDoiError(isValidDoiFormat(norm) ? null : "DOI looks malformed");
          }}
          onConfirmWithoutDoi={handleConfirmWithoutDoi}
          onConfirmWithDoi={handleAddDoiAndConfirm}
        />
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

interface DoiPromptDialogProps {
  citation: CitationItem;
  manualDoi: string;
  manualDoiError: string | null;
  checkState: DoiCheckState;
  onClose: () => void;
  onChange: (value: string) => void;
  onConfirmWithoutDoi: () => void;
  onConfirmWithDoi: () => void;
}

const DoiPromptDialog: React.FC<DoiPromptDialogProps> = ({
  citation,
  manualDoi,
  manualDoiError,
  checkState,
  onClose,
  onChange,
  onConfirmWithoutDoi,
  onConfirmWithDoi,
}) => {
  const trimmed = manualDoi.trim();
  const checking = checkState.status === "checking";
  const hasResult = checkState.status === "ok" || checkState.status === "warn" || checkState.status === "fail";

  const insertDisabled =
    !trimmed ||
    !!manualDoiError ||
    checking ||
    checkState.status === "fail";

  const insertLabel = useMemo(() => {
    if (checking) return "Validating…";
    if (checkState.status === "warn") return "Insert anyway";
    if (checkState.status === "fail") return "Cannot insert (invalid)";
    return "Add DOI & Insert";
  }, [checking, checkState.status]);

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black bg-opacity-70 p-4"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-lg shadow-2xl w-full max-w-lg max-h-[90vh] flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-6 overflow-y-auto">
          <div className="flex items-start gap-3 mb-4">
            <div className="flex-shrink-0 w-10 h-10 bg-yellow-100 rounded-full flex items-center justify-center">
              <AlertCircle className="text-yellow-600" size={24} />
            </div>
            <div className="flex-1 min-w-0">
              <h3 className="text-lg font-bold text-gray-900 mb-1">
                {checkState.status === "fail"
                  ? "DOI does not resolve"
                  : checkState.status === "warn"
                  ? "DOI resolves but metadata differs"
                  : "DOI Missing or Unverified"}
              </h3>
              <p className="text-sm text-gray-700 mb-2 break-words">
                Citation: <strong>"{citation.data.title}"</strong>
              </p>
              <p className="text-sm text-gray-600">
                Validation queries doi.org and compares the registrar metadata
                (Crossref / DataCite) against this citation. Pattern matches
                alone do not prove a DOI exists.
              </p>
            </div>
          </div>

          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-2" htmlFor="manualDoiInput">
              DOI:
            </label>
            <div className="relative">
              <input
                id="manualDoiInput"
                type="text"
                value={manualDoi}
                onChange={(e) => onChange(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !manualDoiError && !checking && trimmed) {
                    e.preventDefault();
                    onConfirmWithDoi();
                  }
                }}
                placeholder="10.1234/example"
                spellCheck={false}
                autoCapitalize="off"
                autoCorrect="off"
                className="w-full pl-3 pr-9 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent font-mono text-sm"
                aria-invalid={!!manualDoiError}
                aria-describedby="manualDoiHint"
              />
              {checking && (
                <Loader2
                  size={16}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 animate-spin"
                  aria-hidden
                />
              )}
            </div>

            {manualDoiError && (
              <div className="text-sm text-red-600 mt-1" role="alert">
                {manualDoiError}
              </div>
            )}

            <p id="manualDoiHint" className="text-xs text-gray-500 mt-1">
              Paste the full DOI (e.g. <code>10.1038/s41586-020-2649-2</code>) or a doi.org URL.
            </p>

            {}
            {hasResult && (
              <DoiValidationStatus state={checkState} />
            )}
          </div>
        </div>

        <div className="flex flex-wrap gap-2 justify-end p-4 border-t border-gray-200 bg-gray-50">
          <button
            type="button"
            onClick={onConfirmWithoutDoi}
            className="px-4 py-2 bg-gray-200 hover:bg-gray-300 text-gray-800 rounded-lg font-medium transition-colors"
          >
            Proceed Without DOI
          </button>
          {trimmed && (
            <button
              type="button"
              onClick={onConfirmWithDoi}
              disabled={insertDisabled}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 disabled:cursor-not-allowed text-white rounded-lg font-medium transition-colors flex items-center gap-2"
            >
              {checking && <Loader2 size={16} className="animate-spin" />}
              {insertLabel}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

const DoiValidationStatus: React.FC<{ state: DoiCheckState }> = ({ state }) => {
  if (state.status === "ok") {
    return (
      <div
        className="mt-3 flex items-start gap-2 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-900"
        role="status"
      >
        <CheckCircle2 size={16} className="mt-0.5 flex-shrink-0 text-green-600" aria-hidden />
        <div className="min-w-0">
          <p className="font-semibold">DOI verified at doi.org</p>
          {state.result.resolvedTitle && (
            <p className="text-xs text-green-900/90 truncate" title={state.result.resolvedTitle}>
              {state.result.resolvedTitle}
              {state.result.resolvedYear ? ` (${state.result.resolvedYear})` : ""}
            </p>
          )}
        </div>
      </div>
    );
  }

  if (state.status === "warn") {
    return (
      <div
        className="mt-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900"
        role="status"
      >
        <ShieldAlert size={16} className="mt-0.5 flex-shrink-0 text-amber-600" aria-hidden />
        <div className="min-w-0 space-y-1">
          <p className="font-semibold">{state.result.error || "Registrar metadata differs from this citation."}</p>
          {state.result.resolvedTitle && (
            <p className="text-xs">
              Registrar title: <span className="font-medium">{state.result.resolvedTitle}</span>
              {state.result.resolvedYear ? ` (${state.result.resolvedYear})` : ""}
            </p>
          )}
          {state.result.resolvedPublicationTitle && (
            <p className="text-xs italic">{state.result.resolvedPublicationTitle}</p>
          )}
          <p className="text-xs">Click again to insert anyway, or correct the DOI above.</p>
        </div>
      </div>
    );
  }

  if (state.status === "fail") {
    return (
      <div
        className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900"
        role="alert"
      >
        <AlertCircle size={16} className="mt-0.5 flex-shrink-0 text-red-600" aria-hidden />
        <div className="min-w-0">
          <p className="font-semibold">{state.result.error || "DOI did not resolve."}</p>
          <p className="text-xs">doi.org could not find a registered record for this string.</p>
        </div>
      </div>
    );
  }

  return null;
};

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

          {}
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
