import React, { useMemo, useState } from "react";
import { X, AlertCircle, AlertTriangle, CheckCircle2, Search, Loader2 } from "lucide-react";
import type { ImportPreview, PreviewAxiom, PreviewIssue } from "../../services/spreadsheetImportService";

interface ImportPreviewDialogProps {
  preview: ImportPreview;
  fileName: string;
  committing: boolean;
  commitError: string | null;
  draftMode: boolean;
  onCommit: () => void;
  onClose: () => void;
}

type View = "row" | "type" | "issues";

const TYPE_LABELS: Record<string, string> = {
  Declaration: "Declarations",
  ClassAssertion: "Class assertions",
  DataPropertyAssertion: "Data property values",
  ObjectPropertyAssertion: "Object property links",
  AnnotationAssertion: "Annotations",
  SubClassOf: "SubClassOf",
  EquivalentClasses: "Equivalent classes",
  DisjointClasses: "Disjoint classes",
  SameIndividual: "Same individual",
  DifferentIndividuals: "Different individuals",
  PropertyDomain: "Domains",
  PropertyRange: "Ranges",
  SubPropertyOf: "SubPropertyOf",
  InverseObjectProperties: "Inverse properties",
  PropertyCharacteristic: "Characteristics",
};

const label = (type: string) => TYPE_LABELS[type] ?? type;

const ImportPreviewDialog: React.FC<ImportPreviewDialogProps> = ({
  preview,
  fileName,
  committing,
  commitError,
  draftMode,
  onCommit,
  onClose,
}) => {
  const [view, setView] = useState<View>(preview.errorCount > 0 ? "issues" : "row");
  const [filter, setFilter] = useState("");

  const q = filter.trim().toLowerCase();
  const axioms = useMemo(
    () => (q ? preview.axioms.filter((a) => a.text.toLowerCase().includes(q)) : preview.axioms),
    [preview.axioms, q],
  );
  const issues = useMemo(
    () =>
      q
        ? preview.issues.filter((i) => `${i.message} ${i.cell ?? ""} ${i.row ?? ""}`.toLowerCase().includes(q))
        : preview.issues,
    [preview.issues, q],
  );

  const issuesByRow = useMemo(() => {
    const m = new Map<string, PreviewIssue[]>();
    preview.issues.forEach((i) => {
      if (i.row == null) return;
      const key = `${i.sheet}|${i.row}`;
      m.set(key, [...(m.get(key) ?? []), i]);
    });
    return m;
  }, [preview.issues]);

  const groups = useMemo(() => {
    const m = new Map<string, PreviewAxiom[]>();
    axioms.forEach((a) => {
      const key = view === "type" ? a.type : `${a.sheet}|${a.row}`;
      m.set(key, [...(m.get(key) ?? []), a]);
    });
    return [...m.entries()];
  }, [axioms, view]);

  const tab = (id: View, text: React.ReactNode) => (
    <button
      onClick={() => setView(id)}
      className={`px-3 py-1.5 text-xs border-b-2 ${
        view === id ? "border-purple-600 text-purple-600" : "border-transparent text-gray-500 hover:text-gray-800"
      }`}
    >
      {text}
    </button>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onMouseDown={committing ? undefined : onClose}>
      <div
        className="bg-theme-surface rounded-lg shadow-xl w-[min(900px,calc(100vw-2rem))] h-[min(720px,calc(100vh-2rem))] flex flex-col"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b" style={{ borderColor: "var(--color-border)" }}>
          <div className="flex items-start justify-between gap-4">
            <div>
              <h3 className="text-sm font-semibold">
                {preview.axiomCount.toLocaleString()} axiom{preview.axiomCount === 1 ? "" : "s"}{" "}
                {preview.nothingNew ? "generated, all already in the ontology" : "would be added"}
              </h3>
              <p className="text-[11px] text-gray-500">
                From {preview.rowsProcessed.toLocaleString()} row{preview.rowsProcessed === 1 ? "" : "s"} of {fileName}.
                Nothing has been saved yet.
              </p>
            </div>
            <button onClick={onClose} disabled={committing} className="p-1 rounded hover:bg-gray-100 disabled:opacity-50" aria-label="Close">
              <X size={16} />
            </button>
          </div>
          <div className="flex flex-wrap gap-1.5 mt-2">
            {Object.entries(preview.countsByType).map(([type, n]) => (
              <span key={type} className="px-2 py-0.5 text-[11px] rounded-full bg-purple-50 text-purple-700">
                {label(type)}: {n.toLocaleString()}
              </span>
            ))}
          </div>
          <div className="mt-2 text-xs">
            {preview.errorCount > 0 ? (
              <span className="flex items-center gap-1 text-red-600">
                <AlertCircle size={14} /> {preview.errorCount} error{preview.errorCount === 1 ? "" : "s"} must be fixed
                before importing. Rows with errors do not produce the bad values.
              </span>
            ) : preview.nothingNew ? (
              <span className="flex items-center gap-1 text-amber-600">
                <AlertTriangle size={14} /> Everything from this file is already in the ontology. Nothing to import.
              </span>
            ) : (
              <span className="flex items-center gap-1 text-green-600">
                <CheckCircle2 size={14} /> No errors
                {!!preview.alreadyPresent && (
                  <span className="text-gray-500 ml-1">
                    · some of it is already in the ontology; only the new part will be added
                  </span>
                )}
                {preview.warningCount > 0 && (
                  <span className="text-amber-600 ml-1">
                    · {preview.warningCount} warning{preview.warningCount === 1 ? "" : "s"}
                  </span>
                )}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1 px-2 border-b" style={{ borderColor: "var(--color-border)" }}>
          {tab("row", "By row")}
          {tab("type", "By type")}
          {tab(
            "issues",
            <span className="flex items-center gap-1">
              Problems
              {preview.errorCount + preview.warningCount > 0 && (
                <span
                  className={`px-1.5 rounded-full text-[10px] text-white ${preview.errorCount > 0 ? "bg-red-600" : "bg-amber-500"}`}
                >
                  {preview.errorCount + preview.warningCount}
                </span>
              )}
            </span>,
          )}
          <div className="ml-auto flex items-center gap-1 px-2 py-1 border rounded my-1" style={{ borderColor: "var(--color-border)" }}>
            <Search size={12} className="text-gray-400" />
            <input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Filter"
              className="text-xs bg-transparent outline-none w-40"
            />
          </div>
        </div>

        <div className="flex-1 min-h-0 overflow-auto px-4 py-2">
          {view === "issues" ? (
            issues.length === 0 ? (
              <p className="text-xs text-gray-500">No problems found.</p>
            ) : (
              <table className="w-full text-xs">
                <thead className="text-left text-gray-500 sticky top-0 bg-theme-surface">
                  <tr>
                    <th className="py-1 w-6" />
                    <th className="py-1 w-14">Rule</th>
                    <th className="py-1 w-16">Row</th>
                    <th className="py-1 w-20">Cell</th>
                    <th className="py-1">Problem</th>
                  </tr>
                </thead>
                <tbody>
                  {issues.map((i, idx) => (
                    <tr key={idx} className="border-t align-top" style={{ borderColor: "var(--color-border)" }}>
                      <td className="py-1">
                        {i.severity === "ERROR" ? (
                          <AlertCircle size={13} className="text-red-600" />
                        ) : (
                          <AlertTriangle size={13} className="text-amber-500" />
                        )}
                      </td>
                      <td className="py-1">{i.rule > 0 ? i.rule : "—"}</td>
                      <td className="py-1">{i.row ?? (i.rule > 0 ? "all rows" : "—")}</td>
                      <td className="py-1 font-mono">{i.cell ?? "—"}</td>
                      <td className={`py-1 ${i.severity === "ERROR" ? "text-red-700" : "text-amber-700"}`}>
                        {i.message}
                        {i.line != null && i.row == null && (
                          <span className="text-gray-400"> (rule line {i.line}, column {i.column})</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          ) : groups.length === 0 ? (
            <p className="text-xs text-gray-500">
              {preview.axiomCount === 0 ? "The rules did not produce any axioms." : "Nothing matches the filter."}
            </p>
          ) : (
            groups.map(([key, items]) => {
              const [sheet, row] = key.split("|");
              const rowIssues = view === "row" ? issuesByRow.get(key) ?? [] : [];
              return (
                <div key={key} className="mb-3">
                  <div className="text-[11px] font-semibold text-gray-600 mb-0.5 flex items-center gap-2">
                    {view === "type" ? `${label(key)} (${items.length})` : `${sheet} · row ${row}`}
                    {rowIssues.some((i) => i.severity === "ERROR") && (
                      <span className="text-red-600 font-normal flex items-center gap-1">
                        <AlertCircle size={11} /> {rowIssues.filter((i) => i.severity === "ERROR").map((i) => i.message).join("; ")}
                      </span>
                    )}
                  </div>
                  <ul className="font-mono text-[11px] space-y-0.5">
                    {items.map((a, idx) => (
                      <li key={idx} className="flex gap-2">
                        <span className="text-gray-400 w-40 flex-shrink-0 truncate">{label(a.type)}</span>
                        <span className="break-all">{a.text}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })
          )}
          {view !== "issues" && preview.axiomsTruncated && (
            <p className="text-[11px] text-gray-500 mt-2">
              Showing the first {preview.axioms.length.toLocaleString()} of {preview.axiomCount.toLocaleString()} axioms.
              The counts above cover all of them.
            </p>
          )}
          {view === "issues" && preview.issuesTruncated && (
            <p className="text-[11px] text-gray-500 mt-2">Showing the first {preview.issues.length.toLocaleString()} problems.</p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-4 py-2 border-t" style={{ borderColor: "var(--color-border)" }}>
          {commitError ? (
            <span className="mr-auto text-[11px] text-red-600 flex items-center gap-1">
              <AlertCircle size={12} /> {commitError}
            </span>
          ) : (
            <span className="mr-auto text-[11px] text-gray-500">
              {draftMode ? "Goes into your private draft. " : ""}Recorded as one Change Assistant entry; one undo removes it.
            </span>
          )}
          <button onClick={onClose} disabled={committing} className="px-3 py-1 text-xs rounded border hover:bg-gray-50 disabled:opacity-50">
            Close
          </button>
          <button
            onClick={onCommit}
            disabled={!preview.canCommit || committing}
            title={
              preview.canCommit
                ? undefined
                : preview.errorCount > 0
                  ? "Fix the errors first"
                  : preview.nothingNew
                    ? "Already in the ontology"
                    : "Nothing to import"
            }
            className="flex items-center gap-1.5 px-3 py-1 text-xs rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {committing && <Loader2 size={12} className="animate-spin" />}
            {draftMode ? "Add to my draft" : "Add to ontology"}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ImportPreviewDialog;
