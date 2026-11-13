package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.ExpiringTable;
import self.research.ontology.common.db.ExpiryPurger;
import self.research.ontology.common.db.ProjectScopedTables;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.config.CacheExpiryConfiguration;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckRecord;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAssistantRepositoriesTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcAssistantSessionRepository sessions = new JdbcAssistantSessionRepository(jdbc);
    private final JdbcAssistantEditGroupRepository groups = new JdbcAssistantEditGroupRepository(jdbc);
    private final JdbcAssistantAuditRepository audits = new JdbcAssistantAuditRepository(jdbc);
    private final JdbcAssistantApplyOperationRepository operations = new JdbcAssistantApplyOperationRepository(jdbc);
    private final JdbcAssistantIdempotencyStore idempotency = new JdbcAssistantIdempotencyStore(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from assistant_session");
        jdbc.update("delete from assistant_edit_group");
        jdbc.update("delete from assistant_audit");
        jdbc.update("delete from assistant_idempotency");
        jdbc.update("delete from assistant_apply_operation");
    }

    private static int race(int callers, Supplier<Boolean> action) throws Exception {
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

    private AssistantSessionDocument session(String user, Instant deadline) {
        return sessions.save(AssistantSessionDocument.builder().projectId("p1").userEmail(user)
                .documentPath("/a.ttl").actionType("ask").provider("openai").model("m").draft(true)
                .draftUserId("d1").pinnedRevision(7L).status(AssistantSessionStatus.ACTIVE)
                .retrievalAttemptsRemaining(3).tokenBudgetRemaining(4000).expiresAt(deadline)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    private AssistantEditGroupDocument group(String user, Instant expiresAt) {
        return groups.save(AssistantEditGroupDocument.builder().sessionId("s1").projectId("p1").userEmail(user)
                .clientGroupId("c1").targetPath("turtle").status(AssistantEditGroupStatus.PENDING)
                .edits(new ArrayList<>(List.of(EditEntry.builder().startLine(3).lineCount(2).originalText("a")
                        .newText("b").lineDelta(-1).build())))
                .consistencyCheck(ConsistencyCheckRecord.builder().state(ConsistencyCheckState.PENDING).build())
                .createdAt(Instant.now()).updatedAt(Instant.now()).expiresAt(expiresAt).build());
    }

    private static AssistantIdempotencyRecordDocument inFlight(String id, String owner, Instant now) {
        return AssistantIdempotencyRecordDocument.builder().id(id).actor("a").method("POST").path("/x")
                .bodyHash("h").state(State.IN_FLIGHT).ownerToken(owner).createdAt(now).updatedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1))).build();
    }

    @Test
    void aSessionRoundTripsAndOnlyItsOwnerFindsIt() {
        Instant deadline = Instant.now().plusSeconds(300);
        AssistantSessionDocument saved = session("u@x.com", deadline);

        AssistantSessionDocument found = sessions.findByIdAndUserEmail(saved.getId(), "u@x.com").orElseThrow();
        assertEquals("p1", found.getProjectId());
        assertTrue(found.isDraft());
        assertEquals(7L, found.getPinnedRevision());
        assertEquals(3, found.getRetrievalAttemptsRemaining());
        assertEquals(deadline.toEpochMilli(), found.getExpiresAt().toEpochMilli());
        assertTrue(sessions.findByIdAndUserEmail(saved.getId(), "other@x.com").isEmpty());
        assertTrue(sessions.findById(saved.getId()).isPresent());
    }

    @Test
    void retrievalAttemptsAndTokensAreExactUnderManyThreads() throws Exception {
        AssistantSessionDocument saved = session("u@x.com", Instant.now().plusSeconds(300));
        jdbc.update("update assistant_session set retrieval_attempts_remaining = 40, token_budget_remaining = 1000 "
                + "where id = ?", saved.getId());

        int attempts = race(250, () -> sessions.tryConsumeRetrievalAttempt(saved.getId(), Instant.now()));
        int tokens = race(250, () -> sessions.tryConsumeTokenBudget(saved.getId(), 7, Instant.now()));

        assertEquals(40, attempts);
        assertEquals(142, tokens);
        AssistantSessionDocument after = sessions.findById(saved.getId()).orElseThrow();
        assertEquals(0, after.getRetrievalAttemptsRemaining());
        assertEquals(1000 - 142 * 7, after.getTokenBudgetRemaining());
    }

    @Test
    void anInactiveSessionSpendsNothing() {
        AssistantSessionDocument saved = session("u@x.com", Instant.now().plusSeconds(300));
        sessions.updateStatus(saved.getId(), AssistantSessionStatus.COMPLETED, Instant.now());

        assertFalse(sessions.tryConsumeRetrievalAttempt(saved.getId(), Instant.now()));
        assertFalse(sessions.tryConsumeTokenBudget(saved.getId(), 1, Instant.now()));
        assertEquals(AssistantSessionStatus.COMPLETED, sessions.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void activeSessionsAreCountedPerUserAndExpiredOnesAreIgnored() {
        session("u@x.com", Instant.now().plusSeconds(100));
        session("u@x.com", Instant.now().plusSeconds(50));
        session("u@x.com", Instant.now().minusSeconds(5));
        session("other@x.com", Instant.now().plusSeconds(10));
        AssistantSessionDocument done = session("u@x.com", Instant.now().plusSeconds(20));
        sessions.updateStatus(done.getId(), AssistantSessionStatus.COMPLETED, Instant.now());

        assertEquals(2, sessions.countActive("u@x.com", Instant.now()));
        long soonest = sessions.soonestActiveDeadline("u@x.com", Instant.now()).orElseThrow().getEpochSecond();
        assertTrue(Math.abs(soonest - (Instant.now().getEpochSecond() + 50)) <= 2);
        assertTrue(sessions.soonestActiveDeadline("nobody@x.com", Instant.now()).isEmpty());
    }

    @Test
    void sessionsAreHiddenAfterTheGraceAndPurgedByTheExpiryPurger() {
        AssistantSessionDocument old = session("u@x.com", Instant.now().minus(Duration.ofHours(25)));
        AssistantSessionDocument recent = session("u@x.com", Instant.now().minusSeconds(60));

        assertTrue(sessions.findById(old.getId()).isEmpty());
        assertTrue(sessions.findById(recent.getId()).isPresent());
        int purged = new ExpiryPurger(jdbc, null, 100).purge(ExpiringTable.of("assistant_session"));
        assertEquals(1, purged);
        assertEquals(1, jdbc.queryForObject("select count(*) from assistant_session", Integer.class));
    }

    @Test
    void anEditGroupRoundTripsWithItsEditsAndCheckRecord() {
        AssistantEditGroupDocument saved = group("u@x.com", Instant.now().plusSeconds(600));

        AssistantEditGroupDocument found = groups.findByIdAndUserEmail(saved.getId(), "u@x.com").orElseThrow();
        assertEquals(1, found.getEdits().size());
        assertEquals(3L, found.getEdits().get(0).getStartLine());
        assertEquals(-1, found.getEdits().get(0).getLineDelta());
        assertEquals(ConsistencyCheckState.PENDING, found.getConsistencyCheck().getState());
        assertTrue(groups.findByIdAndUserEmail(saved.getId(), "other@x.com").isEmpty());
        assertEquals(1, groups.findByProjectIdAndTargetPathAndStatus("p1", "turtle", AssistantEditGroupStatus.PENDING)
                .size());
        assertTrue(groups.findByProjectIdAndTargetPathAndStatus("p1", "turtle", AssistantEditGroupStatus.APPLIED)
                .isEmpty());
        assertEquals(1, groups.findAllById(List.of(saved.getId(), "missing")).size());
    }

    @Test
    void aGroupWithoutACheckRecordComesBackWithoutOne() {
        AssistantEditGroupDocument saved = groups.save(AssistantEditGroupDocument.builder().projectId("p1")
                .userEmail("u@x.com").status(AssistantEditGroupStatus.PENDING).build());

        assertNull(groups.findById(saved.getId()).orElseThrow().getConsistencyCheck());
        assertTrue(groups.resolveConsistencyCheck(saved.getId(), "j", ConsistencyCheckState.PASSED, "ok",
                Instant.now()));
    }

    @Test
    void expiredEditGroupsAreNotReadAndArePurged() {
        AssistantEditGroupDocument gone = group("u@x.com", Instant.now().minusSeconds(5));
        group("u@x.com", Instant.now().plusSeconds(600));

        assertTrue(groups.findById(gone.getId()).isEmpty());
        assertEquals(1, groups.findByProjectIdAndTargetPathAndStatus("p1", "turtle", AssistantEditGroupStatus.PENDING)
                .size());
        assertEquals(1, new ExpiryPurger(jdbc, null, 100).purge(ExpiringTable.of("assistant_edit_group")));
    }

    @Test
    void theConsistencyCheckResolvesExactlyOnceUnderManyThreads() throws Exception {
        AssistantEditGroupDocument saved = group("u@x.com", Instant.now().plusSeconds(600));
        ConsistencyCheckState[] states = {ConsistencyCheckState.PASSED, ConsistencyCheckState.FAILED,
                ConsistencyCheckState.ERROR, ConsistencyCheckState.TIMED_OUT};
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

        int wins = race(64, () -> {
            ConsistencyCheckState state = states[next.getAndIncrement() % states.length];
            return groups.resolveConsistencyCheck(saved.getId(), "whatif-" + state, state, state.name(),
                    Instant.now());
        });

        assertEquals(1, wins);
        ConsistencyCheckRecord record = groups.findById(saved.getId()).orElseThrow().getConsistencyCheck();
        assertNotNull(record.getResolvedAt());
        assertEquals("whatif-" + record.getState(), record.getJobId());
        assertEquals(record.getState().name(), record.getDetail());
        assertFalse(groups.resolveConsistencyCheck(saved.getId(), "late", ConsistencyCheckState.PASSED, "late",
                Instant.now()));
    }

    @Test
    void auditEntriesGetARetentionDateNinetyDaysAfterCreation() {
        Instant created = Instant.now();
        AssistantAuditEntryDocument saved = audits.save(AssistantAuditEntryDocument.builder().actor("u@x.com")
                .projectId("p1").operation("session_create").sourceRevision(5L).outcome("ok").createdAt(created)
                .build());
        audits.save(AssistantAuditEntryDocument.builder().projectId("p1").operation("old").outcome("ok")
                .createdAt(created.minus(Duration.ofDays(91))).build());

        Instant expires = jdbc.queryForObject("select expires_at from assistant_audit where id = ?",
                java.sql.Timestamp.class, saved.getId()).toInstant();
        assertTrue(Math.abs(Duration.between(created.plus(Duration.ofDays(90)), expires).toSeconds()) <= 1);
        assertEquals(1, new ExpiryPurger(jdbc, null, 100).purge(ExpiringTable.of("assistant_audit")));
        assertEquals(1, jdbc.queryForObject("select count(*) from assistant_audit", Integer.class));
    }

    @Test
    void applyOperationsAreFoundByGroupStatusAndProjectAndCleanedUpByAge() {
        Instant now = Instant.now();
        operations.save(operation("o1", "g1", ApplyOperationStatus.COMMITTED, now.minus(Duration.ofDays(40)),
                now.minus(Duration.ofDays(40))));
        operations.save(operation("o2", "g1", ApplyOperationStatus.PREPARED, now.minusSeconds(10), now));
        operations.save(operation("o3", "g2", ApplyOperationStatus.FAILED, now.minusSeconds(20), now));

        assertEquals("o2", operations.findFirstByGroupIdOrderByCreatedAtDesc("g1").orElseThrow().getId());
        assertEquals(1, operations.findByStatusIn(EnumSet.of(ApplyOperationStatus.PREPARED)).size());
        assertEquals(2, operations.findByProjectIdAndStatusIn("p1", EnumSet.of(ApplyOperationStatus.PREPARED,
                ApplyOperationStatus.FAILED)).size());
        assertEquals(1, operations.deleteByStatusInAndUpdatedAtBefore(EnumSet.of(ApplyOperationStatus.COMMITTED,
                ApplyOperationStatus.ROLLED_BACK), now.minus(Duration.ofDays(30))));
        assertTrue(operations.findById("o1").isEmpty());
        operations.save(operation("o2", "g1", ApplyOperationStatus.COMMITTED, now, now));
        assertEquals(ApplyOperationStatus.COMMITTED, operations.findById("o2").orElseThrow().getStatus());
    }

    private static AssistantApplyOperationDocument operation(String id, String groupId,
                                                             ApplyOperationStatus status, Instant created,
                                                             Instant updated) {
        return AssistantApplyOperationDocument.builder().id(id).projectId("p1").groupId(groupId).sessionId("s1")
                .actor("u").targetPath("turtle").status(status).createdAt(created).updatedAt(updated).build();
    }

    @Test
    void insertingTheSameIdempotencyKeyConcurrentlyHasExactlyOneWinner() throws Exception {
        Instant now = Instant.now();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        int wins = race(64, () -> idempotency.tryInsert(inFlight("k1", "owner-" + n.incrementAndGet(), now)));

        assertEquals(1, wins);
        assertEquals(1, jdbc.queryForObject("select count(*) from assistant_idempotency", Integer.class));
        assertEquals(State.IN_FLIGHT, idempotency.find("k1").orElseThrow().getState());
        assertTrue(idempotency.find("none").isEmpty());
    }

    @Test
    void takeoverOfAStaleInFlightRecordHasExactlyOneWinnerAndLiveOnesAreSafe() throws Exception {
        Instant now = Instant.now();
        idempotency.tryInsert(inFlight("k1", "old-owner", now.minusSeconds(600)));
        idempotency.tryInsert(inFlight("k2", "live-owner", now));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        int wins = race(64, () -> {
            String owner = "new-" + n.incrementAndGet();
            return idempotency.tryTakeOver("k1", now.minusSeconds(60), now, inFlight("k1", owner, now));
        });

        assertEquals(1, wins);
        assertTrue(idempotency.find("k1").orElseThrow().getOwnerToken().startsWith("new-"));
        assertFalse(idempotency.tryTakeOver("k2", now.minusSeconds(60), now, inFlight("k2", "thief", now)));
        assertEquals("live-owner", idempotency.find("k2").orElseThrow().getOwnerToken());
    }

    @Test
    void anExpiredRecordCanBeTakenOverEvenWhenItIsCompleted() {
        Instant now = Instant.now();
        AssistantIdempotencyRecordDocument expired = inFlight("k1", "o1", now.minusSeconds(100));
        expired.setExpiresAt(now.minusSeconds(1));
        idempotency.tryInsert(expired);
        idempotency.complete("k1", "o1", 200, "application/json", "{}", now.minusSeconds(50));

        assertTrue(idempotency.tryTakeOver("k1", now.minusSeconds(60), now, inFlight("k1", "o2", now)));

        AssistantIdempotencyRecordDocument taken = idempotency.find("k1").orElseThrow();
        assertEquals(State.IN_FLIGHT, taken.getState());
        assertEquals("o2", taken.getOwnerToken());
        assertNull(taken.getResponseBody());
    }

    @Test
    void onlyTheOwnerCanCompleteAndOnlyOnce() throws Exception {
        Instant now = Instant.now();
        idempotency.tryInsert(inFlight("k1", "mine", now));

        assertFalse(idempotency.complete("k1", "someone-else", 200, "text/plain", "x", now));
        int wins = race(64, () -> idempotency.complete("k1", "mine", 201, "application/json", "{\"a\":1}", now));

        assertEquals(1, wins);
        AssistantIdempotencyRecordDocument done = idempotency.find("k1").orElseThrow();
        assertEquals(State.COMPLETED, done.getState());
        assertEquals(201, done.getResponseStatus());
        assertEquals("application/json", done.getResponseContentType());
        assertEquals("{\"a\":1}", done.getResponseBody());
    }

    @Test
    void aTakenOverRecordCannotBeCompletedByTheOldOwner() {
        Instant now = Instant.now();
        idempotency.tryInsert(inFlight("k1", "old", now.minusSeconds(600)));
        assertTrue(idempotency.tryTakeOver("k1", now.minusSeconds(60), now, inFlight("k1", "new", now)));

        assertFalse(idempotency.complete("k1", "old", 200, "text/plain", "late", now));
        assertEquals(State.IN_FLIGHT, idempotency.find("k1").orElseThrow().getState());
    }

    @Test
    void abandonRemovesOnlyAnInFlightRecordOfTheOwner() {
        Instant now = Instant.now();
        idempotency.tryInsert(inFlight("k1", "mine", now));
        idempotency.tryInsert(inFlight("k2", "mine", now));
        idempotency.complete("k2", "mine", 200, "text/plain", "x", now);

        idempotency.abandon("k1", "other");
        assertTrue(idempotency.find("k1").isPresent());
        idempotency.abandon("k1", "mine");
        idempotency.abandon("k2", "mine");

        assertTrue(idempotency.find("k1").isEmpty());
        assertTrue(idempotency.find("k2").isPresent());
    }

    @Test
    void expiredIdempotencyRecordsArePurged() {
        Instant now = Instant.now();
        AssistantIdempotencyRecordDocument expired = inFlight("k1", "o", now);
        expired.setExpiresAt(now.minusSeconds(5));
        idempotency.tryInsert(expired);
        idempotency.tryInsert(inFlight("k2", "o", now));

        assertEquals(1, new ExpiryPurger(jdbc, null, 100).purge(ExpiringTable.of("assistant_idempotency")));
        assertTrue(idempotency.find("k2").isPresent());
    }

    @Test
    void theAssistantTablesArePurgedWithTheProjectAndTheExpiryBeansExist() {
        List<String> project = ProjectScopedTables.names();
        for (String table : List.of("assistant_session", "assistant_edit_group", "assistant_audit",
                "assistant_apply_operation")) {
            assertTrue(project.contains(table), table);
        }
        CacheExpiryConfiguration config = new CacheExpiryConfiguration();
        assertEquals("assistant_session", config.assistantSessionExpiry().table());
        assertEquals("assistant_edit_group", config.assistantEditGroupExpiry().table());
        assertEquals("assistant_audit", config.assistantAuditExpiry().table());
        assertEquals("assistant_idempotency", config.assistantIdempotencyExpiry().table());
    }
}
