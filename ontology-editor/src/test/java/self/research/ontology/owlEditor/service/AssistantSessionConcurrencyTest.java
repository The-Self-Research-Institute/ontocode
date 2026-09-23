package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantSessionConcurrencyTest {

    @Mock
    private AssistantSessionRepository sessionRepository;

    @Mock
    private ProjectMetadataService metadataService;

    @Mock
    private MongoTemplate mongoTemplate;

    private AssistantSessionService service;
    private FakeAtomicBudgetStore fakeStore;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantSessionService(sessionRepository, metadataService, mongoTemplate);
        fakeStore = new FakeAtomicBudgetStore();

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantSessionDocument.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    String sessionId = String.valueOf(query.getQueryObject().get("_id"));
                    return fakeStore.findAndModifyDecrementIfPositive(sessionId);
                });
    }

    @Test
    void tryConsumeRetrievalAttemptNeverDoubleSpendsUnderHeavyConcurrency() throws InterruptedException {
        int startingBudget = 50;
        int callerCount = 300;
        fakeStore.seed("session-1", startingBudget);

        ConcurrentRun run = runConcurrently("session-1", callerCount);

        assertEquals(startingBudget, run.successCount());
        assertEquals(callerCount - startingBudget, run.failureCount());
        assertEquals(callerCount, run.successCount() + run.failureCount());
        assertEquals(0, fakeStore.currentRemaining("session-1"));
    }

    @Test
    void tryConsumeRetrievalAttemptNeverGoesNegativeAcrossManyRounds() throws InterruptedException {
        for (int round = 0; round < 20; round++) {
            String sessionId = "session-round-" + round;
            int startingBudget = 1 + round;
            int callerCount = 40;
            fakeStore.seed(sessionId, startingBudget);

            ConcurrentRun run = runConcurrently(sessionId, callerCount);

            assertEquals(startingBudget, run.successCount());
            assertTrue(fakeStore.currentRemaining(sessionId) >= 0);
            assertEquals(0, fakeStore.currentRemaining(sessionId));
        }
    }

    @Test
    void tryConsumeRetrievalAttemptKeepsSeparateSessionsIndependentUnderConcurrency() throws InterruptedException {
        fakeStore.seed("session-a", 20);
        fakeStore.seed("session-b", 30);

        int callersPerSession = 100;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callersPerSession * 2);
        AtomicInteger successA = new AtomicInteger();
        AtomicInteger successB = new AtomicInteger();

        for (int i = 0; i < callersPerSession; i++) {
            pool.submit(() -> awaitAndConsume(start, done, successA, "session-a"));
            pool.submit(() -> awaitAndConsume(start, done, successB, "session-b"));
        }

        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished);
        assertEquals(20, successA.get());
        assertEquals(30, successB.get());
        assertEquals(0, fakeStore.currentRemaining("session-a"));
        assertEquals(0, fakeStore.currentRemaining("session-b"));
    }

    private void awaitAndConsume(CountDownLatch start, CountDownLatch done, AtomicInteger successCounter, String sessionId) {
        try {
            start.await();
            if (service.tryConsumeRetrievalAttempt(sessionId)) {
                successCounter.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    private ConcurrentRun runConcurrently(String sessionId, int callerCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, callerCount));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callerCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < callerCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (service.tryConsumeRetrievalAttempt(sessionId)) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished);

        return new ConcurrentRun(successCount.get(), failureCount.get());
    }

    private record ConcurrentRun(int successCount, int failureCount) {
    }

    private static final class FakeAtomicBudgetStore {
        private final Map<String, AtomicInteger> remainingBySessionId = new ConcurrentHashMap<>();
        private final Map<String, AssistantSessionStatus> statusBySessionId = new ConcurrentHashMap<>();

        void seed(String sessionId, int startingBudget) {
            remainingBySessionId.put(sessionId, new AtomicInteger(startingBudget));
            statusBySessionId.put(sessionId, AssistantSessionStatus.ACTIVE);
        }

        int currentRemaining(String sessionId) {
            return remainingBySessionId.get(sessionId).get();
        }

        AssistantSessionDocument findAndModifyDecrementIfPositive(String sessionId) {
            AtomicInteger counter = remainingBySessionId.get(sessionId);
            if (counter == null || statusBySessionId.get(sessionId) != AssistantSessionStatus.ACTIVE) {
                return null;
            }

            while (true) {
                int current = counter.get();
                if (current <= 0) {
                    return null;
                }
                int next = current - 1;
                if (counter.compareAndSet(current, next)) {
                    return AssistantSessionDocument.builder()
                            .id(sessionId)
                            .status(AssistantSessionStatus.ACTIVE)
                            .retrievalAttemptsRemaining(next)
                            .build();
                }
            }
        }
    }
}
