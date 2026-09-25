import { useCallback, useEffect, useRef, useState, type MutableRefObject, type RefObject } from "react";
import type { AppliedRange } from "../../../services/codeAssistantSession";
import type { CodeHighlighterHandle } from "../../CodeHighlighter";
import type { EditorSelectionContext } from "../../codeSelection";
import {
  computeAskAiHighlight,
  toCodeViewFormat,
  type CodeViewFormat,
  type LineHighlight,
  type PendingAskAiHighlight,
} from "../../codeViewHighlight";

const HIGHLIGHT_MS = 4000;

interface CodeViewPageWindow {
  startLine: number;
  lineCount: number;
}

interface AskAiCodeViewSyncOptions {
  codeViewContent: string;
  codeViewFormat: CodeViewFormat;
  codeViewPage: CodeViewPageWindow | null;
  busy: boolean;
  hasUnsavedEditsRef: MutableRefObject<boolean>;
  highlighterRef: RefObject<CodeHighlighterHandle | null>;
  highlightClearTimeoutRef: MutableRefObject<ReturnType<typeof setTimeout> | null>;
  setHighlightedLineNumbers: (lines: Map<number, LineHighlight> | undefined) => void;
  fetchCodeViewContent: (format: CodeViewFormat, forceRefresh?: boolean, forceReload?: boolean, keepEditorMounted?: boolean) => Promise<void>;
  loadCodeViewPage: (startLine: number, keepEditorMounted?: boolean) => Promise<void>;
}

type OptionsRef = MutableRefObject<AskAiCodeViewSyncOptions>;
type ShowLines = (lines: Map<number, LineHighlight>, firstLine: number) => void;

function useApplyHighlight(options: AskAiCodeViewSyncOptions, optionsRef: OptionsRef, showLines: ShowLines) {
  const { codeViewContent, codeViewFormat, codeViewPage, busy } = options;
  const pendingHighlightRef = useRef<PendingAskAiHighlight | null>(null);
  const reloadStartedAtRef = useRef<number | null>(null);

  useEffect(() => {
    if (busy) return;
    const pending = pendingHighlightRef.current;
    if (!pending) return;
    pendingHighlightRef.current = null;
    const reloadStartedAt = reloadStartedAtRef.current;
    reloadStartedAtRef.current = null;
    const started = performance.now();
    const result = computeAskAiHighlight(pending, codeViewContent, codeViewFormat, codeViewPage?.startLine ?? 0);
    if (reloadStartedAt !== null) {
      console.log(
        `[Dashboard] [PERF] Ask AI apply refresh: reload=${Math.round(started - reloadStartedAt)}ms ` +
          `highlight=${Math.round(performance.now() - started)}ms source=${result.source} lines=${result.lines.size}`,
      );
    }
    if (result.firstLine !== null) showLines(result.lines, result.firstLine);
  }, [codeViewContent, codeViewFormat, codeViewPage, busy, showLines]);

  return useCallback((changedTexts: string[], appliedRanges: AppliedRange[]) => {
    const { hasUnsavedEditsRef, codeViewPage: page, codeViewFormat: current, fetchCodeViewContent, loadCodeViewPage } =
      optionsRef.current;
    if (hasUnsavedEditsRef.current) {
      console.log("[Dashboard] Ask AI apply finished while Code View has unsaved edits; not reloading");
      return;
    }
    pendingHighlightRef.current = { texts: changedTexts, ranges: appliedRanges };
    reloadStartedAtRef.current = performance.now();
    if (page) {
      void loadCodeViewPage(page.startLine, true);
    } else {
      void fetchCodeViewContent(current, false, true, true);
    }
  }, []);
}

function useCodeViewJump(options: AskAiCodeViewSyncOptions, optionsRef: OptionsRef, showLines: ShowLines) {
  const { codeViewContent, codeViewFormat, codeViewPage, busy } = options;
  const pendingJumpRef = useRef<{ format: CodeViewFormat; startLine: number } | null>(null);
  const [jumpTick, setJumpTick] = useState(0);

  useEffect(() => {
    if (busy) return;
    const jump = pendingJumpRef.current;
    if (!jump || jump.format !== codeViewFormat) return;
    pendingJumpRef.current = null;
    const relative = jump.startLine - (codeViewPage?.startLine ?? 0);
    if (relative >= 0) showLines(new Map([[relative + 1, "full" as const]]), relative + 1);
  }, [codeViewContent, codeViewFormat, codeViewPage, busy, jumpTick, showLines]);

  return useCallback((targetFormat: string, startLine: number) => {
    const format = toCodeViewFormat(targetFormat);
    if (!format) return;
    const { codeViewFormat: current, codeViewPage: page, fetchCodeViewContent, loadCodeViewPage } = optionsRef.current;
    pendingJumpRef.current = { format, startLine };
    if (format !== current) {
      void fetchCodeViewContent(format);
    } else if (page && (startLine < page.startLine || startLine >= page.startLine + page.lineCount)) {
      void loadCodeViewPage(startLine, true);
    } else {
      setJumpTick((tick) => tick + 1);
    }
  }, []);
}

export function useAskAiCodeViewSync(options: AskAiCodeViewSyncOptions) {
  const { codeViewContent, codeViewFormat } = options;
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const [selection, setSelection] = useState<EditorSelectionContext | null>(null);

  const showLines = useCallback((lines: Map<number, LineHighlight>, firstLine: number) => {
    const { highlighterRef, highlightClearTimeoutRef, setHighlightedLineNumbers } = optionsRef.current;
    setHighlightedLineNumbers(lines);
    highlighterRef.current?.goToLine(firstLine);
    if (highlightClearTimeoutRef.current) clearTimeout(highlightClearTimeoutRef.current);
    highlightClearTimeoutRef.current = setTimeout(() => setHighlightedLineNumbers(undefined), HIGHLIGHT_MS);
  }, []);

  useEffect(() => {
    setSelection(null);
  }, [codeViewContent, codeViewFormat]);

  const handleAskAiApplySuccess = useApplyHighlight(options, optionsRef, showLines);
  const handleShowInCodeView = useCodeViewJump(options, optionsRef, showLines);

  return { handleAskAiApplySuccess, handleShowInCodeView, selection, setSelection };
}
