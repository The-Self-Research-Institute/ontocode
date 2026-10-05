import { useEffect, useRef, type RefObject } from "react";
import { selectionFromDom, selectionFromTextarea, type EditorSelectionContext } from "./codeSelection";

export function useCodeSelectionReporting(
  editorRef: RefObject<HTMLElement | null>,
  textareaRef: RefObject<HTMLTextAreaElement | null>,
  onSelectionChange?: (selection: EditorSelectionContext | null) => void,
): void {
  const callbackRef = useRef(onSelectionChange);
  callbackRef.current = onSelectionChange;
  useEffect(() => {
    const report = (event: Event) => {
      const target = event.target instanceof Node ? event.target : null;
      const textarea = textareaRef.current;
      const editor = editorRef.current;
      if (textarea && target && (target === textarea || textarea.contains(target))) {
        callbackRef.current?.(selectionFromTextarea(textarea.value, textarea.selectionStart, textarea.selectionEnd));
      } else if (editor && target && editor.contains(target)) {
        callbackRef.current?.(selectionFromDom(window.getSelection(), editor));
      }
    };
    document.addEventListener("mouseup", report);
    document.addEventListener("keyup", report);
    return () => {
      document.removeEventListener("mouseup", report);
      document.removeEventListener("keyup", report);
    };
  }, [editorRef, textareaRef]);
}

export function useUnsavedChangesReporting(hasUnsavedChanges: boolean, onChange?: (hasUnsavedChanges: boolean) => void): void {
  const callbackRef = useRef(onChange);
  callbackRef.current = onChange;
  useEffect(() => {
    callbackRef.current?.(hasUnsavedChanges);
  }, [hasUnsavedChanges]);
  useEffect(() => () => callbackRef.current?.(false), []);
}
