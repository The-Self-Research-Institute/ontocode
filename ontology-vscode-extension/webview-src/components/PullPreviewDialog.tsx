import React, { useEffect, useState } from "react";
import { X, Download, AlertTriangle, ChevronDown, ChevronRight, RefreshCw } from "lucide-react";
import apiClient from "../services/apiClient";

interface DraftChange {
  id: string;
  operationType: string;
  operationData: Record<string, any>;
}

interface PullPreviewDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  projectId: string;
  userId: string;
}

// Map operation type → display category
const getCategory = (opType: string): string => {
  if (/^(create|delete|update)Class/i.test(opType)) return "Classes";
  if (/^(create|delete)ObjectProperty/i.test(opType) ||
      /^(add|delete)(PropertyDomain|PropertyRange|SubPropertyOf|InverseProperty|DisjointProperty|EquivalentProperty)/i.test(opType))
    return "Object Properties";
  if (/^(create|delete)DataProperty/i.test(opType)) return "Data Properties";
  if (/^(create|delete)AnnotationProperty/i.test(opType)) return "Annotation Properties";
  if (/^(create|delete)Individual/i.test(opType) ||
      /^(add|remove)ClassAssertion/i.test(opType))
    return "Individuals";
  if (/^(create|delete)Datatype/i.test(opType)) return "Datatypes";
  if (/^(add|delete|update)(SubClassOf|EquivalentClass|DisjointWith)/i.test(opType))
    return "Class Axioms";
  if (/^(add|delete|update)Annotation/i.test(opType)) return "Annotations";
  return "Other";
};

const CATEGORY_ORDER = [
  "Classes", "Object Properties", "Data Properties",
  "Annotation Properties", "Individuals", "Datatypes",
  "Class Axioms", "Annotations", "Other",
];

// Extract a human-readable name from operation data
const getEntityName = (opType: string, data: Record<string, any>): string => {
  if (!data) return "";
  const label = data.label as string;
  if (label) return label;
  const iri = (data.iri || data.target || "") as string;
  if (iri) {
    const lastHash = iri.lastIndexOf("#");
    const lastSlash = iri.lastIndexOf("/");
    const name = iri.substring(Math.max(lastHash, lastSlash) + 1);
    return name || iri;
  }
  return opType;
};

// + / − / ~ prefix and colour class
const getActionMeta = (opType: string) => {
  const lower = opType.toLowerCase();
  if (lower.startsWith("create") || lower.startsWith("add"))
    return { symbol: "+", cls: "text-green-500" };
  if (lower.startsWith("delete") || lower.startsWith("remove"))
    return { symbol: "−", cls: "text-red-500" };
  return { symbol: "~", cls: "text-amber-500" };
};

// Human-readable operation label
const getOpLabel = (opType: string): string =>
  opType
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (s) => s.toUpperCase())
    .trim();

const PullPreviewDialog: React.FC<PullPreviewDialogProps> = ({
  isOpen, onClose, onConfirm, projectId, userId,
}) => {
  const [changes, setChanges] = useState<DraftChange[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    setExpanded(new Set());
    apiClient
      .get<any>(`/api/ontology/${projectId}/drafts`, { userId })
      .then((res: any) => {
        const data = res?.data || res;
        setChanges(data.drafts || []);
      })
      .catch(() => setChanges([]))
      .finally(() => setLoading(false));
  }, [isOpen, projectId, userId]);

  if (!isOpen) return null;

  // Group by category
  const grouped: Record<string, DraftChange[]> = {};
  for (const c of changes) {
    const cat = getCategory(c.operationType);
    (grouped[cat] = grouped[cat] || []).push(c);
  }
  const categories = CATEGORY_ORDER.filter((cat) => grouped[cat]?.length);

  const toggleCategory = (cat: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      next.has(cat) ? next.delete(cat) : next.add(cat);
      return next;
    });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-lg rounded-lg shadow-xl border flex flex-col"
        style={{
          backgroundColor: "var(--color-background)",
          borderColor: "var(--color-border)",
          color: "var(--color-text)",
          maxHeight: "80vh",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-4 py-3 border-b flex-shrink-0"
          style={{ borderColor: "var(--color-border)" }}
        >
          <div className="flex items-center gap-2 font-semibold text-sm">
            <Download size={15} />
            Pull from Public
          </div>
          <button onClick={onClose} className="opacity-60 hover:opacity-100">
            <X size={15} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-4 py-3 flex flex-col gap-3 text-xs">
          {loading && (
            <div className="flex justify-center py-6 opacity-60">
              <RefreshCw size={16} className="animate-spin" />
            </div>
          )}

          {!loading && changes.length === 0 && (
            <div className="flex flex-col gap-2">
              <p className="opacity-70">
                Your draft has no pending changes. Pulling will sync your draft with the
                latest public version.
              </p>
            </div>
          )}

          {!loading && changes.length > 0 && (
            <>
              <div
                className="flex items-start gap-2 rounded-md px-3 py-2 border"
                style={{ borderColor: "var(--color-border)", backgroundColor: "rgba(234,179,8,0.08)" }}
              >
                <AlertTriangle size={14} className="text-amber-500 flex-shrink-0 mt-0.5" />
                <p className="text-amber-600 dark:text-amber-400">
                  <strong>{changes.length} draft change{changes.length !== 1 ? "s" : ""}</strong> will be
                  overwritten. Your draft will be replaced with the latest public version.
                </p>
              </div>

              {/* Grouped change list */}
              {categories.map((cat) => {
                const items = grouped[cat];
                const isOpen = expanded.has(cat);
                return (
                  <div key={cat} className="rounded border" style={{ borderColor: "var(--color-border)" }}>
                    <button
                      className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium hover:opacity-80 transition-opacity"
                      onClick={() => toggleCategory(cat)}
                    >
                      <span className="flex items-center gap-1.5">
                        {isOpen ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        {cat}
                      </span>
                      <span className="opacity-50">{items.length}</span>
                    </button>
                    {isOpen && (
                      <div
                        className="border-t divide-y"
                        style={{ borderColor: "var(--color-border)" }}
                      >
                        {items.map((c) => {
                          const { symbol, cls } = getActionMeta(c.operationType);
                          const name = getEntityName(c.operationType, c.operationData);
                          return (
                            <div key={c.id} className="flex items-center gap-2 px-3 py-1.5">
                              <span className={`font-bold flex-shrink-0 ${cls}`}>{symbol}</span>
                              <span className="opacity-80 truncate">{name}</span>
                              <span className="ml-auto opacity-40 flex-shrink-0 text-[10px]">
                                {getOpLabel(c.operationType)}
                              </span>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })}
            </>
          )}
        </div>

        {/* Footer */}
        <div
          className="flex justify-end gap-2 px-4 py-3 border-t flex-shrink-0"
          style={{ borderColor: "var(--color-border)" }}
        >
          <button
            onClick={onClose}
            className="text-xs px-3 py-1.5 rounded border transition-colors"
            style={{ borderColor: "var(--color-border)" }}
          >
            Cancel
          </button>
          <button
            onClick={() => { onConfirm(); onClose(); }}
            disabled={loading}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors disabled:opacity-60"
          >
            <Download size={12} />
            {changes.length > 0 ? "Pull & Overwrite" : "Pull"}
          </button>
        </div>
      </div>
    </div>
  );
};

export default PullPreviewDialog;
