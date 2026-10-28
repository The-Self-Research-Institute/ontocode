import type { TermRow } from './bindingFormat';

export interface ResultsSort {
  column: string;
  direction: 'asc' | 'desc';
}

export interface ResultsMeta {
  runId: string | null;
  totalRows: number;
  totalRowsExact: boolean;
  truncated: boolean;
  rowLimit: number;
  bytesCapped: boolean;
  storedRows: number;
}

export interface ResultsPageRequest {
  offset: number;
  limit: number;
  filter: string;
  sort: ResultsSort | null;
  exportAll?: boolean;
}

export const PAGE_SIZES = [25, 50, 100, 250, 500];
export const DEFAULT_PAGE_SIZE = 100;
export const FILTER_DEBOUNCE_MS = 200;
export const EXPIRED_MESSAGE = 'This result has expired. Run the query again.';

export function formatNumber(n: number): string {
  return Math.max(0, Math.trunc(n || 0)).toLocaleString('en-US');
}

export function resultsMeta(response: any, returned: number): ResultsMeta {
  const runId = typeof response?.runId === 'string' && response.runId ? response.runId : null;
  const storedRows = runId && typeof response?.storedRows === 'number' ? response.storedRows : returned;
  const totalRows = typeof response?.totalRows === 'number' && response.totalRows >= storedRows ? response.totalRows : storedRows;
  return {
    runId,
    totalRows,
    totalRowsExact: response?.totalRowsExact !== false,
    truncated: response?.truncated === true,
    rowLimit: typeof response?.rowLimit === 'number' ? response.rowLimit : storedRows,
    bytesCapped: response?.bytesCapped === true,
    storedRows
  };
}

export function truncationNotice(meta: ResultsMeta): string | null {
  if (!meta.truncated) return null;
  const tail = ' Narrow the query or add LIMIT.';
  if (meta.totalRows <= meta.storedRows) {
    return `Showing the first ${formatNumber(meta.storedRows)} rows; the rest were cut off.${tail}`;
  }
  const total = meta.totalRowsExact ? formatNumber(meta.totalRows) : `at least ${formatNumber(meta.totalRows)}`;
  const first = `Showing the first ${formatNumber(meta.storedRows)} of ${total} rows.`;
  return meta.bytesCapped ? `${first} The result was also cut to keep its size manageable.${tail}` : first + tail;
}

export function resultsLine(meta: ResultsMeta): string {
  if (!meta.truncated || meta.totalRows <= meta.storedRows) {
    return `${formatNumber(meta.storedRows)} ${meta.storedRows === 1 ? 'row' : 'rows'}`;
  }
  const total = meta.totalRowsExact ? formatNumber(meta.totalRows) : `at least ${formatNumber(meta.totalRows)}`;
  return `${formatNumber(meta.storedRows)} of ${total} rows`;
}

export function cellText(term: { value?: string } | undefined): string {
  return term?.value ?? '';
}

export function filterRows(rows: TermRow[], columns: string[], term: string): TermRow[] {
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

export function sortRows(rows: TermRow[], sort: ResultsSort | null): TermRow[] {
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

export function nextSort(current: ResultsSort | null, column: string): ResultsSort | null {
  if (!current || current.column !== column) return { column, direction: 'asc' };
  if (current.direction === 'asc') return { column, direction: 'desc' };
  return null;
}

export function sortArrow(current: ResultsSort | null, column: string): string {
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

export function showingText(filtered: number, stored: number, page: number, pageSize: number): string {
  if (filtered === 0) {
    return stored > 0 ? `Showing 0 of 0 (filtered from ${formatNumber(stored)})` : 'Showing 0 of 0';
  }
  const p = clampPage(page, filtered, pageSize);
  const from = p * pageSize + 1;
  const to = Math.min(filtered, from + pageSize - 1);
  const base = `Showing ${formatNumber(from)}-${formatNumber(to)} of ${formatNumber(filtered)}`;
  return filtered < stored ? `${base} (filtered from ${formatNumber(stored)})` : base;
}

export function localPage(
  rows: TermRow[], columns: string[], term: string, sort: ResultsSort | null, page: number, pageSize: number
): { rows: TermRow[]; filtered: number } {
  const view = sortRows(filterRows(rows, columns, term), sort);
  const p = clampPage(page, view.length, pageSize);
  return { rows: view.slice(p * pageSize, p * pageSize + pageSize), filtered: view.length };
}

export function pageKey(runId: string, term: string, sort: ResultsSort | null, page: number, pageSize: number): string {
  return [runId, term.trim(), sort ? `${sort.column}:${sort.direction}` : '', page, pageSize].join('|');
}

export function rowsPath(projectId: string, runId: string, req: ResultsPageRequest): string {
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
  return `/api/sparql/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/rows?${params.toString()}`;
}

export function exportNote(received: number, filtered: number): string | null {
  if (filtered <= received) return null;
  return `Downloaded the first ${formatNumber(received)} of ${formatNumber(filtered)} matching rows. Add a filter to get the rest.`;
}
