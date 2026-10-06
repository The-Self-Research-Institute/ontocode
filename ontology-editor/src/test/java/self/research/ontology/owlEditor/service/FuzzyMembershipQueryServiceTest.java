package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FuzzyMembershipQueryServiceTest {

    @Mock
    private SparqlDatasetService datasetService;

    private FuzzyMembershipQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FuzzyMembershipQueryService(datasetService);
    }

    @Test
    void unsafeIndividualIriIsRejectedWithoutQueryingTheDataset() {
        List<Map<String, Object>> result = service.forIndividual("proj-1",
                "http://example.org/x> . } SELECT * WHERE { ?s ?p ?o");

        assertTrue(result.isEmpty());
        verify(datasetService, never()).execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void returnsMembershipsForASafeIndividualIri() {
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("class", "degree"),
                        List.of(Map.of("class", "http://example.org/Diabetic", "degree", "0.85")), false, null));

        List<Map<String, Object>> result = service.forIndividual("proj-1", "http://example.org/alice");

        assertEquals(1, result.size());
        assertEquals("http://example.org/Diabetic", result.get(0).get("classIri"));
        assertEquals(0.85, (double) result.get(0).get("degree"));
    }

    @Test
    void datasetFailureReturnsEmptyListInsteadOfThrowing() {
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("dataset unavailable"));

        List<Map<String, Object>> result = service.forIndividual("proj-1", "http://example.org/alice");

        assertTrue(result.isEmpty());
    }

    @Test
    void perCallOverheadStaysNegligibleUnderRepeatedCalls() {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(Map.of("class", "http://example.org/Class" + i, "degree", "0." + (i % 9 + 1)));
        }
        when(datasetService.execSelectCapped(anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("class", "degree"), rows, false, null));

        int iterations = 2_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<Map<String, Object>> result = service.forIndividual("proj-1", "http://example.org/alice" + i);
            assertEquals(50, result.size());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double perCallMicros = (elapsedMs * 1000.0) / iterations;
        System.out.printf("[PERF] FuzzyMembershipQueryService.forIndividual: %d calls x 50 rows in %dms (%.1f us/call, dataset round trip excluded)%n",
                iterations, elapsedMs, perCallMicros);

        assertTrue(perCallMicros < 500, "per-call overhead grew suspiciously large: " + perCallMicros + "us");
    }
}
