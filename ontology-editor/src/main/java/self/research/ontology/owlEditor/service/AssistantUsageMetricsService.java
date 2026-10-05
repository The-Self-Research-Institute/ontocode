package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantUsageReport;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AssistantUsageMetricsService {

    public static final String LATENCY_TIMER = "assistant.provider.latency";
    public static final String TOKEN_COUNTER = "assistant.provider.tokens";
    public static final String OTHER_MODEL = "other";
    public static final long MAX_LATENCY_MS = Duration.ofMinutes(10).toMillis();
    public static final long MAX_TOKENS_PER_KIND = 5_000_000L;
    static final int MAX_MODEL_TAG_VALUES = 24;
    static final int MAX_MODEL_TAG_LENGTH = 48;
    static final int MAX_NEW_MODEL_TAGS_PER_USER = 6;

    private static final Pattern RAW_MODEL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern DATE_SUFFIX = Pattern.compile("-(\\d{8}|\\d{4}-\\d{2}-\\d{2}|latest)$");
    private static final Pattern OPENAI_MODEL = Pattern.compile("^(gpt-|chatgpt-|o\\d).*");

    public record UsageOutcome(int status, String errorCode, String message, Long retryAfterSeconds) {

        public boolean isOk() {
            return errorCode == null;
        }

        static UsageOutcome accepted() {
            return new UsageOutcome(204, null, null, null);
        }

        static UsageOutcome rejected(int status, String errorCode, String message) {
            return new UsageOutcome(status, errorCode, message, null);
        }
    }

    private final MeterRegistry registry;
    private final AssistantSessionRepository sessionRepository;
    private final Set<String> admittedModels = new HashSet<>();
    private final Map<String, Integer> admissionsByUser = new HashMap<>();
    private final AssistantProviderProxyService proxyService;
    private final Clock clock = Clock.systemUTC();
    private final AssistantProviderRateLimiter rateLimiter;

    @Value("${assistant.usage.max-session-age-minutes:60}")
    private long maxSessionAgeMinutes = 60;

    public AssistantUsageMetricsService(MeterRegistry registry, AssistantSessionRepository sessionRepository,
                                        int reportsPerMinute) {
        this(registry, sessionRepository, null, reportsPerMinute);
    }

    @Autowired
    public AssistantUsageMetricsService(MeterRegistry registry, AssistantSessionRepository sessionRepository,
                                        AssistantProviderProxyService proxyService,
                                        @Value("${assistant.usage.reports-per-minute:120}") int reportsPerMinute) {
        this.registry = registry;
        this.sessionRepository = sessionRepository;
        this.proxyService = proxyService;
        this.rateLimiter = new AssistantProviderRateLimiter(Math.max(1, reportsPerMinute), Duration.ofMinutes(1),
                10_000, clock);
    }

    public UsageOutcome record(String sessionId, String userEmail, AssistantUsageReport report) {
        if (report == null) {
            return UsageOutcome.rejected(400, "VALIDATION_FAILED", "body is required");
        }
        String provider = report.provider() == null ? "" : report.provider().trim().toLowerCase(Locale.ROOT);
        if (!AssistantProviderProxyService.SUPPORTED_PROVIDERS.contains(provider)) {
            return UsageOutcome.rejected(400, "VALIDATION_FAILED", "provider must be claude, openai or gemini");
        }
        if (report.model() == null || report.model().isBlank() || report.model().length() > 200) {
            return UsageOutcome.rejected(400, "VALIDATION_FAILED", "model is required and must be at most 200 characters");
        }
        if (report.latencyMs() == null || report.latencyMs() < 0 || report.latencyMs() > MAX_LATENCY_MS) {
            return UsageOutcome.rejected(400, "VALIDATION_FAILED",
                    "latencyMs must be between 0 and " + MAX_LATENCY_MS);
        }
        if (!tokensInRange(report.inputTokens()) || !tokensInRange(report.outputTokens())
                || !tokensInRange(report.cacheReadTokens()) || !tokensInRange(report.cacheWriteTokens())) {
            return UsageOutcome.rejected(400, "VALIDATION_FAILED",
                    "token counts must be between 0 and " + MAX_TOKENS_PER_KIND);
        }

        Optional<AssistantSessionDocument> session = sessionId == null || userEmail == null
                ? Optional.empty()
                : sessionRepository.findByIdAndUserEmail(sessionId, userEmail);
        if (session.isEmpty() || isTooOld(session.get())) {
            return UsageOutcome.rejected(404, "SESSION_NOT_FOUND", "Session not found");
        }
        AssistantProviderRateLimiter.Decision decision = rateLimiter.tryAcquire(userEmail);
        if (!decision.allowed()) {
            return new UsageOutcome(429, "RATE_LIMITED", "Too many usage reports", decision.retryAfterSeconds());
        }

        String modelTag = normalizeModel(provider, report.model(), userEmail);
        Timer.builder(LATENCY_TIMER)
                .description("Latency of assistant LLM provider calls as reported by the client")
                .tag("provider", provider)
                .tag("model", modelTag)
                .register(registry)
                .record(report.latencyMs(), TimeUnit.MILLISECONDS);
        increment(provider, modelTag, "input", report.inputTokens());
        increment(provider, modelTag, "output", report.outputTokens());
        increment(provider, modelTag, "cache_read", report.cacheReadTokens());
        increment(provider, modelTag, "cache_write", report.cacheWriteTokens());
        return UsageOutcome.accepted();
    }

    String normalizeModel(String provider, String rawModel) {
        return normalizeModel(provider, rawModel, null);
    }

    String normalizeModel(String provider, String rawModel, String userEmail) {
        String model = canonicalModel(provider, rawModel);
        if (model == null) {
            return OTHER_MODEL;
        }
        if (model.equals(managedModelTag(provider))) {
            return model;
        }
        String userKey = userEmail == null ? null : userEmail.trim().toLowerCase(Locale.ROOT);
        synchronized (admittedModels) {
            if (admittedModels.contains(model)) {
                return model;
            }
            if (admittedModels.size() >= MAX_MODEL_TAG_VALUES) {
                return OTHER_MODEL;
            }
            if (userKey != null) {
                int admitted = admissionsByUser.getOrDefault(userKey, 0);
                if (admitted >= MAX_NEW_MODEL_TAGS_PER_USER) {
                    return OTHER_MODEL;
                }
                admissionsByUser.put(userKey, admitted + 1);
            }
            admittedModels.add(model);
            return model;
        }
    }

    private String managedModelTag(String provider) {
        if (proxyService == null || !proxyService.isManaged()) {
            return null;
        }
        AssistantProviderProxyService.ProviderConfigView config = proxyService.config();
        if (!provider.equals(config.provider())) {
            return null;
        }
        return canonicalModel(config.provider(), config.model());
    }

    private static String canonicalModel(String provider, String rawModel) {
        if (rawModel == null || provider == null) {
            return null;
        }
        String model = rawModel.trim().toLowerCase(Locale.ROOT);
        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }
        if (model.isEmpty() || model.length() > 100 || !RAW_MODEL_PATTERN.matcher(model).matches()) {
            return null;
        }
        String previous;
        do {
            previous = model;
            model = DATE_SUFFIX.matcher(model).replaceFirst("");
        } while (!model.equals(previous) && !model.isEmpty());
        if (model.isEmpty() || !matchesProvider(provider, model)) {
            return null;
        }
        if (model.length() > MAX_MODEL_TAG_LENGTH) {
            model = model.substring(0, MAX_MODEL_TAG_LENGTH);
        }
        return model;
    }

    private static boolean matchesProvider(String provider, String model) {
        return switch (provider) {
            case AssistantProviderProxyService.CLAUDE -> model.startsWith("claude-");
            case AssistantProviderProxyService.OPENAI -> OPENAI_MODEL.matcher(model).matches();
            case AssistantProviderProxyService.GEMINI -> model.startsWith("gemini-");
            default -> false;
        };
    }

    private boolean isTooOld(AssistantSessionDocument session) {
        Instant createdAt = session.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        return createdAt.plus(Duration.ofMinutes(maxSessionAgeMinutes)).isBefore(clock.instant());
    }

    private void increment(String provider, String modelTag, String kind, Long value) {
        if (value == null || value <= 0) {
            return;
        }
        Counter.builder(TOKEN_COUNTER)
                .description("Tokens used by assistant LLM provider calls as reported by the client")
                .tag("provider", provider)
                .tag("model", modelTag)
                .tag("kind", kind)
                .register(registry)
                .increment(value);
    }

    private static boolean tokensInRange(Long value) {
        return value == null || (value >= 0 && value <= MAX_TOKENS_PER_KIND);
    }
}
