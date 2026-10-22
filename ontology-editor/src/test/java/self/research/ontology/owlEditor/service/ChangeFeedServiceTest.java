package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.ChangeFeedService.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeFeedServiceTest {

    private HistoryChangeRepository repository;
    private ChangeFeedService service;

    @BeforeEach
    void setUp() {
        repository = mock(HistoryChangeRepository.class);
        service = new ChangeFeedService(repository);
    }

    private static HistoryChange change(String id, String user, String op, String label) {
        HistoryChange change = new HistoryChange("p", "edit-" + id, user, user);
        change.setId(id);
        change.setOperationType(op);
        change.setEntityLabel(label);
        change.setEntityIRI("http://ex/" + label);
        return change;
    }

    private void pages(List<HistoryChange> all) {
        when(repository.findByProjectId(eq("p"), any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(1);
            int from = (int) pageable.getOffset();
            int to = Math.min(all.size(), from + pageable.getPageSize());
            return from >= all.size() ? List.<HistoryChange>of() : all.subList(from, to);
        });
    }

    private static Query query(int count, int offset, String type, String author, String text, boolean rollbacks, String viewer) {
        return new Query(count, offset, type, author, text, rollbacks, viewer == null ? Set.of() : Set.of(viewer));
    }

    @Test
    void otherPeoplesDraftEntriesAreNeverReturned() {
        HistoryChange mine = change("1", "me@x.com", "createClass", "Mine");
        mine.setDraft(true);
        HistoryChange theirs = change("2", "you@x.com", "createClass", "Theirs");
        theirs.setDraft(true);
        HistoryChange published = change("3", "you@x.com", "createClass", "Public");
        pages(List.of(mine, theirs, published));

        List<HistoryChange> result = service.page("p", query(10, 0, null, null, null, true, "ME@x.com")).changes();

        assertEquals(List.of("1", "3"), result.stream().map(HistoryChange::getId).toList());
    }

    @Test
    void withoutAnIdentityNoDraftEntriesAreShown() {
        HistoryChange draft = change("1", "me@x.com", "createClass", "Mine");
        draft.setDraft(true);
        pages(List.of(draft, change("2", "me@x.com", "createClass", "Public")));

        assertEquals(List.of("2"), service.page("p", query(10, 0, null, null, null, true, null)).changes()
                .stream().map(HistoryChange::getId).toList());
    }

    @Test
    void rollbackRowsCanBeLeftOut() {
        HistoryChange undo = change("1", "me@x.com", "ROLLBACK_CHANGESET", "A");
        undo.setSource(ChangeOrigin.ROLLBACK);
        pages(List.of(undo, change("2", "me@x.com", "createClass", "A")));

        assertEquals(1, service.page("p", query(10, 0, null, null, null, false, "me@x.com")).changes().size());
        assertEquals(2, service.page("p", query(10, 0, null, null, null, true, "me@x.com")).changes().size());
    }

    @Test
    void filtersByBucketAuthorAndText() {
        pages(List.of(change("1", "a@x.com", "prefixAdded", "ex"), change("2", "b@x.com", "createClass", "Pizza"),
                change("3", "b@x.com", "addObjectPropertyAssertion", "Rex")));

        assertEquals(List.of("1"), ids(service.page("p", query(10, 0, "PREFIX", null, null, true, "a@x.com"))));
        assertEquals(List.of("3"), ids(service.page("p", query(10, 0, "individual", null, null, true, "a@x.com"))));
        assertEquals(List.of("2", "3"), ids(service.page("p", query(10, 0, null, "B@x.com", null, true, "a@x.com"))));
        assertEquals(List.of("2"), ids(service.page("p", query(10, 0, null, null, "piz", true, "a@x.com"))));
        assertEquals(List.of("3"), ids(service.page("p", query(10, 0, null, null, "http://ex/Rex", true, "a@x.com"))));
    }

    @Test
    void pagesThroughMoreEntriesThanOneFetchAndReportsWhetherMoreRemain() {
        List<HistoryChange> all = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            all.add(change("c" + i, "a@x.com", "createClass", "C" + i));
        }
        pages(all);

        ChangeFeedService.Page first = service.page("p", query(100, 0, null, null, null, true, "a@x.com"));
        ChangeFeedService.Page second = service.page("p", query(100, 100, null, null, null, true, "a@x.com"));
        ChangeFeedService.Page last = service.page("p", query(100, 400, null, null, null, true, "a@x.com"));

        assertEquals(100, first.changes().size());
        assertTrue(first.hasMore());
        assertEquals("c100", second.changes().get(0).getId());
        assertEquals(50, last.changes().size());
        assertFalse(last.hasMore());
    }

    @Test
    void anEmptyProjectReturnsAnEmptyFeed() {
        pages(List.of());
        ChangeFeedService.Page page = service.page("p", query(10, 0, null, null, null, true, "a@x.com"));
        assertTrue(page.changes().isEmpty());
        assertFalse(page.hasMore());
    }

    private static List<String> ids(ChangeFeedService.Page page) {
        return page.changes().stream().map(HistoryChange::getId).toList();
    }
}
