package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.RollbackAudit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcHistoryRepositoriesTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcHistoryChangeRepository history = new JdbcHistoryChangeRepository(jdbc, TestDatabase.transactions());
    private final JdbcOntologyChangeRepository changes = new JdbcOntologyChangeRepository(jdbc);
    private final JdbcRollbackAuditRepository audits = new JdbcRollbackAuditRepository(jdbc);
    private final JdbcUndoIntentRepository intents = new JdbcUndoIntentRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from history_change");
        jdbc.update("delete from ontology_change");
        jdbc.update("delete from rollback_audit");
        jdbc.update("delete from undo_intent");
        jdbc.update("delete from draft_session");
    }

    private HistoryChange row(String id, String project, String user, LocalDateTime at) {
        HistoryChange change = new HistoryChange(project, "edit-" + id, user, user + "-name");
        change.setId(id);
        change.setTimestamp(at);
        change.setOperationType("createClass");
        return history.save(change);
    }

    @Test
    void aHistoryChangeKeepsEveryFieldIncludingNestedParts() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123_000_000);
        HistoryChange change = new HistoryChange("p1", "edit-1", "u1", "User");
        change.setTimestamp(at);
        change.setEntityIRI("http://example.org/a.b:c#X");
        change.setStatus("APPROVED");
        change.setApprovedAt(at.plusHours(1));
        change.setDraft(true);
        change.setChangeSetId("cs1");
        change.setExactInserted("<a> <b> <c> .");
        change.setExactCovers(2);
        change.setDocumentStore("fuzzyRule");
        change.setDocumentId("d1");
        change.setDocumentBefore("{\"id\":\"d1\"}");
        change.getMetadata().put("http://key.with.dots:and/slashes", "v");
        change.getComments().put("k1", new HistoryChange.CommentEntry("u2", "Other", "hello"));
        change.setAi(new HistoryChange.AiInfo("claude", "m", "s", "g", "sum"));
        HistoryChange.SubChange sub = new HistoryChange.SubChange("p", "o", "n", null, true);
        sub.setId("sub-1");
        sub.setLanguage("en");
        change.setSubChanges(new ArrayList<>(List.of(sub)));
        history.save(change);

        assertEquals(24, change.getId().length());
        HistoryChange read = history.findById(change.getId()).orElseThrow();
        assertEquals(at, read.getTimestamp());
        assertEquals(at.plusHours(1), read.getApprovedAt());
        assertEquals("http://example.org/a.b:c#X", read.getEntityIRI());
        assertEquals("APPROVED", read.getStatus());
        assertTrue(read.isDraft());
        assertEquals(2, read.getExactCovers());
        assertEquals("<a> <b> <c> .", read.getExactInserted());
        assertEquals("{\"id\":\"d1\"}", read.getDocumentBefore());
        assertEquals("v", read.getMetadata().get("http://key.with.dots:and/slashes"));
        assertEquals("hello", read.getComments().get("k1").getText());
        assertEquals("claude", read.getAi().getProvider());
        assertEquals("sub-1", read.getSubChanges().get(0).getId());
        assertEquals("en", read.getSubChanges().get(0).getLanguage());
        assertEquals("json", read.getFormat());
        assertNull(read.getUndoBlockedReason());
    }

    @Test
    void aDraftRowIsStampedWithTheOwnersSessionOnSave() {
        jdbc.update("insert into draft_session (id, project_id, user_id) values ('sess-1', 'p1', 'u1')");
        HistoryChange change = new HistoryChange("p1", "edit-1", "u1", "User");
        change.setDraft(true);
        history.save(change);

        assertEquals("sess-1", history.findById(change.getId()).orElseThrow().getDraftSessionId());
    }

    @Test
    void rowsWithAMongoFormatDocumentCannotBeUndone() {
        HistoryChange change = new HistoryChange("p1", "edit-1", "u1", "User");
        change.setId("old");
        change.setDocumentStore("fuzzyRule");
        change.setDocumentId("d1");
        change.setDocumentBefore("{\"_id\":{\"$oid\":\"x\"}}");
        change.setFormat(HistoryChange.FORMAT_MONGO);
        history.save(change);

        HistoryChange read = history.findById("old").orElseThrow();
        assertEquals(HistoryChange.LEGACY_DOCUMENT_REASON, read.getUndoBlockedReason());
        assertEquals("mongo", read.getFormat());
        assertNull(jdbc.queryForObject("select undo_blocked_reason from history_change where id = 'old'", String.class));
    }

    @Test
    void listsAreNewestFirstAndPagesAreHonoured() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        row("a", "p1", "u1", now);
        row("b", "p1", "u1", now.plusMinutes(1));
        row("c", "p1", "u1", now.plusMinutes(2));
        row("d", "p2", "u1", now.plusMinutes(3));

        assertEquals(List.of("c", "b", "a"),
                history.findByProjectIdOrderByTimestampDesc("p1").stream().map(HistoryChange::getId).toList());
        assertEquals(List.of("c", "b"), history.findByProjectId("p1", PageRequest.of(0, 2,
                Sort.by(Sort.Direction.DESC, "timestamp"))).stream().map(HistoryChange::getId).toList());
        assertEquals(List.of("a"), history.findByProjectId("p1", PageRequest.of(1, 2,
                Sort.by(Sort.Direction.DESC, "timestamp"))).stream().map(HistoryChange::getId).toList());
        assertEquals(List.of("a", "b"), history.findByProjectId("p1", PageRequest.of(0, 2,
                Sort.by(Sort.Direction.ASC, "timestamp"))).stream().map(HistoryChange::getId).toList());
        assertEquals(List.of("c", "b"), history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc("p1", false,
                List.of("u1", "u9"), PageRequest.of(0, 2)).stream().map(HistoryChange::getId).toList());
        assertTrue(history.findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc("p1", false, List.of(),
                PageRequest.of(0, 5)).isEmpty());
    }

    @Test
    void markDraftEntriesSharedClearsOnlyTheDraftRowsOfThatEntity() {
        HistoryChange a = row("a", "p1", "u1", LocalDateTime.now());
        a.setDraft(true);
        a.setEntityIRI("iri:A");
        history.save(a);
        HistoryChange b = row("b", "p1", "u1", LocalDateTime.now());
        b.setDraft(true);
        b.setEntityIRI("iri:B");
        history.save(b);

        assertEquals(1, history.markDraftEntriesShared("p1", "iri:A"));

        assertFalse(history.findById("a").orElseThrow().isDraft());
        assertTrue(history.findById("b").orElseThrow().isDraft());
    }

    @Test
    void importIdLookupReadsTheMetadataValue() {
        HistoryChange a = row("a", "p1", "u1", LocalDateTime.now());
        a.getMetadata().put("importId", "imp-1");
        history.save(a);
        row("b", "p1", "u1", LocalDateTime.now());

        assertEquals(List.of("a"), history.findByProjectIdAndImportId("p1", "imp-1").stream().map(HistoryChange::getId).toList());
    }

    @Test
    void orphanGroupsAreCountedWithGroupBy() {
        for (int i = 0; i < 3; i++) {
            HistoryChange c = row("d" + i, "p1", "dead", LocalDateTime.now());
            c.setDraft(true);
            history.save(c);
        }
        HistoryChange discarded = row("x", "p1", "dead", LocalDateTime.now());
        discarded.setDraft(true);
        discarded.setDraftDiscarded(true);
        history.save(discarded);
        HistoryChange other = row("o", "p2", "live", LocalDateTime.now());
        other.setDraft(true);
        history.save(other);
        row("pub", "p1", "dead", LocalDateTime.now());

        List<HistoryChangeRepository.DraftGroup> groups = history.countLiveDraftGroups();

        assertEquals(2, groups.size());
        assertEquals(3, groups.stream().filter(g -> g.userId().equals("dead")).findFirst().orElseThrow().rows());
    }

    @Test
    void theSameNameUnderSeveralIdsIsReported() {
        HistoryChange a = row("a", "p1", "id-1", LocalDateTime.now());
        a.setUsername("sam");
        history.save(a);
        HistoryChange b = row("b", "p1", "id-2", LocalDateTime.now());
        b.setUsername("sam");
        history.save(b);
        HistoryChange c = row("c", "p1", "id-3", LocalDateTime.now());
        c.setUsername("solo");
        history.save(c);

        List<HistoryChangeRepository.SameNameGroup> found = history.findNamesUnderSeveralUserIds();

        assertEquals(1, found.size());
        assertEquals("sam", found.get(0).username());
        assertEquals(2, found.get(0).userIds().size());
    }

    @Test
    void operationGroupsTagOnlyUntaggedPublishedRowsSinceThen() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 10, 0);
        row("old", "p1", "u1", now.minusHours(1));
        row("new", "p1", "u1", now.plusMinutes(1));
        HistoryChange tagged = row("tagged", "p1", "u1", now.plusMinutes(2));
        tagged.setOperationGroupId("g0");
        history.save(tagged);

        assertEquals(1, history.tagOperationGroup("p1", "u1", now, "g1"));

        assertEquals("g1", history.findById("new").orElseThrow().getOperationGroupId());
        assertNull(history.findById("old").orElseThrow().getOperationGroupId());
        assertEquals("g0", history.findById("tagged").orElseThrow().getOperationGroupId());
        assertEquals(1, history.findOpenByOperationGroupId("g1").size());
    }

    @Test
    void blockingARowClearsItsExactDataAndTheRowsItCovers() {
        HistoryChange carrier = row("carrier", "p1", "u1", LocalDateTime.now());
        carrier.setExactInserted("x");
        carrier.setExactCovers(1);
        history.save(carrier);
        HistoryChange covered = row("covered", "p1", "u1", LocalDateTime.now());
        covered.setCoveredBy("edit-carrier");
        history.save(covered);

        history.blockExact("carrier", "edit-carrier", "p1", "changed");

        HistoryChange read = history.findById("carrier").orElseThrow();
        assertEquals("changed", read.getUndoBlockedReason());
        assertNull(read.getExactInserted());
        assertEquals(0, read.getExactCovers());
        assertEquals("changed", history.findById("covered").orElseThrow().getUndoBlockedReason());
        assertNull(history.findById("covered").orElseThrow().getCoveredBy());
    }

    @Test
    void exactCoversIsSetOnTheCarrierRow() {
        row("a", "p1", "u1", LocalDateTime.now());

        history.setExactCovers("p1", "edit-a", 4);

        assertEquals(4, history.findById("a").orElseThrow().getExactCovers());
    }

    @Test
    void manyThreadsMarkingTheSameDraftsPublishedChangeEachRowOnce() throws Exception {
        for (int i = 0; i < 50; i++) {
            HistoryChange c = row("r" + i, "p1", "u1", LocalDateTime.now());
            c.setDraft(true);
            history.save(c);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return history.markDraftsPublished("p1", "u1");
            }));
        }
        start.countDown();
        long total = 0;
        for (Future<Long> result : results) {
            total += result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(50, total);
    }

    @Test
    void anOntologyChangeRoundTripsAndFiltersByTimeTypeAndRevertFlag() {
        LocalDateTime now = LocalDateTime.of(2026, 2, 2, 2, 2);
        OntologyChange a = new OntologyChange("p1", "u1", "User", OntologyChange.ChangeType.ADD_CLASS);
        a.setTimestamp(now);
        a.setEntityIRI("iri:A");
        a.setChangeCategory("class");
        a.setMetadata(new java.util.HashMap<>(Map.of("a.b:c", "v")));
        changes.save(a);
        OntologyChange b = new OntologyChange("p1", "u2", "Other", OntologyChange.ChangeType.REMOVE_CLASS);
        b.setTimestamp(now.plusMinutes(5));
        b.setReverted(true);
        b.setSessionId("s1");
        changes.save(b);

        OntologyChange read = changes.findById(a.getId()).orElseThrow();
        assertEquals(now, read.getTimestamp());
        assertEquals(OntologyChange.ChangeType.ADD_CLASS, read.getChangeType());
        assertEquals("v", read.getMetadata().get("a.b:c"));
        assertEquals(List.of(b.getId(), a.getId()),
                changes.findByProjectIdOrderByTimestampDesc("p1").stream().map(OntologyChange::getId).toList());
        assertEquals(1, changes.findByProjectIdAndTimestampBetweenOrderByTimestampDesc("p1", now.minusMinutes(1),
                now.plusMinutes(1)).size());
        assertEquals(0, changes.findByProjectIdAndTimestampBetweenOrderByTimestampDesc("p1", now, now.plusMinutes(5)).size());
        assertEquals(List.of(a.getId()), changes.findRecentChanges("p1", PageRequest.of(0, 5)).stream()
                .map(OntologyChange::getId).toList());
        assertEquals(1, changes.findByProjectIdAndRevertedTrueOrderByTimestampDesc("p1").size());
        assertEquals(1, changes.findBySessionIdOrderByTimestampDesc("s1").size());
        assertEquals(1, changes.findByProjectIdAndChangeTypeOrderByTimestampDesc("p1",
                OntologyChange.ChangeType.ADD_CLASS).size());
        assertEquals(2, changes.countByProjectId("p1"));
        assertEquals(1, changes.countByProjectIdAndUserId("p1", "u2"));
        changes.deleteByProjectId("p1");
        assertEquals(0, changes.countByProjectId("p1"));
    }

    @Test
    void aRollbackAuditStoresTheOldDraftRowsAsPlainJson() {
        DraftChange draft = new DraftChange("p1", "u1", "User", "createClass", Map.of("iri", "http://x.y/a:b"));
        draft.setId("dc1");
        RollbackAudit audit = new RollbackAudit();
        audit.setProjectId("p1");
        audit.setChangeSetId("cs1");
        audit.setChangeIds(new ArrayList<>(List.of("c1", "c2")));
        audit.setCascadedSubChangeIds(new ArrayList<>(List.of("s1")));
        audit.setDraftRecords(new ArrayList<>(List.of(draft)));
        audits.save(audit);

        RollbackAudit read = audits.findById(audit.getId()).orElseThrow();
        assertEquals(List.of("c1", "c2"), read.getChangeIds());
        assertEquals(List.of("s1"), read.getCascadedSubChangeIds());
        assertEquals("dc1", read.getDraftRecords().get(0).getId());
        assertEquals("http://x.y/a:b", read.getDraftRecords().get(0).getOperationData().get("iri"));
        assertEquals("UNDO", read.getDirection());
        assertEquals("json", read.getFormat());
        assertEquals(audit.getId(), audits.findFirstByChangeSetIdAndDirectionOrderByRevertedAtDesc("cs1", "UNDO")
                .orElseThrow().getId());
    }

    @Test
    void aMongoFormatAuditIsReadWithoutItsDraftRecordsAndNeverFailsToLoad() {
        RollbackAudit audit = new RollbackAudit();
        audit.setProjectId("p1");
        audits.save(audit);
        jdbc.update("update rollback_audit set format = 'mongo', draft_records = '[{\"_id\":{\"$oid\":\"x\"},\"timestamp\":{\"$date\":1}}]'::jsonb where id = ?",
                audit.getId());

        RollbackAudit read = audits.findById(audit.getId()).orElseThrow();

        assertEquals("mongo", read.getFormat());
        assertTrue(read.getDraftRecords().isEmpty());
    }

    @Test
    void auditsAreFoundByUserListAndDeletedById() {
        RollbackAudit mine = new RollbackAudit();
        mine.setProjectId("p1");
        mine.setRevertedByUserId("u1");
        mine.setHistoryChangeId("h1");
        mine.setSubChangeId("s1");
        audits.save(mine);
        RollbackAudit theirs = new RollbackAudit();
        theirs.setProjectId("p1");
        theirs.setRevertedByUserId("u2");
        audits.save(theirs);

        assertEquals(List.of(mine.getId()), audits.findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(
                "p1", "UNDO", List.of("u1"), PageRequest.of(0, 5)).stream().map(RollbackAudit::getId).toList());
        assertTrue(audits.findByHistoryChangeIdAndSubChangeId("h1", "s1").isPresent());
        audits.deleteById(mine.getId());
        assertTrue(audits.findById(mine.getId()).isEmpty());
        audits.deleteByProjectId("p1");
        assertTrue(audits.findById(theirs.getId()).isEmpty());
    }

    private UndoIntentDocument intent(String project, String op) {
        Instant now = Instant.now();
        return UndoIntentDocument.builder().projectId(project).opId(op).direction("UNDO").actor("u1")
                .status(UndoIntentDocument.Status.PENDING).stage(UndoIntentDocument.Stage.STARTED)
                .createdAt(now).updatedAt(now).changeIds(List.of("c1")).build();
    }

    @Test
    void anUndoIntentRoundTripsAndIsUpdatedInPlace() {
        UndoIntentDocument saved = intents.save(intent("p1", "op1"));
        assertNotNull(saved.getId());
        saved.setStatus(UndoIntentDocument.Status.COMMITTED);
        saved.setStage(UndoIntentDocument.Stage.HISTORY_MARKED);
        saved.setAppliedCount(3);
        intents.save(saved);

        UndoIntentDocument read = intents.findByProjectIdAndOpId("p1", "op1").orElseThrow();
        assertEquals(saved.getId(), read.getId());
        assertEquals(UndoIntentDocument.Status.COMMITTED, read.getStatus());
        assertEquals(3, read.getAppliedCount());
        assertEquals(List.of("c1"), read.getChangeIds());
        assertEquals(1, jdbc.queryForObject("select count(*) from undo_intent", Integer.class));
    }

    @Test
    void theSameOperationIdInAProjectIsRejectedButBlankOnesAreNot() {
        intents.save(intent("p1", "op1"));

        assertThrows(DuplicateKeyException.class, () -> intents.save(intent("p1", "op1")));
        intents.save(intent("p2", "op1"));
        intents.save(intent("p1", null));
        intents.save(intent("p1", null));
        assertEquals(4, jdbc.queryForObject("select count(*) from undo_intent", Integer.class));
    }

    @Test
    void manyThreadsStartingTheSameOperationHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    intents.save(intent("p1", "race"));
                    return true;
                } catch (DuplicateKeyException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertEquals(1, winners);
    }

    @Test
    void undoIntentsAreFoundByStatusAndAge() {
        UndoIntentDocument pending = intents.save(intent("p1", "a"));
        UndoIntentDocument done = intent("p1", "b");
        done.setStatus(UndoIntentDocument.Status.COMMITTED);
        intents.save(done);
        UndoIntentDocument old = intent("p2", "c");
        old.setCreatedAt(Instant.now().minusSeconds(3600));
        intents.save(old);

        assertEquals(2, intents.findByStatusIn(List.of(UndoIntentDocument.Status.PENDING)).size());
        assertEquals(1, intents.findByProjectIdAndStatusIn("p1", List.of(UndoIntentDocument.Status.PENDING)).size());
        assertEquals(1, intents.findByStatusAndCreatedAtBefore(UndoIntentDocument.Status.PENDING,
                Instant.now().minusSeconds(60)).size());
        assertEquals(pending.getId(), intents.findById(pending.getId()).orElseThrow().getId());
        intents.deleteByProjectId("p1");
        assertEquals(1, jdbc.queryForObject("select count(*) from undo_intent", Integer.class));
    }
}
