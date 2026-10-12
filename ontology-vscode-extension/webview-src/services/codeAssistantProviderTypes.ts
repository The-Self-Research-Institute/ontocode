import { LlmProvider, LlmRequestError } from "./LlmInsightsService";

export interface JsonSchema {
  type: string;
  properties?: Record<string, JsonSchema>;
  items?: JsonSchema;
  required?: string[];
  description?: string;
  enum?: string[];
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: JsonSchema;
}

export interface ToolCallRequest {
  toolCallId: string;
  name: string;
  args: Record<string, unknown>;
}

export type AssistantTurn =
  | { kind: "answer"; text: string }
  | { kind: "tool_calls"; calls: ToolCallRequest[]; text?: string };

export interface ToolResultProvenance {
  tool: string;
  targetPath?: string;
  format?: string;
  revision: number;
  range?: string;
  reason: string;
  step: number;
}

export interface ToolResultForModel {
  toolCallId: string;
  name: string;
  result: unknown;
  isError: boolean;
  provenance?: ToolResultProvenance;
}

export interface ConversationState {
  provider: LlmProvider;
  systemPrompt: string;
  nativeMessages: unknown[];
}

export interface HistoryTurn {
  role: "user" | "assistant";
  text: string;
}

export interface ProviderUsage {
  provider: LlmProvider;
  model: string;
  latencyMs: number;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
}

export interface NextTurn {
  turn: AssistantTurn;
  advance: (results: ToolResultForModel[]) => ConversationState;
  usage?: ProviderUsage;
}

export class ProviderProtocolError extends LlmRequestError {}
