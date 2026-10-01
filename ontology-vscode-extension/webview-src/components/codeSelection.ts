export interface EditorSelectionContext {
  startLine: number;
  endLine: number;
  text: string;
}

export const MAX_SELECTION_CHARS = 4000;

function lineIndexAt(value: string, offset: number): number {
  let line = 0;
  for (let i = 0; i < offset && i < value.length; i++) {
    if (value.charCodeAt(i) === 10) line++;
  }
  return line;
}

export function selectionFromTextarea(value: string, selectionStart: number, selectionEnd: number): EditorSelectionContext | null {
  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);
  if (end <= start) return null;
  const text = value.slice(start, end);
  if (!text.trim()) return null;
  const startLine = lineIndexAt(value, start);
  return { startLine, endLine: startLine + lineIndexAt(text, text.length), text };
}

function lineIndexOf(node: Node | null): number | null {
  const element = node instanceof Element ? node : node?.parentElement ?? null;
  const line = element?.closest("[data-line-idx]");
  const raw = line?.getAttribute("data-line-idx");
  return raw == null ? null : Number(raw);
}

export function selectionFromDom(selection: Selection | null, container: Element | null): EditorSelectionContext | null {
  if (!selection || selection.isCollapsed || !container) return null;
  if (!container.contains(selection.anchorNode) || !container.contains(selection.focusNode)) return null;
  const anchor = lineIndexOf(selection.anchorNode);
  const focus = lineIndexOf(selection.focusNode);
  if (anchor === null || focus === null) return null;
  const text = selection.toString();
  if (!text.trim()) return null;
  return { startLine: Math.min(anchor, focus), endLine: Math.max(anchor, focus), text };
}

export function describeSelectionForPrompt(
  selection: EditorSelectionContext,
  format: string,
  pageStartLine: number,
): string {
  const first = selection.startLine + pageStartLine;
  const last = selection.endLine + pageStartLine;
  const clipped = selection.text.length > MAX_SELECTION_CHARS;
  const text = clipped ? selection.text.slice(0, MAX_SELECTION_CHARS) : selection.text;
  const range = first === last ? `line ${first + 1}` : `lines ${first + 1}-${last + 1}`;
  const note = clipped
    ? ` The selection is longer than ${MAX_SELECTION_CHARS} characters, so only the start is shown; use read_context with range "${format}:${first}-${last - first + 1}" for the rest.`
    : "";
  return `The user has selected ${range} of the ${format} Code View (zero-based range "${format}:${first}-${last - first + 1}").${note}\nSelected text:\n${text}`;
}

export function withSelection(
  text: string,
  selection: (EditorSelectionContext & { format: string; pageStartLine: number }) | null,
): { loopText: string; actionContext: string } {
  if (!selection) return { loopText: text, actionContext: JSON.stringify({}) };
  const offset = selection.pageStartLine;
  return {
    loopText: `${text}\n\n${describeSelectionForPrompt(selection, selection.format, offset)}`,
    actionContext: JSON.stringify({
      selection: { format: selection.format, startLine: selection.startLine + offset, endLine: selection.endLine + offset },
    }),
  };
}
