package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRollbackActionsTest {

    private static final String ID = "6acf64ae94e5320b04bb880a";
    private static final String OLD = "{\"id\":\"" + ID + "\",\"projectId\":\"p\",\"name\":\"old\"}";
    private static final String NEW = "{\"id\":\"" + ID + "\",\"projectId\":\"p\",\"name\":\"new\"}";
    private static final String LEGACY = "{\"_id\":{\"$oid\":\"" + ID + "\"},\"projectId\":\"p\",\"name\":\"old\"}";

    private DatatypeDefinitionRepository datatypes;
    private FuzzyRuleRepository fuzzy;
    private DocumentRollbackActions actions;
    private DocumentSnapshots snapshots;

    @BeforeEach
    void setUp() {
        datatypes = mock(DatatypeDefinitionRepository.class);
        fuzzy = mock(FuzzyRuleRepository.class);
        snapshots = new DocumentSnapshots();
        actions = new DocumentRollbackActions(datatypes, fuzzy);
    }

    private static RollbackAction restore(String target, String expected) {
        return RollbackAction.restoreDocument("fuzzyRule", ID, target, expected);
    }

    private static FuzzyRuleEntity rule(String name) {
        FuzzyRuleEntity entity = new FuzzyRuleEntity();
        entity.setId(ID);
        entity.setProjectId("p");
        entity.setName(name);
        return entity;
    }

    @Test
    void theCurrentDocumentMustStillEqualTheExpectedOne() {
        FuzzyRuleEntity current = rule("new");
        when(fuzzy.findById(ID)).thenReturn(Optional.of(current));
        String expected = snapshots.snapshot(current);
        String other = snapshots.snapshot(rule("old"));

        assertTrue(actions.holds("p", restore(other, expected), false, null));
        assertFalse(actions.holds("p", restore(expected, other), false, null));
    }

    @Test
    void aMissingDocumentOnlyMatchesAnExpectedAbsence() {
        when(fuzzy.findById(anyString())).thenReturn(Optional.empty());

        assertTrue(actions.holds("p", restore(OLD, null), false, null));
        assertFalse(actions.holds("p", restore(OLD, NEW), false, null));
    }

    @Test
    void restoringSavesTheOldStateKeepingItsId() {
        actions.run("p", restore(snapshots.snapshot(rule("old")), snapshots.snapshot(rule("new"))), false, null);

        ArgumentCaptor<FuzzyRuleEntity> saved = ArgumentCaptor.forClass(FuzzyRuleEntity.class);
        verify(fuzzy).save(saved.capture());
        assertEquals(ID, saved.getValue().getId());
        assertEquals("old", saved.getValue().getName());
        verify(fuzzy, never()).deleteById(anyString());
    }

    @Test
    void aDocumentOfAnotherProjectIsNeverWritten() {
        assertThrows(IllegalArgumentException.class, () -> actions.run("other", restore(OLD, NEW), false, null));
        verify(fuzzy, never()).save(any());
    }

    @Test
    void aDatatypeDefinitionIsRestoredThroughItsOwnRepository() {
        String before = "{\"id\":\"" + ID + "\",\"projectId\":\"p\",\"datatypeIri\":\"http://x/d\"}";

        actions.run("p", RollbackAction.restoreDocument("datatypeDefinition", ID, before, null), false, null);

        ArgumentCaptor<DatatypeDefinitionEntity> saved = ArgumentCaptor.forClass(DatatypeDefinitionEntity.class);
        verify(datatypes).save(saved.capture());
        assertEquals("http://x/d", saved.getValue().getDatatypeIri());
        verify(fuzzy, never()).save(any());
    }

    @Test
    void theOldSavedRuleDocumentsAreNeverRewrittenAndNeverHold() {
        RollbackAction action = RollbackAction.restoreDocument("swrlRule", ID, OLD, NEW);

        assertFalse(actions.holds("p", action, false, null));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> actions.run("p", action, false, null));
        assertEquals("Saved rules moved into the ontology; undo from the rule list", refused.getMessage());
        verify(fuzzy, never()).save(any());
        verify(fuzzy, never()).deleteById(anyString());
    }

    @Test
    void restoringAnAbsentStateDeletesTheDocument() {
        actions.run("p", restore(null, NEW), false, null);

        verify(fuzzy).deleteById(ID);
    }

    @Test
    void aDocumentSavedInTheOldMongoFormatIsRefusedWithAClearMessage() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> actions.run("p", restore(LEGACY, NEW), false, null));

        assertEquals(DocumentRollbackActions.LEGACY_FORMAT, refused.getMessage());
        assertFalse(actions.holds("p", restore(OLD, LEGACY), false, null));
        verify(fuzzy, never()).save(any());
    }

    @Test
    void anUnknownStoreNeverHolds() {
        assertFalse(actions.holds("p", RollbackAction.restoreDocument("nope", "1", OLD, NEW), false, null));
    }

    @Test
    void theCompositeSendsDocumentActionsToTheDocumentRunnerAndTheRestToThePrefixRunner() {
        PrefixRollbackActions prefix = mock(PrefixRollbackActions.class);
        DocumentRollbackActions document = mock(DocumentRollbackActions.class);
        CompositeRollbackActions composite = new CompositeRollbackActions(prefix, document, mock(StoredFileRollbackActions.class), mock(MergeCheckpointRollbackActions.class));

        composite.run("p", restore(OLD, NEW), false, null);
        composite.run("p", RollbackAction.deletePrefix("ex", "http://x/"), false, null);

        verify(document).run("p", restore(OLD, NEW), false, null);
        verify(prefix).run("p", RollbackAction.deletePrefix("ex", "http://x/"), false, null);
    }
}
