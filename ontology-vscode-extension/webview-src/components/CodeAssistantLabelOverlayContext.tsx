import React, { createContext, useContext } from "react";
import type { PrefixMapping } from "./codeAssistantLabelOverlay";

export interface CodeAssistantLabelOverlayValue {
  enabled: boolean;
  setEnabled: (value: boolean) => void;
  labelMap: Map<string, string>;
  prefixMappings: PrefixMapping[];
}

const DEFAULT_VALUE: CodeAssistantLabelOverlayValue = {
  enabled: false,
  setEnabled: () => {},
  labelMap: new Map(),
  prefixMappings: [],
};

const CodeAssistantLabelOverlayContext = createContext<CodeAssistantLabelOverlayValue>(DEFAULT_VALUE);

export const CodeAssistantLabelOverlayProvider: React.FC<{
  value: CodeAssistantLabelOverlayValue;
  children: React.ReactNode;
}> = ({ value, children }) => (
  <CodeAssistantLabelOverlayContext.Provider value={value}>{children}</CodeAssistantLabelOverlayContext.Provider>
);

export function useCodeAssistantLabelOverlay(): CodeAssistantLabelOverlayValue {
  return useContext(CodeAssistantLabelOverlayContext);
}
