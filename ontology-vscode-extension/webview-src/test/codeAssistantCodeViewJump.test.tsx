import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import { useAskAiCodeViewSync } from "../components/dashboard-parts/hooks/useAskAiCodeViewSync";

it("opens the diff's format and loads the page containing its target line", () => {
  const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
  const original = environment.IS_REACT_ACT_ENVIRONMENT;
  environment.IS_REACT_ACT_ENVIRONMENT = true;
  const root = createRoot(document.createElement("div"));
  const loadCodeViewPage = vi.fn(async () => {});
  const fetchCodeViewContent = vi.fn(async () => {});
  const setHighlightedLineNumbers = vi.fn();
  const highlightClearTimeoutRef = { current: null as ReturnType<typeof setTimeout> | null };
  type Options = Parameters<typeof useAskAiCodeViewSync>[0];
  let jump: ReturnType<typeof useAskAiCodeViewSync>["handleShowInCodeView"];

  function Fixture({ format, startLine }: { format: Options["codeViewFormat"]; startLine: number }) {
    jump = useAskAiCodeViewSync({
      codeViewContent: "Content",
      codeViewFormat: format,
      codeViewPage: { startLine, lineCount: 10, totalLines: 100 },
      busy: false,
      hasUnsavedEditsRef: { current: false },
      highlighterRef: { current: null },
      highlightClearTimeoutRef,
      setHighlightedLineNumbers,
      fetchCodeViewContent,
      loadCodeViewPage,
    }).handleShowInCodeView;
    return null;
  }

  try {
    act(() => root.render(<Fixture format="rdfxml" startLine={0} />));
    act(() => jump("turtle", 63));
    expect(fetchCodeViewContent).toHaveBeenCalledWith("turtle");
    expect(setHighlightedLineNumbers).not.toHaveBeenCalled();

    act(() => root.render(<Fixture format="turtle" startLine={0} />));
    expect(loadCodeViewPage).toHaveBeenCalledExactlyOnceWith(63, true);
    expect(setHighlightedLineNumbers).not.toHaveBeenCalled();

    act(() => root.render(<Fixture format="turtle" startLine={60} />));
    expect(setHighlightedLineNumbers).toHaveBeenCalledWith(new Map([[4, "full"]]));
  } finally {
    if (highlightClearTimeoutRef.current) clearTimeout(highlightClearTimeoutRef.current);
    act(() => root.unmount());
    environment.IS_REACT_ACT_ENVIRONMENT = original;
  }
});
