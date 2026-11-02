import type { TermRow, RdfTerm } from './bindingFormat';

export interface ExportTable {
  header: string[];
  rows: string[][];
}

export function buildExportTable(vars: string[], rows: TermRow[], includeTypes: boolean): ExportTable {
  const header: string[] = [];
  for (const v of vars) {
    header.push(v);
    if (includeTypes) header.push(`${v}_datatype`, `${v}_lang`);
  }
  const body = rows.map((row) => {
    const cells: string[] = [];
    for (const v of vars) {
      const term = row[v];
      cells.push(term?.value ?? '');
      if (includeTypes) cells.push(term?.datatype ?? '', term?.['xml:lang'] ?? '');
    }
    return cells;
  });
  return { header, rows: body };
}

export function escapeCsv(val: string): string {
  const str = String(val ?? '');
  if (str.includes('"') || str.includes(',') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

export function toCsv(table: ExportTable): string {
  return [table.header, ...table.rows].map((r) => r.map(escapeCsv).join(',')).join('\n');
}

function escapeTsv(val: string): string {
  return String(val ?? '').replace(/[\t\r\n]+/g, ' ');
}

export function toTsv(table: ExportTable, withHeader = false): string {
  const lines = withHeader ? [table.header, ...table.rows] : table.rows;
  return lines.map((r) => r.map(escapeTsv).join('\t')).join('\n');
}

export function buildJsonBindings(vars: string[], rows: TermRow[], includeTypes: boolean): Record<string, RdfTerm>[] {
  return rows.map((row) => {
    const out: Record<string, RdfTerm> = {};
    for (const v of vars) {
      const term = row[v];
      if (!term) continue;
      const entry: RdfTerm = { type: term.type, value: term.value };
      if (includeTypes) {
        if (term.datatype) entry.datatype = term.datatype;
        if (term['xml:lang']) entry['xml:lang'] = term['xml:lang'];
      }
      out[v] = entry;
    }
    return out;
  });
}
