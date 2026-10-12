package self.research.ontology.owlEditor.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantUsageReport;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.AssistantEditApplyService;
import self.research.ontology.owlEditor.service.AssistantUsageMetricsService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantActuatorMetricsTest {

    private static final String EMAIL = "someone@example.com";

    @Test
    void assistantMetricsAreQueryableThroughTheActuatorMetricsEndpointWithoutUserTags() throws Throwable {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AssistantMetricsAspect aspect = new AssistantMetricsAspect(registry);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(AssistantEditApplyService.ApplyResult.builder()
                .ok(false).errorCode("STALE_GROUP").build());
        aspect.time(joinPoint, "apply");
        AssistantSessionRepository sessions = mock(AssistantSessionRepository.class);
        when(sessions.findByIdAndUserEmail("s1", EMAIL)).thenReturn(Optional.of(AssistantSessionDocument.builder()
                .id("s1").userEmail(EMAIL).projectId("proj-1").createdAt(Instant.now()).build()));
        new AssistantUsageMetricsService(registry, sessions, 100)
                .record("s1", EMAIL, new AssistantUsageReport("claude", "claude-sonnet-4-5", 40L, 7L, 3L, null, null));
        new ProjectWriteLockRegistry(registry).runExclusive("proj-1", () -> null);

        MetricsEndpoint endpoint = new MetricsEndpoint(registry);

        Set<String> names = endpoint.listNames().getNames();
        assertTrue(names.containsAll(List.of("assistant.operation", "assistant.provider.latency",
                "assistant.provider.tokens", "assistant.lock.wait", "assistant.lock.hold")), names.toString());
        MetricsEndpoint.MetricDescriptor apply = endpoint.metric("assistant.operation", List.of("op:apply"));
        assertNotNull(apply);
        assertEquals(1.0, apply.getMeasurements().get(0).getValue());
        MetricsEndpoint.MetricDescriptor input = endpoint.metric("assistant.provider.tokens", List.of("kind:input"));
        assertEquals(7.0, input.getMeasurements().get(0).getValue());
        for (String name : names) {
            for (MetricsEndpoint.AvailableTag tag : endpoint.metric(name, List.of()).getAvailableTags()) {
                assertFalse(tag.getTag().toLowerCase().contains("user"), name + " " + tag.getTag());
                assertTrue(tag.getValues().stream().noneMatch(value -> value.contains("@") || value.contains("proj-")),
                        name + " " + tag.getTag() + " " + tag.getValues());
            }
        }
    }

    @Test
    void metricsEndpointStaysInTheExposedActuatorEndpoints() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in);
            properties.load(in);
        }

        List<String> exposed = List.of(properties.getProperty("management.endpoints.web.exposure.include", "")
                .split("\\s*,\\s*"));

        assertTrue(exposed.contains("metrics"), exposed.toString());
        assertFalse(exposed.contains("*"), exposed.toString());
        assertFalse(exposed.contains("env"), exposed.toString());
        assertFalse(exposed.contains("configprops"), exposed.toString());
        assertFalse(exposed.contains("heapdump"), exposed.toString());
        assertFalse(exposed.contains("threaddump"), exposed.toString());
        assertFalse(exposed.contains("loggers"), exposed.toString());
    }
}
