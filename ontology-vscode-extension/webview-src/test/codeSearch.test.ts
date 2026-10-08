import { describe, it, expect } from "vitest";
import { searchLines, searchScopeNoteFor } from "../components/codeSearch";

function runToCompletion(content: string, query: string, caseSensitive = false) {
  const queue: Array<() => void> = [];
  const progress: number[] = [];
  let result: number[] | undefined;
  let clock = 0;
  searchLines(
    content,
    query,
    caseSensitive,
    { onProgress: (p) => progress.push(p), onDone: (m) => (result = m) },
    { schedule: (run) => queue.push(run), now: () => (clock += 1), sliceBudgetMs: 5 },
  );
  while (queue.length > 0) {
    queue.shift()!();
  }
  return { result, progress };
}

describe("searchLines", () => {
  it("finds a match far past line 10,000", () => {
    const lines = Array.from({ length: 20_000 }, (_, i) => `:Thing${i} a owl:Class .`);
    lines[15_000] = ':Pizza rdfs:label "Pizza Margherita" .';

    const { result } = runToCompletion(lines.join("\n"), "pizza margherita");

    expect(result).toEqual([15_000]);
  });

  it("works through a long file in several slices and reports progress along the way", () => {
    const content = Array.from({ length: 50_000 }, (_, i) => `line ${i}`).join("\n");

    const { result, progress } = runToCompletion(content, "line 49999");

    expect(result).toEqual([49_999]);
    expect(progress.length).toBeGreaterThan(0);
    expect(progress.every((p) => p >= 0 && p < 100)).toBe(true);
  });

  it("respects case sensitivity", () => {
    const content = "Pizza\npizza\nPIZZA";

    expect(runToCompletion(content, "pizza", true).result).toEqual([1]);
    expect(runToCompletion(content, "pizza", false).result).toEqual([0, 1, 2]);
  });

  it("handles Windows line endings", () => {
    expect(runToCompletion("a\r\nb\r\nc", "c").result).toEqual([2]);
  });

  it("stops without reporting when cancelled", () => {
    const queue: Array<() => void> = [];
    let done = false;
    const search = searchLines("a\nb", "b", false, { onProgress: () => {}, onDone: () => (done = true) }, {
      schedule: (run) => queue.push(run),
    });

    search.cancel();
    while (queue.length > 0) {
      queue.shift()!();
    }

    expect(done).toBe(false);
  });

  it("stops when the editor reports the search was cleared", () => {
    const queue: Array<() => void> = [];
    let done = false;
    searchLines("a\nb", "b", false, { onProgress: () => {}, onDone: () => (done = true) }, {
      schedule: (run) => queue.push(run),
      isCancelled: () => true,
    });

    while (queue.length > 0) {
      queue.shift()!();
    }

    expect(done).toBe(false);
  });
});

describe("searchScopeNoteFor", () => {
  it("says which lines are searched in paged mode", () => {
    expect(searchScopeNoteFor({ startLine: 10_000, lineCount: 10_000, totalLines: 45_000 }, null)).toBe(
      `Searching lines ${(10_001).toLocaleString()}–${(20_000).toLocaleString()} of ${(45_000).toLocaleString()} only`,
    );
  });

  it("says only the preview is searched when the file was cut down", () => {
    expect(searchScopeNoteFor(null, { previewLines: 10_000 })).toBe(
      `Searching the first ${(10_000).toLocaleString()} lines only`,
    );
  });

  it("says nothing when the whole file is loaded", () => {
    expect(searchScopeNoteFor(null, null)).toBeUndefined();
  });
});
