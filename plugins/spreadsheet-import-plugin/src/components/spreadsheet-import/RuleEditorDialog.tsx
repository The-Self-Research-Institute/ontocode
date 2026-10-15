import React, { useEffect, useRef, useState } from "react";
import { X, CheckCircle2, AlertCircle, Loader2 } from "lucide-react";
import {
  validateRules,
  type RuleValidationResult,
  type TransformationRule,
} from "../../services/spreadsheetImportService";

interface RuleEditorDialogProps {
  projectId: string;
  sheetNames: string[];
  initial: TransformationRule;
  isNew: boolean;
  onCancel: () => void;
  onSave: (rule: TransformationRule) => void;
}

const COLUMN = /^([A-Za-z]+|\+)$/;
const ROW = /^([1-9][0-9]*|\+)$/;

const RuleEditorDialog: React.FC<RuleEditorDialogProps> = ({
  projectId,
  sheetNames,
  initial,
  isNew,
  onCancel,
  onSave,
}) => {
  const [draft, setDraft] = useState<TransformationRule>(initial);
  const [check, setCheck] = useState<RuleValidationResult | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const requestId = useRef(0);

  const set = <K extends keyof TransformationRule>(key: K, value: TransformationRule[K]) =>
    setDraft((d) => ({ ...d, [key]: value }));

  useEffect(() => {
    if (!draft.rule.trim()) {
      setCheck(null);
      return;
    }
    const id = ++requestId.current;
    const timer = setTimeout(async () => {
      setChecking(true);
      try {
        const [result] = await validateRules(projectId, [draft.rule]);
        if (id === requestId.current) {
          setCheck(result);
          setCheckError(null);
        }
      } catch (e: any) {
        if (id === requestId.current) {
          setCheckError(e?.message || "Could not check the rule");
        }
      } finally {
        if (id === requestId.current) {
          setChecking(false);
        }
      }
    }, 400);
    return () => clearTimeout(timer);
  }, [draft.rule, projectId]);

  const fieldErrors: string[] = [];
  if (!draft.sheetName) fieldErrors.push("Choose a sheet");
  if (!COLUMN.test(draft.startColumn.trim())) fieldErrors.push("Start column must be a letter such as A");
  if (!COLUMN.test(draft.endColumn.trim())) fieldErrors.push("End column must be a letter, or + for the last column");
  if (!ROW.test(draft.startRow.trim())) fieldErrors.push("Start row must be a number (1 or more)");
  if (!ROW.test(draft.endRow.trim())) fieldErrors.push("End row must be a number, or + for the last row");
  if (!draft.rule.trim()) fieldErrors.push("Write a rule");

  const canSave = fieldErrors.length === 0;

  const inputClass =
    "w-full px-2 py-1 text-xs border rounded bg-theme-surface focus:outline-none focus:ring-1 focus:ring-purple-500";
  const field = (label: string, input: React.ReactNode, hint?: string) => (
    <label className="grid grid-cols-[110px_1fr] items-center gap-2">
      <span className="text-xs text-gray-600">{label}</span>
      <span>
        {input}
        {hint && <span className="block text-[11px] text-gray-400 mt-0.5">{hint}</span>}
      </span>
    </label>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onMouseDown={onCancel}>
      <div
        className="bg-theme-surface rounded-lg shadow-xl w-[min(720px,calc(100vw-2rem))] max-h-[calc(100vh-2rem)] flex flex-col"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-2 border-b" style={{ borderColor: "var(--color-border)" }}>
          <h3 className="text-sm font-semibold">{isNew ? "Add transformation rule" : "Edit transformation rule"}</h3>
          <button onClick={onCancel} className="p-1 rounded hover:bg-gray-100" aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="p-4 space-y-2 overflow-auto">
          {field(
            "Sheet",
            <select className={inputClass} value={draft.sheetName} onChange={(e) => set("sheetName", e.target.value)}>
              {sheetNames.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>,
          )}
          <div className="grid grid-cols-2 gap-x-4 gap-y-2">
            {field("Start column", <input className={inputClass} value={draft.startColumn} onChange={(e) => set("startColumn", e.target.value.toUpperCase())} />)}
            {field("End column", <input className={inputClass} value={draft.endColumn} onChange={(e) => set("endColumn", e.target.value.toUpperCase())} />, "+ = last column")}
            {field("Start row", <input className={inputClass} value={draft.startRow} onChange={(e) => set("startRow", e.target.value)} />, "Row 1 is usually the header")}
            {field("End row", <input className={inputClass} value={draft.endRow} onChange={(e) => set("endRow", e.target.value)} />, "+ = last row, so the rule fits new files")}
          </div>
          {field("Comment", <input className={inputClass} value={draft.comment} onChange={(e) => set("comment", e.target.value)} />)}

          <div>
            <div className="flex items-center justify-between mb-1">
              <span className="text-xs text-gray-600">Rule</span>
              <span className="text-[11px] flex items-center gap-1">
                {checking && <Loader2 size={12} className="animate-spin text-gray-400" />}
                {!checking && check?.valid && (
                  <span className="flex items-center gap-1 text-green-600">
                    <CheckCircle2 size={12} /> Valid {check.entityKind} rule
                  </span>
                )}
                {!checking && check && !check.valid && (
                  <span className="flex items-center gap-1 text-red-600">
                    <AlertCircle size={12} /> Syntax error
                  </span>
                )}
              </span>
            </div>
            <textarea
              className={`${inputClass} font-mono h-48 resize-y ${check && !check.valid ? "border-red-400" : ""}`}
              spellCheck={false}
              value={draft.rule}
              onChange={(e) => set("rule", e.target.value)}
              placeholder={"Individual: @A*\n  Types: SensorReading\n  Facts: hasValue @B*(xsd:decimal)\n  Annotations: rdfs:label @C*"}
            />
            {check && !check.valid && <p className="text-[11px] text-red-600 mt-1">{check.error}</p>}
            {checkError && <p className="text-[11px] text-amber-600 mt-1">{checkError}</p>}
            <p className="text-[11px] text-gray-400 mt-1">
              @A* = column A of the current row · @A5 = one cell · @Sheet2!B* = another sheet · (xsd:decimal) sets
              the value type · ([en]) adds a language tag
            </p>
          </div>

          {fieldErrors.length > 0 && (
            <ul className="text-[11px] text-gray-500 list-disc pl-4">
              {fieldErrors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          )}
        </div>

        <div className="flex justify-end gap-2 px-4 py-2 border-t" style={{ borderColor: "var(--color-border)" }}>
          <button onClick={onCancel} className="px-3 py-1 text-xs rounded border hover:bg-gray-50">
            Cancel
          </button>
          <button
            onClick={() => onSave({ ...draft, startColumn: draft.startColumn.trim(), endColumn: draft.endColumn.trim(), startRow: draft.startRow.trim(), endRow: draft.endRow.trim() })}
            disabled={!canSave}
            className="px-3 py-1 text-xs rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
            title={check && !check.valid ? "The rule has a syntax error; you can still save it and fix it later" : undefined}
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default RuleEditorDialog;
