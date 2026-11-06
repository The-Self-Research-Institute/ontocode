package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AssistantSessionServiceTest {

    @Mock
    private AssistantSessionRepository sessionRepository;

    @Mock
    private ProjectMetadataService metadataService;

    @Mock
    private AssistantAuditService auditService;

    private AssistantSessionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantSessionService(sessionRepository, metadataService, auditService);
        ReflectionTestUtils.setField(service, "defaultRetrievalAttempts", 5);
        ReflectionTestUtils.setField(service, "defaultTokenBudget", 8000);
        ReflectionTestUtils.setField(service, "deadlineSeconds", 300L);
        ReflectionTestUtils.setField(service, "maxActiveSessionsPerUser", 20);
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            AssistantSessionDocument doc = inv.getArgument(0);
            if (doc.getId() == null) {
                doc.setId("session-1");
            }
            return doc;
        });
    }

    @Test
    void createSessionPinsCurrentRevisionAndDefaultBudgets() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(42L);

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "why is this class inconsistent?");

        assertEquals(42L, session.getPinnedRevision());
        assertEquals(AssistantSessionStatus.ACTIVE, session.getStatus());
        assertEquals(5, session.getRetrievalAttemptsRemaining());
        assertEquals(8000, session.getTokenBudgetRemaining());
        assertTrue(session.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void createSessionStoresProviderAndModelAndAuditsTheCreation() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(7L);

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "openai", "gpt-5");

        assertEquals("openai", session.getProvider());
        assertEquals("gpt-5", session.getModel());
        org.mockito.ArgumentCaptor<AssistantSessionDocument> saved =
                org.mockito.ArgumentCaptor.forClass(AssistantSessionDocument.class);
        org.mockito.Mockito.verify(sessionRepository).save(saved.capture());
        assertEquals("openai", saved.getValue().getProvider());
        assertEquals("gpt-5", saved.getValue().getModel());

        org.mockito.ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> event =
                org.mockito.ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        org.mockito.Mockito.verify(auditService).record(event.capture());
        assertEquals("user@example.com", event.getValue().actor());
        assertEquals("proj-1", event.getValue().projectId());
        assertEquals("session-1", event.getValue().sessionId());
        assertEquals("session_create", event.getValue().operation());
        assertEquals(7L, event.getValue().sourceRevision());
        assertEquals("openai", event.getValue().provider());
        assertEquals("gpt-5", event.getValue().model());
        assertEquals("ok", event.getValue().outcome());
    }

    @Test
    void createSessionAcceptsABudgetOverrideWithinRange() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(1L);

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "claude", "m", 12000, 7);

        assertEquals(12000, session.getTokenBudgetRemaining());
        assertEquals(7, session.getRetrievalAttemptsRemaining());
    }

    @Test
    void createSessionHonoursABudgetUpToTheNewMaximum() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(1L);

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "claude", "m", 35000, 8);

        assertEquals(35000, session.getTokenBudgetRemaining());
    }

    @Test
    void createSessionClampsAnOutOfRangeBudgetInsteadOfRejectingIt() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(1L);

        AssistantSessionDocument tooHigh = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "claude", "m", 999999, 999);
        AssistantSessionDocument tooLow = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "claude", "m", 10, 0);

        assertEquals(50000, tooHigh.getTokenBudgetRemaining());
        assertEquals(30, tooHigh.getRetrievalAttemptsRemaining());
        assertEquals(2000, tooLow.getTokenBudgetRemaining());
        assertEquals(2, tooLow.getRetrievalAttemptsRemaining());
    }

    @Test
    void createSessionFallsBackToDefaultsWhenNoOverrideIsGiven() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(1L);

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", "/doc.owl", "ask", "ctx", "claude", "m", null, null);

        assertEquals(8000, session.getTokenBudgetRemaining());
        assertEquals(5, session.getRetrievalAttemptsRemaining());
    }

    @Test
    void legacyCreateSessionLeavesProviderAndModelEmpty() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(7L);

        AssistantSessionDocument session = service.createSession("proj-1", "user@example.com", null, "ask", null);

        assertEquals(null, session.getProvider());
        assertEquals(null, session.getModel());
    }

    @Test
    void auditFailureNeverFailsSessionCreation() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(7L);
        org.mockito.Mockito.doThrow(new RuntimeException("database down")).when(auditService).record(any());

        AssistantSessionDocument session = service.createSession(
                "proj-1", "user@example.com", null, "ask", null, "claude", "m");

        assertEquals("session-1", session.getId());
    }

    @Test
    void rejectedCreatesAreAuditedWithTheErrorCode() {
        service.recordCreateRejected("user@example.com", "proj-1", "claude", "m", "RATE_LIMITED", "retryAfterSeconds=3");

        org.mockito.ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> event =
                org.mockito.ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        org.mockito.Mockito.verify(auditService).record(event.capture());
        assertEquals("rejected", event.getValue().outcome());
        assertEquals("RATE_LIMITED", event.getValue().errorCode());
        assertEquals(null, event.getValue().sessionId());
    }

    @Test
    void getActiveSessionReturnsEmptyWhenNotFound() {
        when(sessionRepository.findByIdAndUserEmail("missing", "user@example.com")).thenReturn(Optional.empty());

        assertTrue(service.getActiveSession("missing", "user@example.com").isEmpty());
    }

    @Test
    void getActiveSessionReturnsEmptyWhenNotActive() {
        AssistantSessionDocument completed = baseSession().status(AssistantSessionStatus.COMPLETED).build();
        when(sessionRepository.findByIdAndUserEmail("session-1", "user@example.com"))
                .thenReturn(Optional.of(completed));

        assertTrue(service.getActiveSession("session-1", "user@example.com").isEmpty());
    }

    @Test
    void getActiveSessionExpiresAndReturnsEmptyPastDeadline() {
        AssistantSessionDocument expired = baseSession()
                .expiresAt(Instant.now().minusSeconds(10))
                .build();
        when(sessionRepository.findByIdAndUserEmail("session-1", "user@example.com"))
                .thenReturn(Optional.of(expired));

        assertTrue(service.getActiveSession("session-1", "user@example.com").isEmpty());
        verifyExpiryUpdateWasSent();
    }

    @Test
    void getActiveSessionReturnsSessionWhenActiveAndNotExpired() {
        AssistantSessionDocument active = baseSession()
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(sessionRepository.findByIdAndUserEmail("session-1", "user@example.com"))
                .thenReturn(Optional.of(active));

        Optional<AssistantSessionDocument> result = service.getActiveSession("session-1", "user@example.com");
        assertTrue(result.isPresent());
        assertEquals("session-1", result.get().getId());
    }

    @Test
    void tryConsumeRetrievalAttemptReturnsTrueWhenBudgetAvailable() {
        when(sessionRepository.tryConsumeRetrievalAttempt(eq("session-1"), any())).thenReturn(true);

        assertTrue(service.tryConsumeRetrievalAttempt("session-1"));
    }

    @Test
    void tryConsumeRetrievalAttemptReturnsFalseWhenBudgetExhausted() {
        when(sessionRepository.tryConsumeRetrievalAttempt(eq("session-1"), any())).thenReturn(false);

        assertFalse(service.tryConsumeRetrievalAttempt("session-1"));
    }

    @Test
    void tryConsumeTokenBudgetReturnsTrueWhenBudgetAvailable() {
        when(sessionRepository.tryConsumeTokenBudget(eq("session-1"), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);

        assertTrue(service.tryConsumeTokenBudget("session-1", 500));
    }

    @Test
    void tryConsumeTokenBudgetReturnsFalseWhenItWouldGoNegative() {
        when(sessionRepository.tryConsumeTokenBudget(eq("session-1"), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(false);

        assertFalse(service.tryConsumeTokenBudget("session-1", 50000));
    }

    @Test
    void currentBudgetSnapshotReturnsRemainingValuesForAnActiveSession() {
        when(sessionRepository.findByIdAndUserEmail("session-1", "user@example.com"))
                .thenReturn(Optional.of(baseSession().retrievalAttemptsRemaining(3).tokenBudgetRemaining(6500).build()));

        Optional<AssistantSessionService.BudgetSnapshot> snapshot =
                service.currentBudgetSnapshot("session-1", "user@example.com");

        assertTrue(snapshot.isPresent());
        assertEquals(3, snapshot.get().retrievalAttemptsRemaining());
        assertEquals(6500, snapshot.get().tokenBudgetRemaining());
    }

    @Test
    void currentBudgetSnapshotReturnsEmptyWhenSessionIsGone() {
        when(sessionRepository.findByIdAndUserEmail("missing", "user@example.com")).thenReturn(Optional.empty());

        assertTrue(service.currentBudgetSnapshot("missing", "user@example.com").isEmpty());
    }

    @Test
    void isRevisionStaleComparesAgainstCurrentRevision() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(43L);
        AssistantSessionDocument session = baseSession().pinnedRevision(42L).build();

        assertTrue(service.isRevisionStale(session));

        when(metadataService.getMutationVersion("proj-1")).thenReturn(42L);
        assertFalse(service.isRevisionStale(session));
    }

    @Test
    void activeSessionLimitAllowsCreationBelowTheCapAndOnlyCountsLiveSessionsOfThatUser() {
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenReturn(19L);

        assertTrue(service.activeSessionLimitRetryAfter("user@example.com").isEmpty());

        org.mockito.Mockito.verify(sessionRepository).countActive(eq("user@example.com"), any());
    }

    @Test
    void activeSessionLimitReportsSecondsUntilTheSoonestSessionExpires() {
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenReturn(20L);
        when(sessionRepository.soonestActiveDeadline(eq("user@example.com"), any()))
                .thenReturn(Optional.of(Instant.now().plusSeconds(45)));

        Optional<Integer> retryAfter = service.activeSessionLimitRetryAfter("user@example.com");

        assertTrue(retryAfter.isPresent());
        assertTrue(retryAfter.get() >= 44 && retryAfter.get() <= 46, String.valueOf(retryAfter.get()));
    }

    @Test
    void activeSessionLimitFallsBackToTheDeadlineWhenNoExpiryIsKnown() {
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenReturn(25L);
        when(sessionRepository.soonestActiveDeadline(eq("user@example.com"), any())).thenReturn(Optional.empty());

        assertEquals(Optional.of(300), service.activeSessionLimitRetryAfter("user@example.com"));
    }

    @Test
    void createWithinLimitsCreatesWhenBothLimitsAdmit() {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(3L);
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenReturn(0L);

        AssistantSessionService.SessionCreateOutcome outcome = service.createSession(
                "proj-1", "user@example.com", null, "ask", null, "claude", "m", Optional::empty);

        assertTrue(outcome.created());
        assertTrue(outcome.isOk());
        assertEquals(null, outcome.getErrorCode());
        assertEquals("claude", outcome.session().getProvider());
        assertEquals(null, outcome.retryAfterSeconds());
    }

    @Test
    void createWithinLimitsSkipsTheActiveCountWhenThePerMinuteLimitRejects() {
        AssistantSessionService.SessionCreateOutcome outcome = service.createSession(
                "proj-1", "user@example.com", null, "ask", null, "claude", "m", () -> Optional.of(12));

        assertFalse(outcome.created());
        assertFalse(outcome.isOk());
        assertEquals("RATE_LIMITED", outcome.getErrorCode());
        assertEquals(12, outcome.retryAfterSeconds());
        org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.never()).countActive(any(), any());
        org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> event =
                org.mockito.ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        org.mockito.Mockito.verify(auditService).record(event.capture());
        assertEquals("rejected", event.getValue().outcome());
        assertEquals("RATE_LIMITED", event.getValue().errorCode());
        assertEquals("retryAfterSeconds=12", event.getValue().detail());
    }

    @Test
    void createWithinLimitsRejectsAtTheActiveCap() {
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenReturn(20L);
        when(sessionRepository.soonestActiveDeadline(eq("user@example.com"), any())).thenReturn(Optional.empty());

        AssistantSessionService.SessionCreateOutcome outcome = service.createSession(
                "proj-1", "user@example.com", null, "ask", null, null, null, Optional::empty);

        assertFalse(outcome.created());
        assertEquals(300, outcome.retryAfterSeconds());
        org.mockito.Mockito.verify(sessionRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void concurrentCreatesForOneUserNeverExceedTheActiveCap() throws Exception {
        when(metadataService.getMutationVersion("proj-1")).thenReturn(1L);
        java.util.concurrent.atomic.AtomicLong live = new java.util.concurrent.atomic.AtomicLong();
        when(sessionRepository.countActive(eq("user@example.com"), any())).thenAnswer(inv -> {
            long value = live.get();
            Thread.sleep(2);
            return value;
        });
        when(sessionRepository.soonestActiveDeadline(eq("user@example.com"), any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(inv -> {
            live.incrementAndGet();
            return inv.getArgument(0);
        }).when(sessionRepository).save(any());
        int callers = 60;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.createSession("proj-1", "user@example.com", null, "ask", null,
                        null, null, Optional::empty).created();
            }));
        }
        start.countDown();
        int created = 0;
        for (java.util.concurrent.Future<Boolean> result : results) {
            if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
                created++;
            }
        }
        pool.shutdownNow();

        assertEquals(20, created);
        assertEquals(20L, live.get());
    }

    private AssistantSessionDocument.AssistantSessionDocumentBuilder baseSession() {
        return AssistantSessionDocument.builder()
                .id("session-1")
                .projectId("proj-1")
                .userEmail("user@example.com")
                .pinnedRevision(42L)
                .status(AssistantSessionStatus.ACTIVE)
                .retrievalAttemptsRemaining(5)
                .tokenBudgetRemaining(8000)
                .expiresAt(Instant.now().plusSeconds(300));
    }

    private void verifyExpiryUpdateWasSent() {
        org.mockito.Mockito.verify(sessionRepository).updateStatus(eq("session-1"), eq(AssistantSessionStatus.EXPIRED), any());
    }
}
