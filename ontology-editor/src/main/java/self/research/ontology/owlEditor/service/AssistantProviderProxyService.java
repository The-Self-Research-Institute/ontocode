package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantProviderProxyService {

    public static final String CLAUDE = "claude";
    public static final String OPENAI = "openai";
    public static final String GEMINI = "gemini";
    public static final Set<String> SUPPORTED_PROVIDERS = Set.of(CLAUDE, OPENAI, GEMINI);

    static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    static final String GEMINI_URL_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final Pattern MODEL_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    public record ProviderConfigView(boolean managed, String provider, String model) {
    }

    public record ProviderCallResult(int status, String body, String errorCode, String message,
                                     Long retryAfterSeconds) {

        public boolean isError() {
            return errorCode != null;
        }

        static ProviderCallResult error(int status, String errorCode, String message) {
            return new ProviderCallResult(status, null, errorCode, message, null);
        }

        static ProviderCallResult rateLimited(long retryAfterSeconds, String message) {
            return new ProviderCallResult(429, null, "RATE_LIMITED", message, retryAfterSeconds);
        }
    }

    private record UpstreamResponse(int status, String body, String retryAfter) {
    }

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${ASSISTANT_PROVIDER:}")
    private String provider;

    @Value("${ASSISTANT_PROVIDER_MODEL:}")
    private String model;

    @Value("${ASSISTANT_PROVIDER_API_KEY:}")
    private String apiKey;

    @Value("${assistant.provider.max-output-tokens:4096}")
    private int maxOutputTokens;

    @Value("${assistant.provider.max-request-bytes:1000000}")
    private int maxRequestBytes;

    @Value("${assistant.provider.max-response-bytes:4000000}")
    private int maxResponseBytes;

    @Value("${assistant.provider.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${assistant.provider.response-timeout-seconds:120}")
    private long responseTimeoutSeconds;

    @Value("${assistant.provider.requests-per-minute:30}")
    private int requestsPerMinute;

    @Value("${assistant.provider.max-concurrent-calls:16}")
    private int maxConcurrentCalls;

    private WebClient webClient;
    private ConnectionProvider connectionProvider;
    private AssistantProviderRateLimiter rateLimiter;
    private Semaphore concurrencyLimit;

    public AssistantProviderProxyService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        connectionProvider = ConnectionProvider.builder("assistant-provider")
                .maxConnections(Math.max(1, maxConcurrentCalls))
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
        webClient = webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
        rateLimiter = new AssistantProviderRateLimiter(Math.max(1, requestsPerMinute), Duration.ofMinutes(1),
                10_000, Clock.systemUTC());
        concurrencyLimit = new Semaphore(Math.max(1, maxConcurrentCalls));
        if (isManaged()) {
            log.info("[Assistant] Managed provider mode enabled for provider {} model {}", normalizedProvider(), model);
        }
    }

    @PreDestroy
    void shutdown() {
        if (connectionProvider != null) {
            connectionProvider.disposeLater().subscribe();
        }
    }

    public boolean isManaged() {
        return SUPPORTED_PROVIDERS.contains(normalizedProvider())
                && model != null && MODEL_PATTERN.matcher(model.trim()).matches()
                && apiKey != null && !apiKey.isBlank();
    }

    public ProviderConfigView config() {
        if (!isManaged()) {
            return new ProviderConfigView(false, null, null);
        }
        return new ProviderConfigView(true, normalizedProvider(), model.trim());
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public AssistantProviderRateLimiter.Decision tryAcquireRate(String userEmail) {
        return rateLimiter.tryAcquire(userEmail);
    }

    public ProviderCallResult forward(JsonNode request) {
        if (!isManaged()) {
            return ProviderCallResult.error(503, "PROVIDER_UNAVAILABLE",
                    "Managed provider mode is not configured on this server");
        }
        if (request == null || !request.isObject()) {
            return ProviderCallResult.error(400, "VALIDATION_FAILED", "request must be a JSON object");
        }
        String activeProvider = normalizedProvider();
        String activeModel = model.trim();
        ObjectNode shaped = shape(activeProvider, activeModel, ((ObjectNode) request).deepCopy());
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(shaped);
        } catch (IOException e) {
            return ProviderCallResult.error(400, "VALIDATION_FAILED", "request could not be serialized");
        }
        if (payload.length > maxRequestBytes) {
            return ProviderCallResult.error(413, "VALIDATION_FAILED", "request is larger than the allowed size");
        }
        if (!concurrencyLimit.tryAcquire()) {
            return ProviderCallResult.rateLimited(2, "Too many provider calls in progress, try again shortly");
        }
        long startNanos = System.nanoTime();
        try {
            UpstreamResponse upstream = send(activeProvider, activeModel, payload);
            long latencyMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            log.info("[Assistant] Managed provider call to {} returned {} in {} ms",
                    activeProvider, upstream.status(), latencyMs);
            return toResult(activeProvider, upstream);
        } catch (Exception e) {
            log.warn("[Assistant] Managed provider call to {} failed: {}", activeProvider,
                    Exceptions.unwrap(e).getClass().getSimpleName());
            return ProviderCallResult.error(503, "PROVIDER_UNAVAILABLE", "The AI provider could not be reached");
        } finally {
            concurrencyLimit.release();
        }
    }

    private ProviderCallResult toResult(String activeProvider, UpstreamResponse upstream) {
        int status = upstream.status();
        if (status == 401 || status == 403) {
            log.error("[Assistant] Managed provider {} rejected the server credentials with status {}",
                    activeProvider, status);
            return ProviderCallResult.error(503, "PROVIDER_UNAVAILABLE",
                    "The AI provider rejected the server's credentials");
        }
        String body = upstream.body();
        if (body == null || body.isBlank() || !isJson(body)) {
            return ProviderCallResult.error(503, "PROVIDER_UNAVAILABLE",
                    "The AI provider returned an unreadable response");
        }
        Long retryAfter = parseRetryAfter(upstream.retryAfter());
        return new ProviderCallResult(status, body, null, null, retryAfter);
    }

    private UpstreamResponse send(String activeProvider, String activeModel, byte[] payload) {
        URI uri = switch (activeProvider) {
            case CLAUDE -> URI.create(CLAUDE_URL);
            case OPENAI -> URI.create(OPENAI_URL);
            default -> URI.create(GEMINI_URL_PREFIX + activeModel + ":generateContent");
        };
        String key = apiKey.trim();
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthHeaders(activeProvider, key, headers))
                .bodyValue(payload)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new UpstreamResponse(response.statusCode().value(), body,
                                response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER))))
                .timeout(Duration.ofSeconds(responseTimeoutSeconds + 5))
                .switchIfEmpty(Mono.error(new IllegalStateException("empty upstream response")))
                .block();
    }

    private static void applyAuthHeaders(String activeProvider, String key, HttpHeaders headers) {
        switch (activeProvider) {
            case CLAUDE -> {
                headers.set("x-api-key", key);
                headers.set("anthropic-version", "2023-06-01");
                headers.set("anthropic-beta", "prompt-caching-2024-07-31");
            }
            case OPENAI -> headers.setBearerAuth(key);
            default -> headers.set("x-goog-api-key", key);
        }
    }

    private ObjectNode shape(String activeProvider, String activeModel, ObjectNode body) {
        body.remove("stream");
        body.remove("stream_options");
        switch (activeProvider) {
            case CLAUDE -> {
                body.put("model", activeModel);
                body.put("max_tokens", cappedTokens(body.get("max_tokens")));
            }
            case OPENAI -> {
                body.put("model", activeModel);
                body.remove("n");
                boolean hasLegacy = body.has("max_tokens");
                boolean hasCompletion = body.has("max_completion_tokens");
                if (hasLegacy) {
                    body.put("max_tokens", cappedTokens(body.get("max_tokens")));
                }
                if (hasCompletion || !hasLegacy) {
                    body.put("max_completion_tokens", cappedTokens(body.get("max_completion_tokens")));
                }
            }
            default -> {
                body.remove("model");
                JsonNode existing = body.get("generationConfig");
                ObjectNode generationConfig = existing != null && existing.isObject()
                        ? (ObjectNode) existing
                        : body.putObject("generationConfig");
                generationConfig.put("maxOutputTokens", cappedTokens(generationConfig.get("maxOutputTokens")));
                generationConfig.remove("candidateCount");
            }
        }
        return body;
    }

    private int cappedTokens(JsonNode requested) {
        int cap = Math.max(1, maxOutputTokens);
        if (requested == null || !requested.canConvertToInt() || !requested.isNumber()) {
            return cap;
        }
        int value = requested.asInt();
        if (value < 1) {
            return cap;
        }
        return Math.min(value, cap);
    }

    private boolean isJson(String body) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            return parsed != null && (parsed.isObject() || parsed.isArray());
        } catch (IOException e) {
            return false;
        }
    }

    private static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizedProvider() {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
