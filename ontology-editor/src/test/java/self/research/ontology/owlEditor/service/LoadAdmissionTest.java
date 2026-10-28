package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LoadAdmissionTest {

    private static final String DRAFT_RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#">
                <owl:Ontology rdf:about="http://example.org/draft"/>
                <owl:Class rdf:about="http://example.org/draft#A"/>
            </rdf:RDF>
            """;

    private FakeMemorySignals signals;
    private ReservationLedger ledger;
    private HeavyWorkAdmission admission;
    private SparqlDatasetService dataset;

    @BeforeEach
    void setUp() {
        signals = new FakeMemorySignals();
        ledger = signals.ledger();
        admission = new HeavyWorkAdmission(ledger, new Estimates());
        dataset = spy(new SparqlDatasetService());
        DraftSession session = new DraftSession();
        session.setCopyStatus(DraftCopyStatus.READY);
        DraftSessionRepository sessions = mock(DraftSessionRepository.class);
        when(sessions.findByProjectIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(session));
        ReflectionTestUtils.setField(dataset, "draftSessionRepository", sessions);
        ReflectionTestUtils.setField(dataset, "draftMaxTriples", 500_000L);
        ReflectionTestUtils.setField(dataset, "heavyWork", admission);
        doReturn(2L).when(dataset).countDraftTriples("proj-1", "user-1");
    }

    @Test
    void draftLoadIsAdmittedForTheDurationOfTheLoadAndReleasedAfterwards() throws Exception {
        AtomicLong during = new AtomicLong(-1);
        doAnswer(inv -> {
            during.set(ledger.capacityReport().kinds().stream()
                    .filter(k -> k.kind().equals(WorkKind.DL_QUERY.name())).findFirst().get().reservedBytes());
            return DRAFT_RDF;
        }).when(dataset).exportDraftGraphContent("proj-1", "user-1", RDFFormat.RDFXML);

        assertNotNull(dataset.loadDraftAsOntology("proj-1", "user-1", 1L, WorkKind.DL_QUERY));

        assertTrue(during.get() >= HeavyWorkAdmission.MIN_ESTIMATE_BYTES, "held during load: " + during);
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void draftLoadReleasesWhenTheExportFails() {
        doThrow(new IllegalStateException("export failed")).when(dataset)
                .exportDraftGraphContent("proj-1", "user-1", RDFFormat.RDFXML);
        assertThrows(IllegalStateException.class, () -> dataset.loadDraftAsOntology("proj-1", "user-1", 1L));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void draftLoadIsRefusedWhenTheServerIsUnderPressure() {
        signals.afterGc = 900 * FakeMemorySignals.MIB;
        BusyException busy = assertThrows(BusyException.class,
                () -> dataset.loadDraftAsOntology("proj-1", "user-1", 1L, WorkKind.INFERENCE_BUILD));
        assertEquals(RefusalReason.HEAP_PRESSURE, busy.reason());
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void dlQueryOnTheDraftPathGoesThroughAdmissionAndReleasesOnFailure() throws Exception {
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        when(datasets.getDatasetSize(anyString())).thenReturn(10L);
        doAnswer(inv -> {
            assertTrue(ledger.reservedBytes() > 0, "held while the draft is exported");
            return null;
        }).when(datasets).exportDatasetToStream(anyString(), any(RDFFormat.class), any(OutputStream.class));
        DLQueryService service = new DLQueryService(null, datasets, 5_000_000L, mock(ProjectImportService.class));
        ReflectionTestUtils.setField(service, "heavyWork", admission);
        assertThrows(Exception.class, () -> service.executeQuery("proj-1", "A", null, "user-1"));
        assertEquals(0, ledger.reservedBytes());

        signals.afterGc = 900 * FakeMemorySignals.MIB;
        BusyException busy = assertThrows(BusyException.class,
                () -> service.executeQuery("proj-1", "A", null, "user-1"));
        assertEquals(RefusalReason.HEAP_PRESSURE, busy.reason());
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void inferenceAdmissionUsesTheInferenceBuildKind() {
        try (HeavyWorkAdmission.Hold hold = new InferenceAdmission(admission).admit("user-1", 100_000)) {
            Map<String, Long> byKind = new java.util.HashMap<>();
            ledger.capacityReport().kinds().forEach(k -> byKind.put(k.kind(), k.reservedBytes()));
            assertEquals(100_000L * Estimates.DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_LOAD, byKind.get("INFERENCE_BUILD"));
            assertEquals(hold.bytes(), byKind.get("INFERENCE_BUILD"));
        }
        assertEquals(0, ledger.reservedBytes());
    }
}
