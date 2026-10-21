package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;
import self.research.ontology.owlEditor.service.DatatypeDefinitionDraftService.Change;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatatypeDefinitionDraftServiceTest {

    private static final String P = "proj-1";
    private static final String IRI = "http://ex.org/Age";
    private static final String ME = "me@x.org";
    private static final String OTHER = "other@x.org";

    private DatatypeDefinitionRepository repository;
    private OntologyMutationService mutations;
    private DatatypeDefinitionDraftService service;
    private final List<DatatypeDefinitionEntity> store = new ArrayList<>();
    private int nextId;

    @BeforeEach
    void setUp() {
        repository = mock(DatatypeDefinitionRepository.class);
        mutations = mock(OntologyMutationService.class);
        service = new DatatypeDefinitionDraftService(repository, mutations);
        store.clear();
        nextId = 1;
        when(repository.save(any(DatatypeDefinitionEntity.class))).thenAnswer(invocation -> {
            DatatypeDefinitionEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId("id-" + nextId++);
                store.add(row);
            }
            return row;
        });
        when(repository.findByIdAndProjectId(anyString(), eq(P))).thenAnswer(invocation ->
                store.stream().filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst());
        when(repository.findByProjectIdAndOwnerUserIdAndDraftTrue(eq(P), anyString())).thenAnswer(invocation ->
                store.stream().filter(row -> row.isOwnedBy(invocation.getArgument(1))).toList());
        when(repository.findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue(eq(P), anyString(), anyString())).thenAnswer(
                invocation -> store.stream().filter(row -> row.isOwnedBy(invocation.getArgument(2))
                        && row.getDatatypeIri().equals(invocation.getArgument(1))).toList());
        when(repository.findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc(eq(P), anyString(), eq(true))).thenAnswer(
                invocation -> store.stream().filter(row -> !row.isDraft()
                        && row.getDatatypeIri().equals(invocation.getArgument(1))).toList());
        doAnswer(invocation -> {
            store.remove(invocation.<DatatypeDefinitionEntity>getArgument(0));
            return null;
        }).when(repository).delete(any(DatatypeDefinitionEntity.class));
        doAnswer(invocation -> {
            for (Object row : invocation.<Iterable<?>>getArgument(0)) {
                store.remove(row);
            }
            return null;
        }).when(repository).deleteAll(anyIterable());
    }

    private DatatypeDefinitionEntity shared(String id, String expression) {
        DatatypeDefinitionEntity row = new DatatypeDefinitionEntity();
        row.setId(id);
        row.setProjectId(P);
        row.setDatatypeIri(IRI);
        row.setExpression(expression);
        row.setDefinitionType("restriction");
        row.setCreatedAt(new Date());
        store.add(row);
        return row;
    }

    private List<String> expressionsFor(String user) {
        return service.list(P, IRI, user).stream().map(DatatypeDefinitionEntity::getExpression).sorted().toList();
    }

    @Test
    void aNewDefinitionIsAPrivateRowAndTheGraphChangeGoesToTheDraft() {
        Change change = service.create(P, ME, IRI, "xsd:integer[>= 0]", "restriction");

        assertNull(change.before());
        assertTrue(change.after().isDraft());
        assertEquals(ME, change.after().getOwnerUserId());
        assertEquals(List.of("xsd:integer[>= 0]"), expressionsFor(ME));
        assertEquals(List.of(), expressionsFor(OTHER));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MutationOp>> ops = ArgumentCaptor.forClass(List.class);
        verify(mutations).applyDraft(eq(P), eq(ME), ops.capture());
        assertEquals("addDatatypeDefinition", ops.getValue().get(0).type());
        verify(mutations, never()).apply(anyString(), anyList());
    }

    @Test
    void editingASharedDefinitionCreatesAnOverrideThatReplacesItOnlyForTheEditor() {
        shared("s1", "xsd:integer[>= 0, <= 150]");

        Change change = service.update(P, ME, "s1", "xsd:integer[>= 0, <= 120]", null).orElseThrow();

        assertNull(change.before());
        assertEquals("s1", change.after().getReplacesId());
        assertEquals(List.of("xsd:integer[>= 0, <= 120]"), expressionsFor(ME));
        assertEquals(List.of("xsd:integer[>= 0, <= 150]"), expressionsFor(OTHER));
        assertEquals("xsd:integer[>= 0, <= 150]", store.get(0).getExpression());
    }

    @Test
    void editingYourOwnDraftRowChangesItInPlaceAndKeepsTheBeforeImage() {
        DatatypeDefinitionEntity created = service.create(P, ME, IRI, "a", "expression").after();

        Change change = service.update(P, ME, created.getId(), "b", null).orElseThrow();

        assertEquals("a", change.before().getExpression());
        assertEquals("b", change.after().getExpression());
        assertEquals(1, store.size());
    }

    @Test
    void deletingASharedDefinitionHidesItOnlyForTheUserAndLeavesTheSharedRow() {
        shared("s1", "x");

        Change change = service.delete(P, ME, "s1").orElseThrow();

        assertTrue(change.after().isDeleted());
        assertEquals("s1", change.after().getReplacesId());
        assertEquals(List.of(), expressionsFor(ME));
        assertEquals(List.of("x"), expressionsFor(OTHER));
    }

    @Test
    void deletingYourOverrideTurnsItIntoAHidingRowAndDeletingYourNewRowRemovesIt() {
        shared("s1", "x");
        DatatypeDefinitionEntity override = service.update(P, ME, "s1", "y", null).orElseThrow().after();
        DatatypeDefinitionEntity created = service.create(P, ME, IRI, "n", "expression").after();

        Change hidden = service.delete(P, ME, override.getId()).orElseThrow();
        Change removed = service.delete(P, ME, created.getId()).orElseThrow();

        assertEquals("y", hidden.before().getExpression());
        assertTrue(hidden.after().isDeleted());
        assertNull(removed.after());
        assertFalse(store.contains(created));
        assertEquals(List.of(), expressionsFor(ME));
    }

    @Test
    void anotherUsersDraftRowIsNotFound() {
        DatatypeDefinitionEntity created = service.create(P, OTHER, IRI, "a", "expression").after();

        assertTrue(service.update(P, ME, created.getId(), "b", null).isEmpty());
        assertTrue(service.delete(P, ME, created.getId()).isEmpty());
        assertEquals(Optional.empty(), service.findVisible(P, ME, created.getId()));
    }

    @Test
    void publishingMakesNewRowsSharedAppliesOverridesAndDeletesHiddenOnes() {
        shared("s1", "old");
        shared("s2", "gone");
        service.create(P, ME, IRI, "new", "expression");
        service.update(P, ME, "s1", "edited", null);
        service.delete(P, ME, "s2");

        service.promote(P, ME);

        assertEquals(2, store.size());
        assertTrue(store.stream().noneMatch(DatatypeDefinitionEntity::isDraft));
        assertEquals(List.of("edited", "new"), expressionsFor(OTHER));
        assertTrue(store.stream().anyMatch(row -> row.getId().equals("s1") && row.getExpression().equals("edited")));
    }

    @Test
    void anOverrideWhoseSharedRowDisappearedBecomesASharedRowSoTheEditIsKept() {
        shared("s1", "old");
        service.update(P, ME, "s1", "edited", null);
        store.removeIf(row -> row.getId().equals("s1"));

        service.promote(P, ME);

        assertEquals(1, store.size());
        assertFalse(store.get(0).isDraft());
        assertEquals("edited", store.get(0).getExpression());
        assertNull(store.get(0).getReplacesId());
    }

    @Test
    void discardingRemovesOnlyThatUsersDraftRows() {
        shared("s1", "x");
        service.create(P, ME, IRI, "mine", "expression");
        service.create(P, OTHER, IRI, "theirs", "expression");

        service.discard(P, ME);

        assertEquals(List.of("x"), expressionsFor(ME));
        assertEquals(List.of("theirs", "x"), expressionsFor(OTHER));
    }
}
