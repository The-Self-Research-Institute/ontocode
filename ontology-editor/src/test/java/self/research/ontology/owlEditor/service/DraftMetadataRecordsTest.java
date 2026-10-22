package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.impl.MutableTupleQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class DraftMetadataRecordsTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String P = "p1";
    private static final String USER = "u1";
    private static final String NAME = "User One";
    private static final String GRAPH = "urn:draft:u1";
    private static final String ONTOLOGY = "http://ex.org/onto";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String LEGACY_GCI = "http://ontocode.org/resource/gci";
    private static final Actor ACTOR = new Actor(USER, NAME);

    private SailRepository repository;
    private SparqlDatasetService datasets;
    private OntologyHistoryService history;
    private MainGraphRevisionService revisions;
    private GeneralClassAxiomService gcas;
    private OntologyMutationService mutations;
    private DraftTrackingService draftTracking;
    private OntologyMetadataService metadata;
    private ChangeRollbackService rollback;

    private final List<DraftChange> drafts = new ArrayList<>();
    private final List<HistoryChange> entries = new ArrayList<>();
    private final Map<String, RollbackAudit> audits = new HashMap<>();
    private final Map<String, String> draftPrefixes = new LinkedHashMap<>();
    private int ids;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection c = repository.getConnection()) {
            IRI g = VF.createIRI(GRAPH);
            c.add(VF.createIRI(ONTOLOGY), VF.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                    VF.createIRI("http://www.w3.org/2002/07/owl#Ontology"), g);
            c.add(VF.createIRI(ONTOLOGY), VF.createIRI("http://www.w3.org/2002/07/owl#versionIRI"),
                    VF.createIRI(ONTOLOGY + "/v1"), g);
            c.add(VF.createIRI(ONTOLOGY), VF.createIRI(TITLE), VF.createLiteral("Old title"), g);
            c.add(VF.createIRI(ONTOLOGY), VF.createIRI("http://www.w3.org/2002/07/owl#imports"),
                    VF.createIRI("http://ex.org/kept.owl"), g);
            c.add(VF.createIRI(ONTOLOGY), VF.createIRI(LEGACY_GCI), VF.createLiteral("A and B SubClassOf C"), g);
        }

        datasets = mock(SparqlDatasetService.class);
        when(datasets.graphTarget(P)).thenReturn(new SparqlDatasetService.ProjectGraphTarget(repository, "urn:main"));
        when(datasets.getDraftGraphUri(P, USER)).thenReturn(GRAPH);
        when(datasets.getGraphUri(P)).thenReturn("urn:main");
        doAnswer(inv -> {
            update(inv.getArgument(2));
            return null;
        }).when(datasets).execDraftUpdateCopyOnSwitch(eq(P), eq(USER), anyString());
        when(datasets.execSelect(eq(P), anyString())).thenAnswer(inv -> select(inv.getArgument(1)));
        when(datasets.readProjectPrefixes(P, true, USER)).thenAnswer(inv -> new LinkedHashMap<>(draftPrefixes));
        doAnswer(inv -> {
            String oldPrefix = inv.getArgument(4);
            if (oldPrefix != null && !oldPrefix.isBlank()) {
                draftPrefixes.remove(oldPrefix);
            }
            draftPrefixes.put(inv.getArgument(2), inv.getArgument(3));
            return null;
        }).when(datasets).updateDraftPrefix(eq(P), eq(USER), anyString(), anyString(), any());
        doAnswer(inv -> draftPrefixes.remove((String) inv.getArgument(2))).when(datasets).deleteDraftPrefix(eq(P), eq(USER), anyString());

        history = mock(OntologyHistoryService.class);
        doAnswer(inv -> {
            remember(inv.getArguments());
            return null;
        }).when(history).recordEdit(anyString(), any(), any(), anyString(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
        revisions = mock(MainGraphRevisionService.class);
        DraftCopyService copies = mock(DraftCopyService.class);
        when(copies.isReady(anyString(), any())).thenReturn(true);

        StorageManager storage = mock(StorageManager.class);
        Executor direct = Runnable::run;
        mutations = new OntologyMutationService(datasets, mock(OntologyIndexService.class), mock(ProjectMetadataService.class),
                mock(GraphGeneratingService.class), mock(TopLevelClassCacheService.class), storage, direct);
        RawUpdateHistory rawHistory = new RawUpdateHistory(datasets);
        ReflectionTestUtils.setField(mutations, "rawUpdateHistory", rawHistory);
        ReflectionTestUtils.setField(mutations, "draftCopyService", copies);
        ReflectionTestUtils.setField(mutations, "mainGraphRevisionService", revisions);

        DraftChangeRepository repo = mock(DraftChangeRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> {
            List<DraftChange> saved = new ArrayList<>();
            for (DraftChange draft : (Iterable<DraftChange>) inv.getArgument(0)) {
                if (draft.getId() == null) {
                    draft.setId("d" + (++ids));
                }
                drafts.removeIf(existing -> existing.getId().equals(draft.getId()));
                drafts.add(draft);
                saved.add(draft);
            }
            return saved;
        });
        when(repo.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(anyString(), anyString()))
                .thenAnswer(inv -> drafts.stream().filter(d -> d.getProjectId().equals(inv.getArgument(0))
                        && d.getUserId().equals(inv.getArgument(1)) && !d.isApplied()).toList());
        doAnswer(inv -> drafts.removeIf(d -> d.getId().equals(inv.getArgument(0)))).when(repo).deleteById(anyString());
        draftTracking = new DraftTrackingService(repo, mutations, datasets, mock(OntologyIndexService.class),
                mock(ProjectMetadataService.class), mock(DraftChangeHistoryRecorder.class), direct,
                mock(CollaborativeEditService.class), mock(DraftPublishService.class), revisions,
                mock(DraftPublishMergeService.class), copies);
        ReflectionTestUtils.setField(rawHistory, "historyService", history);
        ReflectionTestUtils.setField(rawHistory, "draftTrackingService", draftTracking);

        gcas = mock(GeneralClassAxiomService.class);
        metadata = new OntologyMetadataService(datasets, mock(ProjectMetadataService.class), mutations, gcas,
                mock(ProjectImportService.class), mock(ManchesterExpressionService.class), storage);
        ReflectionTestUtils.setField(metadata, "historyService", history);
        ReflectionTestUtils.setField(metadata, "draftTracking", draftTracking);

        rollback = rollbackService();
    }

    @AfterEach
    void tearDown() {
        SparqlQueryContext.clear();
        repository.shutDown();
    }

    private void update(String sparql) {
        try (RepositoryConnection c = repository.getConnection()) {
            Update update = c.prepareUpdate(sparql);
            SimpleDataset dataset = new SimpleDataset();
            IRI g = VF.createIRI(GRAPH);
            dataset.addDefaultGraph(g);
            dataset.addDefaultRemoveGraph(g);
            dataset.setDefaultInsertGraph(g);
            update.setDataset(dataset);
            update.execute();
        }
    }

    private MutableTupleQueryResult select(String sparql) {
        try (RepositoryConnection c = repository.getConnection()) {
            TupleQuery query = c.prepareTupleQuery(sparql);
            SimpleDataset dataset = new SimpleDataset();
            dataset.addDefaultGraph(VF.createIRI(SparqlQueryContext.wantsDraft() ? GRAPH : "urn:main"));
            query.setDataset(dataset);
            return new MutableTupleQueryResult(query.evaluate());
        }
    }

    private void remember(Object[] args) {
        String editId = "edit-" + (entries.size() + 1);
        HistoryChange entry = new HistoryChange.Builder((String) args[0], editId, (String) args[1], (String) args[2])
                .operationType((String) args[3]).entityIRI((String) args[4]).entityLabel((String) args[5])
                .oldValue((String) args[6]).newValue((String) args[7]).description((String) args[8])
                .subChanges(new ArrayList<>()).build();
        entry.setId("h" + (entries.size() + 1));
        entry.setDraft((Boolean) args[11]);
        ChangeOrigin origin = (ChangeOrigin) args[12];
        entry.setChangeSetId(origin.changeSetId());
        if (origin.exact() != null) {
            Map<String, Object> stamped = new HashMap<>();
            origin.exact().stamp(stamped, editId);
            entry.setExactDeleted((String) stamped.get("exactDeleted"));
            entry.setExactInserted((String) stamped.get("exactInserted"));
            entry.setCoveredBy((String) stamped.get("coveredBy"));
            if (stamped.get("coveredBy") != null) {
                entries.stream().filter(e -> e.getEditId().equals(stamped.get("coveredBy")))
                        .forEach(carrier -> carrier.setExactCovers(carrier.getExactCovers() + 1));
            }
        }
        entries.add(entry);
    }

    private ChangeRollbackService rollbackService() {
        RollbackAuditRepository auditRepository = mock(RollbackAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            if (audit.getId() == null) {
                audit.setId("audit-" + (audits.size() + 1));
            }
            audits.put(audit.getId(), audit);
            return audit;
        });
        when(auditRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(audits.get(inv.getArgument(0))));
        HistorySyncService historySync = mock(HistorySyncService.class);
        when(historySync.getChangeSet(eq(P), anyString())).thenAnswer(inv -> entries.stream()
                .filter(e -> inv.getArgument(1).equals(e.getChangeSetId())).toList());
        when(historySync.getHistoryChange(anyString())).thenAnswer(inv -> entries.stream()
                .filter(e -> e.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        doAnswer(inv -> {
            entries.stream().filter(e -> e.getId().equals(inv.getArgument(0))).forEach(e -> {
                e.setReverted(inv.getArgument(1));
                e.setRevertedAuditId(inv.getArgument(2));
            });
            return null;
        }).when(historySync).setEntryReverted(anyString(), anyBoolean(), any());
        RollbackGraphFacts facts = mock(RollbackGraphFacts.class);
        when(facts.entityExists(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        when(facts.deltaHolds(anyString(), any(), anyBoolean())).thenReturn(true);
        ChangeRollbackService service = new ChangeRollbackService(historySync, new RollbackMutationPlanner(), facts,
                mock(OntologyMutationService.class), auditRepository, history, new ProjectWriteLockRegistry(), draftTracking,
                null);
        RollbackActionRunner actions = mock(RollbackActionRunner.class);
        when(actions.holds(anyString(), any(), anyBoolean(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "actionRunner", actions);
        return service;
    }

    private List<DraftChange> pending() {
        return draftTracking.getUnappliedDraftsForUser(P, USER);
    }

    private DraftChange only(String type) {
        List<DraftChange> matching = pending().stream().filter(d -> type.equals(d.getOperationType())).toList();
        assertEquals(1, matching.size(), "records: " + pending().stream().map(DraftChange::getOperationType).toList());
        return matching.get(0);
    }

    private static List<?> changeSets(DraftChange draft) {
        return (List<?>) draft.getOperationData().get(DraftTrackingService.CHANGE_SETS);
    }

    private String lastChangeSet() {
        return entries.get(entries.size() - 1).getChangeSetId();
    }

    private void undoThenRedo(String changeSetId, String type, int countBefore) {
        Result undone = rollback.undoChangeSet(P, changeSetId, ACTOR, false);
        assertTrue(undone.ok(), undone.message() + " " + undone.skipped());
        assertEquals(countBefore - 1, pending().size());
        assertTrue(pending().stream().noneMatch(d -> type.equals(d.getOperationType()) && changeSets(d).contains(changeSetId)));

        Result redone = rollback.redoUndo(P, undone.auditId(), ACTOR, false);
        assertTrue(redone.ok(), redone.message() + " " + redone.skipped());
        assertEquals(countBefore, pending().size());
        assertTrue(pending().stream().anyMatch(d -> type.equals(d.getOperationType()) && changeSets(d).contains(changeSetId)));
    }

    @Test
    void aDraftOntologyIriChangeIsOneReadableRecordThatUndoRemovesAndRedoRestores() {
        metadata.updateOntologyIRIs(P, "http://ex.org/renamed", ONTOLOGY + "/v2", true, USER, NAME);

        assertEquals(1, pending().size());
        DraftChange record = only(DraftRecords.ONTOLOGY_IRI);
        Map<String, Object> data = record.getOperationData();
        assertEquals("Ontology IRI", data.get("label"));
        assertEquals(ONTOLOGY, data.get("oldValue"));
        assertEquals("http://ex.org/renamed", data.get("value"));
        assertEquals(ONTOLOGY + "/v1", data.get("oldVersionIri"));
        assertEquals(ONTOLOGY + "/v2", data.get("versionIri"));
        assertEquals(List.of(lastChangeSet()), changeSets(record));
        Set<String> historyIris = new LinkedHashSet<>();
        entries.forEach(e -> historyIris.add(e.getEntityIRI()));
        assertTrue(historyIris.contains("http://ex.org/renamed"), historyIris.toString());

        undoThenRedo(lastChangeSet(), DraftRecords.ONTOLOGY_IRI, 1);
        assertEquals(ONTOLOGY, only(DraftRecords.ONTOLOGY_IRI).getOperationData().get("oldValue"));
    }

    @Test
    void anUnchangedOntologyIriLeavesNothingToReview() {
        metadata.updateOntologyIRIs(P, ONTOLOGY, ONTOLOGY + "/v1", true, USER, NAME);

        assertTrue(pending().isEmpty(), pending().toString());
    }

    @Test
    void ontologyAnnotationsAreRecordedOncePerEditWithTheirValues() {
        metadata.addOntologyAnnotation(P, "http://purl.org/dc/terms/creator", "Ada", "en", null, true, USER, NAME);
        String added = lastChangeSet();
        metadata.updateOntologyAnnotation(P, TITLE, "Old title", "New title", null, null, TITLE, true, USER, NAME);
        String edited = lastChangeSet();
        metadata.deleteOntologyAnnotation(P, "http://purl.org/dc/terms/creator", "Ada", "en", true, USER, NAME);

        assertEquals(3, pending().size());
        DraftChange add = only(DraftRecords.ADD_ANNOTATION);
        assertEquals("http://purl.org/dc/terms/creator", add.getOperationData().get("property"));
        assertEquals("Ada", add.getOperationData().get("value"));
        assertEquals("en", add.getOperationData().get("language"));
        assertEquals(ONTOLOGY, add.getOperationData().get("iri"));
        assertEquals(List.of(added), changeSets(add));
        DraftChange edit = only(DraftRecords.UPDATE_ANNOTATION);
        assertEquals("Old title", edit.getOperationData().get("oldValue"));
        assertEquals("New title", edit.getOperationData().get("value"));
        assertEquals(List.of(edited), changeSets(edit));
        assertEquals("Ada", only(DraftRecords.DELETE_ANNOTATION).getOperationData().get("value"));

        undoThenRedo(edited, DraftRecords.UPDATE_ANNOTATION, 3);
        assertEquals(1, pending().stream().filter(d -> DraftRecords.ADD_ANNOTATION.equals(d.getOperationType())).count());
    }

    @Test
    void importsAreRecordedWithTheImportedIri() {
        metadata.addOntologyImport(P, "http://ex.org/new.owl", true, USER, NAME);
        String added = lastChangeSet();
        metadata.deleteOntologyImport(P, "http://ex.org/kept.owl", true, USER, NAME);

        assertEquals(2, pending().size());
        assertEquals("http://ex.org/new.owl", only(DraftRecords.ADD_IMPORT).getOperationData().get("value"));
        assertEquals("http://ex.org/kept.owl", only(DraftRecords.DELETE_IMPORT).getOperationData().get("value"));

        undoThenRedo(added, DraftRecords.ADD_IMPORT, 2);
    }

    @Test
    void importedContentLoadedWithTheImportStaysPartOfThatOneRecord() {
        Object resolution = metadata.addOntologyImport(P, "http://ex.org/new.owl", true, USER, NAME, () -> {
            mutations.applyRawUpdateWithHistory(P, "INSERT DATA { <http://ex.org/Imported> "
                    + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> }",
                    true, USER, NAME);
            return "loaded";
        });

        assertEquals("loaded", resolution);
        assertEquals(1, pending().size());
        assertEquals(2, changeSets(only(DraftRecords.ADD_IMPORT)).size());
    }

    @Test
    void prefixesAreRecordedWithTheirNamespaces() {
        metadata.updatePrefix(P, "ex", "http://ex.org/a#", null, true, USER, USER, NAME);
        String added = lastChangeSet();
        metadata.updatePrefix(P, "ex", "http://ex.org/b#", "ex", true, USER, USER, NAME);
        metadata.deletePrefix(P, "ex", true, USER, USER, NAME);

        assertEquals(3, pending().size());
        assertEquals("http://ex.org/a#", only(DraftRecords.ADD_PREFIX).getOperationData().get("value"));
        DraftChange changed = only(DraftRecords.UPDATE_PREFIX);
        assertEquals("http://ex.org/a#", changed.getOperationData().get("oldValue"));
        assertEquals("http://ex.org/b#", changed.getOperationData().get("value"));
        assertEquals("ex", changed.getOperationData().get("prefix"));
        assertEquals("http://ex.org/b#", only(DraftRecords.DELETE_PREFIX).getOperationData().get("oldValue"));

        undoThenRedo(added, DraftRecords.ADD_PREFIX, 3);
    }

    @Test
    void generalClassAxiomsAreRecordedWithTheirText() throws Exception {
        doAnswer(inv -> {
            mutations.applyRawUpdateWithHistory(P, "INSERT DATA { _:g <http://www.w3.org/2002/07/owl#intersectionOf> "
                    + "( <http://ex.org/" + ((String) inv.getArgument(1)).replace(" and ", "> <http://ex.org/") + "> ) . "
                    + "_:g <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://ex.org/" + inv.getArgument(2) + "> }",
                    true, USER, NAME);
            return null;
        }).when(gcas).addGeneralClassAxiom(eq(P), anyString(), anyString(), eq(true), eq(USER), eq(NAME));

        metadata.addGCI(P, "D and E", "F", true, USER, NAME);
        String added = lastChangeSet();
        metadata.updateGCI(P, "A and B SubClassOf C", "A and B", "G", true, USER, NAME);

        assertEquals(2, pending().size());
        assertEquals("D and E SubClassOf F", only(DraftRecords.ADD_GCI).getOperationData().get("value"));
        DraftChange edit = only(DraftRecords.UPDATE_GCI);
        assertEquals("A and B SubClassOf C", edit.getOperationData().get("oldValue"));
        assertEquals("A and B SubClassOf G", edit.getOperationData().get("value"));
        assertEquals(2, changeSets(edit).size());

        undoThenRedo(added, DraftRecords.ADD_GCI, 2);
    }

    @Test
    void deletingAGeneralClassAxiomIsRecorded() {
        metadata.deleteGCI(P, "A and B SubClassOf C", true, USER, NAME);

        assertEquals("A and B SubClassOf C", only(DraftRecords.DELETE_GCI).getOperationData().get("value"));
        assertEquals(1, pending().size());
    }

    @Test
    void publishingMarksTheRecordsApplied() {
        metadata.updateOntologyIRIs(P, "http://ex.org/renamed", null, true, USER, NAME);
        metadata.addOntologyImport(P, "http://ex.org/new.owl", true, USER, NAME);
        List<DraftChange> recorded = List.copyOf(pending());

        DraftTrackingService.ApplyDraftsResult published = draftTracking.applyDrafts(P, USER, false);

        assertTrue(published.isSuccess(), published.getMessage());
        assertEquals(2, published.getAppliedCount());
        assertTrue(recorded.stream().allMatch(DraftChange::isApplied));
        assertTrue(pending().isEmpty());
    }

    @Test
    void aPublicOntologyIriChangeBumpsTheSharedRevisionAndRecordsNoDraft() {
        metadata.updateOntologyIRIs(P, "http://ex.org/public", null, false, USER, NAME);

        verify(revisions).incrementRevision(P);
        verify(datasets).execUpdate(eq(P), anyString());
        verify(datasets, never()).execDraftUpdateCopyOnSwitch(anyString(), anyString(), anyString());
        assertTrue(drafts.isEmpty());
    }

    @Test
    void aDraftEditNeverBumpsTheSharedRevision() {
        metadata.updateOntologyIRIs(P, "http://ex.org/renamed", null, true, USER, NAME);

        verify(revisions, never()).incrementRevision(anyString());
        assertNull(SparqlQueryContext.getUserId());
        assertFalse(DraftRecordScope.active());
    }
}
