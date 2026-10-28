package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlResultLimits;
import self.research.ontology.owlEditor.service.SparqlRunStore;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SparqlRunPagingControllerTest {

    private static final String EXPIRED = "This result has expired. Run the query again.";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SparqlDatasetService dataset;
    private SparqlRunStore store;
    private SparqlQueryController controller;

    @BeforeEach
    void setUp() {
        dataset = mock(SparqlDatasetService.class);
        store = new SparqlRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 64L << 20, 1000, 16L << 20);
        controller = new SparqlQueryController(dataset, mock(SimpMessagingTemplate.class),
                mock(SparqlQueryRepository.class), mock(OntologyMutationService.class));
        ReflectionTestUtils.setField(controller, "runStore", store);
    }

    private JsonNode body(ResponseEntity<?> response) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(response.getBody()));
    }

    private List<BindingSet> rows(int count) {
        List<BindingSet> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MapBindingSet row = new MapBindingSet();
            row.addBinding("s", vf.createIRI("http://example.org/s" + i));
            row.addBinding("n", vf.createLiteral(String.valueOf(i % 50), XSD.INTEGER));
            rows.add(row);
        }
        return rows;
    }

    private ResponseEntity<?> paged(String project, Integer pageSize) {
        return controller.query(project, new SparqlQueryController.SparqlRequest("SELECT ?s ?n WHERE {?s ?p ?n}", null,
                null, null, pageSize), null);
    }

    private void result(List<BindingSet> rows, long seen, boolean truncated) {
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt(), any(SparqlResultLimits.class)))
                .thenReturn(new SparqlUserQueryResult.Select(List.of("s", "n"), rows, truncated, seen));
    }

    @Test
    void aRequestWithoutPageSizeKeepsTheLegacyFullResponse() throws Exception {
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenReturn(new SparqlUserQueryResult.Select(List.of("s", "n"), rows(250)));
        JsonNode json = body(controller.query("proj",
                new SparqlQueryController.SparqlRequest("SELECT * WHERE {?s ?p ?o}"), null));
        assertEquals(250, json.get("results").size());
        assertEquals(250, json.get("bindings").size());
        assertFalse(json.has("runId"));
        verify(dataset, never()).execUserQuery(anyString(), anyString(), anyInt(), any(SparqlResultLimits.class));
    }

    @Test
    void thePagedFirstResponseKeepsLegacyFieldsAndAddsPagingFields() throws Exception {
        result(rows(250), 250, false);
        ResponseEntity<?> response = paged("proj", 100);
        JsonNode json = body(response);
        assertEquals("select", json.get("type").asText());
        assertEquals(100, json.get("results").size());
        assertEquals(100, json.get("bindings").size());
        assertEquals("s", json.get("vars").get(0).asText());
        assertEquals("s", json.get("head").get("vars").get(0).asText());
        assertEquals("http://example.org/s0", json.get("results").get(0).get("s").asText());
        assertEquals("uri", json.get("bindings").get(0).get("s").get("type").asText());
        assertEquals(XSD.INTEGER.stringValue(), json.get("bindings").get(0).get("n").get("datatype").asText());
        assertTrue(json.get("runId").asText().length() > 8);
        assertEquals(250, json.get("totalRows").asLong());
        assertTrue(json.get("totalRowsExact").asBoolean());
        assertFalse(json.has("truncated"));
        assertEquals(1000, json.get("rowLimit").asInt());
        assertFalse(json.get("bytesCapped").asBoolean());
        assertEquals(100, json.get("pageSize").asInt());
        assertEquals(100, json.get("nextOffset").asInt());
        assertEquals(250, json.get("filteredRows").asInt());
        assertEquals(250, json.get("storedRows").asInt());
        assertNull(response.getHeaders().getFirst(SparqlQueryController.TRUNCATED_HEADER));
    }

    @Test
    void whenTheCapIsHitTheResponseSaysTruncatedAndCountsPastIt() throws Exception {
        result(rows(1000), 4321, true);
        ResponseEntity<?> response = paged("proj", 100);
        JsonNode json = body(response);
        assertTrue(json.get("truncated").asBoolean());
        assertEquals(4321, json.get("totalRows").asLong());
        assertFalse(json.get("totalRowsExact").asBoolean());
        assertEquals(1000, json.get("storedRows").asInt());
        assertEquals("true", response.getHeaders().getFirst(SparqlQueryController.TRUNCATED_HEADER));
    }

    @Test
    void thePageSizeIsClampedToTheMaximum() throws Exception {
        result(rows(1500), 1500, false);
        ReflectionTestUtils.setField(controller, "runStore",
                new SparqlRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 64L << 20, 5000, 16L << 20));
        JsonNode json = body(paged("proj", 5000));
        assertEquals(1000, json.get("pageSize").asInt());
        assertEquals(1000, json.get("results").size());
    }

    @Test
    void laterPagesFilterAndSortComeFromTheServer() throws Exception {
        result(rows(250), 250, false);
        String runId = body(paged("proj", 100)).get("runId").asText();

        JsonNode second = body(controller.runRows("proj", runId, 100, 100, null, null, "asc", false));
        assertEquals(100, second.get("results").size());
        assertEquals("http://example.org/s100", second.get("results").get(0).get("s").asText());
        assertEquals(200, second.get("nextOffset").asInt());

        JsonNode last = body(controller.runRows("proj", runId, 200, 100, null, null, "asc", false));
        assertEquals(50, last.get("results").size());
        assertEquals(250, last.get("nextOffset").asInt());

        JsonNode filtered = body(controller.runRows("proj", runId, 0, 100, "s249", null, "asc", false));
        assertEquals(1, filtered.get("filteredRows").asInt());
        assertEquals(250, filtered.get("storedRows").asInt());

        JsonNode sorted = body(controller.runRows("proj", runId, 0, 3, null, "n", "desc", false));
        assertEquals("49", sorted.get("results").get(0).get("n").asText());

        JsonNode exported = body(controller.runRows("proj", runId, 0, null, "s24", null, "asc", true));
        assertEquals(11, exported.get("results").size());
    }

    @Test
    void anUnknownRunIsGoneWithThePlainMessage() throws Exception {
        ResponseEntity<?> response = controller.runRows("proj", "nope", 0, 100, null, null, "asc", false);
        assertEquals(410, response.getStatusCode().value());
        assertEquals(EXPIRED, body(response).get("message").asText());
        assertTrue(body(response).get("expired").asBoolean());
    }

    @Test
    void anotherProjectCannotReadTheRun() throws Exception {
        result(rows(10), 10, false);
        String runId = body(paged("proj", 5)).get("runId").asText();
        assertEquals(200, controller.runRows("proj", runId, 0, 5, null, null, "asc", false).getStatusCode().value());
        assertEquals(410, controller.runRows("other", runId, 0, 5, null, null, "asc", false).getStatusCode().value());
    }

    @Test
    void theOldestRunIsEvictedAfterThePerOwnerLimit() throws Exception {
        result(rows(10), 10, false);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(body(paged("proj", 5)).get("runId").asText());
            Thread.sleep(2);
        }
        assertEquals(410, controller.runRows("proj", ids.get(0), 0, 5, null, null, "asc", false).getStatusCode().value());
        assertEquals(200, controller.runRows("proj", ids.get(5), 0, 5, null, null, "asc", false).getStatusCode().value());
    }

    @Test
    void aSmallResultIsNotTruncated() throws Exception {
        result(rows(3), 3, false);
        JsonNode json = body(paged("proj", 100));
        assertFalse(json.has("truncated"));
        assertEquals(3, json.get("totalRows").asLong());
        assertTrue(json.get("totalRowsExact").asBoolean());
    }
}
