import type { AppliedRange } from "../services/codeAssistantSession";

export type LineHighlight = { startCol: number; endCol: number } | "full";

export interface PendingAskAiHighlight {
  texts: string[];
  ranges: AppliedRange[];
}

export interface AskAiHighlightResult {
  lines: Map<number, LineHighlight>;
  firstLine: number | null;
  source: "ranges" | "text" | "none";
}

function formatsMatch(rangeFormat: string, viewFormat: string): boolean {
  const normalize = (value: string) => toCodeViewFormat(value) ?? value.trim().toLowerCase();
  return normalize(rangeFormat) === normalize(viewFormat);
}

function highlightFromRanges(
  ranges: AppliedRange[],
  viewFormat: string,
  pageStartLine: number,
  displayedLineCount: number,
): Map<number, LineHighlight> {
  const lines = new Map<number, LineHighlight>();
  for (const range of ranges) {
    if (!formatsMatch(range.format, viewFormat)) continue;
    for (let i = 0; i < range.lineCount; i++) {
      const relative = range.startLine + i - pageStartLine;
      if (relative >= 0 && relative < displayedLineCount) lines.set(relative + 1, "full");
    }
  }
  return lines;
}

function highlightFromText(texts: string[], contentLines: string[]): Map<number, LineHighlight> {
  const lines = new Map<number, LineHighlight>();
  for (const text of texts) {
    const textLines = text.split("\n");
    const firstLine = textLines[0]?.trim();
    if (!firstLine) continue;
    const lineIdx = contentLines.findIndex((l) => l.includes(firstLine));
    if (lineIdx === -1) continue;
    const startCol = contentLines[lineIdx].indexOf(firstLine);
    lines.set(lineIdx + 1, startCol >= 0 ? { startCol, endCol: startCol + firstLine.length } : "full");
    for (let i = 1; i < textLines.length; i++) lines.set(lineIdx + 1 + i, "full");
  }
  return lines;
}

export function computeAskAiHighlight(
  pending: PendingAskAiHighlight,
  content: string,
  viewFormat: string,
  pageStartLine: number,
): AskAiHighlightResult {
  const contentLines = content.split("\n");
  const hasMatchingRanges = pending.ranges.some((r) => formatsMatch(r.format, viewFormat));
  const lines = hasMatchingRanges
    ? highlightFromRanges(pending.ranges, viewFormat, pageStartLine, contentLines.length)
    : highlightFromText(pending.texts, contentLines);
  const firstLine = lines.size > 0 ? Math.min(...lines.keys()) : null;
  const source = lines.size === 0 ? "none" : hasMatchingRanges ? "ranges" : "text";
  return { lines, firstLine, source };
}

export type CodeViewFormat = "rdfxml" | "turtle" | "ntriples" | "owlxml" | "manchester" | "functional" | "jsonld";

const FORMAT_ALIASES: Record<string, CodeViewFormat> = {
  rdfxml: "rdfxml",
  xml: "rdfxml",
  owl: "rdfxml",
  turtle: "turtle",
  ttl: "turtle",
  ntriples: "ntriples",
  nt: "ntriples",
  owlxml: "owlxml",
  manchester: "manchester",
  manchestersyntax: "manchester",
  functional: "functional",
  functionalsyntax: "functional",
  jsonld: "jsonld",
};

export function toCodeViewFormat(value: string): CodeViewFormat | null {
  return FORMAT_ALIASES[value.trim().toLowerCase()] ?? null;
}
