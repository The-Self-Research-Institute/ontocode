import {
  getStoredApiKey,
  getStoredMaxResponseTokens,
  getStoredModel,
  getStoredProvider,
  LlmConfigError,
  LlmProvider,
  LlmRequestError,
} from "./LlmInsightsService";
import { estimateTokensFromChars, requestBudgetFor, type RequestBudget } from "./codeAssistantBudget";

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
  | { kind: "tool_calls"; calls: ToolCallRequest[] };

export interface ToolResultForModel {
  toolCallId: string;
  name: string;
  result: unknown;
  isError: boolean;
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

export class ProviderProtocolError extends LlmRequestError {}

function newToolCallId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}

function startConversation(
  provider: LlmProvider,
  systemPrompt: string,
  history: HistoryTurn[],
  userMessage: string,
): ConversationState {
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

function buildRequestBody(conversation: ConversationState, model: string, tools: ToolDefinition[]): Record<string, unknown> {
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

function providerEndpoint(provider: LlmProvider, model: string, key: string): { url: string; headers: Record<string, string> } {
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

interface OpenAiToolCall {
  id?: string;
  function?: { name?: string; arguments?: string };
}
interface OpenAiResponse {
  choices?: Array<{ finish_reason?: string; message?: { content?: string | null; tool_calls?: OpenAiToolCall[] } }>;
}

function parseOpenAiResponse(raw: OpenAiResponse): { turn: AssistantTurn; nativeAssistantMessage: unknown } {
  const choice = raw?.choices?.[0];
  const message = choice?.message;
  if (!message) throw new ProviderProtocolError("OpenAI response missing choices[0].message.");
  if (choice?.finish_reason === "length") {
    throw new ProviderProtocolError("OpenAI's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  if (toolCalls.length > 0) {
    const calls: ToolCallRequest[] = toolCalls.map((tc) => {
      let args: Record<string, unknown> = {};
      try {
        args = JSON.parse(tc.function?.arguments ?? "{}");
      } catch {
        throw new ProviderProtocolError(`OpenAI returned malformed tool-call arguments for "${tc.function?.name}".`);
      }
      return { toolCallId: String(tc.id ?? newToolCallId("call")), name: String(tc.function?.name ?? ""), args };
    });
    return { turn: { kind: "tool_calls", calls }, nativeAssistantMessage: message };
  }

  const text = typeof message.content === "string" ? message.content : "";
  if (!text.trim()) throw new ProviderProtocolError("OpenAI returned neither a tool call nor text content.");
  return { turn: { kind: "answer", text: text.trim() }, nativeAssistantMessage: message };
}

function appendOpenAiToolResults(conversation: ConversationState, nativeAssistantMessage: unknown, results: ToolResultForModel[]): ConversationState {
  const toolMessages = results.map((r) => ({
    role: "tool",
    tool_call_id: r.toolCallId,
    content: JSON.stringify(r.isError ? { error: r.result } : r.result),
  }));
  return { ...conversation, nativeMessages: [...conversation.nativeMessages, nativeAssistantMessage, ...toolMessages] };
}

interface ClaudeContentBlock {
  type?: string;
  text?: string;
  id?: string;
  name?: string;
  input?: Record<string, unknown>;
}
interface ClaudeResponse {
  content?: ClaudeContentBlock[];
  stop_reason?: string;
}

function parseClaudeResponse(raw: ClaudeResponse): { turn: AssistantTurn; nativeAssistantContent: unknown } {
  const content = raw?.content;
  if (!Array.isArray(content)) throw new ProviderProtocolError("Claude response missing content array.");
  if (raw?.stop_reason === "max_tokens") {
    throw new ProviderProtocolError("Claude's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const toolUses = content.filter((b) => b.type === "tool_use");
  if (toolUses.length > 0) {
    const calls: ToolCallRequest[] = toolUses.map((b) => ({
      toolCallId: String(b.id ?? newToolCallId("toolu")),
      name: String(b.name ?? ""),
      args: b.input ?? {},
    }));
    return { turn: { kind: "tool_calls", calls }, nativeAssistantContent: content };
  }

  const textBlock = content.find((b) => b.type === "text");
  const text = typeof textBlock?.text === "string" ? textBlock.text : "";
  if (!text.trim()) throw new ProviderProtocolError("Claude returned neither a tool_use block nor text content.");
  return { turn: { kind: "answer", text: text.trim() }, nativeAssistantContent: content };
}

function appendClaudeToolResults(conversation: ConversationState, nativeAssistantContent: unknown, results: ToolResultForModel[]): ConversationState {
  const toolResultContent = results.map((r) => ({
    type: "tool_result",
    tool_use_id: r.toolCallId,
    content: JSON.stringify(r.isError ? { error: r.result } : r.result),
    is_error: r.isError,
  }));
  return {
    ...conversation,
    nativeMessages: [
      ...conversation.nativeMessages,
      { role: "assistant", content: nativeAssistantContent },
      { role: "user", content: toolResultContent },
    ],
  };
}

interface GeminiPart {
  text?: string;
  functionCall?: { name?: string; args?: Record<string, unknown> };
}
interface GeminiResponse {
  candidates?: Array<{ content?: { parts?: GeminiPart[] }; finishReason?: string }>;
}

function parseGeminiResponse(raw: GeminiResponse): { turn: AssistantTurn; nativeAssistantParts: unknown } {
  const candidate = raw?.candidates?.[0];
  const parts = candidate?.content?.parts;
  if (!Array.isArray(parts)) throw new ProviderProtocolError("Gemini response missing candidates[0].content.parts.");
  if (candidate?.finishReason === "MAX_TOKENS") {
    throw new ProviderProtocolError("Gemini's response was cut off (hit the token limit) before it finished. Try a narrower request.");
  }

  const functionCalls = parts.filter((p) => p.functionCall);
  if (functionCalls.length > 0) {
    const calls: ToolCallRequest[] = functionCalls.map((p) => ({
      toolCallId: newToolCallId("gfn"),
      name: String(p.functionCall?.name ?? ""),
      args: p.functionCall?.args ?? {},
    }));
    return { turn: { kind: "tool_calls", calls }, nativeAssistantParts: parts };
  }

  const text = parts.map((p) => p.text ?? "").join("").trim();
  if (!text) throw new ProviderProtocolError("Gemini returned neither a functionCall nor text content.");
  return { turn: { kind: "answer", text }, nativeAssistantParts: parts };
}

function appendGeminiToolResults(conversation: ConversationState, nativeAssistantParts: unknown, results: ToolResultForModel[]): ConversationState {
  const functionResponseParts = results.map((r) => ({
    functionResponse: { name: r.name, response: r.isError ? { error: r.result } : { result: r.result } },
  }));
  return {
    ...conversation,
    nativeMessages: [
      ...conversation.nativeMessages,
      { role: "model", parts: nativeAssistantParts },
      { role: "user", parts: functionResponseParts },
    ],
  };
}

async function extractProviderErrorMessage(res: Response): Promise<string | null> {
  try {
    const data = await res.json();
    const message = data?.error?.message;
    return typeof message === "string" && message.trim() ? message.trim() : null;
  } catch {
    return null;
  }
}

function summarizeQuotaMessage(detail: string): string {
  if (detail.length <= 160 && !detail.includes("\n") && !detail.includes(" * ")) return detail;
  const headline = (detail.match(/^[^.]*\./)?.[0] ?? detail.split(/\s\*\s|\n/)[0]).trim().replace(/\.$/, "");
  const modelMatch = detail.match(/model:\s*([\w.-]+)/i);
  const retryMatch = detail.match(/retry in\s+([\d.]+)\s*s/i);
  const parts = [headline || "Quota exceeded"];
  if (modelMatch) parts.push(`for ${modelMatch[1]}`);
  const sentence = parts.join(" ") + ".";
  return retryMatch ? `${sentence} Try again in about ${Math.ceil(Number(retryMatch[1]))}s.` : sentence;
}

async function mapHttpError(provider: LlmProvider, res: Response): Promise<LlmRequestError> {
  const status = res.status;
  if (status === 401 || status === 403) return new LlmRequestError(`Invalid or unauthorized API key for ${provider}.`);
  if (status === 404) return new LlmRequestError(`Model not found or unavailable for ${provider}.`);
  if (status === 429) {
    const detail = await extractProviderErrorMessage(res);
    return new LlmRequestError(detail ? `Rate limit reached: ${summarizeQuotaMessage(detail)}` : "Rate limit reached. Try again shortly.");
  }
  if (status === 503) return new LlmRequestError(`${provider} is temporarily overloaded. Try again shortly.`);
  return new LlmRequestError(`${provider} API error (HTTP ${status}).`);
}

const RETRYABLE_STATUSES = new Set([503]);
const MAX_TRANSIENT_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 1000;
const MAX_REQUEST_CHARS = 1_200_000;

interface RequestSize {
  chars: number;
  estimatedTokens: number;
}

function measureRequest(body: unknown): RequestSize {
  const chars = JSON.stringify(body).length;
  return { chars, estimatedTokens: estimateTokensFromChars(chars) };
}

function fitsRequestBudget(size: RequestSize, budget: RequestBudget): boolean {
  return size.chars <= MAX_REQUEST_CHARS && size.estimatedTokens <= budget.inputLimit;
}

function requestTooLargeError(provider: LlmProvider, model: string, size: RequestSize, budget: RequestBudget): ProviderProtocolError {
  return new ProviderProtocolError(
    `This conversation has grown too large to send to ${provider} (${model}): about ${size.estimatedTokens} tokens estimated, ` +
      `but the model's ${budget.contextWindow}-token context leaves room for about ${budget.inputLimit} after reserving ` +
      `${budget.outputReserve} for the response. Start a new request for a fresh, smaller context.`,
  );
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      },
      { once: true },
    );
  });
}

export async function startAssistantConversation(
  systemPrompt: string,
  userMessage: string,
  history: HistoryTurn[] = [],
): Promise<ConversationState> {
  const provider = getStoredProvider();
  return startConversation(provider, systemPrompt, history, userMessage);
}

export async function requestNextTurn(
  conversation: ConversationState,
  tools: ToolDefinition[],
  signal?: AbortSignal,
  onRetry?: (attempt: number, maxAttempts: number, status: number) => void,
): Promise<{ turn: AssistantTurn; advance: (results: ToolResultForModel[]) => ConversationState }> {
  const key = getStoredApiKey();
  if (!key) throw new LlmConfigError("No API key configured. Configure an AI provider to use the assistant.");
  const model = getStoredModel();

  const body = buildRequestBody(conversation, model, tools);
  const budget = requestBudgetFor(conversation.provider, model, getStoredMaxResponseTokens());
  const size = measureRequest(body);
  if (!fitsRequestBudget(size, budget)) {
    throw requestTooLargeError(conversation.provider, model, size, budget);
  }
  const { url, headers } = providerEndpoint(conversation.provider, model, key);

  let res: Response;
  let attempt = 0;
  while (true) {
    res = await fetch(url, { method: "POST", headers, body: JSON.stringify(body), signal });
    if (res.ok || !RETRYABLE_STATUSES.has(res.status) || attempt >= MAX_TRANSIENT_RETRIES) break;
    attempt += 1;
    onRetry?.(attempt, MAX_TRANSIENT_RETRIES, res.status);
    await delay(RETRY_BASE_DELAY_MS * attempt, signal);
  }
  if (!res.ok) throw await mapHttpError(conversation.provider, res);

  const raw = await res.json().catch(() => {
    throw new ProviderProtocolError(`${conversation.provider} returned a response that could not be parsed as JSON.`);
  });

  if (conversation.provider === "openai") {
    const { turn, nativeAssistantMessage } = parseOpenAiResponse(raw);
    return { turn, advance: (results) => appendOpenAiToolResults(conversation, nativeAssistantMessage, results) };
  }
  if (conversation.provider === "claude") {
    const { turn, nativeAssistantContent } = parseClaudeResponse(raw);
    return { turn, advance: (results) => appendClaudeToolResults(conversation, nativeAssistantContent, results) };
  }
  const { turn, nativeAssistantParts } = parseGeminiResponse(raw);
  return { turn, advance: (results) => appendGeminiToolResults(conversation, nativeAssistantParts, results) };
}
