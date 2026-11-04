export type SqwrlRow = Record<string, any>;

export interface SqwrlResponseLike {
  columnNames?: string[] | null;
  rows?: SqwrlRow[] | null;
  rowCount?: number;
  executionTimeMs?: number;
  totalRows?: number;
  totalRowsExact?: boolean;
  truncated?: boolean;
  rowLimit?: number;
  bytesCapped?: boolean;
}

export interface SqwrlMeta {
  returned: number;
  total: number;
  exact: boolean;
  truncated: boolean;
  bytesCapped: boolean;
  rowLimit: number;
}

export interface SqwrlSort {
  column: string;
  direction: 'asc' | 'desc';
}

export const SQWRL_PAGE_SIZES = [25, 50, 100, 250];
export const SQWRL_DEFAULT_PAGE_SIZE = 50;
export const SQWRL_FILTER_DEBOUNCE_MS = 200;

export function formatNumber(n: number): string {
  return Math.max(0, Math.trunc(n || 0)).toLocaleString('en-US');
}

export function sqwrlMeta(response: SqwrlResponseLike): SqwrlMeta {
  const rows = response.rows ?? [];
  const returned = rows.length;
  const total = typeof response.totalRows === 'number' && response.totalRows >= returned ? response.totalRows : returned;
  return {
    returned,
    total,
    exact: response.totalRowsExact !== false,
    truncated: response.truncated === true,
    bytesCapped: response.bytesCapped === true,
    rowLimit: typeof response.rowLimit === 'number' ? response.rowLimit : returned,
  };
}

export function cellText(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value);
}

export function filterRows(rows: SqwrlRow[], columns: string[], term: string): SqwrlRow[] {
  const needle = term.trim().toLowerCase();
  if (!needle) return rows;
  return rows.filter((row) => columns.some((c) => cellText(row[c]).toLowerCase().includes(needle)));
}

const NUMBER_PATTERN = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

function asNumber(text: string): number | null {
  const t = text.trim();
  if (!NUMBER_PATTERN.test(t)) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

export function compareCells(a: string, b: string): number {
  const na = asNumber(a);
  const nb = asNumber(b);
  if (na !== null && nb !== null) return na - nb;
  if (na !== null) return -1;
  if (nb !== null) return 1;
  const la = a.toLowerCase();
  const lb = b.toLowerCase();
  return la < lb ? -1 : la > lb ? 1 : 0;
}

export function sortRows(rows: SqwrlRow[], sort: SqwrlSort | null): SqwrlRow[] {
  if (!sort) return rows;
  const factor = sort.direction === 'desc' ? -1 : 1;
  return rows
    .map((row, index) => ({ row, index }))
    .sort((x, y) => {
      const c = compareCells(cellText(x.row[sort.column]), cellText(y.row[sort.column])) * factor;
      return c !== 0 ? c : x.index - y.index;
    })
    .map((entry) => entry.row);
}

export function nextSort(current: SqwrlSort | null, column: string): SqwrlSort | null {
  if (!current || current.column !== column) return { column, direction: 'asc' };
  if (current.direction === 'asc') return { column, direction: 'desc' };
  return null;
}

export function sortArrow(current: SqwrlSort | null, column: string): string {
  if (!current || current.column !== column) return '';
  return current.direction === 'asc' ? '▲' : '▼';
}

export function pageCount(total: number, pageSize: number): number {
  if (total <= 0 || pageSize <= 0) return 1;
  return Math.ceil(total / pageSize);
}

export function clampPage(page: number, total: number, pageSize: number): number {
  return Math.min(Math.max(0, Math.trunc(page) || 0), pageCount(total, pageSize) - 1);
}

export function pageSlice<T>(rows: T[], page: number, pageSize: number): T[] {
  const p = clampPage(page, rows.length, pageSize);
  return rows.slice(p * pageSize, p * pageSize + pageSize);
}

export function showingText(filtered: number, returned: number, page: number, pageSize: number): string {
  if (filtered === 0) {
    return returned > 0 ? `Showing 0 of 0 (filtered from ${formatNumber(returned)})` : 'Showing 0 of 0';
  }
  const p = clampPage(page, filtered, pageSize);
  const from = p * pageSize + 1;
  const to = Math.min(filtered, from + pageSize - 1);
  const base = `Showing ${formatNumber(from)}-${formatNumber(to)} of ${formatNumber(filtered)}`;
  return filtered < returned ? `${base} (filtered from ${formatNumber(returned)})` : base;
}

export function resultsLine(meta: SqwrlMeta, executionTimeMs: number): string {
  const total = meta.exact ? formatNumber(meta.total) : `more than ${formatNumber(meta.total)}`;
  const count = meta.truncated ? `${formatNumber(meta.returned)} of ${total}` : formatNumber(meta.returned);
  const noun = !meta.truncated && meta.returned === 1 ? 'result' : 'results';
  return `${count} ${noun} in ${executionTimeMs}ms`;
}

export function truncationNotice(meta: SqwrlMeta): string | null {
  if (!meta.truncated) return null;
  const total = meta.exact ? formatNumber(meta.total) : `more than ${formatNumber(meta.total)}`;
  const first = `Showing the first ${formatNumber(meta.returned)} of ${total} results.`;
  const tail = ' Narrow the query, add a filter in the query, or use sqwrl:limit.';
  if (meta.bytesCapped) {
    return `${first} The result was also cut to keep its size manageable.${tail}`;
  }
  return first + tail;
}

export function csvCell(value: unknown): string {
  let text = cellText(value);
  if (/^[=+\-@]/.test(text)) text = `'${text}`;
  if (/[",\r\n]/.test(text)) text = `"${text.replace(/"/g, '""')}"`;
  return text;
}

export function buildCsv(rows: SqwrlRow[], columns: string[]): string {
  const lines = [columns.map(csvCell).join(',')];
  for (const row of rows) lines.push(columns.map((c) => csvCell(row[c])).join(','));
  return lines.join('\r\n') + '\r\n';
}

function tsvCell(value: unknown): string {
  return cellText(value).replace(/[\t\r\n]+/g, ' ');
}

export function buildTsv(rows: SqwrlRow[], columns: string[]): string {
  const lines = [columns.map(tsvCell).join('\t')];
  for (const row of rows) lines.push(columns.map((c) => tsvCell(row[c])).join('\t'));
  return lines.join('\n') + '\n';
}

export interface SqwrlPageRequest {
  offset: number;
  limit: number;
  filter: string;
  sort: SqwrlSort | null;
  exportAll?: boolean;
}

export function sqwrlRowsPath(projectId: string, runId: string, req: SqwrlPageRequest, draftMode: boolean): string {
  const params = new URLSearchParams();
  if (req.exportAll) {
    params.set('export', 'true');
  } else {
    params.set('offset', String(Math.max(0, Math.trunc(req.offset))));
    params.set('limit', String(Math.max(1, Math.trunc(req.limit))));
  }
  if (req.filter.trim()) params.set('filter', req.filter.trim());
  if (req.sort) {
    params.set('sort', req.sort.column);
    params.set('dir', req.sort.direction);
  }
  if (draftMode) params.set('draft', 'true');
  return `/api/swrl/${encodeURIComponent(projectId)}/sqwrl/runs/${encodeURIComponent(runId)}/rows?${params.toString()}`;
}

export function localPage(rows: SqwrlRow[], columns: string[], term: string, sort: SqwrlSort | null, page: number, pageSize: number): { rows: SqwrlRow[]; filtered: number } {
  const view = sortRows(filterRows(rows, columns, term), sort);
  return { rows: pageSlice(view, page, pageSize), filtered: view.length };
}

export function pageKey(runId: string, term: string, sort: SqwrlSort | null, page: number, pageSize: number): string {
  return [runId, term.trim(), sort ? `${sort.column}:${sort.direction}` : '', page, pageSize].join('|');
}

export const SQWRL_EXPIRED_MESSAGE = 'This result has expired. Run the query again.';
