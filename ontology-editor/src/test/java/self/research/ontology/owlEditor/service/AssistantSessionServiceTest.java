package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
    private MongoTemplate mongoTemplate;

    private AssistantSessionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AssistantSessionService(sessionRepository, metadataService, mongoTemplate);
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
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantSessionDocument.class)))
                .thenReturn(baseSession().retrievalAttemptsRemaining(4).build());

        assertTrue(service.tryConsumeRetrievalAttempt("session-1"));
    }

    @Test
    void tryConsumeRetrievalAttemptReturnsFalseWhenBudgetExhausted() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantSessionDocument.class)))
                .thenReturn(null);

        assertFalse(service.tryConsumeRetrievalAttempt("session-1"));
    }

    @Test
    void tryConsumeTokenBudgetReturnsTrueWhenBudgetAvailable() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantSessionDocument.class)))
                .thenReturn(baseSession().tokenBudgetRemaining(7500).build());

        assertTrue(service.tryConsumeTokenBudget("session-1", 500));
    }

    @Test
    void tryConsumeTokenBudgetReturnsFalseWhenItWouldGoNegative() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(AssistantSessionDocument.class)))
                .thenReturn(null);

        assertFalse(service.tryConsumeTokenBudget("session-1", 50000));
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
        when(mongoTemplate.count(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(19L);

        assertTrue(service.activeSessionLimitRetryAfter("user@example.com").isEmpty());

        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AssistantSessionDocument.class));
        org.bson.Document filter = captor.getValue().getQueryObject();
        assertEquals("user@example.com", filter.get("userEmail"));
        assertEquals(AssistantSessionStatus.ACTIVE, filter.get("status"));
        assertTrue(((org.bson.Document) filter.get("expiresAt")).containsKey("$gt"));
    }

    @Test
    void activeSessionLimitReportsSecondsUntilTheSoonestSessionExpires() {
        when(mongoTemplate.count(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(20L);
        when(mongoTemplate.findOne(any(Query.class), eq(AssistantSessionDocument.class)))
                .thenReturn(baseSession().expiresAt(Instant.now().plusSeconds(45)).build());

        Optional<Integer> retryAfter = service.activeSessionLimitRetryAfter("user@example.com");

        assertTrue(retryAfter.isPresent());
        assertTrue(retryAfter.get() >= 44 && retryAfter.get() <= 46, String.valueOf(retryAfter.get()));
    }

    @Test
    void activeSessionLimitFallsBackToTheDeadlineWhenNoExpiryIsKnown() {
        when(mongoTemplate.count(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(25L);
        when(mongoTemplate.findOne(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(null);

        assertEquals(Optional.of(300), service.activeSessionLimitRetryAfter("user@example.com"));
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
        org.mockito.Mockito.verify(mongoTemplate).updateFirst(
                any(Query.class), any(Update.class), eq(AssistantSessionDocument.class));
    }
}
