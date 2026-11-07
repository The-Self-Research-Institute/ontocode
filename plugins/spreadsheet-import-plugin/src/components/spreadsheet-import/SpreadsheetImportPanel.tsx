import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  Upload,
  FileSpreadsheet,
  Plus,
  Pencil,
  Trash2,
  Play,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Copy,
  X,
  Save,
  FolderOpen,
  Download,
  FileUp,
} from "lucide-react";
import SheetGrid, { type GridHighlight } from "./SheetGrid";
import RuleEditorDialog from "./RuleEditorDialog";
import ImportPreviewDialog from "./ImportPreviewDialog";
import SaveRuleSetDialog from "./SaveRuleSetDialog";
import ConfirmDeleteDialog from "./ConfirmDeleteDialog";
import {
  commitImport,
  deleteRuleSet,
  downloadTextFile,
  listRuleSets,
  parseSpreadsheet,
  previewImport,
  ruleSetFromJson,
  ruleSetToJson,
  saveRuleSet,
  validateRules,
  SPREADSHEET_ACCEPT,
  type ImportCommitResult,
  type ImportPreview,
  type ParsedSpreadsheet,
  type RuleValidationResult,
  type SavedRuleSet,
  type TransformationRule,
} from "../../services/spreadsheetImportService";
import { clearImportWork, loadImportWork, saveImportFile, saveImportRules, workKey } from "../../services/importWorkStore";
interface SpreadsheetImportPanelProps {
  projectId: string | null;
  userId?: string;
  username?: string;
  draftMode?: boolean;
  onImported?: () => void;
  onViewOnlyAction?: () => void;
  onDraftRequired?: () => void;
  isDraftEditor?: boolean;
}

const actorOf = (userId?: string, username?: string) => ({
  userId: userId || "anonymous",
  username: username || "Anonymous",
});

interface PanelState {
  file: File | null;
  workbook: ParsedSpreadsheet | null;
  activeSheet: string | null;
  rules: TransformationRule[];
  ruleSet: { id: string; name: string; description?: string | null } | null;
  ruleSetSnapshot: string | null;
}

const stateByProject = new Map<string, PanelState>();

const emptyState: PanelState = {
  file: null,
  workbook: null,
  activeSheet: null,
  rules: [],
  ruleSet: null,
  ruleSetSnapshot: null,
};

const newId = () => `rule-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

const errorText = (e: any): string | undefined =>
  e?.data?.error || e?.response?.data?.error || e?.response?.data?.message || e?.message;

const snapshotOf = (rules: TransformationRule[]) =>
  JSON.stringify(rules.map(({ id, ...rest }) => rest));

const fitToWorkbook = <T extends { sheetName: string }>(rules: T[], workbook: ParsedSpreadsheet | null): T[] => {
  if (!workbook || workbook.sheets.length !== 1) return rules;
  const names = new Set(workbook.sheets.map((s) => s.name));
  return rules.map((r) => (names.has(r.sheetName) ? r : { ...r, sheetName: workbook.sheets[0].name }));
};

const columnIndex = (letters: string): number => {
  let n = 0;
  for (const ch of letters.toUpperCase()) {
    if (ch < "A" || ch > "Z") return -1;
    n = n * 26 + (ch.charCodeAt(0) - 64);
  }
  return n;
};

const SpreadsheetImportPanel: React.FC<SpreadsheetImportPanelProps> = ({
  projectId,
  userId,
  username,
  draftMode = false,
  onImported,
  onViewOnlyAction,
  onDraftRequired,
  isDraftEditor = false,
}) => {
  const uploadBlocked = () => {
    if (onViewOnlyAction) {
      onViewOnlyAction();
      return true;
    }
    if (onDraftRequired) {
      onDraftRequired();
      return true;
    }
    return false;
  };

  const openFilePicker = () => {
    if (uploadBlocked()) return;
    fileInput.current?.click();
  };
  const key = projectId ?? "";
  const [state, setState] = useState<PanelState>(() => stateByProject.get(key) ?? emptyState);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [selectedRuleId, setSelectedRuleId] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ rule: TransformationRule; isNew: boolean } | null>(null);
  const [validation, setValidation] = useState<Record<string, RuleValidationResult>>({});
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [committing, setCommitting] = useState(false);
  const [commitError, setCommitError] = useState<string | null>(null);
  const [imported, setImported] = useState<(ImportCommitResult & { fileName: string }) | null>(null);
  const [savedSets, setSavedSets] = useState<SavedRuleSet[]>([]);
  const [saveDialog, setSaveDialog] = useState<"save" | "saveAs" | null>(null);
  const [savingSet, setSavingSet] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [ruleSetNotice, setRuleSetNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ kind: "rule"; id: string } | { kind: "ruleSet" } | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [restoredAt, setRestoredAt] = useState<number | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const ruleFileInput = useRef<HTMLInputElement>(null);
  const hydratedKey = useRef<string | null>(null);
  const savedFile = useRef<{ key: string | null; file: File | null; workbook: PanelState["workbook"] }>({
    key: null,
    file: null,
    workbook: null,
  });
  const [hydrationTick, setHydrationTick] = useState(0);
  const storeKey = workKey(userId, key);

  const { file, workbook, activeSheet, rules, ruleSet, ruleSetSnapshot } = state;
  const loadedSetInfo = ruleSet ? savedSets.find((s) => s.id === ruleSet.id) : undefined;
  const ownsLoadedSet =
    !isDraftEditor ||
    !loadedSetInfo ||
    (loadedSetInfo.createdByUserId
      ? loadedSetInfo.createdByUserId === userId
      : (loadedSetInfo.createdBy ?? "").toLowerCase() === (username ?? "").toLowerCase());

  useEffect(() => {
    const inMemory = stateByProject.get(key);
    setState(inMemory ?? emptyState);
    setSelectedRuleId(null);
    setRestoredAt(null);
    hydratedKey.current = null;
    if (inMemory || !projectId) {
      savedFile.current = { key: storeKey, file: inMemory?.file ?? null, workbook: inMemory?.workbook ?? null };
      hydratedKey.current = storeKey;
      setHydrationTick((t) => t + 1);
      return;
    }
    let cancelled = false;
    loadImportWork(storeKey).then((work) => {
      if (cancelled) return;
      if (work && !stateByProject.has(key)) {
        const restored: PanelState = {
          file: new File([work.file], work.fileName, { type: work.fileType }),
          workbook: work.workbook,
          activeSheet: work.activeSheet,
          rules: work.rules,
          ruleSet: work.ruleSet,
          ruleSetSnapshot: work.ruleSetSnapshot,
        };
        stateByProject.set(key, restored);
        savedFile.current = { key: storeKey, file: restored.file, workbook: restored.workbook };
        setState(restored);
        setRestoredAt(work.savedAt);
      }
      hydratedKey.current = storeKey;
      setHydrationTick((t) => t + 1);
    });
    return () => {
      cancelled = true;
    };
  }, [key, storeKey]);

  useEffect(() => {
    if (!projectId || hydratedKey.current !== storeKey) return;
    if (savedFile.current.key === storeKey && savedFile.current.file === file && savedFile.current.workbook === workbook) return;
    savedFile.current = { key: storeKey, file, workbook };
    if (file && workbook) {
      void saveImportFile(storeKey, {
        fileName: file.name,
        fileType: file.type,
        file,
        workbook,
        savedAt: Date.now(),
      });
    } else {
      void clearImportWork(storeKey);
    }
  }, [file, workbook, storeKey, hydrationTick]);

  useEffect(() => {
    if (!projectId || hydratedKey.current !== storeKey || !file || !workbook) return;
    if (savedFile.current.key !== storeKey || savedFile.current.file !== file) return;
    const timer = setTimeout(() => {
      void saveImportRules(storeKey, { activeSheet, rules, ruleSet, ruleSetSnapshot, savedAt: Date.now() });
    }, 400);
    return () => clearTimeout(timer);
  }, [activeSheet, rules, ruleSet, ruleSetSnapshot, file, workbook, storeKey, hydrationTick]);

  const discardWork = () => {
    void clearImportWork(storeKey);
    stateByProject.delete(key);
    setState(emptyState);
    setRestoredAt(null);
    setSelectedRuleId(null);
    setPreview(null);
  };

  const update = (next: Partial<PanelState>) =>
    setState((prev) => {
      const merged = { ...prev, ...next };
      stateByProject.set(key, merged);
      return merged;
    });

  const refreshSavedSets = async () => {
    if (!projectId) return;
    try {
      setSavedSets(await listRuleSets(projectId));
    } catch {
      setSavedSets([]);
    }
  };

  useEffect(() => {
    refreshSavedSets();
  }, [projectId]);

  useEffect(() => {
    if (!projectId || rules.length === 0) {
      setValidation({});
      return;
    }
    let cancelled = false;
    validateRules(projectId, rules.map((r) => r.rule))
      .then((results) => {
        if (cancelled) return;
        const byId: Record<string, RuleValidationResult> = {};
        results.forEach((res) => {
          const rule = rules[res.index];
          if (rule) byId[rule.id] = res;
        });
        setValidation(byId);
      })
      .catch(() => {
        if (!cancelled) setValidation({});
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, rules]);

  const handleFile = async (file: File | undefined) => {
    if (!file || !projectId) return;
    setUploading(true);
    setUploadError(null);
    try {
      const parsed = await parseSpreadsheet(projectId, file);
      const fitted = fitToWorkbook(rules, parsed);
      const wasSaved = ruleSetSnapshot !== null && snapshotOf(rules) === ruleSetSnapshot;
      update({
        file,
        workbook: parsed,
        activeSheet: parsed.sheets[0]?.name ?? null,
        rules: fitted,
        ...(wasSaved ? { ruleSetSnapshot: snapshotOf(fitted) } : {}),
      });
    } catch (e: any) {
      setUploadError(errorText(e) || "Could not read the spreadsheet");
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  const sheet = workbook?.sheets.find((s) => s.name === activeSheet) ?? null;
  const selectedRule = rules.find((r) => r.id === selectedRuleId) ?? null;

  const highlight: GridHighlight | null = useMemo(() => {
    if (!selectedRule || !sheet || selectedRule.sheetName !== sheet.name) return null;
    const col = (v: string) => (v.trim() === "+" ? sheet.columnCount : columnIndex(v.trim()));
    const row = (v: string) => (v.trim() === "+" ? sheet.rowCount : parseInt(v, 10));
    const h = {
      startColumn: col(selectedRule.startColumn),
      endColumn: col(selectedRule.endColumn),
      startRow: row(selectedRule.startRow),
      endRow: row(selectedRule.endRow),
    };
    return Object.values(h).some((n) => !Number.isFinite(n) || n < 1) ? null : h;
  }, [selectedRule, sheet]);

  const openNewRule = () => {
    const sheetName = activeSheet ?? workbook?.sheets[0]?.name ?? "";
    setEditing({
      isNew: true,
      rule: {
        id: newId(),
        sheetName,
        startColumn: "A",
        endColumn: "A",
        startRow: "2",
        endRow: "+",
        rule: "",
        comment: "",
        active: true,
      },
    });
  };

  const saveRule = (rule: TransformationRule) => {
    const exists = rules.some((r) => r.id === rule.id);
    update({ rules: exists ? rules.map((r) => (r.id === rule.id ? rule : r)) : [...rules, rule] });
    setSelectedRuleId(rule.id);
    if (rule.sheetName !== activeSheet && workbook?.sheets.some((s) => s.name === rule.sheetName)) {
      update({ activeSheet: rule.sheetName });
    }
    setEditing(null);
  };

  const deleteRule = (id: string) => {
    update({ rules: rules.filter((r) => r.id !== id) });
    if (selectedRuleId === id) setSelectedRuleId(null);
  };

  const duplicateRule = (rule: TransformationRule) => {
    const copy = { ...rule, id: newId(), comment: rule.comment ? `${rule.comment} (copy)` : "" };
    update({ rules: [...rules, copy] });
    setSelectedRuleId(copy.id);
  };

  const toggleActive = (id: string) =>
    update({ rules: rules.map((r) => (r.id === id ? { ...r, active: !r.active } : r)) });

  const runPreview = async () => {
    if (uploadBlocked()) return;
    if (!projectId || !file) return;
    setPreviewing(true);
    setPreviewError(null);
    try {
      setCommitError(null);
      setPreview(await previewImport(projectId, file, rules, draftMode, actorOf(userId, username).userId));
    } catch (e: any) {
      setPreviewError(errorText(e) || "Could not generate the preview");
    } finally {
      setPreviewing(false);
    }
  };

  const runCommit = async () => {
    if (uploadBlocked()) return;
    if (!projectId || !file) return;
    setCommitting(true);
    setCommitError(null);
    try {
      const actor = actorOf(userId, username);
      const result = await commitImport(projectId, file, rules, draftMode, actor.userId, actor.username);
      setPreview(null);
      setImported({ ...result, fileName: file.name });
      setRestoredAt(null);
      savedFile.current = { key: storeKey, file: null, workbook: null };
      void clearImportWork(storeKey);
      onImported?.();
    } catch (e: any) {
      setCommitError(errorText(e) || "The import failed");
    } finally {
      setCommitting(false);
    }
  };

  const unsavedChanges = rules.length > 0 && snapshotOf(rules) !== ruleSetSnapshot;
  const [pendingLoad, setPendingLoad] = useState<string | null>(null);
  const savedBy = () => actorOf(userId, username).username;

  const applyRules = (
    loaded: Omit<TransformationRule, "id">[],
    set: PanelState["ruleSet"],
    markSaved: boolean,
  ) => {
    const withIds = fitToWorkbook(loaded.map((r) => ({ ...r, id: newId() })), workbook);
    update({ rules: withIds, ruleSet: set, ruleSetSnapshot: markSaved ? snapshotOf(withIds) : null });
    setSelectedRuleId(null);
  };

  const loadRuleSet = (id: string) => {
    const set = savedSets.find((s) => s.id === id);
    if (!set) return;
    if (unsavedChanges && pendingLoad !== id) {
      setPendingLoad(id);
      setRuleSetNotice({ ok: false, text: `You have unsaved rule changes. Choose "${set.name}" again to replace them.` });
      return;
    }
    setPendingLoad(null);
    applyRules(set.rules, { id: set.id, name: set.name, description: set.description }, true);
    setRuleSetNotice({ ok: true, text: `Loaded "${set.name}" (${set.rules.length} rule${set.rules.length === 1 ? "" : "s"}).` });
  };

  const persistRuleSet = async (name: string, description: string, asNew: boolean) => {
    if (!projectId) return;
    setSavingSet(true);
    setSaveError(null);
    try {
      const saved = await saveRuleSet(projectId, name, rules, savedBy(), asNew ? undefined : ruleSet?.id, description,
        isDraftEditor && draftMode);
      update({
        ruleSet: { id: saved.id, name: saved.name, description: saved.description },
        ruleSetSnapshot: snapshotOf(rules),
      });
      setSaveDialog(null);
      setRuleSetNotice({ ok: true, text: `Saved "${saved.name}" in this project.` });
      refreshSavedSets();
    } catch (e: any) {
      setSaveError(errorText(e) || "Could not save the rule set");
    } finally {
      setSavingSet(false);
    }
  };

  const quickSave = () => {
    if (uploadBlocked()) return;
    if (ruleSet) persistRuleSet(ruleSet.name, ruleSet.description ?? "", false);
    else setSaveDialog("save");
  };

  const askDeleteRuleSet = () => {
    if (uploadBlocked()) return;
    setConfirmDelete({ kind: "ruleSet" });
  };

  const removeRuleSet = async () => {
    if (!projectId || !ruleSet) return;
    setDeleting(true);
    try {
      await deleteRuleSet(projectId, ruleSet.id, isDraftEditor && draftMode);
      setRuleSetNotice({ ok: true, text: `Deleted "${ruleSet.name}".` });
      update({ ruleSet: null, ruleSetSnapshot: null });
      refreshSavedSets();
    } catch (e: any) {
      setRuleSetNotice({ ok: false, text: errorText(e) || "Could not delete the rule set" });
    } finally {
      setDeleting(false);
      setConfirmDelete(null);
    }
  };

  const confirmTarget = confirmDelete?.kind === "rule" ? rules.find((r) => r.id === confirmDelete.id) ?? null : null;

  const exportRules = () => {
    const name = ruleSet?.name || "spreadsheet-rules";
    const fileName = `${name.replace(/[^\w.-]+/g, "_")}.rules.json`;
    downloadTextFile(fileName, ruleSetToJson(name, rules, ruleSet?.description));
  };

  const importRulesFile = async (picked: File | undefined) => {
    if (!picked) return;
    try {
      const parsed = ruleSetFromJson(await picked.text());
      applyRules(parsed.rules, null, false);
      setRuleSetNotice({
        ok: true,
        text: `Read ${parsed.rules.length} rule${parsed.rules.length === 1 ? "" : "s"} from ${picked.name}. Save them to keep them in this project.`,
      });
    } catch (e: any) {
      setRuleSetNotice({ ok: false, text: errorText(e) || "Could not read the rules file" });
    } finally {
      if (ruleFileInput.current) ruleFileInput.current.value = "";
    }
  };

  const allActive = rules.length > 0 && rules.every((r) => r.active);
  const activeRules = rules.filter((r) => r.active);
  const invalidActive = activeRules.filter((r) => validation[r.id] && !validation[r.id].valid);

  if (!projectId) {
    return (
      <div className="h-full flex items-center justify-center text-sm text-gray-500">
        Open an ontology to import spreadsheet data into it.
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col overflow-hidden" style={{ backgroundColor: "var(--color-background)" }}>
      <div className="flex items-center gap-3 px-4 py-2 border-b bg-theme-surface" style={{ borderColor: "var(--color-border)" }}>
        <FileSpreadsheet size={18} className="text-purple-600" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold">Spreadsheet Import</h2>
          <p className="text-[11px] text-gray-500 truncate">
            Turn spreadsheet rows into classes, individuals and property values with transformation rules.
          </p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          {workbook && (
            <span className="text-xs text-gray-600 truncate max-w-[280px]" title={workbook.fileName}>
              {workbook.fileName}
            </span>
          )}
          <input
            ref={fileInput}
            type="file"
            accept={SPREADSHEET_ACCEPT}
            className="hidden"
            onChange={(e) => handleFile(e.target.files?.[0])}
          />
          <button
            onClick={openFilePicker}
            disabled={uploading}
            className="flex items-center gap-1.5 px-3 py-1 text-xs rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50"
          >
            {uploading ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
            {workbook ? "Upload another file" : "Upload spreadsheet"}
          </button>
        </div>
      </div>

      {imported && (
        <div className="mx-4 mt-2 px-3 py-2 text-xs rounded bg-green-50 text-green-800 border border-green-200 flex items-center gap-2">
          <CheckCircle2 size={14} />
          <span>
            Imported {imported.axiomCount.toLocaleString()} axioms from {imported.fileName}
            {imported.draft ? " into your private draft" : ""}.
            {imported.alreadyPresent > 0 && <> {imported.alreadyPresent.toLocaleString()} were already in the ontology and were left as they were.</>}
            {" "}It is one entry in Change Assistant; Ctrl+Z (or Rollback there) removes the whole import.
          </span>
          <button onClick={() => setImported(null)} className="ml-auto p-0.5 rounded hover:bg-green-100" aria-label="Dismiss">
            <X size={12} />
          </button>
        </div>
      )}

      {restoredAt && workbook && (
        <div
          className="mx-4 mt-2 px-3 py-2 text-xs rounded flex items-center gap-2"
          style={{ background: "rgba(147, 51, 234, 0.12)", border: "1px solid rgba(147, 51, 234, 0.35)" }}
        >
          <FolderOpen size={14} className="text-purple-600" />
          <span>
            Restored your unsaved import of {workbook.fileName} from {new Date(restoredAt).toLocaleString()}. It stays here
            until you add it to the ontology or discard it.
          </span>
          <button onClick={discardWork} className="ml-auto px-2 py-0.5 rounded border hover:bg-gray-50">
            Discard
          </button>
          <button onClick={() => setRestoredAt(null)} className="p-0.5 rounded hover:bg-gray-100" aria-label="Dismiss">
            <X size={12} />
          </button>
        </div>
      )}

      {uploadError && (
        <div className="mx-4 mt-2 px-3 py-2 text-xs rounded bg-red-50 text-red-700 border border-red-200 flex items-center gap-2">
          <AlertCircle size={14} /> {uploadError}
        </div>
      )}

      {!workbook ? (
        <div
          className="flex-1 m-4 flex flex-col items-center justify-center border-2 border-dashed rounded-lg text-center"
          style={{ borderColor: "var(--color-border)" }}
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            if (uploadBlocked()) return;
            handleFile(e.dataTransfer.files?.[0]);
          }}
        >
          <FileSpreadsheet size={40} className="text-gray-300 mb-2" />
          <p className="text-sm text-gray-600">Drop an Excel (.xlsx, .xls) or CSV file here</p>
          <p className="text-xs text-gray-400 mb-3">or</p>
          <button
            onClick={openFilePicker}
            className="px-3 py-1 text-xs rounded border hover:bg-gray-50"
            disabled={uploading}
          >
            Choose a file
          </button>
        </div>
      ) : (
        <div className="flex-1 min-h-0 flex flex-col gap-2 p-4">
          <section className="flex-1 min-h-[160px] flex flex-col bg-theme-surface border rounded" style={{ borderColor: "var(--color-border)" }}>
            <div className="flex items-center gap-1 px-2 pt-1 border-b" style={{ borderColor: "var(--color-border)" }}>
              <span className="text-xs font-semibold mr-2">Workbook</span>
              {workbook.sheets.map((s) => (
                <button
                  key={s.name}
                  onClick={() => update({ activeSheet: s.name })}
                  className={`px-2.5 py-1 text-xs border-b-2 ${
                    s.name === activeSheet ? "border-purple-600 text-purple-600" : "border-transparent text-gray-500 hover:text-gray-800"
                  }`}
                >
                  {s.name}
                  <span className="ml-1 text-[10px] text-gray-400">{s.rowCount}×{s.columnCount}</span>
                </button>
              ))}
            </div>
            <div className="flex-1 min-h-0 p-2">{sheet && <SheetGrid sheet={sheet} highlight={highlight} />}</div>
          </section>

          <section className="flex-1 min-h-[180px] flex flex-col bg-theme-surface border rounded" style={{ borderColor: "var(--color-border)" }}>
            <div className="flex items-center gap-2 px-2 py-1.5 border-b" style={{ borderColor: "var(--color-border)" }}>
              <span className="text-xs font-semibold mr-2">Transformation Rules</span>
              <button onClick={openNewRule} className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border hover:bg-gray-50">
                <Plus size={12} /> Add
              </button>
              <button
                onClick={() => selectedRule && setEditing({ rule: selectedRule, isNew: false })}
                disabled={!selectedRule}
                className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border hover:bg-gray-50 disabled:opacity-40"
              >
                <Pencil size={12} /> Edit
              </button>
              <button
                onClick={() => selectedRule && duplicateRule(selectedRule)}
                disabled={!selectedRule}
                className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border hover:bg-gray-50 disabled:opacity-40"
              >
                <Copy size={12} /> Duplicate
              </button>
              <button
                onClick={() => selectedRule && setConfirmDelete({ kind: "rule", id: selectedRule.id })}
                disabled={!selectedRule}
                className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border hover:bg-gray-50 disabled:opacity-40"
              >
                <Trash2 size={12} /> Delete
              </button>

              <div className="ml-auto flex items-center gap-1.5 flex-wrap justify-end">
                <FolderOpen size={13} className="text-gray-400" />
                <select
                  value={ruleSet?.id ?? ""}
                  onChange={(e) => e.target.value && loadRuleSet(e.target.value)}
                  className="text-xs border rounded px-1.5 py-0.5 bg-theme-surface max-w-[200px]"
                  title="Load a rule set saved in this project"
                >
                  <option value="">{savedSets.length === 0 ? "No saved rule sets" : "Load saved rules…"}</option>
                  {savedSets.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name} ({s.rules.length})
                    </option>
                  ))}
                </select>
                {ruleSet && (
                  <span className={`text-[11px] ${unsavedChanges ? "text-amber-600" : "text-gray-500"}`}>
                    {unsavedChanges ? "unsaved changes" : "saved"}
                  </span>
                )}
                <button
                  onClick={quickSave}
                  disabled={rules.length === 0 || savingSet || (!!ruleSet && !unsavedChanges) || !ownsLoadedSet}
                  title={!ownsLoadedSet
                    ? `"${ruleSet?.name}" was saved by someone else. Use Save as to keep your own copy.`
                    : ruleSet ? `Save changes to "${ruleSet.name}"` : "Save these rules in the project"}
                  className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border hover:bg-gray-50 disabled:opacity-40"
                >
                  {savingSet && !saveDialog ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />} Save
                </button>
                <button
                  onClick={() => setSaveDialog("saveAs")}
                  disabled={rules.length === 0}
                  className="px-2 py-0.5 text-xs rounded border hover:bg-gray-50 disabled:opacity-40"
                >
                  Save as…
                </button>
                <button
                  onClick={exportRules}
                  disabled={rules.length === 0}
                  title="Download the rules as a JSON file (for sharing or review in a pull request)"
                  className="p-1 rounded border hover:bg-gray-50 disabled:opacity-40"
                  aria-label="Export rules"
                >
                  <Download size={12} />
                </button>
                <input
                  ref={ruleFileInput}
                  type="file"
                  accept=".json,application/json"
                  className="hidden"
                  onChange={(e) => importRulesFile(e.target.files?.[0])}
                />
                <button
                  onClick={() => ruleFileInput.current?.click()}
                  title="Read rules from a JSON file (this app's export, or Cellfie's rules file)"
                  className="p-1 rounded border hover:bg-gray-50"
                  aria-label="Import rules file"
                >
                  <FileUp size={12} />
                </button>
                {ruleSet && (
                  <button
                    onClick={askDeleteRuleSet}
                    disabled={!ownsLoadedSet}
                    title={ownsLoadedSet
                      ? `Delete "${ruleSet.name}" from the project`
                      : `"${ruleSet.name}" was saved by someone else, so you can't delete it.`}
                    className="p-1 rounded border hover:bg-red-50 text-red-600 disabled:opacity-40"
                    aria-label="Delete saved rule set"
                  >
                    <Trash2 size={12} />
                  </button>
                )}
              </div>
            </div>

            {ruleSetNotice && (
              <div
                className={`flex items-center gap-2 px-2 py-1 text-[11px] border-b ${
                  ruleSetNotice.ok ? "bg-green-50 text-green-800" : "bg-amber-50 text-amber-800"
                }`}
                style={{ borderColor: "var(--color-border)" }}
              >
                {ruleSetNotice.ok ? <CheckCircle2 size={12} /> : <AlertCircle size={12} />}
                {ruleSetNotice.text}
                <button
                  onClick={() => {
                    setRuleSetNotice(null);
                    setPendingLoad(null);
                  }}
                  className="ml-auto p-0.5 rounded hover:bg-black/5"
                  aria-label="Dismiss"
                >
                  <X size={11} />
                </button>
              </div>
            )}

            <div className="flex-1 min-h-0 overflow-auto">
              {rules.length === 0 ? (
                <div className="p-4 text-xs text-gray-500">
                  No rules yet. Click <b>Add</b> to say which cells to read and what to create from each row.
                </div>
              ) : (
                <table className="w-full text-xs">
                  <thead className="sticky top-0 bg-gray-50 text-gray-600">
                    <tr className="text-left">
                      <th className="px-2 py-1 w-8">
                        <input
                          type="checkbox"
                          checked={allActive}
                          onChange={() => update({ rules: rules.map((r) => ({ ...r, active: !allActive })) })}
                          title="Run all rules"
                        />
                      </th>
                      <th className="px-2 py-1 w-6" />
                      <th className="px-2 py-1">Sheet</th>
                      <th className="px-2 py-1">Start col</th>
                      <th className="px-2 py-1">End col</th>
                      <th className="px-2 py-1">Start row</th>
                      <th className="px-2 py-1">End row</th>
                      <th className="px-2 py-1 w-1/2">Rule</th>
                      <th className="px-2 py-1">Comment</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules.map((r) => {
                      const v = validation[r.id];
                      const missingSheet = !workbook.sheets.some((s) => s.name === r.sheetName);
                      return (
                        <tr
                          key={r.id}
                          onClick={() => {
                            setSelectedRuleId(r.id);
                            if (!missingSheet) update({ activeSheet: r.sheetName });
                          }}
                          onDoubleClick={() => setEditing({ rule: r, isNew: false })}
                          aria-selected={r.id === selectedRuleId}
                          className={`align-top border-t cursor-pointer ${
                            r.id === selectedRuleId ? "" : "hover:bg-gray-50"
                          } ${r.active ? "" : "opacity-50"}`}
                          style={
                            r.id === selectedRuleId
                              ? {
                                  borderColor: "var(--color-border)",
                                  background: "rgba(147, 51, 234, 0.18)",
                                  boxShadow: "inset 3px 0 0 #9333ea",
                                }
                              : { borderColor: "var(--color-border)" }
                          }
                        >
                          <td className="px-2 py-1" onClick={(e) => e.stopPropagation()}>
                            <input type="checkbox" checked={r.active} onChange={() => toggleActive(r.id)} title="Run this rule" />
                          </td>
                          <td className="px-1 py-1">
                            {v?.valid && !missingSheet && <CheckCircle2 size={14} className="text-green-600" />}
                            {(v && !v.valid) || missingSheet ? (
                              <span title={missingSheet ? `Sheet '${r.sheetName}' is not in this file` : v?.error}>
                                <AlertCircle size={14} className="text-red-600" />
                              </span>
                            ) : null}
                          </td>
                          <td className={`px-2 py-1 ${missingSheet ? "text-red-600" : ""}`}>{r.sheetName}</td>
                          <td className="px-2 py-1">{r.startColumn}</td>
                          <td className="px-2 py-1">{r.endColumn}</td>
                          <td className="px-2 py-1">{r.startRow}</td>
                          <td className="px-2 py-1">{r.endRow}</td>
                          <td className="px-2 py-1">
                            <pre className="font-mono text-[11px] whitespace-pre-wrap">{r.rule}</pre>
                            {v && !v.valid && <div className="text-[11px] text-red-600 mt-0.5">{v.error}</div>}
                          </td>
                          <td className="px-2 py-1 text-gray-500">{r.comment}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>

            <div className="flex items-center gap-2 px-2 py-1.5 border-t" style={{ borderColor: "var(--color-border)" }}>
              <span className="text-[11px] text-gray-500">
                {activeRules.length} of {rules.length} rule{rules.length === 1 ? "" : "s"} active
                {invalidActive.length > 0 && <span className="text-red-600"> · {invalidActive.length} with errors</span>}
              </span>
              {previewError && (
                <span className="ml-auto text-[11px] text-red-600 flex items-center gap-1 truncate" title={previewError}>
                  <AlertCircle size={12} /> {previewError}
                </span>
              )}
              <button
                onClick={runPreview}
                disabled={previewing || activeRules.length === 0 || !file}
                title={activeRules.length === 0 ? "Add or tick at least one rule" : "Preview the axioms these rules generate. Nothing is saved."}
                className={`${previewError ? "" : "ml-auto "}flex items-center gap-1.5 px-3 py-1 text-xs rounded bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed`}
              >
                {previewing ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />} Generate axioms
              </button>
            </div>
          </section>
        </div>
      )}

      {preview && workbook && (
        <ImportPreviewDialog
          preview={preview}
          fileName={workbook.fileName}
          committing={committing}
          commitError={commitError}
          draftMode={draftMode}
          onCommit={runCommit}
          onClose={() => setPreview(null)}
        />
      )}

      {confirmDelete?.kind === "rule" && confirmTarget && (
        <ConfirmDeleteDialog
          title="Delete rule?"
          message={
            <>
              <p>
                Delete the rule for <span className="font-mono">{confirmTarget.sheetName}!{confirmTarget.startColumn}{confirmTarget.startRow}</span>
                {confirmTarget.comment ? <> ({confirmTarget.comment})</> : null}?
              </p>
              <p className="mt-1 text-gray-500">It is removed from the list. A saved rule set keeps it until you save again.</p>
            </>
          }
          onCancel={() => setConfirmDelete(null)}
          onConfirm={() => {
            deleteRule(confirmTarget.id);
            setConfirmDelete(null);
          }}
        />
      )}

      {confirmDelete?.kind === "ruleSet" && ruleSet && (
        <ConfirmDeleteDialog
          title="Delete saved rule set?"
          message={
            <>
              <p>
                Delete <span className="font-semibold">"{ruleSet.name}"</span> from this project?
              </p>
              <p className="mt-1 text-gray-500">This can't be undone. The rules on screen stay, so you can save them again.</p>
            </>
          }
          busy={deleting}
          onCancel={() => setConfirmDelete(null)}
          onConfirm={removeRuleSet}
        />
      )}

      {saveDialog && (
        <SaveRuleSetDialog
          title={saveDialog === "saveAs" || !ruleSet ? "Save rules as a new rule set" : `Save "${ruleSet.name}"`}
          initialName={saveDialog === "saveAs" && ruleSet ? `${ruleSet.name} (copy)` : ruleSet?.name ?? ""}
          initialDescription={ruleSet?.description}
          saving={savingSet}
          error={saveError}
          onCancel={() => {
            setSaveDialog(null);
            setSaveError(null);
          }}
          onSave={(name, description) => persistRuleSet(name, description, saveDialog === "saveAs" || !ruleSet)}
        />
      )}

      {editing && workbook && (
        <RuleEditorDialog
          projectId={projectId}
          sheetNames={workbook.sheets.map((s) => s.name)}
          initial={editing.rule}
          isNew={editing.isNew}
          onCancel={() => setEditing(null)}
          onSave={saveRule}
        />
      )}
    </div>
  );
};

export default SpreadsheetImportPanel;
