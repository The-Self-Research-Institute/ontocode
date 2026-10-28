package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.controller.SparqlQueryController;
import self.research.ontology.owlEditor.repository.SparqlQueryRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SparqlResultTruncationTest {

    private static final String G = "http://example.org/g";
    private static final String P = "http://example.org/p";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repo;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            for (int i = 0; i < 100; i++) {
                c.add(vf.createIRI("http://example.org/s" + i), vf.createIRI(P), vf.createLiteral("value-" + i),
                        vf.createIRI(G));
            }
        }
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private SparqlUserQueryResult.Select select(SparqlResultLimits limits) {
        try (RepositoryConnection c = repo.getConnection()) {
            SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(
                    "SELECT ?s ?o WHERE { ?s <" + P + "> ?o }", Set.of(G));
            return (SparqlUserQueryResult.Select) SparqlUserQueryRunner.run(c, checked, List.of(G), 1000, limits);
        }
    }

    @Test
    void theRowCapTruncatesAndFlagsIt() {
        SparqlUserQueryResult.Select result = select(new SparqlResultLimits(10, 1L << 30));
        assertEquals(10, result.rows().size());
        assertTrue(result.truncated());
    }

    @Test
    void theByteCapTruncatesBeforeTheRowCap() {
        SparqlUserQueryResult.Select result = select(new SparqlResultLimits(1000, 2000));
        assertTrue(result.truncated());
        assertTrue(result.rows().size() > 0 && result.rows().size() < 100, "rows " + result.rows().size());
    }

    @Test
    void underTheLimitsNothingIsTruncated() {
        SparqlUserQueryResult.Select result = select(SparqlResultLimits.defaults());
        assertEquals(100, result.rows().size());
        assertFalse(result.truncated());
    }

    @Test
    void theCounterChargesRealValueSizes() {
        MapBindingSet small = new MapBindingSet();
        small.addBinding("a", vf.createLiteral("x"));
        MapBindingSet large = new MapBindingSet();
        large.addBinding("a", vf.createLiteral("x".repeat(1000)));
        SparqlResultLimits.Counter counter = new SparqlResultLimits(10, 1L << 30).counter();
        assertTrue(counter.tryAdd(small));
        long afterSmall = counter.bytes();
        assertTrue(counter.tryAdd(large));
        assertTrue(counter.bytes() - afterSmall > 2000);
    }

    @Test
    void theControllerAddsTheFlagAndHeaderOnlyWhenTruncatedAndKeepsTheLegacyShape() throws Exception {
        SparqlDatasetService dataset = mock(SparqlDatasetService.class);
        SparqlQueryController controller = new SparqlQueryController(dataset, mock(SimpMessagingTemplate.class),
                mock(SparqlQueryRepository.class), mock(OntologyMutationService.class));
        MapBindingSet row = new MapBindingSet();
        row.addBinding("a", vf.createLiteral("one"));
        List<BindingSet> rows = List.of(row);
        ObjectMapper mapper = new ObjectMapper();

        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenReturn(new SparqlUserQueryResult.Select(List.of("a"), rows, true));
        ResponseEntity<?> truncated = controller.query("proj", new SparqlQueryController.SparqlRequest("SELECT * {}"), null);
        JsonNode body = mapper.readTree(mapper.writeValueAsString(truncated.getBody()));
        assertEquals("true", truncated.getHeaders().getFirst(SparqlQueryController.TRUNCATED_HEADER));
        assertTrue(body.get("truncated").asBoolean());
        assertEquals("one", body.get("results").get(0).get("a").asText());

        when(dataset.execUserQuery(eq("proj"), anyString(), anyInt()))
                .thenReturn(new SparqlUserQueryResult.Select(List.of("a"), rows));
        ResponseEntity<?> complete = controller.query("proj", new SparqlQueryController.SparqlRequest("SELECT * {}"), null);
        JsonNode plain = mapper.readTree(mapper.writeValueAsString(complete.getBody()));
        assertNull(complete.getHeaders().getFirst(SparqlQueryController.TRUNCATED_HEADER));
        assertFalse(plain.has("truncated") && !plain.get("truncated").isNull());
        assertEquals("one", plain.get("results").get(0).get("a").asText());
    }

    @Test
    void sparqlQueriesAreAdmittedWithTheConfiguredByteCap() {
        FakeMemorySignals signals = new FakeMemorySignals();
        var ledger = signals.ledger();
        HeavyWorkAdmission admission = new HeavyWorkAdmission(ledger,
                new self.research.ontology.common.admission.Estimates().withSparqlRowsBytesCap(40L * FakeMemorySignals.MIB));
        try (HeavyWorkAdmission.Hold hold = admission.admitSparql("u1")) {
            assertEquals(40L * FakeMemorySignals.MIB, hold.bytes());
        }
        assertEquals(0, ledger.reservedBytes());
    }
}
