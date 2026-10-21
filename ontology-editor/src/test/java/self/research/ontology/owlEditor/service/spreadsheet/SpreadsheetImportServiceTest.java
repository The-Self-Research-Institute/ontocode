package self.research.ontology.owlEditor.service.spreadsheet;

import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpreadsheetImportServiceTest {

    private static final String NS = "http://example.org/sensor#";
    private static final String PROJECT = "p1";
    private static final String RULE = """
        Individual: @A*
          Types: SensorReading
          Facts: hasMeasurementValue @B*(xsd:decimal), hasLocation @C*
          Annotations: rdfs:label @D*
        """;

    private Repository repo;
    private SparqlDatasetService dataset;
    private OntologyMutationService mutations;
    private DraftTrackingService drafts;
    private OntologyHistoryService history;
    private ProjectMetadataService metadata;
    private final Map<String, SpreadsheetImportRecord> saved = new HashMap<>();
    private SpreadsheetImportService service;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        update("""
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            PREFIX : <%s>
            INSERT DATA {
              <http://example.org/sensor> a owl:Ontology .
              :SensorReading a owl:Class .
              :Location a owl:Class .
              :hasLocation a owl:ObjectProperty .
              :hasMeasurementValue a owl:DatatypeProperty ; rdfs:range xsd:decimal .
              :LabA a owl:NamedIndividual, :Location .
              :LabB a owl:NamedIndividual, :Location .
              :S001 rdfs:label "Sensor reading 1" .
            }""".formatted(NS));

        dataset = mock(SparqlDatasetService.class);
        when(dataset.getPrefixes(PROJECT)).thenReturn(Map.of(":", NS));
        when(dataset.execSelect(eq(PROJECT), anyString())).thenAnswer(inv -> select(inv.getArgument(1)));

        mutations = mock(OntologyMutationService.class);
        doAnswer(inv -> {
            insertOps(inv.getArgument(1));
            return null;
        }).when(mutations).apply(eq(PROJECT), any());
        doAnswer(inv -> {
            insertOps(inv.getArgument(2));
            return null;
        }).when(mutations).applyDraft(eq(PROJECT), anyString(), any());
        doAnswer(inv -> {
            update(inv.getArgument(1));
            return null;
        }).when(mutations).applyRawUpdate(eq(PROJECT), anyString(), anyBoolean(), any());

        drafts = mock(DraftTrackingService.class);
        history = mock(OntologyHistoryService.class);
        metadata = mock(ProjectMetadataService.class);
        when(metadata.isRequireDraftForMembers(PROJECT)).thenReturn(false);

        SpreadsheetImportRecordRepository records = mock(SpreadsheetImportRecordRepository.class);
        when(records.save(any())).thenAnswer(inv -> {
            SpreadsheetImportRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID().toString());
            }
            saved.put(r.getId(), r);
            return r;
        });
        when(records.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<String>getArgument(0))));
        doAnswer(inv -> saved.remove(inv.<String>getArgument(0))).when(records).deleteById(anyString());

        service = new SpreadsheetImportService(new SpreadsheetParserService(), dataset, mutations, drafts, history, metadata, records);
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    @Test
    void commitIsOneEntryAndRollbackRemovesOnlyWhatTheImportAdded() {
        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");

        assertFalse(result.draft());
        assertEquals(10, result.axiomCount(), "2 rows x (declaration, type, value, location, label)");
        assertEquals(1, result.alreadyPresent(), "S001's label was there before the import");
        assertTrue(ask("<" + NS + "S002> <" + NS + "hasMeasurementValue> \"27.4\"^^<http://www.w3.org/2001/XMLSchema#decimal>"));
        verify(mutations, times(1)).apply(eq(PROJECT), any());
        verify(history, times(1)).recordEdit(eq(PROJECT), eq("kay"), eq("Kay"), eq(SpreadsheetImportService.OPERATION_TYPE),
            eq(SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId()), eq("readings.csv"),
            isNull(), isNull(), anyString(), isNull(), isNull(), eq(false));

        update("INSERT DATA { <" + NS + "S001> <http://www.w3.org/2000/01/rdf-schema#comment> \"checked by hand\" }");

        SpreadsheetImportService.RollbackResult rb =
            service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");

        assertEquals(result.triplesAdded(), rb.triplesRemoved());
        assertFalse(ask("<" + NS + "S002> ?p ?o"), "imported individuals are gone");
        assertFalse(ask("<" + NS + "S001> a <" + NS + "SensorReading>"));
        assertTrue(ask("<" + NS + "S001> <http://www.w3.org/2000/01/rdf-schema#label> \"Sensor reading 1\""),
            "data that existed before the import is kept");
        assertTrue(ask("<" + NS + "S001> <http://www.w3.org/2000/01/rdf-schema#comment> \"checked by hand\""),
            "later edits are kept");
        assertTrue(ask("<" + NS + "LabB> a <" + NS + "Location>"), "referenced entities are untouched");
        assertTrue(saved.get(result.importId()).isRolledBack());

        SpreadsheetImportService.RollbackResult again =
            service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");
        assertEquals(0, again.triplesRemoved(), "a second rollback is a no-op");
    }

    @Test
    void reimportingTheSameDataIsRefusedAndRecordsNothing() {
        SpreadsheetImportService.Novelty before = service.novelty(PROJECT, service.generate(PROJECT, csv(), "readings.csv", rules()));
        assertFalse(before.nothingNew());
        assertEquals(1, before.alreadyPresent(), "S001's label was there before the import");

        service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");

        SpreadsheetImportService.Novelty after = service.novelty(PROJECT, service.generate(PROJECT, csv(), "readings.csv", rules()));
        assertTrue(after.nothingNew(), "the preview shows there is nothing new");

        SpreadsheetImportService.ImportRejectedException e = assertThrows(SpreadsheetImportService.ImportRejectedException.class,
            () -> service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay"));
        assertEquals(SpreadsheetImportService.NOTHING_NEW, e.getMessage());
        verify(mutations, times(1)).apply(eq(PROJECT), any());
        verify(history, times(1)).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyString(), any(), any(), anyBoolean());
        assertEquals(1, saved.size(), "no receipt for the refused import");
    }

    @Test
    void whenTheImportCannotBeRecordedItsDataIsTakenBack() {
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down")).when(history).recordEdit(anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(), any(), anyBoolean());

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay"));

        assertTrue(e.getMessage().contains("taken back"));
        assertFalse(ask("<" + NS + "S002> ?p ?o"), "the imported data was removed again");
        assertTrue(ask("<" + NS + "S001> <http://www.w3.org/2000/01/rdf-schema#label> \"Sensor reading 1\""),
            "data that was there before is kept");
        assertTrue(saved.isEmpty(), "no receipt is left without a history entry");
    }

    @Test
    void refusesToCommitWhenThePreviewHasErrors() {
        byte[] bad = "Id,Value,Location,Label\nS001,abc,LabA,x\n".getBytes(StandardCharsets.UTF_8);
        SpreadsheetImportService.ImportRejectedException e = assertThrows(SpreadsheetImportService.ImportRejectedException.class,
            () -> service.commit(PROJECT, new ByteArrayInputStream(bad), "bad.csv", rules(), false, "kay", "Kay"));
        assertTrue(e.result().hasErrors());
        verify(mutations, never()).apply(anyString(), any());
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            any(), any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void draftImportsGoToTheDraftAndAreTrackedForTheReview() {
        SpreadsheetImportDraftSupport draftSupport = mock(SpreadsheetImportDraftSupport.class);
        service.setDraftSupport(draftSupport);

        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), true, "kay", "Kay");
        assertTrue(result.draft());
        verify(mutations).applyDraft(eq(PROJECT), eq("kay"), any());
        org.mockito.ArgumentCaptor<String> session = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(drafts).recordDrafts(eq(PROJECT), eq("kay"), eq("Kay"), any(), session.capture());
        assertTrue(SpreadsheetImportDraftSupport.isImportSession(session.getValue()));
        assertEquals(session.getValue(), saved.get(result.importId()).getDraftSessionId(), "receipt links to its draft changes");
        verify(mutations, never()).apply(anyString(), any());

        service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");
        verify(mutations).applyRawUpdate(eq(PROJECT), anyString(), eq(true), eq("kay"));
        verify(draftSupport).discardDraftChanges(PROJECT, "kay", session.getValue());
    }

    @Test
    void liveImportsHaveNoDraftSessionAndNeverTouchDraftChanges() {
        SpreadsheetImportDraftSupport draftSupport = mock(SpreadsheetImportDraftSupport.class);
        service.setDraftSupport(draftSupport);

        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");
        assertEquals(null, saved.get(result.importId()).getDraftSessionId());

        service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");
        verify(draftSupport, never()).discardDraftChanges(anyString(), anyString(), any());
    }

    @Test
    void membersAreRedirectedToDraftWhenTheProjectRequiresIt() {
        when(metadata.isRequireDraftForMembers(PROJECT)).thenReturn(true);
        when(metadata.getOwnerEmail(PROJECT)).thenReturn(Optional.of("owner@example.org"));
        assertTrue(service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay").draft());
    }

    @Test
    void rollbackOfAnUnknownImportFails() {
        assertThrows(IllegalArgumentException.class,
            () -> service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + "missing", "Kay"));
        assertThrows(IllegalArgumentException.class, () -> service.rollback(PROJECT, "http://example.org/x", "Kay"));
    }

    @Test
    void aPublicImportRefreshesTheSummaryInTheBackground() throws Exception {
        self.research.ontology.owlEditor.service.OntologyIndexService index =
            mock(self.research.ontology.owlEditor.service.OntologyIndexService.class);
        Map<String, Object> fresh = Map.of("individualCount", 2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(index.computeMetadata(PROJECT)).thenAnswer(inv -> {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return fresh;
        });
        service.setIndexService(index);

        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");

        verify(metadata, never()).writeMeta(anyString(), any());
        release.countDown();
        verify(metadata, org.mockito.Mockito.timeout(5000).times(1)).writeMeta(PROJECT, fresh);

        service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");
        verify(metadata, org.mockito.Mockito.timeout(5000).times(2)).writeMeta(PROJECT, fresh);
    }

    @Test
    void theScreenIsToldWhenThePublicSummaryIsReady() {
        self.research.ontology.owlEditor.service.OntologyIndexService index =
            mock(self.research.ontology.owlEditor.service.OntologyIndexService.class);
        when(index.computeMetadata(PROJECT)).thenReturn(Map.of("individualCount", 2));
        service.setIndexService(index);
        self.research.ontology.owlEditor.service.ProjectImportService notifier =
            mock(self.research.ontology.owlEditor.service.ProjectImportService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<self.research.ontology.owlEditor.service.ProjectImportService> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        service.setProjectImports(provider);

        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");
        verify(notifier, org.mockito.Mockito.timeout(5000).times(1)).notifyMetadataReady(PROJECT);

        service.rollback(PROJECT, SpreadsheetImportService.ENTITY_IRI_PREFIX + result.importId(), "Kay");
        verify(notifier, org.mockito.Mockito.timeout(5000).times(2)).notifyMetadataReady(PROJECT);

        service.commit(PROJECT, csv(), "readings.csv", rules(), true, "kay", "Kay");
        verify(notifier, org.mockito.Mockito.after(500).times(2)).notifyMetadataReady(PROJECT);
    }

    @Test
    void aDraftImportDoesNotTouchTheSharedSummary() {
        self.research.ontology.owlEditor.service.OntologyIndexService index =
            mock(self.research.ontology.owlEditor.service.OntologyIndexService.class);
        service.setIndexService(index);

        service.commit(PROJECT, csv(), "readings.csv", rules(), true, "kay", "Kay");

        verify(index, never()).computeMetadata(anyString());
        verify(metadata, never()).writeMeta(anyString(), any());
    }

    @Test
    void aFailedSummaryRefreshDoesNotFailTheImport() {
        self.research.ontology.owlEditor.service.OntologyIndexService index =
            mock(self.research.ontology.owlEditor.service.OntologyIndexService.class);
        when(index.computeMetadata(PROJECT)).thenThrow(new RuntimeException("fuseki busy"));
        service.setIndexService(index);

        SpreadsheetImportService.CommitResult result = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay");

        assertFalse(result.draft());
        assertTrue(ask("<" + NS + "S002> ?p ?o"), "the import itself went through");
        verify(metadata, never()).writeMeta(anyString(), any());
    }

    @Test
    void aDraftImportIsShownOnlyToItsOwner() {
        String id = service.commit(PROJECT, csv(), "readings.csv", rules(), true, "kay", "Kay").importId();

        assertTrue(service.importedItems(PROJECT, id, "kay", 20).isPresent());
        assertTrue(service.importedItems(PROJECT, id, "other", 20).isEmpty());
        assertTrue(service.importedItems(PROJECT, id, null, 20).isEmpty(), "an unknown caller sees no drafts");
        assertTrue(service.importedItemsPage(PROJECT, id, null, "individuals", 0, 20).isEmpty());
        assertTrue(service.importedItemsPage(PROJECT, id, "kay", "individuals", 0, 20).isPresent());
    }

    @Test
    void loadMoreReusesTheParsedImportInsteadOfParsingAgain() {
        String id = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay").importId();
        SpreadsheetImportRecord record = org.mockito.Mockito.spy(saved.get(id));
        saved.put(id, record);

        SpreadsheetImportService.ImportedItems first = service.importedItems(PROJECT, id, "kay", 1).orElseThrow();
        ImportItemsSummary.Group second = service.importedItemsPage(PROJECT, id, "kay", "individuals", 1, 1).orElseThrow();
        service.importedItemsPage(PROJECT, id, "kay", "individuals", 2, 1);

        verify(record, times(1)).getAddedTriples();
        assertEquals(2, first.groups().get("individuals").total());
        assertEquals(1, second.items().size());
        assertEquals(1, second.offset());
        assertFalse(first.groups().get("individuals").items().get(0).iri()
            .equals(second.items().get(0).iri()), "the next page continues where the first stopped");
    }

    @Test
    void aPublicImportIsShownToEveryone() {
        String id = service.commit(PROJECT, csv(), "readings.csv", rules(), false, "kay", "Kay").importId();

        assertTrue(service.importedItems(PROJECT, id, "other", 20).isPresent());
        assertTrue(service.importedItems(PROJECT, id, null, 20).isPresent());
    }

    private static ByteArrayInputStream csv() {
        return new ByteArrayInputStream("""
            Id,Value,Location,Label
            S001,23.7,LabB,Sensor reading 1
            S002,27.4,LabA,Sensor reading 2
            """.getBytes(StandardCharsets.UTF_8));
    }

    private static List<RuleSpec> rules() {
        return List.of(new RuleSpec("readings", "A", "A", "2", "+", RULE, null, true));
    }

    private void insertOps(List<MutationOp> ops) {
        StringBuilder sb = new StringBuilder("INSERT DATA {\n");
        ops.forEach(op -> ImportTriples.of(op).forEach(t -> sb.append(t).append(" .\n")));
        update(sb.append("}").toString());
    }

    private void update(String sparql) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(sparql).execute();
        }
    }

    private TupleQueryResult select(String sparql) {
        try (RepositoryConnection c = repo.getConnection()) {
            return QueryResults.distinctResults(new org.eclipse.rdf4j.query.impl.MutableTupleQueryResult(
                c.prepareTupleQuery(sparql).evaluate()));
        }
    }

    private boolean ask(String pattern) {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.prepareBooleanQuery("ASK { " + pattern + " }").evaluate();
        }
    }
}
