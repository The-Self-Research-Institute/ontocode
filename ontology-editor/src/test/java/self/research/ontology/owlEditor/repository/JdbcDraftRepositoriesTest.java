package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftPrChange;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.DraftPullRequest.Status;
import self.research.ontology.owlEditor.model.DraftSession;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

class JdbcDraftRepositoriesTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcDraftChangeRepository changes = new JdbcDraftChangeRepository(jdbc, TestDatabase.transactions());
    private final JdbcDraftSessionRepository sessions = new JdbcDraftSessionRepository(jdbc);
    private final JdbcDraftPullRequestRepository pullRequests = new JdbcDraftPullRequestRepository(jdbc);
    private final JdbcDraftPrChangeRepository prChanges = new JdbcDraftPrChangeRepository(jdbc, TestDatabase.transactions());

    @BeforeEach
    void clean() {
        jdbc.update("delete from draft_change");
        jdbc.update("delete from draft_change_parked");
        jdbc.update("delete from draft_session");
        jdbc.update("delete from draft_pull_request");
        jdbc.update("delete from draft_pr_change");
    }

    private static DraftChange change(String project, String user, String session, LocalDateTime at) {
        Map<String, Object> data = new HashMap<>();
        data.put("iri", "http://example.org/onto#Thing");
        data.put("count", 3);
        data.put("nested", Map.of("http://example.org/p.q:r", "v"));
        DraftChange change = new DraftChange(project, user, user + "-name", "createClass", data);
        change.setSessionId(session);
        change.setTimestamp(at);
        return change;
    }

    @Test
    void aDraftChangeKeepsEveryFieldIncludingNestedMapsWithDottedKeys() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123_000_000);
        DraftChange saved = changes.save(change("p1", "u1", "s1", at));

        assertNotNull(saved.getId());
        assertEquals(24, saved.getId().length());
        DraftChange read = changes.findById(saved.getId()).orElseThrow();
        assertEquals("p1", read.getProjectId());
        assertEquals("u1", read.getUserId());
        assertEquals("u1-name", read.getUsername());
        assertEquals("createClass", read.getOperationType());
        assertEquals("s1", read.getSessionId());
        assertFalse(read.isApplied());
        assertEquals(at, read.getTimestamp());
        assertEquals("http://example.org/onto#Thing", read.getOperationData().get("iri"));
        assertEquals(3, read.getOperationData().get("count"));
        assertEquals(Map.of("http://example.org/p.q:r", "v"), read.getOperationData().get("nested"));
    }

    @Test
    void savingAnExistingIdUpdatesTheRowInPlace() {
        DraftChange saved = changes.save(change("p1", "u1", "s1", LocalDateTime.now()));
        saved.setApplied(true);
        saved.setOperationData(null);
        changes.save(saved);

        DraftChange read = changes.findById(saved.getId()).orElseThrow();
        assertTrue(read.isApplied());
        assertNull(read.getOperationData());
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
    }

    @Test
    void pendingChangesAreListedOldestFirstAndAllChangesNewestFirst() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        DraftChange a = changes.save(change("p1", "u1", "s", base.plusMinutes(2)));
        DraftChange b = changes.save(change("p1", "u1", "s", base.plusMinutes(1)));
        DraftChange applied = change("p1", "u1", "s", base.plusMinutes(3));
        applied.setApplied(true);
        changes.save(applied);
        changes.save(change("p1", "u2", "s", base));
        changes.save(change("p2", "u1", "s", base));

        assertEquals(List.of(b.getId(), a.getId()), ids(changes.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")));
        assertEquals(4 - 1, changes.findByProjectIdAndAppliedFalseOrderByTimestampAsc("p1").size());
        assertEquals(applied.getId(), changes.findByProjectIdOrderByTimestampDesc("p1").get(0).getId());
        assertEquals(4, changes.findByProjectIdOrderByTimestampDesc("p1").size());
        assertEquals(3, changes.countByProjectIdAndAppliedFalse("p1"));
        assertEquals(List.of(b.getId(), a.getId()),
                ids(changes.findByProjectIdAndTimestampBetweenOrderByTimestampAsc("p1", base, base.plusMinutes(3))));
    }

    @Test
    void deleteCountsAndScopesAreExact() {
        LocalDateTime now = LocalDateTime.now();
        changes.save(change("p1", "u1", "s", now.minusDays(40)));
        changes.save(change("p1", "u1", "s", now));
        DraftChange applied = change("p1", "u1", "s", now.minusDays(40));
        applied.setApplied(true);
        changes.save(applied);
        changes.save(change("p2", "u1", "s", now));

        assertEquals(1, changes.deleteByAppliedFalseAndTimestampBefore(now.minusDays(30)));
        changes.deleteByProjectIdAndAppliedTrue("p1");
        assertEquals(2, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
        changes.deleteByProjectId("p1");
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
        DraftChange left = changes.findByProjectIdAndAppliedFalseOrderByTimestampAsc("p2").get(0);
        changes.deleteById(left.getId());
        assertEquals(0, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
    }

    @Test
    void aBulkSaveOfThousandsOfChangesWritesEveryRowOnce() {
        List<DraftChange> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(change("p1", "u1", "s", LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(i)));
        }

        List<DraftChange> saved = changes.saveAll(many);

        assertEquals(2500, saved.size());
        assertEquals(2500, changes.countByProjectIdAndAppliedFalse("p1"));
        List<DraftChange> read = changes.findByProjectIdAndAppliedFalseOrderByTimestampAsc("p1");
        assertEquals(many.get(0).getId(), read.get(0).getId());
        assertEquals(many.get(2499).getId(), read.get(2499).getId());
        saved.forEach(d -> d.setApplied(true));
        changes.saveAll(saved);
        assertEquals(0, changes.countByProjectIdAndAppliedFalse("p1"));
        assertEquals(2500, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
    }

    @Test
    void parkingMovesOnlyThePendingRowsOfOneSessionAndRestoringReturnsThemWithTheirIds() {
        DraftChange a = changes.save(change("p1", "u1", "imp", LocalDateTime.now()));
        changes.save(change("p1", "u1", "other", LocalDateTime.now()));
        DraftChange done = change("p1", "u1", "imp", LocalDateTime.now());
        done.setApplied(true);
        changes.save(done);

        assertEquals(1, changes.parkPending("p1", "u1", "imp"));
        assertEquals(0, changes.parkPending("p1", "u1", "imp"));
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_change_parked", Integer.class));
        assertTrue(changes.findById(a.getId()).isEmpty());

        assertEquals(1, changes.restoreParked("p1", "u1", "imp"));
        DraftChange back = changes.findById(a.getId()).orElseThrow();
        assertEquals("imp", back.getSessionId());
        assertEquals(Map.of("http://example.org/p.q:r", "v"), back.getOperationData().get("nested"));
        assertEquals(0, jdbc.queryForObject("select count(*) from draft_change_parked", Integer.class));
        assertEquals(1, changes.deletePending("p1", "u1", "imp"));
        assertEquals(2, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
    }

    @Test
    void manyThreadsParkingTheSameSessionMoveEachRowExactlyOnce() throws Exception {
        for (int i = 0; i < 200; i++) {
            changes.save(change("p1", "u1", "imp", LocalDateTime.now()));
        }
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return changes.parkPending("p1", "u1", "imp");
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> result : results) {
            total += result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(200, total);
        assertEquals(200, jdbc.queryForObject("select count(*) from draft_change_parked", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from draft_change", Integer.class));
    }

    @Test
    void aSessionKeepsItsPrefixesWithEmptyAndDottedKeysAndItsCopyStatus() {
        DraftSession session = new DraftSession("p1", "u1", 7L, 1234L);
        session.setBaselineAt(LocalDateTime.of(2026, 2, 3, 4, 5, 6));
        session.setBaselineSnapshotPath("snap/path");
        session.setCopyStatus(DraftCopyStatus.READY);
        Map<String, String> prefixes = new HashMap<>();
        prefixes.put("", "http://example.org/onto#");
        prefixes.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixes.put("my.pre:fix", "http://example.org/a.b/");
        session.setPrefixes(prefixes);
        sessions.save(session);

        DraftSession read = sessions.findByProjectIdAndUserId("p1", "u1").orElseThrow();
        assertEquals(session.getId(), read.getId());
        assertEquals(7L, read.getBaselineMainRevision());
        assertEquals(1234L, read.getBaselineMainTripleCount());
        assertEquals(LocalDateTime.of(2026, 2, 3, 4, 5, 6), read.getBaselineAt());
        assertEquals("snap/path", read.getBaselineSnapshotPath());
        assertEquals(DraftCopyStatus.READY, read.getCopyStatus());
        assertEquals(prefixes, read.getPrefixes());
        assertEquals(session.getId(), sessions.findById(session.getId()).orElseThrow().getId());
    }

    @Test
    void aSessionWithoutPrefixesOrStatusReadsBackAsNull() {
        sessions.save(new DraftSession("p1", "u1", 0, 0));

        DraftSession read = sessions.findByProjectIdAndUserId("p1", "u1").orElseThrow();
        assertNull(read.getPrefixes());
        assertNull(read.getCopyStatus());
    }

    @Test
    void oneSessionPerProjectAndUserIsEnforcedAndSavingAgainUpdates() {
        DraftSession first = sessions.save(new DraftSession("p1", "u1", 1, 1));

        assertThrows(DuplicateKeyException.class, () -> sessions.save(new DraftSession("p1", "u1", 2, 2)));

        first.setBaselineMainRevision(9);
        first.setCopyStatus(DraftCopyStatus.FAILED);
        sessions.save(first);
        DraftSession read = sessions.findByProjectIdAndUserId("p1", "u1").orElseThrow();
        assertEquals(9, read.getBaselineMainRevision());
        assertEquals(DraftCopyStatus.FAILED, read.getCopyStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_session", Integer.class));
    }

    @Test
    void sessionsAreFoundByProjectAndByUserIdPrefixWithoutWildcardSurprises() {
        sessions.save(new DraftSession("p1", "pr-snapshot-1", 0, 0));
        sessions.save(new DraftSession("p2", "pr-snapshot-2", 0, 0));
        sessions.save(new DraftSession("p1", "prXsnapshot-3", 0, 0));
        sessions.save(new DraftSession("p1", "alice", 0, 0));

        assertEquals(2, sessions.findByUserIdStartingWith("pr-snapshot-").size());
        assertEquals(0, sessions.findByUserIdStartingWith("pr_snapshot").size());
        assertEquals(0, sessions.findByUserIdStartingWith("%").size());
        assertEquals(3, sessions.findByProjectId("p1").size());

        sessions.deleteByProjectIdAndUserId("p1", "alice");
        assertTrue(sessions.findByProjectIdAndUserId("p1", "alice").isEmpty());
        sessions.delete(sessions.findByProjectIdAndUserId("p1", "pr-snapshot-1").orElseThrow());
        assertEquals(1, sessions.findByProjectId("p1").size());
    }

    @Test
    void manyThreadsSavingTheSameSessionIdLeaveOneRowWithOneOfTheWrittenValues() throws Exception {
        DraftSession base = sessions.save(new DraftSession("p1", "u1", 0, 0));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long revision = i + 1;
            results.add(pool.submit(() -> {
                DraftSession copy = new DraftSession("p1", "u1", revision, revision);
                copy.setId(base.getId());
                start.await();
                sessions.save(copy);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> result : results) {
            result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, jdbc.queryForObject("select count(*) from draft_session", Integer.class));
        long revision = sessions.findById(base.getId()).orElseThrow().getBaselineMainRevision();
        assertTrue(revision >= 1 && revision <= threads);
    }

    @Test
    void aPullRequestKeepsNullableFieldsListsAndStatus() {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setProjectId("p1");
        pr.setAuthorId("u1");
        pr.setAuthorUsername("Una");
        pr.setTitle("Title");
        pr.setChangeCount(2);
        pr.setSubmittedChangeIds(List.of("a", "b"));
        pr.setPublishedChangeSetIds(null);
        pullRequests.save(pr);

        DraftPullRequest read = pullRequests.findById(pr.getId()).orElseThrow();
        assertEquals(Status.OPEN, read.getStatus());
        assertEquals(List.of("a", "b"), read.getSubmittedChangeIds());
        assertNull(read.getPublishedChangeSetIds());
        assertNull(read.getDescription());
        assertNull(read.getRevertedAt());
        assertNull(read.getDraftChanged());
        assertNull(read.getNeedsCopy());
        assertNull(read.getCurrentChangeCount());
        assertEquals(2, read.getChangeCount());

        Instant now = Instant.parse("2026-05-06T07:08:09.123456Z");
        read.setStatus(Status.REVERTED);
        read.setRevertedAt(now);
        read.setRevertedBy("boss");
        read.setRevertReason("why");
        read.setRevertStartedAt(now.minusSeconds(5));
        read.setRestoredAt(now.plusSeconds(5));
        read.setReviewedAt(now);
        read.setReviewerId("boss");
        read.setReviewNote("note");
        read.setSnapshotKey("pr-snapshot-" + pr.getId());
        read.setPublishedChangeSetIds(List.of("cs1"));
        read.setSubmittedChangeIds(List.of());
        pullRequests.save(read);

        DraftPullRequest again = pullRequests.findById(pr.getId()).orElseThrow();
        assertEquals(Status.REVERTED, again.getStatus());
        assertEquals(now, again.getRevertedAt());
        assertEquals(now.minusSeconds(5), again.getRevertStartedAt());
        assertEquals(now.plusSeconds(5), again.getRestoredAt());
        assertEquals("boss", again.getReviewerId());
        assertEquals(List.of("cs1"), again.getPublishedChangeSetIds());
        assertEquals(List.of(), again.getSubmittedChangeIds());
        assertEquals(1, jdbc.queryForObject("select count(*) from draft_pull_request", Integer.class));
    }

    @Test
    void pullRequestsAreListedNewestFirstAndCountedByStatus() {
        DraftPullRequest old = pr("p1", "u1", Instant.parse("2026-01-01T00:00:00Z"), Status.APPROVED);
        DraftPullRequest mid = pr("p1", "u2", Instant.parse("2026-02-01T00:00:00Z"), Status.OPEN);
        DraftPullRequest recent = pr("p1", "u1", Instant.parse("2026-03-01T00:00:00Z"), Status.OPEN);
        pr("p2", "u1", Instant.parse("2026-04-01T00:00:00Z"), Status.OPEN);

        assertEquals(List.of(recent.getId(), mid.getId(), old.getId()),
                pullRequests.findByProjectIdOrderByCreatedAtDesc("p1").stream().map(DraftPullRequest::getId).toList());
        assertEquals(List.of(recent.getId(), mid.getId()),
                pullRequests.findByProjectIdAndStatusOrderByCreatedAtDesc("p1", Status.OPEN).stream()
                        .map(DraftPullRequest::getId).toList());
        assertEquals(2, pullRequests.countByProjectIdAndStatus("p1", Status.OPEN));
        assertEquals(0, pullRequests.countByProjectIdAndStatus("p1", Status.REVERTED));
        assertEquals(recent.getId(),
                pullRequests.findByProjectIdAndAuthorIdAndStatus("p1", "u1", Status.OPEN).orElseThrow().getId());
        assertTrue(pullRequests.findByProjectIdAndAuthorIdAndStatus("p1", "u3", Status.OPEN).isEmpty());
        assertEquals(4, pullRequests.findAll().size());

        pullRequests.deleteById(old.getId());
        assertTrue(pullRequests.findById(old.getId()).isEmpty());
    }

    private DraftPullRequest pr(String project, String author, Instant createdAt, Status status) {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setProjectId(project);
        pr.setAuthorId(author);
        pr.setCreatedAt(createdAt);
        pr.setStatus(status);
        return pullRequests.save(pr);
    }

    @Test
    void prChangesKeepTheEmbeddedChangeAndInsertionOrderPerSnapshot() {
        LocalDateTime at = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        List<DraftPrChange> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DraftChange embedded = change("p1", "u1", "s", at);
            embedded.setId("change-" + (4 - i));
            embedded.setApplied(i % 2 == 0);
            rows.add(new DraftPrChange("p1", "pr1", "snap1", embedded));
        }
        rows.add(new DraftPrChange("p1", "pr1", null, change("p1", "u1", "s", at)));
        rows.add(new DraftPrChange("p1", "pr2", "snap1", null));
        prChanges.saveAll(rows);

        List<DraftPrChange> read = prChanges.findByPrIdAndSnapshotKey("pr1", "snap1");
        assertEquals(List.of("change-4", "change-3", "change-2", "change-1", "change-0"),
                read.stream().map(r -> r.getChange().getId()).toList());
        DraftChange first = read.get(0).getChange();
        assertEquals("p1", first.getProjectId());
        assertEquals("u1", first.getUserId());
        assertEquals(at, first.getTimestamp());
        assertTrue(first.isApplied());
        assertEquals(Map.of("http://example.org/p.q:r", "v"), first.getOperationData().get("nested"));
        assertNotNull(read.get(0).getId());
        assertEquals("pr1", read.get(0).getPrId());
        assertEquals(6, prChanges.findByPrId("pr1").size());
        assertEquals(1, prChanges.findByPrId("pr1").stream().filter(r -> r.getSnapshotKey() == null).count());
        assertNull(prChanges.findByPrId("pr2").get(0).getChange());

        prChanges.deleteByPrIdAndSnapshotKey("pr1", "snap1");
        assertEquals(1, prChanges.findByPrId("pr1").size());
        prChanges.deleteByPrIdAndSnapshotKey("pr1", null);
        assertEquals(0, prChanges.findByPrId("pr1").size());
        prChanges.deleteByPrId("pr2");
        assertEquals(0, prChanges.findByPrId("pr2").size());
    }

    @Test
    void aLargePullRequestChangeListIsStoredInChunks() {
        List<DraftPrChange> rows = new ArrayList<>();
        for (int i = 0; i < 2300; i++) {
            DraftChange embedded = change("p1", "u1", "s", LocalDateTime.now());
            embedded.setId("c" + i);
            rows.add(new DraftPrChange("p1", "pr1", "snap", embedded));
        }
        prChanges.saveAll(rows);

        List<DraftPrChange> read = prChanges.findByPrIdAndSnapshotKey("pr1", "snap");
        assertEquals(2300, read.size());
        assertEquals("c0", read.get(0).getChange().getId());
        assertEquals("c2299", read.get(2299).getChange().getId());
    }

    private static List<String> ids(List<DraftChange> list) {
        return list.stream().map(DraftChange::getId).toList();
    }
}
