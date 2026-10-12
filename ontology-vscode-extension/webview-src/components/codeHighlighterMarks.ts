export type ChangedLineRange = { startCol: number; endCol: number } | "full";

const ERROR_LINE_PATTERNS = [
  /\bline[:\s]+(\d+)/gi,
  /at line (\d+)/gi,
  /\[(\d+),\s*\d+\]/g,
  /line (\d+),/gi,
  /\brow[:\s]+(\d+)/gi,
];

const CHANGED_MARK_STYLE = "background-color:rgba(147,51,234,0.35);color:inherit;padding:0 1px;border-radius:2px";

export function parseErrorLines(errorStr: string): number[] {
  if (!errorStr) return [];
  const found = new Set<number>();
  for (const pattern of ERROR_LINE_PATTERNS) {
    const re = new RegExp(pattern.source, pattern.flags);
    let m: RegExpExecArray | null;
    while ((m = re.exec(errorStr)) !== null) {
      const n = parseInt(m[1], 10);
      if (n > 0) found.add(n);
    }
  }
  return Array.from(found);
}

export function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function markChangedRange(processedLine: string, rawLine: string, range: ChangedLineRange | undefined): string {
  if (!range || range === "full") return processedLine;
  const changedText = rawLine.slice(range.startCol, range.endCol);
  if (!changedText) return processedLine;
  const changedRegex = new RegExp(escapeRegex(changedText));
  let replaced = false;
  return processedLine
    .split(/(<[^>]+>)/g)
    .map((part) => {
      if ((part.startsWith("<") && part.endsWith(">")) || replaced) return part;
      return part.replace(changedRegex, (match) => {
        replaced = true;
        return `<mark style="${CHANGED_MARK_STYLE}">${match}</mark>`;
      });
    })
    .join("");
}
