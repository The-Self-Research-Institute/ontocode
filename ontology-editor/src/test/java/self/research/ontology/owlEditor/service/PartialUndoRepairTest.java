package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.delta.DeltaBatches;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;

import java.io.StringReader;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartialUndoRepairTest {

    private static final String P = "proj";
    private static final String MAIN = "http://ontocode.org/project/proj";
    private static final String EX = "http://ex/";
    private static final int BATCH = 4;
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final String BASE = "<http://ex/Keep> <http://www.w3.org/2000/01/rdf-schema#label> \"keep\" .\n";
    private static final String REMOVED = labels("D", "old", 6)
            + "<http://ex/A> <http://www.w3.org/2000/01/rdf-schema#subClassOf> _:r .\n"
            + "_:r <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Restriction> .\n"
            + "_:r <http://www.w3.org/2002/07/owl#onProperty> <http://ex/p> .\n"
            + "_:r <http://www.w3.org/2002/07/owl#someValuesFrom> <http://ex/B> .\n";
    private static final String ADDED = labels("I", "new", 8)
            + "<http://ex/C> <http://www.w3.org/2000/01/rdf-schema#subClassOf> _:s .\n"
            + "_:s <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Restriction> .\n"
            + "_:s <http://www.w3.org/2002/07/owl#onProperty> <http://ex/q> .\n"
            + "_:s <http://www.w3.org/2002/07/owl#allValuesFrom> <http://ex/D> .\n";

    private SailRepository store;
    private final java.util.concurrent.atomic.AtomicInteger failOnWrite = new java.util.concurrent.atomic.AtomicInteger();
    private FakeUndoIntents fake;
    private HistorySyncService history;
    private UndoReconciler reconciler;
    private HistoryChange entry;
    private List<ExactDelta> batches;

    private static String labels(String prefix, String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            out.append("<http://ex/").append(prefix).append(i).append("> <http://www.w3.org/2000/01/rdf-schema#label> \"")
                    .append(text).append(' ').append(i).append("\" .\n");
        }
        return out.toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new SailRepository(new MemoryStore());
        store.init();
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.getGraphUri(P)).thenReturn(MAIN);
        when(datasets.execAskInGraph(eq(P), anyString(), anyString())).thenAnswer(call -> {
            try (RepositoryConnection c = store.getConnection()) {
                return c.prepareBooleanQuery(call.<String>getArgument(2)).evaluate();
            }
        });
        OntologyMutationService mutations = mock(OntologyMutationService.class);
        doAnswer(call -> {
            if (failOnWrite.get() > 0 && failOnWrite.decrementAndGet() == 0) {
                throw new IllegalStateException("Fuseki stopped answering");
            }
            return apply(call.getArgument(1));
        }).when(mutations).applyRawUpdate(eq(P), anyString(), anyBoolean(), any());
        history = mock(HistorySyncService.class);
        fake = new FakeUndoIntents();
        reconciler = new UndoReconciler(fake.service, history, new RollbackMutationPlanner(),
                new RollbackGraphFacts(datasets, null, null), mutations, mock(RollbackActionRunner.class),
                new ProjectWriteLockRegistry());

        entry = new HistoryChange.Builder(P, "edit", "u@x.com", "u@x.com").operationType("editClass")
                .entityIRI(EX + "A").entityLabel("A").build();
        entry.setId("e1");
        entry.setExactDeleted(REMOVED);
        entry.setExactInserted(ADDED);
        when(history.getHistoryChange("e1")).thenReturn(entry);
        batches = DeltaBatches.split(ExactReversal.deltaOf(entry).orElseThrow(), BATCH).stream()
                .filter(batch -> !batch.isEmpty()).toList();
    }

    @AfterEach
    void tearDown() {
        store.shutDown();
    }

    private Object apply(String sparql) {
        try (RepositoryConnection c = store.getConnection()) {
            Update update = c.prepareUpdate(sparql);
            SimpleDataset dataset = new SimpleDataset();
            IRI graph = VF.createIRI(MAIN);
            dataset.addDefaultGraph(graph);
            dataset.addDefaultRemoveGraph(graph);
            dataset.setDefaultInsertGraph(graph);
            update.setDataset(dataset);
            update.execute();
        }
        return null;
    }

    private static Model parse(String... parts) throws Exception {
        return Rio.parse(new StringReader(String.join("", parts)), "", RDFFormat.NTRIPLES);
    }

    private void graphIs(Model model) {
        try (RepositoryConnection c = store.getConnection()) {
            c.clear(VF.createIRI(MAIN));
            c.add(model, VF.createIRI(MAIN));
        }
    }

    private Model graph() {
        try (RepositoryConnection c = store.getConnection()) {
            Model out = new LinkedHashModel();
            c.getStatements(null, null, null, false, VF.createIRI(MAIN)).forEach(st ->
                    out.add(st.getSubject(), st.getPredicate(), st.getObject()));
            return out;
        }
    }

    private long restrictions() {
        return graph().filter(null, RDF.TYPE, OWL.RESTRICTION).size();
    }

    private void removeFromGraph(String subject, String label) {
        try (RepositoryConnection c = store.getConnection()) {
            c.remove(VF.createIRI(EX + subject), RDFS.LABEL, VF.createLiteral(label), VF.createIRI(MAIN));
        }
    }

    private UndoIntentDocument stoppedAfter(String direction, int applied) {
        UndoIntentDocument intent = fake.seed(P, "op-1", direction, Status.NEEDS_ATTENTION, Stage.STARTED,
                List.of("e1"), "audit-1", null);
        intent.setBatchChangeId("e1");
        intent.setAppliedBatches(applied);
        intent.setBatchTriples(BATCH);
        intent.setBatchInFlight(true);
        return intent;
    }

    private void undoBatches(int... indexes) {
        IntStream.of(indexes).forEach(i -> apply(DeltaSparql.reverse(batches.get(i))));
    }

    @Test
    void theTestChangeSplitsIntoBatchesWithABlankNodeAddInTheMiddle() {
        assertEquals(6, batches.size());
        assertTrue(batches.get(2).deleted().stream().anyMatch(st -> st.getSubject().isBNode() || st.getObject().isBNode()));
    }

    @Test
    void anUndoThatStoppedBetweenBatchesIsFinishedFromWhereItStopped() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1);
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 2);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(parse(BASE, REMOVED), graph()), graph().toString());
        assertEquals(Status.COMMITTED, intent.getStatus());
        verify(history).setEntryReverted("e1", true, "audit-1");
    }

    @Test
    void aBlankNodeBatchThatWentThroughDespiteTheErrorIsNotAddedTwice() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1, 2);
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 2);

        reconciler.resolveLocked(P);

        assertEquals(1, restrictions());
        assertTrue(Models.isomorphic(parse(BASE, REMOVED), graph()), graph().toString());
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void whenTheRestNoLongerAppliesTheFinishedPartIsReversed() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1);
        removeFromGraph("I5", "new 5");
        Model expected = parse(BASE, ADDED);
        expected.remove(VF.createIRI(EX + "I5"), RDFS.LABEL, VF.createLiteral("new 5"));
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 2);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(expected, graph()), graph().toString());
        assertEquals(Status.FAILED, intent.getStatus());
        verify(history, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void whenTheFinishedPartWasChangedSinceNothingIsTouched() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1);
        removeFromGraph("D1", "old 1");
        Model before = new LinkedHashModel(graph());
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 2);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(before, graph()));
        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        verify(history, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void aRedoThatStoppedBetweenBatchesIsFinishedToo() throws Exception {
        graphIs(parse(BASE, REMOVED));
        apply(DeltaSparql.forward(batches.get(0)));
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.REDO, 1);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(parse(BASE, ADDED), graph()), graph().toString());
        assertEquals(1, restrictions());
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void aFirstBatchThatWentThroughBeforeTheTimeoutIsNotSentAgain() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0);
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 0);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(parse(BASE, REMOVED), graph()), graph().toString());
        assertEquals(1, restrictions());
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    @Test
    void aFirstBatchThatNeverLandedIsCalledSafeOnlyAfterTheGraphIsChecked() throws Exception {
        graphIs(parse(BASE, ADDED));
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 0);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(parse(BASE, ADDED), graph()), graph().toString());
        assertEquals(Status.FAILED, intent.getStatus());
        verify(history, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }

    @Test
    void aRepairThatStopsHalfwayIsRepairedCorrectlyTheNextTime() throws Exception {
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1);
        UndoIntentDocument intent = stoppedAfter(ChangeRollbackService.UNDO, 2);
        failOnWrite.set(2);

        reconciler.resolveLocked(P);

        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        assertEquals(3, intent.getAppliedBatches());
        assertTrue(intent.isBatchInFlight());

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(parse(BASE, REMOVED), graph()), graph().toString());
        assertEquals(1, restrictions());
        assertEquals(Status.COMMITTED, intent.getStatus());
    }

    private static final String SMALL_OLD = "<http://ex/X> <http://www.w3.org/2000/01/rdf-schema#label> \"x old\" .\n";
    private static final String SMALL_NEW = "<http://ex/X> <http://www.w3.org/2000/01/rdf-schema#label> \"x new\" .\n";

    private HistoryChange smallChangeAlreadyUndoneByThisOperation() {
        HistoryChange small = new HistoryChange.Builder(P, "edit", "u@x.com", "u@x.com").operationType("editClass")
                .entityIRI(EX + "X").entityLabel("X").build();
        small.setId("e0");
        small.setExactDeleted(SMALL_OLD);
        small.setExactInserted(SMALL_NEW);
        small.setReverted(true);
        small.setRevertedAuditId("audit-1");
        when(history.getHistoryChange("e0")).thenReturn(small);
        return small;
    }

    private UndoIntentDocument changeSetStoppedInSecondEntry(int applied) {
        UndoIntentDocument intent = fake.seed(P, "op-2", ChangeRollbackService.UNDO, Status.NEEDS_ATTENTION, Stage.HISTORY_MARKED,
                List.of("e0", "e1"), "audit-1", null);
        intent.setBatchChangeId("e1");
        intent.setAppliedBatches(applied);
        intent.setBatchTriples(BATCH);
        intent.setBatchInFlight(true);
        return intent;
    }

    @Test
    void aChangeSetThatCannotFinishPutsBackEveryChangeItHadMade() throws Exception {
        smallChangeAlreadyUndoneByThisOperation();
        graphIs(parse(BASE, SMALL_OLD, ADDED));
        undoBatches(0, 1);
        removeFromGraph("I5", "new 5");
        Model expected = parse(BASE, SMALL_NEW, ADDED);
        expected.remove(VF.createIRI(EX + "I5"), RDFS.LABEL, VF.createLiteral("new 5"));
        UndoIntentDocument intent = changeSetStoppedInSecondEntry(2);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(expected, graph()), graph().toString());
        assertEquals(Status.FAILED, intent.getStatus());
        assertTrue(intent.getFailureReason().contains("safe to try again"), intent.getFailureReason());
        verify(history).setEntryReverted("e0", false, null);
    }

    @Test
    void aChangeSetWhoseFinishedPartWasEditedSinceIsLeftAloneAndListed() throws Exception {
        smallChangeAlreadyUndoneByThisOperation();
        graphIs(parse(BASE, ADDED));
        undoBatches(0, 1);
        removeFromGraph("I5", "new 5");
        Model before = new LinkedHashModel(graph());
        UndoIntentDocument intent = changeSetStoppedInSecondEntry(2);

        reconciler.resolveLocked(P);

        assertTrue(Models.isomorphic(before, graph()));
        assertEquals(Status.NEEDS_ATTENTION, intent.getStatus());
        assertTrue(intent.getFailureReason().contains("Still undone: X, A"), intent.getFailureReason());
        verify(history, never()).setEntryReverted(anyString(), anyBoolean(), any());
    }
}
