import type { LlmProvider } from "./LlmInsightsService";

export const UNKNOWN_MODEL_CONTEXT_WINDOW = 128_000;
export const CHARS_PER_TOKEN_ESTIMATE = 3.5;

export interface ContextWindowRule {
  provider: LlmProvider;
  pattern: RegExp;
  contextWindow: number;
}

export const CONTEXT_WINDOW_RULES: readonly ContextWindowRule[] = [
  { provider: "claude", pattern: /^claude/i, contextWindow: 200_000 },
  { provider: "openai", pattern: /^gpt-4\.1/i, contextWindow: 1_000_000 },
  { provider: "openai", pattern: /^gpt-4o/i, contextWindow: 128_000 },
  { provider: "openai", pattern: /^o\d/i, contextWindow: 200_000 },
  { provider: "gemini", pattern: /^(models\/)?gemini-1\.5(-|$)/i, contextWindow: 1_000_000 },
  { provider: "gemini", pattern: /^(models\/)?gemini-2(\.\d+)?(-|$)/i, contextWindow: 1_000_000 },
];

export function contextWindowFor(provider: LlmProvider, model: string): number {
  const id = (model ?? "").trim();
  const rule = CONTEXT_WINDOW_RULES.find((r) => r.provider === provider && r.pattern.test(id));
  if (rule) return rule.contextWindow;
  if (provider === "claude") return 200_000;
  return UNKNOWN_MODEL_CONTEXT_WINDOW;
}

export function estimateTokensFromChars(chars: number): number {
  return Math.ceil(chars / CHARS_PER_TOKEN_ESTIMATE);
}

export function estimateTokens(value: unknown): number {
  const serialized = typeof value === "string" ? value : JSON.stringify(value) ?? "";
  return estimateTokensFromChars(serialized.length);
}

export interface RequestBudget {
  contextWindow: number;
  outputReserve: number;
  inputLimit: number;
}

export function requestBudgetFor(provider: LlmProvider, model: string, outputReserve: number): RequestBudget {
  const contextWindow = contextWindowFor(provider, model);
  const reserve = Math.max(0, Math.floor(outputReserve));
  return { contextWindow, outputReserve: reserve, inputLimit: Math.max(0, contextWindow - reserve) };
}
