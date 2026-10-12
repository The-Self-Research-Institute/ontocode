import { getStoredMaxResponseTokens, LlmProvider } from "./LlmInsightsService";
import { compactHistory } from "./codeAssistantCompaction";
import type { ConversationState, HistoryTurn, JsonSchema, ToolDefinition } from "./codeAssistantProviderTypes";

export function startConversation(
  provider: LlmProvider,
  systemPrompt: string,
  fullHistory: HistoryTurn[],
  userMessage: string,
): ConversationState {
  const history = compactHistory(fullHistory);
  if (provider === "openai") {
    return {
      provider,
      systemPrompt,
      nativeMessages: [
        { role: "system", content: systemPrompt },
        ...history.map((h) => ({ role: h.role, content: h.text })),
        { role: "user", content: userMessage },
      ],
    };
  }
  if (provider === "claude") {
    return {
      provider,
      systemPrompt,
      nativeMessages: [...history.map((h) => ({ role: h.role, content: h.text })), { role: "user", content: userMessage }],
    };
  }
  return {
    provider,
    systemPrompt,
    nativeMessages: [
      ...history.map((h) => ({ role: h.role === "assistant" ? "model" : "user", parts: [{ text: h.text }] })),
      { role: "user", parts: [{ text: userMessage }] },
    ],
  };
}

function toOpenAiTool(tool: ToolDefinition) {
  return { type: "function", function: { name: tool.name, description: tool.description, parameters: tool.parameters } };
}

interface ClaudeTool {
  name: string;
  description: string;
  input_schema: JsonSchema;
  cache_control?: { type: "ephemeral" };
}

function toClaudeTool(tool: ToolDefinition): ClaudeTool {
  return { name: tool.name, description: tool.description, input_schema: tool.parameters };
}

function toGeminiFunctionDeclaration(tool: ToolDefinition) {
  return { name: tool.name, description: tool.description, parameters: tool.parameters };
}

const CACHE_CONTROL_EPHEMERAL = { type: "ephemeral" } as const;

function withClaudeCacheBreakpoint(messages: unknown[]): unknown[] {
  if (messages.length === 0) return messages;
  const lastIndex = messages.length - 1;
  const last = messages[lastIndex] as { role: string; content: unknown };
  if (typeof last.content === "string") {
    return [
      ...messages.slice(0, lastIndex),
      { ...last, content: [{ type: "text", text: last.content, cache_control: CACHE_CONTROL_EPHEMERAL }] },
    ];
  }
  if (Array.isArray(last.content) && last.content.length > 0) {
    const blocks = last.content as Record<string, unknown>[];
    const lastBlockIndex = blocks.length - 1;
    const content = blocks.map((block, i) => (i === lastBlockIndex ? { ...block, cache_control: CACHE_CONTROL_EPHEMERAL } : block));
    return [...messages.slice(0, lastIndex), { ...last, content }];
  }
  return messages;
}

export function buildRequestBody(conversation: ConversationState, model: string, tools: ToolDefinition[]): Record<string, unknown> {
  const maxTokens = getStoredMaxResponseTokens();
  if (conversation.provider === "openai") {
    return {
      model,
      messages: conversation.nativeMessages,
      tools: tools.map(toOpenAiTool),
      tool_choice: "auto",
      max_tokens: maxTokens,
      temperature: 0.2,
    };
  }
  if (conversation.provider === "claude") {
    const claudeTools = tools.map(toClaudeTool);
    if (claudeTools.length > 0) {
      claudeTools[claudeTools.length - 1] = { ...claudeTools[claudeTools.length - 1], cache_control: CACHE_CONTROL_EPHEMERAL };
    }
    return {
      model,
      system: [{ type: "text", text: conversation.systemPrompt, cache_control: CACHE_CONTROL_EPHEMERAL }],
      messages: withClaudeCacheBreakpoint(conversation.nativeMessages),
      tools: claudeTools,
      max_tokens: maxTokens,
    };
  }
  return {
    systemInstruction: { parts: [{ text: conversation.systemPrompt }] },
    contents: conversation.nativeMessages,
    tools: [{ functionDeclarations: tools.map(toGeminiFunctionDeclaration) }],
    generationConfig: { temperature: 0.2, maxOutputTokens: maxTokens },
  };
}

export function providerEndpoint(provider: LlmProvider, model: string, key: string): { url: string; headers: Record<string, string> } {
  if (provider === "openai") {
    return {
      url: "https://api.openai.com/v1/chat/completions",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
    };
  }
  if (provider === "claude") {
    return {
      url: "https://api.anthropic.com/v1/messages",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": key,
        "anthropic-version": "2023-06-01",
        "anthropic-dangerous-direct-browser-access": "true",
        "anthropic-beta": "prompt-caching-2024-07-31",
      },
    };
  }
  return {
    url: `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
    headers: { "Content-Type": "application/json", "x-goog-api-key": key },
  };
}
