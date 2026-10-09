export interface LineSearchCallbacks {
  onProgress: (percent: number) => void;
  onDone: (matchingLines: number[]) => void;
}

export interface LineSearchOptions {
  sliceBudgetMs?: number;
  schedule?: (run: () => void) => void;
  now?: () => number;
  isCancelled?: () => boolean;
}

const LINES_BETWEEN_CLOCK_CHECKS = 1024;

export const SEARCH_RESULTS_WINDOW = 500;

export function searchResultWindow(
  total: number,
  currentIndex: number,
  size: number = SEARCH_RESULTS_WINDOW,
): { start: number; end: number } {
  if (total <= size) {
    return { start: 0, end: total };
  }
  const current = Math.min(Math.max(currentIndex, 0), total - 1);
  const start = Math.floor(current / size) * size;
  return { start, end: Math.min(start + size, total) };
}

export function searchScopeNoteFor(
  page: { startLine: number; lineCount: number; totalLines: number } | null | undefined,
  truncation: { previewLines: number } | null | undefined,
): string | undefined {
  if (page) {
    const last = page.startLine + page.lineCount;
    return `Searching lines ${(page.startLine + 1).toLocaleString()}–${last.toLocaleString()} of ${page.totalLines.toLocaleString()} only`;
  }
  if (truncation) {
    return `Searching the first ${truncation.previewLines.toLocaleString()} lines only`;
  }
  return undefined;
}

export function searchLines(
  content: string,
  query: string,
  caseSensitive: boolean,
  callbacks: LineSearchCallbacks,
  options: LineSearchOptions = {},
): { cancel: () => void } {
  const lines = content.split(/\r?\n/);
  const needle = caseSensitive ? query : query.toLowerCase();
  const sliceBudgetMs = options.sliceBudgetMs ?? 12;
  const schedule = options.schedule ?? ((run) => setTimeout(run, 0));
  const now = options.now ?? (() => performance.now());
  const matches: number[] = [];
  let next = 0;
  let cancelled = false;
  const stopped = () => cancelled || (options.isCancelled?.() ?? false);

  const runSlice = () => {
    if (stopped()) {
      return;
    }
    const deadline = now() + sliceBudgetMs;
    while (next < lines.length) {
      const line = caseSensitive ? lines[next] : lines[next].toLowerCase();
      if (line.includes(needle)) {
        matches.push(next);
      }
      next++;
      if (next % LINES_BETWEEN_CLOCK_CHECKS === 0 && now() >= deadline) {
        break;
      }
    }
    if (next < lines.length) {
      callbacks.onProgress(Math.floor((next / lines.length) * 100));
      schedule(runSlice);
    } else {
      callbacks.onDone(matches);
    }
  };

  schedule(runSlice);
  return {
    cancel: () => {
      cancelled = true;
    },
  };
}
