package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

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

class DraftUndoTrackingTest {

    private static final String P = "proj";
    private static final String USER = "mongo-1";
    private static final String NAME = "real@x.org";
    private static final String A = "http://ex.org/A";
    private static final String B = "http://ex.org/B";
    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";
    private static final Actor ACTOR = new Actor(NAME, NAME);

    private final List<DraftChange> drafts = new ArrayList<>();
    private final Map<String, HistoryChange> entries = new HashMap<>();
    private final Map<String, RollbackAudit> savedAudits = new HashMap<>();
    private int draftIds;
    private OntologyHistoryService history;
    private DraftTrackingService draftTracking;
    private CollaborativeEditService collaborators;
    private DraftMergeApplier applier;
    private ChangeRollbackService service;

    @BeforeEach
    void setUp() {
        DraftChangeRepository repository = mock(DraftChangeRepository.class);
        when(repository.saveAll(any())).thenAnswer(inv -> {
            List<DraftChange> saved = new ArrayList<>();
            for (DraftChange draft : (Iterable<DraftChange>) inv.getArgument(0)) {
                draft.setId("d" + (++draftIds));
                saved.add(draft);
            }
            drafts.addAll(saved);
            return saved;
        });
        when(repository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(anyString(), anyString()))
                .thenAnswer(inv -> drafts.stream()
                        .filter(d -> d.getProjectId().equals(inv.getArgument(0)) && d.getUserId().equals(inv.getArgument(1))
                                && !d.isApplied())
                        .toList());
        doAnswer(inv -> drafts.removeIf(d -> d.getId().equals(inv.getArgument(0)))).when(repository).deleteById(anyString());

        DraftCopyService copies = mock(DraftCopyService.class);
        when(copies.isReady(P, USER)).thenReturn(true);
        collaborators = mock(CollaborativeEditService.class);
        draftTracking = new DraftTrackingService(repository, mock(OntologyMutationService.class),
                mock(SparqlDatasetService.class), mock(OntologyIndexService.class), mock(ProjectMetadataService.class),
                mock(DraftChangeHistoryRecorder.class), mock(Executor.class), collaborators,
                mock(DraftPublishService.class), mock(MainGraphRevisionService.class), mock(DraftPublishMergeService.class),
                copies);

        history = mock(OntologyHistoryService.class);
        OntologyMutationService mutations = mock(OntologyMutationService.class);
        applier = new DraftMergeApplier(mutations, draftTracking, history, copies, new ProjectWriteLockRegistry());

        RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            if (audit.getId() == null) {
                audit.setId("audit-" + (savedAudits.size() + 1));
            }
            savedAudits.put(audit.getId(), audit);
            return audit;
        });
        when(audits.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(savedAudits.get(inv.getArgument(0))));

        HistorySyncService historySync = mock(HistorySyncService.class);
        when(historySync.getHistoryChange(anyString())).thenAnswer(inv -> entries.get(inv.getArgument(0)));
        doAnswer(inv -> {
            HistoryChange entry = entries.get(inv.getArgument(0));
            entry.setReverted(inv.getArgument(1));
            entry.setRevertedAuditId(inv.getArgument(2));
            return null;
        }).when(historySync).setEntryReverted(anyString(), anyBoolean(), any());

        RollbackGraphFacts facts = mock(RollbackGraphFacts.class);
        when(facts.entityExists(anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
        when(facts.deltaHolds(anyString(), any(), anyBoolean())).thenReturn(true);

        service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts, mutations, audits, history,
                new ProjectWriteLockRegistry(), draftTracking, null);
        RollbackActionRunner actions = mock(RollbackActionRunner.class);
        when(actions.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "actionRunner", actions);
    }

    private static Model triple(String subject, String object) {
        Model model = new LinkedHashModel();
        var vf = SimpleValueFactory.getInstance();
        model.add(vf.createIRI(subject), vf.createIRI(DISJOINT), vf.createIRI(object));
        return model;
    }

    private long pending() {
        return (long) draftTracking.getDraftStatistics(P, USER).get("unappliedDrafts");
    }

    private HistoryChange draftEntry(String id, String operation, String iri, String label, Model inserted) {
        HistoryChange entry = new HistoryChange.Builder(P, "edit-" + id, USER, NAME)
                .operationType(operation).entityIRI(iri).entityLabel(label).subChanges(new ArrayList<>()).build();
        entry.setId(id);
        entry.setDraft(true);
        entry.setExactInserted(ExactDelta.write(inserted));
        entries.put(id, entry);
        return entry;
    }

    private HistoryChange mergeIntoTheDraft() {
        ExactDelta delta = new ExactDelta(new LinkedHashModel(), triple(A, B));
        applier.apply(P, USER, NAME, delta, "Merged other.owl");
        ArgumentCaptor<String> entity = ArgumentCaptor.forClass(String.class);
        verify(history).recordEdit(eq(P), eq(USER), eq(NAME), eq("ontologyMerge"), entity.capture(), eq("Ontology merge"),
                any(), any(), anyString(), any(), any(), eq(true), any(ChangeOrigin.class));
        return draftEntry("m1", "ontologyMerge", entity.getValue(), "Ontology merge", delta.inserted());
    }

    @Test
    void undoingADraftMergeLeavesNoChangeToRaiseAPullRequestFor() {
        mergeIntoTheDraft();
        assertEquals(1, pending());

        Result result = service.undoEntry(P, "m1", ACTOR, false);

        assertTrue(result.ok(), result.message() + " " + result.skipped());
        assertEquals(0, pending());
    }

    @Test
    void redoingADraftMergeCountsItAgainAsOneMergeChange() {
        HistoryChange merge = mergeIntoTheDraft();
        Result undone = service.undoEntry(P, "m1", ACTOR, false);
        assertEquals(0, pending());

        Result redone = service.redoUndo(P, undone.auditId(), ACTOR, false);

        assertTrue(redone.ok(), redone.message() + " " + redone.skipped());
        assertEquals(1, pending());
        DraftChange marker = drafts.get(0);
        assertEquals(merge.getEntityIRI(), marker.getOperationData().get("iri"));
        assertEquals("ontologyMerge", marker.getOperationType());
        assertTrue(DraftMergeApplier.isMergeSession(marker.getSessionId()));
    }

    @Test
    void publishingAfterUndoingADraftMergeTellsNoCollaboratorAboutAMerge() {
        mergeIntoTheDraft();
        service.undoEntry(P, "m1", ACTOR, false);

        DraftTrackingService.ApplyDraftsResult published = draftTracking.applyDrafts(P, USER, false);

        assertTrue(published.isSuccess(), published.getMessage());
        assertEquals(0, published.getAppliedCount());
        verify(collaborators, never()).broadcastMutation(anyString(), any(), anyString(), any());
    }

    @Test
    void undoingAnExactEntityEditorEditDropsItsDraftRecordButKeepsOtherEntities() {
        draftTracking.recordDrafts(P, USER, NAME, List.of(op("addDisjointWith", A), op("addAnnotation", B)), "session-1");
        draftEntry("e1", "addDisjointWith", A, "A", triple(A, B));

        Result result = service.undoEntry(P, "e1", ACTOR, false);

        assertTrue(result.ok(), result.message() + " " + result.skipped());
        assertEquals(1, pending());
        assertEquals(B, drafts.get(0).getOperationData().get("iri"));
    }

    @Test
    void redoingAnExactEntityEditorEditRecordsItsDraftChangeAgain() {
        draftTracking.recordDrafts(P, USER, NAME, List.of(op("addDisjointWith", A)), "session-1");
        draftEntry("e1", "addDisjointWith", A, "A", triple(A, B));
        Result undone = service.undoEntry(P, "e1", ACTOR, false);
        assertEquals(0, pending());

        Result redone = service.redoUndo(P, undone.auditId(), ACTOR, false);

        assertTrue(redone.ok(), redone.message() + " " + redone.skipped());
        assertEquals(1, pending());
        assertEquals(A, drafts.get(0).getOperationData().get("iri"));
        assertEquals("addDisjointWith", drafts.get(0).getOperationType());
    }

    @Test
    void theUndoOfADraftChangeIsRecordedUnderTheDraftOwnerSoPublishingFlipsIt() {
        mergeIntoTheDraft();

        service.undoEntry(P, "m1", ACTOR, false);

        verify(history).recordEdit(eq(P), eq(USER), eq(NAME), eq("ROLLBACK_ENTRY"), any(), any(), any(), any(),
                anyString(), any(), any(), eq(true), any(ChangeOrigin.class));
    }

    private static final String CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String INDIVIDUAL = "http://www.w3.org/2002/07/owl#NamedIndividual";
    private static final String TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static void declare(Model model, String iri, String type, String label) {
        var vf = SimpleValueFactory.getInstance();
        model.add(vf.createIRI(iri), vf.createIRI(TYPE), vf.createIRI(type));
        if (label != null) {
            model.add(vf.createIRI(iri), vf.createIRI(LABEL), vf.createLiteral(label));
        }
    }

    private HistoryChange mergeWithSummary() {
        Model inserted = triple(A, B);
        declare(inserted, "http://ex.org/Pizza", CLASS, "Pizza");
        declare(inserted, "http://ex.org/PizzaTopping", CLASS, null);
        declare(inserted, "http://ex.org/Pizza_imported", CLASS, "Pizza_imported");
        declare(inserted, "http://ex.org/hasTopping", OBJECT_PROPERTY, "has topping");
        Model deleted = new LinkedHashModel();
        declare(deleted, "http://ex.org/oldOne", INDIVIDUAL, "Old one");
        ExactDelta delta = new ExactDelta(deleted, inserted);
        applier.apply(P, USER, NAME, delta, "Merged other.owl");
        ArgumentCaptor<String> entity = ArgumentCaptor.forClass(String.class);
        verify(history).recordEdit(eq(P), eq(USER), eq(NAME), eq("ontologyMerge"), entity.capture(), eq("Ontology merge"),
                any(), any(), anyString(), any(), any(), eq(true), any(ChangeOrigin.class));
        HistoryChange entry = draftEntry("m1", "ontologyMerge", entity.getValue(), "Ontology merge", inserted);
        entry.setExactDeleted(ExactDelta.write(deleted));
        entry.setDescription("Merged other.owl");
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> kind(Map<String, Object> summary, String kind) {
        return ((List<Map<String, Object>>) summary.get("kinds")).stream()
                .filter(row -> kind.equals(row.get("kind"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> markerSummary() {
        assertEquals(1, pending());
        return (Map<String, Object>) drafts.get(0).getOperationData().get(DraftMergeApplier.SUMMARY);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDraftMergeRecordsWhatItChangedGroupedByKind() {
        mergeWithSummary();

        Map<String, Object> summary = markerSummary();
        assertEquals("Merged other.owl", summary.get("description"));
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        assertEquals(3, totals.get("added"));
        assertEquals(1, totals.get("copies"));
        assertEquals(1, totals.get("changed"));
        assertEquals(1, totals.get("removed"));
        Map<String, Object> classes = kind(summary, "class");
        assertEquals(List.of("Pizza", "PizzaTopping"), classes.get("added"));
        assertEquals(2, classes.get("addedCount"));
        assertEquals(List.of("Pizza_imported"), classes.get("copies"));
        assertEquals(List.of("has topping"), kind(summary, "objectProperty").get("added"));
        assertEquals(List.of("Old one"), kind(summary, "individual").get("removed"));
        assertEquals(List.of("A"), kind(summary, "other").get("changed"));
        assertEquals("Merged other.owl", drafts.get(0).getOperationData().get("value"));
    }

    @Test
    void redoingADraftMergeKeepsItsSummary() {
        mergeWithSummary();
        Map<String, Object> original = markerSummary();
        Result undone = service.undoEntry(P, "m1", ACTOR, false);
        assertEquals(0, pending());

        Result redone = service.redoUndo(P, undone.auditId(), ACTOR, false);

        assertTrue(redone.ok(), redone.message() + " " + redone.skipped());
        assertEquals(original, markerSummary());
    }

    private static MutationOp op(String type, String iri) {
        return new MutationOp(type, iri, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
