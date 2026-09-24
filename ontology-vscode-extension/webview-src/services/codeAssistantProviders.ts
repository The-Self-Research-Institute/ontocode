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
import { compactHistory, compactOlderToolResults } from "./codeAssistantCompaction";
import { toAssistantApiError } from "./codeAssistantSession";

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

function toolResultPayload(r: ToolResultForModel): unknown {
  const inner = r.isError ? { error: r.result } : r.result;
  return r.provenance ? { provenance: r.provenance, result: inner } : inner;
}

function geminiToolResponse(r: ToolResultForModel): unknown {
  if (r.provenance) return toolResultPayload(r);
  return r.isError ? { error: r.result } : { result: r.result };
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

function tokenCount(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

type UsageCounts = Pick<ProviderUsage, "inputTokens" | "outputTokens" | "cacheReadTokens" | "cacheWriteTokens">;

export function parseProviderUsage(provider: LlmProvider, raw: unknown): UsageCounts {
  const r = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  let counts: UsageCounts;
  if (provider === "claude") {
    const u = (r.usage ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.input_tokens),
      outputTokens: tokenCount(u.output_tokens),
      cacheReadTokens: tokenCount(u.cache_read_input_tokens),
      cacheWriteTokens: tokenCount(u.cache_creation_input_tokens),
    };
  } else if (provider === "openai") {
    const u = (r.usage ?? {}) as Record<string, unknown>;
    const details = (u.prompt_tokens_details ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.prompt_tokens),
      outputTokens: tokenCount(u.completion_tokens),
      cacheReadTokens: tokenCount(details.cached_tokens),
    };
  } else {
    const u = (r.usageMetadata ?? {}) as Record<string, unknown>;
    counts = {
      inputTokens: tokenCount(u.promptTokenCount),
      outputTokens: tokenCount(u.candidatesTokenCount),
      cacheReadTokens: tokenCount(u.cachedContentTokenCount),
    };
  }
  return Object.fromEntries(Object.entries(counts).filter(([, v]) => v !== undefined)) as UsageCounts;
}

function newToolCallId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}

function startConversation(
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
    content: JSON.stringify(toolResultPayload(r)),
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
    content: JSON.stringify(toolResultPayload(r)),
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
    functionResponse: { name: r.name, response: geminiToolResponse(r) },
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

function providerErrorDetail(data: unknown): string | null {
  const error = data && typeof data === "object" ? (data as { error?: unknown }).error : undefined;
  const message = error && typeof error === "object" ? (error as { message?: unknown }).message : undefined;
  return typeof message === "string" && message.trim() ? message.trim() : null;
}

async function extractProviderErrorMessage(res: Response): Promise<string | null> {
  try {
    return providerErrorDetail(await res.json());
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

function providerHttpError(provider: LlmProvider, status: number, detail: string | null): LlmRequestError {
  if (status === 401 || status === 403) return new LlmRequestError(`Invalid or unauthorized API key for ${provider}.`);
  if (status === 404) return new LlmRequestError(`Model not found or unavailable for ${provider}.`);
  if (status === 429) {
    return new LlmRequestError(detail ? `Rate limit reached: ${summarizeQuotaMessage(detail)}` : "Rate limit reached. Try again shortly.");
  }
  if (status === 503) return new LlmRequestError(`${provider} is temporarily overloaded. Try again shortly.`);
  return new LlmRequestError(`${provider} API error (HTTP ${status}).`);
}

async function mapHttpError(provider: LlmProvider, res: Response): Promise<LlmRequestError> {
  const detail = res.status === 429 ? await extractProviderErrorMessage(res) : null;
  return providerHttpError(provider, res.status, detail);
}

const BACKEND_ONLY_STATUSES = new Set([401, 403, 423]);

async function mapManagedHttpError(provider: LlmProvider, res: Response, path: string): Promise<Error> {
  const data: unknown = await res.json().catch(() => null);
  const errorCode = data && typeof data === "object" ? (data as { errorCode?: unknown }).errorCode : undefined;
  if (typeof errorCode === "string" || BACKEND_ONLY_STATUSES.has(res.status)) return toAssistantApiError(res, data, path);
  return providerHttpError(provider, res.status, providerErrorDetail(data));
}

export interface ManagedProviderCall {
  apiBaseUrl: string;
  token: string | undefined;
  sessionId: string;
  model: string;
}

export function managedProviderCallPath(sessionId: string): string {
  return `/api/v1/code-assistant/sessions/${encodeURIComponent(sessionId)}/provider-call`;
}

interface ProviderTarget {
  url: string;
  headers: Record<string, string>;
  payload: string;
  path?: string;
}

function managedTarget(managed: ManagedProviderCall, body: Record<string, unknown>): ProviderTarget {
  const path = managedProviderCallPath(managed.sessionId);
  return {
    url: `${managed.apiBaseUrl}${path}`,
    headers: { "Content-Type": "application/json", ...(managed.token ? { Authorization: `Bearer ${managed.token}` } : {}) },
    payload: JSON.stringify({ request: body }),
    path,
  };
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

export interface FittedRequest {
  conversation: ConversationState;
  body: Record<string, unknown>;
  compactedRounds: number;
}

export function fitConversationToBudget(conversation: ConversationState, model: string, tools: ToolDefinition[]): FittedRequest {
  const budget = requestBudgetFor(conversation.provider, model, getStoredMaxResponseTokens());
  const body = buildRequestBody(conversation, model, tools);
  if (fitsRequestBudget(measureRequest(body), budget)) return { conversation, body, compactedRounds: 0 };

  const { messages, compactedRounds } = compactOlderToolResults(conversation.provider, conversation.nativeMessages);
  const compacted: ConversationState = compactedRounds > 0 ? { ...conversation, nativeMessages: messages } : conversation;
  const compactedBody = compactedRounds > 0 ? buildRequestBody(compacted, model, tools) : body;
  const size = measureRequest(compactedBody);
  if (!fitsRequestBudget(size, budget)) throw requestTooLargeError(conversation.provider, model, size, budget);
  return { conversation: compacted, body: compactedBody, compactedRounds };
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
  provider?: LlmProvider,
): Promise<ConversationState> {
  return startConversation(provider ?? getStoredProvider(), systemPrompt, history, userMessage);
}

export async function requestNextTurn(
  inputConversation: ConversationState,
  tools: ToolDefinition[],
  signal?: AbortSignal,
  onRetry?: (attempt: number, maxAttempts: number, status: number) => void,
  managed?: ManagedProviderCall,
): Promise<NextTurn> {
  let key = "";
  if (!managed) {
    key = getStoredApiKey();
    if (!key) throw new LlmConfigError("No API key configured. Configure an AI provider to use the assistant.");
  }
  const model = managed ? managed.model : getStoredModel();

  const { conversation, body } = fitConversationToBudget(inputConversation, model, tools);
  const target: ProviderTarget = managed
    ? managedTarget(managed, body)
    : { ...providerEndpoint(conversation.provider, model, key), payload: JSON.stringify(body) };

  let res: Response;
  let attempt = 0;
  let startedAt = nowMs();
  while (true) {
    startedAt = nowMs();
    res = await fetch(target.url, { method: "POST", headers: target.headers, body: target.payload, signal });
    if (res.ok || !RETRYABLE_STATUSES.has(res.status) || attempt >= MAX_TRANSIENT_RETRIES) break;
    attempt += 1;
    onRetry?.(attempt, MAX_TRANSIENT_RETRIES, res.status);
    await delay(RETRY_BASE_DELAY_MS * attempt, signal);
  }
  if (!res.ok) {
    throw target.path !== undefined
      ? await mapManagedHttpError(conversation.provider, res, target.path)
      : await mapHttpError(conversation.provider, res);
  }

  const raw = await res.json().catch(() => {
    throw new ProviderProtocolError(`${conversation.provider} returned a response that could not be parsed as JSON.`);
  });

  return parseTurn(conversation, raw, {
    provider: conversation.provider,
    model,
    latencyMs: Math.max(0, Math.round(nowMs() - startedAt)),
    ...parseProviderUsage(conversation.provider, raw),
  });
}

export interface NextTurn {
  turn: AssistantTurn;
  advance: (results: ToolResultForModel[]) => ConversationState;
  usage?: ProviderUsage;
}

function nowMs(): number {
  return typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now();
}

function parseTurn(conversation: ConversationState, raw: unknown, usage: ProviderUsage): NextTurn {
  if (conversation.provider === "openai") {
    const { turn, nativeAssistantMessage } = parseOpenAiResponse(raw as OpenAiResponse);
    return { turn, usage, advance: (results) => appendOpenAiToolResults(conversation, nativeAssistantMessage, results) };
  }
  if (conversation.provider === "claude") {
    const { turn, nativeAssistantContent } = parseClaudeResponse(raw as ClaudeResponse);
    return { turn, usage, advance: (results) => appendClaudeToolResults(conversation, nativeAssistantContent, results) };
  }
  const { turn, nativeAssistantParts } = parseGeminiResponse(raw as GeminiResponse);
  return { turn, usage, advance: (results) => appendGeminiToolResults(conversation, nativeAssistantParts, results) };
}
