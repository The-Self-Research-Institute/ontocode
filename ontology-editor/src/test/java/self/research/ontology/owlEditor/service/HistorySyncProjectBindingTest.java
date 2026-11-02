package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistorySyncProjectBindingTest {

    private HistoryChangeRepository repository;
    private HistorySyncService sync;
    private HistoryChange change;

    @BeforeEach
    void setUp() {
        repository = mock(HistoryChangeRepository.class);
        sync = new HistorySyncService(repository, mock(MongoTemplate.class), mock(OntologyHistoryService.class));
        change = new HistoryChange();
        change.setId("c1");
        change.setProjectId("proj-victim");
        when(repository.findById("c1")).thenReturn(Optional.of(change));
    }

    @Test
    void aChangeOfAnotherProjectCannotBeApproved() {
        assertFalse(sync.approveChange("proj-mine", "c1", "u-1", "me@x.org"));

        verify(repository, never()).save(any());
    }

    @Test
    void aChangeOfAnotherProjectCannotBeRejectedCommentedOrResolved() {
        assertFalse(sync.rejectChange("proj-mine", "c1", "u-1", "me@x.org"));
        assertFalse(sync.addComment("proj-mine", "c1", "u-1", "me@x.org", "hi"));
        assertFalse(sync.resolveConflict("proj-mine", "c1", "u-1", "me@x.org", "keep", null));

        verify(repository, never()).save(any());
    }

    @Test
    void aChangeOfTheSameProjectIsApproved() {
        assertTrue(sync.approveChange("proj-victim", "c1", "u-1", "me@x.org"));

        assertEquals("APPROVED", change.getStatus());
        verify(repository).save(change);
    }

    @Test
    void aCommentFoundByItsEditIdIsAlsoCheckedAgainstTheProject() {
        when(repository.findById("e1")).thenReturn(Optional.empty());
        when(repository.findByEditId("e1")).thenReturn(Optional.of(change));

        assertFalse(sync.addComment("proj-mine", "e1", "u-1", "me@x.org", "hi"));
        assertTrue(sync.addComment("proj-victim", "e1", "u-1", "me@x.org", "hi"));
    }
}
