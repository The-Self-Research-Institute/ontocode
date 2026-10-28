package self.research.ontology.owlEditor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryRejectedException;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SparqlQueryControllerResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SparqlDatasetService dataset;
    private SparqlQueryController controller;

    @BeforeEach
    void setUp() {
        dataset = mock(SparqlDatasetService.class);
        controller = new SparqlQueryController(dataset, mock(SimpMessagingTemplate.class),
                mock(SparqlQueryRepository.class), mock(OntologyMutationService.class));
    }

    private JsonNode body(ResponseEntity<?> response) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(response.getBody()));
    }

    private ResponseEntity<?> run(String query, String format, String accept) {
        return controller.query("proj", new SparqlQueryController.SparqlRequest(query, format), accept);
    }

    private BindingSet row(Object... kv) {
        MapBindingSet set = new MapBindingSet();
        for (int i = 0; i < kv.length; i += 2) {
            set.addBinding((String) kv[i], (org.eclipse.rdf4j.model.Value) kv[i + 1]);
        }
        return set;
    }

    @Test
    void selectKeepsLegacyShapeAndAddsBindings() throws Exception {
        List<BindingSet> rows = new ArrayList<>();
        rows.add(row("a", vf.createLiteral("plain"), "b", vf.createLiteral("5", XSD.INTEGER), "c", vf.createLiteral("hola", "es"),
                "d", vf.createLiteral(""), "e", vf.createBNode("n1"), "f", vf.createIRI("http://e/x")));
        rows.add(row("a", vf.createLiteral("second")));
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt())).thenReturn(
                new SparqlUserQueryResult.Select(List.of("a", "b", "c", "d", "e", "f"), rows));

        JsonNode json = body(run("SELECT * WHERE {?s ?p ?o}", null, null));

        assertEquals("select", json.get("type").asText());
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), mapper.convertValue(json.get("vars"), List.class));
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), mapper.convertValue(json.get("head").get("vars"), List.class));
        assertTrue(json.has("executionTime"));

        JsonNode r0 = json.get("results").get(0);
        assertEquals("plain", r0.get("a").asText());
        assertEquals("5", r0.get("b").asText());
        assertEquals("hola", r0.get("c").asText());
        assertTrue(r0.get("d").isTextual());
        assertEquals("", r0.get("d").asText());
        assertEquals("http://e/x", r0.get("f").asText());
        JsonNode r1 = json.get("results").get(1);
        assertEquals("second", r1.get("a").asText());
        assertFalse(r1.has("b"));
        assertFalse(r1.has("f"));
        for (JsonNode field : r0) {
            assertTrue(field.isTextual());
        }

        JsonNode b0 = json.get("bindings").get(0);
        assertEquals("literal", b0.get("a").get("type").asText());
        assertEquals("plain", b0.get("a").get("value").asText());
        assertFalse(b0.get("a").has("datatype"));
        assertFalse(b0.get("a").has("xml:lang"));
        assertEquals("literal", b0.get("b").get("type").asText());
        assertEquals("5", b0.get("b").get("value").asText());
        assertEquals(XSD.INTEGER.stringValue(), b0.get("b").get("datatype").asText());
        assertEquals("es", b0.get("c").get("xml:lang").asText());
        assertFalse(b0.get("c").has("datatype"));
        assertEquals("", b0.get("d").get("value").asText());
        assertEquals("bnode", b0.get("e").get("type").asText());
        assertEquals("n1", b0.get("e").get("value").asText());
        assertEquals("uri", b0.get("f").get("type").asText());
        JsonNode b1 = json.get("bindings").get(1);
        assertFalse(b1.has("b"));
        assertEquals(1, b1.size());
    }

    @Test
    void lexicalFormIsPreserved() throws Exception {
        List<BindingSet> rows = List.of(row("n", vf.createLiteral("007", XSD.INTEGER),
                "d", vf.createLiteral("2024-01-01", XSD.DATE)));
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt())).thenReturn(
                new SparqlUserQueryResult.Select(List.of("n", "d"), rows));
        JsonNode json = body(run("SELECT * WHERE {?s ?p ?o}", null, null));
        assertEquals("007", json.get("bindings").get(0).get("n").get("value").asText());
        assertEquals("007", json.get("results").get(0).get("n").asText());
        assertEquals(XSD.DATE.stringValue(), json.get("bindings").get(0).get("d").get("datatype").asText());
    }

    @Test
    void askReturnsBooleanShape() throws Exception {
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt())).thenReturn(new SparqlUserQueryResult.Ask(true));
        JsonNode json = body(run("ASK {?s ?p ?o}", null, null));
        assertEquals("boolean", json.get("type").asText());
        assertTrue(json.get("boolean").asBoolean());
        assertTrue(json.has("executionTime"));
        assertFalse(json.has("results"));
    }

    private List<Statement> statements(int n) {
        List<Statement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(vf.createStatement(vf.createIRI("http://e/s" + i), vf.createIRI("http://e/p"),
                    vf.createLiteral("v\"" + i)));
        }
        return out;
    }

    @Test
    void graphFormatsAreNegotiatedAndParseable() throws Exception {
        Map<String, String> ns = new LinkedHashMap<>();
        ns.put("e", "http://e/");
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenReturn(new SparqlUserQueryResult.Graph(statements(4), ns, false));
        Object[][] cases = {
                {"turtle", "turtle", RDFFormat.TURTLE}, {"jsonld", "jsonld", RDFFormat.JSONLD},
                {"rdfxml", "rdfxml", RDFFormat.RDFXML}, {"ntriples", "ntriples", RDFFormat.NTRIPLES}};
        for (Object[] c : cases) {
            ResponseEntity<?> response = run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", (String) c[0], null);
            JsonNode json = body(response);
            assertEquals("graph", json.get("type").asText());
            assertEquals(c[1], json.get("format").asText());
            assertEquals(4, json.get("tripleCount").asInt());
            assertFalse(json.get("truncated").asBoolean());
            assertNull(response.getHeaders().getFirst("X-Ontocode-Truncated"));
            assertEquals(4, Rio.parse(new StringReader(json.get("content").asText()), "http://b/", (RDFFormat) c[2]).size());
        }
        JsonNode defaulted = body(run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", null, null));
        assertEquals("turtle", defaulted.get("format").asText());
        JsonNode accepted = body(run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", null, "application/ld+json"));
        assertEquals("jsonld", accepted.get("format").asText());
        JsonNode bodyWins = body(run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", "ntriples", "application/ld+json"));
        assertEquals("ntriples", bodyWins.get("format").asText());
    }

    @Test
    void truncatedGraphSetsFlagAndHeader() throws Exception {
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenReturn(new SparqlUserQueryResult.Graph(statements(3), Map.of(), true));
        ResponseEntity<?> response = run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", "turtle", null);
        JsonNode json = body(response);
        assertTrue(json.get("truncated").asBoolean());
        assertEquals("true", response.getHeaders().getFirst("X-Ontocode-Truncated"));
        assertEquals(3, Rio.parse(new StringReader(json.get("content").asText()), "http://b/", RDFFormat.TURTLE).size());
    }

    @Test
    void unknownFormatIsAPlainBadRequest() throws Exception {
        ResponseEntity<?> response = run("CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}", "csv", null);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(body(response).get("error").asText().contains("Unsupported format"));
    }

    @Test
    void rejectionsBecomeBadRequestWithTheMessage() throws Exception {
        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenThrow(new SparqlQueryRejectedException("FROM and FROM NAMED are not supported; queries run against the project you selected."));
        ResponseEntity<?> response = run("SELECT * FROM <http://x> WHERE {?s ?p ?o}", null, null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("FROM and FROM NAMED are not supported; queries run against the project you selected.",
                body(response).get("error").asText());
    }

    @Test
    void emptyQueryIsBadRequest() {
        assertEquals(400, run("  ", null, null).getStatusCode().value());
    }
}
