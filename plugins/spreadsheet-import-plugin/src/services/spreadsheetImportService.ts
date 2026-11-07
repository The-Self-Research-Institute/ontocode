import apiClient from "./apiClient";

export interface SpreadsheetSheet {
  name: string;
  rowCount: number;
  columnCount: number;
  columns: string[];
  rows: string[][];
  truncated: boolean;
}

export interface ParsedSpreadsheet {
  fileName: string;
  format: "xlsx" | "xls" | "csv";
  sheets: SpreadsheetSheet[];
}

export interface TransformationRule {
  id: string;
  sheetName: string;
  startColumn: string;
  endColumn: string;
  startRow: string;
  endRow: string;
  rule: string;
  comment: string;
  active: boolean;
}

export interface RuleValidationResult {
  index: number;
  valid: boolean;
  entityKind?: string;
  error?: string;
  line?: number;
  column?: number;
}

export const SPREADSHEET_ACCEPT = ".xlsx,.xls,.csv";

const base = (projectId: string) =>
  `/api/ontology/spreadsheet-import/${encodeURIComponent(projectId)}`;

export async function parseSpreadsheet(
  projectId: string,
  file: File,
  previewRows = 200,
): Promise<ParsedSpreadsheet> {
  const formData = new FormData();
  formData.append("file", file, file.name);
  const res = await apiClient.post<{ success: boolean; data: ParsedSpreadsheet; error?: string }>(
    `${base(projectId)}/parse?previewRows=${previewRows}`,
    formData,
  );
  if (!res?.success) {
    throw new Error(res?.error || "Could not read the spreadsheet");
  }
  return res.data;
}

export interface PreviewAxiom {
  type: string;
  text: string;
  rule: number;
  sheet: string;
  row: number;
}

export interface PreviewIssue {
  severity: "ERROR" | "WARNING";
  rule: number;
  sheet: string | null;
  row: number | null;
  cell: string | null;
  message: string;
  line: number | null;
  column: number | null;
}

export interface ImportPreview {
  axiomCount: number;
  countsByType: Record<string, number>;
  rowsProcessed: number;
  errorCount: number;
  warningCount: number;
  canCommit: boolean;
  triples: number | null;
  alreadyPresent: number | null;
  nothingNew: boolean;
  axioms: PreviewAxiom[];
  axiomsTruncated: boolean;
  issues: PreviewIssue[];
  issuesTruncated: boolean;
}

const toRuleSpecs = (rules: TransformationRule[]) =>
  rules.map(({ sheetName, startColumn, endColumn, startRow, endRow, rule, comment, active }) => ({
    sheetName,
    startColumn,
    endColumn,
    startRow,
    endRow,
    rule,
    comment,
    active,
  }));

export async function previewImport(
  projectId: string,
  file: File,
  rules: TransformationRule[],
  draft: boolean,
  userId: string,
  limit = 5000,
): Promise<ImportPreview> {
  const formData = new FormData();
  formData.append("file", file, file.name);
  formData.append("rules", JSON.stringify(toRuleSpecs(rules)));
  const params = new URLSearchParams({ limit: String(limit), draft: String(draft), userId });
  const res = await apiClient.post<{ success: boolean; data: ImportPreview; error?: string }>(
    `${base(projectId)}/preview?${params}`,
    formData,
  );
  if (!res?.success) {
    throw new Error(res?.error || "Could not generate the preview");
  }
  return res.data;
}

export interface ImportCommitResult {
  importId: string;
  axiomCount: number;
  triplesAdded: number;
  alreadyPresent: number;
  draft: boolean;
}

export async function commitImport(
  projectId: string,
  file: File,
  rules: TransformationRule[],
  draft: boolean,
  userId: string,
  username: string,
): Promise<ImportCommitResult> {
  const formData = new FormData();
  formData.append("file", file, file.name);
  formData.append("rules", JSON.stringify(toRuleSpecs(rules)));
  const params = new URLSearchParams({ draft: String(draft), userId, username });
  const res = await apiClient.post<{ success: boolean; data: ImportCommitResult; error?: string }>(
    `${base(projectId)}/commit?${params}`,
    formData,
  );
  if (!res?.success) {
    throw new Error(res?.error || "The import failed");
  }
  window.dispatchEvent(
    new CustomEvent("ontology:mutated", { detail: { projectId, ops: ["spreadsheetImport"] } }),
  );
  return res.data;
}

export async function validateRules(projectId: string, rules: string[]): Promise<RuleValidationResult[]> {
  const res = await apiClient.post<{ success: boolean; data: RuleValidationResult[]; error?: string }>(
    `${base(projectId)}/rules/validate`,
    { rules },
  );
  if (!res?.success) {
    throw new Error(res?.error || "Could not check the rules");
  }
  return res.data;
}

export interface SavedRuleSet {
  id: string;
  name: string;
  description?: string | null;
  rules: Omit<TransformationRule, "id">[];
  createdBy?: string | null;
  createdByUserId?: string | null;
  updatedBy?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

const ruleSetsBase = (projectId: string) => `${base(projectId)}/rulesets`;

export async function listRuleSets(projectId: string): Promise<SavedRuleSet[]> {
  const res = await apiClient.get<{ success: boolean; data: SavedRuleSet[]; error?: string }>(ruleSetsBase(projectId));
  if (!res?.success) throw new Error(res?.error || "Could not load the saved rule sets");
  return res.data ?? [];
}

export async function saveRuleSet(
  projectId: string,
  name: string,
  rules: TransformationRule[],
  user: string,
  id?: string,
  description?: string,
  draft = false,
): Promise<SavedRuleSet> {
  const body = { name, description: description ?? null, rules: toRuleSpecs(rules), user };
  const query = draft ? "?draft=true" : "";
  const res = id
    ? await apiClient.put<{ success: boolean; data: SavedRuleSet; error?: string }>(
        `${ruleSetsBase(projectId)}/${encodeURIComponent(id)}${query}`,
        body,
      )
    : await apiClient.post<{ success: boolean; data: SavedRuleSet; error?: string }>(
        `${ruleSetsBase(projectId)}${query}`,
        body,
      );
  if (!res?.success) throw new Error(res?.error || "Could not save the rule set");
  return res.data;
}

export async function deleteRuleSet(projectId: string, id: string, draft = false): Promise<void> {
  const res = await apiClient.delete<{ success: boolean; error?: string }>(
    `${ruleSetsBase(projectId)}/${encodeURIComponent(id)}${draft ? "?draft=true" : ""}`,
  );
  if (!res?.success) throw new Error(res?.error || "Could not delete the rule set");
}

export const RULE_SET_FILE_FORMAT = "ontocode-spreadsheet-rules";

export function ruleSetToJson(name: string, rules: TransformationRule[], description?: string | null): string {
  return JSON.stringify(
    { format: RULE_SET_FILE_FORMAT, version: 1, name, description: description ?? undefined, rules: toRuleSpecs(rules) },
    null,
    2,
  );
}

export function ruleSetFromJson(text: string): { name: string | null; description: string | null; rules: Omit<TransformationRule, "id">[] } {
  let data: any;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error("This file is not valid JSON");
  }
  const list: any[] | undefined = Array.isArray(data)
    ? data
    : Array.isArray(data?.rules)
      ? data.rules
      : Array.isArray(data?.Collections)
        ? data.Collections
        : undefined;
  if (!list || list.length === 0) {
    throw new Error("No rules found in this file");
  }
  const str = (v: unknown, fallback = "") => (v === undefined || v === null ? fallback : String(v));
  const rules = list.map((r, i) => {
    if (!r || typeof r.rule !== "string" || !r.rule.trim()) {
      throw new Error(`Rule ${i + 1} in the file has no rule text`);
    }
    return {
      sheetName: str(r.sheetName),
      startColumn: str(r.startColumn, "A"),
      endColumn: str(r.endColumn, "A"),
      startRow: str(r.startRow, "2"),
      endRow: str(r.endRow, "+"),
      rule: r.rule,
      comment: str(r.comment),
      active: r.active !== false,
    };
  });
  return { name: typeof data?.name === "string" ? data.name : null, description: data?.description ?? null, rules };
}

export function downloadTextFile(fileName: string, text: string): void {
  const blob = new Blob([text], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
