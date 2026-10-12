import { describe, it, expect } from "vitest";
import {
  contextWindowFor,
  estimateTokens,
  estimateTokensFromChars,
  requestBudgetFor,
  UNKNOWN_MODEL_CONTEXT_WINDOW,
} from "../services/codeAssistantBudget";

describe("contextWindowFor", () => {
  it("gives every Claude model 200k", () => {
    expect(contextWindowFor("claude", "claude-sonnet-4-5")).toBe(200_000);
    expect(contextWindowFor("claude", "claude-3-5-haiku-latest")).toBe(200_000);
    expect(contextWindowFor("claude", "something-new")).toBe(200_000);
  });

  it("distinguishes the OpenAI families", () => {
    expect(contextWindowFor("openai", "gpt-4o")).toBe(128_000);
    expect(contextWindowFor("openai", "gpt-4o-mini-2024-07-18")).toBe(128_000);
    expect(contextWindowFor("openai", "gpt-4.1")).toBe(1_000_000);
    expect(contextWindowFor("openai", "gpt-4.1-nano")).toBe(1_000_000);
    expect(contextWindowFor("openai", "o3-mini")).toBe(200_000);
    expect(contextWindowFor("openai", "o1")).toBe(200_000);
    expect(contextWindowFor("openai", "o4-mini")).toBe(200_000);
  });

  it("gives Gemini 1.5 and 2.x a 1M window, with or without the models/ prefix", () => {
    expect(contextWindowFor("gemini", "gemini-1.5-pro")).toBe(1_000_000);
    expect(contextWindowFor("gemini", "gemini-2.0-flash")).toBe(1_000_000);
    expect(contextWindowFor("gemini", "gemini-2.5-pro")).toBe(1_000_000);
    expect(contextWindowFor("gemini", "models/gemini-2.5-flash-lite")).toBe(1_000_000);
  });

  it("falls back to a conservative 128k for anything unrecognised", () => {
    expect(contextWindowFor("openai", "gpt-3.5-turbo")).toBe(UNKNOWN_MODEL_CONTEXT_WINDOW);
    expect(contextWindowFor("gemini", "gemini-1.0-pro")).toBe(UNKNOWN_MODEL_CONTEXT_WINDOW);
    expect(contextWindowFor("gemini", "gemini-20-experimental")).toBe(UNKNOWN_MODEL_CONTEXT_WINDOW);
    expect(contextWindowFor("openai", "")).toBe(UNKNOWN_MODEL_CONTEXT_WINDOW);
  });
});

describe("token estimate", () => {
  it("is about chars / 3.5 and rounds up", () => {
    expect(estimateTokensFromChars(7)).toBe(2);
    expect(estimateTokensFromChars(8)).toBe(3);
    expect(estimateTokensFromChars(0)).toBe(0);
  });

  it("counts the JSON overhead of structured values", () => {
    const value = { messages: [{ role: "user", content: "hi" }] };
    expect(estimateTokens(value)).toBe(Math.ceil(JSON.stringify(value).length / 3.5));
    expect(estimateTokens(value)).toBeGreaterThan(estimateTokens("hi"));
  });

  it("is more conservative than the old chars / 4 estimate", () => {
    expect(estimateTokensFromChars(4000)).toBeGreaterThan(4000 / 4);
  });
});

describe("requestBudgetFor", () => {
  it("reserves the response tokens out of the context window", () => {
    expect(requestBudgetFor("claude", "claude-sonnet-4-5", 8192)).toEqual({
      contextWindow: 200_000,
      outputReserve: 8192,
      inputLimit: 200_000 - 8192,
    });
  });

  it("never goes negative when the reserve exceeds the window", () => {
    expect(requestBudgetFor("openai", "gpt-4o", 500_000).inputLimit).toBe(0);
  });
});
