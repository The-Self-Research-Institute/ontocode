package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.JdbcHistoryChangeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HistorySyncDraftPublishedTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final HistoryChangeRepository repository = new JdbcHistoryChangeRepository(jdbc, TestDatabase.transactions());
    private final HistorySyncService sync = new HistorySyncService(repository, mock(OntologyHistoryService.class));

    @BeforeEach
    void clean() {
        jdbc.update("delete from history_change");
        jdbc.update("delete from draft_session");
    }

    private HistoryChange row(String id, String project, String user, boolean draft) {
        HistoryChange change = new HistoryChange(project, "edit-" + id, user, user);
        change.setId(id);
        change.setDraft(draft);
        change.setOperationType("createClass");
        return repository.save(change);
    }

    private HistoryChange reload(String id) {
        return repository.findById(id).orElseThrow();
    }

    @Test
    void publishingClearsTheDraftFlagOnlyForThatUsersDraftEntriesInThatProject() {
        row("a", "p1", "u1", true);
        row("b", "p1", "u1", true);
        row("c", "p1", "u2", true);
        row("d", "p2", "u1", true);
        HistoryChange discarded = new HistoryChange("p1", "edit-x", "u1", "u1");
        discarded.setId("x");
        discarded.setDraft(true);
        discarded.setDraftDiscarded(true);
        repository.save(discarded);

        assertEquals(2, sync.markDraftsPublished("p1", "u1"));

        assertFalse(reload("a").isDraft());
        assertFalse(reload("b").isDraft());
        assertTrue(reload("c").isDraft());
        assertTrue(reload("d").isDraft());
        assertTrue(reload("x").isDraft());
    }

    @Test
    void publishingAPrsChangeSetsDoesNotDependOnWhichNameTheRowsWereSavedUnderAndSkipsDiscardedOnes() {
        HistoryChange a = row("a", "p1", "u1", true);
        a.setChangeSetId("cs1");
        repository.save(a);
        HistoryChange b = row("b", "p1", "someone-else", true);
        b.setChangeSetId("cs2");
        repository.save(b);
        HistoryChange gone = row("g", "p1", "u1", true);
        gone.setChangeSetId("cs1");
        gone.setDraftDiscarded(true);
        repository.save(gone);

        assertEquals(2, sync.markChangeSetsPublished("p1", List.of("cs1", "cs2")));

        assertFalse(reload("a").isDraft());
        assertFalse(reload("b").isDraft());
        assertTrue(reload("g").isDraft());
    }

    @Test
    void rowsThePublishWroteWithoutAChangeSetAreFoundByAuthorAndTimeAndNeverTheRollbackOrMergeRecords() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 15, 8, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 24, 15, 10, 10);
        HistoryChange plain = row("plain", "p1", "author", false);
        plain.setTimestamp(from.plusSeconds(30));
        repository.save(plain);
        HistoryChange publish = row("publish", "p1", "author", false);
        publish.setTimestamp(from.plusSeconds(40));
        publish.setChangeSetId("cs-publish");
        publish.setOperationType("publishDraft");
        repository.save(publish);
        HistoryChange rollback = row("rollback", "p1", "author", false);
        rollback.setTimestamp(from.plusSeconds(50));
        rollback.setSource("ROLLBACK");
        repository.save(rollback);
        HistoryChange marker = row("marker", "p1", "author", false);
        marker.setTimestamp(from.plusSeconds(60));
        marker.setOperationType("prPublished");
        repository.save(marker);
        HistoryChange early = row("early", "p1", "author", false);
        early.setTimestamp(from.minusMinutes(5));
        repository.save(early);
        HistoryChange withSet = row("withSet", "p1", "author", false);
        withSet.setTimestamp(from.plusSeconds(70));
        withSet.setChangeSetId("cs-other");
        repository.save(withSet);

        assertEquals(2, sync.countUnlinkedRows("p1", "author", from, to));
        assertEquals(2, sync.tagUnlinkedRows("p1", "author", from, to, "pr1"));

        assertEquals("pr1", reload("plain").getPublishedPrId());
        assertEquals("pr1", reload("publish").getPublishedPrId());
        assertNull(reload("rollback").getPublishedPrId());
        assertNull(reload("marker").getPublishedPrId());
        assertNull(reload("early").getPublishedPrId());
        assertNull(reload("withSet").getPublishedPrId());
        assertEquals(0, sync.countUnlinkedRows("p1", "author", from, to));
    }

    @Test
    void theUndoRowsOfAPrRevertAreFoundByWhatTheyUndid() {
        HistoryChange undo = row("undo", "p1", "u1", false);
        undo.setSource("ROLLBACK");
        undo.setOperationType("ROLLBACK_ENTRY");
        undo.getMetadata().put("revertsChangeSetId", "cs1");
        repository.save(undo);
        HistoryChange unrelated = row("unrelated", "p1", "u1", false);
        unrelated.setSource("ROLLBACK");
        unrelated.getMetadata().put("revertsChangeSetId", "cs9");
        repository.save(unrelated);

        assertEquals(1, sync.tagRollbackRowsOfPr("p1", List.of("cs1", "cs2"), "pr1"));

        assertEquals("pr1", reload("undo").getPublishedPrId());
        assertNull(reload("unrelated").getPublishedPrId());
        assertEquals(Set.of("cs1"), sync.setsUndoneForPr("p1", "pr1"));
    }

    @Test
    void theRowsOfAPrThatWasRevertedAreNotListedAsItsItems() {
        HistoryChange item = row("item", "p1", "u1", false);
        item.setPublishedPrId("pr1");
        repository.save(item);
        HistoryChange undo = row("undo", "p1", "u1", false);
        undo.setPublishedPrId("pr1");
        undo.setSource("ROLLBACK");
        repository.save(undo);

        assertEquals(List.of("item"), sync.publishedRows("p1", "pr1").stream().map(HistoryChange::getId).toList());
    }

    @Test
    void discardingKeepsTheChangeSetsOfAnOpenPullRequest() {
        HistoryChange open = row("open", "p1", "u1", true);
        open.setChangeSetId("cs-open");
        repository.save(open);
        HistoryChange other = row("other", "p1", "u1", true);
        other.setChangeSetId("cs-other");
        repository.save(other);
        row("bare", "p1", "u1", true);

        assertEquals(2, sync.markDraftsDiscarded("p1", "u1", List.of("cs-open")));

        assertFalse(reload("open").isDraftDiscarded());
        assertTrue(reload("other").isDraftDiscarded());
        assertTrue(reload("bare").isDraftDiscarded());
    }

    @Test
    void doesNothingWithoutAProjectAndUser() {
        row("a", "p1", "u1", true);

        assertEquals(0, sync.markDraftsPublished(null, "u1"));
        assertEquals(0, sync.markDraftsPublished("p1", null));

        assertTrue(reload("a").isDraft());
    }

    @Test
    void discardingMarksOnlyThatUsersLiveDraftEntriesInThatProject() {
        row("a", "p1", "u1", true);
        row("b", "p1", "u2", true);
        row("c", "p2", "u1", true);

        assertEquals(1, sync.markDraftsDiscarded("p1", "u1"));

        assertTrue(reload("a").isDraftDiscarded());
        assertFalse(reload("b").isDraftDiscarded());
        assertFalse(reload("c").isDraftDiscarded());
    }

    @Test
    void discardingWithoutAUserMarksEveryDraftEntryOfTheProject() {
        row("a", "p1", "u1", true);
        row("b", "p1", "u2", true);
        row("c", "p2", "u1", true);

        assertEquals(2, sync.markDraftsDiscarded("p1", null));

        assertTrue(reload("a").isDraftDiscarded());
        assertTrue(reload("b").isDraftDiscarded());
        assertFalse(reload("c").isDraftDiscarded());
    }

    @Test
    void aSubChangeIsMarkedRevertedByItsIdWithoutTouchingItsSiblings() {
        HistoryChange change = new HistoryChange("p1", "edit-1", "u1", "u1");
        change.setId("c1");
        HistoryChange.SubChange first = new HistoryChange.SubChange("p", "old", "new", null, true);
        first.setId("sub-1");
        HistoryChange.SubChange second = new HistoryChange.SubChange("q", "old2", "new2", null, false);
        second.setId("sub-2");
        change.setSubChanges(new java.util.ArrayList<>(List.of(first, second)));
        repository.save(change);

        sync.setSubChangesReverted("c1", List.of("sub-1"), true, "audit-1");

        List<HistoryChange.SubChange> subs = reload("c1").getSubChanges();
        assertEquals(List.of("sub-1", "sub-2"), subs.stream().map(HistoryChange.SubChange::getId).toList());
        assertTrue(subs.get(0).isReverted());
        assertEquals("audit-1", subs.get(0).getRevertedAuditId());
        assertFalse(subs.get(1).isReverted());
        assertNull(subs.get(1).getRevertedAuditId());

        sync.setSubChangesReverted("c1", List.of("sub-1"), false, null);

        assertFalse(reload("c1").getSubChanges().get(0).isReverted());
    }

    @Test
    void subChangesWithoutAnIdGetOneAndTheOnesWithAnIdKeepIt() {
        HistoryChange change = new HistoryChange("p1", "edit-1", "u1", "u1");
        change.setId("c1");
        HistoryChange.SubChange kept = new HistoryChange.SubChange("p", "a", "b", null, true);
        kept.setId("keep-me");
        HistoryChange.SubChange bare = new HistoryChange.SubChange("q", "a", "b", null, true);
        change.setSubChanges(new java.util.ArrayList<>(List.of(kept, bare)));
        repository.save(change);

        assertEquals(1, sync.backfillSubChangeIds());

        List<HistoryChange.SubChange> subs = reload("c1").getSubChanges();
        assertEquals("keep-me", subs.get(0).getId());
        assertEquals(36, subs.get(1).getId().length());
        assertEquals(0, sync.backfillSubChangeIds());
    }
}
