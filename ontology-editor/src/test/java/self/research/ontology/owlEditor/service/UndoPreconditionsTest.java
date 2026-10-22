package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UndoPreconditionsTest {

    private static final String P = "proj";
    private static final String A = "http://x/A";
    private static final String PARENT = "http://x/Parent";
    private static final String SUB = "http://www.w3.org/2000/01/rdf-schema#subClassOf";

    private RollbackGraphFacts facts;
    private UndoPreconditions guard;

    @BeforeEach
    void setUp() {
        facts = mock(RollbackGraphFacts.class);
        when(facts.entityExists(eq(P), anyString(), anyBoolean(), any())).thenReturn(true);
        when(facts.statementPresent(eq(P), anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(true);
        when(facts.referencedOutside(eq(P), anyString(), anyBoolean(), any(), any())).thenReturn(false);
        guard = new UndoPreconditions(new RollbackMutationPlanner(), facts);
    }

    private static HistoryChange created(String iri) {
        HistoryChange change = new HistoryChange();
        change.setId("c1");
        change.setProjectId(P);
        change.setUserId("u@x.com");
        change.setOperationType("createClass");
        change.setEntityIRI(iri);
        change.setEntityLabel("A");
        HistoryChange.SubChange parent = new HistoryChange.SubChange(SUB, null, PARENT, null, true);
        parent.setId("s1");
        List<HistoryChange.SubChange> subs = new ArrayList<>();
        subs.add(parent);
        change.setSubChanges(subs);
        return change;
    }

    @Test
    void allowsASpreadsheetImportWhoseIdIsNotAnEntity() {
        when(facts.entityExists(eq(P), anyString(), anyBoolean(), any())).thenReturn(false);
        HistoryChange imported = created("import-1");
        imported.setOperationType("SPREADSHEET_IMPORT");
        assertEquals(Optional.empty(), guard.violation(P, List.of(imported)));
    }

    @Test
    void stillRefusesAnAlreadyUndoneSpreadsheetImport() {
        HistoryChange imported = created("import-1");
        imported.setOperationType("SPREADSHEET_IMPORT");
        imported.setReverted(true);
        assertTrue(guard.violation(P, List.of(imported)).isPresent());
    }

    @Test
    void allowsAnUntouchedCreation() {
        assertEquals(Optional.empty(), guard.violation(P, List.of(created(A))));
    }

    @Test
    void refusesWhenOtherItemsStillReferToTheCreatedEntity() {
        when(facts.referencedOutside(eq(P), eq(A), anyBoolean(), any(), any())).thenReturn(true);
        Optional<String> problem = guard.violation(P, List.of(created(A)));
        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("Other items still refer to"));
    }

    @Test
    void refusesWhenTheAssertedParentWasChangedSince() {
        when(facts.statementPresent(eq(P), eq(A), eq(SUB), eq(PARENT), anyBoolean(), any()))
                .thenReturn(false);
        Optional<String> problem = guard.violation(P, List.of(created(A)));
        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("changed since"));
    }

    @Test
    void refusesWhenTheEntityIsGone() {
        when(facts.entityExists(eq(P), eq(A), anyBoolean(), any())).thenReturn(false);
        assertTrue(guard.violation(P, List.of(created(A))).get().contains("no longer exists"));
    }

    @Test
    void refusesWhenAlreadyUndone() {
        HistoryChange change = created(A);
        change.setReverted(true);
        assertTrue(guard.violation(P, List.of(change)).get().contains("already undone"));
    }

    @Test
    void refusesEntriesWithoutAnEntity() {
        HistoryChange change = created(null);
        assertTrue(guard.violation(P, List.of(change)).get().contains("can't be undone yet"));
    }

    @Test
    void treatsAnUnknownGraphAnswerAsUnchanged() {
        when(facts.statementPresent(eq(P), anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(null);
        assertEquals(Optional.empty(), guard.violation(P, List.of(created(A))));
    }
}
