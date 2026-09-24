package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantUsageReport;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantUsageMetricsServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private AssistantSessionRepository sessionRepository;

    private SimpleMeterRegistry registry;
    private AssistantUsageMetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new SimpleMeterRegistry();
        service = new AssistantUsageMetricsService(registry, sessionRepository, 5);
        when(sessionRepository.findByIdAndUserEmail(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessionRepository.findByIdAndUserEmail("s1", EMAIL)).thenReturn(Optional.of(session(Instant.now())));
    }

    private static AssistantSessionDocument session(Instant createdAt) {
        return AssistantSessionDocument.builder().id("s1").userEmail(EMAIL).projectId("p").createdAt(createdAt).build();
    }

    private static AssistantUsageReport report(String provider, String model, Long latency, Long in, Long out,
                                               Long cacheRead, Long cacheWrite) {
        return new AssistantUsageReport(provider, model, latency, in, out, cacheRead, cacheWrite);
    }

    @Test
    void recordsLatencyAndEachTokenKind() {
        AssistantUsageMetricsService.UsageOutcome outcome = service.record("s1", EMAIL,
                report("Claude", "claude-sonnet-4-5", 2500L, 1000L, 200L, 700L, 30L));

        assertTrue(outcome.isOk());
        assertEquals(204, outcome.status());
        var timer = registry.get(AssistantUsageMetricsService.LATENCY_TIMER)
                .tags("provider", "claude", "model", "claude-sonnet-4-5").timer();
        assertEquals(1, timer.count());
        assertEquals(2500.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertEquals(1000.0, tokens("claude", "claude-sonnet-4-5", "input"));
        assertEquals(200.0, tokens("claude", "claude-sonnet-4-5", "output"));
        assertEquals(700.0, tokens("claude", "claude-sonnet-4-5", "cache_read"));
        assertEquals(30.0, tokens("claude", "claude-sonnet-4-5", "cache_write"));
    }

    @Test
    void missingTokenKindsAreNotRecorded() {
        service.record("s1", EMAIL, report("openai", "gpt-4o", 10L, 5L, null, null, null));

        assertEquals(5.0, tokens("openai", "gpt-4o", "input"));
        assertNull(registry.find(AssistantUsageMetricsService.TOKEN_COUNTER).tag("kind", "output").counter());
    }

    @Test
    void countersAccumulateAcrossReports() {
        service.record("s1", EMAIL, report("gemini", "gemini-2.5-flash", 10L, 5L, 1L, null, null));
        service.record("s1", EMAIL, report("gemini", "gemini-2.5-flash", 20L, 7L, 1L, null, null));

        assertEquals(12.0, tokens("gemini", "gemini-2.5-flash", "input"));
        assertEquals(2, registry.get(AssistantUsageMetricsService.LATENCY_TIMER)
                .tags("provider", "gemini").timer().count());
    }

    @Test
    void rejectsUnknownProviderMissingModelAndOutOfRangeNumbers() {
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL, report("mistral", "m", 1L, 1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL, report("claude", " ", 1L, 1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL, report("claude", "claude-x", null, 1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL, report("claude", "claude-x", -5L, 1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL,
                report("claude", "claude-x", AssistantUsageMetricsService.MAX_LATENCY_MS + 1, 1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL, report("claude", "claude-x", 1L, -1L, 1L, null, null)).errorCode());
        assertEquals("VALIDATION_FAILED", service.record("s1", EMAIL,
                report("claude", "claude-x", 1L, 1L, 1L, AssistantUsageMetricsService.MAX_TOKENS_PER_KIND + 1, null)).errorCode());
        assertEquals(400, service.record("s1", EMAIL, null).status());
        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void rejectsSessionNotOwnedByCaller() {
        AssistantUsageMetricsService.UsageOutcome outcome = service.record("s1", "intruder@example.com",
                report("claude", "claude-sonnet-4-5", 1L, 1L, 1L, null, null));

        assertEquals(404, outcome.status());
        assertEquals("SESSION_NOT_FOUND", outcome.errorCode());
        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void rejectsSessionOlderThanTheAllowedAge() {
        when(sessionRepository.findByIdAndUserEmail("old", EMAIL))
                .thenReturn(Optional.of(session(Instant.now().minusSeconds(2 * 3600))));

        AssistantUsageMetricsService.UsageOutcome outcome = service.record("old", EMAIL,
                report("claude", "claude-sonnet-4-5", 1L, 1L, 1L, null, null));

        assertEquals("SESSION_NOT_FOUND", outcome.errorCode());
    }

    @Test
    void rateLimitsUsageReportsPerUser() {
        for (int i = 0; i < 5; i++) {
            assertTrue(service.record("s1", EMAIL, report("claude", "claude-sonnet-4-5", 1L, 1L, 1L, null, null)).isOk());
        }
        AssistantUsageMetricsService.UsageOutcome limited = service.record("s1", EMAIL,
                report("claude", "claude-sonnet-4-5", 1L, 1L, 1L, null, null));

        assertEquals(429, limited.status());
        assertEquals("RATE_LIMITED", limited.errorCode());
        assertTrue(limited.retryAfterSeconds() >= 1);
    }

    @Test
    void normalizesModelsIntoStableTagValues() {
        assertEquals("claude-sonnet-4-5", service.normalizeModel("claude", "claude-sonnet-4-5-20250929"));
        assertEquals("claude-3-5-haiku", service.normalizeModel("claude", "Claude-3-5-Haiku-Latest"));
        assertEquals("gpt-4o", service.normalizeModel("openai", "gpt-4o-2024-08-06"));
        assertEquals("o3-mini", service.normalizeModel("openai", "o3-mini"));
        assertEquals("gemini-2.5-pro", service.normalizeModel("gemini", "models/gemini-2.5-pro"));
        assertEquals("other", service.normalizeModel("claude", "gpt-4o"));
        assertEquals("other", service.normalizeModel("openai", "my model; drop table"));
        assertEquals("other", service.normalizeModel("gemini", "x".repeat(150)));
    }

    @Test
    void distinctModelTagValuesAreCapped() {
        for (int i = 0; i < 100; i++) {
            service.normalizeModel("claude", "claude-custom-" + i);
        }
        Set<String> values = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> service.normalizeModel("claude", "claude-custom-" + i))
                .collect(Collectors.toSet());

        assertEquals(AssistantUsageMetricsService.MAX_MODEL_TAG_VALUES + 1, values.size());
        assertTrue(values.contains("other"));
        assertEquals("claude-custom-0", service.normalizeModel("claude", "claude-custom-0"));
    }

    @Test
    void metricTagCardinalityStaysBoundedUnderHostileReports() {
        AssistantUsageMetricsService relaxed = new AssistantUsageMetricsService(registry, sessionRepository, 10_000);
        for (int i = 0; i < 500; i++) {
            relaxed.record("s1", EMAIL, report("openai", "gpt-random-" + i, 1L, 1L, null, null, null));
        }

        Set<String> modelTags = registry.find(AssistantUsageMetricsService.LATENCY_TIMER).timers().stream()
                .map(Meter::getId)
                .map(id -> id.getTag("model"))
                .collect(Collectors.toSet());
        assertTrue(modelTags.size() <= AssistantUsageMetricsService.MAX_MODEL_TAG_VALUES + 1);
        assertFalse(modelTags.contains(null));
    }

    @Test
    void oneUserCannotUseUpAllTheModelTagSlots() {
        for (int i = 0; i < 50; i++) {
            service.normalizeModel("claude", "claude-junk-" + i, "hostile@example.com");
        }

        assertEquals("other", service.normalizeModel("claude", "claude-junk-49", "HOSTILE@example.com"));
        assertEquals("claude-junk-0", service.normalizeModel("claude", "claude-junk-0", "someone@example.com"));
        assertEquals("claude-opus-4-1", service.normalizeModel("claude", "claude-opus-4-1", "someone@example.com"));
        long junkTags = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> service.normalizeModel("claude", "claude-junk-" + i, "someone-else@example.com"))
                .filter(tag -> !tag.equals("other"))
                .count();
        assertEquals(2L * AssistantUsageMetricsService.MAX_NEW_MODEL_TAGS_PER_USER, junkTags);
    }

    @Test
    void theManagedModelAlwaysGetsItsOwnTagEvenWhenTheSlotsAreFull() {
        AssistantProviderProxyService proxy = org.mockito.Mockito.mock(AssistantProviderProxyService.class);
        when(proxy.isManaged()).thenReturn(true);
        when(proxy.config()).thenReturn(
                new AssistantProviderProxyService.ProviderConfigView(true, "claude", "claude-sonnet-4-5-20250929"));
        AssistantUsageMetricsService managed = new AssistantUsageMetricsService(registry, sessionRepository, proxy, 10_000);
        for (int i = 0; i < 100; i++) {
            managed.normalizeModel("claude", "claude-custom-" + i, "user" + i + "@example.com");
        }

        assertEquals("other", managed.normalizeModel("claude", "claude-brand-new", "late@example.com"));
        assertEquals("claude-sonnet-4-5", managed.normalizeModel("claude", "claude-sonnet-4-5", "late@example.com"));
        assertEquals("other", managed.normalizeModel("openai", "claude-sonnet-4-5", "late@example.com"));

        assertTrue(managed.record("s1", EMAIL, report("claude", "claude-sonnet-4-5-20250929", 5L, 1L, null, null, null)).isOk());
        assertEquals(1, registry.get(AssistantUsageMetricsService.LATENCY_TIMER)
                .tags("provider", "claude", "model", "claude-sonnet-4-5").timer().count());
    }

    @Test
    void recordAppliesThePerUserTagCap() {
        AssistantUsageMetricsService relaxed = new AssistantUsageMetricsService(registry, sessionRepository, 10_000);
        for (int i = 0; i < 20; i++) {
            assertTrue(relaxed.record("s1", EMAIL, report("openai", "gpt-junk-" + i, 1L, 1L, null, null, null)).isOk());
        }

        Set<String> modelTags = registry.find(AssistantUsageMetricsService.LATENCY_TIMER).timers().stream()
                .map(meter -> meter.getId().getTag("model"))
                .collect(Collectors.toSet());
        assertEquals(AssistantUsageMetricsService.MAX_NEW_MODEL_TAGS_PER_USER + 1, modelTags.size());
        assertTrue(modelTags.contains("other"));
    }

    private double tokens(String provider, String model, String kind) {
        return registry.get(AssistantUsageMetricsService.TOKEN_COUNTER)
                .tags("provider", provider, "model", model, "kind", kind).counter().count();
    }
}
