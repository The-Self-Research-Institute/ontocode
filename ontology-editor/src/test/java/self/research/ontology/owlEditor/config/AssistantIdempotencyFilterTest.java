package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;
import self.research.ontology.owlEditor.service.AssistantIdempotencyStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AssistantIdempotencyFilterTest {

    private InMemoryStore store;
    private MutableClock clock;
    private TestAssistantController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        clock = new MutableClock();
        controller = new TestAssistantController();
        AssistantIdempotencyFilter filter = new AssistantIdempotencyFilter(store, 1024, 1024);
        AssistantIdempotencyInterceptor interceptor =
                new AssistantIdempotencyInterceptor(store, Duration.ofSeconds(300), clock);
        HandlerInterceptor authGate = new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("deny".equals(request.getHeader("X-Test-Auth"))) {
                    response.setStatus(403);
                    return false;
                }
                return true;
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .addInterceptors(authGate, interceptor)
                .build();
    }

    @Test
    void identicalRetryReplaysTheStoredResponseWithoutRunningTheHandlerAgain() throws Exception {
        MvcResult first = mvc.perform(propose("user@example.com", "k1", "{\"groups\":[1]}")).andReturn();
        MvcResult second = mvc.perform(propose("user@example.com", "k1", "{\"groups\":[1]}")).andReturn();

        assertEquals(200, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertNull(first.getResponse().getHeader("Idempotency-Replayed"));
        assertTrue(second.getResponse().getContentType().startsWith("application/json"));
        assertEquals(1, controller.proposeCalls.get());
        assertEquals("{\"groups\":[1]}", controller.lastBody);
    }

    @Test
    void sameKeyWithADifferentBodyIs422() throws Exception {
        mvc.perform(propose("user@example.com", "k1", "{\"groups\":[1]}")).andReturn();

        MvcResult reused = mvc.perform(propose("user@example.com", "k1", "{\"groups\":[2]}")).andReturn();

        assertEquals(422, reused.getResponse().getStatus());
        assertTrue(reused.getResponse().getContentAsString().contains("\"errorCode\":\"IDEMPOTENCY_KEY_REUSED\""));
        assertTrue(reused.getResponse().getContentAsString().contains("\"ok\":false"));
        assertEquals(1, controller.proposeCalls.get());
    }

    @Test
    void sameKeyWhileTheFirstIsStillRunningIs409AndTheFirstStillCompletes() throws Exception {
        controller.block = new CountDownLatch(1);
        controller.entered = new CountDownLatch(1);
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(controller.entered.await(10, TimeUnit.SECONDS));

        MvcResult concurrent = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();
        controller.block.countDown();
        MvcResult firstResult = first.get(10, TimeUnit.SECONDS);

        assertEquals(409, concurrent.getResponse().getStatus());
        assertTrue(concurrent.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(200, firstResult.getResponse().getStatus());
        assertEquals(1, controller.proposeCalls.get());
        assertEquals(State.COMPLETED, store.only().getState());
    }

    @Test
    void staleInFlightRecordIsTakenOverAfterTheTimeout() throws Exception {
        store.records.put(recordIdFor("user@example.com", "/api/v1/code-assistant/sessions/s1/propose", "k1"),
                AssistantIdempotencyRecordDocument.builder()
                        .id(recordIdFor("user@example.com", "/api/v1/code-assistant/sessions/s1/propose", "k1"))
                        .bodyHash(AssistantIdempotencyFilter.sha256Hex("{\"a\":1}".getBytes()))
                        .state(State.IN_FLIGHT).ownerToken("crashed-node")
                        .updatedAt(clock.instant().minusSeconds(301))
                        .expiresAt(clock.instant().plus(Duration.ofHours(23)))
                        .build());

        MvcResult result = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, controller.proposeCalls.get());
        assertEquals(State.COMPLETED, store.only().getState());
        assertNotEquals("crashed-node", store.only().getOwnerToken());
    }

    @Test
    void recentInFlightRecordIsNotTakenOver() throws Exception {
        String id = recordIdFor("user@example.com", "/api/v1/code-assistant/sessions/s1/propose", "k1");
        store.records.put(id, AssistantIdempotencyRecordDocument.builder().id(id)
                .bodyHash(AssistantIdempotencyFilter.sha256Hex("{\"a\":1}".getBytes()))
                .state(State.IN_FLIGHT).ownerToken("other-node")
                .updatedAt(clock.instant().minusSeconds(30))
                .expiresAt(clock.instant().plus(Duration.ofHours(23)))
                .build());

        MvcResult result = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();

        assertEquals(409, result.getResponse().getStatus());
        assertEquals(0, controller.proposeCalls.get());
    }

    @Test
    void expiredCompletedRecordIsNotReplayed() throws Exception {
        mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();
        clock.advance(Duration.ofHours(25));

        MvcResult later = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();

        assertEquals(200, later.getResponse().getStatus());
        assertNull(later.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(2, controller.proposeCalls.get());
    }

    @Test
    void keysAreScopedPerCallerAndPerPath() throws Exception {
        mvc.perform(propose("alice@example.com", "shared", "{\"a\":1}")).andReturn();
        MvcResult bob = mvc.perform(propose("bob@example.com", "shared", "{\"a\":1}")).andReturn();
        MvcResult otherSession = mvc.perform(post("/api/v1/code-assistant/sessions/s2/propose")
                .header("Authorization", bearer("alice@example.com"))
                .header("Idempotency-Key", "shared")
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")).andReturn();

        assertNull(bob.getResponse().getHeader("Idempotency-Replayed"));
        assertNull(otherSession.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(3, controller.proposeCalls.get());
        assertEquals(3, store.records.size());
    }

    @Test
    void serverErrorsAreNotStoredSoARetryRunsAgain() throws Exception {
        controller.failNext = true;
        MvcResult failed = mvc.perform(apply("user@example.com", "k1")).andReturn();
        MvcResult retried = mvc.perform(apply("user@example.com", "k1")).andReturn();

        assertEquals(500, failed.getResponse().getStatus());
        assertEquals(200, retried.getResponse().getStatus());
        assertNull(retried.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(2, controller.applyCalls.get());
        assertEquals(State.COMPLETED, store.only().getState());
    }

    @Test
    void clientErrorsAreReplayedLikeSuccesses() throws Exception {
        MvcResult first = mvc.perform(post("/api/v1/code-assistant/sessions")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        MvcResult second = mvc.perform(post("/api/v1/code-assistant/sessions")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertEquals(400, first.getResponse().getStatus());
        assertEquals(400, second.getResponse().getStatus());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, controller.createCalls.get());
    }

    @Test
    void rateLimitedResponsesAreNotStored() throws Exception {
        controller.rateLimitNext = true;
        MvcResult limited = mvc.perform(post("/api/v1/code-assistant/sessions")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"p\"}")).andReturn();
        MvcResult retry = mvc.perform(post("/api/v1/code-assistant/sessions")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"p\"}")).andReturn();

        assertEquals(429, limited.getResponse().getStatus());
        assertEquals(200, retry.getResponse().getStatus());
        assertEquals(2, controller.createCalls.get());
    }

    @Test
    void aRequestRejectedByAnEarlierInterceptorNeverCreatesARecordOrReplays() throws Exception {
        mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();

        MvcResult denied = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")
                .header("X-Test-Auth", "deny")).andReturn();
        MvcResult deniedFresh = mvc.perform(propose("user@example.com", "k2", "{\"a\":1}")
                .header("X-Test-Auth", "deny")).andReturn();

        assertEquals(403, denied.getResponse().getStatus());
        assertNull(denied.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(403, deniedFresh.getResponse().getStatus());
        assertEquals(1, store.records.size());
    }

    @Test
    void requestsWithoutTheHeaderOrOnOtherEndpointsAreUntouched() throws Exception {
        mvc.perform(post("/api/v1/code-assistant/sessions/s1/propose")
                .header("Authorization", bearer("user@example.com"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")).andReturn();
        mvc.perform(post("/api/v1/code-assistant/sessions/s1/propose")
                .header("Authorization", bearer("user@example.com"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")).andReturn();
        MvcResult tool = mvc.perform(post("/api/v1/code-assistant/sessions/s1/tools/read_context")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"t\":1}")).andReturn();
        mvc.perform(post("/api/v1/code-assistant/sessions/s1/tools/read_context")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"t\":1}")).andReturn();
        MvcResult getCall = mvc.perform(get("/api/v1/code-assistant/sessions/s1/propose")
                .header("Authorization", bearer("user@example.com"))
                .header("Idempotency-Key", "k1")).andReturn();

        assertEquals(2, controller.proposeCalls.get());
        assertEquals(2, controller.toolCalls.get());
        assertEquals(200, tool.getResponse().getStatus());
        assertEquals(200, getCall.getResponse().getStatus());
        assertTrue(store.records.isEmpty());
        assertNull(tool.getRequest().getAttribute(AssistantIdempotencyFilter.ATTR_RECORD_ID));
        assertFalse(tool.getResponse().containsHeader("Idempotency-Replayed"));
    }

    @Test
    void callsWithoutAnIdentityPassThroughWithoutIdempotency() throws Exception {
        mvc.perform(post("/api/v1/code-assistant/sessions/s1/propose")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")).andReturn();
        mvc.perform(post("/api/v1/code-assistant/sessions/s1/propose")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")).andReturn();

        assertEquals(2, controller.proposeCalls.get());
        assertTrue(store.records.isEmpty());
    }

    @Test
    void oversizedBodiesAreForwardedIntactWithoutIdempotency() throws Exception {
        String big = "{\"text\":\"" + "x".repeat(4000) + "\"}";

        mvc.perform(propose("user@example.com", "k1", big)).andReturn();
        mvc.perform(propose("user@example.com", "k1", big)).andReturn();

        assertEquals(2, controller.proposeCalls.get());
        assertEquals(big, controller.lastBody);
        assertTrue(store.records.isEmpty());
    }

    @Test
    void responsesTooLargeToStoreAreNotKeptSoRetriesRunAgain() throws Exception {
        controller.hugeResponse = true;
        mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();
        MvcResult second = mvc.perform(propose("user@example.com", "k1", "{\"a\":1}")).andReturn();

        assertEquals(200, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().length() > 1024);
        assertEquals(2, controller.proposeCalls.get());
        assertTrue(store.records.isEmpty());
    }

    private static MockHttpServletRequestBuilder propose(String email, String key, String body) {
        return post("/api/v1/code-assistant/sessions/s1/propose")
                .header("Authorization", bearer(email))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder apply(String email, String key) {
        return post("/api/v1/code-assistant/sessions/s1/groups/g1/apply")
                .header("Authorization", bearer(email))
                .header("Idempotency-Key", key);
    }

    private static String recordIdFor(String email, String path, String key) {
        return AssistantIdempotencyFilter.sha256Hex(email + "\nPOST\n" + path + "\n" + key);
    }

    private static String bearer(String email) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + email + "\"}").getBytes());
        return "Bearer header." + payload + ".signature";
    }

    @RestController
    static class TestAssistantController {
        final AtomicInteger proposeCalls = new AtomicInteger();
        final AtomicInteger applyCalls = new AtomicInteger();
        final AtomicInteger createCalls = new AtomicInteger();
        final AtomicInteger toolCalls = new AtomicInteger();
        volatile String lastBody;
        volatile CountDownLatch block;
        volatile CountDownLatch entered;
        volatile boolean failNext;
        volatile boolean rateLimitNext;
        volatile boolean hugeResponse;

        @PostMapping("/api/v1/code-assistant/sessions")
        ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
            int n = createCalls.incrementAndGet();
            if (rateLimitNext) {
                rateLimitNext = false;
                return ResponseEntity.status(429).header("Retry-After", "1")
                        .body(Map.of("ok", false, "errorCode", "RATE_LIMITED"));
            }
            if (!body.containsKey("projectId")) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "projectId is required"));
            }
            return ResponseEntity.ok(Map.of("sessionId", "session-" + n));
        }

        @PostMapping("/api/v1/code-assistant/sessions/{sessionId}/propose")
        ResponseEntity<Map<String, Object>> propose(@PathVariable String sessionId, @RequestBody String body)
                throws InterruptedException {
            int n = proposeCalls.incrementAndGet();
            lastBody = body;
            if (entered != null) {
                entered.countDown();
            }
            if (block != null) {
                block.await(10, TimeUnit.SECONDS);
            }
            if (hugeResponse) {
                return ResponseEntity.ok(Map.of("ok", true, "padding", "y".repeat(5000)));
            }
            return ResponseEntity.ok(Map.of("ok", true, "serverGroupId", "group-" + n));
        }

        @GetMapping("/api/v1/code-assistant/sessions/{sessionId}/propose")
        ResponseEntity<Map<String, Object>> proposeGet(@PathVariable String sessionId) {
            return ResponseEntity.ok(Map.of("ok", true));
        }

        @PostMapping("/api/v1/code-assistant/sessions/{sessionId}/groups/{groupId}/apply")
        ResponseEntity<Map<String, Object>> apply(@PathVariable String sessionId, @PathVariable String groupId) {
            int n = applyCalls.incrementAndGet();
            if (failNext) {
                failNext = false;
                return ResponseEntity.status(500).body(Map.of("ok", false, "message", "boom"));
            }
            return ResponseEntity.ok(Map.of("ok", true, "applied", true, "newRevision", n));
        }

        @PostMapping("/api/v1/code-assistant/sessions/{sessionId}/tools/read_context")
        ResponseEntity<Map<String, Object>> tool(@PathVariable String sessionId, @RequestBody String body) {
            toolCalls.incrementAndGet();
            return ResponseEntity.ok(Map.of("ok", true));
        }
    }

    static final class InMemoryStore implements AssistantIdempotencyStore {
        final Map<String, AssistantIdempotencyRecordDocument> records = new HashMap<>();

        synchronized AssistantIdempotencyRecordDocument only() {
            assertEquals(1, records.size());
            return records.values().iterator().next();
        }

        @Override
        public synchronized boolean tryInsert(AssistantIdempotencyRecordDocument inFlight) {
            if (records.containsKey(inFlight.getId())) {
                return false;
            }
            records.put(inFlight.getId(), inFlight.toBuilder().build());
            return true;
        }

        @Override
        public synchronized Optional<AssistantIdempotencyRecordDocument> find(String id) {
            return Optional.ofNullable(records.get(id)).map(r -> r.toBuilder().build());
        }

        @Override
        public synchronized boolean tryTakeOver(String id, Instant staleInFlightBefore, Instant now,
                                                AssistantIdempotencyRecordDocument replacement) {
            AssistantIdempotencyRecordDocument existing = records.get(id);
            if (existing == null) {
                return false;
            }
            boolean expired = existing.getExpiresAt().isBefore(now);
            boolean stale = existing.getState() == State.IN_FLIGHT
                    && existing.getUpdatedAt().isBefore(staleInFlightBefore);
            if (!expired && !stale) {
                return false;
            }
            records.put(id, replacement.toBuilder().build());
            return true;
        }

        @Override
        public synchronized boolean complete(String id, String ownerToken, int status, String contentType,
                                             String body, Instant now) {
            AssistantIdempotencyRecordDocument existing = records.get(id);
            if (existing == null || !ownerToken.equals(existing.getOwnerToken())
                    || existing.getState() != State.IN_FLIGHT) {
                return false;
            }
            records.put(id, existing.toBuilder().state(State.COMPLETED).responseStatus(status)
                    .responseContentType(contentType).responseBody(body).updatedAt(now).build());
            return true;
        }

        @Override
        public synchronized void abandon(String id, String ownerToken) {
            AssistantIdempotencyRecordDocument existing = records.get(id);
            if (existing != null && ownerToken.equals(existing.getOwnerToken())
                    && existing.getState() == State.IN_FLIGHT) {
                records.remove(id);
            }
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
