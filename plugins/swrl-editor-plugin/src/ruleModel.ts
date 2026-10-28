export const COMPARISONS = [
  'greaterThan',
  'lessThan',
  'greaterThanOrEqual',
  'lessThanOrEqual',
  'equal',
  'notEqual',
] as const;

export const OPERATIONS = ['add', 'subtract', 'multiply', 'divide', 'mod'] as const;

export type Comparison = typeof COMPARISONS[number];
export type Operation = typeof OPERATIONS[number];
export type ValueKind = 'number' | 'text' | 'boolean' | 'variable' | 'none';

export interface RuleCondition {
  property: string;
  variable: string;
  comparison: Comparison | '';
  value: string;
  valueKind: ValueKind;
}

export interface RuleCalculation {
  result: string;
  op: Operation;
  left: string;
  right: string;
}

export interface SimpleRuleModel {
  kind: 'simple';
  when: { cls: string; variable: string };
  conditions: RuleCondition[];
  calculations: RuleCalculation[];
  then: { cls: string };
}

export interface AdvancedRuleModel {
  kind: 'advanced';
  reason: string;
}

export type RuleModel = SimpleRuleModel | AdvancedRuleModel;

const NAME = /^[^\s(),^?"<>{}\[\]]+$/;
const VAR = /^\?([A-Za-z_][A-Za-z0-9_]*)$/;
const NUMBER = /^-?\d+(\.\d+)?$/;
const PLAIN_VAR = /^[A-Za-z_][A-Za-z0-9_]*$/;

class Unsupported extends Error {}

function fail(reason: string): never {
  throw new Unsupported(reason);
}

function splitTop(text: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let inString = false;
  let current = '';
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      current += ch;
      if (ch === '\\' && i + 1 < text.length) {
        current += text[++i];
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      current += ch;
    } else if (ch === '(') {
      depth++;
      current += ch;
    } else if (ch === ')') {
      depth--;
      if (depth < 0) fail('The rule has unbalanced brackets.');
      current += ch;
    } else if (depth === 0 && text.startsWith(separator, i)) {
      if (separator === '^' && text[i + 1] === '^') {
        current += '^^';
        i++;
        continue;
      }
      parts.push(current);
      current = '';
      i += separator.length - 1;
    } else {
      current += ch;
    }
  }
  if (inString) fail('The rule has an unclosed quote.');
  if (depth !== 0) fail('The rule has unbalanced brackets.');
  parts.push(current);
  return parts;
}

interface ParsedAtom {
  name: string;
  args: string[];
}

function parseAtom(raw: string): ParsedAtom {
  const text = raw.trim();
  const open = text.indexOf('(');
  if (open <= 0 || !text.endsWith(')')) fail('Part of the rule is not a simple atom such as Name(?x).');
  const name = text.slice(0, open).trim();
  if (!NAME.test(name)) fail('The rule uses a class or property expression the builder cannot show.');
  const inner = text.slice(open + 1, -1);
  if (inner.trim() === '') fail('An atom in the rule has no arguments.');
  const args = splitTop(inner, ',').map(a => a.trim());
  if (args.some(a => a === '')) fail('An atom in the rule has an empty argument.');
  return { name, args };
}

function unquote(token: string): string | null {
  let body = token;
  if (token.startsWith('"') && token.endsWith('"^^xsd:string')) {
    body = token.slice(0, -'^^xsd:string'.length);
  }
  if (body.length < 2 || !body.startsWith('"') || !body.endsWith('"')) return null;
  const inner = body.slice(1, -1);
  let out = '';
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i];
    if (ch === '\\') {
      const next = inner[i + 1];
      if (next !== '"' && next !== '\\') return null;
      out += next;
      i++;
    } else if (ch === '"') {
      return null;
    } else {
      out += ch;
    }
  }
  return out;
}

function quote(value: string): string {
  return '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function builtinName(name: string): string | null {
  return name.startsWith('swrlb:') ? name.slice('swrlb:'.length) : null;
}

function operandOf(token: string): string {
  const v = VAR.exec(token);
  if (v) return '?' + v[1];
  if (NUMBER.test(token)) return token;
  return fail('An arithmetic step uses something other than a variable or a number.');
}

export function parseRuleText(text: string): RuleModel {
  try {
    return { kind: 'simple', ...parseSimple(text) };
  } catch (e) {
    if (e instanceof Unsupported) return { kind: 'advanced', reason: e.message };
    return { kind: 'advanced', reason: 'The rule text could not be read.' };
  }
}

function parseSimple(text: string): Omit<SimpleRuleModel, 'kind'> {
  if (typeof text !== 'string' || text.trim() === '') fail('The rule is empty.');
  const arrowParts = splitTop(text, '->');
  if (arrowParts.length !== 2) fail('The rule must have one WHEN part, an arrow, and one THEN part.');
  const bodyParts = splitTop(arrowParts[0], '^');
  const headParts = splitTop(arrowParts[1], '^');
  if (headParts.length !== 1) fail('The rule has more than one conclusion.');

  const bodyAtoms = bodyParts.map(parseAtom);
  const headAtom = parseAtom(headParts[0]);

  const classAtoms: ParsedAtom[] = [];
  const propertyAtoms: { atom: ParsedAtom; variable: string }[] = [];
  const builtins: ParsedAtom[] = [];

  for (const atom of bodyAtoms) {
    if (builtinName(atom.name) !== null) {
      builtins.push(atom);
    } else if (atom.args.length === 1) {
      classAtoms.push(atom);
    } else if (atom.args.length === 2) {
      const first = VAR.exec(atom.args[0]);
      const second = VAR.exec(atom.args[1]);
      if (!first) fail('A property in the rule is used on something other than a variable.');
      if (!second) fail('A property in the rule is matched against a fixed value or an individual, which needs the advanced editor.');
      propertyAtoms.push({ atom, variable: second[1] });
    } else {
      fail('An atom in the rule has more than two arguments.');
    }
  }

  if (classAtoms.length !== 1) fail('The rule must have exactly one class condition in the WHEN part.');
  const rootMatch = VAR.exec(classAtoms[0].args[0]);
  if (!rootMatch) fail('The class condition is not on a variable.');
  const root = rootMatch[1];
  if (builtinName(headAtom.name) !== null) fail('The conclusion is a built-in, not a class.');
  if (headAtom.args.length !== 1) fail('The conclusion is not a single class.');
  const headVar = VAR.exec(headAtom.args[0]);
  if (!headVar || headVar[1] !== root) fail('The conclusion is not about the same variable as the WHEN class.');

  const propertyVars = new Map<string, string>();
  for (const { atom, variable } of propertyAtoms) {
    const subject = VAR.exec(atom.args[0]);
    if (!subject || subject[1] !== root) {
      fail('A property in the rule belongs to something other than the main variable (this looks like an object property or a chain).');
    }
    if (variable === root) fail('A property points back at the main variable.');
    if (propertyVars.has(variable)) fail('A variable is read from more than one property.');
    propertyVars.set(variable, atom.name);
  }

  const calculations: RuleCalculation[] = [];
  const comparisonAtoms: ParsedAtom[] = [];
  const calcVars = new Set<string>();
  for (const atom of builtins) {
    const short = builtinName(atom.name) as string;
    if ((OPERATIONS as readonly string[]).includes(short)) {
      if (atom.args.length !== 3) fail('An arithmetic step does not have a result and two inputs.');
      const result = VAR.exec(atom.args[0]);
      if (!result) fail('An arithmetic step does not store its result in a variable.');
      const name = result[1];
      if (name === root || propertyVars.has(name) || calcVars.has(name)) {
        fail('An arithmetic step reuses a variable name that is already taken.');
      }
      const left = operandOf(atom.args[1]);
      const right = operandOf(atom.args[2]);
      for (const operand of [left, right]) {
        if (operand.startsWith('?')) {
          const v = operand.slice(1);
          if (!propertyVars.has(v) && !calcVars.has(v)) {
            fail('An arithmetic step uses a variable that has not been read or calculated before it.');
          }
        }
      }
      calcVars.add(name);
      calculations.push({ result: name, op: short as Operation, left, right });
    } else if ((COMPARISONS as readonly string[]).includes(short)) {
      if (atom.args.length !== 2) fail('A comparison does not have exactly two sides.');
      comparisonAtoms.push(atom);
    } else {
      fail('The rule uses the built-in ' + atom.name + ', which the builder does not support.');
    }
  }

  const used = new Set<string>();
  const byVariable = new Map<string, RuleCondition[]>();
  const calcConditions: RuleCondition[] = [];
  for (const atom of comparisonAtoms) {
    const short = builtinName(atom.name) as Comparison;
    const left = VAR.exec(atom.args[0]);
    if (!left) fail('A comparison has a fixed value on its left side.');
    const lv = left[1];
    const isProp = propertyVars.has(lv);
    if (!isProp && !calcVars.has(lv)) fail('A comparison uses a variable that is not read from a property or calculated.');
    used.add(lv);
    const right = atom.args[1];
    let value: string;
    let valueKind: ValueKind;
    const rv = VAR.exec(right);
    if (rv) {
      if (!propertyVars.has(rv[1]) && !calcVars.has(rv[1])) {
        fail('A comparison uses a variable that is not read from a property or calculated.');
      }
      value = rv[1];
      valueKind = 'variable';
      used.add(value);
    } else if (NUMBER.test(right)) {
      value = right;
      valueKind = 'number';
    } else if (right === 'true' || right === 'false') {
      value = right;
      valueKind = 'boolean';
    } else {
      const s = unquote(right);
      if (s === null) fail('A comparison uses a value type the builder does not support.');
      value = s;
      valueKind = 'text';
    }
    const condition: RuleCondition = {
      property: isProp ? (propertyVars.get(lv) as string) : '',
      variable: lv,
      comparison: short,
      value,
      valueKind,
    };
    if (isProp) {
      const list = byVariable.get(lv) || [];
      list.push(condition);
      byVariable.set(lv, list);
    } else {
      calcConditions.push(condition);
    }
  }
  for (const c of calculations) {
    for (const operand of [c.left, c.right]) {
      if (operand.startsWith('?')) used.add(operand.slice(1));
    }
  }

  const conditions: RuleCondition[] = [];
  for (const { atom, variable } of propertyAtoms) {
    if (!used.has(variable)) {
      fail('A property is read but never compared or used, so it cannot be told apart from an object property.');
    }
    const list = byVariable.get(variable);
    if (list && list.length > 0) {
      conditions.push(...list);
    } else {
      conditions.push({ property: atom.name, variable, comparison: '', value: '', valueKind: 'none' });
    }
  }
  conditions.push(...calcConditions);

  return {
    when: { cls: classAtoms[0].name, variable: root },
    conditions,
    calculations,
    then: { cls: headAtom.name },
  };
}

function valueText(c: RuleCondition): string {
  if (c.valueKind === 'variable') return '?' + c.value;
  if (c.valueKind === 'text') return quote(c.value);
  return c.value;
}

export function serializeRule(model: SimpleRuleModel): string {
  const root = '?' + model.when.variable;
  const parts: string[] = [`${model.when.cls}(${root})`];
  const emitted = new Set<string>();
  const propertyConditions = model.conditions.filter(c => c.property !== '');
  const calcConditions = model.conditions.filter(c => c.property === '');

  for (const c of propertyConditions) {
    const key = c.property + '|' + c.variable;
    if (emitted.has(key)) continue;
    emitted.add(key);
    parts.push(`${c.property}(${root}, ?${c.variable})`);
    for (const d of propertyConditions) {
      if (d.property === c.property && d.variable === c.variable && d.comparison !== '') {
        parts.push(`swrlb:${d.comparison}(?${d.variable}, ${valueText(d)})`);
      }
    }
  }
  for (const k of model.calculations) {
    parts.push(`swrlb:${k.op}(?${k.result}, ${k.left}, ${k.right})`);
  }
  for (const c of calcConditions) {
    if (c.comparison !== '') parts.push(`swrlb:${c.comparison}(?${c.variable}, ${valueText(c)})`);
  }
  return `${parts.join(' ^ ')} -> ${model.then.cls}(${root})`;
}

export function isValidVariableName(name: string): boolean {
  return PLAIN_VAR.test(name);
}

export interface ListedRule {
  id: string;
  ruleName: string;
  ruleText: string;
  comment?: string | null;
  category?: string | null;
  enabled: boolean;
  source?: string | null;
  readOnly?: boolean;
}

export interface UnmigratedListedRule {
  id?: string;
  ruleName: string;
  ruleText?: string;
  error?: string;
}

export function withDraft(path: string, draftMode: boolean): string {
  if (!draftMode) return path;
  return path + (path.includes('?') ? '&draft=true' : '?draft=true');
}

export function normalizeRulesResponse(res: unknown): { rules: ListedRule[]; unmigrated: UnmigratedListedRule[] } {
  if (Array.isArray(res)) return { rules: res as ListedRule[], unmigrated: [] };
  const body = (res || {}) as { rules?: unknown; unmigrated?: unknown; content?: unknown };
  const rules = Array.isArray(body.rules) ? body.rules : Array.isArray(body.content) ? body.content : [];
  const unmigrated = Array.isArray(body.unmigrated) ? body.unmigrated : [];
  return { rules: rules as ListedRule[], unmigrated: unmigrated as UnmigratedListedRule[] };
}

export function unmigratedNotice(count: number): string {
  return `${count} saved rule${count === 1 ? '' : 's'} could not be moved into the ontology`;
}

export function hasDisabledRule(rules: { enabled: boolean }[]): boolean {
  return rules.some(r => !r.enabled);
}

export function isImportedRule(rule: { readOnly?: boolean; source?: string | null }): boolean {
  return rule.readOnly === true || (typeof rule.source === 'string' && rule.source !== '');
}

export function sourceLabel(rule: { source?: string | null }): string {
  return rule.source ? `From ${rule.source}` : '';
}

export function localCopyBody(rule: ListedRule, takenNames: Iterable<string>): {
  ruleName: string;
  ruleText: string;
  comment: string;
  category: string;
  enabled: boolean;
} {
  const taken = new Set(takenNames);
  const base = `${rule.ruleName} (local copy)`;
  let name = base;
  for (let n = 2; taken.has(name); n++) name = `${rule.ruleName} (local copy ${n})`;
  return {
    ruleName: name,
    ruleText: rule.ruleText,
    comment: `Copied from ${rule.source ?? 'an imported ontology'}`,
    category: rule.category || '',
    enabled: true,
  };
}

export interface RuleFormValues {
  ruleName: string;
  ruleText: string;
  comment?: string;
  category?: string;
  enabled: boolean;
}

export function ruleWriteBody(form: RuleFormValues, label: string): {
  ruleName: string;
  ruleText: string;
  comment: string;
  category: string;
  enabled: boolean;
  origin: { source: string; label: string };
} {
  return {
    ruleName: form.ruleName.trim(),
    ruleText: form.ruleText,
    comment: form.comment || '',
    category: form.category || '',
    enabled: form.enabled,
    origin: { source: 'swrl-rules', label },
  };
}

export function friendlyRuleError(e: any, fallback: string): string {
  const status: number | undefined = e?.status;
  const data = e?.data;
  const errors: unknown[] = Array.isArray(data?.errors) ? data.errors : [];
  const suggestions: unknown[] = Array.isArray(data?.suggestions) ? data.suggestions : [];
  if (status === 400 || errors.length > 0) {
    const head = errors.length > 0 ? errors.map(String).join(' ') : (data?.message || e?.message || 'The rule has a problem.');
    const tail = suggestions.length > 0 ? ' Try this: ' + suggestions.map(String).join(' ') : '';
    return head + tail;
  }
  if (status === 409) {
    const text = String(data?.message || e?.message || '');
    if (/duplicate|already|exists|name/i.test(text) && !/stale|changed since|precondition/i.test(text)) {
      return 'Another rule already has this name. Choose a different name.';
    }
    return 'The rules changed since you opened them. Refresh the list, then try again.';
  }
  if (status === 404) return 'This rule no longer exists. Refresh the list.';
  if (status === 503) return 'The rule service is not available right now. Try again in a moment.';
  return data?.message || e?.message || fallback;
}

export interface SkippedRow {
  sheet?: string | null;
  row?: number | null;
  ruleName?: string | null;
  reason?: string | null;
  kind?: string | null;
  suggestions?: string[] | null;
}

export interface ImportedRow {
  id?: string;
  ruleName?: string;
  row?: number | null;
}

export interface ImportReport {
  read: number;
  imported: ImportedRow[];
  skipped: SkippedRow[];
  duplicates: number;
  invalid: number;
  dryRun?: boolean;
  previewStamp?: string | null;
  replayed?: boolean;
  changeSetId?: string | null;
  stale?: boolean;
  message?: string;
}

export interface SkippedRowView {
  key: string;
  sheet: string;
  row: string;
  ruleName: string;
  reason: string;
  suggestions: string;
}

export function newImportId(): string {
  const c: any = (globalThis as any).crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  const hex = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${hex(3)}-${hex(12)}`;
}

export interface ImportSession {
  fileName: string;
  fileSize: number;
  lastModified: number;
  importId: string;
}

export function sameImportFile(
  session: ImportSession | null,
  file: { name: string; size: number; lastModified?: number }
): boolean {
  return !!session
    && session.fileName === file.name
    && session.fileSize === file.size
    && session.lastModified === (file.lastModified ?? 0);
}

export function importSessionFor(
  previous: ImportSession | null,
  file: { name: string; size: number; lastModified?: number }
): ImportSession {
  if (previous && sameImportFile(previous, file)) return previous;
  return { fileName: file.name, fileSize: file.size, lastModified: file.lastModified ?? 0, importId: newImportId() };
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

export function importSummaryLine(report: Pick<ImportReport, 'read' | 'imported' | 'duplicates' | 'invalid'>, dryRun: boolean): string {
  const count = Array.isArray(report.imported) ? report.imported.length : 0;
  return [
    plural(report.read ?? 0, 'row read', 'rows read'),
    `${count} ${dryRun ? 'would import' : 'imported'}`,
    plural(report.duplicates ?? 0, 'duplicate', 'duplicates'),
    `${report.invalid ?? 0} invalid`,
  ].join(' · ');
}

export function skippedRowsView(skipped: SkippedRow[] | null | undefined): SkippedRowView[] {
  return (skipped || []).map((s, i) => ({
    key: `${s.sheet ?? ''}-${s.row ?? ''}-${i}`,
    sheet: s.sheet ? String(s.sheet) : '',
    row: s.row == null ? '' : String(s.row),
    ruleName: s.ruleName ? String(s.ruleName) : '',
    reason: s.reason ? String(s.reason) : 'Skipped',
    suggestions: Array.isArray(s.suggestions) ? s.suggestions.map(String).join(', ') : '',
  }));
}

export function importButtonLabel(report: Pick<ImportReport, 'imported'>): string {
  const n = Array.isArray(report.imported) ? report.imported.length : 0;
  return `Import ${plural(n, 'rule', 'rules')}`;
}

export function canRunImport(report: Pick<ImportReport, 'imported'> | null, running: boolean): boolean {
  return !!report && !running && report.imported.length > 0;
}

export function importOutcomeNotice(report: ImportReport): string {
  if (report.replayed) return 'This import was already done';
  return importSummaryLine(report, false);
}

export function isStaleImport(e: any): boolean {
  return e?.status === 409 && !!e?.data && (e.data.stale === true || Array.isArray(e.data.imported));
}

export function staleImportReport(e: any): ImportReport | null {
  if (!isStaleImport(e)) return null;
  const d = e.data;
  return {
    read: Number(d.read) || 0,
    imported: Array.isArray(d.imported) ? d.imported : [],
    skipped: Array.isArray(d.skipped) ? d.skipped : [],
    duplicates: Number(d.duplicates) || 0,
    invalid: Number(d.invalid) || 0,
    dryRun: true,
    previewStamp: d.previewStamp ?? null,
    stale: true,
    message: String(d.message || 'The rules changed since the preview. Review the new result, then import again.'),
  };
}

export function importErrorMessage(e: any): string {
  const status = e?.status;
  const msg = e?.data?.message || e?.message || '';
  if (status === 413) return 'This file is too large to import.';
  if (status === 400) return msg || 'This file could not be read.';
  return friendlyRuleError(e, 'The import failed - please try again.');
}

export function isImportFile(name: string): boolean {
  return /\.(xlsx|json)$/i.test(name);
}

export function applySavedRule<T extends { id: string; ruleName: string }>(
  rules: T[],
  selectedId: string | null,
  res: Partial<T> | null | undefined,
  form: { ruleName: string; ruleText: string; comment?: string; category?: string; enabled: boolean }
): { rules: T[]; saved: T } {
  const current = rules.find(r => r.id === (selectedId ?? res?.id));
  const merged = {
    ...(current || {}),
    ruleText: form.ruleText,
    comment: form.comment || '',
    category: form.category || '',
    enabled: form.enabled,
    ...(res || {}),
    ruleName: (res && res.ruleName) || form.ruleName.trim(),
    id: (res && res.id) || selectedId || '',
  } as unknown as T;
  const exists = rules.some(r => r.id === merged.id || r.id === selectedId);
  const next = exists
    ? rules.map(r => (r.id === merged.id || r.id === selectedId ? merged : r))
    : [...rules, merged];
  return { rules: next, saved: merged };
}

export const RESULTS_PAGE_SIZE = 50;
export const EXPIRED_RUN_MESSAGE = 'This result has expired. Run Execute again.';

export type AxiomKind = 'conclusions' | 'structure';

export interface RunCounts {
  conclusions: number;
  structure: number;
  omittedTrivial?: number;
}

export interface KeyedAxiom {
  description: string;
}

export function isPagedRun(res: { runId?: unknown; counts?: unknown } | null | undefined): boolean {
  if (!res || typeof res.runId !== 'string' || !res.runId) return false;
  const c = res.counts as Partial<RunCounts> | null | undefined;
  return !!c && typeof c.conclusions === 'number' && typeof c.structure === 'number';
}

export function formatCount(n: number): string {
  return Math.max(0, Math.trunc(n || 0)).toLocaleString('en-US');
}

function pluralize(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}

export function derivedSummary(counts: RunCounts): string {
  const total = counts.conclusions + counts.structure;
  const base = `${formatCount(total)} derived: ${formatCount(counts.conclusions)} ${pluralize(counts.conclusions, 'rule conclusion', 'rule conclusions')}, ${formatCount(counts.structure)} structure`;
  const omitted = counts.omittedTrivial ?? 0;
  if (omitted > 0) {
    return `${base} (${formatCount(omitted)} trivial same-individual ${pluralize(omitted, 'fact', 'facts')} hidden)`;
  }
  return base;
}

export function pageStart(offset: number, total: number, limit: number = RESULTS_PAGE_SIZE): number {
  if (total <= 0) return 0;
  const clamped = Math.min(Math.max(0, offset), total - 1);
  return Math.floor(clamped / limit) * limit;
}

export function nextOffset(offset: number, total: number, limit: number = RESULTS_PAGE_SIZE): number | null {
  const next = offset + limit;
  return next < total ? next : null;
}

export function previousOffset(offset: number, limit: number = RESULTS_PAGE_SIZE): number | null {
  return offset > 0 ? Math.max(0, offset - limit) : null;
}

export function pageRangeText(offset: number, shown: number, total: number): string {
  if (total <= 0 || shown <= 0) return 'No results';
  return `Showing ${formatCount(offset + 1)}-${formatCount(offset + shown)} of ${formatCount(total)}`;
}

export function pageCacheKey(runId: string, kind: AxiomKind, query: string, offset: number): string {
  return `${runId}|${kind}|${query}|${offset}`;
}

export function axiomsPagePath(
  projectId: string,
  runId: string,
  opts: { offset: number; limit: number; kind: AxiomKind; query: string },
  draftMode: boolean
): string {
  const params = new URLSearchParams();
  params.set('offset', String(opts.offset));
  params.set('limit', String(opts.limit));
  params.set('kind', opts.kind);
  const q = opts.query.trim();
  if (q) params.set('q', q);
  return withDraft(`/api/ontology/${encodeURIComponent(projectId)}/rules/execute/${encodeURIComponent(runId)}/axioms?${params.toString()}`, draftMode);
}

export function axiomKey(ax: KeyedAxiom): string {
  return ax.description;
}

export function toggleSelection<T extends KeyedAxiom>(sel: Map<string, T>, ax: T): Map<string, T> {
  const next = new Map(sel);
  const key = axiomKey(ax);
  if (next.has(key)) next.delete(key);
  else next.set(key, ax);
  return next;
}

export function addToSelection<T extends KeyedAxiom>(sel: Map<string, T>, axioms: T[]): Map<string, T> {
  const next = new Map(sel);
  axioms.forEach(ax => next.set(axiomKey(ax), ax));
  return next;
}

export function allSelected<T extends KeyedAxiom>(sel: Map<string, T>, axioms: T[]): boolean {
  return axioms.length > 0 && axioms.every(ax => sel.has(axiomKey(ax)));
}

export function isExpiredRunError(e: any): boolean {
  return e?.status === 410;
}

export function pageErrorMessage(e: any): string {
  if (isExpiredRunError(e)) return EXPIRED_RUN_MESSAGE;
  const detail = e?.data?.message || e?.message;
  return detail ? `Could not load this page: ${detail}` : 'Could not load this page. Try again.';
}
