import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, ChevronRight, Search, X } from "lucide-react";
import {
  ALL_SEARCH_TYPES,
  SEARCH_GROUP_LABELS,
  SEARCH_TYPE_LABELS,
  describeMatch,
  flattenHits,
  highlightSegments,
  searchEntities,
  shortIri,
  type EntitySearchAncestor,
  type EntitySearchMatch,
  type EntitySearchResponse,
  type FlatHit,
  type SearchEntityType,
} from "../services/searchApi";

export interface GlobalSearchDialogProps {
  open: boolean;
  seedQuery?: string;
  projectId?: string | null;
  userId?: string;
  draft?: boolean;
  allowClosure: boolean;
  onClose: () => void;
  onNavigate: (hit: FlatHit, ancestors: EntitySearchAncestor[]) => void;
}

type Status = "idle" | "loading" | "warming" | "error";

const ROW_COLUMNS = "132px 190px 170px minmax(0, 1fr)";

const Highlighted: React.FC<{ text: string; ranges: number[][] }> = ({ text, ranges }) => (
  <>
    {highlightSegments(text, ranges).map((segment, index) =>
      segment.hit ? (
        <mark
          key={index}
          style={{ background: "var(--warning)", color: "var(--on-warning)", borderRadius: 2, padding: "0 1px" }}
        >
          {segment.text}
        </mark>
      ) : (
        <React.Fragment key={index}>{segment.text}</React.Fragment>
      ),
    )}
  </>
);

const MatchCell: React.FC<{ matches: EntitySearchMatch[] }> = ({ matches }) => {
  const first = matches[0];
  if (!first) {
    return null;
  }
  return (
    <span style={{ overflowWrap: "anywhere" }}>
      <span style={{ fontFamily: "monospace", fontSize: 11, color: "var(--text-tertiary)", marginRight: 6 }}>
        {describeMatch(first)}
      </span>
      <Highlighted text={first.value} ranges={first.ranges} />
      {matches.length > 1 && (
        <span style={{ color: "var(--text-tertiary)", marginLeft: 6, fontSize: 11 }}>+{matches.length - 1} more</span>
      )}
    </span>
  );
};

const Toggle: React.FC<{
  label: string;
  checked: boolean;
  disabled?: boolean;
  title?: string;
  onChange: (value: boolean) => void;
}> = ({ label, checked, disabled, title, onChange }) => (
  <label
    title={title}
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: 6,
      opacity: disabled ? 0.5 : 1,
      cursor: disabled ? "not-allowed" : "pointer",
    }}
  >
    <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
    {label}
  </label>
);

const GlobalSearchDialog: React.FC<GlobalSearchDialogProps> = ({
  open,
  seedQuery,
  projectId,
  userId,
  draft,
  allowClosure,
  onClose,
  onNavigate,
}) => {
  const [query, setQuery] = useState("");
  const [annotations, setAnnotations] = useState(true);
  const [regex, setRegex] = useState(false);
  const [hideDeprecated, setHideDeprecated] = useState(false);
  const [hideBuiltins, setHideBuiltins] = useState(false);
  const [closure, setClosure] = useState(false);
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [wholeWords, setWholeWords] = useState(false);
  const [ignoreWhitespace, setIgnoreWhitespace] = useState(false);
  const [searchIris, setSearchIris] = useState(true);
  const [showAll, setShowAll] = useState(false);
  const [types, setTypes] = useState<SearchEntityType[]>(ALL_SEARCH_TYPES);
  const [response, setResponse] = useState<EntitySearchResponse | null>(null);
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState("");
  const [collapsed, setCollapsed] = useState<SearchEntityType[]>([]);
  const [selected, setSelected] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const selectedRowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {return;}
    if (seedQuery) {
      setQuery(seedQuery);
    }
    inputRef.current?.focus();
    if (!seedQuery) {
      inputRef.current?.select();
    }
  }, [open, seedQuery]);

  useEffect(() => {
    const field = inputRef.current;
    if (open && seedQuery && field && field.value === seedQuery) {
      field.setSelectionRange(seedQuery.length, seedQuery.length);
    }
  }, [open, seedQuery, query]);

  useEffect(() => {
    const trimmed = query.trim();
    if (!open || !projectId || !trimmed || types.length === 0) {
      setResponse(null);
      setStatus("idle");
      setError("");
      return;
    }

    const controller = new AbortController();
    let cancelled = false;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const run = async (attempt: number): Promise<void> => {
      setStatus("loading");
      try {
        const result = await searchEntities(
          projectId,
          {
            query: trimmed,
            types,
            annotations,
            regex,
            hideDeprecated,
            hideBuiltins,
            caseSensitive,
            wholeWords,
            ignoreWhitespace,
            searchIris,
            showAll,
            scope: closure && allowClosure ? "closure" : "active",
            userId,
            draft,
          },
          controller.signal,
        );
        if (cancelled) {return;}
        if (result.warming) {
          if (attempt < 5) {
            setStatus("warming");
            retry = setTimeout(() => {
              if (!cancelled) {void run(attempt + 1);}
            }, 1500);
          } else {
            setStatus("error");
            setError("The ontology is still loading. Try again in a moment.");
          }
          return;
        }
        setResponse(result);
        setStatus("idle");
        setError("");
        setSelected(0);
      } catch (e) {
        if (cancelled) {return;}
        setResponse(null);
        setStatus("error");
        setError(e instanceof Error ? e.message : "Search failed");
      }
    };

    const timer = setTimeout(() => void run(0), 300);
    return () => {
      cancelled = true;
      controller.abort();
      clearTimeout(timer);
      if (retry) {clearTimeout(retry);}
    };
  }, [open, projectId, query, types, annotations, regex, hideDeprecated, hideBuiltins, closure, allowClosure, caseSensitive, wholeWords, ignoreWhitespace, searchIris, showAll, userId, draft]);

  const hits = useMemo(() => (response ? flattenHits(response, types) : []), [response, types]);
  const visible = useMemo(() => hits.filter((hit) => !collapsed.includes(hit.type)), [hits, collapsed]);

  useEffect(() => {
    selectedRowRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [selected, visible]);

  useEffect(() => {
    if (!open) {return;}
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === "f" || e.key === "F")) {
        e.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
      }
    };
    document.addEventListener("keydown", handler, true);
    return () => document.removeEventListener("keydown", handler, true);
  }, [open]);

  const go = useCallback(
    (hit: FlatHit | undefined) => {
      if (!hit) {return;}
      onNavigate(hit, response?.classAncestors ?? []);
      onClose();
    },
    [onNavigate, onClose, response],
  );

  const onKeyDown = (e: KeyboardEvent) => {
    const onButton = e.target instanceof HTMLElement && e.target.tagName === "BUTTON";
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelected((current) => Math.min(current + 1, Math.max(visible.length - 1, 0)));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelected((current) => Math.max(current - 1, 0));
    } else if (e.key === "Enter" && !onButton) {
      e.preventDefault();
      go(visible[selected]);
    }
  };

  const keyHandlerRef = useRef(onKeyDown);
  keyHandlerRef.current = onKeyDown;

  useEffect(() => {
    if (!open) {return;}
    const handler = (e: KeyboardEvent) => keyHandlerRef.current(e);
    document.addEventListener("keydown", handler, true);
    return () => document.removeEventListener("keydown", handler, true);
  }, [open]);

  const toggleType = (type: SearchEntityType) =>
    setTypes((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]));

  const toggleGroup = (type: SearchEntityType) =>
    setCollapsed((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]));

  if (!open) {
    return null;
  }

  const trimmed = query.trim();
  const total = hits.length;
  let rowIndex = -1;

  const message = (() => {
    if (status === "error") {return error;}
    if (status === "warming") {return "Loading ontology… retrying automatically";}
    if (!trimmed) {return "Type to search classes, properties, datatypes and individuals.";}
    if (status === "loading" && !response) {return "Searching…";}
    if (response && total === 0) {return `No results for “${trimmed}”. Try Annotations or fewer filters.`;}
    return "";
  })();

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-start justify-center"
      style={{ paddingTop: "10vh" }}
    >
      <div className="absolute inset-0" style={{ background: "var(--overlay)" }} onClick={onClose} />
      <div
        role="dialog"
        aria-label="Search ontology"
        style={{
          position: "relative",
          width: "min(860px, calc(100vw - 32px))",
          maxHeight: "78vh",
          display: "flex",
          flexDirection: "column",
          background: "var(--surface-1)",
          color: "var(--text-primary)",
          border: "1px solid var(--border)",
          borderRadius: 10,
          boxShadow: "0 24px 60px rgba(0,0,0,0.35)",
          fontSize: 13,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "12px 16px",
            borderBottom: "1px solid var(--border)",
            fontWeight: 600,
            fontSize: 14,
          }}
        >
          <span>Search ontology</span>
          <button type="button" aria-label="Close" onClick={onClose} style={{ color: "var(--text-secondary)" }}>
            <X size={16} />
          </button>
        </div>

        <div style={{ padding: "14px 16px", display: "grid", gap: 12, minHeight: 0 }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              background: "var(--surface-2)",
              border: "1px solid var(--accent)",
              borderRadius: 6,
              padding: "8px 10px",
            }}
          >
            <Search size={15} style={{ color: "var(--text-secondary)" }} />
            <input
              ref={inputRef}
              aria-label="Search query"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search by name, IRI or annotation value"
              style={{ flex: 1, background: "transparent", outline: "none", color: "inherit", fontSize: 14 }}
            />
            {(status === "loading" || status === "warming") && (
              <span
                aria-label="Searching"
                className="animate-spin"
                style={{
                  width: 12,
                  height: 12,
                  border: "2px solid var(--border)",
                  borderTopColor: "var(--accent)",
                  borderRadius: "50%",
                }}
              />
            )}
          </div>

          <div style={{ display: "flex", flexWrap: "wrap", gap: "6px 16px", color: "var(--text-secondary)" }}>
            <Toggle label="Case sensitive" checked={caseSensitive} onChange={setCaseSensitive} />
            <Toggle label="Whole words" checked={wholeWords} onChange={setWholeWords} />
            <Toggle label="Ignore white space" checked={ignoreWhitespace} onChange={setIgnoreWhitespace} />
            <Toggle label="Regex" checked={regex} onChange={setRegex} />
            <Toggle label="Show all results" checked={showAll} onChange={setShowAll} />
            <Toggle label="Search in IRIs" checked={searchIris} onChange={setSearchIris} />
            <Toggle label="Search in annotation values" checked={annotations} onChange={setAnnotations} />
            <Toggle label="Hide deprecated" checked={hideDeprecated} onChange={setHideDeprecated} />
            <Toggle label="Hide built-ins" checked={hideBuiltins} onChange={setHideBuiltins} />
            {allowClosure && <Toggle label="Include imports" checked={closure} onChange={setClosure} />}
          </div>

          <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
            {ALL_SEARCH_TYPES.map((type) => {
              const on = types.includes(type);
              return (
                <button
                  key={type}
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggleType(type)}
                  style={{
                    border: "1px solid var(--border)",
                    borderRadius: 999,
                    padding: "2px 10px",
                    fontSize: 12,
                    background: on ? "var(--accent-tint)" : "transparent",
                    color: on ? "var(--text-primary)" : "var(--text-tertiary)",
                  }}
                >
                  {SEARCH_GROUP_LABELS[type]}
                </button>
              );
            })}
          </div>

          <div
            role="listbox"
            aria-label="Search results"
            style={{
              border: "1px solid var(--border)",
              borderRadius: 6,
              overflow: "auto",
              minHeight: 120,
              maxHeight: "40vh",
            }}
          >
            <div
              style={{
                display: "grid",
                gridTemplateColumns: ROW_COLUMNS,
                gap: 10,
                padding: "7px 12px",
                background: "var(--surface-3)",
                color: "var(--text-tertiary)",
                fontSize: 11,
                textTransform: "uppercase",
                letterSpacing: "0.06em",
                position: "sticky",
                top: 0,
              }}
            >
              <span>Found in</span>
              <span>Entity</span>
              <span>Id</span>
              <span>Match</span>
            </div>

            {message && (
              <div
                style={{
                  padding: "18px 12px",
                  color: status === "error" ? "var(--error)" : "var(--text-secondary)",
                }}
              >
                {message}
              </div>
            )}

            {response &&
              types.map((type) => {
                const group = hits.filter((hit) => hit.type === type);
                const isCollapsed = collapsed.includes(type);
                return (
                  <div key={type}>
                    <button
                      type="button"
                      onClick={() => group.length > 0 && toggleGroup(type)}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 4,
                        width: "100%",
                        textAlign: "left",
                        padding: "6px 12px",
                        background: "var(--surface-2)",
                        borderTop: "1px solid var(--border)",
                        fontWeight: 600,
                        color: group.length === 0 ? "var(--text-tertiary)" : "var(--text-primary)",
                      }}
                    >
                      {isCollapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                      {SEARCH_GROUP_LABELS[type]}
                      <span style={{ fontWeight: 400, color: "var(--text-tertiary)", marginLeft: 4 }}>{group.length}</span>
                    </button>
                    {!isCollapsed &&
                      group.map((hit) => {
                        rowIndex += 1;
                        const index = rowIndex;
                        const isSelected = index === selected;
                        return (
                          <div
                            key={`${type}:${hit.id}`}
                            ref={isSelected ? selectedRowRef : undefined}
                            role="option"
                            aria-selected={isSelected}
                            onClick={() => setSelected(index)}
                            onDoubleClick={() => go(hit)}
                            style={{
                              display: "grid",
                              gridTemplateColumns: ROW_COLUMNS,
                              gap: 10,
                              padding: "7px 12px",
                              alignItems: "baseline",
                              cursor: "pointer",
                              borderTop: "1px solid var(--divider)",
                              background: isSelected ? "var(--accent-pressed)" : undefined,
                            }}
                          >
                            <span style={{ color: "var(--text-secondary)" }}>{SEARCH_TYPE_LABELS[type]}</span>
                            <span style={{ overflowWrap: "anywhere" }}>{hit.label}</span>
                            <span
                              title={hit.id}
                              style={{
                                fontFamily: "monospace",
                                fontSize: 12,
                                color: "var(--text-tertiary)",
                                overflowWrap: "anywhere",
                              }}
                            >
                              #{shortIri(hit.id)}
                            </span>
                            <MatchCell matches={hit.matches} />
                          </div>
                        );
                      })}
                  </div>
                );
              })}
          </div>
        </div>

        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
            padding: "10px 16px",
            borderTop: "1px solid var(--border)",
            color: "var(--text-secondary)",
            fontSize: 12,
          }}
        >
          <span>
            {response ? `${total} result${total === 1 ? "" : "s"}` : ""}
            {response?.truncated ? " · showing the first matches per type, narrow your search" : ""}
          </span>
          <span style={{ color: "var(--text-tertiary)" }}>Enter or double-click to open · Esc to close</span>
        </div>
      </div>
    </div>
  );
};

export default GlobalSearchDialog;
