package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.repository.JdbcAssistantSessionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantSessionConcurrencyTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcAssistantSessionRepository sessionRepository = new JdbcAssistantSessionRepository(jdbc);
    private AssistantSessionService service;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from assistant_session");
        ProjectMetadataService metadata = Mockito.mock(ProjectMetadataService.class);
        service = new AssistantSessionService(sessionRepository, metadata);
        ReflectionTestUtils.setField(service, "maxActiveSessionsPerUser", 20);
        ReflectionTestUtils.setField(service, "deadlineSeconds", 300L);
    }

    private String seed(int attempts, int tokens) {
        AssistantSessionDocument session = sessionRepository.save(AssistantSessionDocument.builder()
                .projectId("p1").userEmail("u@example.com").status(AssistantSessionStatus.ACTIVE)
                .retrievalAttemptsRemaining(attempts).tokenBudgetRemaining(tokens)
                .expiresAt(Instant.now().plusSeconds(300)).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        return session.getId();
    }

    private int race(int callers, java.util.function.Supplier<Boolean> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return action.get();
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                wins++;
            }
        }
        pool.shutdownNow();
        return wins;
    }

    private int remainingAttempts(String id) {
        return jdbc.queryForObject("select retrieval_attempts_remaining from assistant_session where id = ?",
                Integer.class, id);
    }

    private int remainingTokens(String id) {
        return jdbc.queryForObject("select token_budget_remaining from assistant_session where id = ?",
                Integer.class, id);
    }

    @Test
    void retrievalAttemptsAreNeverSpentTwiceUnderHeavyConcurrency() throws Exception {
        String id = seed(50, 1000);

        int wins = race(300, () -> service.tryConsumeRetrievalAttempt(id));

        assertEquals(50, wins);
        assertEquals(0, remainingAttempts(id));
    }

    @Test
    void tokenBudgetIsNeverOverspentAndTheCountersAddUpExactly() throws Exception {
        String id = seed(10, 1000);

        int wins = race(200, () -> service.tryConsumeTokenBudget(id, 30));

        assertEquals(33, wins);
        assertEquals(1000 - 33 * 30, remainingTokens(id));
    }

    @Test
    void separateSessionsStayIndependent() throws Exception {
        String a = seed(20, 100);
        String b = seed(30, 100);

        int winsA = race(100, () -> service.tryConsumeRetrievalAttempt(a));
        int winsB = race(100, () -> service.tryConsumeRetrievalAttempt(b));

        assertEquals(20, winsA);
        assertEquals(30, winsB);
        assertEquals(0, remainingAttempts(a));
        assertEquals(0, remainingAttempts(b));
    }

    @Test
    void aCompletedSessionSpendsNothing() throws Exception {
        String id = seed(5, 500);
        service.completeSession(id);

        assertEquals(0, race(20, () -> service.tryConsumeRetrievalAttempt(id)));
        assertEquals(0, race(20, () -> service.tryConsumeTokenBudget(id, 1)));
        assertEquals(5, remainingAttempts(id));
        assertEquals(500, remainingTokens(id));
    }

    @Test
    void concurrentCreatesForOneUserNeverExceedTheActiveCap() throws Exception {
        int wins = race(60, () -> service.createSession("p1", "u@example.com", null, "ask", null, null, null,
                Optional::empty).created());

        assertEquals(20, wins);
        assertEquals(20, jdbc.queryForObject("select count(*) from assistant_session", Integer.class));
    }
}
