import React, { useEffect, useState } from "react";
import { X, Download, AlertTriangle, ChevronDown, ChevronRight, RefreshCw } from "lucide-react";
import apiClient from "../services/apiClient";
import {
  DraftChange,
  CATEGORY_ORDER,
  getCategory,
  getEntityName,
  getEntityIri,
  extractLocalName,
  getActionMeta,
  getOpLabel,
  groupByCategory,
} from "../utils/draftChangeHelpers";

interface PullPreviewDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  projectId: string;
  userId: string;
}

interface ConflictDetail {
  entityIRI: string;
  entityLabel: string;
  changedBy: string;
}

const PullPreviewDialog: React.FC<PullPreviewDialogProps> = ({
  isOpen, onClose, onConfirm, projectId, userId,
}) => {
  const [changes, setChanges] = useState<DraftChange[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [conflictIris, setConflictIris] = useState<Set<string>>(new Set());
  const [mainChangedSinceDraft, setMainChangedSinceDraft] = useState(false);
  const [conflictDetails, setConflictDetails] = useState<ConflictDetail[]>([]);

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    setExpanded(new Set());
    setConflictIris(new Set());
    setMainChangedSinceDraft(false);
    setConflictDetails([]);
    Promise.all([
      apiClient.get<any>(`/api/ontology/${projectId}/drafts`, { userId }),
      apiClient.get<any>(`/api/ontology/${projectId}/drafts/publish-preview`, { userId }),
    ])
      .then(([draftsRes, previewRes]) => {
        const draftsData = draftsRes?.data || draftsRes;
        setChanges(draftsData.drafts || []);
        const preview = previewRes?.data || previewRes;
        if (preview) {
          setMainChangedSinceDraft(!!preview.mainChangedSinceDraft);
          const cList: ConflictDetail[] = preview.conflicts || [];
          setConflictDetails(cList);
          setConflictIris(new Set<string>(cList.map((c) => c.entityIRI)));
        }
      })
      .catch(() => setChanges([]))
      .finally(() => setLoading(false));
  }, [isOpen, projectId, userId]);

  if (!isOpen) return null;

  const grouped = groupByCategory(changes);
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

              {/* Conflict details */}
              {conflictDetails.length > 0 && (
                <div
                  className="flex flex-col gap-1 rounded-md px-3 py-2 border text-[11px]"
                  style={{ borderColor: "rgba(234,179,8,0.4)", backgroundColor: "rgba(234,179,8,0.05)" }}
                >
                  <div className="flex items-center gap-1.5 font-semibold text-amber-600">
                    <AlertTriangle size={11} />
                    {conflictDetails.length} conflict{conflictDetails.length !== 1 ? "s" : ""} with public version
                  </div>
                  <ul className="opacity-70 space-y-0.5 pl-1">
                    {conflictDetails.slice(0, 5).map((c) => (
                      <li key={c.entityIRI}>
                        · {c.entityLabel || extractLocalName(c.entityIRI)}
                        {c.changedBy && <span className="opacity-60"> (changed by {c.changedBy})</span>}
                      </li>
                    ))}
                    {conflictDetails.length > 5 && (
                      <li className="opacity-50">…and {conflictDetails.length - 5} more</li>
                    )}
                  </ul>
                </div>
              )}
              {mainChangedSinceDraft && conflictDetails.length === 0 && (
                <div className="text-[11px] opacity-60 flex items-center gap-1.5 px-1">
                  <AlertTriangle size={11} className="text-amber-500" />
                  Public ontology was updated after you started your draft.
                </div>
              )}

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
                        {conflictDetails.length > 0 && items.some((c) => conflictIris.has(getEntityIri(c.operationData))) && (
                          <AlertTriangle size={10} className="text-amber-500" />
                        )}
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
                          const iri = getEntityIri(c.operationData);
                          const isConflict = iri ? conflictIris.has(iri) : false;
                          const parentIri = c.operationData?.parent as string | undefined;
                          const parentLabel = parentIri ? extractLocalName(parentIri) : null;
                          return (
                            <div key={c.id} className="flex items-start gap-2 px-3 py-1.5">
                              <span className={`font-bold flex-shrink-0 mt-0.5 ${cls}`}>{symbol}</span>
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-1.5">
                                  <span className="opacity-80 truncate">{name}</span>
                                  {isConflict && (
                                    <AlertTriangle
                                      size={11}
                                      className="text-amber-500 flex-shrink-0"
                                      title="Also modified in the public version"
                                    />
                                  )}
                                </div>
                                {parentLabel && (
                                  <div className="opacity-40 text-[10px] mt-0.5">⊂ {parentLabel}</div>
                                )}
                              </div>
                              <span className="opacity-40 flex-shrink-0 text-[10px] mt-0.5">
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
