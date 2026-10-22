import React, { useState } from "react";
import { AlertTriangle, ChevronDown, ChevronRight } from "lucide-react";
import {
  DraftChange,
  MERGE_CHANGES,
  MergeChange,
  MergeSummary,
  displayValue,
  extractLocalName,
  mergeCount,
  mergeHeadline,
  mergeKindTitle,
  mergeNames,
} from "../utils/draftChangeHelpers";

export type Resolution = "keep_draft" | "take_public" | "merge";

export interface FullDiffChange {
  entityIri: string;
  entityLabel?: string;
  kind: string;
  change: "added" | "changed" | "removed";
  yourAxioms: string[];
  publicAxioms: string[];
  conflict: boolean;
}

export interface FullDiffHeader {
  field: string;
  baselineValue?: unknown;
  yourValue?: unknown;
  publicValue?: unknown;
  conflict: boolean;
}

export interface FullDiff {
  available: boolean;
  baselineLost: boolean;
  publicChangedSinceDraft: boolean;
  conflictCount: number;
  changes: FullDiffChange[];
  header: FullDiffHeader[];
  otherAxioms: { added: number; removed: number; items: { axiom: string; change: "added" | "removed" }[] };
}

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.map(String) : []);

export const normalizeFullDiff = (raw: any): FullDiff => {
  const data = raw && typeof raw === "object" ? raw : {};
  const other = data.otherAxioms && typeof data.otherAxioms === "object" ? data.otherAxioms : {};
  return {
    available: data.available === true,
    baselineLost: data.baselineLost === true,
    publicChangedSinceDraft: data.publicChangedSinceDraft === true,
    conflictCount: typeof data.conflictCount === "number" ? data.conflictCount : 0,
    changes: (Array.isArray(data.changes) ? data.changes : []).map((c: any) => ({
      entityIri: String(c?.entityIri ?? ""),
      entityLabel: c?.entityLabel ?? undefined,
      kind: String(c?.kind ?? "other"),
      change: c?.change === "added" || c?.change === "removed" ? c.change : "changed",
      yourAxioms: strings(c?.yourAxioms),
      publicAxioms: strings(c?.publicAxioms),
      conflict: c?.conflict === true,
    })),
    header: (Array.isArray(data.header) ? data.header : []).map((h: any) => ({
      field: String(h?.field ?? ""),
      baselineValue: h?.baselineValue,
      yourValue: h?.yourValue,
      publicValue: h?.publicValue,
      conflict: h?.conflict === true,
    })),
    otherAxioms: {
      added: typeof other.added === "number" ? other.added : 0,
      removed: typeof other.removed === "number" ? other.removed : 0,
      items: (Array.isArray(other.items) ? other.items : []).map((i: any) => ({
        axiom: String(i?.axiom ?? ""),
        change: i?.change === "removed" ? "removed" : "added",
      })),
    },
  };
};

export const headerKey = (field: string): string => `header:${field}`;

export const conflictKeys = (diff: FullDiff): string[] => [
  ...diff.changes.filter((c) => c.conflict).map((c) => c.entityIri),
  ...diff.header.filter((h) => h.conflict).map((h) => headerKey(h.field)),
];

const HEADER_LABELS: Record<string, string> = {
  ontologyIri: "Ontology IRI",
  versionIri: "Version IRI",
  ontologyAnnotations: "Ontology annotations",
  imports: "Imports",
};

const KIND_ORDER = ["class", "objectProperty", "dataProperty", "annotationProperty", "individual", "datatype", "other"];

const CHANGE_SYMBOL: Record<string, { symbol: string; cls: string }> = {
  added: { symbol: "+", cls: "text-green-500" },
  removed: { symbol: "−", cls: "text-red-500" },
  changed: { symbol: "~", cls: "text-amber-500" },
};

const CONFLICT_STYLE = { borderColor: "rgba(234,179,8,0.5)", backgroundColor: "rgba(234,179,8,0.08)" };

const ResolutionChoices: React.FC<{
  value?: Resolution;
  allowMerge: boolean;
  onChoose: (value: Resolution | null) => void;
}> = ({ value, allowMerge, onChoose }) => {
  const options: [Resolution, string][] = [["keep_draft", "Keep draft"], ["take_public", "Take Public's"]];
  if (allowMerge) {
    options.push(["merge", "Merge"]);
  }
  return (
    <div className="flex flex-wrap items-center gap-1 mt-1">
      {options.map(([option, text]) => (
        <button
          key={option}
          type="button"
          aria-pressed={value === option}
          onClick={() => onChoose(value === option ? null : option)}
          className={`text-[10px] px-1.5 py-0.5 rounded border ${value === option ? "bg-blue-600 text-white border-blue-600" : ""}`}
          style={value === option ? undefined : { borderColor: "var(--color-border)" }}
        >
          {text}
        </button>
      ))}
      {!value && <span className="text-[10px] text-amber-600">Needs a choice</span>}
    </div>
  );
};

export const DraftFullChanges: React.FC<{
  diff: FullDiff;
  resolutions: Record<string, Resolution>;
  onResolve: (key: string, value: Resolution | null) => void;
  notice?: string | null;
}> = ({ diff, resolutions, onResolve, notice }) => {
  const [openRows, setOpenRows] = useState<Set<string>>(new Set());
  const [closedKinds, setClosedKinds] = useState<Set<string>>(new Set());
  const pending = conflictKeys(diff).filter((key) => !resolutions[key]).length;
  const toggle = (set: Set<string>, key: string) => {
    const next = new Set(set);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    return next;
  };
  const kinds = KIND_ORDER.filter((kind) => diff.changes.some((c) => (KIND_ORDER.includes(c.kind) ? c.kind : "other") === kind));
  const empty = diff.changes.length === 0 && diff.header.length === 0 && diff.otherAxioms.items.length === 0
    && diff.otherAxioms.added === 0 && diff.otherAxioms.removed === 0;
  return (
    <div className="mt-2 flex flex-col gap-1.5 text-[11px]" data-testid="full-changes">
      {notice && (
        <div className="rounded px-2 py-1.5 border text-red-600" style={{ borderColor: "rgba(239,68,68,0.4)" }}>{notice}</div>
      )}
      {diff.publicChangedSinceDraft && (
        <div className="flex items-center gap-1 rounded px-2 py-1.5 border text-amber-600" style={CONFLICT_STYLE}>
          <AlertTriangle size={11} />
          Public changed since this draft started
        </div>
      )}
      {pending > 0 && (
        <div className="font-semibold text-amber-600">
          {pending} conflict{pending !== 1 ? "s" : ""} still need{pending === 1 ? "s" : ""} a choice
        </div>
      )}
      {empty && <div className="opacity-50">No differences found between this draft and its starting point.</div>}
      {diff.header.length > 0 && (
        <div className="rounded border" style={{ borderColor: "var(--color-border)" }}>
          <div className="px-2 py-1.5 font-medium">Ontology header</div>
          <div className="border-t divide-y" style={{ borderColor: "var(--color-border)" }}>
            {diff.header.map((h) => {
              const label = HEADER_LABELS[h.field] || h.field;
              const showPublic = h.publicValue !== undefined && displayValue(h.publicValue) !== displayValue(h.baselineValue);
              return (
                <div key={h.field} className={`px-2 py-1.5 ${h.conflict ? "border-l-2" : ""}`} style={h.conflict ? CONFLICT_STYLE : undefined}
                  data-conflict={h.conflict ? "true" : undefined}>
                  <div className="flex items-start gap-1.5">
                    {h.conflict && <AlertTriangle size={10} className="text-amber-500 mt-0.5 flex-shrink-0" />}
                    <span className="opacity-80 break-all">
                      {label}: {displayValue(h.baselineValue)} → {displayValue(h.yourValue)}
                      {showPublic && <span className="opacity-60"> (public: {displayValue(h.publicValue)})</span>}
                    </span>
                  </div>
                  {h.conflict && (
                    <ResolutionChoices value={resolutions[headerKey(h.field)]} allowMerge={false}
                      onChoose={(value) => onResolve(headerKey(h.field), value)} />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
      {kinds.map((kind) => {
        const items = diff.changes.filter((c) => (KIND_ORDER.includes(c.kind) ? c.kind : "other") === kind);
        const counts = (["added", "changed", "removed"] as const)
          .map((change) => [change, items.filter((c) => c.change === change).length] as const)
          .filter(([, count]) => count > 0)
          .map(([change, count]) => `${count} ${change}`);
        const open = !closedKinds.has(kind);
        return (
          <div key={kind} className="rounded border" style={{ borderColor: "var(--color-border)" }}>
            <button type="button" className="w-full flex items-center justify-between px-2 py-1.5 font-medium"
              onClick={() => setClosedKinds((prev) => toggle(prev, kind))}>
              <span className="flex items-center gap-1.5">
                {open ? <ChevronDown size={11} /> : <ChevronRight size={11} />}
                {mergeKindTitle(kind)}
                {items.some((c) => c.conflict) && <AlertTriangle size={10} className="text-amber-500" />}
              </span>
              <span className="opacity-50">{counts.join(" · ")}</span>
            </button>
            {open && (
              <div className="border-t divide-y" style={{ borderColor: "var(--color-border)" }}>
                {items.map((c) => {
                  const meta = CHANGE_SYMBOL[c.change];
                  const rowOpen = openRows.has(c.entityIri);
                  return (
                    <div key={c.entityIri} className={`px-2 py-1.5 ${c.conflict ? "border-l-2" : ""}`}
                      style={c.conflict ? CONFLICT_STYLE : undefined} data-conflict={c.conflict ? "true" : undefined}>
                      <button type="button" className="w-full flex items-start gap-2 text-left"
                        onClick={() => setOpenRows((prev) => toggle(prev, c.entityIri))}>
                        <span className={`font-bold flex-shrink-0 ${meta.cls}`}>{meta.symbol}</span>
                        <span className="flex-1 min-w-0 opacity-80 truncate" title={c.entityIri}>
                          {c.entityLabel || extractLocalName(c.entityIri)}
                        </span>
                        {c.conflict && <AlertTriangle size={10} className="text-amber-500 mt-0.5 flex-shrink-0" />}
                        <span className="opacity-40 text-[10px] flex-shrink-0">{c.change}</span>
                      </button>
                      {c.conflict && (
                        <ResolutionChoices value={resolutions[c.entityIri]} allowMerge
                          onChoose={(value) => onResolve(c.entityIri, value)} />
                      )}
                      {rowOpen && (
                        <div className="mt-1 grid grid-cols-2 gap-2 text-[10px]">
                          <div>
                            <div className="font-medium opacity-70">Your draft</div>
                            {c.yourAxioms.length ? c.yourAxioms.map((a, i) => <div key={i} className="opacity-70 break-all">{a}</div>)
                              : <div className="opacity-40">(none)</div>}
                          </div>
                          <div>
                            <div className="font-medium opacity-70">Public</div>
                            {c.publicAxioms.length ? c.publicAxioms.map((a, i) => <div key={i} className="opacity-70 break-all">{a}</div>)
                              : <div className="opacity-40">(none)</div>}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      {(diff.otherAxioms.added > 0 || diff.otherAxioms.removed > 0 || diff.otherAxioms.items.length > 0) && (
        <div className="rounded border" style={{ borderColor: "var(--color-border)" }}>
          <div className="px-2 py-1.5 font-medium">
            Other axioms: {diff.otherAxioms.added} added, {diff.otherAxioms.removed} removed
          </div>
          {diff.otherAxioms.items.length > 0 && (
            <div className="border-t divide-y" style={{ borderColor: "var(--color-border)" }}>
              {diff.otherAxioms.items.map((item, i) => (
                <div key={i} className="flex items-start gap-2 px-2 py-1">
                  <span className={`font-bold flex-shrink-0 ${CHANGE_SYMBOL[item.change].cls}`}>{CHANGE_SYMBOL[item.change].symbol}</span>
                  <span className="opacity-70 break-all">{item.axiom}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const CHANGE_TITLES: Record<MergeChange, string> = {
  added: "added",
  changed: "changed",
  removed: "removed",
  copies: "copies added",
};

export const DraftMergeRow: React.FC<{ change: DraftChange; expanded: boolean; onToggle: () => void }> = ({
  change,
  expanded,
  onToggle,
}) => {
  const summary = change.operationData?.mergeSummary as MergeSummary | undefined;
  const kinds = summary?.kinds || [];
  const headline = mergeHeadline(summary, change.operationData?.value);
  return (
    <div className="px-2 py-1.5" data-testid="merge-row">
      <button type="button" className="w-full flex items-start gap-2 text-left" onClick={onToggle}
        aria-expanded={kinds.length ? expanded : undefined} disabled={!kinds.length}>
        {kinds.length > 0 && (expanded ? <ChevronDown size={11} className="mt-0.5 flex-shrink-0" /> : <ChevronRight size={11} className="mt-0.5 flex-shrink-0" />)}
        <span className="font-bold flex-shrink-0 text-green-500">+</span>
        <span className="flex-1 min-w-0 opacity-80">{headline}</span>
      </button>
      {expanded && kinds.map((kind) => (
        <div key={kind.kind} className="mt-1 pl-5 text-[10px]">
          {MERGE_CHANGES.filter((c) => mergeCount(kind, c) > 0).map((c) => {
            const names = mergeNames(kind, c);
            const count = mergeCount(kind, c);
            return (
              <div key={c} className="mt-0.5">
                <span className="font-medium opacity-70">{mergeKindTitle(kind.kind)} — {CHANGE_TITLES[c]} ({count}): </span>
                <span className="opacity-60 break-words">
                  {names.join(", ")}
                  {count > names.length && ` +${count - names.length} more`}
                </span>
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
};
